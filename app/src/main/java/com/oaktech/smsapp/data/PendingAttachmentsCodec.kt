package com.oaktech.smsapp.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes the composer's unsent-attachment queue into the drafts table.
 *
 * The queue is a list of local files the user picked but has not sent. It is
 * mirrored into a single JSON column on the draft row so a send or a keystroke
 * only rewrites the half it touched; a restart restores both. An empty queue
 * encodes to null so a draft with only text stays a text row, and untrusted or
 * truncated JSON decodes to an empty queue rather than crashing the composer.
 */
object PendingAttachmentsCodec {

    fun encode(attachments: List<AttachmentPart>): String? {
        if (attachments.isEmpty()) return null
        return JSONArray().apply {
            attachments.forEach { part ->
                put(
                    JSONObject().apply {
                        put("fileName", part.fileName ?: JSONObject.NULL)
                        put("contentType", part.contentType ?: JSONObject.NULL)
                        put("filePath", part.filePath ?: JSONObject.NULL)
                        put("byteSize", part.byteSize)
                    },
                )
            }
        }.toString()
    }

    fun decode(json: String?): List<AttachmentPart> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                AttachmentPart(
                    fileName = item.optString("fileName"),
                    contentType = item.optString("contentType"),
                    filePath = if (item.isNull("filePath")) null else item.optString("filePath").takeIf { it.isNotEmpty() },
                    byteSize = item.optLong("byteSize", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }
}