package com.oaktech.smsapp.data

import com.oaktech.smsapp.data.local.AddressNormalizer
import com.oaktech.smsapp.data.local.AttachmentDao
import com.oaktech.smsapp.data.local.AttachmentEntity
import com.oaktech.smsapp.data.local.AttachmentState
import com.oaktech.smsapp.data.local.ConversationDao
import com.oaktech.smsapp.data.local.ConversationEntity
import com.oaktech.smsapp.data.local.ConversationSummary
import com.oaktech.smsapp.data.local.DraftDao
import com.oaktech.smsapp.data.local.DraftEntity
import com.oaktech.smsapp.data.local.DownloadState
import com.oaktech.smsapp.data.local.MessageDao
import com.oaktech.smsapp.data.local.MessageDirection
import com.oaktech.smsapp.data.local.MessageEntity
import com.oaktech.smsapp.data.local.MessageSearchHit
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.telephony.ProviderMessage
import com.oaktech.smsapp.data.telephony.ProviderMessageMapper
import com.oaktech.smsapp.data.telephony.ProviderMms
import com.oaktech.smsapp.data.telephony.ProviderRowRef
import com.oaktech.smsapp.data.telephony.TelephonyMessageWriter
import com.oaktech.smsapp.data.telephony.NoopTelephonyMessageWriter
import com.oaktech.smsapp.sms.receive.InboundMessage
import com.oaktech.smsapp.sms.receive.InboundMessageKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID

