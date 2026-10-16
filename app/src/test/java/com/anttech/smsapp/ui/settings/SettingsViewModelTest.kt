package com.anttech.smsapp.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.notify.NotificationActionPreferences
import com.anttech.smsapp.notify.NotificationActionReceiver
import com.anttech.smsapp.sms.block.BlockedSenders
import com.anttech.smsapp.sms.send.SmsSubscriptionSettings
import com.anttech.smsapp.sync.SyncOutcome
import com.anttech.smsapp.sync.SyncSettings
import com.anttech.smsapp.sync.SyncStatus
import com.anttech.smsapp.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings screen's stored preferences.
 *
 * Every toggle is a write to a preference store read back through a fresh
 * instance, so the assertions prove both the in-memory state the screen draws
 * from and that the choice survives a restart.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun viewModel() = SettingsViewModel(context, mainDispatcherRule.testDispatcher)

    /**
     * A view model whose sync is the given lambda rather than the real Drive
     * store, so none of these tests build a graph, a database or a Play
     * services client.
     */
    private fun viewModel(runSync: suspend () -> SyncOutcome) =
        SettingsViewModel(context, mainDispatcherRule.testDispatcher, runSync)

    @Test
    fun `a notification action starts enabled and persists when turned off`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val model = viewModel()
            val initial = model.uiState.first { it.notificationActions.isNotEmpty() }
            assertTrue(initial.notificationActions.getValue(NotificationActionReceiver.ACTION_REPLY))

            model.setNotificationAction(NotificationActionReceiver.ACTION_REPLY, false)

            val updated = model.uiState
                .first { !it.notificationActions.getValue(NotificationActionReceiver.ACTION_REPLY) }
            assertFalse(updated.notificationActions.getValue(NotificationActionReceiver.ACTION_REPLY))
            assertFalse(NotificationActionPreferences(context).isEnabled(NotificationActionReceiver.ACTION_REPLY))
        }

    @Test
    fun `choosing a SIM persists the preference`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel()

        model.chooseSubscription(3)

        assertEquals(3, model.uiState.first { it.preferredSubscriptionId == 3 }.preferredSubscriptionId)
        assertEquals(3, SmsSubscriptionSettings(context).preferredSubscriptionId())
    }

    @Test
    fun `blocking a sender persists and unblocking clears it`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val model = viewModel()

            model.block("+15551234567")

            val blocked = model.uiState.first { it.blockedSenders.isNotEmpty() }.blockedSenders
            assertTrue(BlockedSenders(context).isBlocked("+15551234567"))

            model.unblock(blocked.first())

            assertTrue(model.uiState.first { it.blockedSenders.isEmpty() }.blockedSenders.isEmpty())
            assertFalse(BlockedSenders(context).isBlocked("+15551234567"))
        }

    @Test
    fun `sync starts off and turning it on persists`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel()

        assertFalse(model.uiState.first().syncEnabled)

        model.setSyncEnabled(true)

        assertTrue(model.uiState.first { it.syncEnabled }.syncEnabled)
        // The worker reads the preference through its own instance, so the
        // object's state is not the thing that matters.
        assertTrue(SyncSettings(context).isEnabled())
    }

    @Test
    fun `sync now runs the sync and reflects what it recorded`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val model = viewModel {
                // Stand in for the repository, which is what writes the result.
                SyncSettings(context).recordSuccess(syncedAt = 42L, mergedCount = 5)
                SyncOutcome.Success(5)
            }
            model.setSyncEnabled(true)

            model.syncNow()

            // Read back from the store rather than from the returned outcome, so
            // the button and a background sync can never disagree.
            val state = model.uiState.first { it.syncLastSyncedAt != null }
            assertEquals(42L, state.syncLastSyncedAt)
            assertEquals(5, state.syncLastMergedCount)
            assertFalse(state.syncing)
        }

    @Test
    fun `sync now ignores a second press while one is running`() =
        runTest(mainDispatcherRule.testDispatcher) {
            var runs = 0
            val gate = CompletableDeferred<Unit>()
            val model = viewModel {
                runs += 1
                gate.await()
                SyncOutcome.Success(0)
            }
            model.setSyncEnabled(true)

            model.syncNow()
            assertTrue(model.uiState.first { it.syncing }.syncing)
            model.syncNow()

            gate.complete(Unit)

            assertFalse(model.uiState.first { !it.syncing }.syncing)
            assertEquals(1, runs)
        }

    @Test
    fun `a result recorded in the background is pulled in on refresh`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val model = viewModel()
            model.setSyncEnabled(true)

            // The periodic worker writes through its own instance, so the screen
            // has no way to see this without asking.
            SyncSettings(context).recordStatus(SyncStatus.NEEDS_SIGN_IN)

            model.refreshSyncState()

            assertEquals(
                SyncStatus.NEEDS_SIGN_IN,
                model.uiState.first { it.syncStatus == SyncStatus.NEEDS_SIGN_IN }.syncStatus,
            )
        }

    @Test
    fun `connecting Drive turns sync on and syncs once`() =
        runTest(mainDispatcherRule.testDispatcher) {
            var runs = 0
            val model = viewModel {
                runs += 1
                SyncOutcome.Success(0)
            }

            model.onDriveConnected()

            assertTrue(model.uiState.first { it.syncEnabled }.syncEnabled)
            assertEquals(1, runs)
        }

    @Test
    fun `a connection that was not granted asks for sign-in`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val model = viewModel()
            model.setSyncEnabled(true)

            model.onDriveConnectionFailed("no OAuth client registered")

            assertEquals(
                SyncStatus.NEEDS_SIGN_IN,
                model.uiState.first { it.syncStatus == SyncStatus.NEEDS_SIGN_IN }.syncStatus,
            )
        }
}
