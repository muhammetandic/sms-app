package com.anttech.smsapp.ui.conversations

import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.testing.MainDispatcherRule
import com.anttech.smsapp.testing.inlineInMemoryDatabase
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.sms.receive.InboundMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(
            ApplicationProvider.getApplicationContext(),
            SmsDatabase::class.java,
        )
        repository = MessageRepository(database.conversationDao(), database.messageDao(), database.draftDao())
    }

    @After
    fun tearDown() = database.close()

    private suspend fun givenInbound(address: String, body: String, timestamp: Long) {
        repository.recordInbound(
            message = InboundMessage(
                address = address,
                body = body,
                timestampMillis = timestamp,
                parts = 1,
            ),
            partCount = 1,
        )
    }

    @Test
    fun `conversations are listed with the most recent first`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "older", 1_000L)
        givenInbound("+15550000002", "newer", 9_000L)

        val rows = repository.observeConversations().first()

        assertEquals(listOf("+15550000002", "+15550000001"), rows.map { it.displayAddress })
    }

    @Test
    fun `unread count is surfaced for conversations with unread messages`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "one", 1_000L)
        givenInbound("+15550000001", "two", 2_000L)

        val row = repository.observeConversations().first().single()

        assertEquals(2, row.unreadCount)
        assertEquals("two", row.snippet)
    }

    @Test
    fun `opening a conversation clears its unread count`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "one", 1_000L)
        val conversationId = repository.conversationIdFor("+15550000001")!!

        repository.markConversationRead(conversationId)

        assertEquals(0, repository.observeConversations().first().single().unreadCount)
    }

    @Test
    fun `losing the sms role switches the ui to read only`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "hello", 1_000L)
        var roleHeld = true
        val viewModel = ConversationsViewModel(repository, { roleHeld })

        assertFalse(viewModel.uiState.first { !it.isLoading }.isReadOnly)

        roleHeld = false
        viewModel.refreshRole()

        val degraded = viewModel.uiState.first { it.isReadOnly }
        assertTrue(degraded.isReadOnly)
        assertEquals(1, degraded.conversations.size)
    }

    @Test
    fun `holding the sms role keeps the ui writable`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "hello", 1_000L)
        val viewModel = ConversationsViewModel(repository, { true })

        val state = viewModel.uiState.first { !it.isLoading }

        assertFalse(state.isReadOnly)
        assertEquals(1, state.conversations.size)
    }

    @Test
    fun `the opening frame already knows the role instead of flashing the banner`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Read before any collector starts the upstream: this is what the
            // first composition renders, so a held role must not look lost while
            // the conversations query is still warming up.
            assertFalse(ConversationsViewModel(repository, { true }).uiState.value.isReadOnly)
            assertTrue(ConversationsViewModel(repository, { false }).uiState.value.isReadOnly)
        }

    @Test
    fun `a new inbound message appears without any manual refresh`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = ConversationsViewModel(repository, { true })
        assertTrue(viewModel.uiState.first { !it.isLoading }.conversations.isEmpty())

        givenInbound("+15550000001", "new arrival", 5_000L)

        val updated = viewModel.uiState.first { it.conversations.isNotEmpty() }
        assertEquals("new arrival", updated.conversations.single().snippet)
    }

    @Test
    fun `a row falls back to the address key when no display name is stored`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "hello", 1_000L)

        val row = ConversationsViewModel(repository, { true })
            .uiState.first { !it.isLoading }
            .conversations
            .single()

        assertEquals("+15550000001", row.address)
        assertEquals(1, row.unreadCount)
        assertTrue(row.timestampLabel.isNotBlank())
    }

    @Test
    fun `deleting a conversation removes it from the inbox`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "hello", 1_000L)
        givenInbound("+15550000002", "bye", 2_000L)
        val viewModel = ConversationsViewModel(repository, { true })
        val conversationId = viewModel.uiState.first { !it.isLoading }.conversations.last().id

        viewModel.deleteConversation(conversationId)

        val remaining = viewModel.uiState.first { it.conversations.size == 1 }.conversations
        assertEquals(listOf("+15550000002"), remaining.map { it.address })
    }

    @Test
    fun `pinning rows keeps them first and marks them pinned`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "old", 1_000L)
        givenInbound("+15550000002", "new", 9_000L)
        val viewModel = ConversationsViewModel(repository, { true })
        val olderId = viewModel.uiState.first { !it.isLoading }.conversations.last().id

        viewModel.setConversationsPinned(setOf(olderId), isPinned = true)

        val rows = viewModel.uiState.first { it.conversations.first().isPinned }.conversations
        assertEquals(listOf(olderId, rows.last().id), rows.map { it.id })
    }

    @Test
    fun `moving conversations to spam removes them from the inbox`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "one", 1_000L)
        givenInbound("+15550000002", "two", 2_000L)
        val viewModel = ConversationsViewModel(repository, { true })
        val ids = viewModel.uiState.first { !it.isLoading }.conversations.map { it.id }

        viewModel.moveToSpam(ids.toSet())

        assertTrue(viewModel.uiState.first { !it.isLoading }.conversations.isEmpty())
    }

    @Test
    fun `marking read rows unread restores their unread badge`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "hello", 1_000L)
        val viewModel = ConversationsViewModel(repository, { true })
        val row = viewModel.uiState.first { !it.isLoading }.conversations.single()
        repository.markConversationRead(row.id)
        viewModel.uiState.first { it.conversations.single().unreadCount == 0 }

        viewModel.markConversationsUnread(setOf(row.id))

        val restored = viewModel.uiState.first { it.conversations.single().unreadCount == 1 }
            .conversations.single()
        assertEquals(1, restored.unreadCount)
    }

    @Test
    fun `marking an unread row unread keeps its existing count`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "first", 1_000L)
        givenInbound("+15550000001", "second", 2_000L)
        val viewModel = ConversationsViewModel(repository, { true })
        val row = viewModel.uiState.first { !it.isLoading }.conversations.single()

        viewModel.markConversationsUnread(setOf(row.id))

        assertEquals(
            2,
            viewModel.uiState.first { it.conversations.single().unreadCount == 2 }
                .conversations.single().unreadCount,
        )
    }

    @Test
    fun `deleting conversations in a batch removes them from the inbox`() = runTest(mainDispatcherRule.testDispatcher) {
        givenInbound("+15550000001", "one", 1_000L)
        givenInbound("+15550000002", "two", 2_000L)
        givenInbound("+15550000003", "three", 3_000L)
        val viewModel = ConversationsViewModel(repository, { true })
        val ids = viewModel.uiState.first { !it.isLoading }.conversations.map { it.id }

        viewModel.deleteConversations(ids.drop(1).toSet())

        val remaining = viewModel.uiState.first { it.conversations.size == 1 }.conversations
        assertEquals(ids.first(), remaining.single().id)
    }
}