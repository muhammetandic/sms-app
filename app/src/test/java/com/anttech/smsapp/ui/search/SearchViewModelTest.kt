package com.anttech.smsapp.ui.search

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Search over the store and the contacts provider.
 *
 * The debounce and the contact lookup both run on [SearchViewModel]'s dispatcher,
 * so the rule's test dispatcher is passed in: that makes the 300ms debounce
 * advance on the virtual clock instead of real time, which is what keeps these
 * assertions from racing.
 */
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {

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

    private fun viewModel(resolveName: suspend (String) -> String? = { null }) = SearchViewModel(
        repository = repository,
        conversationRepository = repository.conversations,
        resolveName = resolveName,
        dispatcher = mainDispatcherRule.testDispatcher,
    )

    private suspend fun givenMessage(address: String, body: String, timestamp: Long = 1_000L) {
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
    fun `a body match resolves to its message`() = runTest(mainDispatcherRule.testDispatcher) {
        givenMessage("+15551234567", "dinner at eight")
        val model = viewModel()

        model.onQueryChange("dinner")

        val result = model.results.first { it.isNotEmpty() }.single()
        assertEquals("dinner at eight", result.snippet)
        assertEquals("+15551234567", result.address)
        assertNotNull("a body hit points at the matching row", result.messageId)
    }

    @Test
    fun `a conversation can match by its contact name alone`() = runTest(mainDispatcherRule.testDispatcher) {
        givenMessage("+15551234567", "dinner at eight")
        // The body and the number contain nothing like the query, so only the
        // resolved contact name can produce this hit.
        val model = viewModel(resolveName = { "Alice" })

        model.onQueryChange("ali")

        val result = model.results.first { it.isNotEmpty() }.single()
        assertEquals("Alice", result.title)
        assertNull("a name-only hit names no particular message", result.messageId)
    }

    @Test
    fun `clearing the query clears the results`() = runTest(mainDispatcherRule.testDispatcher) {
        givenMessage("+15551234567", "dinner at eight")
        val model = viewModel()
        model.onQueryChange("dinner")
        model.results.first { it.isNotEmpty() }

        model.onQueryChange("")

        model.results.first { it.isEmpty() }
    }

    @Test
    fun `a query that matches nothing returns nothing`() = runTest(mainDispatcherRule.testDispatcher) {
        givenMessage("+15551234567", "dinner at eight")
        val model = viewModel()
        model.onQueryChange("dinner")
        model.results.first { it.isNotEmpty() }

        model.onQueryChange("zzzzz")

        model.results.first { it.isEmpty() }
    }
}
