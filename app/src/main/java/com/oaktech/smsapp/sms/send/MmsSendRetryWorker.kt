package com.oaktech.smsapp.sms.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * One background attempt at re-sending a failed outgoing MMS.
 *
 * The work is thin: [MmsSendRetry.retry] decides whether there is anything to do
 * and runs the transaction, and this only maps the outcome onto WorkManager's
 * vocabulary. The attempt cap is here rather than in the retry logic because
 * `runAttemptCount` is WorkManager's own bookkeeping; when it is exhausted the
 * row is left FAILED for a manual resend instead of looping forever.
 */
class MmsSendRetryWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getLong(MmsSendRetry.KEY_MESSAGE_ID, -1L)
        if (messageId <= 0L) return Result.success()
        if (runAttemptCount >= MmsSendRetry.MAX_ATTEMPTS) return Result.failure()

        val coordinator = MmsSendGraph.coordinator(applicationContext)
        return when (MmsSendRetry.retry(applicationContext, messageId, coordinator)) {
            MmsSendRetry.RetryResult.SENT,
            MmsSendRetry.RetryResult.ABANDON,
            -> Result.success()

            MmsSendRetry.RetryResult.RETRY -> Result.retry()
        }
    }
}
