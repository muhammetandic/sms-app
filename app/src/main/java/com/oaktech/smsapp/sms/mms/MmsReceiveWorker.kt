package com.oaktech.smsapp.sms.mms

import android.content.Context
import android.util.Log
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.local.DownloadState
import com.oaktech.smsapp.notify.IncomingMessageNotifier
import com.oaktech.smsapp.sms.block.BlockedSenders
import com.oaktech.smsapp.sms.receive.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Off-main-thread entry point for an incoming MMS PDU.
 *
 * Follows [com.oaktech.smsapp.sms.receive.SmsReceiveWorker]: the receiver keeps
 * the broadcast pending for the duration of the work through [onComplete], and
 * the persistence runs in one process-wide scope.
 *
 * The two PDU types arrive differently:
 *  - a retrieve-conf carries the message body and media. Parts that made it to
 *    disk stream straight in; if media was advertised but did not land, the row
 *    is marked PENDING and the thread offers a download.
 *  - a notification.ind carries only metadata plus an X-Mms-Content-Location.
 *    The body is fetched from the MMSC; if that succeeds it is handled exactly
 *    like a retrieve-conf. If it fails, a PENDING placeholder row is stored so
 *    the user can retry from the thread — without ever double-storing, because
 *    the placeholder is only written when no body was pulled.
 */
