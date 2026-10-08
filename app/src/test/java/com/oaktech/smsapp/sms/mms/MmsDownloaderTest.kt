package com.oaktech.smsapp.sms.mms

import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.DownloadState
import com.oaktech.smsapp.data.local.MessageEntity
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.testing.inlineInMemoryDatabase
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

@RunWith(RobolectricTestRunner::class)
class MmsDownloaderTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository

    private val recipient = "+15550000001"

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(context, SmsDatabase::class.java)
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
            database.attachmentDao(),
        )
        DataGraph.overrideForTest(context, repository)
    }

    @After
    fun tearDown() {
        DataGraph.overrideForTest(context, null)
        database.close()
    }

    @Test
    fun `retrieves a pending notification from the mmsc and records the parts`() = runTest {
        val messageId = recordPendingNotification(contentLocation = "/rc/42")
        val retriever = PendingRetriever(
            MmsPduBuilder.retrieveConf(
                transactionId = "tid",
                from = recipient,
                subject = null,
                bodyText = "hi",
                media = listOf(
                    MmsPduBuilder.Media("image/jpeg", "a.jpg", jpegBytes()),
                ),
                boundary = "dltest",
            ),
        )
        val downloader = MmsDownloader(
            context,
            EmptyPartSource(),
            MmsAutoRetriever(retriever, FakeApn("http://mmsc.example")),
        )

        val downloaded = downloader.download(repository, messageId)

        assertTrue(downloaded)
        assertEquals("http://mmsc.example/rc/42", retriever.requestedUrl)
        val row = repository.getMessagesInConversation()
        assertEquals(DownloadState.RETRIEVED, row.downloadState)
        assertEquals("hi", row.body)
        val stored = repository.observeAttachments(messageId).first { it.size == 1 }
        assertTrue("stored name should keep the part name", stored.single().fileName.endsWith("a.jpg"))
    }

    @Test
    fun `retrieves a pending text-only MMS and updates body`() = runTest {
        val messageId = recordPendingNotification(contentLocation = "/rc/43")
        val retriever = PendingRetriever(
            MmsPduBuilder.retrieveConf(
                transactionId = "tid-text",
                from = recipient,
                subject = "text-subject",
                bodyText = "hello text only",
                media = emptyList(),
                boundary = "dltest",
            ),
        )
        val downloader = MmsDownloader(
            context,
            EmptyPartSource(),
            MmsAutoRetriever(retriever, FakeApn("http://mmsc.example")),
        )

        val downloaded = downloader.download(repository, messageId)

        assertTrue(downloaded)
        val row = repository.getMessagesInConversation()
        assertEquals(DownloadState.RETRIEVED, row.downloadState)
        assertEquals("hello text only", row.body)
    }

    @Test
    fun `a pending notification without media keeps its pending state`() = runTest {
        val messageId = recordPendingNotification(contentLocation = "/rc/42")
        val downloader = MmsDownloader(
            context,
            EmptyPartSource(),
            MmsAutoRetriever(PendingRetriever(null), FakeApn("http://mmsc.example")),
        )

        val downloaded = downloader.download(repository, messageId)

        assertFalse(downloaded)
        val row = repository.getMessagesInConversation()
        assertEquals(DownloadState.PENDING, row.downloadState)
    }

    @Test
    fun `a retrieved message without a location falls back to the provider parts`() = runTest {
        val messageId = repository.recordInboundMms(
            address = recipient,
            body = "media",
            subject = null,
            timestampMillis = 1_000L,
            transactionId = "t",
            attachments = emptyList(),
            downloadState = DownloadState.RETRIEVED,
        )
        requireNotNull(messageId)
        val downloader = MmsDownloader(
            context,
            FakePartSource(FoundAttachment("p.jpg", "image/jpeg", jpegBytes())),
            MmsAutoRetriever(PendingRetriever(null), FakeApn("http://mmsc.example")),
        )

        val downloaded = downloader.download(repository, messageId)

        assertTrue(downloaded)
        val stored = repository.observeAttachments(messageId).first { it.size == 1 }
        assertTrue("stored name should keep the part name", stored.single().fileName.endsWith("p.jpg"))
    }

    @Test
    fun `a message without a provider part and without an mmsc cannot be downloaded`() = runTest {
        val messageId = repository.recordInboundMms(
            address = recipient,
            body = "gone",
            subject = null,
            timestampMillis = 1_000L,
            transactionId = "t",
            attachments = emptyList(),
        )
        requireNotNull(messageId)
        val downloader = MmsDownloader(
            context,
            EmptyPartSource(),
            MmsAutoRetriever(PendingRetriever(null), FakeApn(null)),
        )

        assertFalse(downloader.download(repository, messageId))
    }

    private suspend fun recordPendingNotification(contentLocation: String?): Long {
        val id = repository.recordInboundMms(
            address = recipient,
            body = "",
            subject = "subject",
            timestampMillis = 1_000L,
            transactionId = "tid",
            attachments = emptyList(),
            downloadState = DownloadState.PENDING,
            contentLocation = contentLocation,
        )
        return requireNotNull(id)
    }

    private suspend fun MessageRepository.getMessagesInConversation(): MessageEntity {
        val messages = observeMessages(1L).first()
        check(messages.size == 1) { "expected exactly one message row, got $messages" }
        return messages.first()
    }

    private fun jpegBytes() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    private class PendingRetriever(private val bytes: ByteArray?) : MmsRetriever {
        var requestedUrl: String? = null
        override fun get(url: String): ByteArray? {
            requestedUrl = url
            return bytes
        }
    }

    private class FakeApn(private val mmsc: String?) : MmscUrlResolver {
        override fun mmscUrl(): String? = mmsc
    }

    private class EmptyPartSource : MmsPartSource {
        override fun firstAttachment(address: String, sentAtMillis: Long): FoundAttachment? = null
    }

    private class FakePartSource(
        private val attachment: FoundAttachment?,
    ) : MmsPartSource {
        override fun firstAttachment(address: String, sentAtMillis: Long): FoundAttachment? = attachment
    }
}