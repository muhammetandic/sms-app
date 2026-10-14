package com.anttech.smsapp.ui.archive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anttech.smsapp.R
import com.anttech.smsapp.ui.components.FolderScreen
import com.anttech.smsapp.ui.conversations.ConversationRow

/**
 * The archive's entry point: collects the folder state and binds it to the
 * shared [FolderScreen]. Everything the archive does differently from spam is
 * the three labels and the restore call.
 */
@Composable
fun ArchiveRoute(
    viewModel: ArchiveViewModel,
    onOpenConversation: (ConversationRow) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Contact names are a provider query, not part of the Room flow, so they are
    // kicked off once the screen is on.
    LaunchedEffect(Unit) { viewModel.resolveContactNames() }

    FolderScreen(
        title = R.string.archive_title,
        emptyText = R.string.archive_empty,
        restoreLabel = R.string.archive_restore,
        state = state,
        onOpenConversation = onOpenConversation,
        onRestore = viewModel::restore,
        onRestoreMany = viewModel::restore,
        onDeleteConversations = viewModel::delete,
        onBack = onBack,
    )
}
