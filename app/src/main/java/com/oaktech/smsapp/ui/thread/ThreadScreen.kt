package com.oaktech.smsapp.ui.thread

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oaktech.smsapp.R
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.local.AddressNormalizer
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.sms.send.ResultCode
import com.oaktech.smsapp.sms.send.SmsSegments
import com.oaktech.smsapp.ui.TimeFormat
import com.oaktech.smsapp.ui.components.LinkifiedText
import com.oaktech.smsapp.ui.components.ReadOnlyBanner
import coil.compose.AsyncImage
import coil.request.ImageRequest

class ThreadTestTags {
    companion object {
        const val DRAFT_FIELD = "thread_draft_field"
        const val SEND_BUTTON = "thread_send_button"
        const val COMPOSER = "thread_composer"
        const val MESSAGE_LIST = "thread_message_list"
        const val DOWNLOAD_BUTTON = "thread_download_button"
        const val SEGMENT_COUNTER = "thread_segment_counter"
        const val SUBJECT_FIELD = "thread_subject_field"
        const val IMAGE_ATTACHMENT = "thread_image_attachment"
    }
}

/**
 * An image attachment is the message, so it is shown inline instead of as a
 * filename row. Anything else, and any image whose bytes are not on disk yet
 * (a RETRIEVABLE part has no path until the user downloads it), stays a row.
 */
@Composable
private fun AttachmentPreview(attachment: AttachmentUi) {
    val filePath = attachment.filePath
    val isImage = attachment.contentType?.startsWith("image/") == true
    if (filePath != null && isImage) {
        AttachmentImage(attachment = attachment, filePath = filePath)
    } else {
        AttachmentRow(attachment = attachment)
    }
}

/**
 * The tap target matches the row's: share the original file. A file that fails
 * to decode (corrupt, or swept from disk) falls back to the filename row rather
 * than leaving an empty gap in the bubble.
 */
@Composable
private fun AttachmentImage(attachment: AttachmentUi, filePath: String) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    var failed by remember(filePath) { mutableStateOf(false) }
    if (failed) {
        AttachmentRow(attachment = attachment)
        return
    }

    AsyncImage(
        model = ImageRequest.Builder(context).data(File(filePath)).crossfade(true).build(),
        contentDescription = attachment.fileName,
        contentScale = ContentScale.Fit,
        onError = { failed = true },
        modifier = Modifier
            .padding(bottom = 6.dp)
            .widthIn(max = 240.dp)
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .testTag(ThreadTestTags.IMAGE_ATTACHMENT)
            .clickable {
                launcher.launch(shareAttachmentIntent(context, filePath, attachment.contentType, attachment.fileName))
            },
    )
}

/** Sends an attachment through the system share sheet via the FileProvider. */
@Composable
private fun AttachmentRow(attachment: AttachmentUi) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clickable(enabled = attachment.filePath != null) {
                val path = attachment.filePath ?: return@clickable
                launcher.launch(shareAttachmentIntent(context, path, attachment.contentType, attachment.fileName))
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_action_download),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = attachment.fileName ?: "attachment",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.thread_share_attachment),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun shareAttachmentIntent(
    context: Context,
    filePath: String,
    contentType: String?,
    fileName: String?,
): Intent {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(filePath))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = contentType ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, fileName ?: "attachment")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, fileName ?: "attachment")
}

