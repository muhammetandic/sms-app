package com.anttech.smsapp.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves phone numbers to contact names for the conversation list.
 *
 * [READ_CONTACTS] is only held while the app is the default SMS app, so every
 * lookup can fail with [SecurityException] after a role loss. Callers get
 * `null` and fall back to the raw number rather than an empty row.
 *
 * Lookups are cached per process: a conversation list re-resolves the same few
 * senders on every scroll, and each miss is a provider query.
 */
class ContactNameResolver(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val cache = HashMap<String, String?>()

    /**
     * Uptime of the last [preload], or far enough in the past that the first one
     * always runs. Best-effort: two callers racing the check can each run once,
     * which only repeats an idempotent write into [cache].
     */
    @Volatile
    private var lastPreloadElapsed: Long = -PRELOAD_THROTTLE_MILLIS

    /**
     * Loads the phone/name index in one provider query. The conversation list
     * often needs several names at once; issuing one PhoneLookup query per row
     * makes the first frame show numbers and leaves the list visibly catching
     * up behind the provider.
     */
    suspend fun preload() = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext

        // The inbox re-resolves on every resume and each folder on entry, so this
        // is asked for far more often than contacts change. A full
        // Phone.CONTENT_URI read plus a normalize per number is real work on a
        // large address book, so a run inside this window is skipped: a contact
        // saved a moment ago is picked up on the next resume instead of the whole
        // table being re-read on every screen transition. The window is short
        // enough that a name the user just added still lands promptly.
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreloadElapsed < PRELOAD_THROTTLE_MILLIS) return@withContext
        lastPreloadElapsed = now

        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                if (numberIndex < 0 || nameIndex < 0) return@use

                synchronized(cache) {
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(numberIndex)?.trim().orEmpty()
                        if (number.isNotEmpty()) {
                            cache[ContactAddressNormalizer.normalize(number)] =
                                cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Display name for [address], or null when no contact matches or the
     * contacts permission is not held.
     */
    suspend fun displayName(address: String): String? {
        val key = address.trim()
        if (key.isEmpty()) return null

        // A cached null is a real answer: that number has no contact. Retrying
        // every scroll would repeat a query that is known to return nothing.
        val normalizedKey = ContactAddressNormalizer.normalize(key)
        synchronized(cache) {
            if (cache.containsKey(normalizedKey)) return cache[normalizedKey]
        }

        // Only a miss reaches the provider, so only a miss needs moving off the
        // caller's thread. A hit returns on the current one: the phrase search
        // resolves a name per conversation, and dispatching hundreds of cache
        // hits would cost more than the lookups they avoid.
        return withContext(Dispatchers.IO) {
            val name = runCatching { queryDisplayName(key) }.getOrNull()
            synchronized(cache) { cache[normalizedKey] = name }
            name
        }
    }

    /**
     * Display label for a stored [displayAddress], which names several
     * recipients when a conversation is a group ("+1..., +2..."). Each part is
     * resolved on its own so a group of contacts reads as names, not as the
     * whole joined number: a known part shows its name and an unknown part keeps
     * the number it stored. A single address is just the one-part case.
     *
     * Null when nothing resolved, so the caller keeps the address as stored
     * rather than a churned-up copy of the same digits.
     */
    suspend fun displayLabel(displayAddress: String): String? {
        val parts = displayAddress.splitAddresses()
        return joinContactLabels(parts.map { part -> part to displayName(part) })
    }

    /**
     * True when the app may read contacts right now.
     *
     * Checked rather than assumed: `READ_CONTACTS` is only held while the app is
     * the default SMS app, so it disappears on a role loss.
     */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun queryDisplayName(address: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address),
        )

        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.NUMBER,
        )

        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            // Prefer the name of the contact matching this exact number rather
            // than the first row: a lookup can return several contacts when a
            // number is shared, and picking arbitrarily shows the wrong name.
            val best = cursor.chooseBestMatchingName(address)
            best ?: cursor.stringOrNull(ContactsContract.PhoneLookup.DISPLAY_NAME)
        }
    }

    private fun Cursor.chooseBestMatchingName(address: String): String? {
        val numberIndex = getColumnIndex(ContactsContract.PhoneLookup.NUMBER)
        if (numberIndex < 0) return null

        val normalizedTarget = ContactAddressNormalizer.normalize(address)
        val names = mutableListOf<Pair<String?, String?>>()
        do {
            names += stringOrNull(ContactsContract.PhoneLookup.NUMBER) to
                stringOrNull(ContactsContract.PhoneLookup.DISPLAY_NAME)
        } while (moveToNext())

        return names
            .firstOrNull { (number, _) ->
                number != null && ContactAddressNormalizer.normalize(number) == normalizedTarget
            }
            ?.second
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private companion object {
        /** Contacts change rarely; a re-read this soon after the last is skipped. */
        const val PRELOAD_THROTTLE_MILLIS = 5_000L
    }
}

/**
 * The individual addresses packed into a stored display address. A group
 * conversation keeps its recipients comma-separated in one field.
 */
internal fun String.splitAddresses(): List<String> =
    split(',').map(String::trim).filter(String::isNotEmpty)

/**
 * Folds resolved recipients into one label: each [parts] entry shows its
 * resolved name when there is one and the raw address otherwise. Returns null
 * when no part resolved, so the caller keeps the stored address instead of an
 * equivalent copy of it.
 *
 * Pure so the group/label rule is unit-testable without a contacts provider.
 */
internal fun joinContactLabels(parts: List<Pair<String, String?>>): String? {
    if (parts.isEmpty()) return null

    var anyResolved = false
    val labels = parts.map { (address, name) ->
        if (name != null) anyResolved = true
        name ?: address
    }
    return if (anyResolved) labels.joinToString(", ") else null
}
