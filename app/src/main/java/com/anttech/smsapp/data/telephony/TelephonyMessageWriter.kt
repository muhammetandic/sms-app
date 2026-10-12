package com.anttech.smsapp.data.telephony

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.anttech.smsapp.sms.receive.InboundMessage
import com.anttech.smsapp.sms.receive.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One provider row this app wants to mutate.
 *
 * A message stored by this app holds the [ProviderMessage.id] it was read from.
 */
data class ProviderRowRef(val providerId: Long)

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
        Telephony.Sms.CONTENT_URI,
        row.providerId.toString(),
    )
}