package com.anttech.smsapp.ui.compose

import com.anttech.smsapp.sms.send.ResultCode
import com.anttech.smsapp.sms.send.SendOutcome
import com.anttech.smsapp.sms.send.SendState
import com.anttech.smsapp.sms.send.SendValidator
import com.anttech.smsapp.testing.MainDispatcherRule
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
        autoSend: Boolean = false,
        send: suspend (String, String) -> SendOutcome = { _, _ ->
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        },
    ) = ComposeViewModel(recipient, draft, { roleHeld }, send, autoSend)

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
    fun `a picked contact keeps its name against the address`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "")

        model.addRecipient("+905530676691", "Ayşe Yılmaz")

        val state = model.uiState.value
        assertEquals("+905530676691", state.recipient)
        assertEquals(mapOf("+905530676691" to "Ayşe Yılmaz"), state.recipientNames)
    }

    @Test
    fun `re-adding a picked contact still records its name`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "+905530676691")

        model.addRecipient("+905530676691", "Ayşe Yılmaz")

        assertEquals("+905530676691", model.uiState.value.recipient)
        assertEquals(mapOf("+905530676691" to "Ayşe Yılmaz"), model.uiState.value.recipientNames)
    }

    @Test
    fun `a seeded recipient can be named for display`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "+15551234567")

        model.registerRecipientName("+15551234567", "John Doe")

        assertEquals(mapOf("+15551234567" to "John Doe"), model.uiState.value.recipientNames)
    }

    @Test
    fun `removing a recipient drops the address and its name`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "+15551234567")
        model.addRecipient("+905530676691", "Ayşe Yılmaz")

        model.removeRecipient("+15551234567")

        val state = model.uiState.value
        assertEquals("+905530676691", state.recipient)
        assertEquals(mapOf("+905530676691" to "Ayşe Yılmaz"), state.recipientNames)
    }

    @Test
    fun `committing the typed field turns it into a recipient`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "")

        model.onRecipientInputChanged("+905530676691")
        model.commitRecipientInput()

        assertEquals("+905530676691", model.uiState.value.recipient)
        assertEquals("", model.uiState.value.recipientInput)
    }

    @Test
    fun `a pasted run of separators becomes separate recipients`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "")

        model.onRecipientInputChanged("+15551234567, +905530676691;+15559876543")
        model.commitRecipientInput()

        assertEquals(
            "+15551234567, +905530676691, +15559876543",
            model.uiState.value.recipient,
        )
        assertEquals("", model.uiState.value.recipientInput)
    }

    @Test
    fun `typing a separator commits what came before it`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "")

        model.onRecipientInputChanged("+15551234567,")

        assertEquals("+15551234567", model.uiState.value.recipient)
        assertEquals("", model.uiState.value.recipientInput)
    }

    @Test
    fun `sending commits the typed field first`() = runTest(mainDispatcherRule.testDispatcher) {
        val sentTo = mutableListOf<String>()
        val model = viewModel(recipient = "", draft = "hello", send = { to, _ ->
            sentTo += to
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })

        model.onRecipientInputChanged("+15551234567")
        model.onSend()

        model.uiState.first { it.isFinished }
        assertEquals(listOf("+15551234567"), sentTo)
    }

    @Test
    fun `recipient addresses drop blank entries`() {
        assertEquals(
            listOf("+15551234567", "+905530676691"),
            recipientAddresses("+15551234567,  , +905530676691, "),
        )
        assertEquals(emptyList<String>(), recipientAddresses("   "))
    }

    @Test
    fun `editing the recipient clears a previous error`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(recipient = "12", draft = "hi")
        model.onSend()
        assertEquals(
            ComposeError.Invalid(SendValidator.Reason.UNPARSEABLE_RECIPIENT),
            model.uiState.first { it.error != null }.error,
        )

        model.onRecipientInputChanged("+15551234567")

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
    fun `an auto-send request sends on open`() = runTest(mainDispatcherRule.testDispatcher) {
        var sentBody: String? = null
        val model = viewModel(draft = "send me now", autoSend = true, send = { _, body ->
            sentBody = body
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })

        model.uiState.first { it.isFinished }
        assertEquals("send me now", sentBody)
    }

    @Test
    fun `an auto-send request without the role holds the draft back`() =
        runTest(mainDispatcherRule.testDispatcher) {
            var sendCount = 0
            val model = viewModel(
                draft = "not yet",
                roleHeld = false,
                autoSend = true,
                send = { _, _ ->
                    sendCount += 1
                    SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
                },
            )

            assertEquals(0, sendCount)
            assertEquals("not yet", model.uiState.first { it.draft == "not yet" }.draft)
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
