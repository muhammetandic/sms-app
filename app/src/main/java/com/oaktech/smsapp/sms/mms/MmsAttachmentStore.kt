package com.oaktech.smsapp.sms.mms

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Writes attachment bytes to app-private storage.
 *
 * The files live under `filesDir/mms/parts` and are referenced from the
 * attachments table by absolute path. Writing to a `content://` source directly
 * is never attempted, because the incoming URI is a transient render of the
 * gallery result or an MMS provider row and can disappear; copying into owned
 * storage is what makes the attachment shareable later through the app's own
 * FileProvider.
 */
class MmsAttachmentStore(context: Context) {

    private val dir = File(context.filesDir, MMSPART_DIR).apply { mkdirs() }

    fun store(attachment: MmsAttachment): File? {
        val data = attachment.data ?: return null
        val file = File(dir, "${UUID.randomUUID()}-${safeName(attachment.fileName)}")
        return try {
            file.writeBytes(data)
            file
        } catch (throwable: Throwable) {
            file.delete()
            null
        }
    }

    fun store(data: ByteArray, fileName: String): File? = store(
        MmsAttachment(fileName = fileName, contentType = "application/octet-stream", data = data),
    )

    private fun safeName(fileName: String): String {
        val sanitized = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return if (sanitized.isBlank()) "attachment" else sanitized
    }

    companion object {
        const val MMSPART_DIR = "mms/parts"
    }
}