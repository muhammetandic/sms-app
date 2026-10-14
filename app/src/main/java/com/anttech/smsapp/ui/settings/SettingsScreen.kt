package com.anttech.smsapp.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anttech.smsapp.R
import com.anttech.smsapp.notify.NotificationActionReceiver
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
 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val roleState = rememberSmsRoleState()
    val roleRequestScope = rememberCoroutineScope()
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

    SettingsScreen(
        isDefaultSmsApp = roleState.isHeld,
        state = state,
        onBack = onBack,
        onSetDefaultSms = setDefaultSms,
        onChooseSubscription = viewModel::chooseSubscription,
        onNotificationActionChanged = viewModel::setNotificationAction,
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
