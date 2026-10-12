package com.anttech.smsapp.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
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
     * Loads the phone/name index in one provider query. The conversation list
     * often needs several names at once; issuing one PhoneLookup query per row
     * makes the first frame show numbers and leaves the list visibly catching
     * up behind the provider.
     */
    suspend fun preload() = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext

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
    suspend fun displayName(address: String): String? = withContext(Dispatchers.IO) {
        val key = address.trim()
        if (key.isEmpty()) return@withContext null

        // A cached null is a real answer: that number has no contact. Retrying
        // every scroll would repeat a query that is known to return nothing.
        val normalizedKey = ContactAddressNormalizer.normalize(key)
        synchronized(cache) {
            if (cache.containsKey(normalizedKey)) return@withContext cache[normalizedKey]
        }

        val name = runCatching { queryDisplayName(key) }.getOrNull()
        synchronized(cache) { cache[normalizedKey] = name }
        name
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
}
