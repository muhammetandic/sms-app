package com.oaktech.smsapp.sms.mms

import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.sms.send.ResultCode
import com.oaktech.smsapp.sms.send.SendOutcome
import com.oaktech.smsapp.sms.send.SendState
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

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(context, SmsDatabase::class.java)
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
            database.attachmentDao(),
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