package com.anttech.smsapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.sms.receive.InboundMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MessageRepositoryTest {

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SmsDatabase::class.java,
        ).build()
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

    private fun inbound(
        address: String = "+15551234567",
        body: String = "hello",
        timestamp: Long = 1_700_000_000_000L,
    ) = InboundMessage(address, body, timestamp, parts = 1)

    @Test
    fun `inbound message creates conversation and message`() = runTest {
        val rowId = repository.recordInbound(inbound(), partCount = 1)

        assertNotNull(rowId)
        val conversations = repository.observeConversations().first()
        assertEquals(1, conversations.size)
        assertEquals("hello", conversations.single().snippet)
    }

    @Test
    fun `muting a conversation persists independently of the spam flag`() = runTest {
        repository.recordInbound(inbound(), partCount = 1)
        val conversationId = requireNotNull(repository.conversationIdFor(inbound().address))
        assertFalse(repository.isMuted(conversationId))

        repository.setConversationMuted(conversationId, true)
        assertTrue(repository.isMuted(conversationId))
        assertFalse(repository.isSpam(conversationId))
        assertTrue(repository.observeConversations().first().single().isMuted)

        repository.setConversationMuted(conversationId, false)
        assertFalse(repository.isMuted(conversationId))
    }

    @Test
    fun `one contact can have multiple standalone drafts`() = runTest {
        repository.saveDraftForRecipient("+15551234567", "first draft")
        repository.saveDraftForRecipient("+15551234567", "second draft")

        val drafts = repository.observeDraftConversations().first()
        assertEquals(2, drafts.size)
        assertEquals(setOf("first draft", "second draft"), drafts.map { it.body }.toSet())
        assertNotEquals(drafts[0].draftId, drafts[1].draftId)
    }

    @Test
    fun `a draft to a new recipient stays out of the inbox`() = runTest {
        repository.saveDraftForRecipient("+15551234567", "not sent yet")

        // The draft belongs on the drafts screen; an inbox row with no message
        // and no snippet would be a blank entry the user cannot act on.
        assertTrue(repository.observeConversations().first().isEmpty())
        assertEquals("not sent yet", repository.observeDraftConversations().first().single().body)

        // Sending it gives the conversation a message, so it joins the inbox.
        repository.recordOutgoing("+15551234567", "not sent yet", 1_000L)
        assertEquals(1, repository.observeConversations().first().size)
    }

    @Test
    fun `duplicate inbound message is ignored`() = runTest {
        repository.recordInbound(inbound(), partCount = 1)
        val second = repository.recordInbound(inbound(), partCount = 1)

        assertNull(second)
        assertEquals(1, repository.observeConversations().first().size)
    }

    @Test
    fun `duplicate does not bump unread count`() = runTest {
        repository.recordInbound(inbound(), partCount = 1)
        repository.recordInbound(inbound(), partCount = 1)

        assertEquals(1, repository.observeConversations().first().single().unreadCount)
    }

    @Test
    fun `different body from same sender creates a second message`() = runTest {
        repository.recordInbound(inbound(body = "first"), partCount = 1)
        repository.recordInbound(inbound(body = "second"), partCount = 1)

        val conversation = repository.observeConversations().first().single()
        assertEquals(2, conversation.unreadCount)
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    @Test
    fun `messages from same sender share one conversation`() = runTest {
        repository.recordInbound(inbound(body = "a", timestamp = 1L), partCount = 1)
        repository.recordInbound(inbound(body = "b", timestamp = 2L), partCount = 1)

        val conversations = repository.observeConversations().first()
        assertEquals(1, conversations.size)
        assertEquals(2, repository.observeMessages(conversations.single().id).first().size)
    }

    @Test
    fun `formatting variants of one number share a conversation`() = runTest {
        repository.recordInbound(inbound(address = "+1 (555) 123-4567", body = "a"), partCount = 1)
        repository.recordInbound(inbound(address = "+15551234567", body = "b"), partCount = 1)

        assertEquals(1, repository.observeConversations().first().size)
    }

    @Test
    fun `different senders get separate conversations`() = runTest {
        repository.recordInbound(inbound(address = "+15551111111"), partCount = 1)
        repository.recordInbound(inbound(address = "+15552222222"), partCount = 1)

        assertEquals(2, repository.observeConversations().first().size)
    }

    @Test
    fun `alphanumeric sender gets its own conversation`() = runTest {
        repository.recordInbound(inbound(address = "Google", body = "code 1234"), partCount = 1)

        val conversation = repository.observeConversations().first().single()
        assertEquals("google", conversation.addressKey)
        assertEquals("Google", conversation.displayAddress)
    }

    @Test
    fun `conversation ordering follows most recent message`() = runTest {
        repository.recordInbound(inbound(address = "+15551111111", timestamp = 100L), partCount = 1)
        repository.recordInbound(inbound(address = "+15552222222", timestamp = 200L), partCount = 1)

        val conversations = repository.observeConversations().first()
        assertEquals("+15552222222", conversations.first().addressKey)
    }

    @Test
    fun `marking conversation read clears unread count`() = runTest {
        repository.recordInbound(inbound(), partCount = 1)
        val conversation = repository.observeConversations().first().single()

        repository.markConversationRead(conversation.id)

        assertEquals(0, repository.observeConversations().first().single().unreadCount)
    }

    @Test
    fun `marking a conversation unread restores the unread count`() = runTest {
        repository.recordInbound(inbound(), partCount = 1)
        val conversation = repository.observeConversations().first().single()
        repository.markConversationRead(conversation.id)

        repository.setConversationsUnread(setOf(conversation.id))

        assertEquals(1, repository.observeConversations().first().single().unreadCount)
    }

    @Test
    fun `outgoing message is stored as sending`() = runTest {
        val id = repository.recordOutgoing("+15551234567", "reply", 1_700_000_000_000L)

        val message = database.messageDao().getById(id)
        assertEquals(MessageDirection.OUTGOING, message?.direction)
        assertEquals(MessageStatus.SENDING, message?.status)
    }

    @Test
    fun `outgoing message can share the inbound conversation`() = runTest {
        repository.recordInbound(inbound(address = "+15551234567", timestamp = 1L), partCount = 1)
        repository.recordOutgoing("+15551234567", "reply", 2L)

        assertEquals(1, repository.observeConversations().first().size)
    }

    @Test
    fun `identical outgoing messages are both stored`() = runTest {
        repository.recordOutgoing("+15551234567", "hi", 1L)
        repository.recordOutgoing("+15551234567", "hi", 1L)

        val conversation = repository.observeConversations().first().single()
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    @Test
    fun `outgoing status updates to sent`() = runTest {
        val id = repository.recordOutgoing("+15551234567", "hi", 1L)

        repository.updateOutgoingStatus(id, MessageStatus.SENT, providerId = 42L)

        val message = database.messageDao().getById(id)
        assertEquals(MessageStatus.SENT, message?.status)
        assertEquals(42L, message?.providerId)
    }

    @Test
    fun `stuck outgoing messages are failed at startup`() = runTest {
        repository.recordOutgoing("+15551111111", "a", 1L)
        repository.recordOutgoing("+15552222222", "b", 2L)
        repository.updateOutgoingStatus(1L, MessageStatus.SENT)

        repository.resolveStuckOutgoing()

        val statuses = repository.observeConversations().first()
            .flatMap { repository.observeMessages(it.id).first() }
            .map { it.status }
        assertTrue(statuses.all { it != MessageStatus.SENDING })
    }

    @Test
    fun `messages within a conversation are ordered oldest first`() = runTest {
        repository.recordInbound(inbound(body = "newer", timestamp = 200L), partCount = 1)
        repository.recordInbound(inbound(body = "older", timestamp = 100L), partCount = 1)

        val conversation = repository.observeConversations().first().single()
        val bodies = repository.observeMessages(conversation.id).first().map { it.body }

        assertEquals(listOf("older", "newer"), bodies)
    }

    @Test
    fun `snippet reflects the latest message`() = runTest {
        repository.recordInbound(inbound(body = "old text", timestamp = 100L), partCount = 1)
        repository.recordInbound(inbound(body = "new text", timestamp = 200L), partCount = 1)

        assertEquals("new text", repository.observeConversations().first().single().snippet)
    }

    @Test
    fun `part count is retained`() = runTest {
        repository.recordInbound(inbound(), partCount = 4)

        val conversation = repository.observeConversations().first().single()
        val message = repository.observeMessages(conversation.id).first().single()

        assertEquals(4, message.partCount)
    }

    /**
     * Deleting an unread message must take its unread badge with it.
     *
     * Found on the emulator: removing a message from a conversation that had
     * two unread left the row on `1`, so the inbox kept advertising a message
     * that was no longer there.
     */
    @Test
    fun `deleting an unread message drops one from the unread count`() = runTest {
        repository.recordInbound(inbound(body = "one", timestamp = 100L), partCount = 1)
        repository.recordInbound(inbound(body = "two", timestamp = 200L), partCount = 1)
        val conversation = repository.observeConversations().first().single()
        assertEquals(2, conversation.unreadCount)

        val first = repository.observeMessages(conversation.id).first().first()
        repository.deleteMessage(first.id)

        assertEquals(1, repository.observeConversations().first().single().unreadCount)
    }

    @Test
    fun `deleting the only message removes the conversation`() = runTest {
        repository.recordInbound(inbound(body = "only", timestamp = 100L), partCount = 1)
        val conversation = repository.observeConversations().first().single()
        val message = repository.observeMessages(conversation.id).first().single()

        repository.deleteMessage(message.id)

        assertTrue(
            "an empty conversation must not linger in the inbox",
            repository.observeConversations().first().isEmpty(),
        )
    }

    @Test
    fun `deleting a conversation removes it and its messages`() = runTest {
        repository.recordInbound(inbound(body = "one", timestamp = 100L), partCount = 1)
        repository.recordInbound(inbound(body = "two", timestamp = 200L), partCount = 1)
        val conversation = repository.observeConversations().first().single()

        repository.deleteConversation(conversation.id)

        assertTrue(repository.observeConversations().first().isEmpty())
        assertTrue(
            "deleting the row must take every message with it",
            repository.observeMessages(conversation.id).first().isEmpty(),
        )
    }

    @Test
    fun `deleting one conversation leaves the others alone`() = runTest {
        repository.recordInbound(inbound(address = "+15551111111", timestamp = 1L), partCount = 1)
        repository.recordInbound(inbound(address = "+15552222222", timestamp = 2L), partCount = 1)
        val conversations = repository.observeConversations().first()
        val keep = conversations.first { it.displayAddress == "+15552222222" }
        val drop = conversations.first { it.displayAddress == "+15551111111" }

        repository.deleteConversation(drop.id)

        assertEquals(listOf(keep.id), repository.observeConversations().first().map { it.id })
    }

    @Test
    fun `deleting a spam conversation removes it from the spam folder`() = runTest {
        repository.recordInbound(inbound(body = "spam", timestamp = 100L), partCount = 1)
        val conversation = repository.observeConversations().first().single()
        repository.setConversationSpam(conversation.id, isSpam = true)

        repository.deleteConversation(conversation.id)

        assertTrue(repository.observeSpamConversations().first().isEmpty())
    }

    @Test
    fun `deleting an outgoing message leaves the unread count alone`() = runTest {
        repository.recordInbound(inbound(body = "in", timestamp = 100L), partCount = 1)
        repository.markConversationRead(repository.conversationIdFor("+15551234567")!!)
        repository.recordOutgoing("+15551234567", "reply", 200L)
        val conversationId = repository.conversationIdFor("+15551234567")!!
        val outgoing = repository.observeMessages(conversationId).first().single {
            it.direction == MessageDirection.OUTGOING
        }

        repository.deleteMessage(outgoing.id)

        assertEquals(
            "an outgoing message was never unread",
            0,
            repository.observeConversations().first().single().unreadCount,
        )
    }

    @Test
    fun `pinning a conversation keeps it above a newer one`() = runTest {
        repository.recordInbound(inbound(address = "+15551111111", timestamp = 100L), partCount = 1)
        repository.recordInbound(inbound(address = "+15552222222", timestamp = 200L), partCount = 1)
        val older = repository.observeConversations().first().first { it.addressKey == "+15551111111" }

        repository.setConversationsPinned(listOf(older.id), isPinned = true)

        val conversations = repository.observeConversations().first()
        assertEquals(older.id, conversations.first().id)
        assertTrue(conversations.first().isPinned)
        assertTrue(conversations.last().isPinned.not())
    }

    @Test
    fun `unpinning restores recency ordering`() = runTest {
        repository.recordInbound(inbound(address = "+15551111111", timestamp = 100L), partCount = 1)
        repository.recordInbound(inbound(address = "+15552222222", timestamp = 200L), partCount = 1)
        val older = repository.observeConversations().first().first { it.addressKey == "+15551111111" }
        repository.setConversationsPinned(listOf(older.id), isPinned = true)
        assertEquals(
            older.id,
            repository.observeConversations().first().first().id,
        )

        repository.setConversationsPinned(listOf(older.id), isPinned = false)

        val conversations = repository.observeConversations().first()
        assertEquals("+15552222222", conversations.first().addressKey)
    }

    @Test
    fun `moving conversations to spam in a batch empties the inbox`() = runTest {
        repository.recordInbound(inbound(address = "+15551111111", timestamp = 1L), partCount = 1)
        repository.recordInbound(inbound(address = "+15552222222", timestamp = 2L), partCount = 1)
        val ids = repository.observeConversations().first().map { it.id }

        repository.setConversationsSpam(ids, isSpam = true)

        assertTrue(repository.observeConversations().first().isEmpty())
        assertEquals(2, repository.observeSpamConversations().first().size)
    }

    @Test
    fun `deleting conversations in a batch removes them everywhere`() = runTest {
        repository.recordInbound(inbound(address = "+15551111111", timestamp = 1L), partCount = 1)
        repository.recordInbound(inbound(address = "+15552222222", timestamp = 2L), partCount = 1)
        val conversations = repository.observeConversations().first()
        val third = conversations.first().id
        repository.setConversationSpam(third, isSpam = true)
        val keep = repository.observeConversations().first().first()

        repository.deleteConversations(listOf(third, keep.id))

        assertTrue(repository.observeConversations().first().isEmpty())
        assertTrue(repository.observeSpamConversations().first().isEmpty())
    }

    @Test
    fun `deleting an empty batch leaves the store alone`() = runTest {
        repository.recordInbound(inbound(), partCount = 1)

        repository.deleteConversations(emptyList())
        repository.setConversationsPinned(emptyList(), isPinned = true)
        repository.setConversationsSpam(emptyList(), isSpam = true)

        assertEquals(1, repository.observeConversations().first().size)
    }

    @Test
    fun `search finds a message by a word in its body`() = runTest {
        repository.recordInbound(inbound(body = "dinner at eight"), partCount = 1)

        val hits = repository.observeMessageSearch("dinner").first()

        assertEquals(1, hits.size)
        assertEquals("dinner at eight", hits.single().body)
    }

    @Test
    fun `search matches a body word while it is still being typed`() = runTest {
        repository.recordInbound(inbound(body = "dinner at eight"), partCount = 1)

        assertEquals(1, repository.observeMessageSearch("dinn").first().size)
    }

    @Test
    fun `search matches a body word through turkish diacritics`() = runTest {
        repository.recordInbound(inbound(body = "şeker gibi bir gün"), partCount = 1)

        // unicode61 folds diacritics, so the ASCII spelling finds it as well.
        assertEquals(1, repository.observeMessageSearch("şeker").first().size)
        assertEquals(1, repository.observeMessageSearch("seker").first().size)
    }

    @Test
    fun `search still finds a conversation by a partial address`() = runTest {
        repository.recordInbound(inbound(address = "+15551234567", body = "unrelated"), partCount = 1)

        val hits = repository.observeMessageSearch("5551234").first()

        assertEquals(1, hits.size)
        assertEquals("unrelated", hits.single().body)
    }

    @Test
    fun `search treats an operator word as a term instead of failing`() = runTest {
        repository.recordInbound(inbound(body = "either this or that"), partCount = 1)

        // A raw MATCH 'OR' is a syntax error; the builder quotes it.
        assertEquals(1, repository.observeMessageSearch("OR").first().size)
    }

    @Test
    fun `search drops a message once it is deleted`() = runTest {
        repository.recordInbound(inbound(body = "keep me"), partCount = 1)
        val conversationId = requireNotNull(repository.conversationIdFor(inbound().address))
        val doomed = repository.observeMessages(conversationId).first().single()

        database.messageDao().delete(doomed.id)

        assertTrue(repository.observeMessageSearch("keep").first().isEmpty())
    }
}
