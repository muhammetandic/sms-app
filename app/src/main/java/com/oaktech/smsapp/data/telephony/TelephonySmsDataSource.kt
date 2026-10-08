package com.oaktech.smsapp.data.telephony

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read access to the platform Telephony provider.
 *
 * An interface so the import path can be exercised without a real provider,
 * which is unavailable in tests and unavailable entirely without the SMS role.
 */
interface SmsMessageSource {
    suspend fun queryAllMessages(limit: Int = 5_000): List<ProviderMessage>
}

/**
 * Reads SMS and MMS from the platform Telephony provider.
 *
 * Only the default SMS app can read this provider, so queries may throw
 * [SecurityException] when the role is not held; callers get an empty list
 * instead. Column access is guarded per column because OEM providers omit
 * columns and a missing one must degrade the row rather than crash the query.
 *
 * The MMS body and sender live in per-message part and address tables rather
 * than on the message row, so reading history is a small N+1 per message.
 * The MMS layout varies across ROMs; each part is read defensively and a
 * message that cannot be reconstructed is dropped rather than inserted empty.
 */
class TelephonySmsDataSource(context: Context) : SmsMessageSource {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun queryConversations(limit: Int = 100): List<ProviderThread> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            Telephony.Threads._ID,
            Telephony.Threads.DATE,
            Telephony.Threads.MESSAGE_COUNT,
            Telephony.Threads.READ,
            Telephony.Threads.SNIPPET,
        )

        val cursor = runCatching {
            resolver.query(
                THREADS_URI,
                projection,
                "${Telephony.Threads.MESSAGE_COUNT} > 0",
                null,
                "${Telephony.Threads.DATE} DESC LIMIT $limit",
            )
        }.getOrNull() ?: return@withContext emptyList()

        cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) add(rows.toProviderThread())
            }
        }
    }

    suspend fun queryMessages(threadId: Long, limit: Int = 500): List<ProviderMessage> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.READ,
        )

        val cursor = runCatching {
            resolver.query(
                SMS_URI,
                projection,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} ASC LIMIT $limit",
            )
        }.getOrNull() ?: return@withContext emptyList()

        cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    rows.toProviderMessage()?.let(::add)
                }
            }
        }
    }

    /**
     * Every SMS and MMS row in the provider, across all threads.
     *
     * Rows are read in `_id` order (monotonic with insertion) in pages, with the
     * page size defaulting to the old hard limit so the query stays bounded per
     * round-trip. The loop keeps going until a page comes back short, so a large
     * inbox is imported in full instead of truncating at the first page.
     */
    override suspend fun queryAllMessages(limit: Int): List<ProviderMessage> = withContext(Dispatchers.IO) {
        val messages = buildList {
            var lastSmsId = 0L
            while (true) {
                val page = querySmsPage(lastSmsId, limit)
                addAll(page)
                lastSmsId = page.lastOrNull()?.id ?: break
                if (page.size < limit) break
            }

            var lastMmsId = 0L
            while (true) {
                val page = queryMmsPage(lastMmsId, limit)
                addAll(page)
                lastMmsId = page.lastOrNull()?.id ?: break
                if (page.size < limit) break
            }
        }

        // Oldest-first so conversations build in a sensible order.
        messages.sortedBy { it.date }
    }

    private fun querySmsPage(afterId: Long, limit: Int): List<ProviderMessage> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.READ,
        )
        val selection = if (afterId > 0L) "${Telephony.Sms._ID} > $afterId" else null
        val cursor = runCatching {
            resolver.query(
                SMS_URI,
                projection,
                selection,
                null,
                "${Telephony.Sms._ID} ASC LIMIT $limit",
            )
        }.getOrNull() ?: return emptyList()

        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    rows.toProviderMessage()?.let(::add)
                }
            }
        }
    }

    private fun queryMmsPage(afterId: Long, limit: Int): List<ProviderMessage> {
        val projection = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.DATE,
            Telephony.Mms.READ,
            Telephony.Mms.MESSAGE_BOX,
        )
        val selection = if (afterId > 0L) "${Telephony.Mms._ID} > $afterId" else null
        val cursor = runCatching {
            resolver.query(
                MMS_URI,
                projection,
                selection,
                null,
                "${Telephony.Mms._ID} ASC LIMIT $limit",
            )
        }.getOrNull() ?: return emptyList()

        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val id = rows.longOrZero(Telephony.Mms._ID)
                    toProviderMmsMessage(id, rows)?.let(::add)
                }
            }
        }
    }

    /**
     * Rebuilds an MMS row as a [ProviderMessage].
     *
     * The message row has no body or sender; both live in child tables. The body
     * is the concatenation of text parts, and the address is the FROM row for an
     * inbound message or the first TO row for an outbound one. Everything is read
     * defensively because these tables differ across ROMs, and a row whose sender
     * cannot be found has no conversation to file under and is dropped.
     */
    private fun toProviderMmsMessage(messageId: Long, rows: Cursor): ProviderMessage? {
        val msgBox = rows.intOrZero(Telephony.Mms.MESSAGE_BOX)
        val address = mmsAddress(messageId) ?: return null

        return ProviderMessage(
            id = messageId,
            threadId = rows.longOrZero(Telephony.Mms.THREAD_ID),
            address = address,
            body = mmsTextParts(messageId),
            // Telephony.Mms.DATE is seconds since epoch, not millis.
            date = rows.longOrZero(Telephony.Mms.DATE) * 1_000L,
            type = msgBox,
            status = SmsProviderTypes.STATUS_NONE,
            read = rows.intOrZero(Telephony.Mms.READ) == 1,
            mms = true,
        )
    }

    /** Joins the text/plain parts of an MMS into the message body. */
    private fun mmsTextParts(messageId: Long): String {
        val cursor = runCatching {
            resolver.query(
                PART_URI(messageId),
                arrayOf(Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
                null,
                null,
                "${Telephony.Mms.Part._ID} ASC",
            )
        }.getOrNull() ?: return ""

        return cursor.use { parts ->
            buildString {
                while (parts.moveToNext()) {
                    if ((parts.stringOrNull(Telephony.Mms.Part.CONTENT_TYPE) ?: "").startsWith("text/")) {
                        append(parts.stringOrNull(Telephony.Mms.Part.TEXT).orEmpty())
                    }
                }
            }
        }
    }

    /**
     * Finds a display address for an MMS. Inbound messages carry the sender in a
     * FROM row; outbound messages list recipients as TO rows. Falling back to any
     * row keeps a message fileable when an OEM writes the address differently.
     */
    private fun mmsAddress(messageId: Long): String? {
        val cursor = runCatching {
            resolver.query(
                ADDRESS_URI(messageId),
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                null,
                null,
                null,
            )
        }.getOrNull() ?: return null

        return cursor.use { addresses ->
            var outgoing = ""
            while (addresses.moveToNext()) {
                val address = addresses.stringOrNull(Telephony.Mms.Addr.ADDRESS)?.trim().orEmpty()
                if (address.isEmpty()) continue
                if (addresses.intOrZero(Telephony.Mms.Addr.TYPE) == MMS_ADDRESS_TYPE_FROM) return address
                if (outgoing.isEmpty()) outgoing = address
            }
            outgoing.ifEmpty { null }
        }
    }

    private fun Cursor.toProviderThread(): ProviderThread = ProviderThread(
        threadId = longOrZero(Telephony.Threads._ID),
        snippet = stringOrNull(Telephony.Threads.SNIPPET).orEmpty(),
        date = longOrZero(Telephony.Threads.DATE),
        messageCount = intOrZero(Telephony.Threads.MESSAGE_COUNT),
        read = intOrZero(Telephony.Threads.READ) == 1,
    )

    private fun Cursor.toProviderMessage(): ProviderMessage? {
        val body = stringOrNull(Telephony.Sms.BODY)
        val address = stringOrNull(Telephony.Sms.ADDRESS)
        if (body == null && address.isNullOrEmpty()) return null

        return ProviderMessage(
            id = longOrZero(Telephony.Sms._ID),
            threadId = longOrZero(Telephony.Sms.THREAD_ID),
            address = address.orEmpty(),
            body = body.orEmpty(),
            date = longOrZero(Telephony.Sms.DATE),
            type = intOrZero(Telephony.Sms.TYPE),
            status = intOrZero(Telephony.Sms.STATUS),
            read = intOrZero(Telephony.Sms.READ) == 1,
        )
    }

    private fun Cursor.longOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) 0L else getLong(index)
    }

    private fun Cursor.intOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) 0 else getInt(index)
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private companion object {
        val THREADS_URI: Uri = Telephony.Threads.CONTENT_URI
        val SMS_URI: Uri = Telephony.Sms.CONTENT_URI
        val MMS_URI: Uri = Telephony.Mms.CONTENT_URI
        const val MMS_ADDRESS_TYPE_FROM = 0x89
        fun PART_URI(messageId: Long): Uri =
            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$messageId/part")
        fun ADDRESS_URI(messageId: Long): Uri =
            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$messageId/addr")
    }
}