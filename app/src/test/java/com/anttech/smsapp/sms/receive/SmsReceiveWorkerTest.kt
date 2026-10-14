package com.anttech.smsapp.sms.receive

import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.testing.RecordingTelephonyMessageWriter
import com.anttech.smsapp.testing.inlineInMemoryDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The receive path end to end: store locally, then write the same message into
 * the provider inbox. The second half is the default-SMS-app contract - a
 * message that only exists in Room is invisible to every other reader of the
 * device's messages.
 */
@RunWith(RobolectricTestRunner::class)
class SmsReceiveWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    // Conversation state, split out of the message store and shared by the
    // surfaces that work in whole conversations.
    private val conversations get() = repository.conversations
    private lateinit var writer: RecordingTelephonyMessageWriter

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(context, SmsDatabase::class.java)
        writer = RecordingTelephonyMessageWriter()
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
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
    fun `an incoming sms is stored locally and written to the provider inbox`() {
        val message = SmsPart(originatingAddress = "+15551234567", messageBody = "hello", timestampMillis = 1L)

        awaitReceive(listOf(message))

        val expected = InboundMessage("+15551234567", "hello", 1L, parts = 1)
        assertEquals(listOf(expected), writer.insertedInbound)

        val stored = runBlocking {
            repository.observeMessages(conversations.observeConversations().first().single().id).first().single()
        }
        assertEquals(100L, stored.providerId)
        assertEquals("hello", stored.body)
    }

    @Test
    fun `a duplicate part is not written to the provider twice`() {
        val message = SmsPart(originatingAddress = "+15551234567", messageBody = "hello", timestampMillis = 1L)

        awaitReceive(listOf(message))
        awaitReceive(listOf(message))

        assertEquals(1, writer.insertedInbound.size)
    }

    /** Runs one receive batch synchronously. */
    private fun awaitReceive(parts: List<SmsPart>) = kotlinx.coroutines.runBlocking {
        SmsReceiveWorker.handle(context, parts)
    }
}
