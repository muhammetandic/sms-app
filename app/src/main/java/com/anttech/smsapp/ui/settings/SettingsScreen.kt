package com.anttech.smsapp.ui.settings

import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anttech.smsapp.R
import com.anttech.smsapp.sms.block.BlockedSenders
import com.anttech.smsapp.sms.send.SmsSubscription
import com.anttech.smsapp.sms.send.SmsSubscriptionSettings
import com.anttech.smsapp.sms.send.activeSmsSubscriptions
import com.anttech.smsapp.notify.NotificationActionPreferences
import com.anttech.smsapp.notify.NotificationActionReceiver
import com.anttech.smsapp.ui.rememberSmsRoleState
import com.anttech.smsapp.ui.theme.iconCommand
import kotlinx.coroutines.launch

/**
 * The settings screen.
 *
 * The role status is read live on every resume via [rememberSmsRoleState]: the
 * role can be granted or revoked outside the app, and what this screen shows has
 * to follow. The "set as default" action launches the system role prompt, which
 * is the same request the role banners on other screens make.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val roleState = rememberSmsRoleState()
    val roleRequestScope = rememberCoroutineScope()

    val versionName = remember {
        // PackageManager only hands out PackageInfoFlags from API 33; the
        // flagless overload is deprecated after that, so one path per platform.
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        packageInfo.versionName.orEmpty()
    }

    // The live subscriptions and the stored choice are captured once when the
    // screen is entered; the radio rows below keep their own state as the user
    // picks, and the transport re-reads the preference at send time anyway.
    val subscriptionSettings = remember { SmsSubscriptionSettings(context) }
    val subscriptions = remember { activeSmsSubscriptions(context) }
    var preferredSubscriptionId by remember {
        mutableStateOf(subscriptionSettings.preferredSubscriptionId())
    }

    val notificationActions = remember { NotificationActionPreferences(context) }
    var notificationReplyEnabled by remember {
        mutableStateOf(notificationActions.isEnabled(NotificationActionReceiver.ACTION_REPLY))
    }
    var notificationReadEnabled by remember {
        mutableStateOf(notificationActions.isEnabled(NotificationActionReceiver.ACTION_MARK_READ))
    }
    var notificationSpamEnabled by remember {
        mutableStateOf(notificationActions.isEnabled(NotificationActionReceiver.ACTION_MARK_SPAM))
    }
    var notificationDeleteEnabled by remember {
        mutableStateOf(notificationActions.isEnabled(NotificationActionReceiver.ACTION_DELETE))
    }

    val blockedSenders = remember { BlockedSenders(context) }
    val blockedList by blockedSenders.blocks.collectAsStateWithLifecycle()

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
        versionName = versionName,
        subscriptions = subscriptions,
        preferredSubscriptionId = preferredSubscriptionId,
        notificationReplyEnabled = notificationReplyEnabled,
        notificationReadEnabled = notificationReadEnabled,
        notificationSpamEnabled = notificationSpamEnabled,
        notificationDeleteEnabled = notificationDeleteEnabled,
        blockedSenders = blockedList,
        onBack = onBack,
        onSetDefaultSms = setDefaultSms,
        onChooseSubscription = { id ->
            subscriptionSettings.setPreferredSubscriptionId(id)
            preferredSubscriptionId = id
        },
        onNotificationReplyChanged = {
            notificationActions.setEnabled(NotificationActionReceiver.ACTION_REPLY, it)
            notificationReplyEnabled = it
        },
        onNotificationReadChanged = {
            notificationActions.setEnabled(NotificationActionReceiver.ACTION_MARK_READ, it)
            notificationReadEnabled = it
        },
        onNotificationSpamChanged = {
            notificationActions.setEnabled(NotificationActionReceiver.ACTION_MARK_SPAM, it)
            notificationSpamEnabled = it
        },
        onNotificationDeleteChanged = {
            notificationActions.setEnabled(NotificationActionReceiver.ACTION_DELETE, it)
            notificationDeleteEnabled = it
        },
        onBlock = { blockedSenders.add(it) },
        onUnblock = { blockedSenders.remove(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDefaultSmsApp: Boolean,
    versionName: String,
    subscriptions: List<SmsSubscription>,
    preferredSubscriptionId: Int?,
    notificationReplyEnabled: Boolean,
    notificationReadEnabled: Boolean,
    notificationSpamEnabled: Boolean,
    notificationDeleteEnabled: Boolean,
    blockedSenders: Set<String>,
    onBack: () -> Unit,
    onSetDefaultSms: () -> Unit,
    onChooseSubscription: (Int?) -> Unit,
    onNotificationReplyChanged: (Boolean) -> Unit,
    onNotificationReadChanged: (Boolean) -> Unit,
    onNotificationSpamChanged: (Boolean) -> Unit,
    onNotificationDeleteChanged: (Boolean) -> Unit,
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
                            tint = MaterialTheme.colorScheme.iconCommand,
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
            if (subscriptions.isNotEmpty()) {
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
                                selected = preferredSubscriptionId == null,
                                onClick = { onChooseSubscription(null) },
                            )
                            subscriptions.forEach { subscription ->
                                SubscriptionRow(
                                    label = subscription.label,
                                    secondary = subscription.carrierName,
                                    selected = preferredSubscriptionId == subscription.subscriptionId,
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
                        NotificationActionSetting(
                            label = stringResource(R.string.notification_action_reply),
                            checked = notificationReplyEnabled,
                            onCheckedChange = onNotificationReplyChanged,
                        )
                        NotificationActionSetting(
                            label = stringResource(R.string.notification_action_read),
                            checked = notificationReadEnabled,
                            onCheckedChange = onNotificationReadChanged,
                        )
                        NotificationActionSetting(
                            label = stringResource(R.string.notification_action_spam),
                            checked = notificationSpamEnabled,
                            onCheckedChange = onNotificationSpamChanged,
                        )
                        NotificationActionSetting(
                            label = stringResource(R.string.notification_action_delete),
                            checked = notificationDeleteEnabled,
                            onCheckedChange = onNotificationDeleteChanged,
                        )
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
                        if (blockedSenders.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_block_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        blockedSenders.forEach { sender ->
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
                                        tint = MaterialTheme.colorScheme.iconCommand,
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
                            text = versionName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
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
