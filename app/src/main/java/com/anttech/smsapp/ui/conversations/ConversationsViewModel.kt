package com.anttech.smsapp.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.contacts.ContactNameResolver
import com.anttech.smsapp.data.ConversationRepository
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
import java.time.ZoneId

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
    private val repository: ConversationRepository,
    private val isSmsRoleHeld: () -> Boolean,
    private val contactNames: ContactNameResolver? = null,
    /**
     * Dismisses the alerts for the given conversations. Injected rather than
     * built from a Context so the ViewModel stays free of framework services
     * and a test can observe the call.
     */
    private val cancelNotification: (Set<Long>) -> Unit = {},
    /**
     * Resource-resolved stand-in for the hardcoded "Yesterday" that
     * [TimeFormat.conversationTimestamp] falls back to. A plain value rather than
     * a Context keeps the ViewModel free of framework services; the default only
     * lets tests build one without a resource bundle, so production always passes
     * the translated string from the NavHost.
     */
    private val yesterdayLabel: String = "Yesterday",
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
        // One zone for the whole list; see [TimeFormat.defaultZone].
        val zone = TimeFormat.defaultZone()
        ConversationsUiState(
            conversations = summaries.map { summary ->
                summary.toRow(names[summary.addressKey], zone)
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

    /** Clears the unread badge on several selected conversations. */
    fun markConversationsRead(ids: Set<Long>) {
        viewModelScope.launch {
            repository.setConversationsRead(ids)
            // Reading them is the moment their alerts have served their purpose,
            // so they go away with the badge rather than contradicting the list.
            cancelNotification(ids)
        }
    }

    /** Clears the unread badge on every conversation at once. */
    fun markAllConversationsRead() {
        viewModelScope.launch {
            repository.markAllConversationsRead()
            cancelNotification(repository.conversationIds().toSet())
        }
    }

    /** Hides the selected conversations in the archive folder. */
    fun archiveConversations(ids: Set<Long>) {
        viewModelScope.launch { repository.setConversationsArchived(ids, isArchived = true) }
    }

    /** Reverses the last archive operation, moving conversations back to the inbox. */
    fun undoArchive(ids: Set<Long>) {
        viewModelScope.launch { repository.setConversationsArchived(ids, isArchived = false) }
    }

    /** Reverses the last spam operation, moving conversations back to the inbox. */
    fun undoSpam(ids: Set<Long>) {
        viewModelScope.launch { repository.setConversationsSpam(ids, isSpam = false) }
    }

    private fun ConversationSummary.toRow(resolvedName: String?, zone: ZoneId) = ConversationRow(
        id = id,
        address = displayAddress.ifBlank { addressKey },
        displayName = resolvedName,
        snippet = snippet.orEmpty(),
        timestampLabel = TimeFormat.conversationTimestamp(lastMessageAt, yesterdayLabel = yesterdayLabel, zone = zone),
        unreadCount = unreadCount,
        isPinned = isPinned,
        isMuted = isMuted,
        isGroup = displayAddress.contains(", "),
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(
            repository: ConversationRepository,
            isSmsRoleHeld: () -> Boolean,
            contactNames: ContactNameResolver? = null,
            cancelNotification: (Set<Long>) -> Unit = {},
            yesterdayLabel: String,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationsViewModel(
                    repository,
                    isSmsRoleHeld,
                    contactNames,
                    cancelNotification,
                    yesterdayLabel,
                ) as T
        }
    }
}
