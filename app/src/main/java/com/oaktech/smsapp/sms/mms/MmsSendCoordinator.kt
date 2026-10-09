package com.oaktech.smsapp.sms.mms

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.telephony.ProviderMedia
import com.oaktech.smsapp.data.telephony.ProviderMms
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
    /**
     * Whether this app currently holds the default-SMS role, checked before any
     * row is persisted so a send started from a stale UI cannot leave a bubble
     * the platform will never accept. Defaults to held because tests build this
     * coordinator directly and stand in as the default app; production wiring
     * in `MmsSendGraph` passes the live check.
     */
    private val isRoleHeld: () -> Boolean = { true },
    /**
     * Called with the row id after a transaction fails in a way worth retrying,
     * so the host can schedule a background attempt. Defaults to nothing, and a
     * retry itself never calls it, so a coordinator built by a test - or the
     * retry worker - does not recursively enqueue work.
     */
    private val onRetryableFailure: (Long) -> Unit = {},
) {

    val supportsAutomaticRetry: Boolean
        get() = poster.supportsAutomaticRetry

    private val appContext = context.applicationContext

    suspend fun send(
        recipients: List<String>,
        subject: String?,
        body: String,
        attachments: List<AttachmentPart>,
    ): SendOutcome {
        if (!isRoleHeld()) {
            Log.w(LogTags.SEND, "Rejected MMS send: the app is not the default SMS app")
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.NO_DEFAULT_SMS_APP)
        }

        val members = recipients.map { it.trim() }.filter { it.isNotBlank() }
        if (members.isEmpty()) {
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.GENERIC_FAILURE)
        }

        val repository = DataGraph.get(appContext).messageRepository
        val now = System.currentTimeMillis()
        val rowId = runCatching {
            repository.recordOutgoingMms(
                recipients = members,
                subject = subject,
                body = body,
                sentAt = now,
                attachments = attachments,
            )
        }.getOrElse { throwable ->
            Log.e(LogTags.SEND, "Could not persist outgoing MMS", throwable)
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_SEND_FAILED)
        }

        return transmit(rowId, members, subject, body, attachments, now, scheduleRetry = true)
    }

    /**
     * Retries a failed outgoing MMS in place, reusing its row and stored parts
     * rather than composing a new message.
     *
     * The caller (the retry worker) has already flipped the row back to SENDING;
     * this runs the same transaction as a first send, so a success marks SENT
     * and mirrors the provider copy while a failure returns it to FAILED with
     * this attempt's error. The retry backoff lives in the worker, so no retry
     * is scheduled from here.
     */
    suspend fun resend(
        rowId: Long,
        recipients: List<String>,
        subject: String?,
        body: String,
        attachments: List<AttachmentPart>,
        sentAt: Long,
    ): SendOutcome {
        if (!isRoleHeld()) {
            Log.w(LogTags.SEND, "Rejected MMS retry: the app is not the default SMS app")
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.NO_DEFAULT_SMS_APP)
        }
        val members = recipients.map { it.trim() }.filter { it.isNotBlank() }
        if (members.isEmpty()) {
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.GENERIC_FAILURE)
        }
        return transmit(rowId, members, subject, body, attachments, sentAt, scheduleRetry = false)
    }

    /**
     * The MMSC transaction shared by a first send and a retry: build the PDU,
     * resolve the MMSC, post, and correct the row from the outcome.
     *
     * [scheduleRetry] is true only for a first send. A retry already runs inside
     * the worker's own backoff, so it must not enqueue another job. Only a
     * transport failure or a non-2xx response schedules a retry: a PDU that
     * cannot be built or a missing MMSC is a configuration problem a backoff
     * will not fix.
     */
    private suspend fun transmit(
        rowId: Long,
        members: List<String>,
        subject: String?,
        body: String,
        attachments: List<AttachmentPart>,
        sentAt: Long,
        scheduleRetry: Boolean,
    ): SendOutcome {
        val repository = DataGraph.get(appContext).messageRepository

        val pdu = buildPdu(members, subject, body, attachments)
        if (pdu == null) {
            repository.updateOutgoingError(rowId, ResultCode.MMS_SEND_FAILED.platformCode)
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_SEND_FAILED)
        }

        val mmsc = if (poster.requiresMmscUrl) {
            runCatching { mmscResolver.mmscUrl() }.getOrElse { throwable ->
                Log.e(LogTags.SEND, "Could not resolve MMSC", throwable)
                repository.updateOutgoingError(rowId, ResultCode.MMS_CONFIG_UNAVAILABLE.platformCode)
                return SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_CONFIG_UNAVAILABLE)
            }
        } else {
            null
        }
        if (poster.requiresMmscUrl && mmsc.isNullOrBlank()) {
            Log.w(LogTags.SEND, "No MMSC configured; cannot send MMS")
            repository.updateOutgoingError(rowId, ResultCode.MMS_CONFIG_UNAVAILABLE.platformCode)
            return SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_CONFIG_UNAVAILABLE)
        }

        return try {
            // SmsManager's MMS callback can take tens of seconds while the
            // carrier provisions its bearer. Keep that wait away from Compose's
            // main dispatcher so the composer remains responsive and survives
            // a slow or unavailable network.
            val status = withContext(Dispatchers.IO) {
                poster.post(mmsc.orEmpty(), pdu)
            }
            if (poster.isSuccess(status)) {
                repository.updateOutgoingStatus(rowId, MessageStatus.SENT)
                // The platform does not write the default app's sends, so the
                // accepted message goes into the provider's sent table itself;
                // the local row adopts the provider's key so a later import
                // does not add a second copy.
                repository.mirrorSentMmsToProvider(
                    rowId = rowId,
                    message = ProviderMms(
                        address = members.first(),
                        recipients = members,
                        subject = subject,
                        timestampMillis = sentAt,
                        parts = MmsPduBuilder.providerParts(
                            body = body,
                            media = attachments.mapNotNull { part ->
                                val path = part.filePath ?: return@mapNotNull null
                                ProviderMedia(part.contentType, part.fileName, path)
                            },
                        ),
                        outgoing = true,
                    ),
                )
                SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
            } else {
                val failure = poster.failureCode(status)
                repository.updateOutgoingError(rowId, failure.platformCode)
                if (scheduleRetry && poster.supportsAutomaticRetry) scheduleRetry(rowId)
                SendOutcome(SendState.FAILED, 0, 1, failure)
            }
        } catch (throwable: Throwable) {
            Log.e(LogTags.SEND, "MMSC transaction failed", throwable)
            repository.updateOutgoingError(rowId, ResultCode.MMS_NETWORK_UNAVAILABLE.platformCode)
            if (scheduleRetry && poster.supportsAutomaticRetry) scheduleRetry(rowId)
            SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_NETWORK_UNAVAILABLE)
        }
    }

    /**
     * Hands a retryable failure to the host so it can schedule a background
     * attempt. A configuration problem or a lost role is not routed here: it
     * will not fix itself on the MMSC's schedule, and the row stays failed for a
     * manual resend either way.
     */
    private fun scheduleRetry(rowId: Long) {
        runCatching { onRetryableFailure(rowId) }
            .onFailure { Log.w(LogTags.SEND, "Could not schedule an MMS retry", it) }
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
