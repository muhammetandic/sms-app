package com.anttech.smsapp.ui.spam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.contacts.ContactNameResolver
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.ConversationSummary
import com.anttech.smsapp.ui.TimeFormat
import com.anttech.smsapp.ui.conversations.ConversationRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SpamUiState(
    val conversations: List<ConversationRow> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * The spam folder: the conversations the user moved out of the inbox.
 *
 * Reads the same rows as the inbox from a different query, so marking something
 * as spam and restoring it is a single flag change rather than a move, and
 * neither list can disagree about which conversation it holds.
 */
class SpamViewModel(
    private val repository: MessageRepository,
    private val contactNames: ContactNameResolver? = null,
) : ViewModel() {

    private val resolvedNames = MutableStateFlow<Map<String, String?>>(emptyMap())

    val uiState: StateFlow<SpamUiState> = combine(
        repository.observeSpamConversations(),
        resolvedNames,
    ) { summaries, names ->
        SpamUiState(
            conversations = summaries.map { summary -> summary.toSpamRow(names[summary.addressKey]) },
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SpamUiState(),
    )

    /** Contact names cannot be part of the query, so they resolve off the flow. */
    fun resolveContactNames() {
        val resolver = contactNames ?: return
        if (!resolver.hasPermission()) return

        viewModelScope.launch {
            resolver.preload()
            val summaries = repository.observeSpamConversations().first()
            resolvedNames.value = summaries.associate { summary ->
                summary.addressKey to resolver.displayName(summary.displayAddress)
            }
        }
    }

    /** Returns a conversation to the inbox. */
    fun restore(conversationId: Long) {
        viewModelScope.launch {
            repository.setConversationSpam(conversationId, isSpam = false)
        }
    }

    /** Returns several conversations to the inbox at once. */
    fun restore(ids: Set<Long>) {
        viewModelScope.launch {
            repository.setConversationsSpam(ids, isSpam = false)
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

    private fun ConversationSummary.toSpamRow(resolvedName: String?) = ConversationRow(
        id = id,
        address = displayAddress.ifBlank { addressKey },
        displayName = resolvedName,
        snippet = snippet.orEmpty(),
        timestampLabel = TimeFormat.conversationTimestamp(lastMessageAt),
        // Marking as spam clears the unread count in the same write, so a row
        // that reaches this screen is read unless a later message arrived.
        unreadCount = unreadCount,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(
            repository: MessageRepository,
            contactNames: ContactNameResolver? = null,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SpamViewModel(repository, contactNames) as T
        }
    }
}
