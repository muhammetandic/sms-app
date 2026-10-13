package com.anttech.smsapp.data

import androidx.room.withTransaction
import com.anttech.smsapp.data.local.AddressNormalizer
import com.anttech.smsapp.data.local.ConversationDao
import com.anttech.smsapp.data.local.ConversationEntity
import com.anttech.smsapp.data.local.ImportStateEntity
import com.anttech.smsapp.data.local.MessageDao
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageEntity
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.data.telephony.ProviderMessage
import com.anttech.smsapp.data.telephony.ProviderMessageMapper
import com.anttech.smsapp.sms.receive.InboundMessageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backfills the local store from the Telephony provider's history.
 *
 * The provider is the system store of every message on the device, so a user who
 * switches to this app expects their history. This is the one writer of the
 * local store that does not run in response to a live event, and its own concern:
 * it owns the import's high-water mark and the row-mapping rules, and is driven
 * by [com.anttech.smsapp.data.telephony.ProviderImportCoordinator].
 */
class ProviderImportRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    /**
     * The Room database. Needed beyond the DAOs so a history-sized import can run
     * as one transaction, and so the import's high-water mark lives beside the
     * rows it describes.
     */
    private val database: SmsDatabase,
) {

    /**
     * Imports messages that predate this app becoming the default SMS app.
     *
     * Rows carry a dedupe key prefixed with the provider id and insert with
     * `IGNORE`, so a row already linked to a provider id is skipped. The whole
     * batch runs as one transaction (one commit, one wake of the observing
     * flows); callers avoid calling it at all when nothing new has arrived.
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
     * Unlike the live path's find-or-create, this records the most recent
     * timestamp seen for the conversation in [raisedTimestamps] instead of
     * writing it per row; [importRows] applies the recorded value once at the
     * end.
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
}
