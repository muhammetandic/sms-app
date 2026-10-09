package com.oaktech.smsapp.sms.send

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.sms.mms.MmsSendCoordinator
import com.oaktech.smsapp.sms.receive.LogTags
import java.util.concurrent.TimeUnit

/**
 * Background retry for a failed outgoing MMS.
 *
 * A plain SMS failure is terminal: its per-part result code is the radio's last
 * word, and resending risks a duplicate of a message the network did deliver. An
 * MMS failure is usually the MMSC being briefly unreachable, so it is worth a
 * bounded number of further attempts. WorkManager owns the backoff and the
 * network gate, so a retry survives process death and waits for connectivity;
 * the unique name per message keeps a burst of failures from piling up.
 */
object MmsSendRetry {

    internal const val MAX_ATTEMPTS = 5
    internal const val KEY_MESSAGE_ID = "message_id"
    private const val BACKOFF_SECONDS = 30L

    internal fun uniqueWorkName(messageId: Long) = "mms-send-retry-$messageId"

    /** Queues a retry of [messageId], replacing any retry already queued for it. */
    fun schedule(context: Context, messageId: Long) {
        val appContext = context.applicationContext
        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueWorkName(messageId),
                // REPLACE, not APPEND: a later failure for the same row is the
                // same message to retry, so a stale queued attempt would only
                // delay the current one.
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MmsSendRetryWorker>()
                    .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        } catch (throwable: Throwable) {
            // A failed enqueue only means no background retry; the row is already
            // FAILED and can still be retried by hand, so this is not fatal.
            Log.e(LogTags.SEND, "Could not schedule MMS send retry", throwable)
        }
    }

    /**
     * Runs one attempt against [coordinator].
     *
     * [RetryResult.ABANDON] means there is nothing left to retry - the row was
     * sent, deleted, or replaced by a manual resend - and the worker should
     * stop. [RetryResult.RETRY] means the attempt ran but the MMSC was still
     * unreachable, so the worker backs off and tries again.
     */
    internal suspend fun retry(
        context: Context,
        messageId: Long,
        coordinator: MmsSendCoordinator,
    ): RetryResult {
        val repository = DataGraph.get(context).messageRepository
        val snapshot = repository.outgoingMmsRetry(messageId) ?: return RetryResult.ABANDON
        // Claim the retry by flipping the row back to SENDING. If it is no
        // longer FAILED (a delete or a manual resend won the race) the update
        // changes nothing and this attempt bows out instead of double-sending.
        if (!repository.markFailedMmsSending(messageId)) return RetryResult.ABANDON

        val outcome = coordinator.resend(
            rowId = snapshot.rowId,
            recipients = snapshot.recipients,
            subject = snapshot.subject,
            body = snapshot.body,
            attachments = snapshot.attachments,
            sentAt = snapshot.sentAt,
        )
        return if (outcome.state == SendState.SENT) RetryResult.SENT else RetryResult.RETRY
    }

    internal enum class RetryResult { SENT, RETRY, ABANDON }
}
