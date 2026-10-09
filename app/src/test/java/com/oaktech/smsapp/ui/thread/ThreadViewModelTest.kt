package com.oaktech.smsapp.ui.thread

import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.DownloadState
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.sms.receive.InboundMessage
import com.oaktech.smsapp.sms.send.ResultCode
import com.oaktech.smsapp.sms.send.SendOutcome
import com.oaktech.smsapp.sms.send.SendState
import com.oaktech.smsapp.testing.MainDispatcherRule
import com.oaktech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThreadViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository

    private val recipient = "+15550000001"

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(
            ApplicationProvider.getApplicationContext(),
            SmsDatabase::class.java,
        )
        repository = MessageRepository(database.conversationDao(), database.messageDao(), database.draftDao(), database.attachmentDao())
    }

    @After
    fun tearDown() = database.close()

    private suspend fun givenInbound(body: String, timestamp: Long): Long {
        repository.recordInbound(
            message = InboundMessage(
                address = recipient,
                body = body,
                timestampMillis = timestamp,
                parts = 1,
            ),
            partCount = 1,
        )
        return requireNotNull(repository.conversationIdFor(recipient))
    }

    private fun viewModel(
        conversationId: Long,
        roleHeld: () -> Boolean = { true },
        send: suspend (String, String?, String, List<AttachmentPart>) -> SendOutcome = { _, _, _, _ ->
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        },
        download: suspend (Long) -> Boolean = { false },
        threadRecipient: String = recipient,
    ) = ThreadViewModel(
        repository = repository,
        conversationId = conversationId,
        recipient = threadRecipient,
        isSmsRoleHeld = roleHeld,
        send = send,
        download = download,
    )

    private suspend fun ThreadViewModel.awaitLoaded() = uiState.first { it.messages.isNotEmpty() }

    @Test
    fun `opening a thread marks its conversation read`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("unread message", 1_000L)
        assertEquals(1, repository.observeConversations().first().single().unreadCount)

        viewModel(conversationId).awaitLoaded()

        assertEquals(0, repository.observeConversations().first().single().unreadCount)
    }

    @Test
    fun `muting a conversation persists and unmuting restores it`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId)
        model.awaitLoaded()
        assertFalse(model.uiState.value.isMuted)

        model.toggleMuted()

        assertTrue(model.uiState.first { it.isMuted }.isMuted)
        assertTrue(repository.observeConversations().first().single().isMuted)

        model.toggleMuted()

        assertFalse(model.uiState.first { !it.isMuted }.isMuted)
        assertFalse(repository.observeConversations().first().single().isMuted)
    }

    @Test
    fun `incoming messages render as delivered and not outgoing`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("incoming text", 1_000L)

        val bubble = viewModel(conversationId).awaitLoaded().messages.single()

        assertFalse(bubble.isOutgoing)
        assertEquals(MessageStatus.DELIVERED, bubble.status)
        assertEquals("incoming text", bubble.body)
    }

    @Test
    fun `sending a draft passes the trimmed body to the sender`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        var sentBody: String? = null
        var sentTo: String? = null
        val model = viewModel(conversationId, send = { to, _, body, _ ->
            sentTo = to
            sentBody = body
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })
        model.awaitLoaded()

        model.onDraftSubmitted("  hello there  ")

        assertEquals(recipient, sentTo)
        assertEquals("hello there", sentBody)
    }

    @Test
    fun `a blank draft is never sent`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        var sendCount = 0
        val model = viewModel(conversationId, send = { _, _, _, _ ->
            sendCount += 1
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })
        model.awaitLoaded()

        model.onDraftSubmitted("   ")
        model.onDraftSubmitted("")

        assertEquals(0, sendCount)
    }

    @Test
    fun `a failed send surfaces an error and can be dismissed`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId, send = { _, _, _, _ ->
            SendOutcome(SendState.FAILED, 1, 1, ResultCode.RADIO_OFF)
        })
        model.awaitLoaded()

        model.onDraftSubmitted("will fail")
        val failed = model.uiState.first { it.lastFailure != null }
        assertEquals(ThreadFailure.SEND, failed.lastFailure)

        model.dismissFailure()
        assertNull(model.uiState.first { it.lastFailure == null }.lastFailure)
    }

    @Test
    fun `losing the sms role hides the composer`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        var roleHeld = true
        val model = viewModel(conversationId, roleHeld = { roleHeld })

        assertTrue(model.awaitLoaded().canSend)

        roleHeld = false
        model.refreshRole()

        val degraded = model.uiState.first { it.isReadOnly }
        assertTrue(degraded.isReadOnly)
        assertFalse(degraded.canSend)
    }

    @Test
    fun `a thread with no resolved address cannot send`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        var sendCount = 0
        val model = viewModel(
            conversationId,
            threadRecipient = "",
            send = { _, _, _, _ ->
                sendCount += 1
                SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
            },
        )
        model.awaitLoaded()

        assertFalse(model.uiState.value.canSend)

        model.onDraftSubmitted("should not go out")

        assertEquals(0, sendCount)
    }

    @Test
    fun `a sent message appears in the thread with its status`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId, send = { address, _, body, _ ->
            val id = repository.recordOutgoing(address, body, 5_000L)
            repository.updateOutgoingStatus(id, MessageStatus.SENT)
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })
        model.awaitLoaded()

        model.onDraftSubmitted("outgoing text")

        val state = model.uiState.first { it.messages.any { bubble -> bubble.body == "outgoing text" } }
        val bubble = state.messages.last { it.body == "outgoing text" }
        assertTrue(bubble.isOutgoing)
        assertEquals(MessageStatus.SENT, bubble.status)
    }

    @Test
    fun `a failed message keeps its failed status in the thread`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId, send = { address, _, body, _ ->
            val id = repository.recordOutgoing(address, body, 5_000L)
            repository.updateOutgoingStatus(id, MessageStatus.FAILED)
            SendOutcome(SendState.FAILED, 1, 1, ResultCode.RADIO_OFF)
        })
        model.awaitLoaded()

        model.onDraftSubmitted("will not send")

        val state = model.uiState.first { it.messages.any { bubble -> bubble.body == "will not send" } }
        assertEquals(MessageStatus.FAILED, state.messages.last { it.body == "will not send" }.status)
    }

    @Test
    fun `deleting a message removes it from the thread`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("one", 1_000L)
        repository.recordOutgoing(recipient, "two", 2_000L)
        val model = viewModel(conversationId)
        model.awaitLoaded()
        val incoming = model.uiState.value.messages.first { !it.isOutgoing }

        model.deleteMessage(incoming.id)

        val remaining = model.uiState.first { it.messages.size == 1 }.messages
        assertEquals("two", remaining.single().body)
    }

    @Test
    fun `deleting the last message removes the conversation`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("only message", 1_000L)
        val model = viewModel(conversationId)
        model.awaitLoaded()

        model.deleteMessage(model.uiState.value.messages.single().id)

        assertTrue(
            "an empty conversation must not linger",
            repository.observeConversations().first().isEmpty(),
        )
    }

    @Test
    fun `a typed draft is persisted and restored`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId)
        model.awaitLoaded()

        assertNull("no draft was stored yet", model.draft.value)

        model.onDraftChanged("almost done")
        assertEquals("almost done", model.draft.first { it == "almost done" })

        val reopened = viewModel(conversationId)
        assertEquals("almost done", reopened.draft.first { it == "almost done" })
    }

    @Test
    fun `sending clears the stored draft`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId)
        model.awaitLoaded()
        model.onDraftChanged("unsent words")
        model.draft.first { it == "unsent words" }

        model.onDraftSubmitted("unsent words")

        assertNull(model.draft.first { it == null })
    }

    @Test
    fun `a blank draft clears a stored one`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId)
        model.awaitLoaded()
        model.onDraftChanged("words")

        model.onDraftChanged("   ")

        assertNull(model.draft.first { it == null })
    }

    @Test
    fun `resending a failed message composes the text again`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        var sentBody: String? = null
        val model = viewModel(conversationId, send = { address, _, body, _ ->
            sentBody = body
            val id = repository.recordOutgoing(address, body, 5_000L)
            repository.updateOutgoingStatus(id, MessageStatus.SENT)
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })
        model.awaitLoaded()
        val failedId = repository.recordOutgoing(recipient, "retry me", 4_000L)
        repository.updateOutgoingStatus(failedId, MessageStatus.FAILED)
        model.resendMessage(failedId)

        assertEquals("retry me", sentBody)
        val state = model.uiState.first { it.messages.any { it.body == "retry me" } }
        assertEquals(MessageStatus.SENT, state.messages.single { it.body == "retry me" }.status)
    }

    @Test
    fun `resending a non-failed message does nothing`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        var sendCount = 0
        val model = viewModel(conversationId, send = { _, _, _, _ ->
            sendCount += 1
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })
        model.awaitLoaded()
        val sentId = repository.recordOutgoing(recipient, "already out", 4_000L)
        repository.updateOutgoingStatus(sentId, MessageStatus.SENT)

        model.resendMessage(sentId)

        assertEquals(0, sendCount)
        assertEquals("already out", model.uiState.value.messages.single { it.id == sentId }.body)
    }

    @Test
    fun `queued attachments ride along on the send`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        var sentAttachments: List<AttachmentPart>? = null
        val model = viewModel(conversationId, send = { _, _, body, attachments ->
            sentAttachments = attachments
            val outcome = if (attachments.isEmpty()) {
                SendOutcome(SendState.FAILED, 1, 1, ResultCode.GENERIC_FAILURE)
            } else {
                SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
            }
            outcome
        })
        model.awaitLoaded()

        val part = AttachmentPart(fileName = "pic.jpg", contentType = "image/jpeg", filePath = "/tmp/pic.jpg", byteSize = 10)
        model.queueAttachment(part)
        model.onDraftSubmitted("with a photo")

        assertNotNull(sentAttachments)
        assertEquals(listOf(part.filePath), sentAttachments?.map { it.filePath })
        assertTrue(model.uiState.value.pendingAttachments.isEmpty())
    }

    @Test
    fun `a failed send keeps the attachment queue for retry`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId, send = { _, _, _, _ ->
            SendOutcome(SendState.FAILED, 1, 1, ResultCode.MMS_SEND_FAILED)
        })
        model.awaitLoaded()

        model.queueAttachment(
            AttachmentPart(fileName = "a.jpg", contentType = "image/jpeg", filePath = "/tmp/a.jpg", byteSize = 1),
        )
        model.onDraftSubmitted("retry me")

        assertEquals(1, model.uiState.value.pendingAttachments.size)
    }

    @Test
    fun `an attachment with no file is ignored`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId)
        model.awaitLoaded()

        model.queueAttachment(
            AttachmentPart(fileName = "x.jpg", contentType = "image/jpeg", filePath = null, byteSize = 1),
        )

        assertTrue(model.uiState.value.pendingAttachments.isEmpty())
    }

    @Test
    fun `picked attachments are restored when the thread reopens`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val part = AttachmentPart(fileName = "pic.jpg", contentType = "image/jpeg", filePath = "/tmp/pic.jpg", byteSize = 10)
        val model = viewModel(conversationId)
        model.awaitLoaded()
        model.queueAttachment(part)
        repository.observePendingAttachments(conversationId).first { it.isNotEmpty() }

        val reopened = viewModel(conversationId)
        reopened.awaitLoaded()

        assertEquals(
            listOf(part),
            reopened.uiState.first { it.pendingAttachments.isNotEmpty() }.pendingAttachments,
        )
    }

    @Test
    fun `a successful send clears the persisted attachment queue`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val model = viewModel(conversationId)
        model.awaitLoaded()
        val part = AttachmentPart(fileName = "pic.jpg", contentType = "image/jpeg", filePath = "/tmp/pic.jpg", byteSize = 10)
        model.queueAttachment(part)
        repository.observePendingAttachments(conversationId).first { it.isNotEmpty() }

        model.onDraftSubmitted("with photo")

        repository.observePendingAttachments(conversationId).first { it.isEmpty() }
        assertTrue(model.uiState.value.pendingAttachments.isEmpty())
    }

    @Test
    fun `an inbound mms without attachments can be downloaded`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        val messageId = repository.recordInboundMms(
            address = recipient,
            body = "multimedia",
            subject = null,
            timestampMillis = 1_000L,
            transactionId = "t1",
            attachments = emptyList(),
            downloadState = DownloadState.PENDING,
        )
        requireNotNull(messageId)
        var downloaded = 0
        val model = viewModel(conversationId, download = {
            downloaded += 1
            true
        })
        model.awaitLoaded()

        val bubble = model.uiState.first { it.messages.any { m -> m.id == messageId } }
            .messages.single { it.id == messageId }
        assertTrue(bubble.needsDownload)

        model.downloadAttachment(messageId)

        assertEquals(1, downloaded)
    }

    @Test
    fun `an inbound text-only mms has no download action`() = runTest(mainDispatcherRule.testDispatcher) {
        val messageId = repository.recordInboundMms(
            address = recipient,
            body = "text only",
            subject = null,
            timestampMillis = 1_000L,
            transactionId = "t2",
            attachments = emptyList(),
            downloadState = DownloadState.NONE,
        )
        requireNotNull(messageId)
        val model = viewModel(requireNotNull(repository.conversationIdFor(recipient)))
        model.awaitLoaded()

        val bubble = model.uiState.first { it.messages.any { m -> m.id == messageId } }
            .messages.single { it.id == messageId }
        assertFalse(bubble.needsDownload)
    }

    @Test
    fun `an mms resend re-attaches its stored parts`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenInbound("hi", 1_000L)
        var sentWith: List<AttachmentPart>? = null
        var sentSubject: String? = null
        val model = viewModel(conversationId, send = { _, subject, _, attachments ->
            sentSubject = subject
            sentWith = attachments
            repository.recordOutgoing(recipient, "resent", 5_000L).let { repository.updateOutgoingStatus(it, MessageStatus.SENT) }
            SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT)
        })
        model.awaitLoaded()

        val storedMessage = repository.recordOutgoingMms(
            recipients = listOf(recipient),
            subject = "subj",
            body = "resend me",
            sentAt = 4_000L,
            attachments = emptyList(),
        )
        repository.updateOutgoingStatus(storedMessage, MessageStatus.FAILED)
        repository.addAttachment(
            storedMessage,
            AttachmentPart(fileName = "k.jpg", contentType = "image/jpeg", filePath = "/tmp/k.jpg", byteSize = 5),
        )

        model.resendMessage(storedMessage)

        assertEquals(listOf("/tmp/k.jpg"), sentWith?.map { it.filePath })
        assertEquals("subj", sentSubject)
    }
}