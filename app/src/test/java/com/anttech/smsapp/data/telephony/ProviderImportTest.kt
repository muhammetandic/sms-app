package com.anttech.smsapp.data.telephony

import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.ProviderImportRepository
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.data.local.AddressNormalizer
import com.anttech.smsapp.testing.MainDispatcherRule
import com.anttech.smsapp.testing.inlineInMemoryDatabase
import com.anttech.smsapp.sms.receive.InboundMessage
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
    // Conversation state, split out of the message store and shared by the
    // surfaces that work in whole conversations.
    private val conversations get() = repository.conversations
    private lateinit var importRepository: ProviderImportRepository

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(
            ApplicationProvider.getApplicationContext(),
            SmsDatabase::class.java,
        )
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
        )
        importRepository = ProviderImportRepository(
            database.conversationDao(),
            database.messageDao(),
            database,
        )
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

    @Test
    fun `imported messages land in the local store`() = runTest(mainDispatcherRule.testDispatcher) {
        val inserted = importRepository.importFromProvider(
            listOf(providerMessage(1L), providerMessage(2L, body = "another")),
        )

        assertEquals(2, inserted)
        // Same sender, so one conversation with two messages. Counting
        // conversations would confuse grouping with insertion.
        val conversation = conversations.observeConversations().first().single()
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    @Test
    fun `re-importing the same rows adds nothing`() = runTest(mainDispatcherRule.testDispatcher) {
        val messages = listOf(providerMessage(1L), providerMessage(2L, body = "another"))
        assertEquals(2, importRepository.importFromProvider(messages))

        // Runs on every launch, so this must be a no-op rather than a duplicate.
        assertEquals(0, importRepository.importFromProvider(messages))
        val conversation = conversations.observeConversations().first().single()
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

            val inserted = importRepository.importFromProvider(listOf(providerMessage(1L)))

            assertEquals(0, inserted)
            val conversationId = conversations.conversationIdFor("+15551234567")!!
            assertEquals(1, repository.observeMessages(conversationId).first().size)
        }

    @Test
    fun `provider rows group into a conversation by address`() = runTest(mainDispatcherRule.testDispatcher) {
        importRepository.importFromProvider(
            listOf(
                providerMessage(1L, body = "first", date = 1_000L),
                providerMessage(2L, body = "second", date = 2_000L),
            ),
        )

        val conversation = conversations.observeConversations().first().single()
        assertEquals("+15551234567", conversation.displayAddress)
        assertEquals(AddressNormalizer.conversationKey("+15551234567"), conversation.addressKey)
    }

    @Test
    fun `different senders become separate conversations`() = runTest(mainDispatcherRule.testDispatcher) {
        importRepository.importFromProvider(
            listOf(
                providerMessage(1L, address = "+15551234567"),
                providerMessage(2L, address = "+15557654321", date = 2_000L),
            ),
        )

        assertEquals(2, conversations.observeConversations().first().size)
    }

    @Test
    fun `an incoming row is stored as delivered`() = runTest(mainDispatcherRule.testDispatcher) {
        importRepository.importFromProvider(listOf(providerMessage(1L)))

        val conversationId = conversations.conversationIdFor("+15551234567")!!
        val message = repository.observeMessages(conversationId).first().single()

        assertEquals(MessageDirection.INCOMING, message.direction)
        assertEquals(MessageStatus.DELIVERED, message.status)
        assertNotNull(message.providerId)
    }

    @Test
    fun `a sent row is stored as outgoing`() = runTest(mainDispatcherRule.testDispatcher) {
        importRepository.importFromProvider(
            listOf(providerMessage(1L, type = SmsProviderTypes.MESSAGE_TYPE_SENT)),
        )

        val conversationId = conversations.conversationIdFor("+15551234567")!!
        val message = repository.observeMessages(conversationId).first().single()

        assertEquals(MessageDirection.OUTGOING, message.direction)
    }

    @Test
    fun `rows with no address are skipped`() = runTest(mainDispatcherRule.testDispatcher) {
        val inserted = importRepository.importFromProvider(
            listOf(
                providerMessage(1L),
                providerMessage(2L, address = ""),
            ),
        )

        assertEquals(1, inserted)
    }

    @Test
    fun `imported history does not mark anything unread`() = runTest(mainDispatcherRule.testDispatcher) {
        importRepository.importFromProvider(listOf(providerMessage(1L), providerMessage(2L)))

        assertTrue(conversations.observeConversations().first().all { it.unreadCount == 0 })
    }

    @Test
    fun `the conversation keeps the timestamp of its most recent message`() =
        runTest(mainDispatcherRule.testDispatcher) {
            importRepository.importFromProvider(
                listOf(
                    providerMessage(1L, date = 1_000L),
                    providerMessage(2L, date = 5_000L),
                    providerMessage(3L, date = 2_000L),
                ),
            )

            assertEquals(5_000L, conversations.observeConversations().first().single().lastMessageAt)
        }

    @Test
    fun `import is skipped when the sms role is not held`() = runTest(mainDispatcherRule.testDispatcher) {
        val dataSource = FakeDataSource(listOf(providerMessage(1L)))
        val coordinator = ProviderImportCoordinator(
            repository = importRepository,
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
            repository = importRepository,
            dataSource = dataSource,
            hasSmsRole = { true },
        )

        assertEquals(1, coordinator.importIfPossible())
        assertEquals(1, dataSource.queryCount)
    }

    @Test
    fun `import skips the full read when nothing new arrived`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val dataSource = FakeDataSource(listOf(providerMessage(1L), providerMessage(2L, body = "another")))
            val coordinator = ProviderImportCoordinator(
                repository = importRepository,
                dataSource = dataSource,
                hasSmsRole = { true },
            )

            assertEquals(2, coordinator.importIfPossible())
            // The launch chore runs on every start, so a second run with no new
            // rows must not re-read history: the store now holds the provider's
            // max id, and the watermark short-circuits before queryAllMessages.
            assertEquals(0, coordinator.importIfPossible())
            assertEquals(1, dataSource.queryCount)
        }

    @Test
    fun `an unmappable newest row still advances the watermark`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The newest provider row has no address, so the import drops it (no
            // conversation to file it under) and it never earns a local
            // provider_id. The watermark must still move past it, or every
            // launch would re-read the whole provider to learn nothing.
            val dataSource = FakeDataSource(
                listOf(providerMessage(1L), providerMessage(2L, address = "")),
            )
            val coordinator = ProviderImportCoordinator(
                repository = importRepository,
                dataSource = dataSource,
                hasSmsRole = { true },
            )

            assertEquals(1, coordinator.importIfPossible())
            assertEquals(0, coordinator.importIfPossible())
            assertEquals(1, dataSource.queryCount)
        }

    @Test
    fun `a provider query failure yields no rows rather than crashing`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val dataSource = FakeDataSource(emptyList(), failWith = SecurityException("no role"))
            val coordinator = ProviderImportCoordinator(
                repository = importRepository,
                dataSource = dataSource,
                hasSmsRole = { true },
            )

            assertEquals(0, coordinator.importIfPossible())
        }

    @Test
    fun `concurrent imports do not duplicate rows`() = runTest(mainDispatcherRule.testDispatcher) {
        val dataSource = FakeDataSource(listOf(providerMessage(1L), providerMessage(2L, body = "another")))
        val coordinator = ProviderImportCoordinator(
            repository = importRepository,
            dataSource = dataSource,
            hasSmsRole = { true },
        )

        val first = async { coordinator.importIfPossible() }
        val second = async { coordinator.importIfPossible() }
        val inserted = listOf(first.await(), second.await())

        // The mutex serialises the pair. The first run imports both rows and
        // leaves the store's watermark at their highest id; the second either
        // finds them already linked (old path) or the watermark no longer
        // differs (new path), so in both cases only the first inserts.
        assertEquals(2, inserted.sum())
        val conversation = conversations.observeConversations().first().single()
        assertEquals(2, repository.observeMessages(conversation.id).first().size)
    }

    /** Stands in for the provider, counting queries so serialisation is observable. */
    private class FakeDataSource(
        private val messages: List<ProviderMessage>,
        private val failWith: Throwable? = null,
    ) : SmsMessageSource {
        var queryCount = 0
            private set

        override suspend fun queryAllMessages(limit: Int, afterId: Long): List<ProviderMessage> {
            queryCount += 1
            failWith?.let { throw it }
            return messages.filter { it.id > afterId }
        }

        override suspend fun queryMaxMessageId(): Long? {
            failWith?.let { throw it }
            return messages.maxOfOrNull { it.id }
        }
    }
}
