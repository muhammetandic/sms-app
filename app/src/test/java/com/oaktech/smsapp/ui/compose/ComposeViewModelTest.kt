package com.oaktech.smsapp.ui.compose

import com.oaktech.smsapp.sms.send.ResultCode
import com.oaktech.smsapp.sms.send.SendOutcome
import com.oaktech.smsapp.sms.send.SendState
import com.oaktech.smsapp.sms.send.SendValidator
import com.oaktech.smsapp.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComposeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        recipient: String = "+15551234567",
        draft: String = "",
        roleHeld: Boolean = true,
        send: suspend (String, String) -> SendOutcome = { _, _ ->
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        },
    ) = ComposeViewModel(recipient, draft, { roleHeld }, send)

    @Test
    fun `the parsed intent pre-fills the composer`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "+15551234567", draft = "on my way")

        val state = model.uiState.value
        assertEquals("+15551234567", state.recipient)
        assertEquals("on my way", state.draft)
    }

    @Test
    fun `picked recipients append and duplicates are ignored`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "+15551234567")

        model.addRecipient("+905530676691")
        model.addRecipient(" +15551234567 ")

        assertEquals("+15551234567, +905530676691", model.uiState.value.recipient)
    }

    @Test
    fun `editing the recipient clears a previous error`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "12", draft = "hi")
        model.onSend()
        assertEquals(
            ComposeError.Invalid(SendValidator.Reason.UNPARSEABLE_RECIPIENT),
            model.uiState.first { it.error != null }.error,
        )

        model.onRecipientChanged("+15551234567")

        assertNull(model.uiState.first { it.error == null }.error)
    }


    @Test
    fun `an unparseable recipient from another app is rejected before sending`() =
        runTest(mainDispatcherRule.testDispatcher) {
            var sendCount = 0
            val model = viewModel(recipient = "12", draft = "hi", send = { _, _ ->
                sendCount += 1
                SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
            })

            model.onSend()

            val state = model.uiState.first { it.error != null }
            assertEquals(0, sendCount)
            assertEquals(
                ComposeError.Invalid(SendValidator.Reason.UNPARSEABLE_RECIPIENT),
                state.error,
            )
        }

    @Test
    fun `an empty draft is rejected`() = runTest(mainDispatcherRule.testDispatcher) {
        var sendCount = 0
        val model = viewModel(draft = "   ", send = { _, _ ->
            sendCount += 1
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })

        model.onSend()

        assertEquals(0, sendCount)
        assertEquals(
            ComposeError.Invalid(SendValidator.Reason.EMPTY_BODY),
            model.uiState.first { it.error != null }.error,
        )
    }

    @Test
    fun `a successful send finishes the composer`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(draft = "hello")

        model.onSend()

        val state = model.uiState.first { it.isFinished }
        assertTrue(state.isFinished)
        assertEquals("", state.draft)
    }

    @Test
    fun `a failed send reports an error and does not finish`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(draft = "hello", send = { _, _ ->
            SendOutcome(SendState.FAILED, 1, 1, ResultCode.RADIO_OFF)
        })

        model.onSend()

        val state = model.uiState.first { it.error != null }
        assertFalse(state.isFinished)
        assertEquals(ComposeError.SendFailed, state.error)
    }

    @Test
    fun `the trimmed draft is what gets sent`() = runTest(mainDispatcherRule.testDispatcher) {
        var sentBody: String? = null
        val model = viewModel(draft = "  hello  ", send = { _, body ->
            sentBody = body
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })

        model.onSend()

        assertEquals("hello", sentBody)
    }

    @Test
    fun `a multiple recipient intent sends one message per recipient`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val sentTo = mutableListOf<String>()
            val model = viewModel(
                recipient = "+15551234567, +15559876543",
                draft = "hello",
                send = { to, _ ->
                    sentTo += to
                    SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
                },
            )

            model.onSend()

            model.uiState.first { it.isFinished }
            assertEquals(listOf("+15551234567", "+15559876543"), sentTo)
        }

    @Test
    fun `the error can be dismissed`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "12", draft = "hi")
        model.onSend()
        model.uiState.first { it.error != null }

        model.dismissError()

        assertNull(model.uiState.first { it.error == null }.error)
    }

    @Test
    fun `editing the draft clears a previous error`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "12", draft = "hi")
        model.onSend()
        model.uiState.first { it.error != null }

        model.onDraftChanged("something")

        assertNull(model.uiState.first { it.error == null }.error)
    }
}
