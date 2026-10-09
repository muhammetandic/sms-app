package com.oaktech.smsapp.sms.send

import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.sms.mms.MmsPoster
import com.oaktech.smsapp.sms.mms.MmsSendCoordinator
import com.oaktech.smsapp.sms.mms.MmscUrlResolver
import com.oaktech.smsapp.testing.inlineInMemoryDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteAwareMessageSenderTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository

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
    fun `a single plain-text recipient goes over SMS`() = runTest {
        val smsCalls = mutableListOf<Pair<String, String>>()
        val sender = RouteAwareMessageSender(
            sms = { recipient, body -> smsCalls += recipient to body; SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT) },
            mms = MmsSendCoordinator(FakePoster(200), FakeApn("http://mmsc.example"), context),
        )

        val outcome = sender.send("+15550000001", null, "hello", attachments = emptyList())

        assertEquals(listOf("+15550000001" to "hello"), smsCalls)
        assertEquals(SendState.SENT, outcome.state)
    }

    @Test
    fun `a comma-joined group goes over MMS with every member`() = runTest {
        var smsCalled = false
        val poster = FakePoster(200)
        val sender = RouteAwareMessageSender(
            sms = { _, _ -> smsCalled = true; SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT) },
            mms = MmsSendCoordinator(poster, FakeApn("http://mmsc.example"), context),
        )

        val outcome = sender.send(
            "+15550000001, +15550000002",
            null,
            "group hello",
            attachments = emptyList(),
        )

        // A group has no single SMS recipient, so it must not touch the SMS leg.
        assertEquals(SendState.SENT, outcome.state)
        assertFalse(smsCalled)
        assertTrue("the PDU carried the group send", poster.lastPdu()!!.isNotEmpty())
        val row = repository.observeMessages(1L).first().single()
        assertTrue(row.isMms)
        assertEquals(MessageStatus.SENT, row.status)
    }

    @Test
    fun `an attachment forces MMS even for a single recipient`() = runTest {
        var smsCalled = false
        val poster = FakePoster(200)
        val sender = RouteAwareMessageSender(
            sms = { _, _ -> smsCalled = true; SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT) },
            mms = MmsSendCoordinator(poster, FakeApn("http://mmsc.example"), context),
        )
        val file = File.createTempFile("route-test", ".jpg")
        file.writeBytes(byteArrayOf(1, 2, 3))

        val outcome = sender.send(
            "+15550000001",
            null,
            "with photo",
            attachments = listOf(
                AttachmentPart(
                    fileName = "photo.jpg",
                    contentType = "image/jpeg",
                    filePath = file.absolutePath,
                    byteSize = file.length(),
                ),
            ),
        )

        assertEquals(SendState.SENT, outcome.state)
        assertFalse(smsCalled)
        assertTrue(poster.lastPdu()!!.isNotEmpty())
        val row = repository.observeMessages(1L).first().single()
        assertTrue(row.isMms)
        file.delete()
    }

    @Test
    fun `a subject rides the mms leg and reaches the stored row`() = runTest {
        val poster = FakePoster(200)
        val sender = RouteAwareMessageSender(
            sms = { _, _ -> SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT) },
            mms = MmsSendCoordinator(poster, FakeApn("http://mmsc.example"), context),
        )

        val outcome = sender.send(
            "+15550000001",
            "Dinner plans",
            "with photo",
            attachments = listOf(
                AttachmentPart(
                    fileName = "photo.jpg",
                    contentType = "image/jpeg",
                    filePath = File.createTempFile("route-subject", ".jpg")
                        .apply { writeBytes(byteArrayOf(1, 2, 3)) }
                        .also { it.deleteOnExit() }
                        .absolutePath,
                    byteSize = 3,
                ),
            ),
        )

        assertEquals(SendState.SENT, outcome.state)
        val row = repository.observeMessages(1L).first().single()
        assertTrue(row.isMms)
        assertEquals("Dinner plans", row.subject)
    }

    @Test
    fun `a blank subject is treated as none`() = runTest {
        val poster = FakePoster(200)
        val sender = RouteAwareMessageSender(
            sms = { _, _ -> SendOutcome(SendState.SENT, 0, 1, ResultCode.SENT) },
            mms = MmsSendCoordinator(poster, FakeApn("http://mmsc.example"), context),
        )

        val outcome = sender.send(
            "+15550000001",
            "   ",
            "hello group",
            attachments = listOf(
                AttachmentPart(
                    fileName = "photo.jpg",
                    contentType = "image/jpeg",
                    filePath = File.createTempFile("route-blank-subject", ".jpg")
                        .apply { writeBytes(byteArrayOf(1, 2, 3)) }
                        .also { it.deleteOnExit() }
                        .absolutePath,
                    byteSize = 3,
                ),
            ),
        )

        assertEquals(SendState.SENT, outcome.state)
        assertNull(repository.observeMessages(1L).first().single().subject)
    }

    private class FakePoster(private val status: Int) : MmsPoster {
        private var lastPosted: ByteArray? = null

        override fun post(mmscUrl: String, pdu: ByteArray): Int {
            lastPosted = pdu
            return status
        }

        fun lastPdu(): ByteArray? = lastPosted
    }

    private class FakeApn(private val mmsc: String?) : MmscUrlResolver {
        override fun mmscUrl(): String? = mmsc
    }
}