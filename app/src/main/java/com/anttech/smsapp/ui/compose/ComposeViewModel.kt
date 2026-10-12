package com.anttech.smsapp.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anttech.smsapp.sms.send.SendOutcome
import com.anttech.smsapp.sms.send.SendState
import com.anttech.smsapp.sms.send.SendValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ComposeUiState(
    val recipient: String = "",
    val draft: String = "",
    val isSending: Boolean = false,
    val isFinished: Boolean = false,
    val error: ComposeError? = null,
)

/**
 * What the compose screen is reporting to the user. Modelled as a value so the
 * (translatable) wording lives in resources owned by the composable rather than
 * being baked into the ViewModel as an English sentence.
 */
sealed interface ComposeError {
    /** The message was validated but the send itself failed. */
    data object SendFailed : ComposeError

    /** The recipient or body was rejected by [SendValidator]. */
    data class Invalid(val reason: SendValidator.Reason) : ComposeError
}

/**
 * Backs the compose screen reached from an `sms:` or `smsto:` intent.
 *
 * Separate from the thread screen because the recipient comes from another app
 * and has not been seen before: it is untrusted input, so it is validated the
 * same way a typed number is rather than being passed straight to the radio.
 */
class ComposeViewModel(
    initialRecipient: String,
    initialDraft: String,
    private val isSmsRoleHeld: () -> Boolean,
    private val send: suspend (String, String) -> SendOutcome,
    autoSend: Boolean = false,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        ComposeUiState(recipient = initialRecipient, draft = initialDraft),
    )
    val uiState: StateFlow<ComposeUiState> = mutableState.asStateFlow()

    init {
        // The drafts list's "send" action opens this screen already sending. The
        // role is re-checked here for the same reason the UI disables the button
        // without it: a stale grant must not put a message on the radio.
        if (autoSend && isSmsRoleHeld()) onSend()
    }

    fun onRecipientChanged(value: String) = mutableState.update {
        it.copy(recipient = value, error = null)
    }

    /** Adds a picked contact without replacing recipients already selected. */
    fun addRecipient(address: String) {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        mutableState.update {
            val existing = it.recipient
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
            if (existing.any { current -> current.equals(trimmed, ignoreCase = true) }) {
                it
            } else {
                it.copy(recipient = (existing + trimmed).joinToString(", "), error = null)
            }
        }
    }

    fun onDraftChanged(value: String) = mutableState.update {
        it.copy(draft = value, error = null)
    }

    fun onSend() {
        val state = mutableState.value
        if (state.isSending) return

        val recipient = state.recipient.trim()
        val body = state.draft.trim()

        val validation = SendValidator.validate(recipient, body)
        if (validation is SendValidator.Result.Invalid) {
            mutableState.update { it.copy(error = ComposeError.Invalid(validation.reason)) }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(isSending = true, error = null) }
            try {
                // A SENDTO can name several recipients; SMS has no group form,
                // so each gets its own message. Report a failure if any leg
                // failed, but still finish: the successfully-sent legs must not
                // be retried as a draft.
                var anyFailed = false
                for (destination in SendValidator.recipients(recipient)) {
                    if (send(destination, body).state == SendState.FAILED) anyFailed = true
                }
                if (anyFailed) {
                    mutableState.update { it.copy(error = ComposeError.SendFailed) }
                } else {
                    // Returning to the caller is the expected end state for a
                    // SENDTO request; leaving the screen up looks like a hang.
                    mutableState.update {
                        // Clear the consumed body before navigation. The
                        // compose destination saves non-empty text on back,
                        // and a successful send must never become a draft.
                        it.copy(draft = "", isFinished = true)
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
            send: suspend (String, String) -> SendOutcome,
            autoSend: Boolean = false,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ComposeViewModel(initialRecipient, initialDraft, isSmsRoleHeld, send, autoSend) as T
        }
    }
}
