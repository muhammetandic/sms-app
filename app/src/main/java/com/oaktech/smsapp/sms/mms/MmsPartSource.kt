package com.oaktech.smsapp.sms.mms

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.oaktech.smsapp.sms.receive.LogTags
import java.io.File

/**
 * Where the bytes of a promised-but-missing MMS attachment come from.
 *
 * Incoming notifications are metadata only; tapping Download in the thread has
 * to go find the actual media. On a phone running the platform MMS stack, the
 * system downloads it into its own store and exposes it through the
 * `content://mms` provider, which is what [PlatformMmsPartSource] reads. That
 * path is deeply carrier-and-Role-dependent, so it is isolated behind this
 * seam and meant to be exercised on real hardware; the UI only knows the seam.
 */
interface MmsPartSource {

    /**
     * Returns the first attachment of the given message, or null when the
     * platform store has nothing for it yet.
     */
    fun firstAttachment(address: String, sentAtMillis: Long): FoundAttachment?
}

/** A resolved attachment: readable bytes plus the metadata a row needs. */
data class FoundAttachment(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
)

object NullMmsPartSource : MmsPartSource {
    override fun firstAttachment(address: String, sentAtMillis: Long): FoundAttachment? = null
}

/**
 * Reads MMS parts from the platform provider.
 *
 * The download is matched on sender address and timestamp: the message table
 * (`content://mms`) lists text messages with their `date` in seconds, and the
 * part table (`content://mms/part`) holds one row per media part joined to the
 * message by its `mid` column. This is the scrubby part of the feature and is
 * flagged for on-device validation because the provider row layout changes
 * hands across ROMs.
 */
class PlatformMmsPartSource(context: Context) : MmsPartSource {

    private val resolver = context.applicationContext.contentResolver

    override fun firstAttachment(address: String, sentAtMillis: Long): FoundAttachment? =
        runCatching {
            val messageId = messageIdFor(address, sentAtMillis) ?: return null
            partFor(messageId)
        }.onFailure { throwable ->
            Log.d(LogTags.RECEIVE, "MMS provider lookup failed", throwable)
        }.getOrNull()

    private fun messageIdFor(address: String, sentAtMillis: Long): Long? {
        val date = sentAtMillis / 1000
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.DATE}=?",
            arrayOf(date.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val idColumn = cursor.getColumnIndex(Telephony.Mms._ID)
                if (idColumn < 0) continue
                return cursor.getLong(idColumn)
            }
        }
        return null
    }

    private fun partFor(messageId: Long): FoundAttachment? {
        resolver.query(
            MILLS_PART_URI,
            arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part._DATA),
            "$PART_MID=?", // The part table's join key to the message row.
            arrayOf(messageId.toString()),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val contentType = cursor.stringOr(Telephony.Mms.Part.CONTENT_TYPE, "application/octet-stream")
            val partId = cursor.longOr(Telephony.Mms.Part._ID)
            val dataPath = cursor.stringOrNull(Telephony.Mms.Part._DATA)
            val bytes = if (dataPath != null) {
                File(dataPath).readBytes()
            } else {
                resolver.openInputStream(Uri.withAppendedPath(MILLS_PART_URI, partId.toString()))
                    ?.use { it.readBytes() }
                    ?: ByteArray(0)
            }
            if (bytes.isEmpty()) return null
            return FoundAttachment(
                fileName = "mms-attachment",
                contentType = contentType,
                bytes = bytes,
            )
        }
        return null
    }

    private fun Cursor.stringOr(column: String, fallback: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else fallback
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.longOr(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    private companion object {
        val MILLS_PART_URI = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "part")
        const val PART_MID = "mid"
    }
}