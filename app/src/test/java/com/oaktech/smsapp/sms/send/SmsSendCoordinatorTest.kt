package com.oaktech.smsapp.sms.send

import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.testing.RecordingTelephonyMessageWriter
import com.oaktech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The send orchestration off the radio: what happens when the role is gone,
 * when validation rejects, and how the split an outgoing message was really
 * sent as lands in the row.
 */
@RunWith(RobolectricTestRunner::class)
class SmsSendCoordinatorTest {

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
    fun `a send without the default role fails before anything is recorded`() = runTest {
        val transport = RecordingTransport()
        val coordinator = SmsSendCoordinator(context, transport, isRoleHeld = { false })

        val outcome = coordinator.send("+15551234567", "never leaves")

        assertEquals(
            SendOutcome(SendState.FAILED, 0, 0, ResultCode.NO_DEFAULT_SMS_APP),
            outcome,
        )
        assertTrue(
            "no conversation was created for a send the radio would refuse",
            repository.observeConversations().first().isEmpty(),
        )
        assertTrue("nothing was handed to the transport", transport.sentParts.isEmpty())
        assertTrue("nothing was written to the provider", writer.insertedSent.isEmpty())
    }

    @Test
    fun `a rejected recipient fails as a plain failure, not a role loss`() = runTest {
        val transport = RecordingTransport()
        val coordinator = SmsSendCoordinator(context, transport, isRoleHeld = { true })

        val outcome = coordinator.send("   ", "hello")

        assertEquals(ResultCode.GENERIC_FAILURE, outcome.code)
        assertTrue(repository.observeConversations().first().isEmpty())
        assertTrue(transport.sentParts.isEmpty())
    }

    @Test
    fun `a send with the role hands every part to the transport and stores the split`() = runTest {
        val transport = RecordingTransport(partsPerMessage = 3)
        val coordinator = SmsSendCoordinator(context, transport, isRoleHeld = { true })

        val outcome = coordinator.send("+15551234567", "a long body")

        assertEquals(SendOutcome(SendState.SENT, 0, 3, ResultCode.SENT), outcome)
        assertEquals(listOf("a long body", "a long body", "a long body"), transport.sentParts)

        // The platform does not write sent rows for the default app, so this
        // one has to file its own or the system store never learns of the send.
        val sent = writer.insertedSent.single()
        assertEquals("+15551234567", sent.address)
        assertEquals("a long body", sent.body)

        // The row is the only thing that outlives the process, so it has to
        // carry the real part count for a later result to aggregate against.
        val row = repository.observeMessages(1L).first().single()
        assertEquals(MessageStatus.SENDING, row.status)
        assertEquals(3, row.partCount)
        assertEquals(100L, row.providerId)
    }

    /** Splits every body the same way, so the recorded parts are predictable. */
    private class RecordingTransport(private val partsPerMessage: Int = 1) : SmsTransport {
        val sentParts = mutableListOf<String>()

        override fun divideMessage(body: String): List<String> = List(partsPerMessage) { body }

        override fun sendMultipart(
            destination: String,
            parts: List<String>,
            sentIntents: List<PendingIntent>,
            deliveryIntent: PendingIntent,
        ) {
            sentParts += parts
        }

        override fun subscriptionId(): Int = 1
    }
}