/**
 * Local, device-only message store.
 *
 * Holds messages that arrive through this app. The Telephony provider is a
 * separate concern handled by [com.oaktech.smsapp.data.telephony]; this store is
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
    private val attachmentDao: AttachmentDao,
    /** Mirrors local mutations back to the platform provider. Test default is a no-op. */
    private val telephonyWriter: TelephonyMessageWriter = NoopTelephonyMessageWriter(),
) {

    fun observeConversations(): Flow<List<ConversationSummary>> = conversationDao.observeConversations()

    fun observeSpamConversations(): Flow<List<ConversationSummary>> =
        conversationDao.observeSpamConversations()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.observeMessages(conversationId)

    /**
     * Marks a conversation read in the store and mirrors the change to every
     * provider message this app can identify in it, so the system inbox's badge
     * clears with this app's.
     */
    suspend fun markConversationRead(conversationId: Long) {
        telephonyWriter.markRead(messageDao.getByConversation(conversationId).providerRefs())
        conversationDao.markRead(conversationId)
    }

    /**
     * Marks a batch of conversations unread in the store and mirrors the change
     * to every provider message this app can identify in them, so the system
     * inbox's badge returns with this app's. The count is floored at one rather
     * than accumulated, matching [ConversationDao.markUnread].
     */
    suspend fun setConversationsUnread(ids: Set<Long>) {
        for (id in ids) {
            telephonyWriter.markUnread(messageDao.getByConversation(id).providerRefs())
        }
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
        message.providerId?.let { telephonyWriter.delete(listOf(ProviderRowRef(it, message.isMms))) }
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
        telephonyWriter.delete(messageDao.getByConversation(conversationId).providerRefs())
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
        telephonyWriter.delete(messageDao.getByConversations(ids.toList()).providerRefs())
        conversationDao.delete(ids.toList())
    }

    /** Conversation id for a message row, needed to target a notification action. */
    suspend fun conversationIdForMessage(messageId: Long): Long? = messageDao.getById(messageId)?.conversationId

    /**
     * The provider rows behind a batch of local messages, for write-back.
     *
     * Only rows with a known provider id are mirrored; empty for rows the
     * provider refused to store, which have no identity to write back to.
     */
    private fun List<MessageEntity>.providerRefs(): List<ProviderRowRef> =
        mapNotNull { message -> message.providerId?.let { ProviderRowRef(it, message.isMms) } }

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
     * prefixed with the provider id and insert with `IGNORE`, so re-running this
     * on every launch costs a query and adds nothing.
     *
     * Returns the number of rows actually inserted.
     */
    suspend fun importFromProvider(messages: List<ProviderMessage>): Int {
        var inserted = 0

        for (message in messages) {
            val mapped = ProviderMessageMapper.map(message) ?: continue
            // A row the live path already mirrored carries its provider id, so
            // importing it again would file a second copy: an inbound MMS's live
            // key and its provider-import key are deliberately different, and
            // nothing else would collide them.
            if (messageDao.idForProvider(mapped.providerId, mapped.mms) != null) continue
            val conversationId = findOrCreateConversation(mapped.address, mapped.sentAt)
            // Incoming provider rows share the live path's address/date/body key,
            // so a message this app already received through SMS_DELIVER is
            // skipped instead of stored a second time under a provider key.
            val dedupeKey = when {
                // Incoming provider rows share the live path's address/date/body key,
                // so a message this app already received through SMS_DELIVER is
                // skipped instead of stored a second time under a provider key. MMS
                // gets its own namespace: the same text from the same sender at the
                // same time would otherwise collide with an identical SMS row.
                mapped.direction == MessageDirection.INCOMING && mapped.mms ->
                    "mms-import:${digest("${mapped.address}|${mapped.sentAt}|${mapped.body}")}"
                mapped.direction == MessageDirection.INCOMING ->
                    InboundMessageKey.of(mapped.address, mapped.sentAt, mapped.body).hash
                else -> ProviderMessageMapper.dedupeKey(mapped.providerId, mapped.mms)
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
                    isMms = mapped.mms,
                ),
            )
            // IGNORE returns -1 for a row that already exists.
            if (rowId != -1L) inserted += 1
        }

        return inserted
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
     * Persists one inbound MMS.
     *
     * The address is the sender (an MMS notification does not carry the whole
     * member list, so an incoming message lands in the sender's conversation
     * even when it was addressed to a group). Every attachment is stored as a
     * row; placeholders arrive with a null [AttachmentPart.filePath] and are
     * filled in later by a download.
     *
     * Returns the row id, or null when the transaction had already been stored.
     */
    suspend fun recordInboundMms(
        address: String,
        body: String,
        subject: String?,
        timestampMillis: Long,
        transactionId: String?,
        attachments: List<AttachmentPart>,
        downloadState: DownloadState = DownloadState.NONE,
        contentLocation: String? = null,
    ): Long? {
        val key = "mms:${digest("$address|$timestampMillis|${transactionId.orEmpty()}|$body")}"
        val conversationId = findOrCreateConversation(address, timestampMillis)
        val rowId = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                dedupeKey = key,
                body = body,
                sentAt = timestampMillis,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                isMms = true,
                subject = subject,
                downloadState = downloadState,
                transactionId = transactionId,
                contentLocation = contentLocation,
            ),
        )
        if (rowId == -1L) return null

        bumpUnread(conversationId)
        storeAttachments(rowId, attachments)
        return rowId
    }

    /**
     * Persists an outgoing MMS in the SENDING state.
     *
     * Unlike SMS there is no part callback: the platform does not report MMS
     * per-part, so the row is corrected once, from the outcome of the MMSC
     * transaction. The group conversation is keyed on the sorted member set,
     * but its display address keeps the order the user picked.
     */
    suspend fun recordOutgoingMms(
        recipients: List<String>,
        subject: String?,
        body: String,
        sentAt: Long,
        attachments: List<AttachmentPart>,
        simSlot: Int? = null,
    ): Long {
        val conversationId = findOrCreateConversation(recipients, sentAt)
        val rowId = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                dedupeKey = "mms-out:$sentAt:${recipients.joinToString("+")}:${UUID.randomUUID()}",
                body = body,
                sentAt = sentAt,
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENDING,
                partCount = 1,
                simSlot = simSlot,
                isMms = true,
                subject = subject,
            ),
        )
        storeAttachments(rowId, attachments)
        return rowId
    }

    private suspend fun storeAttachments(messageId: Long, attachments: List<AttachmentPart>) {
        if (attachments.isEmpty()) return
        attachmentDao.insertAll(
            attachments.mapIndexed { index, part ->
                AttachmentEntity(
                    messageId = messageId,
                    contentType = part.contentType,
                    fileName = part.fileName,
                    filePath = part.filePath,
                    byteSize = part.byteSize,
                    state = if (part.filePath == null) AttachmentState.RETRIEVABLE else AttachmentState.RETRIEVED,
                    partIndex = index,
                )
            },
        )
    }

    /** Persists an outgoing message in the SENDING state, to be updated on callback. */
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
     * Mirrors a received MMS into the provider inbox and links the local row to
     * it.
     *
     * The SMS mirror's contract one level up: the `pdu` row, its From address
     * and every part go in, and media bytes are streamed through the part URI
     * the provider mints. The local row keeps its `mms:` key (what a later
     * import fingerprints an already-stored message against) and gains the
     * provider id, which turns on the delete and read-state mirroring.
     *
     * Returns the provider row id, or null when the write was refused.
     */
    suspend fun mirrorInboundMmsToProvider(rowId: Long, message: ProviderMms): Long? {
        val providerId = telephonyWriter.insertInboundMms(message) ?: return null
        messageDao.updateProviderId(rowId, providerId)
        return providerId
    }

    /**
     * Mirrors an accepted outbound MMS into the provider's sent table.
     *
     * Like the SMS send mirror, the local row adopts the provider's namespaced
     * dedupe key so the next import recognises the send instead of adding a
     * second row.
     */
    suspend fun mirrorSentMmsToProvider(rowId: Long, message: ProviderMms) {
        val providerId = telephonyWriter.insertSentMms(message) ?: return
        messageDao.adoptSentProviderRow(
            id = rowId,
            providerId = providerId,
            dedupeKey = ProviderMessageMapper.dedupeKey(providerId, mms = true),
        )
    }

    /**
     * Flips the provider copy of a send the radio turned down, so the system
     * app stops showing it as sent. Rows without a provider copy (the write was
     * refused, or it never ran because the send failed before acceptance) and
     * MMS rows (only written to the provider once the MMSC accepts, so a failed
     * MMS never got one) have nothing to flip.
     */
    private suspend fun mirrorFailedSend(id: Long) {
        val row = messageDao.getById(id) ?: return
        val providerId = row.providerId ?: return
        if (row.isMms) return
        telephonyWriter.markSentFailed(ProviderRowRef(providerId, isMms = false))
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

    /**
     * The content a background retry of a failed outgoing MMS needs, read back
     * from the store because the worker may run in a fresh process with no
     * composer state. Returns null unless the row is still an outgoing, failed
     * MMS, so an attempt that races a manual resend or a delete is a no-op
     * rather than a duplicate send.
     */
    suspend fun outgoingMmsRetry(messageId: Long): OutgoingMmsRetry? {
        val message = messageDao.getById(messageId) ?: return null
        if (message.direction != MessageDirection.OUTGOING || !message.isMms ||
            message.status != MessageStatus.FAILED
        ) {
            return null
        }
        val recipients = conversationDao.getById(message.conversationId)
            ?.displayAddress
            ?.let(AddressNormalizer::membersOf)
            ?.filter(String::isNotBlank)
        if (recipients.isNullOrEmpty()) return null
        val attachments = attachmentDao.getForMessage(messageId).map {
            AttachmentPart(
                fileName = it.fileName,
                contentType = it.contentType,
                filePath = it.filePath,
                byteSize = it.byteSize,
            )
        }
        return OutgoingMmsRetry(
            rowId = message.id,
            recipients = recipients,
            subject = message.subject,
            body = message.body,
            sentAt = message.sentAt,
            attachments = attachments,
        )
    }

    /** Puts a failed outgoing MMS back to SENDING; false when it is no longer failed. */
    suspend fun markFailedMmsSending(messageId: Long): Boolean =
        messageDao.markFailedMmsSending(messageId) > 0

    /** Messages and conversations whose body or address matches, newest first. */
    fun observeMessageSearch(query: String): Flow<List<MessageSearchHit>> =
        messageDao.searchMessages(query)

    /** The stored draft for a conversation, or null when it has none. */
    fun observeDraft(conversationId: Long): Flow<String?> =
        draftDao.observe(conversationId).map { it.firstOrNull() }

    /** The composer's unsent-attachment queue, restored after restarts. */
    fun observePendingAttachments(conversationId: Long): Flow<List<AttachmentPart>> =
        draftDao.observePending(conversationId).map { row ->
            PendingAttachmentsCodec.decode(row.firstOrNull())
        }

    /** The queue as stored right now, for hydrating a freshly opened thread. */
    suspend fun getPendingAttachments(conversationId: Long): List<AttachmentPart> =
        draftDao.row(conversationId)
            ?.pendingAttachments
            ?.let { PendingAttachmentsCodec.decode(it) }
            .orEmpty()

    /** Attachments of one message, in PDU part order. */
    fun observeAttachments(messageId: Long): Flow<List<AttachmentEntity>> =
        attachmentDao.observeForMessage(messageId)

    /** Attachments of all messages in a conversation, in PDU part order. */
    fun observeConversationAttachments(conversationId: Long): Flow<List<AttachmentEntity>> =
        attachmentDao.observeForConversation(conversationId)

    /** Attachments of one message, for building a share or a send PDU. */
    suspend fun getAttachments(messageId: Long): List<AttachmentEntity> =
        attachmentDao.getForMessage(messageId)

    /**
     * Every attachment file the store still points at, from both the message
     * attachments and the composers' unsent queues. The orphan sweep deletes
     * only files outside this set, so a queued-but-unsent pick (which has no
     * message row) survives.
     */
    suspend fun referencedAttachmentPaths(): Set<String> {
        val fromMessages = attachmentDao.allFilePaths()
        val fromQueues = draftDao.allPendingAttachments()
            .flatMap { PendingAttachmentsCodec.decode(it) }
            .mapNotNull { it.filePath }
        return (fromMessages + fromQueues).toSet()
    }

    /** Marks a placeholder attachment downloaded to disk. */
    suspend fun markAttachmentRetrieved(id: Long, filePath: String, byteSize: Long) =
        attachmentDao.markRetrieved(id, filePath, byteSize)

    /** Adds an attachment a later download brought in for an existing message. */
    suspend fun addAttachment(messageId: Long, part: AttachmentPart) {
        attachmentDao.insert(
            AttachmentEntity(
                messageId = messageId,
                contentType = part.contentType,
                fileName = part.fileName,
                filePath = part.filePath,
                byteSize = part.byteSize,
                state = AttachmentState.RETRIEVED,
            ),
        )
    }

    /** Tracks an inbound MMS's media lifecycle for the thread's download action. */
    suspend fun markDownloadState(messageId: Long, state: DownloadState) =
        messageDao.updateDownloadState(messageId, state)

    /** Updates the body and subject of an MMS row after retrieval. */
    suspend fun updateMessageContent(messageId: Long, body: String, subject: String?) =
        messageDao.updateContent(messageId, body, subject)

    /**
     * Keeps the composed-but-unsent text for a conversation across restarts.
     * Blanking the text clears the stored body but leaves an attachment queue
     * in place, so a retry after a failed send does not have to re-pick files.
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

    /** Stores the composer's unsent-attachment queue for one conversation. */
    suspend fun savePendingAttachments(conversationId: Long, attachments: List<AttachmentPart>) {
        val encoded = PendingAttachmentsCodec.encode(attachments)
        if (draftDao.updatePending(conversationId, encoded) == 0 && encoded != null) {
            draftDao.save(
                DraftEntity(
                    conversationId = conversationId,
                    body = "",
                    pendingAttachments = encoded,
                ),
            )
        }
        if (attachments.isEmpty()) {
            draftDao.deleteIfEmpty(conversationId)
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

        fun digest(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

/**
 * An attachment carried by an MMS, either on disk already (outgoing media,
 * embedded inbound parts) or as a placeholder to be downloaded later.
 */
data class AttachmentPart(
    val fileName: String,
    val contentType: String,
    val filePath: String?,
    val byteSize: Long,
)

/**
 * A failed outgoing MMS reconstructed from the store, for the send-retry worker:
 * everything the original send carried, so a retry in a new process is the same
 * message rather than an empty one.
 */
data class OutgoingMmsRetry(
    val rowId: Long,
    val recipients: List<String>,
    val subject: String?,
    val body: String,
    val sentAt: Long,
    val attachments: List<AttachmentPart>,
)