package com.anttech.smsapp.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.notify.NotificationActionPreferences
import com.anttech.smsapp.notify.NotificationActionReceiver
import com.anttech.smsapp.sms.block.BlockedSenders
import com.anttech.smsapp.sms.send.SmsSubscription
import com.anttech.smsapp.sms.send.SmsSubscriptionSettings
import com.anttech.smsapp.sms.send.activeSmsSubscriptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Everything the settings screen shows, except whether the SMS role is held.
 *
 * The role is re-read on every resume (`rememberSmsRoleState`) and so stays with
 * the screen's lifecycle; the rest is preference state, which is why it lives
 * here where it can be read and written in one place.
 */
data class SettingsUiState(
    val versionName: String = "",
    val subscriptions: List<SmsSubscription> = emptyList(),
    val preferredSubscriptionId: Int? = null,
    val notificationActions: Map<String, Boolean> = emptyMap(),
    val blockedSenders: Set<String> = emptySet(),
)

/**
 * The settings screen's reads and writes.
 *
 * The version and the SIM list are read once: neither can change while the
 * process is alive, and re-querying the telephony stack on every recomposition
 * would be one provider call per frame. The preferred SIM, the notification
 * actions and the blocked list are kept in state so a change shows immediately
 * rather than after a reload.
 */
class SettingsViewModel(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val subscriptionSettings = SmsSubscriptionSettings(appContext)
    private val notificationActions = NotificationActionPreferences(appContext)
    private val blockedSenders = BlockedSenders(appContext)

    private val versionName = readVersionName()
    private val subscriptions = activeSmsSubscriptions(appContext)

    private val preferredSubscriptionId =
        MutableStateFlow(subscriptionSettings.preferredSubscriptionId())

    private val notificationActionStates = MutableStateFlow(
        NotificationActionReceiver.ALL_ACTIONS.associateWith(notificationActions::isEnabled),
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        blockedSenders.blocks,
        preferredSubscriptionId,
        notificationActionStates,
    ) { blocked, preferred, actions ->
        SettingsUiState(
            versionName = versionName,
            subscriptions = subscriptions,
            preferredSubscriptionId = preferred,
            notificationActions = actions,
            blockedSenders = blocked,
        )
        // The emission copies the whole blocked set; keep that off the main
        // thread so a long list does not stutter the frame that shows it.
    }.flowOn(dispatcher).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        // The first frame must already show the stored values, not an empty
        // default the user would see every time the screen opens.
        initialValue = SettingsUiState(
            versionName = versionName,
            subscriptions = subscriptions,
            preferredSubscriptionId = preferredSubscriptionId.value,
            notificationActions = notificationActionStates.value,
            blockedSenders = blockedSenders.blocks.value,
        ),
    )

    /** Persists the chosen SIM and reflects it without waiting for a reload. */
    fun chooseSubscription(subscriptionId: Int?) {
        subscriptionSettings.setPreferredSubscriptionId(subscriptionId)
        preferredSubscriptionId.value = subscriptionId
    }

    /** Persists one notification action's visibility and reflects it at once. */
    fun setNotificationAction(action: String, enabled: Boolean) {
        notificationActions.setEnabled(action, enabled)
        notificationActionStates.update { it + (action to enabled) }
    }

    /** Blocks a sender, ignoring blanks and duplicates. */
    fun block(sender: String) {
        blockedSenders.add(sender)
    }

    /** Unblocks a sender. */
    fun unblock(sender: String) {
        blockedSenders.remove(sender)
    }

    private fun readVersionName(): String {
        // PackageManager only hands out PackageInfoFlags from API 33; the
        // flagless overload is deprecated after that, so one path per platform.
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return packageInfo.versionName.orEmpty()
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(context.applicationContext) as T
        }
    }
}
