package com.oaktech.smsapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.sms.receive.InboundMessage
import com.oaktech.smsapp.testing.DirectExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The spam move at the repository level.
 *
 * Every case is about the same invariant: the inbox and the spam folder are two
 * views of one flag, so a conversation can never be in both or in neither, and
 * the messages themselves survive the move either way.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned: Robolectric cannot run against the target SDK, and 33 is the oldest
// level that has the runtime-permission behaviour the app guards on.
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SpamRepositoryTest {

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SmsDatabase::class.java,
        )
            .setQueryExecutor(DirectExecutor)
            .setTransactionExecutor(DirectExecutor)
            .allowMainThreadQueries()
            .build()
        repository = MessageRepository(database.conversationDao(), database.messageDao(), database.draftDao(), database.attachmentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun inbound(address: String, timestamp: Long) =
        InboundMessage(address = address, body = "hello", timestampMillis = timestamp, parts = 1)

    private suspend fun inbox() = repository.observeConversations().first()
    private suspend fun spam() = repository.observeSpamConversations().first()

    @Test
    fun `spam conversation leaves the inbox and enters the spam folder`() = runTest {
        val conversationId = repository.conversationIdFor("+15551234567")
        assertNull(conversationId)

        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = repository.conversationIdFor("+15551234567")!!
        assertEquals(1, inbox().size)

        repository.setConversationSpam(id, isSpam = true)

        assertTrue("conversation should be gone from the inbox", inbox().isEmpty())
        assertEquals(1, spam().size)
        assertEquals(id, spam().single().id)
    }

    @Test
    fun `restoring a conversation returns it to the inbox`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = repository.conversationIdFor("+15551234567")!!

        repository.setConversationSpam(id, isSpam = true)
        repository.setConversationSpam(id, isSpam = false)

        assertEquals(1, inbox().size)
        assertTrue("conversation should be gone from spam", spam().isEmpty())
        assertFalse(repository.isSpam(id))
    }

    @Test
    fun `moving to spam keeps the messages`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = repository.conversationIdFor("+15551234567")!!

        repository.setConversationSpam(id, isSpam = true)

        val messages = repository.observeMessages(id).first()
        assertEquals("marking spam must not delete messages", 1, messages.size)
        assertEquals("hello", messages.single().body)
    }

    @Test
    fun `marking as spam clears the unread count in the same write`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = repository.conversationIdFor("+15551234567")!!
        assertEquals(1, inbox().single().unreadCount)

        repository.setConversationSpam(id, isSpam = true)

        assertEquals(
            "a message the user just dismissed must not stay unread in spam",
            0,
            spam().single().unreadCount,
        )
    }

    @Test
    fun `spam does not block later messages from being stored`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = repository.conversationIdFor("+15551234567")!!
        repository.setConversationSpam(id, isSpam = true)

        repository.recordInbound(inbound("+15551234567", 1_700_000_001_000L), partCount = 1)

        assertEquals("folder is a view, not a block list", 2, repository.observeMessages(id).first().size)
    }

    @Test
    fun `isSpam reflects the flag`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = repository.conversationIdFor("+15551234567")!!

        assertFalse(repository.isSpam(id))
        repository.setConversationSpam(id, isSpam = true)
        assertTrue(repository.isSpam(id))
    }

    @Test
    fun `deleting a message leaves the rest of the conversation intact`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        repository.recordInbound(inbound("+15551234567", 1_700_000_001_000L), partCount = 1)
        val id = repository.conversationIdFor("+15551234567")!!
        val first = repository.observeMessages(id).first().first()

        repository.deleteMessage(first.id)

        val remaining = repository.observeMessages(id).first()
        assertEquals(1, remaining.size)
        assertTrue("the deleted row must be the one that went", remaining.none { it.id == first.id })
    }
}
