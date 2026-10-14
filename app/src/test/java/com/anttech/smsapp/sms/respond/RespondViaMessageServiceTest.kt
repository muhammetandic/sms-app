package com.anttech.smsapp.sms.respond

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.sms.send.SendGraph
import com.anttech.smsapp.sms.send.SmsSendCoordinator
import com.anttech.smsapp.sms.send.SmsTransport
import com.anttech.smsapp.testing.MainDispatcherRule
import com.anttech.smsapp.testing.RecordingTelephonyMessageWriter
import com.anttech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * RESPOND_VIA_MESSAGE, the reply path used by contact cards and the call screen.
 *
 * The service reaches its coordinator only through [SendGraph], so the test
 * installs one over a recording transport and drives the service with real
 * intents: what matters is which intents produce a send and which are declined
 * before the radio is ever touched.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RespondViaMessageServiceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    private lateinit var transport: RecordingTransport

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(context, SmsDatabase::class.java)
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
            RecordingTelephonyMessageWriter(),
        )
        DataGraph.overrideForTest(context, repository)
        transport = RecordingTransport()
        SendGraph.overrideForTest(
            SmsSendCoordinator(context, transport, isRoleHeld = { true }),
        )
    }

    @After
    fun tearDown() {
        SendGraph.overrideForTest(null)
        DataGraph.overrideForTest(context, null)
        database.close()
    }

    /** Starts the service with [intent] and drains the reply coroutine. */
    private fun TestScope.start(intent: Intent) {
        val controller = Robolectric.buildService(RespondViaMessageService::class.java).create()
        try {
            controller.get().onStartCommand(intent, 0, START_ID)
            advanceUntilIdle()
        } finally {
            controller.destroy()
        }
    }

    private fun replyIntent(uri: String, text: String? = null) =
        Intent(Intent.ACTION_SENDTO, Uri.parse(uri)).apply {
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }

    @Test
    fun `a valid reply is sent to its recipient`() = runTest(mainDispatcherRule.testDispatcher) {
        start(replyIntent("smsto:+15551234567", text = "on my way"))

        assertEquals(listOf("+15551234567"), transport.destinations)
        assertEquals(listOf("on my way"), transport.bodies)
    }

    @Test
    fun `a body carried in the uri is used when no extra is present`() =
        runTest(mainDispatcherRule.testDispatcher) {
            start(replyIntent("smsto:+15551234567?body=from%20the%20uri"))

            assertEquals(listOf("+15551234567"), transport.destinations)
            assertEquals(listOf("from the uri"), transport.bodies)
        }

    @Test
    fun `a reply is sent to every named recipient`() = runTest(mainDispatcherRule.testDispatcher) {
        start(replyIntent("smsto:+15551234567,+15557654321", text = "group reply"))

        assertEquals(listOf("+15551234567", "+15557654321"), transport.destinations)
    }

    @Test
    fun `an intent without a recipient is declined before sending`() =
        runTest(mainDispatcherRule.testDispatcher) {
            start(replyIntent("smsto:", text = "nobody to send to"))

            assertTrue("no recipient, no send", transport.destinations.isEmpty())
        }

    @Test
    fun `an intent without a body is declined before sending`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The caller shows its own compose UI and no text was typed yet, so
            // there is nothing to send.
            start(replyIntent("smsto:+15551234567"))

            assertTrue("empty body, no send", transport.destinations.isEmpty())
        }

    private class RecordingTransport : SmsTransport {
        val destinations = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        override fun canSend(): Boolean = true

        override fun divideMessage(body: String): List<String> = listOf(body)

        override fun sendMultipart(
            destination: String,
            parts: List<String>,
            sentIntents: List<PendingIntent>,
            deliveryIntent: PendingIntent,
        ) {
            destinations += destination
            bodies += parts
        }

        override fun subscriptionId(): Int = 1
    }

    private companion object {
        const val START_ID = 1
    }
}
