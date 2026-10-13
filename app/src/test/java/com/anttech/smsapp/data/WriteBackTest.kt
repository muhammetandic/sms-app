package com.anttech.smsapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.data.telephony.ProviderMessage
import com.anttech.smsapp.data.telephony.ProviderMessageMapper
import com.anttech.smsapp.data.telephony.ProviderRowRef
import com.anttech.smsapp.sms.receive.InboundMessage
import com.anttech.smsapp.testing.RecordingTelephonyMessageWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WriteBackTest {

    private lateinit var database: SmsDatabase
    private lateinit var writer: RecordingTelephonyMessageWriter
    private lateinit var repository: MessageRepository
    // Conversation state, split out of the message store and shared by the
    // surfaces that work in whole conversations.
    private val conversations get() = repository.conversations
    private lateinit var importRepository: ProviderImportRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SmsDatabase::class.java,
        ).build()
        writer = RecordingTelephonyMessageWriter()
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
            writer,
        )
        importRepository = ProviderImportRepository(
            database.conversationDao(),
            database.messageDao(),
            database,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun providerMessage(
        id: Long,
        address: String = "+15551234567",
        body: String = "hi",
        type: Int = com.anttech.smsapp.data.telephony.SmsProviderTypes.MESSAGE_TYPE_INBOX,
    ) = ProviderMessage(
        id = id,
        threadId = 1L,
        address = address,
        body = body,
        date = 1_000L,
        type = type,
        status = com.anttech.smsapp.data.telephony.SmsProviderTypes.STATUS_NONE,
        read = true,
    )

    @Test
    fun `deleting an imported message mirrors the provider delete`() = runTest {
        // Imported history carries the provider row's own id.
        importRepository.importFromProvider(listOf(providerMessage(id = 7L)))
        val messageId = repository.observeMessages(conversations.observeConversations().first().single().id).first().single().id

        repository.deleteMessage(messageId)

        assertEquals(listOf(ProviderRowRef(7L)), writer.deleted)
    }

    @Test
    fun `deleting a live arrival does not guess at a provider row`() = runTest {
        // A message received over SMS_DELIVER has no provider id; mirroring it
        // would mean matching on content, deleting something it cannot identify.
        repository.recordInbound(InboundMessage("+15551234567", "hello", 1L, parts = 1), partCount = 1)
        val messageId = repository.observeMessages(conversations.observeConversations().first().single().id).first().single().id

        repository.deleteMessage(messageId)

        assertEquals(emptyList<ProviderRowRef>(), writer.deleted)
    }

    @Test
    fun `deleting a conversation mirrors every provider row it held`() = runTest {
        importRepository.importFromProvider(
            listOf(
                providerMessage(id = 1L),
                providerMessage(id = 2L, body = "second"),
            ),
        )
        val conversation = conversations.observeConversations().first().single()

        conversations.deleteConversation(conversation.id)

        assertEquals(
            setOf(ProviderRowRef(1L), ProviderRowRef(2L)),
            writer.deleted.toSet(),
        )
    }

    @Test
    fun `marking a conversation read mirrors the read state`() = runTest {
        importRepository.importFromProvider(
            listOf(
                providerMessage(id = 1L),
                providerMessage(id = 5L, address = "+15555555555"),
            ),
        )
        val readConversation = conversations.observeConversations().first()
            .first { it.displayAddress == "+15551234567" }

        conversations.markConversationRead(readConversation.id)

        assertEquals(
            setOf(ProviderRowRef(1L)),
            writer.markedRead.toSet(),
        )
    }

    @Test
    fun `marking a conversation read mirrors live rows only when they have a provider id`() = runTest {
        // A sent message learns its provider id from the delivery report.
        repository.recordOutgoing("+15551234567", "are you there", 1L).let { id ->
            repository.updateOutgoingStatus(id, MessageStatus.SENT, providerId = 42L)
        }
        repository.recordInbound(InboundMessage("+15551234567", "yes", 2L, parts = 1), partCount = 1)
        val conversation = conversations.observeConversations().first().single()

        conversations.markConversationRead(conversation.id)

        assertEquals(listOf(ProviderRowRef(42L)), writer.markedRead)
    }

    @Test
    fun `marking conversations unread mirrors the read state back`() = runTest {
        importRepository.importFromProvider(
            listOf(
                providerMessage(id = 1L),
                providerMessage(id = 5L, address = "+15555555555"),
            ),
        )
        val unreadConversation = conversations.observeConversations().first()
            .first { it.displayAddress == "+15551234567" }

        conversations.setConversationsUnread(setOf(unreadConversation.id))

        assertEquals(
            setOf(ProviderRowRef(1L)),
            writer.markedUnread.toSet(),
        )
    }

    @Test
    fun `a received message is written to the inbox and linked for later mirrors`() = runTest {
        val message = InboundMessage("+15551234567", "hello", 1L, parts = 1)
        val rowId = repository.recordInbound(message, partCount = 1)!!

        val providerId = repository.mirrorInboundToProvider(rowId, message)

        assertEquals(listOf(message), writer.insertedInbound)
        assertEquals(100L, providerId)
        // The link is what the destructive and read-state mirrors need: without
        // it the provider row would outlive a delete made here.
        repository.deleteMessage(rowId)
        assertEquals(listOf(ProviderRowRef(100L)), writer.deleted)
    }

    @Test
    fun `a refused inbox write leaves the local row unlinked`() = runTest {
        writer.refuseInserts = true
        val message = InboundMessage("+15551234567", "hello", 1L, parts = 1)
        val rowId = repository.recordInbound(message, partCount = 1)!!

        val providerId = repository.mirrorInboundToProvider(rowId, message)

        assertEquals(null, providerId)
        repository.deleteMessage(rowId)
        assertEquals(emptyList<ProviderRowRef>(), writer.deleted)
    }

    @Test
    fun `an accepted send is written to the sent table under the provider's dedupe key`() = runTest {
        val rowId = repository.recordOutgoing("+15551234567", "are you there", 1L)

        repository.mirrorSentToProvider(rowId, "+15551234567", "are you there", 1L)

        assertEquals(
            listOf(RecordingTelephonyMessageWriter.SentRow("+15551234567", "are you there", 1L)),
            writer.insertedSent,
        )
        val row = repository.getMessage(rowId)!!
        assertEquals(100L, row.providerId)
        assertEquals(ProviderMessageMapper.dedupeKey(100L), row.dedupeKey)

        // The row now answers to the key an import would derive for the same
        // provider row, so the next import recognises it instead of filing a
        // second copy of the same send.
        val inserted = importRepository.importFromProvider(
            listOf(
                providerMessage(
                    id = 100L,
                    body = "are you there",
                    type = com.anttech.smsapp.data.telephony.SmsProviderTypes.MESSAGE_TYPE_SENT,
                ),
            ),
        )

        assertEquals(0, inserted)
        val conversation = conversations.observeConversations().first().single()
        assertEquals(1, repository.observeMessages(conversation.id).first().size)
    }

    @Test
    fun `a failed send flips its provider row to failed`() = runTest {
        val rowId = repository.recordOutgoing("+15551234567", "are you there", 1L)
        repository.mirrorSentToProvider(rowId, "+15551234567", "are you there", 1L)

        repository.updateOutgoingError(rowId, errorCode = 64)

        assertEquals(listOf(ProviderRowRef(100L)), writer.markedFailed)
        assertEquals(MessageStatus.FAILED, repository.getMessage(rowId)!!.status)
    }

    @Test
    fun `a send refused by the provider has nothing to flip when it fails`() = runTest {
        writer.refuseInserts = true
        val rowId = repository.recordOutgoing("+15551234567", "are you there", 1L)
        repository.mirrorSentToProvider(rowId, "+15551234567", "are you there", 1L)

        repository.updateOutgoingStatus(rowId, MessageStatus.FAILED)

        assertEquals(emptyList<ProviderRowRef>(), writer.markedFailed)
    }
}