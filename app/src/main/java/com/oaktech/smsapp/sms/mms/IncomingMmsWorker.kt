package com.oaktech.smsapp.sms.mms

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.oaktech.smsapp.sms.receive.LogTags
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs one received MMS PDU off a WAP push.
 *
 * The broadcast that brings a push must return almost immediately; a retrieve
 * from a congested MMSC plus the two acknowledgement round trips take minutes,
 * far past the budget a pending result buys. WorkManager takes over the whole
 * transaction, including the HTTP, and survives the process being killed
 * mid-fetch, which a scoped coroutine inside `goAsync()` did not.
 *
 * Retrying is safe and desirable: the carrier retransmits an unacknowledged
 * notification, and the handling order (store first, acknowledge second) means
 * a crash before the acknowledgement loses nothing.
 */
class IncomingMmsWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val fileName = inputData.getString(KEY_PDU_FILE) ?: return Result.success()
        val queueDirectory = File(applicationContext.cacheDir, QUEUE_DIRECTORY)
        val pduFile = File(queueDirectory, fileName)
        // The name comes from this process's own input data, but WorkManager
        // writes that data to disk and reads it back, so `..` in a forged row
        // must not be able to point the delete at an arbitrary file.
        if (!pduFile.canonicalPath.startsWith(queueDirectory.canonicalPath)) {
            Log.e(LogTags.RECEIVE, "Refused MMS queue file outside the cache directory")
            return Result.success()
        }
        if (!pduFile.isFile) {
            // Purged by the system, or already handled on an earlier attempt.
            return Result.success()
        }

        return try {
            MmsReceiveWorker.handle(applicationContext, pduFile.readBytes())
            pduFile.delete()
            Result.success()
        } catch (throwable: Throwable) {
            Log.e(LogTags.RECEIVE, "Failed to handle incoming MMS", throwable)
            if (runAttemptCount >= MAX_ATTEMPTS) {
                // Give up without acknowledging the notification, so the
                // carrier keeps the message and retransmits it later rather
                // than the message being lost with the file.
                pduFile.delete()
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {

        private const val KEY_PDU_FILE = "pdu_file"
        internal const val QUEUE_DIRECTORY = "mms-incoming"
        internal const val UNIQUE_WORK = "incoming-mms"
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_SECONDS = 30L

        /**
         * Queues [pduBytes] for handling; returns false when it could not be
         * queued, which the caller should treat as "the carrier will repush".
         *
         * The bytes go to a cache file because `Data` is capped at 10 KB and a
         * retrieve-conf can carry a photo. The cache directory is the right
         * home: a purge before the worker runs only delays the message, since
         * the notification has not been acknowledged yet.
         */
        fun enqueue(context: Context, pduBytes: ByteArray): Boolean {
            val appContext = context.applicationContext
            return try {
                val queueDirectory = File(appContext.cacheDir, QUEUE_DIRECTORY).apply { mkdirs() }
                val pduFile = File(queueDirectory, "${UUID.randomUUID()}.pdu")
                pduFile.writeBytes(pduBytes)
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    UNIQUE_WORK,
                    // A burst of pushes has to survive in order, so APPEND
                    // rather than REPLACE; OR_REPLACE so one failed transaction
                    // cannot wedge every later push behind it.
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<IncomingMmsWorker>()
                        .setInputData(workDataOf(KEY_PDU_FILE to pduFile.name))
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            BACKOFF_SECONDS,
                            TimeUnit.SECONDS,
                        )
                        .build(),
                )
                true
            } catch (throwable: Throwable) {
                Log.e(LogTags.RECEIVE, "Could not queue incoming MMS", throwable)
                false
            }
        }
    }
}
