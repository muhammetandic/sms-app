package com.anttech.smsapp.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.MessageSearchHit
import com.anttech.smsapp.ui.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/** One row the search returned, whether it matched by body or by name. */
data class SearchResult(
    val conversationId: Long,
    val title: String,
    val address: String,
    val snippet: String,
    val timestampLabel: String,
    val sentAt: Long,
    /**
     * Row id of the matching message, or null when the hit is a whole
     * conversation that matched only by its contact's name. Unique per result,
     * so it is a safe LazyColumn key even when one conversation matches several
     * messages.
     */
    val messageId: Long?,
)

/**
 * Drives search across messages and contacts.
 *
 * Message bodies and addresses can be matched in SQL because the store owns
 * them. Contact display names live in another app, so rows that match by name
 * are found by resolving each conversation's address against the contacts
 * provider and, like the conversation list, falling back to the raw number.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val repository: MessageRepository,
    private val resolveName: suspend (String) -> String?,
) : ViewModel() {

    private val query = MutableStateFlow("")

    fun onQueryChange(value: String) {
        query.value = value
    }

    val results: StateFlow<List<SearchResult>> = query
        .debounce(DEBOUNCE_MILLIS)
        .flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf(emptyList())
            } else {
                repository.observeMessageSearch(q).mapLatest { hits -> buildResults(q, hits) }
            }
        }
        // Contact lookup is a provider query; together with the per-row naming
        // it belongs off the composition thread.
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    /** Body matches first; then conversations whose name alone matched. */
    private suspend fun buildResults(query: String, hits: List<MessageSearchHit>): List<SearchResult> {
        val matchedConversationIds = mutableSetOf<Long>()
        val found = hits.map { hit ->
            matchedConversationIds += hit.conversationId
            SearchResult(
                conversationId = hit.conversationId,
                title = resolveName(hit.displayAddress) ?: hit.displayAddress,
                address = hit.displayAddress,
                snippet = hit.body,
                timestampLabel = TimeFormat.conversationTimestamp(hit.sentAt),
                sentAt = hit.sentAt,
                messageId = hit.messageId,
            )
        }.toMutableList()

        // A conversation can match by its contact name while none of its stored
        // rows contain the text, so the address list has to be added to the SQL
        // results rather than filtered by them.
        for (conversation in repository.observeConversations().first()) {
            if (conversation.id in matchedConversationIds) continue
            val title = resolveName(conversation.displayAddress) ?: conversation.displayAddress
            if (title.contains(query, ignoreCase = true) ||
                conversation.displayAddress.contains(query, ignoreCase = true)
            ) {
                found += SearchResult(
                    conversationId = conversation.id,
                    title = title,
                    address = conversation.displayAddress,
                    snippet = conversation.snippet.orEmpty(),
                    timestampLabel = TimeFormat.conversationTimestamp(conversation.lastMessageAt),
                    sentAt = conversation.lastMessageAt,
                    messageId = null,
                )
            }
        }

        return found.sortedByDescending { it.sentAt }.take(MAX_RESULTS)
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 300L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val MAX_RESULTS = 200

        fun factory(
            repository: MessageRepository,
            resolveName: suspend (String) -> String?,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(repository, resolveName) as T
        }
    }
}