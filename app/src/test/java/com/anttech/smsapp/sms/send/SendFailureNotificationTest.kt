package com.anttech.smsapp.sms.send

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.notify.NotificationIds
import com.anttech.smsapp.testing.RecordingTelephonyMessageWriter
import com.anttech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * The failure alert for an outgoing message.
 *
 * The coordinator resolves a part's result on its own `Dispatchers.IO` scope,
 * not the test's, so the store and the shade are polled rather than advanced on
 * a virtual clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SendFailureNotificationTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    private lateinit var notifications: ShadowNotificationManager

    private val conversations get() = repository.conversations

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        database = inlineInMemoryDatabase(context, SmsDatabase::class.java)
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
            RecordingTelephonyMessageWriter(),
        )
        DataGraph.overrideForTest(context, repository)
        notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
    }

    @After
    fun tearDown() {
        DataGraph.overrideForTest(context, null)
        database.close()
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun alertIds(): Set<Int> = notifications.activeNotifications.map { it.id }.toSet()

    @Test
    fun `a part failure that resolves after the composer is gone posts an alert`() = runBlocking {
        val transport = RecordingTransport()
        val coordinator = SmsSendCoordinator(context, transport, isRoleHeld = { true })
        coordinator.send("+15551234567", "hello")
        val conversationId = conversations.conversationIdFor("+15551234567")!!
        val rowId = repository.observeMessages(conversationId).first().single().id

        // An unknown token stands in for a result that arrived after the process
        // was recreated: the coordinator rebuilds the tracker from the row's part
        // count, so this single report resolves the one-part message as failed.
        coordinator.onPartResult(
            token = 4242L,
            rowId = rowId,
            partIndex = 0,
            code = ResultCode.RADIO_OFF.platformCode,
        )

        awaitUntil("the failure alert") { NotificationIds.forFailedSend(conversationId) in alertIds() }
        assertEquals(MessageStatus.FAILED, statusOf(conversationId))
    }

    @Test
    fun `a successful part result posts no alert`() = runBlocking {
        val transport = RecordingTransport()
        val coordinator = SmsSendCoordinator(context, transport, isRoleHeld = { true })
        coordinator.send("+15551234567", "hello")
        val conversationId = conversations.conversationIdFor("+15551234567")!!
        val rowId = repository.observeMessages(conversationId).first().single().id

        coordinator.onPartResult(
            token = 4242L,
            rowId = rowId,
            partIndex = 0,
            code = ResultCode.SENT.platformCode,
        )

        awaitUntil("the row to be marked sent") { statusOf(conversationId) == MessageStatus.SENT }
        assertTrue("a delivered send must not alert", alertIds().isEmpty())
    }

    private fun statusOf(conversationId: Long): MessageStatus =
        runBlocking { repository.observeMessages(conversationId).first().single().status }

    private class RecordingTransport : SmsTransport {
        val sentParts = mutableListOf<String>()

        override fun canSend(): Boolean = true

        override fun divideMessage(body: String): List<String> = listOf(body)

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

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 10L
    }
}
