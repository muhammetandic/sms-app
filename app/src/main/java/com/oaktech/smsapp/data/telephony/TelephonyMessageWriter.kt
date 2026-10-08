package com.oaktech.smsapp.data.telephony

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One provider row this app wants to mutate.
 *
 * Rows are identified by their platform table and id because the SMS and MMS
 * tables number their rows independently; a message stored by this app holds
 * the [ProviderMessage.id] it was read from.
 */
data class ProviderRowRef(val providerId: Long, val isMms: Boolean)

/**
 * Mirrors destructive and read-state changes from the local store back to the
 * platform Telephony provider.
 *
 * The local Room store is the app's source of truth, but the platform provider
 * is the system's: a message this app deletes is still in the system SMS app's
 * inbox unless the provider row goes too, and a conversation opened here still
 * shows as unread there. Interfaces are the same seam as [SmsMessageSource] so
 * the write path is testable without a real provider.
 *
 * Only rows the app actually imported or sent carry a provider id; rows that
 * arrived live have no stable mapping back to the provider and are deliberately
 * left alone rather than guessed at by matching content.
 */
interface TelephonyMessageWriter {
    /** Deletes the given provider rows. */
    suspend fun delete(rows: List<ProviderRowRef>)

    /** Marks the given provider rows read. */
    suspend fun markRead(rows: List<ProviderRowRef>)

    /** Marks the given provider rows unread, so the system inbox badge returns. */
    suspend fun markUnread(rows: List<ProviderRowRef>)
}

/** Writer that does nothing, used when the app does not hold the SMS role. */
class NoopTelephonyMessageWriter : TelephonyMessageWriter {
    override suspend fun delete(rows: List<ProviderRowRef>) = Unit
    override suspend fun markRead(rows: List<ProviderRowRef>) = Unit
    override suspend fun markUnread(rows: List<ProviderRowRef>) = Unit
}

/**
 * Writes through to the platform provider.
 *
 * The app can only modify provider rows while it holds the default SMS role;
 * when the role is lost the same operations become [SecurityException], so each
 * row is written defensively and a failed row stops the loop without crashing.
 */
class PlatformTelephonyMessageWriter(context: Context) : TelephonyMessageWriter {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

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

    private fun uriFor(row: ProviderRowRef): Uri = Uri.withAppendedPath(
        if (row.isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI,
        row.providerId.toString(),
    )
}