@Composable
fun ThreadRoute(
    viewModel: ThreadViewModel,
    recipient: String,
    onBack: () -> Unit,
    onRequestSmsRole: () -> Unit,
    onForward: (String) -> Unit,
    onPickAttachment: () -> Unit,
    onMessageNumber: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val sendFailedMessage = stringResource(R.string.thread_send_failed)
    val downloadFailedMessage = stringResource(R.string.thread_download_failed)

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
    // The subject only exists on the MMS leg, so it is not stored with the
    // draft; it lives with the other composer state and clears on send.
    var subjectText by rememberSaveable { mutableStateOf("") }
    var draftSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(persistedDraft) {
        if (!draftSeeded) {
            draftText = persistedDraft.orEmpty()
            draftSeeded = true
        }
    }

    LaunchedEffect(state.lastFailure) {
        state.lastFailure?.let { failure ->
            snackbarHostState.showSnackbar(
                when (failure) {
                    ThreadFailure.SEND -> sendFailedMessage
                    ThreadFailure.DOWNLOAD -> downloadFailedMessage
                },
            )
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
        onBack = onBack,
        draft = draftText,
        onDraftChange = { text ->
            draftText = text
            viewModel.onDraftChanged(text)
        },
        subject = subjectText,
        onSubjectChange = { subjectText = it },
        onSubmit = { body ->
            viewModel.onDraftSubmitted(body, subjectText)
            draftText = ""
            subjectText = ""
        },
        onRequestSmsRole = onRequestSmsRole,
        onDeleteMessage = viewModel::deleteMessage,
        onResendMessage = viewModel::resendMessage,
        onForward = onForward,
        onPickAttachment = onPickAttachment,
        onRemoveAttachment = viewModel::removeAttachment,
        onDownloadAttachment = viewModel::downloadAttachment,
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
    onBack: () -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    subject: String,
    onSubjectChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onRequestSmsRole: () -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onResendMessage: (Long) -> Unit,
    onForward: (String) -> Unit,
    onPickAttachment: () -> Unit,
    onToggleMuted: () -> Unit,
    onMessageNumber: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDownloadAttachment: (Long) -> Unit,
) {
    // The bubble the user long-pressed, held until the action sheet is answered.
    var selectedBubble by remember { mutableStateOf<MessageBubble?>(null) }

    // The bubble offered for deletion, held until the confirmation dialog is
    // answered. Deleting a message is permanent, so it is never implicit.
    var pendingDelete by remember { mutableStateOf<MessageBubble?>(null) }

    val clipboard = LocalClipboardManager.current
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
    val title = recipient.ifBlank { genericTitle }
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
                        )
                    }
                },
                actions = {
                    // One bell-off glyph covers both directions; the label says
                    // what a tap will do (Mute when live, Unmute when silenced),
                    // so the state is always discoverable.
                    val muteLabel = stringResource(
                        if (state.isMuted) R.string.thread_unmute else R.string.thread_mute,
                    )
                    IconButton(onClick = onToggleMuted) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_mute),
                            contentDescription = muteLabel,
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
                // The counter describes SMS parts only. A group address (several
                // members) or any attachment sends the text as MMS, where it is
                // one body and the per-part arithmetic does not apply.
                val isMms = state.pendingAttachments.isNotEmpty() ||
                    AddressNormalizer.membersOf(recipient).size > 1
                Composer(
                    draft = draft,
                    onDraftChange = onDraftChange,
                    isSending = state.isSending,
                    onSubmit = onSubmit,
                    pendingAttachments = state.pendingAttachments,
                    onPickAttachment = onPickAttachment,
                    onRemoveAttachment = onRemoveAttachment,
                    showSegmentCounter = !isMms,
                    subject = subject,
                    onSubjectChange = onSubjectChange,
                    showSubject = isMms,
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
                        onDownloadAttachment = onDownloadAttachment,
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
                clipboard.setText(AnnotatedString(bubble.body))
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
    onDownloadAttachment: (Long) -> Unit,
    onMessageNumber: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    // New messages arrive at the end. Scrolling to the latest one keeps the
    // newest content visible without yanking the user if they scrolled up to
    // read history.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
                onDownloadAttachment = onDownloadAttachment,
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
    onDownloadAttachment: (Long) -> Unit,
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
                if (bubble.attachments.isNotEmpty()) {
                    bubble.attachments.forEach { attachment ->
                        AttachmentPreview(attachment = attachment)
                    }
                }
                // A carrier-supplied MMS subject sits above the body like an
                // email header; text-only and outgoing MMS rows rarely have one.
                if (bubble.isMms && !bubble.subject.isNullOrBlank()) {
                    Text(
                        text = bubble.subject!!,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                // URLs, emails, WhatsApp links, phone numbers and coordinates
                // are tappable inside the bubble. The body stays plain text in
                // the accessibility label, which already reads out the message.
                if (bubble.body.isNotBlank()) {
                    LinkifiedText(
                        text = bubble.body,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 20,
                        overflow = TextOverflow.Ellipsis,
                        onMessage = onMessageNumber,
                    )
                }
                if (bubble.needsDownload) {
                    TextButton(
                        onClick = { onDownloadAttachment(bubble.id) },
                        modifier = Modifier.testTag(ThreadTestTags.DOWNLOAD_BUTTON),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_download),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.thread_download_attachment),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (bubble.isMms) {
                        Text(
                            text = "MMS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
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
    pendingAttachments: List<AttachmentPart>,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    showSegmentCounter: Boolean,
    subject: String,
    onSubjectChange: (String) -> Unit,
    showSubject: Boolean,
) {
    val draftLabel = stringResource(R.string.thread_composer_description)
    val subjectLabel = stringResource(R.string.thread_subject_label)
    val subjectHint = stringResource(R.string.thread_subject_hint)
    val sendLabel = stringResource(R.string.thread_send)
    val hint = stringResource(R.string.thread_draft_hint)
    val attachLabel = stringResource(R.string.thread_attach)
    val removeLabel = stringResource(R.string.thread_remove_attachment)
    val canSend = (draft.isNotBlank() || pendingAttachments.isNotEmpty()) && !isSending

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ThreadTestTags.COMPOSER),
        ) {
            // The subject is an MMS-only field: it appears once the message has
            // an attachment or several recipients, which is what makes the route
            // MMS. Showing it for a plain SMS would offer a field the SMS leg
            // silently drops.
            if (showSubject) {
                OutlinedTextField(
                    value = subject,
                    onValueChange = onSubjectChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                        .testTag(ThreadTestTags.SUBJECT_FIELD)
                        .semantics { contentDescription = subjectLabel },
                    label = { Text(subjectHint) },
                    singleLine = true,
                    enabled = !isSending,
                )
            }

            if (pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingAttachments.forEach { attachment ->
                        AttachmentChip(
                            fileName = attachment.fileName ?: "attachment",
                            onRemove = { onRemoveAttachment(attachment.filePath.orEmpty()) },
                            removeLabel = removeLabel,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onPickAttachment,
                    enabled = !isSending,
                    modifier = Modifier.semantics { contentDescription = attachLabel },
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                }

                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ThreadTestTags.DRAFT_FIELD)
                            .semantics { contentDescription = draftLabel },
                        placeholder = { Text(hint) },
                        maxLines = 5,
                        enabled = !isSending,
                    )

                    if (showSegmentCounter && draft.isNotEmpty()) {
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

@Composable
internal fun AttachmentChip(
    fileName: String,
    onRemove: () -> Unit,
    removeLabel: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp).semantics { contentDescription = removeLabel },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}