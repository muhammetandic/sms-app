package com.anttech.smsapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.sms.receive.InboundMessage
import com.anttech.smsapp.testing.DirectExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The archive at the repository level.
 *
 * Archiving is a second hidden-but-kept state beside spam, so the
 * cases mirror the spam ones: the archive and the inbox are two views
 * of one flag, and a conversation can never be in both or in neither.
 * The difference is what a new message does: an archived conversation
 * is ordinary mail, so an arrival returns it to the inbox rather than
 * staying quiet.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned: Robolectric cannot run against the target SDK, and 33 is the
// oldest level that has the runtime-permission behaviour the app guards on.
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveRepositoryTest {

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    private val conversations get() = repository.conversations

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
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun inbound(address: String, timestamp: Long) =
        InboundMessage(address = address, body = "hello", timestampMillis = timestamp, parts = 1)

    private suspend fun inbox() = conversations.observeConversations().first()
    private suspend fun archive() = conversations.observeArchivedConversations().first()

    @Test
    fun `archiving a conversation leaves the inbox and enters the archive`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = conversations.conversationIdFor("+15551234567")!!
        assertEquals(1, inbox().size)

        conversations.setConversationsArchived(setOf(id), isArchived = true)

        assertTrue("conversation should be gone from the inbox", inbox().isEmpty())
        assertEquals(1, archive().size)
        assertEquals(id, archive().single().id)
    }

    @Test
    fun `restoring an archived conversation returns it to the inbox`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = conversations.conversationIdFor("+15551234567")!!

        conversations.setConversationsArchived(setOf(id), isArchived = true)
        conversations.setConversationsArchived(setOf(id), isArchived = false)

        assertEquals(1, inbox().size)
        assertTrue("conversation should be gone from the archive", archive().isEmpty())
    }

    @Test
    fun `archiving keeps the messages`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = conversations.conversationIdFor("+15551234567")!!

        conversations.setConversationsArchived(setOf(id), isArchived = true)

        val messages = repository.observeMessages(id).first()
        assertEquals("archiving must not delete messages", 1, messages.size)
        assertEquals("hello", messages.single().body)
    }

    @Test
    fun `archiving keeps the unread count`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = conversations.conversationIdFor("+15551234567")!!
        assertEquals(1, inbox().single().unreadCount)

        conversations.setConversationsArchived(setOf(id), isArchived = true)

        assertEquals(
            "a hidden conversation keeps the badge it earned, so restoring it shows the unread",
            1,
            archive().single().unreadCount,
        )
    }

    @Test
    fun `a new message returns an archived conversation to the inbox`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = conversations.conversationIdFor("+15551234567")!!
        conversations.setConversationsArchived(setOf(id), isArchived = true)

        repository.recordInbound(inbound("+15551234567", 1_700_000_001_000L), partCount = 1)

        assertEquals("an archived thread is ordinary mail, so a reply resurfaces it", 1, inbox().size)
        assertEquals(id, inbox().single().id)
        assertTrue("the reply must not stay hidden", archive().isEmpty())
    }

    @Test
    fun `spam wins over archive`() = runTest {
        repository.recordInbound(inbound("+15551234567", 1_700_000_000_000L), partCount = 1)
        val id = conversations.conversationIdFor("+15551234567")!!

        conversations.setConversationsArchived(setOf(id), isArchived = true)
        conversations.setConversationSpam(id, isSpam = true)

        assertTrue("a spam row is not archived mail", archive().isEmpty())
        assertEquals(1, conversations.observeSpamConversations().first().size)
    }
}
