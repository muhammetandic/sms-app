package com.oaktech.smsapp.data.telephony

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.oaktech.smsapp.sms.receive.InboundMessage
import com.oaktech.smsapp.sms.receive.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One provider row this app wants to mutate.
 *
 * Rows are identified by their platform table and id because the SMS and MMS
 * tables number their rows independently; a message stored by this app holds
 * the [ProviderMessage.id] it was read from.
 */
data class ProviderRowRef(val providerId: Long, val isMms: Boolean)

/**
 * Mirrors this app's message handling back to the platform Telephony provider.
 *
 * The local Room store is the app's source of truth, but the platform provider
 * is the system's: a message this app deletes is still in the system SMS app's
 * inbox unless the provider row goes too, and a conversation opened here still
 * shows as unread there. Received and sent messages have to be written there in
 * the first place, because as the default SMS app this one is what the platform
 * expects to create those rows. Interfaces are the same seam as
 * [SmsMessageSource] so the write path is testable without a real provider.
 *
 * Every method identifies rows by the [ProviderRowRef] they were read with (or
 * returned from an insert), so a row is never matched back to the provider by
 * guessing on content.
 */
interface TelephonyMessageWriter {
    /** Deletes the given provider rows. */
    suspend fun delete(rows: List<ProviderRowRef>)

    /** Marks the given provider rows read. */
    suspend fun markRead(rows: List<ProviderRowRef>)

    /** Marks the given provider rows unread, so the system inbox badge returns. */
    suspend fun markUnread(rows: List<ProviderRowRef>)

    /**
     * Writes a received message into the provider inbox and returns its row id.
     *
     * Null when the row could not be stored: the provider only accepts writes
     * from the default SMS app, so a role loss mid-session leaves the local
     * store as the only copy rather than throwing out of the receive path.
     */
    suspend fun insertInbound(message: InboundMessage): Long?

    /**
     * Writes an accepted outgoing message into the provider's sent table and
     * returns its row id. Same refusal contract as [insertInbound].
     */
    suspend fun insertSent(address: String, body: String, sentAt: Long): Long?

    /**
     * Writes a received MMS into the provider inbox and returns its row id.
     *
     * The row, its From address and every part go in together. Same refusal
     * contract as [insertInbound]: a role loss leaves the local copy as the only
     * one rather than throwing out of the receive path.
     */
    suspend fun insertInboundMms(message: ProviderMms): Long?

    /**
     * Writes an accepted outgoing MMS into the provider's sent table and
     * returns its row id. Same refusal contract as [insertSent].
     */
    suspend fun insertSentMms(message: ProviderMms): Long?

    /**
     * Flips a row this app already wrote from sent to failed, so the system SMS
     * app does not keep showing a send the radio turned down.
     */
    suspend fun markSentFailed(row: ProviderRowRef)
}

/** Writer that does nothing, used when the app does not hold the SMS role. */
class NoopTelephonyMessageWriter : TelephonyMessageWriter {
    override suspend fun delete(rows: List<ProviderRowRef>) = Unit
    override suspend fun markRead(rows: List<ProviderRowRef>) = Unit
    override suspend fun markUnread(rows: List<ProviderRowRef>) = Unit
    override suspend fun insertInbound(message: InboundMessage): Long? = null
    override suspend fun insertSent(address: String, body: String, sentAt: Long): Long? = null
    override suspend fun insertInboundMms(message: ProviderMms): Long? = null
    override suspend fun insertSentMms(message: ProviderMms): Long? = null
    override suspend fun markSentFailed(row: ProviderRowRef) = Unit
}

/**
 * Writes through to the platform provider.
 *
 * The app can only modify provider rows while it holds the default SMS role;
 * when the role is lost the same operations become [SecurityException], so each
 * row is written defensively and a failed row stops the loop without crashing.
 */
class PlatformTelephonyMessageWriter(context: Context) : TelephonyMessageWriter {

    private val appContext = context.applicationContext

    private val resolver: ContentResolver = appContext.contentResolver

    override suspend fun delete(rows: List<ProviderRowRef>) = withContext(Dispatchers.IO) {
        for (row in rows) {
            runCatching { resolver.delete(uriFor(row), null, null) }
        }
    }

