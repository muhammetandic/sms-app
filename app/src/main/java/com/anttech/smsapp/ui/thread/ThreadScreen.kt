package com.anttech.smsapp.ui.thread

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anttech.smsapp.R
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.sms.send.ResultCode
import com.anttech.smsapp.sms.send.SmsSegments
import com.anttech.smsapp.ui.TimeFormat
import com.anttech.smsapp.ui.components.LinkifiedText
import com.anttech.smsapp.ui.components.ReadOnlyBanner
import com.anttech.smsapp.ui.theme.iconCommand
import kotlinx.coroutines.launch

class ThreadTestTags {
    companion object {
        const val DRAFT_FIELD = "thread_draft_field"
        const val SEND_BUTTON = "thread_send_button"
        const val COMPOSER = "thread_composer"
        const val MESSAGE_LIST = "thread_message_list"
        const val SEGMENT_COUNTER = "thread_segment_counter"
    }
}

@Composable
fun ThreadRoute(
    viewModel: ThreadViewModel,
    recipient: String,
    contactName: String? = null,
    roleHeldNow: Boolean,
    onBack: () -> Unit,
    onRequestSmsRole: () -> Unit,
    onForward: (String) -> Unit,
    onMessageNumber: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val sendFailedMessage = stringResource(R.string.thread_send_failed)

    LaunchedEffect(roleHeldNow) { viewModel.refreshRole() }

    // The role can be revoked from system settings while this screen sits in
    // the back stack, and both the read-only banner and the composer's enabled
    // state key off it. Re-read on resume: the value captured when the ViewModel
    // was created would otherwise outlive the revocation for as long as this
    // thread stays open.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshRole()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The composer's text lives here so it can be seeded from the stored draft
    // and pushed back into the store on every change. The seeding runs once; the
    // echo of a keystroke (the store answering our own save) must not reset the
    // field while the user is typing.
    val persistedDraft by viewModel.draft.collectAsStateWithLifecycle()
    var draftText by rememberSaveable { mutableStateOf("") }
    var draftSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(persistedDraft) {
        if (!draftSeeded) {
            draftText = persistedDraft.orEmpty()
            draftSeeded = true
        }
    }

    LaunchedEffect(state.lastFailure) {
        state.lastFailure?.let {
            snackbarHostState.showSnackbar(sendFailedMessage)
            viewModel.dismissFailure()
        }
    }

    // Deleting the last message empties the thread, and the repository removes
    // the empty conversation with it. Pop back once a thread that held messages
    // becomes empty, instead of stranding the user on an empty screen. The flag
    // guards the initial empty frame (before Room has emitted), so a thread that
    // is already empty when it opens stays put.
    var sawMessage by remember { mutableStateOf(false) }
    LaunchedEffect(state.messages.isNotEmpty()) {
        if (state.messages.isNotEmpty()) sawMessage = true
    }
    LaunchedEffect(state.messages.isEmpty()) {
        if (state.messages.isEmpty() && sawMessage) onBack()
    }

    ThreadScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        recipient = recipient,
        contactName = contactName,
        onBack = onBack,
        draft = draftText,
        onDraftChange = { text ->
            draftText = text
            viewModel.onDraftChanged(text)
        },
        onSubmit = { body ->
            viewModel.onDraftSubmitted(body)
            draftText = ""
        },
        onRequestSmsRole = onRequestSmsRole,
        onDeleteMessage = viewModel::deleteMessage,
        onResendMessage = viewModel::resendMessage,
        onForward = onForward,
        onToggleMuted = viewModel::toggleMuted,
        onMessageNumber = onMessageNumber,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    snackbarHostState: SnackbarHostState,
    recipient: String,
    contactName: String? = null,
    onBack: () -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onRequestSmsRole: () -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onResendMessage: (Long) -> Unit,
    onForward: (String) -> Unit,
    onToggleMuted: () -> Unit,
    onMessageNumber: (String) -> Unit,
) {
    // The bubble the user long-pressed, held until the action sheet is answered.
    var selectedBubble by remember { mutableStateOf<MessageBubble?>(null) }

    // The bubble offered for deletion, held until the confirmation dialog is
    // answered. Deleting a message is permanent, so it is never implicit.
    var pendingDelete by remember { mutableStateOf<MessageBubble?>(null) }

    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.message_copied)
    // Each copy is a new trigger; a state change re-runs the one-shot snackbar,
    // and 0 means nothing to show yet. A plain Boolean would not re-fire for a
    // second consecutive copy.
    var copiedNotice by remember { mutableLongStateOf(0L) }
    LaunchedEffect(copiedNotice) {
        if (copiedNotice > 0) snackbarHostState.showSnackbar(copiedLabel)
    }

    // Naming the correspondent beats a generic "Messages" heading: it is the
    // one thing that tells you which thread you are in. Resolved outside the
    // title lambda because the fallback resource has to be read either way.
    val genericTitle = stringResource(R.string.conversations_title)
    val title = contactName ?: recipient.ifBlank { genericTitle }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    val backLabel = stringResource(R.string.thread_back)
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backLabel,
                            tint = MaterialTheme.colorScheme.iconCommand,
                        )
                    }
                },
                actions = {
                    // The glyph reflects the current state (bell = alerts on,
                    // struck-through bell = silenced) while the label names the
                    // action a tap performs, so the toggle reads correctly
                    // before and after it is pressed.
                    val muted = state.isMuted
                    IconButton(onClick = onToggleMuted) {
                        Icon(
                            painter = painterResource(
                                if (muted) {
                                    R.drawable.ic_action_mute
                                } else {
                                    R.drawable.ic_action_notifications
                                },
                            ),
                            contentDescription = stringResource(
                                if (muted) R.string.thread_unmute else R.string.thread_mute,
                            ),
                            tint = MaterialTheme.colorScheme.iconCommand,
                        )
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.canSend) {
                Composer(
                    draft = draft,
                    onDraftChange = onDraftChange,
                    isSending = state.isSending,
                    onSubmit = onSubmit,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            if (state.isReadOnly) {
                ReadOnlyBanner(onAction = onRequestSmsRole)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (state.messages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.thread_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    MessageList(
                        messages = state.messages,
                        onLongPress = { selectedBubble = it },
                        onMessageNumber = onMessageNumber,
                    )
                }
            }
        }
    }

    selectedBubble?.let { bubble ->
        MessageBubbleSheet(
            bubble = bubble,
            onDismiss = { selectedBubble = null },
            onCopy = {
                selectedBubble = null
                // Clipboard writes are suspend now; the sheet dismisses at once
                // and the copy lands on the composition's scope.
                clipboardScope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, bubble.body)))
                }
                copiedNotice += 1
            },
            onShare = {
                selectedBubble = null
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, bubble.body)
                }
                runCatching { context.startActivity(Intent.createChooser(share, null)) }
            },
            onForward = {
                selectedBubble = null
                onForward(bubble.body)
            },
            onResend = {
                selectedBubble = null
                onResendMessage(bubble.id)
            },
            onDelete = {
                selectedBubble = null
                pendingDelete = bubble
            },
        )
    }

    pendingDelete?.let { bubble ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_message_title)) },
            text = { Text(stringResource(R.string.delete_message_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteMessage(bubble.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.dialog_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun MessageList(
    messages: List<MessageBubble>,
    onLongPress: (MessageBubble) -> Unit,
    onMessageNumber: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    var hasInitialPosition by remember { mutableStateOf(false) }

    // New messages arrive at the end. Scrolling to the latest one keeps the
    // newest content visible without yanking the user if they scrolled up to
    // read history. The first load jumps directly to the end: animating across
    // a long history makes opening a conversation look stalled and composes
    // every intermediate list position before the latest message is visible.
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!hasInitialPosition) {
            listState.scrollToItem(messages.lastIndex)
            hasInitialPosition = true
        } else {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(ThreadTestTags.MESSAGE_LIST),
        // contentPadding rather than padding on the modifier: padding applied to
        // a LazyColumn is applied to the container, so the top and bottom insets
        // would be scrolled away and the first and last bubbles would sit flush
        // against the bar and the composer.
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { bubble ->
            MessageBubbleRow(
                bubble = bubble,
                onLongPress = { onLongPress(bubble) },
                onMessageNumber = onMessageNumber,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleRow(
    bubble: MessageBubble,
    onLongPress: () -> Unit,
    onMessageNumber: (String) -> Unit,
) {
    val description = if (bubble.isOutgoing) {
        stringResource(R.string.thread_outgoing_description, bubble.timestampLabel, bubble.body)
    } else {
        stringResource(R.string.thread_incoming_description, bubble.timestampLabel, bubble.body)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (bubble.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        // The leading corner is squared off on the sender's side, so
        // consecutive bubbles read as one group rather than a column of
        // identical pills. 28dp on the long edges is the Material 3 bubble
        // radius; start/top/bottom-left is zeroed for outgoing messages,
        // top-right/bottom-right for incoming.
        val shape = if (bubble.isOutgoing) {
            RoundedCornerShape(topStart = 0.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 28.dp)
        } else {
            RoundedCornerShape(topStart = 28.dp, topEnd = 0.dp, bottomEnd = 28.dp, bottomStart = 28.dp)
        }

        Surface(
            color = if (bubble.isOutgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (bubble.isOutgoing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            shape = shape,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                // Long-press opens the message actions; the bubble itself has no
                // tap behaviour of its own, so the tap is a deliberate no-op.
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // URLs, emails, WhatsApp links, phone numbers and coordinates
                // are tappable inside the bubble. The body stays plain text in
                // the accessibility label, which already reads out the message.
                if (bubble.body.isNotBlank()) {
                    LinkifiedText(
                        text = bubble.body,
                        style = MaterialTheme.typography.bodyLarge,
                        onMessage = onMessageNumber,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = bubble.timestampLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusLabel(status = bubble.status)
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(status: MessageStatus) {
    val label = when (status) {
        MessageStatus.SENDING -> stringResource(R.string.thread_status_sending)
        MessageStatus.SENT -> stringResource(R.string.thread_status_sent)
        MessageStatus.DELIVERED -> stringResource(R.string.thread_status_delivered)
        MessageStatus.FAILED -> stringResource(R.string.thread_status_failed)
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (status == MessageStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * Bottom sheet for a long-pressed message: what is known about the message, and
 * what can be done with it. Details live here rather than behind a second
 * dialog so one long-press answers both "what happened to this?" and "what now?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageBubbleSheet(
    bubble: MessageBubble,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onForward: () -> Unit,
    onResend: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp)) {
            Text(
                text = stringResource(R.string.message_details_title),
                style = MaterialTheme.typography.titleMedium,
            )

            val sentLabel = stringResource(R.string.message_details_sent_at)
            val deliveredLabel = stringResource(R.string.message_details_delivered_at)
            val partsLabel = pluralStringResource(R.plurals.message_details_parts, bubble.partCount, bubble.partCount)
            val partsTitle = stringResource(R.string.message_details_parts_title)

            DetailRow(sentLabel, bubble.timestampLabel)
            bubble.deliveredAt?.let {
                DetailRow(deliveredLabel, TimeFormat.messageTimestamp(it))
            }
            if (bubble.partCount > 1) {
                DetailRow(partsTitle, partsLabel)
            }
            bubble.simSlot?.let {
                DetailRow(stringResource(R.string.message_details_sim), it.toString())
            }
            bubble.errorCode?.let {
                ResultCode.fromPlatformCode(it)
                    .takeIf { status -> status != ResultCode.UNKNOWN }
                    ?.let { status -> DetailRow(stringResource(R.string.message_details_error), status.name) }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        SheetAction(
            icon = { painterResource(R.drawable.ic_action_copy) },
            label = stringResource(R.string.message_action_copy),
            onClick = onCopy,
        )
        SheetAction(
            icon = { Icon(Icons.Filled.Share, contentDescription = null) },
            label = stringResource(R.string.message_action_share),
            onClick = onShare,
        )
        SheetAction(
            icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
            label = stringResource(R.string.message_action_forward),
            onClick = onForward,
        )
        if (bubble.isOutgoing && bubble.status == MessageStatus.FAILED) {
            SheetAction(
                icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                label = stringResource(R.string.message_action_resend),
                onClick = onResend,
            )
        }
        SheetAction(
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            label = stringResource(R.string.dialog_delete),
            labelColor = MaterialTheme.colorScheme.error,
            onClick = onDelete,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SheetAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    isSending: Boolean,
    onSubmit: (String) -> Unit,
) {
    val draftLabel = stringResource(R.string.thread_composer_description)
    val sendLabel = stringResource(R.string.thread_send)
    val hint = stringResource(R.string.thread_draft_hint)
    val canSend = draft.isNotBlank() && !isSending

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ThreadTestTags.COMPOSER),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ThreadTestTags.DRAFT_FIELD)
                            .semantics { contentDescription = draftLabel },
                        placeholder = { Text(hint) },
                        shape = RoundedCornerShape(12.dp),
                        maxLines = 5,
                        enabled = !isSending,
                    )

                    if (draft.isNotEmpty()) {
                        // Warns before a send that will cost several messages.
                        // A blank field has no counter to show.
                        val estimate = SmsSegments.estimate(draft)
                        val counter = if (estimate.segments > 1) {
                            pluralStringResource(
                                R.plurals.thread_segment_parts,
                                estimate.segments,
                                estimate.usedInCurrentSegment,
                                estimate.segmentLimit,
                                estimate.segments,
                            )
                        } else {
                            stringResource(
                                R.string.thread_segment_counter,
                                estimate.usedInCurrentSegment,
                                estimate.segmentLimit,
                            )
                        }
                        Text(
                            text = counter,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 2.dp, end = 4.dp)
                                .testTag(ThreadTestTags.SEGMENT_COUNTER),
                        )
                    }
                }

                FilledIconButton(
                    onClick = {
                        val body = draft
                        onDraftChange("")
                        onSubmit(body)
                    },
                    enabled = canSend,
                    modifier = Modifier
                        .testTag(ThreadTestTags.SEND_BUTTON)
                        .semantics { contentDescription = sendLabel },
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    }
                }
            }
        }
    }
}
