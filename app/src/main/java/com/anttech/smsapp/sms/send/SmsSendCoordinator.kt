package com.anttech.smsapp.sms.send

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.notify.OutgoingFailureNotifier
import com.anttech.smsapp.sms.receive.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Orchestrates one outgoing message: validate, persist, split, hand to the
 * transport, then resolve the row's status from the callbacks that come back.
 *
 * Status resolution lives in [MultipartSendTracker]; this class only routes
 * results into it, so the aggregation rules stay testable off-device.
 *
 * Nothing here times a send out. The sent and delivery results arrive as
 * broadcast PendingIntents, which the manifest-declared [SmsSendResultReceiver]
 * delivers to a fresh process, so a timer owned by this instance would expire on
 * exactly the process death it was meant to cover - and a send that was still in
 * flight at that moment would be failed even though its callback was coming.
 * What can strand a row instead is a result that never arrives: the radio
 * abandons per-part reports after a short window, and a process killed between
 * persisting SENDING and the callback leaves a spinner with nothing left to
 * resolve it. That residue is swept once at startup rather than per send -
 * MainActivity runs MessageRepository.resolveStuckOutgoing, which fails any row
 * still SENDING past the platform's callback window - because startup always
 * happens, while a per-send timer may not.
 */
class SmsSendCoordinator(
    context: Context,
    private val transport: SmsTransport,
    /**
     * Whether this app currently holds the default-SMS role.
     *
     * Checked here rather than only in the composer because losing the role
     * also revokes SEND_SMS mid-session: a send started from a stale UI state
     * would otherwise persist a row that the radio silently refuses, leaving a
     * bubble that never resolves. Production wiring reads the live role; tests
     * stand in as the default app.
     */
    private val isRoleHeld: () -> Boolean = { true },
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val failureNotifier = OutgoingFailureNotifier(appContext)

    private val trackers = ConcurrentTrackers()

    suspend fun send(recipient: String, body: String): SendOutcome {
        if (!isRoleHeld()) {
            android.util.Log.w(LogTags.SEND, "Rejected send: the app is not the default SMS app")
            return SendOutcome(
                state = SendState.FAILED,
                failedParts = 0,
                totalParts = 0,
                code = ResultCode.NO_DEFAULT_SMS_APP,
            )
        }

        if (!transport.canSend()) {
            android.util.Log.w(LogTags.SEND, "Rejected send: SEND_SMS is not granted")
            return SendOutcome(
                state = SendState.FAILED,
                failedParts = 0,
                totalParts = 0,
                code = ResultCode.GENERIC_FAILURE,
            )
        }

        when (val validation = SendValidator.validate(recipient, body)) {
            is SendValidator.Result.Invalid -> {
                android.util.Log.w(LogTags.SEND, "Rejected send: ${validation.detail}")
                return SendOutcome(
                    state = SendState.FAILED,
                    failedParts = 0,
                    totalParts = 0,
                    code = ResultCode.GENERIC_FAILURE,
                )
            }

            SendValidator.Result.Valid -> Unit
        }

        val repository = DataGraph.get(appContext).messageRepository
        val destination = recipient.trim()
        val now = System.currentTimeMillis()
        val rowId = repository.recordOutgoing(destination, body, now, simSlot = transport.subscriptionId())

        val parts = try {
            transport.divideMessage(body)
        } catch (throwable: Throwable) {
            android.util.Log.e(LogTags.SEND, "Failed to divide message", throwable)
            repository.updateOutgoingStatus(rowId, MessageStatus.FAILED)
            return SendOutcome(SendState.FAILED, 0, 0, ResultCode.GENERIC_FAILURE)
        }

        if (parts.size > SendValidator.MAX_PARTS) {
            repository.updateOutgoingStatus(rowId, MessageStatus.FAILED)
            return SendOutcome(SendState.FAILED, 0, parts.size, ResultCode.LIMIT_EXCEEDED)
        }

        repository.updateOutgoingPartCount(rowId, parts.size)

        val token = trackers.register(rowId, parts.size)

        val sentIntents = parts.indices.map { index ->
            PendingIntent.getBroadcast(
                appContext,
                notificationId(token, index),
                SmsSendResultReceiver.sentIntent(appContext, token, rowId, index),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val deliveryIntent = PendingIntent.getBroadcast(
            appContext,
            notificationId(token, DELIVERY_INDEX),
            SmsSendResultReceiver.deliveryIntent(appContext, token, rowId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return try {
            transport.sendMultipart(destination, parts, sentIntents, deliveryIntent)
            // The platform writes sent rows only for apps that are not the
            // default, so this one has to file its own; the row also adopts the
            // provider's dedupe key here, before any result can arrive.
            repository.mirrorSentToProvider(rowId, destination, body, now)
            SendOutcome(SendState.SENT, failedParts = 0, totalParts = parts.size, code = ResultCode.SENT)
        } catch (throwable: Throwable) {
            android.util.Log.e(LogTags.SEND, "Failed to send message", throwable)
            repository.updateOutgoingStatus(rowId, MessageStatus.FAILED)
            trackers.discard(token)
            SendOutcome(SendState.FAILED, 0, parts.size, ResultCode.GENERIC_FAILURE)
        }
    }

    /** Called by [SmsSendResultReceiver] for one part's result. */
    fun onPartResult(token: Long, rowId: Long, partIndex: Int, code: Int) {
        val tracker = trackers.trackerFor(token)
        if (tracker != null) {
            val outcome = tracker.onPartResult(partIndex, code)
            if (outcome != null) persist(rowId, outcome)
            return
        }

        // The process was recreated since this message was handed to the radio,
        // so the in-memory tracker died with it. The row persists the part count
        // so the tracker can be rebuilt and the remaining parts still aggregate;
        // without it (a legacy row) the first report resolves the message.
        scope.launch {
            val partCount = DataGraph.get(appContext).messageRepository
                .messagePartCount(rowId)
            if (partCount == null || partCount <= 0) {
                resolveFromFirstReport(rowId, code)
                return@launch
            }
            val rebuilt = MultipartSendTracker(expectedParts = partCount)
            trackers.register(token, rebuilt)
            rebuilt.onPartResult(partIndex, code)?.let { persist(rowId, it) }
        }
    }

    fun onDelivered(token: Long, rowId: Long) {
        val tracker = trackers.trackerFor(token)
        if (tracker != null) {
            persist(rowId, tracker.markDelivered())
            return
        }
        // A delivery report survived a process death without its tracker; the
        // row is delivered now regardless of the parts that reportedly sent it.
        scope.launch {
            DataGraph.get(appContext).messageRepository.updateOutgoingDelivery(rowId, System.currentTimeMillis())
        }
    }

    private fun resolveFromFirstReport(rowId: Long, code: Int) {
        scope.launch {
            val repository = DataGraph.get(appContext).messageRepository
            val result = ResultCode.fromPlatformCode(code)
            if (result.isFailure) {
                repository.failOutgoing(rowId, code)
                notifySendFailed(repository, rowId)
            } else {
                repository.markOutgoingSent(rowId)
            }
        }
    }

    private fun persist(rowId: Long, outcome: SendOutcome) {
        scope.launch {
            val repository = DataGraph.get(appContext).messageRepository
            when {
                outcome.isSuccess && outcome.code == ResultCode.DELIVERED ->
                    // The delivery report is the only honest moment to time a
                    // DELIVERED status, so the timestamp is captured here rather
                    // than on a later read.
                    repository.updateOutgoingDelivery(rowId, System.currentTimeMillis())

                outcome.isSuccess -> repository.updateOutgoingStatus(rowId, MessageStatus.SENT)
                // Keep the platform code behind the failure so the thread can
                // name it instead of only showing "failed".
                else -> {
                    repository.updateOutgoingError(rowId, outcome.code.platformCode)
                    notifySendFailed(repository, rowId)
                }
            }
        }
    }

    /**
     * Alerts that a send failed.
     *
     * Only the asynchronous resolution paths call this. A failure the composer
     * sees on [send]'s return is shown inline there, and alerting as well would
     * buzz the user about the screen already in front of them; the parts that
     * resolve here arrive after that screen is gone, which is exactly when the
     * alert is the only way the failure is ever noticed.
     */
    private suspend fun notifySendFailed(repository: MessageRepository, rowId: Long) {
        val conversationId = repository.conversations.conversationIdForMessage(rowId)
        failureNotifier.notify(
            recipient = conversationId?.let { repository.conversations.recipientFor(it) }.orEmpty(),
            conversationId = conversationId,
        )
    }

    private companion object {
        const val DELIVERY_INDEX = 99

        fun notificationId(token: Long, partIndex: Int): Int =
            ((token % Int.MAX_VALUE.toLong()) * 100 + partIndex).toInt()
    }
}

/**
 * In-flight send trackers keyed by token.
 *
 * Kept process-wide because sent intents arrive through the manifest-declared
 * receiver, which the system may create without consulting the sending process
 * state.
 */
class ConcurrentTrackers {

    private val trackers = mutableMapOf<Long, MultipartSendTracker>()

    @Synchronized
    fun register(rowId: Long, parts: Int): Long {
        val token = nextToken++
        trackers[token] = MultipartSendTracker(expectedParts = parts)
        return token
    }

    /** Registers a pre-built tracker under a token from a previous process. */
    @Synchronized
    fun register(token: Long, tracker: MultipartSendTracker) {
        trackers[token] = tracker
    }

    @Synchronized
    fun trackerFor(token: Long): MultipartSendTracker? = trackers[token]

    @Synchronized
    fun discard(token: Long) {
        trackers.remove(token)
    }

    @Synchronized
    fun resolvedTokens(): Set<Long> = trackers.filterValues { it.isResolved }.keys

    private var nextToken = 1L
}
