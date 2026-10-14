package com.anttech.smsapp.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.contacts.ContactNameResolver
import com.anttech.smsapp.data.ConversationRepository
import com.anttech.smsapp.data.local.ConversationSummary
import com.anttech.smsapp.ui.TimeFormat
import com.anttech.smsapp.ui.components.FolderUiState
import com.anttech.smsapp.ui.conversations.ConversationRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The archive: conversations the user hid from the inbox without
 * deleting.
 *
 * Reads the same rows as the inbox through a different query, so
 * archiving and restoring are one flag change and neither list can
 * hold a row the other still claims. Unlike spam, an archived
 * conversation is ordinary mail: a new message returns it to the
 * inbox on its own.
 */
class ArchiveViewModel(
    private val repository: ConversationRepository,
    private val contactNames: ContactNameResolver? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val resolvedNames = MutableStateFlow<Map<String, String?>>(emptyMap())

    val uiState: StateFlow<FolderUiState> = combine(
        repository.observeArchivedConversations(),
        resolvedNames,
    ) { summaries, names ->
        FolderUiState(
            conversations = summaries.map { summary -> summary.toArchiveRow(names[summary.addressKey]) },
            isLoading = false,
        )
        // Row building formats a timestamp per conversation; keep it off
        // the main thread so a long archive does not stutter the frame
        // that shows it.
    }.flowOn(dispatcher).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = FolderUiState(),
    )

    /** Contact names cannot be part of the query, so they resolve off the flow. */
    fun resolveContactNames() {
        val resolver = contactNames ?: return
        if (!resolver.hasPermission()) return

        viewModelScope.launch {
            resolver.preload()
            val summaries = repository.observeArchivedConversations().first()
            resolvedNames.value = summaries.associate { summary ->
                summary.addressKey to resolver.displayLabel(summary.displayAddress)
            }
        }
    }

    /** Returns a conversation to the inbox. */
    fun restore(conversationId: Long) {
        viewModelScope.launch {
            repository.setConversationsArchived(listOf(conversationId), isArchived = false)
        }
    }

    /** Returns several conversations to the inbox at once. */
    fun restore(ids: Set<Long>) {
        viewModelScope.launch {
            repository.setConversationsArchived(ids, isArchived = false)
        }
    }

    /** Deletes a conversation and every message it holds, permanently. */
    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch { repository.deleteConversation(conversationId) }
    }

    /** Deletes several conversations and everything they hold, permanently. */
    fun delete(ids: Set<Long>) {
        viewModelScope.launch { repository.deleteConversations(ids) }
    }

    private fun ConversationSummary.toArchiveRow(resolvedName: String?) = ConversationRow(
        id = id,
        address = displayAddress.ifBlank { addressKey },
        displayName = resolvedName,
        snippet = snippet.orEmpty(),
        timestampLabel = TimeFormat.conversationTimestamp(lastMessageAt),
        // Archiving leaves the unread count in place, so a row that
        // reaches this screen may still carry the badge it earned
        // before it was hidden.
        unreadCount = unreadCount,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(
            repository: ConversationRepository,
            contactNames: ContactNameResolver? = null,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ArchiveViewModel(repository, contactNames) as T
        }
    }
}
