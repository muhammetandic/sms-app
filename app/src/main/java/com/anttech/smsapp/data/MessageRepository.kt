package com.anttech.smsapp.data

import androidx.room.withTransaction
import com.anttech.smsapp.data.local.AddressNormalizer
import com.anttech.smsapp.data.local.ConversationDao
import com.anttech.smsapp.data.local.ConversationEntity
import com.anttech.smsapp.data.local.ConversationSummary
import com.anttech.smsapp.data.local.DraftDao
import com.anttech.smsapp.data.local.DraftEntity
import com.anttech.smsapp.data.local.DraftConversationSummary
import com.anttech.smsapp.data.local.ImportStateEntity
import com.anttech.smsapp.data.local.MessageDao
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageEntity
import com.anttech.smsapp.data.local.MessageSearchHit
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.data.local.ftsMatchExpression
import com.anttech.smsapp.data.telephony.ProviderMessage
import com.anttech.smsapp.data.telephony.ProviderMessageMapper
import com.anttech.smsapp.data.telephony.ProviderRowRef
import com.anttech.smsapp.data.telephony.TelephonyMessageWriter
import com.anttech.smsapp.data.telephony.NoopTelephonyMessageWriter
import com.anttech.smsapp.sms.receive.InboundMessage
import com.anttech.smsapp.sms.receive.InboundMessageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Local, device-only message store.
 *
 * Holds messages that arrive through this app. The Telephony provider is a
 * separate concern handled by [com.anttech.smsapp.data.telephony]; this store is
 * always available regardless of whether the app holds the default SMS role.
 *
 * Destructive and read-state changes are mirrored back to the provider through
 * [telephonyWriter] for the messages this app can identify there, and the
 * receive and send paths mirror their own rows through it as they happen
 * ([mirrorInboundToProvider], [mirrorSentToProvider]), so the system SMS app
 * shows the same conversation this one does instead of an inbox that only ever
 * holds imported history.
 */
class MessageRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val draftDao: DraftDao,
    /** Mirrors local mutations back to the platform provider. Test default is a no-op. */
    private val telephonyWriter: TelephonyMessageWriter = NoopTelephonyMessageWriter(),
    /**
     * The Room database. Needed beyond the DAOs so a history-sized provider
     * import can run as one transaction, and so the import's high-water mark
     * lives beside the rows it describes.
     */
    private val database: SmsDatabase,
) {

    fun observeConversations(): Flow<List<ConversationSummary>> = conversationDao.observeConversations()

    fun observeSpamConversations(): Flow<List<ConversationSummary>> =
        conversationDao.observeSpamConversations()

    fun observeDraftConversations(): Flow<List<DraftConversationSummary>> =
        draftDao.observeDraftConversations()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.observeMessages(conversationId)

    /**
     * Marks a conversation read in the store and mirrors the change to every
     * provider message this app can identify in it, so the system inbox's badge
     * clears with this app's.
     */
    suspend fun markConversationRead(conversationId: Long) {
        telephonyWriter.markRead(messageDao.providerIdsInConversation(conversationId).toProviderRefs())
        conversationDao.markRead(conversationId)
    }

    /**
     * Marks a batch of conversations unread in the store and mirrors the change
     * to every provider message this app can identify in them, so the system
     * inbox's badge returns with this app's. The count is floored at one rather
     * than accumulated, matching [ConversationDao.markUnread].
     *
     * The provider ids are collected in one query and written back in one call,
     * rather than per conversation: selection mode can cover the whole list, and
     * a query plus a provider write per row is the difference between a tap and
     * a stall.
     */
    suspend fun setConversationsUnread(ids: Set<Long>) {
        if (ids.isEmpty()) return
        telephonyWriter.markUnread(messageDao.providerIdsInConversations(ids.toList()).toProviderRefs())
        conversationDao.markUnread(ids.toList())
    }

    /**
     * Moves a conversation into or out of the spam folder.
     *
     * Messages are kept rather than deleted so the folder is viewable and the
     * move is reversible; only the inbox/spam split changes.
     */
    suspend fun setConversationSpam(conversationId: Long, isSpam: Boolean) =
        conversationDao.setSpam(conversationId, isSpam)

    /** Whether a conversation is in the spam folder, so its arrivals stay quiet. */
    suspend fun isSpam(conversationId: Long): Boolean = conversationDao.isSpam(conversationId) == true

    /** Mutes or unmutes a conversation, silencing only its incoming alerts. */
    suspend fun setConversationMuted(conversationId: Long, isMuted: Boolean) =
        conversationDao.setMuted(conversationId, isMuted)

    /** Whether a conversation is muted, so its arrivals stay quiet. */
    suspend fun isMuted(conversationId: Long): Boolean = conversationDao.isMuted(conversationId) == true

    /**
     * Deletes a single message, keeping the derived conversation state honest.
     *
     * Three things have to be corrected alongside the row itself, and each was a
     * real bug found on the emulator when only the row was deleted:
     *
     * - An incoming message takes one off the unread count, or the inbox keeps
     *   advertising a message that is no longer there.
     * - A conversation with no messages left is deleted, or the inbox shows a
     *   permanent empty row with no snippet.
     * - The snippet comes from a subquery over the messages table, so it follows
     *   the delete on its own; only the stored counters need writing.
     *
     * The conversation is only removed when it is empty, which is why this is not
     * a plain foreign key: the cascade on `messages` runs the other way.
     */
    suspend fun deleteMessage(messageId: Long) {
        val message = messageDao.getById(messageId) ?: return
        // Mirror before removing locally: the provider row is only reachable
        // while the local row still carries its id. Rows the provider refused
        // to store have none and are skipped.
        message.providerId?.let { telephonyWriter.delete(listOf(ProviderRowRef(it))) }
        val conversationId = message.conversationId
        val removed = messageDao.delete(messageId)
        if (removed == 0) return

        if (messageDao.countInConversation(conversationId) == 0) {
            conversationDao.delete(conversationId)
            return
        }

        // Only incoming messages are ever counted unread, so an outgoing delete
        // must leave the counter alone. MAX keeps it from going negative if the
        // count was already out of step with the messages present.
        if (message.direction == MessageDirection.INCOMING) {
            conversationDao.decrementUnread(conversationId)
        }
    }

    /**
     * Deletes a whole conversation from the store.
     *
     * The messages table cascades on the conversation id, so removing the row
     * also removes every message it holds. The inbox and the spam folder both
     * read this table, so the row disappears from whichever list showed it.
     * The provider rows behind any imported or sent messages are deleted first.
     *
     * [deleteMessage] is preferred when only one message is unwanted: this path
     * discards the conversation's unread state too, so an inbox badge can
     * disappear together with the message the user is dismissing.
     */
    suspend fun deleteConversation(conversationId: Long) {
        telephonyWriter.delete(messageDao.providerIdsInConversation(conversationId).toProviderRefs())
        conversationDao.delete(conversationId)
    }

    /**
     * Pins or unpins a batch of conversations so they stay at the top of the
     * inbox. A no-op for an empty list, because room rejects an empty `IN` list.
     */
    suspend fun setConversationsPinned(ids: Collection<Long>, isPinned: Boolean) {
        if (ids.isEmpty()) return
        conversationDao.setPinned(ids.toList(), isPinned)
    }

    /**
     * Moves a batch of conversations into or out of the spam folder, keeping
     * the same semantics as a single move: unread counts are cleared in the
     * same write so the spam folder never shows a badge for the move itself.
     */
    suspend fun setConversationsSpam(ids: Collection<Long>, isSpam: Boolean) {
        if (ids.isEmpty()) return
        conversationDao.setSpam(ids.toList(), isSpam)
    }

    /**
     * Deletes a batch of conversations and every message they hold, from
     * whatever list (inbox or spam) currently shows them.
     */
    suspend fun deleteConversations(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        telephonyWriter.delete(messageDao.providerIdsInConversations(ids.toList()).toProviderRefs())
        conversationDao.delete(ids.toList())
    }

    /** Conversation id for a message row, needed to target a notification action. */
    suspend fun conversationIdForMessage(messageId: Long): Long? = messageDao.getById(messageId)?.conversationId

    /**
     * The provider rows behind a batch of local provider ids, for write-back.
     *
     * The ids come from a query that already dropped the nulls, so every one
     * here is a provider row this app can write to.
     */
    private fun List<Long>.toProviderRefs(): List<ProviderRowRef> = map(::ProviderRowRef)

    /** Resolves the conversation for a sender, used to deep-link a notification. */
    suspend fun conversationIdFor(address: String): Long? =
        conversationDao.getByAddressKey(AddressNormalizer.conversationKey(address))?.id

    /**
     * Address to reply to, for a conversation already in the store.
     *
     * A notification tap carries a conversation id but no address, and sending to
     * the wrong address is not a recoverable mistake, so the thread screen refuses
     * to send until this resolves.
     */
    suspend fun recipientFor(conversationId: Long): String? =
        conversationDao.getById(conversationId)?.displayAddress?.takeIf { it.isNotBlank() }

    /**
     * Imports messages that predate this app becoming the default SMS app.
     *
     * The provider is the system store of every message on the device, so a user
     * who switches to this app expects their history. Rows carry a dedupe key
     * prefixed with the provider id and insert with `IGNORE`, so a row already
     * linked to a provider id is skipped. The whole batch runs as one
     * transaction (one commit, one wake of the observing flows); callers avoid
     * calling it at all when nothing new has arrived.
     *
     * Returns the number of rows actually inserted.
     */
    suspend fun importFromProvider(messages: List<ProviderMessage>): Int =
        withContext(Dispatchers.IO) {
            database.withTransaction { importRows(messages) }
        }

    /**
     * The highest provider `_id` whose batch has been fully processed, or null
     * before the first import. Always advances, even for rows the import skips,
     * so a run that finds nothing new can skip the provider read entirely.
     */
    suspend fun providerImportWatermark(): Long? = database.importStateDao().providerHighWater()

    /** Records that everything up to [providerId] has been processed. */
    suspend fun recordProviderImportWatermark(providerId: Long) {
        database.importStateDao().save(ImportStateEntity(providerHighWater = providerId))
    }

    /**
     * The import loop itself, inside the caller's transaction.
     *
     * Run as a single transaction so a history-sized backfill commits once
     * instead of once per row (each autocommit forces a disk sync and wakes
     * every observing flow). Conversation timestamps are applied once per
     * touched conversation afterwards rather than per row: provider rows arrive
     * oldest-first, so raising the timestamp on most rows would rewrite the row
     * for nearly every message.
     */
    private suspend fun importRows(messages: List<ProviderMessage>): Int {
        var inserted = 0
        val raisedTimestamps = mutableMapOf<Long, Long>()

        for (message in messages) {
            val mapped = ProviderMessageMapper.map(message) ?: continue
            // A row the live path already mirrored carries its provider id, so
            // importing it again would file a second copy. The provider_id index
            // keeps this per-row lookup out of a full table scan.
            if (messageDao.idForProvider(mapped.providerId) != null) continue
            val conversationId = importConversationFor(mapped.address, mapped.sentAt, raisedTimestamps)
            // Incoming provider rows share the live path's address/date/body key,
            // so a message this app already received through SMS_DELIVER is
            // skipped instead of stored a second time under a provider key.
            val dedupeKey = if (mapped.direction == MessageDirection.INCOMING) {
                InboundMessageKey.of(mapped.address, mapped.sentAt, mapped.body).hash
            } else {
                ProviderMessageMapper.dedupeKey(mapped.providerId)
            }
            val rowId = messageDao.insert(
                MessageEntity(
                    conversationId = conversationId,
                    dedupeKey = dedupeKey,
                    body = mapped.body,
                    sentAt = mapped.sentAt,
                    direction = mapped.direction,
                    status = mapped.status,
                    providerId = mapped.providerId,
                ),
            )
            // IGNORE returns -1 for a row that already exists.
            if (rowId != -1L) inserted += 1
        }

        raisedTimestamps.forEach { (conversationId, lastMessageAt) ->
            conversationDao.updateLastMessageAt(conversationId, lastMessageAt)
        }
        return inserted
    }

    /**
     * Resolves or creates the conversation for an imported row, deferring the
     * last-message bump.
     *
     * Unlike [findOrCreateConversation], this records the most recent timestamp
     * seen for the conversation in [raisedTimestamps] instead of writing it per
     * row; [importRows] applies the recorded value once at the end.
     */
    private suspend fun importConversationFor(
        address: String,
        sentAt: Long,
        raisedTimestamps: MutableMap<Long, Long>,
    ): Long {
        // Same grouping as the live path (a single member's group key is its own
        // conversationKey), so an imported row and a live row for the same
        // number can never land in two conversations.
        val display = address.trim()
        val key = AddressNormalizer.groupKey(listOf(display))
        conversationDao.getByAddressKey(key)?.let { existing ->
            val newest = maxOf(raisedTimestamps[existing.id] ?: existing.lastMessageAt, sentAt)
            if (newest > existing.lastMessageAt) {
                raisedTimestamps[existing.id] = newest
            }
            return existing.id
        }
        return conversationDao.insert(
            ConversationEntity(
                addressKey = key,
                displayAddress = display,
                lastMessageAt = sentAt,
            ),
        )
    }

    /**
     * Persists one inbound message, ignoring it if the same message was already
     * stored. Returns the row id, or null when the message was a duplicate.
     */
    suspend fun recordInbound(message: InboundMessage, partCount: Int): Long? {
        val key = InboundMessageKey.of(message)
        val conversationId = findOrCreateConversation(message.address, message.timestampMillis)
        val rowId = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                dedupeKey = key.hash,
                body = message.body,
                sentAt = message.timestampMillis,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                partCount = partCount,
            ),
        )
        if (rowId == -1L) return null

        bumpUnread(conversationId)
        return rowId
    }

    /**
     * Persists an outgoing message in the SENDING state, to be updated on callback.
     */
    suspend fun recordOutgoing(
        address: String,
        body: String,
        sentAt: Long,
        simSlot: Int? = null,
        partCount: Int = 1,
    ): Long {
        val conversationId = findOrCreateConversation(address, sentAt)
        return messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                // Sending the same text twice is two real messages, so outgoing rows
                // carry a nonce and never collide on the unique dedupe index.
                dedupeKey = "out:$sentAt:$address:$body:${UUID.randomUUID()}",
                body = body,
                sentAt = sentAt,
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENDING,
                simSlot = simSlot,
                // Kept so a result that arrives in a new process (the tracker is
                // in-memory and died with the old one) can rebuild the part count.
                partCount = partCount,
            ),
        )
    }

    suspend fun updateOutgoingStatus(id: Long, status: MessageStatus, providerId: Long? = null) {
        messageDao.updateStatus(id, status, providerId)
        if (status == MessageStatus.FAILED) mirrorFailedSend(id)
    }

    /** Marks a message delivered with the moment its report arrived. */
    suspend fun updateOutgoingDelivery(id: Long, deliveredAt: Long) =
        messageDao.updateDelivery(id, deliveredAt)

    /** Marks a message failed with the platform code behind the failure. */
    suspend fun updateOutgoingError(id: Long, errorCode: Int) {
        messageDao.updateError(id, errorCode)
        mirrorFailedSend(id)
    }

    /**
     * Mirrors a received message into the provider inbox and links the local
     * row to it.
     *
     * The platform hands the default app the parts over SMS_DELIVER but writes
     * no inbox row of its own, so every other reader of the system store - the
     * dialer's contact preview, a backup app, the app that held the role before
     * this one - only sees a message this app put there. The local row keeps
     * its address/date/body key (that is what a later import fingerprints
     * against) and gains the provider id, which turns on the delete and
     * read-state mirroring above.
     *
     * Returns the provider row id, or null when the write was refused.
     */
    suspend fun mirrorInboundToProvider(rowId: Long, message: InboundMessage): Long? {
        val providerId = telephonyWriter.insertInbound(message) ?: return null
        messageDao.updateProviderId(rowId, providerId)
        return providerId
    }

    /**
     * Mirrors an accepted outgoing message into the provider's sent table.
     *
     * The default app is responsible for writing its own sends (the platform
     * only writes them for apps that are not the default), and the local row
     * adopts the provider's own dedupe key so the next import recognises what
     * it just wrote rather than adding a second row for the same send.
     */
    suspend fun mirrorSentToProvider(rowId: Long, address: String, body: String, sentAt: Long) {
        val providerId = telephonyWriter.insertSent(address, body, sentAt) ?: return
        messageDao.adoptSentProviderRow(
            id = rowId,
            providerId = providerId,
            dedupeKey = ProviderMessageMapper.dedupeKey(providerId),
        )
    }

    /**
     * Flips the provider copy of a send the radio turned down, so the system
     * app stops showing it as sent. Rows without a provider copy (the write was
     * refused, or it never ran because the send failed before acceptance) have
     * nothing to flip.
     */
    private suspend fun mirrorFailedSend(id: Long) {
        val row = messageDao.getById(id) ?: return
        val providerId = row.providerId ?: return
        telephonyWriter.markSentFailed(ProviderRowRef(providerId))
    }

    /**
     * Stores the split an outgoing message was really sent as, so a result that
     * arrives in a new process (the in-memory tracker died with the old one)
     * can rebuild an aggregator for every part instead of resolving on the
     * first report that shows up.
     */
    suspend fun updateOutgoingPartCount(id: Long, partCount: Int) =
        messageDao.updatePartCount(id, partCount)

    /** One message row, for retrying or inspecting a single message. */
    suspend fun getMessage(id: Long): MessageEntity? = messageDao.getById(id)

    /** Messages and conversations whose body or address matches, newest first. */
    fun observeMessageSearch(query: String): Flow<List<MessageSearchHit>> =
        messageDao.searchMessages(ftsMatchExpression(query), query)

    /** The stored draft for a conversation, or null when it has none. */
    fun observeDraft(conversationId: Long): Flow<String?> =
        draftDao.observe(conversationId).map { it.firstOrNull() }

    /**
     * Keeps the composed-but-unsent text for a conversation across restarts.
     * Blanking the text clears the stored body.
     */
    suspend fun saveDraft(conversationId: Long, body: String) {
        val normalized = if (body.isBlank()) "" else body
        if (draftDao.updateBody(conversationId, normalized) == 0 && normalized.isNotEmpty()) {
            draftDao.save(DraftEntity(conversationId = conversationId, body = normalized))
        }
        if (normalized.isEmpty()) {
            draftDao.deleteIfEmpty(conversationId)
        }
    }

    /** Creates or reuses a conversation so the standalone composer can keep a draft. */
    suspend fun saveDraftForRecipient(recipient: String, body: String, draftId: Long? = null): Long? {
        val members = AddressNormalizer.membersOf(recipient)
        if (members.isEmpty()) return null
        if (draftId != null) {
            if (body.isBlank()) draftDao.deleteById(draftId)
            else draftDao.updateBodyById(draftId, body)
            return draftId
        }
        if (body.isBlank()) {
            conversationDao.getByAddressKey(AddressNormalizer.groupKey(members))?.let {
                saveDraft(it.id, "")
                return it.id
            }
            return null
        }
        val conversationId = findOrCreateConversation(members, System.currentTimeMillis())
        // A standalone compose session owns its own draft row: a contact may
        // have several independent unsent messages, which the drafts screen
        // lists separately. Editing one of them passes its id above instead.
        draftDao.save(DraftEntity(conversationId = conversationId, body = body))
        return conversationId
    }

    suspend fun deleteDraft(draftId: Long) {
        val draft = draftDao.byId(draftId) ?: return
        draftDao.deleteById(draftId)
        if (messageDao.countInConversation(draft.conversationId) == 0) {
            conversationDao.delete(draft.conversationId)
        }
    }

    /**
     * Reconcilies rows still awaiting a send callback.
     *
     * A send that was persisted just before the process died has a result
     * intent that the manifest-declared receiver still delivers, so it should
     * not be failed here; only rows whose callback could never arrive (they
     * have been SENDING for longer than the platform keeps radio callbacks)
     * are failed, so a genuinely in-flight message is left alone.
     */
    suspend fun resolveStuckOutgoing(beforeMillis: Long = startOfStuckWindow()) =
        messageDao.resolvePendingOutgoing(MessageStatus.FAILED, beforeMillis)

    /** Marks a still-SENDING row sent (e.g. when the process was killed before the callback). */
    suspend fun markOutgoingSent(id: Long) = messageDao.markIfSending(id, MessageStatus.SENT)

    /** Marks a still-SENDING row failed with the platform's error code. */
    suspend fun failOutgoing(id: Long, errorCode: Int) = messageDao.failIfSending(id, errorCode)

    /** The number of parts the outgoing row was persisted with, or null when unknown. */
    suspend fun messagePartCount(id: Long): Int? = messageDao.getById(id)?.partCount

    /**
     * Anything SENDING for at least this long has passed every window in which
     * a radio callback can arrive (the platform abandons per-part results after
     * a short time), so it is safe to fail at startup.
     */
    private fun startOfStuckWindow(): Long = System.currentTimeMillis() - STUCK_WINDOW_MILLIS

    private suspend fun findOrCreateConversation(address: String, timestamp: Long): Long =
        findOrCreateConversation(listOf(address), timestamp)

    /**
     * Resolves or creates the conversation for a participant set.
     *
     * A single member shares the plain-SMS conversation; two or more become a
     * group keyed on the sorted normalized members. The display address is the
     * member list as the user typed it, which is what the thread and the
     * notification show.
     */
    private suspend fun findOrCreateConversation(members: List<String>, timestamp: Long): Long {
        val display = members.map { it.trim() }.filter { it.isNotBlank() }
        if (display.isEmpty()) error("Cannot create a conversation without any recipient")
        val key = AddressNormalizer.groupKey(display)

        conversationDao.getByAddressKey(key)?.let { existing ->
            if (timestamp > existing.lastMessageAt) {
                conversationDao.update(existing.copy(lastMessageAt = timestamp))
            }
            return existing.id
        }

        return conversationDao.insert(
            ConversationEntity(
                addressKey = key,
                displayAddress = display.joinToString(", "),
                lastMessageAt = timestamp,
            ),
        )
    }

    private suspend fun bumpUnread(conversationId: Long) {
        val conversation = conversationDao.getById(conversationId) ?: return
        conversationDao.update(conversation.copy(unreadCount = conversation.unreadCount + 1))
    }

    private companion object {
        /**
         * Anything SENDING for at least this long has passed every window in
         * which a radio callback can arrive (the platform abandons per-part
         * results after a short period), so it is safe to fail at startup.
         */
        const val STUCK_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
