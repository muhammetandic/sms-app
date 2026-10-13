package com.anttech.smsapp.data

import com.anttech.smsapp.data.local.AddressNormalizer
import com.anttech.smsapp.data.local.ConversationDao
import com.anttech.smsapp.data.local.DraftDao
import com.anttech.smsapp.data.local.DraftEntity
import com.anttech.smsapp.data.local.DraftConversationSummary
import com.anttech.smsapp.data.local.MessageDao
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageEntity
import com.anttech.smsapp.data.local.MessageSearchHit
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.data.local.ftsMatchExpression
import com.anttech.smsapp.data.telephony.ProviderMessageMapper
import com.anttech.smsapp.data.telephony.ProviderRowRef
import com.anttech.smsapp.data.telephony.TelephonyMessageWriter
import com.anttech.smsapp.data.telephony.NoopTelephonyMessageWriter
import com.anttech.smsapp.sms.receive.InboundMessage
import com.anttech.smsapp.sms.receive.InboundMessageKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    /**
     * Conversation state, shared with the surfaces that work in whole
     * conversations rather than messages. Exposed so the graph hands every
     * caller the same instance the message path writes through: a thread and the
     * inbox that lists it must agree on the read count and last-message time.
     */
    val conversations: ConversationRepository,
    private val messageDao: MessageDao,
    private val draftDao: DraftDao,
    /** Mirrors local mutations back to the platform provider. Test default is a no-op. */
    private val telephonyWriter: TelephonyMessageWriter = NoopTelephonyMessageWriter(),
) {

    /**
     * Builds the conversation store over the given DAOs. Convenience for callers
     * that already hold them: tests construct a store from a database's DAOs and
     * do not care how the two pieces are wired together.
     */
    constructor(
        conversationDao: ConversationDao,
        messageDao: MessageDao,
        draftDao: DraftDao,
        telephonyWriter: TelephonyMessageWriter = NoopTelephonyMessageWriter(),
    ) : this(
        ConversationRepository(conversationDao, messageDao, telephonyWriter),
        messageDao,
        draftDao,
        telephonyWriter,
    )

    fun observeDraftConversations(): Flow<List<DraftConversationSummary>> =
        draftDao.observeDraftConversations()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.observeMessages(conversationId)

    /**
     * Deletes a single message, keeping the derived conversation state honest.
     *
     * The row itself is removed here; the conversation's own corrections - an
     * incoming message taking one off the unread count, and a conversation with
     * no messages left being dropped rather than shown as a permanent empty row -
     * are [ConversationRepository.onMessageRemoved]'s, because the inbox and the
     * spam folder are what read them.
     *
     * The provider copy is mirrored first: the provider row is only reachable
     * while the local row still carries its id. Rows the provider refused to
     * store have none and are skipped. The conversation is only removed when it
     * is empty, which is why this is not a plain foreign key: the cascade on
     * `messages` runs the other way.
     */
    suspend fun deleteMessage(messageId: Long) {
        val message = messageDao.getById(messageId) ?: return
        message.providerId?.let { telephonyWriter.delete(listOf(ProviderRowRef(it))) }
        val removed = messageDao.delete(messageId)
        if (removed == 0) return

        conversations.onMessageRemoved(
            conversationId = message.conversationId,
            wasIncoming = message.direction == MessageDirection.INCOMING,
        )
    }

    /**
     * Persists one inbound message, ignoring it if the same message was already
     * stored. Returns the row id, or null when the message was a duplicate.
     */
    suspend fun recordInbound(message: InboundMessage, partCount: Int): Long? {
        val key = InboundMessageKey.of(message)
        val conversationId = conversations.findOrCreateConversation(message.address, message.timestampMillis)
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

        conversations.bumpUnread(conversationId)
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
        val conversationId = conversations.findOrCreateConversation(address, sentAt)
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
            conversations.conversationIdForMembers(members)?.let {
                saveDraft(it, "")
                return it
            }
            return null
        }
        val conversationId = conversations.findOrCreateConversation(members, System.currentTimeMillis())
        // A standalone compose session owns its own draft row: a contact may
        // have several independent unsent messages, which the drafts screen
        // lists separately. Editing one of them passes its id above instead.
        draftDao.save(DraftEntity(conversationId = conversationId, body = body))
        return conversationId
    }

    suspend fun deleteDraft(draftId: Long) {
        val draft = draftDao.byId(draftId) ?: return
        draftDao.deleteById(draftId)
        conversations.deleteIfEmpty(draft.conversationId)
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

    private companion object {
        /**
         * Anything SENDING for at least this long has passed every window in
         * which a radio callback can arrive (the platform abandons per-part
         * results after a short period), so it is safe to fail at startup.
         */
        const val STUCK_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