    override suspend fun markRead(rows: List<ProviderRowRef>) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
        for (row in rows) {
            runCatching { resolver.update(uriFor(row), values, null, null) }
        }
    }

    override suspend fun markUnread(rows: List<ProviderRowRef>) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 0) }
        for (row in rows) {
            runCatching { resolver.update(uriFor(row), values, null, null) }
        }
    }

    override suspend fun insertInbound(message: InboundMessage): Long? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, message.address)
            put(Telephony.Sms.BODY, message.body)
            // The provider's `date` is what a later import reads back and
            // fingerprints against the local row, so it has to be the same
            // timestamp the receive path stored: any other value would give one
            // message two keys and file it twice.
            put(Telephony.Sms.DATE, message.timestampMillis)
            put(Telephony.Sms.DATE_SENT, message.timestampMillis)
            put(Telephony.Sms.READ, 0)
            // This app has already decided whether to notify about the message
            // (including deciding not to, for spam and muted conversations), so
            // the platform has nothing left to announce.
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        insertInto(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    override suspend fun insertSent(address: String, body: String, sentAt: Long): Long? =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, sentAt)
                put(Telephony.Sms.DATE_SENT, sentAt)
                // Sent rows are never pending for anyone else: the user is
                // reading them in this app, and a `read` flag left clear would
                // show as an unopened conversation in the system app.
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            insertInto(Telephony.Sms.Sent.CONTENT_URI, values)
        }

    override suspend fun insertInboundMms(message: ProviderMms): Long? =
        insertMms(message, Telephony.Mms.Inbox.CONTENT_URI)

    override suspend fun insertSentMms(message: ProviderMms): Long? =
        insertMms(message, Telephony.Mms.Sent.CONTENT_URI)

    /**
     * Writes one MMS: the `pdu` row, its address rows and every part.
     *
     * A part's bytes cannot be handed over in the insert (the provider rejects
     * `_data` from a foreign package), so each part is inserted first and then
     * its bytes are streamed through the URI the provider returns. The whole
     * write is guarded by one runCatching so a refusal anywhere stops it with a
     * log line instead of escaping the receive path.
     */
    private suspend fun insertMms(message: ProviderMms, boxUri: Uri): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val threadId = Telephony.Threads.getOrCreateThreadId(appContext, message.address)
            val values = ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, threadId)
                // The provider's MMS times are seconds (its SMS times are
                // milliseconds), so the same instant has to survive a re-read.
                put(Telephony.Mms.DATE, message.timestampMillis / 1000L)
                put(Telephony.Mms.DATE_SENT, message.timestampMillis / 1000L)
                message.subject?.let { put(Telephony.Mms.SUBJECT, it) }
                put(Telephony.Mms.CONTENT_TYPE, contentTypeFor(message.parts))
                put(Telephony.Mms.MESSAGE_SIZE, message.parts.sumOf(::partSize))
                // Inbound starts unread, but the app has already decided
                // whether to alert, so SEEN is set either way; outbound is read
                // because the user is looking at it here.
                put(Telephony.Mms.READ, if (message.outgoing) 1 else 0)
                put(Telephony.Mms.SEEN, 1)
            }
            val rowUri = resolver.insert(boxUri, values) ?: error("provider refused the MMS row")
            val mmsId = ContentUris.parseId(rowUri)

            if (message.outgoing) {
                message.recipients.forEach { insertMmsAddress(mmsId, it, ADDRESS_TYPE_TO) }
            } else {
                insertMmsAddress(mmsId, message.address, ADDRESS_TYPE_FROM)
            }
            message.parts.forEach { insertMmsPart(mmsId, it) }
            mmsId
        }
            .onFailure { Log.w(LogTags.STORE, "MMS provider write refused", it) }
            .getOrNull()
    }

    private fun insertMmsAddress(mmsId: Long, address: String, type: Int) {
        val uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$mmsId/addr")
        val values = ContentValues().apply {
            put(Telephony.Mms.Addr.ADDRESS, address)
            put(Telephony.Mms.Addr.TYPE, type)
            put(Telephony.Mms.Addr.CHARSET, ADDRESS_CHARSET_UTF8)
        }
        runCatching { resolver.insert(uri, values) }
            .onFailure { Log.w(LogTags.STORE, "MMS address write refused for $mmsId", it) }
    }

    private fun insertMmsPart(mmsId: Long, part: ProviderMmsPart) {
        val uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$mmsId/part")
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
            part.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, it) }
            part.fileName?.let { put(Telephony.Mms.Part.FILENAME, it) }
            part.text?.let { put(Telephony.Mms.Part.TEXT, it) }
        }
        val partUri = runCatching { resolver.insert(uri, values) }
            .onFailure { Log.w(LogTags.STORE, "MMS part write refused for $mmsId", it) }
            .getOrNull()
            ?: return

        // Inline parts are already complete. A media part is not: the provider
        // created the file for it, so the bytes have to be written through the
        // minted URI or the part stays empty.
        val path = part.filePath ?: return
        val file = File(path)
        if (!file.isFile) {
            Log.w(LogTags.STORE, "MMS part file is missing: $path")
            return
        }
        runCatching {
            resolver.openOutputStream(partUri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }.onFailure { Log.w(LogTags.STORE, "MMS part bytes write refused for $mmsId", it) }
    }

    private fun contentTypeFor(parts: List<ProviderMmsPart>): String =
        if (parts.any { it.filePath != null }) MULTIPART_MIME else TEXT_PLAIN_MIME

    private fun partSize(part: ProviderMmsPart): Long {
        part.text?.let { return it.toByteArray(Charsets.UTF_8).size.toLong() }
        val path = part.filePath ?: return 0L
        return File(path).length()
    }

    override suspend fun markSentFailed(row: ProviderRowRef) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
            }
            runCatching { resolver.update(uriFor(row), values, null, null) }
        }
    }

    /**
     * Inserts and returns the new row's id, or null when the provider refused
     * the write. The refusal is a log line rather than an exception because the
     * receive path must keep its local copy whatever the provider says.
     */
    private suspend fun insertInto(uri: Uri, values: ContentValues): Long? {
        val rowUri = runCatching { resolver.insert(uri, values) }
            .onFailure { Log.w(LogTags.STORE, "Provider write to $uri refused", it) }
            .getOrNull()
            ?: return null
        return runCatching { ContentUris.parseId(rowUri) }
            .onFailure { Log.w(LogTags.STORE, "Provider returned an unreadable row id: $rowUri", it) }
            .getOrNull()
    }

    private fun uriFor(row: ProviderRowRef): Uri = Uri.withAppendedPath(
        if (row.isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI,
        row.providerId.toString(),
    )

    private companion object {
        // PduHeaders address types. FROM is what the read side looks for when it
        // turns a provider row back into a conversation address.
        const val ADDRESS_TYPE_FROM = 0x89
        const val ADDRESS_TYPE_TO = 0x81

        // WSP character set 106 (UTF-8), the value AOSP writes for addresses.
        const val ADDRESS_CHARSET_UTF8 = 106

        const val MULTIPART_MIME = "application/vnd.wap.multipart.related"
        const val TEXT_PLAIN_MIME = "text/plain"
    }
}