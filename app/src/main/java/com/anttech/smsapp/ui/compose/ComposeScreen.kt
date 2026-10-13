package com.anttech.smsapp.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anttech.smsapp.R
import com.anttech.smsapp.sms.send.SendValidator
import com.anttech.smsapp.ui.components.ReadOnlyBanner
import com.anttech.smsapp.ui.theme.iconCommand

class ComposeTestTags {
    companion object {
        const val RECIPIENT_FIELD = "compose_recipient_field"
        const val DRAFT_FIELD = "compose_draft_field"
        const val SEND_BUTTON = "compose_send_button"
    }
}

@Composable
fun ComposeRoute(
    viewModel: ComposeViewModel,
    onBack: () -> Unit,
    onRequestSmsRole: () -> Unit,
    isSmsRoleHeld: Boolean,
    onPickContact: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // The system Back gesture/key must use the same path as the app-bar arrow,
    // otherwise the NavHost pops the destination before its draft is saved.
    BackHandler(enabled = !state.isSending, onBack = onBack)

    val errorText = state.error?.let { composeErrorText(it) }
    LaunchedEffect(state.error) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.dismissError()
        }
    }

    // A SENDTO request comes from another app; once the message is away the
    // caller expects this activity to finish rather than linger.
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onBack()
    }

    ComposeScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        isSmsRoleHeld = isSmsRoleHeld,
        onBack = onBack,
        onRecipientInputChanged = viewModel::onRecipientInputChanged,
        onCommitRecipient = viewModel::commitRecipientInput,
        onRemoveRecipient = viewModel::removeRecipient,
        onDraftChanged = viewModel::onDraftChanged,
        onSend = viewModel::onSend,
        onRequestSmsRole = onRequestSmsRole,
        onPickContact = onPickContact,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    state: ComposeUiState,
    snackbarHostState: SnackbarHostState,
    isSmsRoleHeld: Boolean,
    onBack: () -> Unit,
    onRecipientInputChanged: (String) -> Unit,
    onCommitRecipient: () -> Unit,
    onRemoveRecipient: (String) -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRequestSmsRole: () -> Unit,
    onPickContact: () -> Unit,
) {
    val backLabel = stringResource(R.string.thread_back)
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backLabel,
                            tint = MaterialTheme.colorScheme.iconCommand,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!isSmsRoleHeld) {
                ReadOnlyBanner(onAction = onRequestSmsRole)
            }

            val recipientLabel = stringResource(R.string.compose_recipient_label)
            val removeRecipientLabel = stringResource(R.string.compose_remove_recipient)
            // A recipient resolved to a contact is drawn as a chip carrying the
            // name; the address stays in the state and is what gets sent. The
            // chip's only action is its remove button.
            val recipients = recipientAddresses(state.recipient)
            if (recipients.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (address in recipients) {
                        InputChip(
                            selected = false,
                            onClick = {},
                            enabled = !state.isSending,
                            label = { Text(state.recipientNames[address] ?: address) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = removeRecipientLabel,
                                    tint = MaterialTheme.colorScheme.iconCommand,
                                    modifier = Modifier
                                        .size(InputChipDefaults.IconSize)
                                        .clickable(enabled = !state.isSending) {
                                            onRemoveRecipient(address)
                                        },
                                )
                            },
                        )
                    }
                }
            }

            // The "To" field only ever holds the address being typed; committed
            // recipients have moved into chips above it.
            OutlinedTextField(
                value = state.recipientInput,
                onValueChange = onRecipientInputChanged,
                label = { Text(stringResource(R.string.compose_recipient_hint)) },
                trailingIcon = {
                    IconButton(
                        onClick = onPickContact,
                        enabled = !state.isSending,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.compose_pick_contact),
                            tint = MaterialTheme.colorScheme.iconCommand,
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                enabled = !state.isSending,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onCommitRecipient()
                        focusManager.clearFocus()
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ComposeTestTags.RECIPIENT_FIELD)
                    .semantics { contentDescription = recipientLabel },
            )

            val draftLabel = stringResource(R.string.thread_composer_description)
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChanged,
                label = { Text(stringResource(R.string.thread_draft_hint)) },
                shape = RoundedCornerShape(12.dp),
                maxLines = 8,
                enabled = !state.isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ComposeTestTags.DRAFT_FIELD)
                    .semantics { contentDescription = draftLabel },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                val sendLabel = stringResource(R.string.thread_send)
                Button(
                    onClick = onSend,
                    enabled = !state.isSending && isSmsRoleHeld,
                    modifier = Modifier
                        .testTag(ComposeTestTags.SEND_BUTTON)
                        .semantics { contentDescription = sendLabel },
                ) {
                    if (state.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Text(
                            text = stringResource(R.string.compose_send),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The (translatable) wording for a compose error, chosen here rather than in
 * the ViewModel so the state stays free of English text.
 */
@Composable
private fun composeErrorText(error: ComposeError): String = when (error) {
    ComposeError.SendFailed -> stringResource(R.string.thread_send_failed)
    is ComposeError.Invalid -> when (error.reason) {
        SendValidator.Reason.EMPTY_RECIPIENT -> stringResource(R.string.compose_error_no_recipient)
        SendValidator.Reason.UNPARSEABLE_RECIPIENT ->
            stringResource(R.string.compose_error_bad_recipient)
        SendValidator.Reason.EMPTY_BODY -> stringResource(R.string.compose_error_empty_body)
        SendValidator.Reason.BODY_TOO_LONG ->
            pluralStringResource(
                R.plurals.compose_error_body_too_long,
                SendValidator.MAX_BODY_LENGTH,
                SendValidator.MAX_BODY_LENGTH,
            )
    }
}
