package com.anttech.smsapp.notify

import android.app.Application
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.sms.receive.InboundMessage
import com.anttech.smsapp.testing.DirectExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What each notification button does to the store.
 *
 * Driven through a real broadcast rather than by calling the handler directly,
 * because the part worth protecting is that a tap arrives carrying its
 * conversation and message, and that the asynchronous write completes.
 *
 * The receiver posts to a `Dispatchers.IO` scope of its own, so the store is
 * read with [runBlocking] and polled until the expected change appears. There is
 * no test scheduler to advance, since the scope is not the test's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationActionReceiverTest {

    private lateinit var context: Application
    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    // Conversation state, split out of the message store and shared by the
    // surfaces that work in whole conversations.
    private val conversations get() = repository.conversations

    private val address = "+15551234567"
    private var conversationId: Long = 0
    private var messageId: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        database = Room.inMemoryDatabaseBuilder(context, SmsDatabase::class.java)
            .setQueryExecutor(DirectExecutor)
            .setTransactionExecutor(DirectExecutor)
            .allowMainThreadQueries()
            .build()
        repository = MessageRepository(database.conversationDao(), database.messageDao(), database.draftDao())
        // The graph is a singleton, so the receiver under test has to be pointed
        // at this store rather than left to resolve the real on-device one.
        DataGraph.overrideForTest(context, repository)

        runBlocking {
            messageId = repository.recordInbound(
                InboundMessage(address, "buy now", 1_700_000_000_000L, parts = 1),
                partCount = 1,
            )!!
            conversationId = conversations.conversationIdFor(address)!!
        }
    }

    @After
    fun tearDown() {
        // The receiver works asynchronously on its own scope, so the store has to
        // outlive it: closing first would leave work in flight against a closed
        // database, and the resulting failure would land on whichever test class
        // happened to run next.
        NotificationActionReceiver.awaitIdle()
        DataGraph.overrideForTest(context, null)
        database.close()
    }

    @Test
    fun `mark read clears the unread count`() {
        assertEquals(1, unreadInInbox())

        send(NotificationActionReceiver.ACTION_MARK_READ) { unreadInInbox() == 0 }

        assertEquals(0, unreadInInbox())
    }

    @Test
    fun `mark spam moves the conversation out of the inbox`() {
        send(NotificationActionReceiver.ACTION_MARK_SPAM) { isSpam() }

        assertTrue("conversation should be gone from the inbox", inbox().isEmpty())
        assertTrue(isSpam())
    }

    @Test
    fun `mark spam keeps the message`() {
        send(NotificationActionReceiver.ACTION_MARK_SPAM) { isSpam() }

        assertEquals("folder is a view, not a delete", 1, spam().size)
        assertEquals(1, messages().size)
    }

    @Test
    fun `mark spam clears the unread count`() {
        send(NotificationActionReceiver.ACTION_MARK_SPAM) { isSpam() }

        assertEquals(0, spam().single().unreadCount)
    }

    @Test
    fun `delete removes the notified message`() {
        send(NotificationActionReceiver.ACTION_DELETE) { messages().isEmpty() }

        assertTrue(messages().isEmpty())
    }

    @Test
    fun `delete with an unknown message id is harmless`() {
        send(NotificationActionReceiver.ACTION_DELETE, message = 9_999L) { true }

        assertEquals(1, messages().size)
    }

    @Test
    fun `unknown action changes nothing`() {
        send("something_else", waitFor = { true })

        assertEquals(1, inbox().size)
        assertEquals(1, messages().size)
        assertFalse(isSpam())
    }

    @Test
    fun `missing extras are ignored`() {
        NotificationActionReceiver().onReceive(context, Intent())

        assertEquals(1, messages().size)
    }

    @Test
    fun `reply sends the typed text to the sender`() {
        var sentRecipient: String? = null
        var sentBody: String? = null

        val replyIntent = NotificationActionReceiver.intent(
            context,
            NotificationActionReceiver.ACTION_REPLY,
            conversationId,
            messageId,
            recipient = address,
        )
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_QUICK_REPLY).build()
        RemoteInput.addResultsToIntent(
            arrayOf(remoteInput),
            replyIntent,
            android.os.Bundle().apply {
                putCharSequence(NotificationActionReceiver.KEY_QUICK_REPLY, "on my way")
            },
        )

        NotificationActionReceiver(sendReply = { recipient, body ->
            sentRecipient = recipient
            sentBody = body
        }).onReceive(context, replyIntent)

        // The receiver sends from its own scope, so the captured values must be
        // polled until the write inside the coroutine has run.
        val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline && sentBody == null) {
            shadowOf(context.mainLooper).idle()
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        assertEquals(address, sentRecipient)
        assertEquals("on my way", sentBody)
        assertEquals("replying marks the alert read", 0, unreadInInbox())
    }

    @Test
    fun `reply with an empty reply text does not send`() {
        var sendCount = 0

        val replyIntent = NotificationActionReceiver.intent(
            context,
            NotificationActionReceiver.ACTION_REPLY,
            conversationId,
            messageId,
            recipient = address,
        )
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(NotificationActionReceiver.KEY_QUICK_REPLY).build()),
            replyIntent,
            android.os.Bundle().apply {
                putCharSequence(NotificationActionReceiver.KEY_QUICK_REPLY, "   ")
            },
        )

        NotificationActionReceiver(sendReply = { _, _ -> sendCount += 1 })
            .onReceive(context, replyIntent)
        NotificationActionReceiver.awaitIdle()

        assertEquals(0, sendCount)
    }

    private fun inbox() = runBlocking { conversations.observeConversations().first() }
    private fun spam() = runBlocking { conversations.observeSpamConversations().first() }
    private fun messages() = runBlocking { repository.observeMessages(conversationId).first() }
    private fun unreadInInbox() = inbox().single().unreadCount
    private fun isSpam() = runBlocking { conversations.isSpam(conversationId) }

    /**
     * Fires a button and waits for its write to land.
     *
     * The poll is bounded so a genuine failure fails the test rather than
     * hanging the suite. [NotificationActionReceiver.awaitIdle] is not used as the
     * condition because a broken action never becomes idle, so the observed row
     * state is what is waited on.
     */
    private fun send(
        action: String,
        conversation: Long = conversationId,
        message: Long = messageId,
        waitFor: () -> Boolean,
    ) {
        NotificationActionReceiver().onReceive(
            context,
            NotificationActionReceiver.intent(context, action, conversation, message),
        )

        val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(context.mainLooper).idle()
            if (waitFor()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("notification action '$action' did not settle within ${WRITE_TIMEOUT_MILLIS}ms")
    }

    private companion object {
        const val WRITE_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 10L
    }
}
