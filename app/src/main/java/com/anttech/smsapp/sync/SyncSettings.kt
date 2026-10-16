package com.anttech.smsapp.sync

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** How the last sync attempt ended, for the settings screen to phrase. */
enum class SyncStatus {
    /** Sync is on, but no attempt has completed yet. */
    NEVER,

    /** The last attempt reached Drive and merged successfully. */
    SUCCESS,

    /**
     * The last attempt found no usable grant. Distinct from [FAILED] because
     * the user can fix this with the connect button, and no retry can.
     */
    NEEDS_SIGN_IN,

    /** The last attempt failed for a reason a retry might clear. */
    FAILED,
}

/**
 * What the settings screen draws, and what the worker records.
 *
 * [lastMergedCount] counts only messages the store did not already hold, so the
 * number reads as "what arrived from the other phone", not as a total.
 */
data class SyncState(
    val enabled: Boolean = false,
    val status: SyncStatus = SyncStatus.NEVER,
    val lastSyncedAt: Long? = null,
    val lastMergedCount: Int = 0,
    val lastError: String? = null,
)

/**
 * The stored Drive sync choices and the outcome of the last attempt.
 *
 * A plain preference store, deliberately free of WorkManager and Play services
 * so the settings ViewModel that reads it stays testable off-device. Two
 * instances exist at once - the screen's and the worker's - so writes go to
 * SharedPreferences, and a screen that was away while the worker wrote calls
 * [refresh] to pick the result up.
 *
 * [deviceId] is generated once per install and never derived from the phone or
 * the account. It names this device's file in the shared folder, so a reinstall
 * that generates a new id simply becomes one more member of the set rather than
 * fighting the old file for the name.
 */
class SyncSettings(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Whether sync is on, read straight from the preference store.
     *
     * Deliberately not from [state]: the periodic worker runs through the
     * process-wide repository, whose [SyncSettings] was built when the process
     * started, while the switch flips the value much later on the screen's own
     * instance. A cached read would leave the worker acting on whatever was
     * stored at startup - so turning sync on would schedule the job but every
     * run would find it "off" and do nothing until the process happened to
     * restart.
     */
    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    /** Turns sync on or off. Scheduling follows elsewhere; this only records it. */
    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        _state.value = _state.value.copy(enabled = enabled)
    }

    /** The stable per-install file identity, generated on first use. */
    @Synchronized
    fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit { putString(KEY_DEVICE_ID, generated) }
        return generated
    }

    /**
     * Remote file name to the revision already merged from it.
     *
     * The cursor that keeps a steady-state sync to one list and one upload: a
     * file whose version is unchanged since the device last read it is not
     * downloaded again.
     */
    @Synchronized
    fun mergedVersions(): Map<String, Long> {
        val raw = preferences.getString(KEY_MERGED_VERSIONS, null) ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (name in json.keys()) put(name, json.optLong(name))
        }
    }

    /** Records that the named remote file has been read up to [version]. */
    @Synchronized
    fun recordMergedVersion(fileName: String, version: Long) {
        val json = JSONObject()
        for ((name, value) in mergedVersions() + (fileName to version)) {
            json.put(name, value)
        }
        preferences.edit { putString(KEY_MERGED_VERSIONS, json.toString()) }
    }

    /** Records a completed sync and how many messages it added. */
    fun recordSuccess(syncedAt: Long, mergedCount: Int) {
        preferences.edit {
            putString(KEY_STATUS, SyncStatus.SUCCESS.name)
            putLong(KEY_LAST_SYNCED_AT, syncedAt)
            putInt(KEY_LAST_MERGED_COUNT, mergedCount)
            remove(KEY_LAST_ERROR)
        }
        _state.value = _state.value.copy(
            status = SyncStatus.SUCCESS,
            lastSyncedAt = syncedAt,
            lastMergedCount = mergedCount,
            lastError = null,
        )
    }

    /** Records an attempt that did not complete, and why. */
    fun recordStatus(status: SyncStatus, error: String? = null) {
        preferences.edit {
            putString(KEY_STATUS, status.name)
            if (error == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, error)
        }
        _state.value = _state.value.copy(status = status, lastError = error)
    }

    /**
     * Re-reads everything from the preference store.
     *
     * The worker writes through its own instance, so a screen that was open
     * while a background sync ran would otherwise show the state it last wrote.
     */
    fun refresh() {
        _state.value = load()
    }

    private fun load(): SyncState = SyncState(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        status = runCatching {
            SyncStatus.valueOf(preferences.getString(KEY_STATUS, null).orEmpty())
        }.getOrDefault(SyncStatus.NEVER),
        lastSyncedAt = preferences.getLong(KEY_LAST_SYNCED_AT, NO_TIME).takeIf { it != NO_TIME },
        lastMergedCount = preferences.getInt(KEY_LAST_MERGED_COUNT, 0),
        lastError = preferences.getString(KEY_LAST_ERROR, null),
    )

    private companion object {
        const val PREFERENCES_NAME = "drive_sync"
        const val KEY_ENABLED = "enabled"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_STATUS = "status"
        const val KEY_LAST_SYNCED_AT = "last_synced_at"
        const val KEY_LAST_MERGED_COUNT = "last_merged_count"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_MERGED_VERSIONS = "merged_versions"

        /** Long has no null; a negative sentinel reads as "never" on the screen. */
        const val NO_TIME = -1L
    }
}
