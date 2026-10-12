package com.anttech.smsapp.ui.drafts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.contacts.ContactNameResolver
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.DraftConversationSummary
import com.anttech.smsapp.ui.TimeFormat
import com.anttech.smsapp.ui.conversations.ConversationRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DraftsUiState(
    val conversations: List<ConversationRow> = emptyList(),
    val isLoading: Boolean = true,
)

class DraftsViewModel(
    private val repository: MessageRepository,
    private val contactNames: ContactNameResolver? = null,
) : ViewModel() {

    private val resolvedNames = MutableStateFlow<Map<String, String?>>(emptyMap())

    val uiState: StateFlow<DraftsUiState> = combine(
        repository.observeDraftConversations(),
        resolvedNames,
    ) { drafts, names ->
        DraftsUiState(
            conversations = drafts.map { it.toRow(names[it.addressKey]) },
            isLoading = false,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        DraftsUiState(),
    )

    fun resolveContactNames() {
        val resolver = contactNames ?: return
        if (!resolver.hasPermission()) return
        viewModelScope.launch {
            resolver.preload()
            val drafts = repository.observeDraftConversations().first()
            resolvedNames.value = drafts.associate { it.addressKey to resolver.displayLabel(it.displayAddress) }
        }
    }

    fun deleteDraft(draftId: Long) {
        viewModelScope.launch { repository.deleteDraft(draftId) }
    }

    private fun DraftConversationSummary.toRow(name: String?) = ConversationRow(
        id = draftId,
        address = displayAddress.ifBlank { addressKey },
        displayName = name,
        snippet = body,
        timestampLabel = TimeFormat.conversationTimestamp(lastMessageAt),
        unreadCount = 0,
    )

    companion object {
        fun factory(repository: MessageRepository, contactNames: ContactNameResolver? = null) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DraftsViewModel(repository, contactNames) as T
            }
    }
}
