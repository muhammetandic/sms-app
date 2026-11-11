package com.anttech.smsapp.sync

import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageExportRow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One conversation's filing, as it travels in the backup: the folder it was
 * moved to and the drafts the thread holds.
 *
 * Separate from the query row because the drafts come from their own table, so
 * the repository pairs the two before encoding.
 */
data class SyncConversation(
    val addressKey: String,
    val displayAddress: String,
    val lastMessageAt: Long,
    val isSpam: Boolean = false,
    val isArchived: Boolean = false,
    val drafts: List<String> = emptyList(),
)

/**
 * One backup file's contents: the messages, and where their conversations were
 * filed.
 *
 * Split rather than flattened into one list because the two merge differently.
 * A message is identified by its `dedupe_key` and inserted if absent; a
 * conversation's filing is a property of the thread, applied once to the local
 * row and never allowed to overwrite a choice already made here.
 */
data class SyncContents(
    val messages: List<MessageExportRow> = emptyList(),
    val conversations: List<SyncConversation> = emptyList(),
)

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

    // Conversation lines spell their keys out. A message record repeats its
    // keys thousands of times and earns the abbreviations; a conversation line
    // appears once per thread, so readable keys cost nothing and make a file
    // the user downloads legible.
    private const val KEY_CONVERSATION = "conversation"
    private const val KEY_CONVERSATION_NAME = "name"
    private const val KEY_CONVERSATION_AT = "lastMessageAt"
    private const val KEY_SPAM = "spam"
    private const val KEY_ARCHIVED = "archived"
    private const val KEY_DRAFTS = "drafts"

    private const val DIRECTION_INCOMING = "IN"
    private const val DIRECTION_OUTGOING = "OUT"

    /**
     * Encodes one device's whole store, header first.
     *
     * Messages come before conversation lines so a reader that streams the file
     * sees each thread's messages before the filing that applies to it. Both
     * are optional: a store with only drafts publishes conversation lines
     * alone, and one with only messages publishes no conversation lines at all.
     */
    fun encode(
        deviceId: String,
        exportedAt: Long,
        messages: List<MessageExportRow>,
        conversations: List<SyncConversation> = emptyList(),
    ): ByteArray {
        val text = buildString {
            append(headerJson(deviceId, exportedAt))
            append('\n')
            for (message in messages) {
                append(recordJson(message))
                append('\n')
            }
            for (conversation in conversations) {
                append(conversationJson(conversation))
                append('\n')
            }
        }
        return gzip(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Parses a file back into its messages and conversation filings.
     *
     * A blank line is skipped and an unreadable record is dropped rather than
     * failing the whole file: the merge is idempotent, so one lost record is
     * simply offered again by the next sync from the same device.
     *
     * A line carrying a conversation key is a filing record, anything else is a
     * message record. An older build reading a file this version wrote sees the
     * filing lines as records without a dedupe key and drops them, which is why
     * adding them did not need a format bump.
     *
     * @throws IllegalArgumentException when the bytes are not a gzip stream, do
     *   not start with a readable header, or carry a format version this build
     *   does not know. One type on purpose: every one of those means the same
     *   thing to a caller - this file cannot be read - and the caller treats it
     *   as "not merged" so the file is retried. The underlying cause is carried
     *   as the exception's cause for anyone reading a log.
     */
    fun decode(bytes: ByteArray): SyncContents {
        val text = try {
            gunzip(bytes).toString(Charsets.UTF_8)
        } catch (error: IOException) {
            // A gzip stream that is truncated or not gzip at all. ZipException
            // is an IOException, so one catch covers both.
            throw IllegalArgumentException("Sync file is not a readable gzip stream", error)
        }

        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return SyncContents()

        val header = try {
            JSONObject(lines.first())
        } catch (error: JSONException) {
            throw IllegalArgumentException("Sync file has no readable header", error)
        }
        require(header.optInt(KEY_VERSION, -1) == FORMAT_VERSION) {
            "Unsupported sync file version ${header.optInt(KEY_VERSION, -1)}"
        }

        val messages = mutableListOf<MessageExportRow>()
        val conversations = mutableListOf<SyncConversation>()
        for (line in lines.drop(1)) {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.has(KEY_CONVERSATION)) {
                parseConversation(json)?.let(conversations::add)
            } else {
                parseRecord(json)?.let(messages::add)
            }
        }
        return SyncContents(messages = messages, conversations = conversations)
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

    private fun conversationJson(conversation: SyncConversation): String {
        val json = JSONObject()
            .put(KEY_CONVERSATION, conversation.addressKey)
            .put(KEY_CONVERSATION_NAME, conversation.displayAddress)
            .put(KEY_CONVERSATION_AT, conversation.lastMessageAt)
            .put(KEY_SPAM, conversation.isSpam)
            .put(KEY_ARCHIVED, conversation.isArchived)
        // Omitted when empty rather than written as [], so a store with no
        // drafts produces no key at all and the line stays short.
        if (conversation.drafts.isNotEmpty()) {
            json.put(KEY_DRAFTS, JSONArray(conversation.drafts))
        }
        return json.toString()
    }

    /**
     * A filing record missing its address key is dropped: the key is the only
     * way to know which conversation the folder belongs to, and guessing would
     * file a thread the peer never meant to file.
     *
     * A blank draft body is dropped for the same reason an empty draft row is
     * not exported: it carries no text.
     */
    private fun parseConversation(json: JSONObject): SyncConversation? {
        val addressKey = json.optString(KEY_CONVERSATION).takeIf { it.isNotEmpty() } ?: return null
        val drafts = json.optJSONArray(KEY_DRAFTS)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        return SyncConversation(
            addressKey = addressKey,
            // An old record may predate the name; the key is a serviceable
            // stand-in because it is what the row groups on.
            displayAddress = json.optString(KEY_CONVERSATION_NAME).ifEmpty { addressKey },
            lastMessageAt = json.optLong(KEY_CONVERSATION_AT),
            isSpam = json.optBoolean(KEY_SPAM, false),
            isArchived = json.optBoolean(KEY_ARCHIVED, false),
            drafts = drafts,
        )
    }

    /**
     * A record missing its key, its direction or its address is dropped: every
     * one of those is required to insert a message, and inventing one would
     * write a row the merge could never match again.
     */
    private fun parseRecord(json: JSONObject): MessageExportRow? {
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
