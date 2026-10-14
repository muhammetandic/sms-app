package com.anttech.smsapp.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.notify.NotificationActionPreferences
import com.anttech.smsapp.notify.NotificationActionReceiver
import com.anttech.smsapp.sms.block.BlockedSenders
import com.anttech.smsapp.sms.send.SmsSubscriptionSettings
import com.anttech.smsapp.testing.MainDispatcherRule
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
}
