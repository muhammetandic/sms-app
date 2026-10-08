package com.oaktech.smsapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.local.MessageDirection
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.data.telephony.ProviderMessage
import com.oaktech.smsapp.data.telephony.ProviderRowRef
import com.oaktech.smsapp.data.telephony.TelephonyMessageWriter
import com.oaktech.smsapp.sms.receive.InboundMessage
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
            database.attachmentDao(),
            writer,
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
        type: Int = com.oaktech.smsapp.data.telephony.SmsProviderTypes.MESSAGE_TYPE_INBOX,
        mms: Boolean = false,
    ) = ProviderMessage(
        id = id,
        threadId = 1L,
        address = address,
        body = body,
        date = 1_000L,
        type = type,
        status = com.oaktech.smsapp.data.telephony.SmsProviderTypes.STATUS_NONE,
        read = true,
        mms = mms,
    )

    @Test
    fun `deleting an imported message mirrors the provider delete`() = runTest {
        // Imported history carries the provider row's own id.
        repository.importFromProvider(listOf(providerMessage(id = 7L)))
        val messageId = repository.observeMessages(repository.observeConversations().first().single().id).first().single().id

        repository.deleteMessage(messageId)

        assertEquals(listOf(ProviderRowRef(7L, isMms = false)), writer.deleted)
    }

    @Test
    fun `deleting an imported mms mirrors an mms provider delete`() = runTest {
        repository.importFromProvider(listOf(providerMessage(id = 9L, mms = true)))
        val messageId = repository.observeMessages(repository.observeConversations().first().single().id).first().single().id

        repository.deleteMessage(messageId)

        assertEquals(listOf(ProviderRowRef(9L, isMms = true)), writer.deleted)
    }

    @Test
    fun `deleting a live arrival does not guess at a provider row`() = runTest {
        // A message received over SMS_DELIVER has no provider id; mirroring it
        // would mean matching on content, deleting something it cannot identify.
        repository.recordInbound(InboundMessage("+15551234567", "hello", 1L, parts = 1), partCount = 1)
        val messageId = repository.observeMessages(repository.observeConversations().first().single().id).first().single().id

        repository.deleteMessage(messageId)

        assertEquals(emptyList<ProviderRowRef>(), writer.deleted)
    }

    @Test
    fun `deleting a conversation mirrors every provider row it held`() = runTest {
        repository.importFromProvider(
            listOf(
                providerMessage(id = 1L),
                providerMessage(id = 2L, mms = true),
            ),
        )
        val conversation = repository.observeConversations().first().single()

        repository.deleteConversation(conversation.id)

        assertEquals(
            setOf(ProviderRowRef(1L, false), ProviderRowRef(2L, true)),
            writer.deleted.toSet(),
        )
    }

    @Test
    fun `marking a conversation read mirrors the read state`() = runTest {
        repository.importFromProvider(
            listOf(
                providerMessage(id = 1L),
                providerMessage(id = 3L, mms = true),
                providerMessage(id = 5L, address = "+15555555555"),
            ),
        )
        val readConversation = repository.observeConversations().first()
            .first { it.displayAddress == "+15551234567" }

        repository.markConversationRead(readConversation.id)

        assertEquals(
            setOf(ProviderRowRef(1L, false), ProviderRowRef(3L, true)),
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
        val conversation = repository.observeConversations().first().single()

        repository.markConversationRead(conversation.id)

        assertEquals(listOf(ProviderRowRef(42L, isMms = false)), writer.markedRead)
    }

    @Test
    fun `marking conversations unread mirrors the read state back`() = runTest {
        repository.importFromProvider(
            listOf(
                providerMessage(id = 1L),
                providerMessage(id = 3L, mms = true),
                providerMessage(id = 5L, address = "+15555555555"),
            ),
        )
        val unreadConversation = repository.observeConversations().first()
            .first { it.displayAddress == "+15551234567" }

        repository.setConversationsUnread(setOf(unreadConversation.id))

        assertEquals(
            setOf(ProviderRowRef(1L, false), ProviderRowRef(3L, true)),
            writer.markedUnread.toSet(),
        )
    }

    private class RecordingTelephonyMessageWriter : TelephonyMessageWriter {
        val deleted = mutableListOf<ProviderRowRef>()
        val markedRead = mutableListOf<ProviderRowRef>()
        val markedUnread = mutableListOf<ProviderRowRef>()

        override suspend fun delete(rows: List<ProviderRowRef>) {
            deleted.addAll(rows)
        }

        override suspend fun markRead(rows: List<ProviderRowRef>) {
            markedRead.addAll(rows)
        }

        override suspend fun markUnread(rows: List<ProviderRowRef>) {
            markedUnread.addAll(rows)
        }
    }
}