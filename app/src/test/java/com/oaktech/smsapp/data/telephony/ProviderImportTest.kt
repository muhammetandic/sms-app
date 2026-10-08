package com.oaktech.smsapp.data.telephony

import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.MessageDirection
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.data.local.AddressNormalizer
import com.oaktech.smsapp.testing.MainDispatcherRule
import com.oaktech.smsapp.testing.inlineInMemoryDatabase
import com.oaktech.smsapp.sms.receive.InboundMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderImportTest {

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
        repository = MessageRepository(database.conversationDao(), database.messageDao(), database.draftDao(), database.attachmentDao())
    }

    @After
    fun tearDown() = database.close()

    private fun providerMessage(
        id: Long,
        address: String = "+15551234567",
        body: String = "old message",
        date: Long = 1_000L,
        type: Int = SmsProviderTypes.MESSAGE_TYPE_INBOX,
    ) = ProviderMessage(
        id = id,
        threadId = 1L,
        address = address,
        body = body,
        date = date,
        type = type,
        status = SmsProviderTypes.STATUS_NONE,
        read = true,
    )

    private fun providerMms(
        id: Long,
        address: String = "+15551234567",
        body: String = "old mms",
        date: Long = 1_000L,
        type: Int = SmsProviderTypes.MESSAGE_TYPE_INBOX,
    ) = providerMessage(id = id, address = address, body = body, date = date, type = type).copy(mms = true)

    @Test
    fun `imported messages land in the local store`() = runTest(mainDispatcherRule.testDispatcher) {
        val inserted = repository.importFromProvider(
            listOf(providerMessage(1L), providerMessage(2L, body = "another")),
        )

        assertEquals(2, inserted)
        // Same sender, so one conversation with two messages. Counting
        // conversations would confuse grouping with insertion.
        val conversation = repository.observeConversations().first().single()
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    @Test
    fun `re-importing the same rows adds nothing`() = runTest(mainDispatcherRule.testDispatcher) {
        val messages = listOf(providerMessage(1L), providerMessage(2L, body = "another"))
        assertEquals(2, repository.importFromProvider(messages))

        // Runs on every launch, so this must be a no-op rather than a duplicate.
        assertEquals(0, repository.importFromProvider(messages))
        val conversation = repository.observeConversations().first().single()
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    @Test
    fun `a message already received live is not duplicated by the import`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The same text arriving through SMS_DELIVER and then sitting in the
            // provider. The import must derive the live path's address/date/body
            // key and hit the unique index, leaving one row.
            repository.recordInbound(
                message = InboundMessage(
                    address = "+15551234567",
                    body = "old message",
                    timestampMillis = 1_000L,
                    parts = 1,
                ),
                partCount = 1,
            )

            val inserted = repository.importFromProvider(listOf(providerMessage(1L)))

            assertEquals(0, inserted)
            val conversationId = repository.conversationIdFor("+15551234567")!!
            assertEquals(1, repository.observeMessages(conversationId).first().size)
        }

    @Test
    fun `provider rows group into a conversation by address`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.importFromProvider(
            listOf(
                providerMessage(1L, body = "first", date = 1_000L),
                providerMessage(2L, body = "second", date = 2_000L),
            ),
        )

        val conversation = repository.observeConversations().first().single()
        assertEquals("+15551234567", conversation.displayAddress)
        assertEquals(AddressNormalizer.conversationKey("+15551234567"), conversation.addressKey)
    }

    @Test
    fun `different senders become separate conversations`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.importFromProvider(
            listOf(
                providerMessage(1L, address = "+15551234567"),
                providerMessage(2L, address = "+15557654321", date = 2_000L),
            ),
        )

        assertEquals(2, repository.observeConversations().first().size)
    }

    @Test
    fun `an incoming row is stored as delivered`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.importFromProvider(listOf(providerMessage(1L)))

        val conversationId = repository.conversationIdFor("+15551234567")!!
        val message = repository.observeMessages(conversationId).first().single()

        assertEquals(MessageDirection.INCOMING, message.direction)
        assertEquals(MessageStatus.DELIVERED, message.status)
        assertNotNull(message.providerId)
    }

    @Test
    fun `a sent row is stored as outgoing`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.importFromProvider(
            listOf(providerMessage(1L, type = SmsProviderTypes.MESSAGE_TYPE_SENT)),
        )

        val conversationId = repository.conversationIdFor("+15551234567")!!
        val message = repository.observeMessages(conversationId).first().single()

        assertEquals(MessageDirection.OUTGOING, message.direction)
    }

    @Test
    fun `an mms row imports under its own dedupe namespace`() = runTest(mainDispatcherRule.testDispatcher) {
        // SMS and MMS rows share id sequences, so an outbound MMS id 1 must not
        // collide with an outbound SMS id 1 that was already imported.
        repository.importFromProvider(
            listOf(providerMessage(1L, type = SmsProviderTypes.MESSAGE_TYPE_SENT)),
        )
        val inserted = repository.importFromProvider(
            listOf(providerMms(1L, type = SmsProviderTypes.MESSAGE_TYPE_SENT, body = "hi")),
        )

        assertEquals(1, inserted)
        val conversation = repository.observeConversations().first().single()
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    @Test
    fun `re-importing the same mms adds nothing`() = runTest(mainDispatcherRule.testDispatcher) {
        val messages = listOf(providerMms(1L), providerMms(2L, body = "another"))
        assertEquals(2, repository.importFromProvider(messages))

        assertEquals(0, repository.importFromProvider(messages))
        val conversation = repository.observeConversations().first().single()
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    @Test
    fun `an identical sms and mms are not mistaken for one another`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Same sender, same date, same text: the only difference is which
            // table the row came from. Without a namespace the MMS is treated as
            // a duplicate of the SMS and silently dropped from history.
            repository.importFromProvider(
                listOf(
                    providerMessage(1L, body = "look at this"),
                    providerMms(2L, body = "look at this"),
                ),
            )

            val conversation = repository.observeConversations().first().single()
            assertEquals(2, repository.observeMessages(conversation.id).first().size)
        }

    @Test
    fun `an outbound mms row is stored as outgoing`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.importFromProvider(
            listOf(providerMms(1L, type = SmsProviderTypes.MESSAGE_TYPE_SENT)),
        )

        val conversationId = repository.conversationIdFor("+15551234567")!!
        val message = repository.observeMessages(conversationId).first().single()

        assertEquals(MessageDirection.OUTGOING, message.direction)
        assertTrue(message.isMms)
    }

    @Test
    fun `rows with no address are skipped`() = runTest(mainDispatcherRule.testDispatcher) {
        val inserted = repository.importFromProvider(
            listOf(
                providerMessage(1L),
                providerMessage(2L, address = ""),
            ),
        )

        assertEquals(1, inserted)
    }

    @Test
    fun `imported history does not mark anything unread`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.importFromProvider(listOf(providerMessage(1L), providerMessage(2L)))

        assertTrue(repository.observeConversations().first().all { it.unreadCount == 0 })
    }

    @Test
    fun `the conversation keeps the timestamp of its most recent message`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.importFromProvider(
                listOf(
                    providerMessage(1L, date = 1_000L),
                    providerMessage(2L, date = 5_000L),
                    providerMessage(3L, date = 2_000L),
                ),
            )

            assertEquals(5_000L, repository.observeConversations().first().single().lastMessageAt)
        }

    @Test
    fun `import is skipped when the sms role is not held`() = runTest(mainDispatcherRule.testDispatcher) {
        val dataSource = FakeDataSource(listOf(providerMessage(1L)))
        val coordinator = ProviderImportCoordinator(
            repository = repository,
            dataSource = dataSource,
            hasSmsRole = { false },
        )

        assertEquals(0, coordinator.importIfPossible())
        assertEquals(0, dataSource.queryCount)
    }

    @Test
    fun `import runs when the sms role is held`() = runTest(mainDispatcherRule.testDispatcher) {
        val dataSource = FakeDataSource(listOf(providerMessage(1L)))
        val coordinator = ProviderImportCoordinator(
            repository = repository,
            dataSource = dataSource,
            hasSmsRole = { true },
        )

        assertEquals(1, coordinator.importIfPossible())
        assertEquals(1, dataSource.queryCount)
    }

    @Test
    fun `a provider query failure yields no rows rather than crashing`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val dataSource = FakeDataSource(emptyList(), failWith = SecurityException("no role"))
            val coordinator = ProviderImportCoordinator(
                repository = repository,
                dataSource = dataSource,
                hasSmsRole = { true },
            )

            assertEquals(0, coordinator.importIfPossible())
        }

    @Test
    fun `concurrent imports do not duplicate rows`() = runTest(mainDispatcherRule.testDispatcher) {
        val dataSource = FakeDataSource(listOf(providerMessage(1L), providerMessage(2L, body = "another")))
        val coordinator = ProviderImportCoordinator(
            repository = repository,
            dataSource = dataSource,
            hasSmsRole = { true },
        )

        val first = async { coordinator.importIfPossible() }
        val second = async { coordinator.importIfPossible() }
        val inserted = listOf(first.await(), second.await())

        // The mutex serialises rather than deduplicates, so both may query. What
        // matters is that the second run's inserts are rejected by the unique
        // index, leaving each message stored exactly once.
        assertEquals(2, inserted.sum())
        val conversation = repository.observeConversations().first().single()
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    /** Stands in for the provider, counting queries so serialisation is observable. */
    private class FakeDataSource(
        private val messages: List<ProviderMessage>,
        private val failWith: Throwable? = null,
    ) : SmsMessageSource {
        var queryCount = 0
            private set

        override suspend fun queryAllMessages(limit: Int): List<ProviderMessage> {
            queryCount += 1
            failWith?.let { throw it }
            return messages
        }
    }
}
