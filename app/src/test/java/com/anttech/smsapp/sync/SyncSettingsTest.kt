package com.anttech.smsapp.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stored sync preferences.
 *
 * Robolectric because the store is `SharedPreferences`. Each test gets a fresh
 * application, so the preferences start empty without any cleanup.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun settings() = SyncSettings(context)

    @Test
    fun `sync is off until the user turns it on`() {
        val state = settings().state.value

        assertFalse(state.enabled)
        assertEquals(SyncStatus.NEVER, state.status)
        assertNull(state.lastSyncedAt)
        assertEquals(0, state.lastMergedCount)
    }

    @Test
    fun `the choice survives a new instance`() {
        settings().setEnabled(true)

        // The worker builds its own instance, so the preference, not the object,
        // is the source of truth.
        assertTrue(settings().isEnabled())
    }

    @Test
    fun `turning it off again is persisted`() {
        settings().setEnabled(true)
        settings().setEnabled(false)

        assertFalse(settings().isEnabled())
    }

    @Test
    fun `an instance built before the switch still sees the change`() {
        // The periodic worker runs through the process-wide repository, whose
        // SyncSettings was built at startup; the user flips the switch later on
        // the screen's own instance. The worker must act on the new value
        // without a refresh, or turning sync on would schedule a job that every
        // run finds "off" and does nothing - which is exactly what happened on
        // the device before isEnabled read the preference itself.
        val worker = settings()

        settings().setEnabled(true)

        assertTrue(worker.isEnabled())
    }

    @Test
    fun `the device id is generated once and reused`() {
        val first = settings().deviceId()

        assertTrue(first.isNotBlank())
        assertEquals(first, settings().deviceId())
    }

    @Test
    fun `two devices get different ids`() {
        // Two instances over the same preferences still share one id, so the
        // difference has to be observed across genuinely separate stores. This
        // pins the property that matters: the id is random, not derived from
        // anything the phone or the account would make identical on both.
        val mine = settings().deviceId()
        context.getSharedPreferences("drive_sync", Context.MODE_PRIVATE)
            .edit().clear().commit()

        assertNotEquals(mine, settings().deviceId())
    }

    @Test
    fun `a successful sync records its time and count`() {
        val settings = settings()

        settings.recordSuccess(syncedAt = 1_700_000_000_000L, mergedCount = 7)

        val state = settings.state.value
        assertEquals(SyncStatus.SUCCESS, state.status)
        assertEquals(1_700_000_000_000L, state.lastSyncedAt)
        assertEquals(7, state.lastMergedCount)
        assertNull(state.lastError)
    }

    @Test
    fun `a success clears an earlier error`() {
        val settings = settings()
        settings.recordStatus(SyncStatus.FAILED, "network is down")

        settings.recordSuccess(syncedAt = 5L, mergedCount = 0)

        assertNull(settings.state.value.lastError)
    }

    @Test
    fun `a failure keeps its message alongside the status`() {
        val settings = settings()

        settings.recordStatus(SyncStatus.FAILED, "no route to host")

        assertEquals(SyncStatus.FAILED, settings.state.value.status)
        assertEquals("no route to host", settings.state.value.lastError)
    }

    @Test
    fun `a sign-in requirement is a status of its own`() {
        // Distinct from FAILED on purpose: only the user can clear it, so the
        // screen must be able to tell the two apart.
        val settings = settings()

        settings.recordStatus(SyncStatus.NEEDS_SIGN_IN)

        assertEquals(SyncStatus.NEEDS_SIGN_IN, settings.state.value.status)
        assertNull(settings.state.value.lastError)
    }

    @Test
    fun `a status survives a new instance`() {
        settings().recordStatus(SyncStatus.NEEDS_SIGN_IN)

        assertEquals(SyncStatus.NEEDS_SIGN_IN, settings().state.value.status)
    }

    @Test
    fun `an unrecognised stored status reads as never`() {
        // A downgrade, or a value left by a build that named things differently,
        // must not crash the screen.
        context.getSharedPreferences("drive_sync", Context.MODE_PRIVATE)
            .edit().putString("status", "SOMETHING_ELSE").commit()

        assertEquals(SyncStatus.NEVER, settings().state.value.status)
    }

    @Test
    fun `merge cursors round trip per file`() {
        val settings = settings()

        settings.recordMergedVersion("messages-a.jsonl.gz", 12L)
        settings.recordMergedVersion("messages-b.jsonl.gz", 34L)

        assertEquals(
            mapOf("messages-a.jsonl.gz" to 12L, "messages-b.jsonl.gz" to 34L),
            settings.mergedVersions(),
        )
    }

    @Test
    fun `re-recording a file replaces its cursor`() {
        val settings = settings()
        settings.recordMergedVersion("messages-a.jsonl.gz", 12L)

        settings.recordMergedVersion("messages-a.jsonl.gz", 99L)

        assertEquals(mapOf("messages-a.jsonl.gz" to 99L), settings.mergedVersions())
    }

    @Test
    fun `a device with no cursors has an empty map`() {
        assertTrue(settings().mergedVersions().isEmpty())
    }

    @Test
    fun `refresh picks up a write made through another instance`() {
        // The settings screen holds one instance and the periodic worker holds
        // another; without refresh the screen would show a stale result forever.
        val screen = settings()
        val worker = settings()

        worker.recordSuccess(syncedAt = 42L, mergedCount = 3)
        screen.refresh()

        assertEquals(42L, screen.state.value.lastSyncedAt)
        assertEquals(3, screen.state.value.lastMergedCount)
    }
}
