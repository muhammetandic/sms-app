package com.oaktech.smsapp.ui.compose

import com.oaktech.smsapp.data.AttachmentPart
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
        send: suspend (String, String, List<AttachmentPart>) -> SendOutcome = { _, _, _ ->
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
            val model = viewModel(recipient = "12", draft = "hi", send = { _, _, _ ->
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
        val model = viewModel(draft = "   ", send = { _, _, _ ->
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

        assertTrue(model.uiState.first { it.isFinished }.isFinished)
    }

    @Test
    fun `a failed send reports an error and does not finish`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(draft = "hello", send = { _, _, _ ->
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
        val model = viewModel(draft = "  hello  ", send = { _, body, _ ->
            sentBody = body
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })

        model.onSend()

        assertEquals("hello", sentBody)
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

    @Test
    fun `an attachment rides along on the send`() = runTest(mainDispatcherRule.testDispatcher) {
        var sentAttachments: List<AttachmentPart> = emptyList()
        val model = viewModel(draft = "look", send = { _, _, attachments ->
            sentAttachments = attachments
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })
        model.queueAttachment(attachmentPart("a.jpg"))

        model.onSend()

        assertEquals(1, sentAttachments.size)
        assertEquals("a.jpg", sentAttachments.single().fileName)
    }

    @Test
    fun `an attachment with no file path is ignored`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel()
        model.queueAttachment(
            AttachmentPart(fileName = "ghost.jpg", contentType = "image/jpeg", filePath = null, byteSize = 0),
        )

        assertTrue(model.uiState.first { it.attachments.isEmpty() }.attachments.isEmpty())
    }

    @Test
    fun `a duplicate attachment is not queued twice`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel()
        model.queueAttachment(attachmentPart("a.jpg"))
        model.queueAttachment(attachmentPart("a.jpg"))

        assertEquals(1, model.uiState.first { it.attachments.isNotEmpty() }.attachments.size)
    }

    @Test
    fun `removing an attachment drops it from the queue`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel()
        model.queueAttachment(attachmentPart("a.jpg"))
        model.queueAttachment(attachmentPart("b.jpg"))

        model.removeAttachment("/tmp/b.jpg")

        val state = model.uiState.first { it.attachments.size == 1 }
        assertEquals("a.jpg", state.attachments.single().fileName)
    }

    @Test
    fun `a failed send keeps the queued attachment`() = runTest(mainDispatcherRule.testDispatcher) {
        val model = viewModel(draft = "look", send = { _, _, _ ->
            SendOutcome(SendState.FAILED, 1, 1, ResultCode.RADIO_OFF)
        })
        model.queueAttachment(attachmentPart("a.jpg"))

        model.onSend()

        val state = model.uiState.first { it.error != null }
        assertEquals(1, state.attachments.size)
        assertFalse(state.isFinished)
    }

    private fun attachmentPart(fileName: String) = AttachmentPart(
        fileName = fileName,
        contentType = "image/jpeg",
        filePath = "/tmp/$fileName",
        byteSize = 10L,
    )
}
