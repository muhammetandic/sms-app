package com.oaktech.smsapp.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.sms.send.SendOutcome
import com.oaktech.smsapp.sms.send.SendState
import com.oaktech.smsapp.sms.send.SendValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ComposeUiState(
    val recipient: String = "",
    val draft: String = "",
    val attachments: List<AttachmentPart> = emptyList(),
    val isSending: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null,
)

/**
 * Backs the compose screen reached from an `sms:` or `smsto:` intent.
 *
 * Separate from the thread screen because the recipient comes from another app
 * and has not been seen before: it is untrusted input, so it is validated the
 * same way a typed number is rather than being passed straight to the radio.
 *
 * Attachments ride along on the 3-argument send seam, so appending media here
 * routes the message through the same SMS↔MMS decision the thread composer
 * uses. The caller stores the bytes first (as a picked file lives outside our
 * sandbox) and hands in an [AttachmentPart] with a local file path.
 */
class ComposeViewModel(
    initialRecipient: String,
    initialDraft: String,
    private val isSmsRoleHeld: () -> Boolean,
    private val send: suspend (String, String, List<AttachmentPart>) -> SendOutcome,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        ComposeUiState(recipient = initialRecipient, draft = initialDraft),
    )
    val uiState: StateFlow<ComposeUiState> = mutableState.asStateFlow()

    fun onRecipientChanged(value: String) = mutableState.update {
        it.copy(recipient = value, error = null)
    }

    fun onDraftChanged(value: String) = mutableState.update {
        it.copy(draft = value, error = null)
    }

    fun queueAttachment(attachment: AttachmentPart) {
        if (attachment.filePath == null) return
        mutableState.update {
            if (it.attachments.any { part -> part.filePath == attachment.filePath }) it
            else it.copy(attachments = it.attachments + attachment)
        }
    }

    fun removeAttachment(filePath: String) = mutableState.update {
        it.copy(attachments = it.attachments.filterNot { part -> part.filePath == filePath })
    }

    fun onSend() {
        val state = mutableState.value
        if (state.isSending) return

        val recipient = state.recipient.trim()
        val body = state.draft.trim()
        val attachments = state.attachments

        val validation = SendValidator.validate(recipient, body, hasAttachments = attachments.isNotEmpty())
        if (validation is SendValidator.Result.Invalid) {
            mutableState.update { it.copy(error = validation.detail) }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(isSending = true, error = null) }
            try {
                val outcome = send(recipient, body, attachments)
                if (outcome.state == SendState.FAILED) {
                    mutableState.update { it.copy(error = "Message could not be sent") }
                } else {
                    // Returning to the caller is the expected end state for a
                    // SENDTO request; leaving the screen up looks like a hang.
                    mutableState.update {
                        it.copy(attachments = emptyList(), isFinished = true)
                    }
                }
            } finally {
                mutableState.update { it.copy(isSending = false) }
            }
        }
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    companion object {
        fun factory(
            initialRecipient: String,
            initialDraft: String,
            isSmsRoleHeld: () -> Boolean,
            send: suspend (String, String, List<AttachmentPart>) -> SendOutcome,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ComposeViewModel(initialRecipient, initialDraft, isSmsRoleHeld, send) as T
        }
    }
}