package com.anttech.smsapp.ui.drafts

import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.testing.MainDispatcherRule
import com.anttech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DraftsViewModelTest {

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

    private fun viewModel() = DraftsViewModel(
        repository = repository,
        contactNames = null,
        dispatcher = mainDispatcherRule.testDispatcher,
    )

    @Test
    fun `an unsent draft appears with its body as the snippet`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.saveDraftForRecipient("+15551234567", "not sent yet")
        val model = viewModel()

        val state = model.uiState.firstValue()
        assertFalse(state.isLoading)
        val row = state.conversations.single()
        assertEquals("+15551234567", row.address)
        assertEquals("not sent yet", row.snippet)
        assertEquals("a draft carries no unread badge", 0, row.unreadCount)
    }

    @Test
    fun `deleting a draft removes it from the list`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.saveDraftForRecipient("+15551234567", "not sent yet")
        val model = viewModel()
        val row = model.uiState.firstValue().conversations.single()

        model.deleteDraft(row.id)

        assertTrue(model.uiState.first { it.conversations.isEmpty() }.conversations.isEmpty())
    }

    @Test
    fun `resolving names without a resolver leaves the draft on its address`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.saveDraftForRecipient("+15551234567", "not sent yet")
            val model = viewModel()

            // Absent a resolver there is nothing to look up; the call must be a
            // safe no-op rather than clearing the rows it cannot name.
            model.resolveContactNames()

            val row = model.uiState.firstValue().conversations.single()
            assertEquals("+15551234567", row.address)
            assertNull(row.displayName)
        }

    /**
     * The draft row only becomes visible once the flow has started, and
     * `WhileSubscribed` holds it back until a collector appears, so the first
     * collected value is what the screen would first draw.
     */
    private suspend fun StateFlow<DraftsUiState>.firstValue(): DraftsUiState =
        first { !it.isLoading }
}
