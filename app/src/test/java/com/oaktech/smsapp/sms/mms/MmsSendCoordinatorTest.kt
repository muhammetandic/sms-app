package com.oaktech.smsapp.sms.mms

import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.sms.send.ResultCode
import com.oaktech.smsapp.sms.send.SendOutcome
import com.oaktech.smsapp.sms.send.SendState
import com.oaktech.smsapp.testing.RecordingTelephonyMessageWriter
import com.oaktech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MmsSendCoordinatorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    private lateinit var writer: RecordingTelephonyMessageWriter

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(context, SmsDatabase::class.java)
        writer = RecordingTelephonyMessageWriter()
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
            database.attachmentDao(),
            writer,
        )
        DataGraph.overrideForTest(context, repository)
    }

    @After
    fun tearDown() {
        DataGraph.overrideForTest(context, null)
        database.close()
    }

    @Test
    fun `a successful transaction posts the pdu and marks the message sent`() = runTest {
        val poster = FakePoster(200)
        val coordinator = MmsSendCoordinator(poster, FakeApn("http://mmsc.example"), context)

        val outcome = coordinator.send(
            recipients = listOf("+15550000001", "+15550000002"),
            subject = null,
            body = "group hello",
            attachments = emptyList(),
        )

        assertEquals(SendState.SENT, outcome.state)
        assertTrue("the PDU carried the body", poster.lastPdu()!!.isNotEmpty())
        val row = repository.getMessagesInConversation()
        assertEquals(MessageStatus.SENT, row.status)
        // The accepted send is written into the provider's sent table, and the
        // local row adopts the provider key so a later import does not double it.
        val mirrored = writer.insertedSentMms.single()
        assertEquals(listOf("+15550000001", "+15550000002"), mirrored.recipients)
        assertEquals("group hello", mirrored.parts.first { it.contentType == "text/plain" }.text)
        assertEquals(100L, row.providerId)
    }

    @Test
    fun `no mmsc resolves to a config failure`() = runTest {
        val coordinator = MmsSendCoordinator(FakePoster(200), FakeApn(null), context)

        val outcome = coordinator.send(
            recipients = listOf("+15550000001", "+15550000002"),
            subject = null,
            body = "group hello",
            attachments = emptyList(),
        )

        assertEquals(SendOutcome(SendState.FAILED, 0, 1, ResultCode.MMS_CONFIG_UNAVAILABLE), outcome)
        val row = repository.getMessagesInConversation()
        assertEquals(MessageStatus.FAILED, row.status)
        assertEquals(ResultCode.MMS_CONFIG_UNAVAILABLE.platformCode, row.errorCode)
    }

    @Test
    fun `an http error is a send failure`() = runTest {
        val poster = FakePoster(500)
        val coordinator = MmsSendCoordinator(poster, FakeApn("http://mmsc.example"), context)

        val outcome = coordinator.send(
            recipients = listOf("+15550000001", "+15550000002"),
            subject = null,
            body = "will fail",
            attachments = emptyList(),
        )

        assertEquals(ResultCode.MMS_SEND_FAILED, outcome.code)
        assertEquals(MessageStatus.FAILED, repository.getMessagesInConversation().status)
    }

    @Test
    fun `a transport throw is a network failure`() = runTest {
        val coordinator = MmsSendCoordinator(
            BrokenPoster(RuntimeException("no route to host")),
            FakeApn("http://mmsc.example"),
            context,
        )

        val outcome = coordinator.send(
            recipients = listOf("+15550000001"),
            subject = null,
            body = "will drop",
            attachments = emptyList(),
        )

        assertEquals(ResultCode.MMS_NETWORK_UNAVAILABLE, outcome.code)
        assertEquals(MessageStatus.FAILED, repository.getMessagesInConversation().status)
    }

    @Test
    fun `a blank recipient list is rejected before any network call`() = runTest {
        val poster = FakePoster(200)
        val coordinator = MmsSendCoordinator(poster, FakeApn("http://mmsc.example"), context)

        val outcome = coordinator.send(
            recipients = listOf("  "),
            subject = null,
            body = "",
            attachments = emptyList(),
        )

        assertEquals(ResultCode.GENERIC_FAILURE, outcome.code)
        assertNull(poster.lastPdu())
    }

    @Test
    fun `a send without the default role fails before anything is recorded`() = runTest {
        val poster = FakePoster(200)
        val coordinator = MmsSendCoordinator(
            poster = poster,
            mmscResolver = FakeApn("http://mmsc.example"),
            context = context,
            isRoleHeld = { false },
        )

        val outcome = coordinator.send(
            recipients = listOf("+15550000001"),
            subject = null,
            body = "never leaves",
            attachments = emptyList(),
        )

        assertEquals(SendOutcome(SendState.FAILED, 0, 1, ResultCode.NO_DEFAULT_SMS_APP), outcome)
        assertNull("nothing was posted", poster.lastPdu())
        assertTrue(
            "no row was persisted for a send the radio would refuse",
            repository.observeMessages(1L).first().isEmpty(),
        )
    }

    @Test
    fun `a retryable failure asks the host to schedule a retry`() = runTest {
        val scheduled = mutableListOf<Long>()
        val coordinator = MmsSendCoordinator(
            poster = BrokenPoster(RuntimeException("no route to host")),
            mmscResolver = FakeApn("http://mmsc.example"),
            context = context,
            onRetryableFailure = { scheduled += it },
        )

        coordinator.send(
            recipients = listOf("+15550000001"),
            subject = null,
            body = "will drop",
            attachments = emptyList(),
        )

        assertEquals(1, scheduled.size)
    }

    @Test
    fun `a config failure is not scheduled for a background retry`() = runTest {
        val scheduled = mutableListOf<Long>()
        val coordinator = MmsSendCoordinator(
            poster = FakePoster(200),
            mmscResolver = FakeApn(null),
            context = context,
            onRetryableFailure = { scheduled += it },
        )

        coordinator.send(
            recipients = listOf("+15550000001"),
            subject = null,
            body = "no mmsc",
            attachments = emptyList(),
        )

        assertTrue("a missing MMSC will not fix itself on a backoff", scheduled.isEmpty())
    }

    @Test
    fun `an unbuildable pdu is not scheduled for a background retry`() = runTest {
        val scheduled = mutableListOf<Long>()
        val coordinator = MmsSendCoordinator(
            poster = FakePoster(200),
            mmscResolver = FakeApn("http://mmsc.example"),
            context = context,
            onRetryableFailure = { scheduled += it },
        )

        coordinator.send(
            recipients = listOf("+15550000001"),
            subject = null,
            body = "text with a missing photo",
            attachments = listOf(
                AttachmentPart("missing.jpg", "image/jpeg", "/does/not/exist.jpg", 10),
            ),
        )

        assertTrue("a missing attachment file will not fix itself on a backoff", scheduled.isEmpty())
    }

    @Test
    fun `a retry reuses the failed row instead of composing a new one`() = runTest {
        val failing = MmsSendCoordinator(
            BrokenPoster(RuntimeException("down")),
            FakeApn("http://mmsc.example"),
            context,
        )
        failing.send(
            recipients = listOf("+15550000001"),
            subject = null,
            body = "retry me",
            attachments = emptyList(),
        )
        val failed = repository.getMessagesInConversation()
        assertEquals(MessageStatus.FAILED, failed.status)
        assertTrue("the worker flips the row back to SENDING", repository.markFailedMmsSending(failed.id))

        val coordinator = MmsSendCoordinator(FakePoster(200), FakeApn("http://mmsc.example"), context)
        val outcome = coordinator.resend(
            rowId = failed.id,
            recipients = listOf("+15550000001"),
            subject = null,
            body = "retry me",
            attachments = emptyList(),
            sentAt = failed.sentAt,
        )

        assertEquals(SendState.SENT, outcome.state)
        val rows = repository.observeMessages(1L).first()
        assertEquals("a retry corrects the row rather than adding a second", 1, rows.size)
        assertEquals(failed.id, rows.single().id)
        assertEquals(MessageStatus.SENT, rows.single().status)
        assertEquals(1, writer.insertedSentMms.size)
    }

    private class FakePoster(private val status: Int) : MmsPoster {
        private var lastPosted: ByteArray? = null

        override fun post(mmscUrl: String, pdu: ByteArray): Int {
            lastPosted = pdu
            return status
        }

        fun lastPdu(): ByteArray? = lastPosted
    }

    private class BrokenPoster(private val error: Throwable) : MmsPoster {
        override fun post(mmscUrl: String, pdu: ByteArray): Int = throw error
    }

    private class FakeApn(private val mmsc: String?) : MmscUrlResolver {
        override fun mmscUrl(): String? = mmsc
    }

    private suspend fun MessageRepository.getMessagesInConversation(): com.oaktech.smsapp.data.local.MessageEntity {
        val messages = observeMessages(1L).first()
        check(messages.size == 1) { "expected exactly one message row, got $messages" }
        return messages.first()
    }
}