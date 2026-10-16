package com.anttech.smsapp.sync

import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageExportRow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONException
import org.json.JSONObject

/**
 * The wire format: one gzip stream of newline-delimited JSON.
 *
 * NDJSON so a file stays readable and diffable if the user ever downloads one
 * from the Drive folder, and gzip because SMS bodies are short and repetitive
 * while a full history is not - an uncompressed thread archive is megabytes of
 * near-duplicate JSON on a phone's uplink. The first line is a header carrying
 * the format version, so a future change is detected rather than mis-parsed.
 *
 * See [MessageExportRow] for what is deliberately not in a record.
 */
object SyncPayload {

    /**
     * Bumped when the shape of a record changes in a way an older build cannot
     * read. A reader that finds a version it does not know treats the file as
     * unreadable: it merges nothing and leaves the cursor alone, so the file is
     * offered again after an app update that does know the version, rather than
     * being written off or half-parsed.
     */
    const val FORMAT_VERSION = 1

    /**
     * Refuse to inflate past this. The folder is private to this app and
     * account, so this only bounds a truncated or corrupt stream, not an
     * attacker: a bad stream must not be able to exhaust the phone's memory.
     */
    private const val MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024

    private const val KEY_VERSION = "v"
    private const val KEY_DEVICE = "device"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_DEDUPE = "k"
    private const val KEY_BODY = "b"
    private const val KEY_SENT_AT = "t"
    private const val KEY_DIRECTION = "d"
    private const val KEY_PART_COUNT = "p"
    private const val KEY_ADDRESS_KEY = "a"
    private const val KEY_DISPLAY_ADDRESS = "n"

    private const val DIRECTION_INCOMING = "IN"
    private const val DIRECTION_OUTGOING = "OUT"

    /** Encodes one device's whole store, header first. */
    fun encode(deviceId: String, exportedAt: Long, messages: List<MessageExportRow>): ByteArray {
        val text = buildString {
            append(headerJson(deviceId, exportedAt))
            append('\n')
            for (message in messages) {
                append(recordJson(message))
                append('\n')
            }
        }
        return gzip(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Parses a file back into records.
     *
     * A blank line is skipped and an unreadable record is dropped rather than
     * failing the whole file: the merge is idempotent, so one lost record is
     * simply offered again by the next sync from the same device.
     *
     * @throws IllegalArgumentException when the bytes are not a gzip stream, do
     *   not start with a readable header, or carry a format version this build
     *   does not know. One type on purpose: every one of those means the same
     *   thing to a caller - this file cannot be read - and the caller treats it
     *   as "not merged" so the file is retried. The underlying cause is carried
     *   as the exception's cause for anyone reading a log.
     */
    fun decode(bytes: ByteArray): List<MessageExportRow> {
        val text = try {
            gunzip(bytes).toString(Charsets.UTF_8)
        } catch (error: IOException) {
            // A gzip stream that is truncated or not gzip at all. ZipException
            // is an IOException, so one catch covers both.
            throw IllegalArgumentException("Sync file is not a readable gzip stream", error)
        }

        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()

        val header = try {
            JSONObject(lines.first())
        } catch (error: JSONException) {
            throw IllegalArgumentException("Sync file has no readable header", error)
        }
        require(header.optInt(KEY_VERSION, -1) == FORMAT_VERSION) {
            "Unsupported sync file version ${header.optInt(KEY_VERSION, -1)}"
        }

        return lines.drop(1).mapNotNull(::parseRecord)
    }

    private fun headerJson(deviceId: String, exportedAt: Long): String =
        JSONObject()
            .put(KEY_VERSION, FORMAT_VERSION)
            .put(KEY_DEVICE, deviceId)
            .put(KEY_EXPORTED_AT, exportedAt)
            .toString()

    private fun recordJson(message: MessageExportRow): String =
        JSONObject()
            .put(KEY_DEDUPE, message.dedupeKey)
            .put(KEY_BODY, message.body)
            .put(KEY_SENT_AT, message.sentAt)
            .put(
                KEY_DIRECTION,
                if (message.direction == MessageDirection.INCOMING) DIRECTION_INCOMING
                else DIRECTION_OUTGOING,
            )
            .put(KEY_PART_COUNT, message.partCount)
            .put(KEY_ADDRESS_KEY, message.addressKey)
            .put(KEY_DISPLAY_ADDRESS, message.displayAddress)
            .toString()

    /**
     * A record missing its key, its direction or its address is dropped: every
     * one of those is required to insert a message, and inventing one would
     * write a row the merge could never match again.
     */
    private fun parseRecord(line: String): MessageExportRow? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val dedupeKey = json.optString(KEY_DEDUPE).takeIf { it.isNotEmpty() } ?: return null
        val addressKey = json.optString(KEY_ADDRESS_KEY).takeIf { it.isNotEmpty() } ?: return null
        val direction = when (json.optString(KEY_DIRECTION)) {
            DIRECTION_INCOMING -> MessageDirection.INCOMING
            DIRECTION_OUTGOING -> MessageDirection.OUTGOING
            else -> return null
        }
        return MessageExportRow(
            dedupeKey = dedupeKey,
            body = json.optString(KEY_BODY),
            sentAt = json.optLong(KEY_SENT_AT),
            direction = direction,
            // A part count below one would render a nonsense counter.
            partCount = json.optInt(KEY_PART_COUNT, 1).coerceAtLeast(1),
            addressKey = addressKey,
            // An old record may predate the display address; the key is a
            // serviceable stand-in because it is what the row groups on.
            displayAddress = json.optString(KEY_DISPLAY_ADDRESS).ifEmpty { addressKey },
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_DECOMPRESSED_BYTES) { "Sync file is larger than the read limit" }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
