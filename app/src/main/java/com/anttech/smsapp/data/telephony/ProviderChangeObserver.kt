package com.anttech.smsapp.data.telephony

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.anttech.smsapp.sms.receive.LogTags
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watches the Telephony provider while the process lives and re-runs the
 * history import when its rows change.
 *
 * The app's own stores mirror every message it handles, so a user never needs
 * to poll for their own traffic. But the provider is also written by the
 * platform (delivery reports) and by whichever app held the SMS role before this
 * one: rows that this app did not originate. Without an observer those only reach
 * the store on the next launch, leaving the inbox stale for the whole session.
 *
 * The callback is [TelephonySmsDataSource]-style gated: the import coordinator
 * checks the role before querying, so a notification is harmless when the app
 * is not the default SMS app (the read would throw and be caught).
 *
 * Notifications are debounced: a message arrives as a burst of row writes, and
 * the observer fires once per row. Importing once is enough, so the callback
 * only runs after the row writes quiet down.
 */
class ProviderChangeObserver(
    context: Context,
    private val onProviderChanged: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    private val handler = Handler(Looper.getMainLooper())

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // A write this process made. The app mirrors its own deletes and
            // read-state through [PlatformTelephonyMessageWriter], and re-importing
            // those rows would be a pointless full scan; the store already knows
            // about them.
            if (selfChange) return
            scheduleImport()
        }
    }

    private val importRunnable = Runnable { trigger() }

    /** Registers on the SMS and thread tables, including row-level notifications. */
    fun start() {
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        resolver.registerContentObserver(Telephony.Threads.CONTENT_URI, true, observer)
    }

    /**
     * The underlying observer, exposed so tests can drive [ContentObserver.onChange]
     * with an explicit self-change flag rather than rely on Robolectric's resolver
     * notification semantics.
     */
    @get:VisibleForTesting
    internal val contentObserver: ContentObserver = observer

    /** Unregisters so a restarted process does not double-observe. */
    fun stop() {
        resolver.unregisterContentObserver(observer)
        handler.removeCallbacks(importRunnable)
    }

    private fun scheduleImport() {
        // Debounce: coalesce a burst of row writes into one import, and reset
        // the clock on each new write so the import waits for the burst to end.
        handler.removeCallbacks(importRunnable)
        handler.postDelayed(importRunnable, debounceMillis)
    }

    private fun trigger() {
        scope.launch {
            runCatching { onProviderChanged() }
                .onFailure { Log.w(LogTags.STORE, "Provider change import failed", it) }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 1_000L
    }
}