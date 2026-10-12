package com.anttech.smsapp.sms.receive

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Durable adapter between the SMS_DELIVER broadcast and the receive handler. */
class SmsReceiveWork(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val fileName = inputData.getString(KEY_PARTS_FILE) ?: return Result.failure()
        val directory = File(applicationContext.filesDir, DIRECTORY)
        val file = File(directory, fileName)
        if (!file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
            Log.e(LogTags.RECEIVE, "Refused SMS queue file outside the app directory")
            return Result.failure()
        }
        if (!file.isFile) {
            Log.e(LogTags.RECEIVE, "SMS queue file is missing: $fileName")
            return Result.failure()
        }

        return try {
            val parts = readParts(file)
            SmsReceiveWorker.handle(applicationContext, parts)
            file.delete()
            Result.success()
        } catch (throwable: Throwable) {
            Log.e(LogTags.RECEIVE, "Failed to handle queued incoming SMS", throwable)
            Result.retry()
        }
    }

    companion object {
        private const val KEY_PARTS_FILE = "parts_file"
        private const val DIRECTORY = "sms-incoming"
        private const val UNIQUE_WORK = "incoming-sms"

        fun enqueue(context: Context, parts: List<SmsPart>): Boolean = runCatching {
            val appContext = context.applicationContext
            val directory = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.sms")
            writeParts(file, parts)
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SmsReceiveWork>()
                    .setInputData(workDataOf(KEY_PARTS_FILE to file.name))
                    .build(),
            )
        }.onFailure { throwable ->
            Log.e(LogTags.RECEIVE, "Could not persist incoming SMS", throwable)
        }.isSuccess

        private fun writeParts(file: File, parts: List<SmsPart>) {
            DataOutputStream(FileOutputStream(file)).use { output ->
                output.writeInt(parts.size)
                parts.forEach { part ->
                    output.writeUTF(part.originatingAddress)
                    output.writeUTF(part.messageBody)
                    output.writeLong(part.timestampMillis)
                }
            }
        }

        private fun readParts(file: File): List<SmsPart> =
            DataInputStream(FileInputStream(file)).use { input ->
                List(input.readInt()) {
                    SmsPart(
                        originatingAddress = input.readUTF(),
                        messageBody = input.readUTF(),
                        timestampMillis = input.readLong(),
                    )
                }
            }
    }
}
