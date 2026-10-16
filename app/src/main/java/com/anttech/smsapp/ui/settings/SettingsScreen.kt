package com.anttech.smsapp.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anttech.smsapp.R
import com.anttech.smsapp.notify.NotificationActionReceiver
import com.anttech.smsapp.sync.GoogleDriveAuth
import com.anttech.smsapp.sync.SyncScheduler
import com.anttech.smsapp.sync.SyncStatus
import com.anttech.smsapp.ui.TimeFormat
import com.anttech.smsapp.ui.rememberSmsRoleState
import com.anttech.smsapp.ui.theme.iconNeutral
import kotlinx.coroutines.launch

/**
 * The settings screen.
 *
 * [SettingsViewModel] owns every stored preference. Whether the SMS role is held
 * stays here because it is read live on every resume via [rememberSmsRoleState]:
 * the role can be granted or revoked outside the app, and what this screen shows
 * has to follow. The "set as default" action launches the system role prompt,
 * which is the same request the role banners on other screens make.
 *
 * The Google Drive grant is started here too, for the same reason: it is a
 * platform flow that needs an Activity result registry, which only a composable
 * hosted by an Activity has. The ViewModel is told the outcome, not asked to
 * obtain it.
 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val roleState = rememberSmsRoleState()
    val roleRequestScope = rememberCoroutineScope()
    val driveAuthScope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The role request dialog resolves the requester from the calling
    // package, which only exists when the intent is started for result; a
    // fire-and-forget startActivity() makes the dialog dismiss itself
    // before drawing anything.
    val setDefaultSmsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { roleRequestScope.launch { roleState.refreshAfterRequest() } }

    val setDefaultSms: () -> Unit = {
        roleState.requestIntent()?.let { intent ->
            setDefaultSmsLauncher.launch(intent)
        }
    }

    // Resolving the pending intent is how the flow completes when Google had to
    // show the account picker or the consent sheet: the token arrives in the
    // returned intent, not in the first call.
    val driveAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val data: Intent? = result.data
        val granted = result.resultCode == Activity.RESULT_OK && data != null &&
            GoogleDriveAuth.resultFromIntent(context, data) is GoogleDriveAuth.SetUpResult.Authorized
        if (granted) {
            viewModel.onDriveConnected()
            SyncScheduler.reconcile(context)
        } else {
            viewModel.onDriveConnectionFailed(null)
        }
    }

    val connectDrive: () -> Unit = {
        driveAuthScope.launch {
            when (val outcome = GoogleDriveAuth.begin(context)) {
                is GoogleDriveAuth.SetUpResult.Resolution ->
                    driveAuthLauncher.launch(
                        IntentSenderRequest.Builder(outcome.intentSender).build(),
                    )

                GoogleDriveAuth.SetUpResult.Authorized -> {
                    viewModel.onDriveConnected()
                    SyncScheduler.reconcile(context)
                }

                is GoogleDriveAuth.SetUpResult.Failed ->
                    viewModel.onDriveConnectionFailed(outcome.message)
            }
        }
    }

    // Entering the screen is the repair point for the background schedule: the
    // result the worker recorded since the screen was last open is pulled in,
    // and the periodic request is re-asserted if it had been lost.
    LaunchedEffect(Unit) {
        viewModel.refreshSyncState()
        SyncScheduler.reconcile(context)
    }

    SettingsScreen(
        isDefaultSmsApp = roleState.isHeld,
        state = state,
        onBack = onBack,
        onSetDefaultSms = setDefaultSms,
        onChooseSubscription = viewModel::chooseSubscription,
        onNotificationActionChanged = viewModel::setNotificationAction,
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
        onSyncEnabledChanged = { enabled ->
            viewModel.setSyncEnabled(enabled)
            SyncScheduler.reconcile(context)
            // Switching it on is a request to use it, and nothing can move
            // until Google has granted the scope, so ask now rather than let
            // the first sync fail into a "reconnect" prompt.
            if (enabled) connectDrive()
        },
        onConnectDrive = connectDrive,
        onSyncNow = viewModel::syncNow,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    isDefaultSmsApp: Boolean,
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetDefaultSms: () -> Unit,
    onChooseSubscription: (Int?) -> Unit,
    onNotificationActionChanged: (String, Boolean) -> Unit,
    onBlock: (String) -> Unit,
    onUnblock: (String) -> Unit,
    onSyncEnabledChanged: (Boolean) -> Unit,
    onConnectDrive: () -> Unit,
    onSyncNow: () -> Unit,
) {
    var blockInput by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.thread_back),
                            tint = MaterialTheme.colorScheme.iconNeutral,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "default_sms") {
                SettingsSection(title = stringResource(R.string.settings_default_sms)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (isDefaultSmsApp) {
                                stringResource(R.string.settings_default_sms_held)
                            } else {
                                stringResource(R.string.settings_default_sms_lost)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!isDefaultSmsApp) {
                            Button(onClick = onSetDefaultSms) {
                                Text(stringResource(R.string.settings_set_default))
                            }
                        }
                    }
                }
            }

            // Only the active subscriptions can be picked from, so the section is
            // hidden entirely when none are readable (no READ_PHONE_STATE, or a
            // failure to query the telephony stack).
            if (state.subscriptions.isNotEmpty()) {
                item(key = "sim") {
                    SettingsSection(title = stringResource(R.string.settings_sim)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.settings_sim_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SubscriptionRow(
                                label = stringResource(R.string.settings_sim_follow_default),
                                secondary = null,
                                selected = state.preferredSubscriptionId == null,
                                onClick = { onChooseSubscription(null) },
                            )
                            state.subscriptions.forEach { subscription ->
                                SubscriptionRow(
                                    label = subscription.label,
                                    secondary = subscription.carrierName,
                                    selected = state.preferredSubscriptionId ==
                                        subscription.subscriptionId,
                                    onClick = { onChooseSubscription(subscription.subscriptionId) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "notification_actions") {
                SettingsSection(title = stringResource(R.string.settings_notification_actions)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.settings_notification_actions_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Driven off the receiver's action list so a newly added
                        // action cannot be handled there and left off the screen.
                        NOTIFICATION_ACTION_LABELS.forEach { (action, label) ->
                            NotificationActionSetting(
                                label = stringResource(label),
                                checked = state.notificationActions[action] ?: true,
                                onCheckedChange = { onNotificationActionChanged(action, it) },
                            )
                        }
                    }
                }
            }

            // The live blocked list is read from the store; the input is local
            // to this screen. Clearing on submit is fine whether or not the add
            // succeeded: a duplicate or empty number simply adds nothing.
            item(key = "blocked") {
                SettingsSection(title = stringResource(R.string.settings_blocked)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.settings_blocked_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = blockInput,
                                onValueChange = { blockInput = it },
                                placeholder = {
                                    Text(stringResource(R.string.settings_block_hint))
                                },
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    onBlock(blockInput.trim())
                                    blockInput = ""
                                },
                                enabled = blockInput.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.settings_block_add))
                            }
                        }
                        if (state.blockedSenders.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_block_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.blockedSenders.forEach { sender ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sender,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { onUnblock(sender) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(
                                            R.string.settings_block_remove,
                                        ),
                                        tint = MaterialTheme.colorScheme.iconNeutral,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "drive_sync") {
                SettingsSection(title = stringResource(R.string.settings_sync)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_sync_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NotificationActionSetting(
                            label = stringResource(R.string.settings_sync_enabled),
                            checked = state.syncEnabled,
                            onCheckedChange = onSyncEnabledChanged,
                        )
                        // Everything below only means anything once sync is on,
                        // so it stays folded away until then rather than showing
                        // a "never synced" line for a feature that is off.
                        if (state.syncEnabled) {
                            Text(
                                text = syncStatusText(state),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.syncLastSyncedAt?.let { syncedAt ->
                                Text(
                                    text = stringResource(
                                        R.string.settings_sync_last,
                                        TimeFormat.messageTimestamp(syncedAt),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // FlowRow, not Row: the Turkish labels run long
                            // enough that a Row pushed the second button past
                            // the right edge, where it was clipped and could not
                            // be tapped at all. Wrapping keeps both reachable on
                            // a narrow phone as well as this tablet.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = onConnectDrive) {
                                    Text(
                                        stringResource(
                                            // "Connect" only reads correctly before the
                                            // first attempt; after one, the grant is
                                            // either present or was refused, and the
                                            // button re-runs the same flow either way.
                                            if (state.syncStatus == SyncStatus.NEVER) {
                                                R.string.settings_sync_connect
                                            } else {
                                                R.string.settings_sync_reconnect
                                            },
                                        ),
                                    )
                                }
                                Button(onClick = onSyncNow, enabled = !state.syncing) {
                                    Text(
                                        stringResource(
                                            if (state.syncing) {
                                                R.string.settings_sync_running
                                            } else {
                                                R.string.settings_sync_now
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "about") {
                SettingsSection(title = stringResource(R.string.settings_about)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_version),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = state.versionName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Notification action to its label, in display order. The action strings come
 * from [NotificationActionReceiver] so the screen cannot offer an action the
 * receiver does not understand; the order here is the one the screen shows.
 */
