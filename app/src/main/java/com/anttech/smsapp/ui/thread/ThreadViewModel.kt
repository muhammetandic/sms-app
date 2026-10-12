package com.anttech.smsapp.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageEntity
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.sms.receive.LogTags
import com.anttech.smsapp.sms.send.SendOutcome
import com.anttech.smsapp.sms.send.SendState
import com.anttech.smsapp.ui.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ThreadUiState(
    val messages: List<MessageBubble> = emptyList(),
    val isReadOnly: Boolean = true,
    val isSending: Boolean = false,
    val lastFailure: ThreadFailure? = null,
    val canSend: Boolean = false,
    val isMuted: Boolean = false,
)

/**
 * The transient failure a thread can surface. Kept as a value rather than a
 * sentence so the wording stays in the (translatable) resources owned by the
 * composable.
 */
enum class ThreadFailure { SEND }

data class MessageBubble(
    val id: Long,
    val body: String,
    val timestampLabel: String,
    val isOutgoing: Boolean,
    val status: MessageStatus,
    val sentAt: Long,
    val deliveredAt: Long? = null,
    val errorCode: Int? = null,
    val simSlot: Int? = null,
    val partCount: Int = 1,
)

class ThreadViewModel(
    private val repository: MessageRepository,
    private val conversationId: Long,
    private val recipient: String,
    private val isSmsRoleHeld: () -> Boolean,
    private val send: suspend (recipient: String, body: String) -> SendOutcome,
) : ViewModel() {

    private val roleHeld = MutableStateFlow(isSmsRoleHeld())
    private val sending = MutableStateFlow(false)
    private val lastFailure = MutableStateFlow<ThreadFailure?>(null)
    private val muted = MutableStateFlow(false)

    /** The stored draft for this conversation, null until the store has spoken. */
    val draft: StateFlow<String?> = repository.observeDraft(conversationId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

    private val messages: Flow<List<MessageEntity>> = repository.observeMessages(conversationId)

    val uiState: StateFlow<ThreadUiState> = combine(
        messages,
        combine(roleHeld, sending, lastFailure, muted) { role, isSending, failure, mutedConversation ->
            Quadruple(role, isSending, failure, mutedConversation)
        },
    ) { messages, (roleIsHeld, isSending, failure, mutedConversation) ->
        ThreadUiState(
            messages = messages.map { it.toBubble() },
            isReadOnly = !roleIsHeld,
            isSending = isSending,
            lastFailure = failure,
            // A thread deep-linked from a notification can open before its address
            // resolves. Sending to an empty recipient is unrecoverable, so the
            // composer stays hidden until there is a real address to send to.
            canSend = roleIsHeld && recipient.isNotBlank(),
            isMuted = mutedConversation,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ThreadUiState(),
    )

    init {
        viewModelScope.launch {
            repository.markConversationRead(conversationId)
            muted.value = repository.isMuted(conversationId)
        }
    }

    fun refreshRole() {
        roleHeld.value = isSmsRoleHeld()
    }

    /** Flips the conversation mute flag and persists the new state. */
    fun toggleMuted() {
        val target = !muted.value
        muted.value = target
        viewModelScope.launch {
            repository.setConversationMuted(conversationId, target)
            muted.value = repository.isMuted(conversationId)
        }
    }

    fun onDraftChanged(body: String) {
        viewModelScope.launch { repository.saveDraft(conversationId, body) }
    }

    fun onDraftSubmitted(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || sending.value || recipient.isBlank()) return

        viewModelScope.launch {
            // Once the text becomes a message there is nothing to restore, so the
            // draft is cleared as the send starts rather than after it resolves.
            repository.saveDraft(conversationId, "")
            sending.value = true
            lastFailure.value = null
            try {
                val outcome = send(recipient, trimmed)
                if (outcome.state == SendState.FAILED) {
                    lastFailure.value = ThreadFailure.SEND
                }
            } catch (cancellation: CancellationException) {
                // Cancellation is not a send failure: swallowing it would leave
                // the coroutine running past the ViewModel's lifetime.
                throw cancellation
            } catch (throwable: Throwable) {
                Log.e(LogTags.STORE, "SMS send failed unexpectedly", throwable)
                lastFailure.value = ThreadFailure.SEND
            } finally {
                sending.value = false
            }
        }
    }

    fun dismissFailure() {
        lastFailure.value = null
    }

    /**
     * Re-sends a failed outgoing message. The failed row is dropped and the text
     * is composed again through the normal send path, so a retry behaves exactly
     * like a fresh message: same validation, same status resolution, and the
     * failed bubble is replaced rather than corrected in place.
     */
    fun resendMessage(messageId: Long) {
        viewModelScope.launch {
            val message = repository.getMessage(messageId) ?: return@launch
            if (message.direction != MessageDirection.OUTGOING || message.status != MessageStatus.FAILED) {
                return@launch
            }
            repository.deleteMessage(messageId)
            sending.value = true
            lastFailure.value = null
            try {
                val outcome = send(recipient, message.body)
                if (outcome.state == SendState.FAILED) {
                    lastFailure.value = ThreadFailure.SEND
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.e(LogTags.STORE, "SMS resend failed unexpectedly", throwable)
                lastFailure.value = ThreadFailure.SEND
            } finally {
                sending.value = false
            }
        }
    }

    /**
     * Deletes a single message. The conversation disappears with its last
     * message, which is what the repository's delete path already enforces.
     */
    fun deleteMessage(messageId: Long) {
        viewModelScope.launch { repository.deleteMessage(messageId) }
    }

    private fun MessageEntity.toBubble() = MessageBubble(
        id = id,
        body = body,
        timestampLabel = TimeFormat.messageTimestamp(sentAt),
        isOutgoing = direction == MessageDirection.OUTGOING,
        status = status,
        sentAt = sentAt,
        deliveredAt = deliveredAt,
        errorCode = errorCode,
        simSlot = simSlot,
        partCount = partCount,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(
            repository: MessageRepository,
            conversationId: Long,
            recipient: String,
            isSmsRoleHeld: () -> Boolean,
            send: suspend (String, String) -> SendOutcome,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ThreadViewModel(
                    repository = repository,
                    conversationId = conversationId,
                    recipient = recipient,
                    isSmsRoleHeld = isSmsRoleHeld,
                    send = send,
                ) as T
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
