package com.oaktech.smsapp.sms.mms

import android.content.Context
import android.util.Log
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.DownloadState
import com.oaktech.smsapp.data.telephony.ProviderMedia
import com.oaktech.smsapp.data.telephony.ProviderMms
import com.oaktech.smsapp.sms.receive.LogTags

/**
 * Brings a missing attachment in for an inbound MMS and records it against the
 * message row, called from the thread's download button.
 *
 * Two situations need it:
 *  - a retrieve-conf whose advertised media did not reach disk: the platform
 *    MMS provider still holds the parts, surfaced through [MmsPartSource];
 *  - a notification.ind stored as a PENDING placeholder when auto-retrieve
 *    failed at arrival: the body is pulled now with a GET to the MMSC using the
 *    content-location persisted on the row.
 *
 * If the second path produces fewer parts than the PDU advertised, the row
 * stays PENDING; only full media lands the RETRIEVED state.
 */
class MmsDownloader(
    context: Context,
    private val partSource: MmsPartSource,
    private val autoRetriever: MmsAutoRetriever,
    private val acknowledger: MmsAcknowledger? = null,
) {
    private val appContext = context.applicationContext

    private val store = MmsAttachmentStore(appContext)

    suspend fun download(repository: MessageRepository, messageId: Long): Boolean {
        val message = repository.getMessage(messageId) ?: return false
        val conversationId = repository.conversationIdForMessage(messageId) ?: return false
        val address = repository.recipientFor(conversationId)?.takeIf { it.isNotBlank() } ?: return false

        if (message.downloadState == DownloadState.PENDING && message.contentLocation != null) {
            return retrieveAndStore(repository, messageId, address)
        }

        val found = partSource.firstAttachment(address, message.sentAt) ?: return false
        val stored = store.store(found.bytes, found.fileName) ?: return false
        repository.addAttachment(
            messageId = messageId,
            part = AttachmentPart(
                fileName = stored.name,
                contentType = found.contentType,
                filePath = stored.absolutePath,
                byteSize = stored.length(),
            ),
        )
        repository.markDownloadState(messageId, DownloadState.RETRIEVED)
        mirrorRetrieved(repository, messageId, address)
        return true
    }

    private suspend fun retrieveAndStore(
        repository: MessageRepository,
        messageId: Long,
        address: String,
    ): Boolean {
        val message = repository.getMessage(messageId) ?: return false
        val bytes = autoRetriever.retrieve(message.contentLocation ?: return false) ?: return false
        val pdu = MmsPduParser.parse(bytes)
        if (pdu?.messageType != MmsMessageType.RETRIEVE_CONF) return false

        val inbounds = MmsPduMapper.toInbound(pdu, message.sentAt)
        var storedCount = 0
        inbounds?.attachments?.forEach { attachment ->
            val file = store.store(attachment) ?: return@forEach
            storedCount += 1
            repository.addAttachment(
                messageId = messageId,
                part = AttachmentPart(
                    fileName = file.name,
                    contentType = attachment.contentType,
                    filePath = file.absolutePath,
                    byteSize = file.length(),
                ),
            )
        }
        val declared = inbounds?.attachments?.size ?: 0
        if (declared > 0 && storedCount == 0) return false

        if (inbounds != null) {
            repository.updateMessageContent(messageId, inbounds.body, inbounds.subject)
        }

        val finalState = if (storedCount < declared) DownloadState.PENDING else DownloadState.RETRIEVED
        repository.markDownloadState(messageId, finalState)
        if (finalState == DownloadState.RETRIEVED) {
            mirrorRetrieved(repository, messageId, address)
        }
        acknowledger?.notifyResponse(message.transactionId, MmsNotifyStatus.RETRIEVED)
        acknowledger?.acknowledge(pdu.transactionId)
        Log.d(LogTags.RECEIVE, "Retrieved $storedCount/$declared attachment(s) for MMS row $messageId")
        return true
    }

    /**
     * Writes a now-complete inbound MMS into the provider, unless it is already
     * there. A retrieve-conf at arrival is mirrored then and skips this; this is
     * the path for a PENDING placeholder the user later downloaded, which could
     * not be mirrored while it had no parts.
     */
    private suspend fun mirrorRetrieved(repository: MessageRepository, messageId: Long, address: String) {
        val message = repository.getMessage(messageId) ?: return
        if (message.providerId != null) return
        val media = repository.getAttachments(messageId).mapNotNull { attachment ->
            val path = attachment.filePath ?: return@mapNotNull null
            ProviderMedia(attachment.contentType, attachment.fileName, path)
        }
        repository.mirrorInboundMmsToProvider(
            rowId = messageId,
            message = ProviderMms(
                address = address,
                recipients = emptyList(),
                subject = message.subject,
                timestampMillis = message.sentAt,
                parts = MmsPduBuilder.providerParts(body = message.body, media = media),
                outgoing = false,
            ),
        )
    }
}