private val NOTIFICATION_ACTION_LABELS = listOf(
    NotificationActionReceiver.ACTION_REPLY to R.string.notification_action_reply,
    NotificationActionReceiver.ACTION_MARK_READ to R.string.notification_action_read,
    NotificationActionReceiver.ACTION_MARK_SPAM to R.string.notification_action_spam,
    NotificationActionReceiver.ACTION_DELETE to R.string.notification_action_delete,
)

/**
 * The one line under the switch that says whether the last attempt worked.
 *
 * Kept as a `when` over the stored status rather than a stored message: the
 * persisted state must not carry user-facing text, or a translation change
 * would leave old English behind in a preference.
 */
@Composable
private fun syncStatusText(state: SettingsUiState): String = when (state.syncStatus) {
    SyncStatus.NEVER -> stringResource(R.string.settings_sync_never)

    SyncStatus.SUCCESS -> if (state.syncLastMergedCount > 0) {
        // A plural, because "merged 1 messages" is the kind of wrong the whole
        // translated file exists to avoid.
        pluralStringResource(
            R.plurals.settings_sync_success,
            state.syncLastMergedCount,
            state.syncLastMergedCount,
        )
    } else {
        stringResource(R.string.settings_sync_success_none)
    }

    SyncStatus.NEEDS_SIGN_IN -> stringResource(R.string.settings_sync_needs_sign_in)

    SyncStatus.FAILED -> state.syncLastError
        ?.let { stringResource(R.string.settings_sync_failed_detail, it) }
        ?: stringResource(R.string.settings_sync_failed)
}

@Composable
private fun NotificationActionSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** One selectable SIM option: a radio button to the left, its names to the right. */
@Composable
private fun SubscriptionRow(
    label: CharSequence,
    secondary: CharSequence?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the radio button: the reachable node announces
            // the label and its checked state instead of an unlabelled circle.
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label.toString(),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!secondary.isNullOrEmpty()) {
                Text(
                    text = secondary.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A titled group of settings, wrapped in the tonal surface the conversation
 * rows use so the list reads as cards rather than floating text.
 */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(modifier = Modifier.padding(16.dp)) { content() }
        }
    }
}
