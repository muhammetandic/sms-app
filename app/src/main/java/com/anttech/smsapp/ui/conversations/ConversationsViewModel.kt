package com.anttech.smsapp.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.contacts.ContactNameResolver
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.ConversationSummary
import com.anttech.smsapp.ui.TimeFormat
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
 * State for the conversation list.
 *
 * [isReadOnly] follows the SMS role rather than the permission flags: the system
 * revokes SMS access when the role is lost, so a still-granted flag is not by
 * itself a reason to offer to send.
 */
data class ConversationsUiState(
    val conversations: List<ConversationRow> = emptyList(),
    val isLoading: Boolean = true,
    val isReadOnly: Boolean = true,
)

data class ConversationRow(
    val id: Long,
    val address: String,
    val displayName: String?,
    val snippet: String,
    val timestampLabel: String,
    val unreadCount: Int,
    val isPinned: Boolean = false,
    /** Alerts silenced; the row stays in the inbox with its unread badge. */
    val isMuted: Boolean = false,
    /** True when more than one recipient shares the thread. */
    val isGroup: Boolean = false,
) {
    /** Contact name when one resolved, otherwise the number as stored. */
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: address
}

class ConversationsViewModel(
    private val repository: MessageRepository,
    private val isSmsRoleHeld: () -> Boolean,
    private val contactNames: ContactNameResolver? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /**
     * Read live rather than sampled once. The role can be revoked while the
     * screen is in the background, and the UI has to reflect that on resume.
     */
    private val roleHeld = MutableStateFlow(isSmsRoleHeld())

    /** Contact names are resolved per address and merged into the rows. */
    private val resolvedNames = MutableStateFlow<Map<String, String?>>(emptyMap())

    val uiState: StateFlow<ConversationsUiState> = combine(
        repository.observeConversations(),
        roleHeld,
        resolvedNames,
    ) { summaries, roleIsHeld, names ->
        ConversationsUiState(
            conversations = summaries.map { summary ->
                summary.toRow(names[summary.addressKey])
            },
            isLoading = false,
            isReadOnly = !roleIsHeld,
        )
        // Rows are rebuilt (and timestamps formatted) for the whole list on every
        // store emission; viewModelScope would run that on the main thread. The
        // mapping is pure, so it belongs on a background dispatcher.
    }.flowOn(dispatcher).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        // The role is already known synchronously, so seed the first frame with
        // it. Seeding the default (read-only) paints the "role lost" banner
        // while the first query runs, on every open, even while the role is held.
        initialValue = ConversationsUiState(isReadOnly = !roleHeld.value),
    )

    /**
     * Looks up contact names for the rows currently shown.
     *
     * Called on first load and whenever the list changes, because a name can
     * arrive after the conversation that needs it.
     */
    fun resolveContactNames() {
        val resolver = contactNames ?: return
        if (!resolver.hasPermission()) return

        viewModelScope.launch {
            resolver.preload()
            val summaries = repository.observeConversations().first()
            val resolved = summaries.associate { summary ->
                summary.addressKey to resolver.displayLabel(summary.displayAddress)
            }
            resolvedNames.value = resolved
        }
    }

    /** Re-reads the role; call from `onResume`, since the loss happens off-screen. */
    fun refreshRole() {
        roleHeld.value = isSmsRoleHeld()
    }

    /** Deletes a conversation and every message it holds, permanently. */
    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch { repository.deleteConversation(conversationId) }
    }

    /** Deletes several conversations and everything they hold, permanently. */
    fun deleteConversations(ids: Set<Long>) {
        viewModelScope.launch { repository.deleteConversations(ids) }
    }

    /** Moves several conversations to the spam folder. Pins survive the move. */
    fun moveToSpam(ids: Set<Long>) {
        viewModelScope.launch { repository.setConversationsSpam(ids, isSpam = true) }
    }

    /** Pins or unpins several conversations at once. */
    fun setConversationsPinned(ids: Set<Long>, isPinned: Boolean) {
        viewModelScope.launch { repository.setConversationsPinned(ids, isPinned) }
    }

    /** Restores the unread badge for several already-open conversations. */
    fun markConversationsUnread(ids: Set<Long>) {
        viewModelScope.launch { repository.setConversationsUnread(ids) }
    }

    private fun ConversationSummary.toRow(resolvedName: String?) = ConversationRow(
        id = id,
        address = displayAddress.ifBlank { addressKey },
        displayName = resolvedName,
        snippet = snippet.orEmpty(),
        timestampLabel = TimeFormat.conversationTimestamp(lastMessageAt),
        unreadCount = unreadCount,
        isPinned = isPinned,
        isMuted = isMuted,
        isGroup = displayAddress.contains(", "),
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(
            repository: MessageRepository,
            isSmsRoleHeld: () -> Boolean,
            contactNames: ContactNameResolver? = null,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationsViewModel(repository, isSmsRoleHeld, contactNames) as T
        }
    }
}
