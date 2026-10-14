package com.anttech.smsapp.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anttech.smsapp.R
import com.anttech.smsapp.ui.conversations.ConversationItem
import com.anttech.smsapp.ui.conversations.ConversationRow
import com.anttech.smsapp.ui.theme.iconDelete
import com.anttech.smsapp.ui.theme.iconNeutral
import com.anttech.smsapp.ui.theme.iconRestore

/**
 * The rows a folder screen shows.
 *
 * Shared by spam and the archive: both are the inbox under a different hidden
 * flag, so both render the same rows and only their labels and the verb that
 * takes a row back to the inbox differ.
 */
data class FolderUiState(
    val conversations: List<ConversationRow> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * A hidden-conversations folder: spam or the archive.
 *
 * The two screens were byte-for-byte identical apart from their title, empty
 * text, per-row action label and restore call, which is exactly the set of
 * parameters here. Keeping one composable means a fix to selection, deletion or
 * the toolbar cannot land in one folder and miss the other.
 *
 * @param title the header when not selecting.
 * @param emptyText shown when the folder holds nothing.
 * @param restoreLabel the per-row action's accessibility label.
 * @param onRestore returns a single conversation to the inbox.
 * @param onRestoreMany returns a selected batch to the inbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    @StringRes title: Int,
    @StringRes emptyText: Int,
    @StringRes restoreLabel: Int,
    state: FolderUiState,
    onOpenConversation: (ConversationRow) -> Unit,
    onRestore: (Long) -> Unit,
    onRestoreMany: (Set<Long>) -> Unit,
    onDeleteConversations: (Set<Long>) -> Unit,
    onBack: () -> Unit,
) {
    // An empty selection is normal mode; anything else swaps the back button for
    // a close and adds the restore/delete actions to the toolbar.
    val selection = rememberSelectionState<Long>()
    val isSelecting = selection.isSelecting

    // The batch being deleted, held until the confirmation dialog is answered.
    // Deleting from a folder is permanent, like the inbox.
    var pendingDelete by remember { mutableStateOf<Set<Long>?>(null) }

    val backLabel = stringResource(R.string.thread_back)
    val closeSelectionLabel = stringResource(R.string.selection_close)
    val moveToInboxLabel = stringResource(R.string.conversations_move_to_inbox)
    val deleteLabel = stringResource(R.string.dialog_delete)
    val restoreDescription = stringResource(restoreLabel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isSelecting) {
                            pluralStringResource(
                                R.plurals.selection_count,
                                selection.size,
                                selection.size,
                            )
                        } else {
                            stringResource(title)
                        },
                    )
                },
                navigationIcon = {
                    if (isSelecting) {
                        IconButton(onClick = selection::clear) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = closeSelectionLabel,
                                tint = MaterialTheme.colorScheme.iconNeutral,
                            )
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = backLabel,
                                tint = MaterialTheme.colorScheme.iconNeutral,
                            )
                        }
                    }
                },
                actions = {
                    if (isSelecting) {
                        IconButton(onClick = {
                            onRestoreMany(selection.selectedIds)
                            selection.clear()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_restore),
                                contentDescription = moveToInboxLabel,
                                tint = MaterialTheme.colorScheme.iconRestore,
                            )
                        }
                        IconButton(onClick = { pendingDelete = selection.selectedIds }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_delete),
                                contentDescription = deleteLabel,
                                tint = MaterialTheme.colorScheme.iconDelete,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Unit
                state.conversations.isEmpty() -> FolderEmpty(emptyText)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.conversations, key = { it.id }) { row ->
                        ConversationItem(
                            row = row,
                            onClick = {
                                if (isSelecting) {
                                    selection.toggle(row.id)
                                } else {
                                    onOpenConversation(row)
                                }
                            },
                            onLongClick = { selection.toggle(row.id) },
                            selected = selection.isSelected(row.id),
                            trailingAction = if (!isSelecting) {
                                {
                                    IconButton(onClick = { onRestore(row.id) }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_action_restore),
                                            contentDescription = restoreDescription,
                                            tint = MaterialTheme.colorScheme.iconRestore,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
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
                selection.clear()
                onDeleteConversations(ids)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun FolderEmpty(@StringRes text: Int) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
