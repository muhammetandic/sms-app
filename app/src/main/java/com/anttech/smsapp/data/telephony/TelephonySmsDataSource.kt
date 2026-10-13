package com.anttech.smsapp.data.telephony

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
    /**
     * SMS rows from the provider with `_id > afterId`, oldest-first.
     *
     * Pass 0 (the default) for a full history read on first import. On
     * incremental runs pass the watermark so only rows that have not been
     * processed before are returned — turning an O(total history) scan into an
     * O(new rows) scan.
     */
    suspend fun queryAllMessages(limit: Int = 5_000, afterId: Long = 0L): List<ProviderMessage>

    /**
     * The highest provider `_id` currently visible, or null when the store is
     * empty. Cheap (a `MAX` on the rowid index) and read before a full
     * [queryAllMessages], so the import can prove nothing new arrived instead
     * of re-reading and re-sorting all of history.
     */
    suspend fun queryMaxMessageId(): Long?
}

/**
 * Reads SMS from the platform Telephony provider.
 *
 * Only the default SMS app can read this provider, so queries may throw
 * [SecurityException] when the role is not held; callers get an empty list
 * instead. Column access is guarded per column because OEM providers omit
 * columns and a missing one must degrade the row rather than crash the query.
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
     * Every SMS row in the provider with `_id > afterId`, across all threads.
     *
     * Rows are read in `_id` order (monotonic with insertion) in pages, with the
     * page size defaulting to the old hard limit so the query stays bounded per
     * round-trip. The loop keeps going until a page comes back short, so a large
     * inbox is imported in full instead of truncating at the first page.
     *
     * Passing the watermark as [afterId] turns an incremental import from an
     * O(total history) scan into an O(new rows) scan: the provider applies the
     * `_id > afterId` predicate against its rowid index.
     */
    override suspend fun queryAllMessages(limit: Int, afterId: Long): List<ProviderMessage> = withContext(Dispatchers.IO) {
        val messages = buildList {
            var lastSmsId = afterId
            while (true) {
                val page = querySmsPage(lastSmsId, limit)
                addAll(page)
                lastSmsId = page.lastOrNull()?.id ?: break
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

    override suspend fun queryMaxMessageId(): Long? = withContext(Dispatchers.IO) {
        // MAX over the provider's rowid index is O(1); scanning the whole table
        // would make the "nothing new" check as expensive as the import itself.
        val cursor = runCatching {
            resolver.query(
                SMS_URI,
                arrayOf("MAX(${Telephony.Sms._ID})"),
                null,
                null,
                null,
            )
        }.getOrNull() ?: return@withContext null

        cursor.use { rows ->
            if (!rows.moveToFirst() || rows.isNull(0)) null else rows.getLong(0)
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
    }
}