object MmsReceiveWorker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(context: Context, pduBytes: ByteArray, onComplete: () -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                handle(appContext, pduBytes)
            } catch (throwable: Throwable) {
                Log.e(LogTags.RECEIVE, "Failed to handle incoming MMS", throwable)
            } finally {
                onComplete()
            }
        }
    }

    private suspend fun handle(context: Context, pduBytes: ByteArray) {
        val pdu = MmsPduParser.parse(pduBytes)
        if (pdu == null) {
            Log.w(LogTags.RECEIVE, "Discarded undecodable MMS PDU (${pduBytes.size} byte(s))")
            return
        }
        when (pdu.messageType) {
            MmsMessageType.NOTIFICATION_IND -> handleNotification(context, pdu)
            MmsMessageType.RETRIEVE_CONF -> handleRetrieveConf(context, pdu, closeTransaction = true)
            else -> Log.d(
                LogTags.RECEIVE,
                "Ignored MMS PDU that is not an inbound message (type 0x${pdu.messageType.toString(16)})",
            )
        }
    }

    private suspend fun handleNotification(context: Context, pdu: MmsPdu) {
        val acknowledger = MmsAcknowledger(HttpMmsPoster(), PlatformApnResolver(context))
        val autoRetriever = MmsAutoRetriever(HttpMmsRetriever(), PlatformApnResolver(context))
        val retrieveBytes = autoRetriever.retrieve(pdu)
        val retrievePdu = retrieveBytes?.let { MmsPduParser.parse(it) }
        if (retrievePdu?.messageType == MmsMessageType.RETRIEVE_CONF) {
            handleRetrieveConf(context, retrievePdu, closeTransaction = true)
            // The retrieve-conf was stored; tell the MMSC the notification's
            // message has been retrieved so it stops holding or retransmitting it.
            acknowledger.notifyResponse(pdu.transactionId, MmsNotifyStatus.RETRIEVED)
        } else {
            Log.d(LogTags.RECEIVE, "Auto-retrieve of MMS from ${pdu.from} failed; storing pending notification")
            storeNotification(context, pdu)
            // Nothing was pulled, but the notification was acknowledged as
            // deferred: the message stays on the MMSC until the user's manual
            // download, and the carrier stops repushing the same notification.
            acknowledger.notifyResponse(pdu.transactionId, MmsNotifyStatus.DEFERRED)
        }
    }

    private suspend fun handleRetrieveConf(
        context: Context,
        pdu: MmsPdu,
        closeTransaction: Boolean = false,
    ) {
        val inbounds = MmsPduMapper.toInbound(pdu, System.currentTimeMillis())
        if (inbounds == null) {
            Log.d(LogTags.RECEIVE, "Ignored retrieve-conf without an address")
            return
        }

        val store = MmsAttachmentStore(context)
        val stored = inbounds.attachments.mapNotNull { attachment ->
            val file: File? = store.store(attachment)
            if (file == null) {
                Log.w(LogTags.RECEIVE, "Could not store MMS attachment ${attachment.fileName}")
            }
            file?.let { StoredAttachment(file = it, contentType = attachment.contentType) }
        }

        val downloadState = when {
            // No media was ever declared: a text-only MMS.
            inbounds.attachments.isEmpty() -> DownloadState.NONE
            // Everything that was advertised is on disk.
            stored.size == inbounds.attachments.size -> DownloadState.RETRIEVED
            // Some parts were advertised but did not arrive: offer a download.
            else -> DownloadState.PENDING
        }

        storeInbound(
            context = context,
            inbounds = inbounds,
            stored = stored,
            downloadState = downloadState,
        )

        if (closeTransaction) {
            // The platform may deliver a retrieve-conf without a preceding
            // notification (the push and the fetch are independent transactions);
            // closing the transaction here is what stops the MMSC pestering.
            MmsAcknowledger(HttpMmsPoster(), PlatformApnResolver(context))
                .acknowledge(pdu.transactionId)
        }
    }

    private suspend fun storeNotification(context: Context, pdu: MmsPdu) {
        val notification = MmsPduMapper.toInbound(pdu, System.currentTimeMillis())
        if (notification == null) {
            Log.d(LogTags.RECEIVE, "Ignored notification without an address")
            return
        }
        storeInbound(
            context = context,
            inbounds = notification,
            stored = emptyList(),
            downloadState = DownloadState.PENDING,
        )
    }

    private suspend fun storeInbound(
        context: Context,
        inbounds: InboundMms,
        stored: List<StoredAttachment>,
        downloadState: DownloadState,
    ) {
        val graph = DataGraph.get(context)
        val rowId = graph.messageRepository.recordInboundMms(
            address = inbounds.address,
            body = inbounds.body,
            subject = inbounds.subject,
            timestampMillis = inbounds.timestampMillis,
            transactionId = inbounds.transactionId,
            attachments = stored.map { item ->
                AttachmentPart(
                    fileName = item.file.name,
                    contentType = item.contentType,
                    filePath = item.file.absolutePath,
                    byteSize = item.file.length(),
                )
            },
            downloadState = downloadState,
            contentLocation = inbounds.contentLocation,
        )
        if (rowId == null) {
            Log.d(LogTags.RECEIVE, "Skipped duplicate MMS from ${inbounds.address}")
            return
        }

        val conversationId = graph.messageRepository.conversationIdFor(inbounds.address)
        if (conversationId != null) {
            val spam = graph.messageRepository.isSpam(conversationId)
            val freshlyBlocked = !spam && BlockedSenders(context).isBlocked(inbounds.address)
            if (freshlyBlocked) {
                graph.messageRepository.setConversationSpam(conversationId, true)
            }
            if (spam || freshlyBlocked) {
                Log.d(
                    LogTags.RECEIVE,
                    "Stored ${if (freshlyBlocked) "blocked" else "spam"} MMS from " +
                        "${inbounds.address} without notifying",
                )
                return
            }
            // A muted conversation still stores and counts the message; only the
            // notification is suppressed. Unlike spam, the row stays in the
            // inbox so the unread badge keeps speaking.
            if (graph.messageRepository.isMuted(conversationId)) {
                Log.d(
                    LogTags.RECEIVE,
                    "Stored muted MMS from ${inbounds.address} without notifying",
                )
                return
            }
        }

        IncomingMessageNotifier(context).notifyMms(
            address = inbounds.address,
            body = inbounds.body,
            subject = inbounds.subject,
            messageId = rowId,
            conversationId = conversationId,
            attachmentCount = stored.size,
        )
        Log.d(
            LogTags.RECEIVE,
            "Stored inbound MMS from ${inbounds.address} (${stored.size} attachment(s), row $rowId, $downloadState)",
        )
    }

    private data class StoredAttachment(val file: File, val contentType: String)
}