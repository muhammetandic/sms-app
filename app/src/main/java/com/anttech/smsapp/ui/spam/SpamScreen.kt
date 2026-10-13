package com.anttech.smsapp.ui.spam

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anttech.smsapp.R
import com.anttech.smsapp.ui.components.ConfirmDeleteConversations
import com.anttech.smsapp.ui.conversations.ConversationItem
import com.anttech.smsapp.ui.conversations.ConversationRow
import com.anttech.smsapp.ui.theme.iconDelete
import com.anttech.smsapp.ui.theme.iconNeutral
import com.anttech.smsapp.ui.theme.iconRestore

@Composable
fun SpamRoute(
    viewModel: SpamViewModel,
    onOpenConversation: (ConversationRow) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.resolveContactNames() }

    SpamScreen(
        state = state,
        onOpenConversation = onOpenConversation,
        onRestore = viewModel::restore,
        onRestoreMany = viewModel::restore,
        onDeleteConversations = viewModel::delete,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamScreen(
    state: SpamUiState,
    onOpenConversation: (ConversationRow) -> Unit,
    onRestore: (Long) -> Unit,
    onRestoreMany: (Set<Long>) -> Unit,
    onDeleteConversations: (Set<Long>) -> Unit,
    onBack: () -> Unit,
) {
    // Selected conversation ids, mirroring the inbox: an empty set is normal
    // mode, anything else swaps the back button for a close and adds the
    // restore/delete actions to the toolbar.
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val isSelecting = selectedIds.isNotEmpty()

    // The batch being deleted, held until the confirmation dialog is answered.
    // Deleting from the spam folder is permanent, just like the inbox.
    var pendingDelete by remember { mutableStateOf<Set<Long>?>(null) }

    val backLabel = stringResource(R.string.thread_back)
    val closeSelectionLabel = stringResource(R.string.selection_close)
    val moveToInboxLabel = stringResource(R.string.conversations_move_to_inbox)
    val deleteLabel = stringResource(R.string.dialog_delete)

    Scaffold(
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
                            stringResource(R.string.spam_title)
                        },
                    )
                },
                navigationIcon = {
                    if (isSelecting) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
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
                            onRestoreMany(selectedIds)
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_restore),
                                contentDescription = moveToInboxLabel,
                                tint = MaterialTheme.colorScheme.iconRestore,
                            )
                        }
                        IconButton(onClick = { pendingDelete = selectedIds }) {
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
                state.conversations.isEmpty() -> EmptySpam()
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                            trailingAction = if (!isSelecting) {
                                {
                                    IconButton(onClick = { onRestore(row.id) }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_action_restore),
                                            contentDescription = stringResource(R.string.spam_restore),
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

@Composable
private fun EmptySpam() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.spam_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}