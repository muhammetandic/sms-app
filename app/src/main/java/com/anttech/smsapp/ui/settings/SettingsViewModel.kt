package com.anttech.smsapp.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.notify.NotificationActionPreferences
import com.anttech.smsapp.notify.NotificationActionReceiver
import com.anttech.smsapp.sms.block.BlockedSenders
import com.anttech.smsapp.sms.send.SmsSubscription
import com.anttech.smsapp.sms.send.SmsSubscriptionSettings
import com.anttech.smsapp.sms.send.activeSmsSubscriptions
import com.anttech.smsapp.sync.SyncOutcome
import com.anttech.smsapp.sync.SyncSettings
import com.anttech.smsapp.sync.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val syncEnabled: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.NEVER,
    val syncLastSyncedAt: Long? = null,
    val syncLastMergedCount: Int = 0,
    val syncLastError: String? = null,
    /** True while the screen's own "sync now" is running, so the button can read busy. */
    val syncing: Boolean = false,
)

/**
 * The settings screen's reads and writes.
 *
 * The version and the SIM list are read once: neither can change while the
 * process is alive, and re-querying the telephony stack on every recomposition
 * would be one provider call per frame. The preferred SIM, the notification
 * actions and the blocked list are kept in state so a change shows immediately
 * rather than after a reload.
 *
 * The Drive sync state is the one piece that can change while this screen is
 * not looking, because the periodic worker records its result through a
 * different [SyncSettings] instance. [refreshSyncState] pulls that in; the
 * screen's own button updates it directly, so neither direction waits for the
 * other.
 */
class SettingsViewModel(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * How "sync now" reaches Drive. Resolving the store is deferred to the call
     * because the default is only ever used by the real screen: a test passes
     * its own and so never constructs a graph, a Room database or a Play
     * services client.
     */
    private val runSync: suspend () -> SyncOutcome = {
        DataGraph.get(context.applicationContext).syncRepository.sync()
    },
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

    private val syncSettings = SyncSettings(appContext)
    private val syncState = MutableStateFlow(syncSettings.state.value)
    private val syncing = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        blockedSenders.blocks,
        preferredSubscriptionId,
        notificationActionStates,
        syncState,
        syncing,
    ) { blocked, preferred, actions, sync, running ->
        SettingsUiState(
            versionName = versionName,
            subscriptions = subscriptions,
            preferredSubscriptionId = preferred,
            notificationActions = actions,
            blockedSenders = blocked,
            syncEnabled = sync.enabled,
            syncStatus = sync.status,
            syncLastSyncedAt = sync.lastSyncedAt,
            syncLastMergedCount = sync.lastMergedCount,
            syncLastError = sync.lastError,
            syncing = running,
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
            syncEnabled = syncState.value.enabled,
            syncStatus = syncState.value.status,
            syncLastSyncedAt = syncState.value.lastSyncedAt,
            syncLastMergedCount = syncState.value.lastMergedCount,
            syncLastError = syncState.value.lastError,
            syncing = false,
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

    /**
     * Persists the Drive sync switch. Scheduling is the caller's job: this
     * class deliberately knows nothing about WorkManager, which is also what
     * lets the preference round trip be tested without a work database.
     */
    fun setSyncEnabled(enabled: Boolean) {
        syncSettings.setEnabled(enabled)
        if (!enabled) syncing.value = false
        syncState.value = syncSettings.state.value
    }

    /**
     * Called after the Google authorization screen came back having granted
     * access. Enables sync and runs the first one straight away, because the
     * user just told us to and an empty folder with no file in it is the one
     * state a sync always has to write.
     */
    fun onDriveConnected() {
        syncSettings.setEnabled(true)
        syncState.value = syncSettings.state.value
        syncNow()
    }

    /** Records that the authorization flow ended without a grant. */
    fun onDriveConnectionFailed(message: String?) {
        syncSettings.recordStatus(SyncStatus.NEEDS_SIGN_IN, message)
        syncState.value = syncSettings.state.value
    }

    /**
     * Syncs for the button, and reflects the result in place.
     *
     * Direct rather than through WorkManager on purpose: the user is standing
     * at the button, so the answer belongs on the screen that was open when
     * they pressed it instead of a job they cannot see. The background path
     * still goes through the worker, and the repository serialises the two.
     */
    fun syncNow() {
        if (syncing.value) return
        syncing.value = true
        viewModelScope.launch {
            try {
                runSync()
            } finally {
                // read back even on cancellation: the attempt may already have
                // recorded itself, and a cancelled one must not leave the
                // button stuck on "syncing".
                syncSettings.refresh()
                syncState.value = syncSettings.state.value
                syncing.value = false
            }
        }
    }

    /**
     * Pulls the result the periodic worker recorded. The screen keeps its own
     * [SyncSettings] instance, so anything written elsewhere since it opened is
     * invisible without this - the same reason the SMS role is re-read rather
     * than remembered.
     */
    fun refreshSyncState() {
        syncSettings.refresh()
        syncState.value = syncSettings.state.value
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
