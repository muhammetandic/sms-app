package com.anttech.smsapp.sync

import androidx.room.withTransaction
import com.anttech.smsapp.data.local.ConversationDao
import com.anttech.smsapp.data.local.ConversationEntity
import com.anttech.smsapp.data.local.MessageDao
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageEntity
import com.anttech.smsapp.data.local.MessageExportRow
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.data.local.SmsDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one sync attempt did, so a caller can show it and decide about a retry. */
sealed interface SyncOutcome {

    /** Reached Drive; merged [mergedCount] messages the store did not already hold. */
    data class Success(val mergedCount: Int) : SyncOutcome

    /** The user has not granted Drive access, or revoked it. */
    data object NeedsSignIn : SyncOutcome

    /** Sync is switched off; nothing was attempted. */
    data object Disabled : SyncOutcome

    /** The attempt failed; a later one may succeed. */
    data class Failed(val message: String?) : SyncOutcome
}

/**
 * Backs the local store up into the user's hidden Drive app-data folder, and
 * merges in whatever the user's other phones put there.
 *
 * The unit of exchange is one file per device, named for a locally generated
 * id. That choice is what makes this safe without any locking or coordination:
 * a device only ever writes its own file, so two phones syncing at the same
 * moment cannot overwrite each other, and the folder becomes a set of partial
 * snapshots that every device reads whole. A phone that is wiped and reinstalled
 * has a new id, so it writes a new file and reads the old one back - which is
 * exactly the restore case.
 *
 * Merge is a union keyed on the message `dedupe_key`, which the schema already
 * indexes uniquely and the provider import already relied on. So a merge is
 * order-independent and idempotent: syncing twice adds nothing the second time,
 * and a message that arrives from two phones at once lands once.
 *
 * What a sync does not do, and why:
 *  - Deletes and edits are not propagated. A merge that removed rows absent
 *    from a peer's file would delete every message on a device that synced while
 *    the peer was mid-upload. Propagating them needs a tombstone record the
 *    schema does not have yet, so this version is append-only.
 *  - Conversation flags (spam, pin, mute, archive) and unread counts stay local:
 *    they are where the user filed things on this phone, not facts about the
 *    messages.
 *  - Restored messages do not bump an unread badge. They are history the user
 *    has already lived through, and a wall of unread rows for it would be a lie.
 */
class SyncRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val database: SmsDatabase,
    private val settings: SyncSettings,
    private val drive: DriveClient,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Serialises attempts. The singleton repository is reachable both from the
     * periodic worker and from the settings screen's button, and two uploads of
     * the same file racing is pointless work even though it is harmless.
     */
    private val mutex = Mutex()

    suspend fun sync(): SyncOutcome = mutex.withLock {
        if (!settings.isEnabled()) return@withLock SyncOutcome.Disabled

        try {
            val merged = withContext(Dispatchers.IO) { exchange() }
            settings.recordSuccess(now(), merged)
            SyncOutcome.Success(merged)
        } catch (cancelled: CancellationException) {
            // A cancelled sync is not a failed one: the periodic worker or the
            // next launch will run it again, so it must not be recorded as an
            // error the user would see.
            throw cancelled
        } catch (signIn: SignInRequiredException) {
            settings.recordStatus(SyncStatus.NEEDS_SIGN_IN, signIn.message)
            SyncOutcome.NeedsSignIn
        } catch (error: Exception) {
            settings.recordStatus(SyncStatus.FAILED, error.message)
            SyncOutcome.Failed(error.message)
        }
    }

    /**
     * Pulls every file this device has not already read, then publishes this
     * device's own snapshot.
     *
     * Pulling first means the upload that follows already carries what the other
     * phones added, so a freshly restored phone reaches a steady state in one
     * pass instead of two.
     */
    private suspend fun exchange(): Int {
        val deviceId = settings.deviceId()
        val ownFileName = fileNameFor(deviceId)
        val knownVersions = settings.mergedVersions()

        var merged = 0
        var ownFileId: String? = null
        for (file in drive.listAppData()) {
            // This device's own file is written, never read: everything in it
            // came from here in the first place.
            if (file.name == ownFileName) {
                ownFileId = file.id
                continue
            }
            if (!file.name.startsWith(FILE_PREFIX)) continue
            if (knownVersions[file.name] == file.version) continue

            val inserted = mergeFrom(file)
            if (inserted != null) {
                settings.recordMergedVersion(file.name, file.version)
                merged += inserted
            }
        }

        val snapshot = SyncPayload.encode(deviceId, now(), messageDao.exportForSync())
        // The revision the upload returns is deliberately not recorded as a
        // cursor: the cursor map is for files this device *reads*, and this one
        // is skipped by name on every sync because everything in it came from
        // here. Recording it would put an entry in the map that no code reads.
        drive.upload(ownFileName, snapshot, ownFileId)
        return merged
    }

    /**
     * Downloads and merges one file, returning how many messages landed, or
     * null when the file could not be read and the cursor must not advance.
     *
     * A file that cannot be read leaves the cursor alone, so it is retried on
     * the next sync rather than being written off for good - a corrupt upload
     * today should not silently cost those messages forever.
     */
    private suspend fun mergeFrom(file: DriveFile): Int? {
        val records = try {
            SyncPayload.decode(drive.download(file.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            return null
        }
        return merge(records)
    }

    /** Inserts every record that is not already stored, returning how many landed. */
    private suspend fun merge(records: List<MessageExportRow>): Int = database.withTransaction {
        var inserted = 0
        // The conversation timestamp is raised once per conversation at the end
        // rather than once per message, so a 5,000-message history is one write
        // per thread instead of 5,000.
        val raisedTo = mutableMapOf<Long, Long>()

        for (record in records) {
            val conversationId = conversationFor(record, raisedTo)
            val rowId = messageDao.insert(
                MessageEntity(
                    conversationId = conversationId,
                    dedupeKey = record.dedupeKey,
                    body = record.body,
                    sentAt = record.sentAt,
                    direction = record.direction,
                    // A restored incoming message did arrive, so DELIVERED is
                    // true of it. A restored outgoing one must not claim a
                    // delivery report this phone never saw, so it gets SENT -
                    // the state it would have reached here without a radio.
                    status = if (record.direction == MessageDirection.INCOMING) {
                        MessageStatus.DELIVERED
                    } else {
                        MessageStatus.SENT
                    },
                    partCount = record.partCount,
                    // provider_id, delivered_at, error_code and sim_slot stay
                    // null: they describe what happened on the phone the message
                    // came from, and nothing here can reproduce them.
                ),
            )
            if (rowId != NO_ROW) inserted += 1
        }

        for ((conversationId, newest) in raisedTo) {
            conversationDao.updateLastMessageAt(conversationId, newest)
        }
        inserted
    }

    /**
     * Resolves the conversation a restored message belongs to, creating it when
     * the file brings a thread this phone has never seen.
     *
     * The shipped `address_key` is used as-is rather than re-derived from the
     * display address: the key is the identity the schema indexes and the
     * sending device already computed it, so recomputing it here could only
     * disagree. An existing row keeps its own display address and its flags
     * (the insert is ignored), because those are this phone's choices.
     */
    private suspend fun conversationFor(
        record: MessageExportRow,
        raisedTo: MutableMap<Long, Long>,
    ): Long {
        conversationDao.insertOrIgnore(
            ConversationEntity(
                addressKey = record.addressKey,
                displayAddress = record.displayAddress,
                lastMessageAt = record.sentAt,
            ),
        )
        val conversation = conversationDao.getByAddressKey(record.addressKey)
            ?: error("Conversation ${record.addressKey} missing immediately after insert")

        val newest = maxOf(raisedTo[conversation.id] ?: conversation.lastMessageAt, record.sentAt)
        if (newest > conversation.lastMessageAt) raisedTo[conversation.id] = newest
        return conversation.id
    }

    private fun fileNameFor(deviceId: String): String = "$FILE_PREFIX$deviceId.jsonl.gz"

    companion object {
        /**
         * Every file this app reads or writes starts with this, so anything else
         * that ends up in the folder is left alone rather than parsed.
         */
        const val FILE_PREFIX = "messages-"

        /** Room's `insert` returns this when the unique index rejected the row. */
        private const val NO_ROW = -1L
    }
}
