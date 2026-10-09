package com.oaktech.smsapp.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.AddressNormalizer
import com.oaktech.smsapp.data.local.AttachmentEntity
import com.oaktech.smsapp.data.local.DownloadState
import com.oaktech.smsapp.data.local.MessageDirection
import com.oaktech.smsapp.data.local.MessageEntity
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.sms.send.SendOutcome
import com.oaktech.smsapp.sms.send.SendState
import com.oaktech.smsapp.ui.TimeFormat
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
    val pendingAttachments: List<AttachmentPart> = emptyList(),
    val isMuted: Boolean = false,
)

/**
 * The transient failure a thread can surface. Kept as a value rather than a
 * sentence so the wording stays in the (translatable) resources owned by the
 * composable, and the two causes can read differently.
 */
enum class ThreadFailure { SEND, DOWNLOAD }

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
    val isMms: Boolean = false,
    val subject: String? = null,
    val attachments: List<AttachmentUi> = emptyList(),
    /** Media was advertised by the PDU but has not landed: offer the download. */
    val needsDownload: Boolean = false,
)

/** The parts of a bubble the composer and the details sheet show. */
data class AttachmentUi(
    val id: Long,
    val fileName: String,
    val contentType: String,
    val filePath: String?,
)

class ThreadViewModel(
    private val repository: MessageRepository,
    private val conversationId: Long,
    private val recipient: String,
    private val isSmsRoleHeld: () -> Boolean,
    private val send: suspend (recipient: String, subject: String?, body: String, attachments: List<AttachmentPart>) -> SendOutcome,
    private val download: suspend (messageId: Long) -> Boolean,
) : ViewModel() {

    private val roleHeld = MutableStateFlow(isSmsRoleHeld())
    private val sending = MutableStateFlow(false)
    private val lastFailure = MutableStateFlow<ThreadFailure?>(null)
    private val pendingAttachments = MutableStateFlow<List<AttachmentPart>>(emptyList())
    private val muted = MutableStateFlow(false)

    /** The stored draft for this conversation, null until the store has spoken. */
    val draft: StateFlow<String?> = repository.observeDraft(conversationId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

    private val messagesAndAttachments: Flow<Pair<List<MessageEntity>, Map<Long, List<AttachmentUi>>>> =
        combine(
            repository.observeMessages(conversationId),
            repository.observeConversationAttachments(conversationId),
        ) { messages, attachments ->
            messages to attachments.groupBy(
                keySelector = { it.messageId },
                valueTransform = { it.toUi() },
            )
        }

    val uiState: StateFlow<ThreadUiState> = combine(
        messagesAndAttachments,
        combine(roleHeld, sending, lastFailure, pendingAttachments, muted, ::Quintuple),
    ) { (messages, attachmentsByMessage), (roleIsHeld, isSending, failure, queue, mutedConversation) ->
        ThreadUiState(
            messages = messages.map { it.toBubble(attachmentsByMessage[it.id].orEmpty()) },
            isReadOnly = !roleIsHeld,
            isSending = isSending,
            lastFailure = failure,
            // A thread deep-linked from a notification can open before its address
            // resolves. Sending to an empty recipient is unrecoverable, so the
            // composer stays hidden until there is a real address to send to.
            canSend = roleIsHeld && recipient.isNotBlank(),
            pendingAttachments = queue,
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
            // A queue is meaningless until the thread has an address to send to,
            // but it is restored lazily here rather than in the state combine so
            // the composer sees it only when this thread actually owns it.
            pendingAttachments.value = repository.getPendingAttachments(conversationId)
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

    fun onDraftSubmitted(body: String, subject: String = "") {
        val trimmed = body.trim()
        if ((trimmed.isEmpty() && pendingAttachments.value.isEmpty() || sending.value || recipient.isBlank())) return

        viewModelScope.launch {
            // Once the text becomes a message there is nothing to restore, so the
            // draft is cleared as the send starts rather than after it resolves.
            // A failure keeps the attachment queue (and its row) so the user can
            // retry without re-picking files; only the text moves into the failed
            // message bubble.
            if (trimmed.isNotEmpty()) repository.saveDraft(conversationId, "")
            sending.value = true
            lastFailure.value = null
            try {
                val attachments = pendingAttachments.value
                val outcome = send(recipient, subject.trim().ifBlank { null }, trimmed, attachments)
                if (outcome.state == SendState.FAILED) {
                    lastFailure.value = ThreadFailure.SEND
                } else if (attachments.isNotEmpty()) {
                    // Sent and persisted: the composer queue has served its
                    // purpose. A failure keeps the queue so the user can retry
                    // without re-picking files.
                    pendingAttachments.value = emptyList()
                    repository.savePendingAttachments(conversationId, emptyList())
                }
            } finally {
                sending.value = false
            }
        }
    }

    fun queueAttachment(attachment: AttachmentPart) {
        if (attachment.filePath == null) return
        val updated = pendingAttachments.value + attachment
        pendingAttachments.value = updated
        viewModelScope.launch { repository.savePendingAttachments(conversationId, updated) }
    }

    fun removeAttachment(filePath: String) {
        val updated = pendingAttachments.value.filterNot { it.filePath == filePath }
        if (updated == pendingAttachments.value) return
        pendingAttachments.value = updated
        viewModelScope.launch { repository.savePendingAttachments(conversationId, updated) }
    }

    fun dismissFailure() {
        lastFailure.value = null
    }

    /** Fetches the missing attachment of an inbound MMS and records it. */
    fun downloadAttachment(messageId: Long) {
        val bubble = uiState.value.messages.find { it.id == messageId } ?: return
        if (!bubble.needsDownload || sending.value) return
        viewModelScope.launch {
            sending.value = true
            try {
                if (!download(messageId)) {
                    lastFailure.value = ThreadFailure.DOWNLOAD
                }
            } finally {
                sending.value = false
            }
        }
    }

    /**
     * Re-sends a failed outgoing message. The failed row is dropped and the text
     * is composed again through the normal send path, so a retry behaves exactly
     * like a fresh message: same validation, same status resolution, and the
     * failed bubble is replaced rather than corrected in place. An MMS retry
     * re-attaches its stored parts.
     */
    fun resendMessage(messageId: Long) {
        viewModelScope.launch {
            val message = repository.getMessage(messageId) ?: return@launch
            if (message.direction != MessageDirection.OUTGOING || message.status != MessageStatus.FAILED) {
                return@launch
            }
            val attachments = if (message.isMms) {
                repository.getAttachments(messageId).map {
                    AttachmentPart(
                        fileName = it.fileName,
                        contentType = it.contentType,
                        filePath = it.filePath,
                        byteSize = it.byteSize,
                    )
                }
            } else {
                emptyList()
            }
            repository.deleteMessage(messageId)
            val members = AddressNormalizer.membersOf(recipient)
            sending.value = true
            lastFailure.value = null
            try {
                val outcome = send(
                    if (attachments.isEmpty()) recipient else members.joinToString(", "),
                    // A retried MMS keeps its subject; an SMS has none to keep.
                    message.subject,
                    message.body,
                    attachments,
                )
                if (outcome.state == SendState.FAILED) {
                    lastFailure.value = ThreadFailure.SEND
                }
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

    private fun MessageEntity.toBubble(attachments: List<AttachmentUi>) = MessageBubble(
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
        isMms = isMms,
        subject = subject,
        attachments = attachments,
        needsDownload = downloadState == DownloadState.PENDING,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(
            repository: MessageRepository,
            conversationId: Long,
            recipient: String,
            isSmsRoleHeld: () -> Boolean,
            send: suspend (String, String?, String, List<AttachmentPart>) -> SendOutcome,
            download: suspend (Long) -> Boolean = { false },
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ThreadViewModel(
                    repository = repository,
                    conversationId = conversationId,
                    recipient = recipient,
                    isSmsRoleHeld = isSmsRoleHeld,
                    send = send,
                    download = download,
                ) as T
        }
    }
}

private fun AttachmentEntity.toUi() = AttachmentUi(
    id = id,
    fileName = fileName,
    contentType = contentType,
    filePath = filePath,
)

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)