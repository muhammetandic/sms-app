package com.anttech.smsapp.ui.spam

import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.sms.receive.InboundMessage
import com.anttech.smsapp.testing.MainDispatcherRule
import com.anttech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpamViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    // Conversation state, split out of the message store and shared by the
    // surfaces that work in whole conversations.
    private val conversations get() = repository.conversations

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

    private suspend fun givenSpamConversation(address: String, body: String, timestamp: Long): Long {
        repository.recordInbound(
            message = InboundMessage(
                address = address,
                body = body,
                timestampMillis = timestamp,
                parts = 1,
            ),
            partCount = 1,
        )
        val conversationId = requireNotNull(conversations.conversationIdFor(address))
        conversations.setConversationSpam(conversationId, isSpam = true)
        return conversationId
    }

    @Test
    fun `deleting a conversation removes it from the spam folder`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = givenSpamConversation("+15550000001", "spam", 1_000L)
        val viewModel = SpamViewModel(conversations, dispatcher = mainDispatcherRule.testDispatcher)
        viewModel.resolveContactNames()

        viewModel.deleteConversation(conversationId)

        assertTrue(viewModel.uiState.first { !it.isLoading }.conversations.isEmpty())
    }

    @Test
    fun `restoring is a move back to the inbox, not a delete`() = runTest(mainDispatcherRule.testDispatcher) {
        givenSpamConversation("+15550000001", "spam", 1_000L)
        val viewModel = SpamViewModel(conversations, dispatcher = mainDispatcherRule.testDispatcher)
        val row = viewModel.uiState.first { it.conversations.isNotEmpty() }.conversations.single()

        viewModel.restore(row.id)

        assertTrue(viewModel.uiState.first { it.conversations.isEmpty() }.conversations.isEmpty())
        assertTrue(conversations.observeConversations().first().isNotEmpty())
    }

    @Test
    fun `restoring a selection moves all of them back to the inbox`() = runTest(mainDispatcherRule.testDispatcher) {
        givenSpamConversation("+15550000001", "a", 1_000L)
        givenSpamConversation("+15550000002", "b", 2_000L)
        val viewModel = SpamViewModel(conversations, dispatcher = mainDispatcherRule.testDispatcher)
        val ids = viewModel.uiState.first { it.conversations.size == 2 }.conversations.map { it.id }

        viewModel.restore(ids.toSet())

        assertTrue(viewModel.uiState.first { it.conversations.isEmpty() }.conversations.isEmpty())
        assertEquals(2, conversations.observeConversations().first().size)
    }

    @Test
    fun `deleting a selection clears the spam folder`() = runTest(mainDispatcherRule.testDispatcher) {
        givenSpamConversation("+15550000001", "a", 1_000L)
        givenSpamConversation("+15550000002", "b", 2_000L)
        val viewModel = SpamViewModel(conversations, dispatcher = mainDispatcherRule.testDispatcher)
        viewModel.resolveContactNames()
        val ids = viewModel.uiState.first { it.conversations.size == 2 }.conversations.map { it.id }

        viewModel.delete(ids.toSet())

        assertTrue(viewModel.uiState.first { !it.isLoading }.conversations.isEmpty())
    }
}