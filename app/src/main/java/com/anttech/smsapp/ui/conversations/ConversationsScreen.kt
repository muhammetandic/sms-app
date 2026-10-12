package com.anttech.smsapp.ui.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anttech.smsapp.R
import com.anttech.smsapp.ui.components.ConfirmDeleteConversations
import com.anttech.smsapp.ui.components.ReadOnlyBanner
import com.anttech.smsapp.ui.theme.OnAvatarColor
import com.anttech.smsapp.ui.theme.avatarColorFor

@Composable
fun ConversationsRoute(
    viewModel: ConversationsViewModel,
    roleHeldNow: Boolean,
    onOpenConversation: (ConversationRow) -> Unit,
    onRequestSmsRole: () -> Unit,
    onResume: () -> Unit,
    onOpenSpam: () -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onCompose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(roleHeldNow) { viewModel.refreshRole() }

    // Contact names are resolved per address off the Room flow, so they cannot
    // be part of the query. Re-resolved on resume because a name can be added to
    // the device while this screen is in the background. The role is re-read for
    // the same reason: it can be revoked from system settings while the app is
    // paused, and the read-only banner keys off the ViewModel's copy of it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshRole()
                onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ConversationsScreen(
        state = state,
        onOpenConversation = onOpenConversation,
        onRequestSmsRole = onRequestSmsRole,
        onOpenSpam = onOpenSpam,
        onOpenDrafts = onOpenDrafts,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onCompose = onCompose,
        onDeleteConversations = viewModel::deleteConversations,
        onMoveToSpam = viewModel::moveToSpam,
        onSetPinned = viewModel::setConversationsPinned,
        onMarkUnread = viewModel::markConversationsUnread,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onOpenConversation: (ConversationRow) -> Unit,
    onRequestSmsRole: () -> Unit,
    onOpenSpam: () -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onCompose: () -> Unit,
    onDeleteConversations: (Set<Long>) -> Unit,
    onMoveToSpam: (Set<Long>) -> Unit,
    onSetPinned: (Set<Long>, Boolean) -> Unit,
    onMarkUnread: (Set<Long>) -> Unit,
) {
    // Selected conversation ids; an empty set means normal mode, anything else
    // puts the bar into selection mode. Long-pressing a row enters it with that
    // row selected, and tapping rows while it is active toggles them.
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val isSelecting = selectedIds.isNotEmpty()

    // The batch being deleted, held until the confirmation dialog is answered.
    // A dialog is required because deletion is permanent.
    var pendingDelete by remember { mutableStateOf<Set<Long>?>(null) }

    val closeSelectionLabel = stringResource(R.string.selection_close)
    val pinLabel = stringResource(R.string.conversations_pin)
    val unpinLabel = stringResource(R.string.conversations_unpin)
    val moveToSpamLabel = stringResource(R.string.conversations_move_to_spam)
    val markUnreadLabel = stringResource(R.string.conversations_mark_unread)
    val deleteLabel = stringResource(R.string.dialog_delete)

    Scaffold(
        // The list scrolls under the bar, so the bar has to be told the content
        // goes behind it rather than just painting over the top of it.
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isSelecting) {
                            pluralStringResource(
                                R.plurals.selection_count,
                                selectedIds.size,
                                selectedIds.size,
                            )
                        } else {
                            stringResource(R.string.app_name)
                        },
                    )
                },
                navigationIcon = {
                    if (isSelecting) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = closeSelectionLabel,
                            )
                        }
                    }
                },
                actions = {
                    if (isSelecting) {
                        val selectedRows = state.conversations.filter { it.id in selectedIds }
                        // All pinned flips to unpin; a mixed or unpinned batch pins.
                        val allPinned = selectedRows.isNotEmpty() && selectedRows.all { it.isPinned }
                        IconButton(onClick = {
                            onMoveToSpam(selectedIds)
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_spam),
                                contentDescription = moveToSpamLabel,
                            )
                        }
                        IconButton(onClick = {
                            onSetPinned(selectedIds, !allPinned)
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_pin),
                                contentDescription = if (allPinned) unpinLabel else pinLabel,
                            )
                        }
                        IconButton(onClick = {
                            onMarkUnread(selectedIds)
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_unread),
                                contentDescription = markUnreadLabel,
                            )
                        }
                        IconButton(onClick = { pendingDelete = selectedIds }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_delete),
                                contentDescription = deleteLabel,
                            )
                        }
                    } else {
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search_title),
                            )
                        }
                        IconButton(onClick = onOpenSpam) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_spam),
                                contentDescription = stringResource(R.string.spam_open),
                            )
                        }
                        IconButton(onClick = onOpenDrafts) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_unread),
                                contentDescription = stringResource(R.string.drafts_open),
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings_open),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            // Composing while selecting would end the selection, so the button
            // is hidden to keep the two modes visually distinct.
            if (!isSelecting) {
                FloatingActionButton(onClick = onCompose) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.conversations_compose),
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isReadOnly) {
                ReadOnlyBanner(onAction = onRequestSmsRole)
            }

            when {
                state.isLoading -> Unit
                state.conversations.isEmpty() -> EmptyConversations()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Clears the floating action button, which would otherwise sit
                    // on top of the last row.
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(state.conversations, key = { it.id }) { row ->
                        ConversationItem(
                            row = row,
                            onClick = {
                                if (isSelecting) {
                                    selectedIds = toggleSelection(selectedIds, row.id)
                                } else {
                                    onOpenConversation(row)
                                }
                            },
                            onLongClick = { selectedIds = toggleSelection(selectedIds, row.id) },
                            selected = row.id in selectedIds,
                            showPin = true,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { ids ->
        ConfirmDeleteConversations(
            count = ids.size,
            onConfirm = {
                pendingDelete = null
                selectedIds = emptySet()
                onDeleteConversations(ids)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Selects the row that does not have it yet, deselects the one that does. */
private fun toggleSelection(selected: Set<Long>, id: Long): Set<Long> =
    if (id in selected) selected - id else selected + id

/**
 * One conversation row, shared by the inbox and the spam folder.
 *
 * [trailingAction] carries the per-row control the spam folder needs; the inbox
 * passes nothing, because a conversation in the inbox has nothing to undo.
 * Selection is rendered in place of the leading avatar, and [showPin] decides
 * whether a pinned conversation advertises its pin.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationItem(
    row: ConversationRow,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    showPin: Boolean = false,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    // The row is one accessibility node: a screen reader should announce sender,
    // preview, time and unread count as a single item rather than four fragments.
    val unreadLabel = if (row.unreadCount > 0) {
        pluralStringResource(
            R.plurals.conversation_unread_count,
            row.unreadCount,
            row.unreadCount,
        )
    } else {
        ""
    }
    val rowDescription = stringResource(
        R.string.conversation_row_description,
        row.title,
        row.snippet,
        row.timestampLabel,
        unreadLabel,
    )

    // The sender, preview, time and unread count are announced as a single
    // accessibility item rather than four fragments, so the merge is scoped to
    // the text and the trailing column only. The trailing action sits outside
    // it: merging would fold the button into the row's label and leave it
    // unreachable as a button for a screen reader.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // The gutter lives here rather than on the list so the inbox and the
            // spam folder, which share this row, stay in step.
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = MaterialTheme.shapes.large,
        // A tonal surface is what separates the rows now that the dividers are
        // gone. On a dynamic scheme this role is derived from the wallpaper, so
        // it stays distinct from the scaffold in both light and dark. A selected
        // row trades it for a filled container so the selection reads at a glance.
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Long-press enters selection; while selecting, tapping toggles,
                // otherwise a tap opens the conversation.
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected) {
                // Selected rows swap the avatar for a filled check so the choice
                // reads like a checkbox. Both are hidden from the accessibility
                // tree: the row still announces sender, preview, time and unread.
                Box(modifier = Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else {
                Avatar(title = row.title, colorKey = row.address)
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { contentDescription = rowDescription },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (row.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.isGroup) {
                        Text(
                            text = stringResource(R.string.conversations_group_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = row.snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (showPin && row.isPinned) {
                            // The pin means nothing to a screen reader here, so it
                            // stays out of the announcement rather than repeating
                            // itself on every pinned row.
                            Icon(
                                painter = painterResource(R.drawable.ic_action_pin),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        if (row.isMuted) {
                            // Same reasoning as the pin: the label already names the
                            // thread, and a bell-off glyph adds nothing for a screen
                            // reader beyond repetition.
                            Icon(
                                painter = painterResource(R.drawable.ic_action_mute),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = row.timestampLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.unreadCount > 0) {
                        // The count is already spoken by the row label, so the badge
                        // itself stays out of the announcement.
                        Box(modifier = Modifier.clearAndSetSemantics { }) {
                            Badge { Text(row.unreadCount.toString()) }
                        }
                    }
                }
            }

            trailingAction?.invoke()
        }
    }
}

/**
 * Leading circle carrying the sender's first character.
 *
 * The fill is picked per sender from [AvatarColors], keyed on the stored address
 * so a row keeps its colour once a contact name resolves. Hidden from the
 * accessibility tree because the row label already names the sender, and this
 * would repeat it.
 */
@Composable
internal fun Avatar(title: String, colorKey: String = title, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = CircleShape,
            color = avatarColorFor(colorKey),
        ) {}
        Text(
            text = title.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = OnAvatarColor,
        )
    }
}

@Composable
private fun EmptyConversations() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.conversations_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
