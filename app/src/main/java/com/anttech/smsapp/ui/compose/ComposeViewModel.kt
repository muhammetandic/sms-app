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
    /**
     * The recipients the message will go to, as comma-separated addresses. This
     * is the source of truth for sending; the screen draws each one as a chip.
     */
    val recipient: String = "",
    /**
     * The address being typed in the "To" field and not yet committed to a chip.
     * Kept apart from [recipient] so a half-typed number is never sent as-is.
     */
    val recipientInput: String = "",
    /**
     * Contact names for addresses in [recipient], keyed by the trimmed address.
     * The address is still what gets sent; this only lets a chip show a name.
     */
    val recipientNames: Map<String, String> = emptyMap(),
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

    fun onRecipientInputChanged(value: String) {
        // A separator is how the "To" field ends one recipient and starts the
        // next, so commit on it rather than keeping the comma in a chip.
        if (value.lastOrNull() == ',' || value.lastOrNull() == ';') {
            mutableState.update { it.copy(recipientInput = value, error = null) }
            commitRecipientInput()
        } else {
            mutableState.update { it.copy(recipientInput = value, error = null) }
        }
    }

    /**
     * Moves whatever is in the "To" field into the recipient list. A pasted run
     * like "111, 222" becomes two recipients rather than one bogus address.
     */
    fun commitRecipientInput() {
        val pending = mutableState.value.recipientInput
        for (part in pending.split(',', ';').map(String::trim).filter(String::isNotEmpty)) {
            addRecipient(part)
        }
        if (pending.isNotEmpty()) mutableState.update { it.copy(recipientInput = "") }
    }

    /**
     * Adds a picked contact without replacing recipients already selected. A
     * known [name] is remembered against the address so the chip can show the
     * contact rather than the raw number.
     */
    fun addRecipient(address: String, name: String? = null) {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        mutableState.update {
            val existing = recipientAddresses(it.recipient)
            val names = if (name != null) it.recipientNames + (trimmed to name) else it.recipientNames
            if (existing.any { current -> current.equals(trimmed, ignoreCase = true) }) {
                it.copy(recipientNames = names)
            } else {
                it.copy(
                    recipient = (existing + trimmed).joinToString(", "),
                    recipientNames = names,
                    error = null,
                )
            }
        }
    }

    /** Drops [address] from the recipient list, and its name with it. */
    fun removeRecipient(address: String) = mutableState.update {
        val trimmed = address.trim()
        it.copy(
            recipient = recipientAddresses(it.recipient)
                .filterNot { current -> current.equals(trimmed, ignoreCase = true) }
                .joinToString(", "),
            recipientNames = it.recipientNames - trimmed,
            error = null,
        )
    }

    /** Remembers the contact name behind [address] so its chip can display it. */
    fun registerRecipientName(address: String, name: String) = mutableState.update {
        it.copy(recipientNames = it.recipientNames + (address.trim() to name))
    }

    fun onDraftChanged(value: String) = mutableState.update {
        it.copy(draft = value, error = null)
    }

    fun onSend() {
        if (mutableState.value.isSending) return

        // The message must go to whatever is in the "To" field too, even if the
        // user hit Send without turning it into a chip first.
        commitRecipientInput()

        val state = mutableState.value
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

/**
 * Splits the stored comma-separated recipient string into the individual
 * addresses a chip is drawn for. Blank entries are dropped so a trailing comma
 * or a stray space never becomes an empty chip.
 */
internal fun recipientAddresses(recipient: String): List<String> =
    recipient.split(',').map(String::trim).filter(String::isNotEmpty)
