package com.oaktech.smsapp.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oaktech.smsapp.R
import com.oaktech.smsapp.sms.send.SendValidator
import com.oaktech.smsapp.ui.components.ReadOnlyBanner
import com.oaktech.smsapp.ui.thread.AttachmentChip
import com.oaktech.smsapp.ui.thread.ThreadTestTags

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
    onPickAttachment: () -> Unit,
    isSmsRoleHeld: Boolean,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
        onRecipientChanged = viewModel::onRecipientChanged,
        onDraftChanged = viewModel::onDraftChanged,
        onSend = viewModel::onSend,
        onPickAttachment = onPickAttachment,
        onRemoveAttachment = viewModel::removeAttachment,
        onRequestSmsRole = onRequestSmsRole,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    state: ComposeUiState,
    snackbarHostState: SnackbarHostState,
    isSmsRoleHeld: Boolean,
    onBack: () -> Unit,
    onRecipientChanged: (String) -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRequestSmsRole: () -> Unit,
) {
    val backLabel = stringResource(R.string.thread_back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backLabel,
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
                .padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            if (!isSmsRoleHeld) {
                ReadOnlyBanner(onAction = onRequestSmsRole)
            }

            val recipientLabel = stringResource(R.string.compose_recipient_label)
            OutlinedTextField(
                value = state.recipient,
                onValueChange = onRecipientChanged,
                label = { Text(stringResource(R.string.compose_recipient_hint)) },
                singleLine = true,
                enabled = !state.isSending,
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
                maxLines = 8,
                enabled = !state.isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ComposeTestTags.DRAFT_FIELD)
                    .semantics { contentDescription = draftLabel },
            )

            if (state.attachments.isNotEmpty()) {
                val removeLabel = stringResource(R.string.thread_remove_attachment)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.attachments.forEach { attachment ->
                        AttachmentChip(
                            fileName = attachment.fileName ?: "attachment",
                            onRemove = { onRemoveAttachment(attachment.filePath.orEmpty()) },
                            removeLabel = removeLabel,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val attachLabel = stringResource(R.string.thread_attach)
                IconButton(
                    onClick = onPickAttachment,
                    enabled = !state.isSending,
                    modifier = Modifier.semantics { contentDescription = attachLabel },
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                }
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
            stringResource(R.string.compose_error_body_too_long, SendValidator.MAX_BODY_LENGTH)
    }
}
