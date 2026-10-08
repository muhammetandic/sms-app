package com.oaktech.smsapp.sms.mms

import android.content.Context
import android.util.Log
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.sms.receive.LogTags
import com.oaktech.smsapp.sms.send.ResultCode
import com.oaktech.smsapp.sms.send.SendOutcome
import com.oaktech.smsapp.sms.send.SendState
import java.io.File

/**
 * Orchestrates one outgoing MMS.
 *
 * The send-req PDU is built, the message row is persisted first (so a process
 * death marks it forever-failed rather than lost), and then the bytes ride to
 * the MMSC through the [MmsPoster] seam. There is no per-part callback for MMS:
 * the row is corrected once, from this transaction's outcome.
 */
class MmsSendCoordinator(
    private val poster: MmsPoster,
    private val mmscResolver: MmscUrlResolver,
    context: Context,
) {

    private val appContext = context.applicationContext

    suspend fun send(
        recipients: List<String>,
        subject: String?,
        body: String,
        attachments: List<AttachmentPart>,
    ): SendOutcome {
        val members = recipients.map { it.trim() }.filter { it.isNotBlank() }
        if (members.isEmpty()) {
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.GENERIC_FAILURE)
        }

        val repository = DataGraph.get(appContext).messageRepository
        val now = System.currentTimeMillis()
        val rowId = repository.recordOutgoingMms(
            recipients = members,
            subject = subject,
            body = body,
            sentAt = now,
            attachments = attachments,
        )

        val pdu = buildPdu(members, subject, body, attachments)
        if (pdu == null) {
            repository.updateOutgoingError(rowId, ResultCode.MMS_SEND_FAILED.platformCode)
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_SEND_FAILED)
        }

        val mmsc = mmscResolver.mmscUrl()
        if (mmsc.isNullOrBlank()) {
            Log.w(LogTags.SEND, "No MMSC configured; cannot send MMS")
            repository.updateOutgoingError(rowId, ResultCode.MMS_CONFIG_UNAVAILABLE.platformCode)
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_CONFIG_UNAVAILABLE)
        }

        return try {
            val status = poster.post(mmsc, pdu)
            if (poster.isSuccess(status)) {
                repository.updateOutgoingStatus(rowId, MessageStatus.SENT)
                SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
            } else {
                repository.updateOutgoingError(rowId, ResultCode.MMS_SEND_FAILED.platformCode)
                SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_SEND_FAILED)
            }
        } catch (throwable: Throwable) {
            Log.e(LogTags.SEND, "MMSC transaction failed", throwable)
            repository.updateOutgoingError(rowId, ResultCode.MMS_NETWORK_UNAVAILABLE.platformCode)
            SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_NETWORK_UNAVAILABLE)
        }
    }

    private suspend fun buildPdu(
        recipients: List<String>,
        subject: String?,
        body: String,
        attachments: List<AttachmentPart>,
    ): ByteArray? {
        val media = if (attachments.isEmpty()) {
            emptyList()
        } else {
            attachments.map { part ->
                val file = File(part.filePath ?: return null)
                if (!file.isFile) return null
                MmsPduBuilder.Media(
                    contentType = part.contentType,
                    fileName = part.fileName,
                    data = file.readBytes(),
                )
            }
        }
        return runCatching {
            MmsPduBuilder.sendRequest(
                transactionId = newTransactionId(),
                recipients = recipients,
                subject = subject,
                bodyText = body,
                media = media,
            )
        }.getOrNull()
    }

    private fun newTransactionId(): String =
        "oid-$TIMESTAMP_MS-${kotlin.random.Random.nextInt(0x1000, 0xFFFF).toString(16)}"

    private companion object {
        val TIMESTAMP_MS: String
            get() = java.lang.Long.toHexString(System.currentTimeMillis())
    }
}