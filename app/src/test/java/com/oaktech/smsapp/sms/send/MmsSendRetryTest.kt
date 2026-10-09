package com.oaktech.smsapp.sms.send

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.MessageStatus
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.sms.mms.MmsPoster
import com.oaktech.smsapp.sms.mms.MmsSendCoordinator
import com.oaktech.smsapp.sms.mms.MmscUrlResolver
import com.oaktech.smsapp.testing.RecordingTelephonyMessageWriter
import com.oaktech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The retry decision and the work handoff, kept apart from the coordinator test
 * because the interesting part here is what a retry must not do: send a message
 * that is no longer failed, or add a second row for one that is.
 */
@RunWith(RobolectricTestRunner::class)
class MmsSendRetryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    private lateinit var writer: RecordingTelephonyMessageWriter

    @Before
    fun setUp() {
        database = inlineInMemoryDatabase(context, SmsDatabase::class.java)
        writer = RecordingTelephonyMessageWriter()
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
            database.attachmentDao(),
            writer,
        )
        DataGraph.overrideForTest(context, repository)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        DataGraph.overrideForTest(context, null)
        database.close()
    }

    private suspend fun failedMms(): Long {
        val id = repository.recordOutgoingMms(
            recipients = listOf("+15550000001"),
            subject = null,
            body = "retry me",
            sentAt = 1_700_000_000_000L,
            attachments = emptyList(),
        )
        repository.updateOutgoingError(id, ResultCode.MMS_NETWORK_UNAVAILABLE.platformCode)
        return id
    }

    @Test
    fun `an attempt marks the message sent when the mmsc accepts it`() = runTest {
        val id = failedMms()
        val coordinator = MmsSendCoordinator(FakePoster(200), FakeApn("http://mmsc.example"), context)

        val result = MmsSendRetry.retry(context, id, coordinator)

        assertEquals(MmsSendRetry.RetryResult.SENT, result)
        assertEquals(MessageStatus.SENT, repository.getMessage(id)!!.status)
        assertEquals(1, writer.insertedSentMms.size)
    }

    @Test
    fun `an attempt that still fails leaves the row failed and asks to retry`() = runTest {
        val id = failedMms()
        val coordinator = MmsSendCoordinator(FakePoster(500), FakeApn("http://mmsc.example"), context)

        val result = MmsSendRetry.retry(context, id, coordinator)

        assertEquals(MmsSendRetry.RetryResult.RETRY, result)
        assertEquals(MessageStatus.FAILED, repository.getMessage(id)!!.status)
    }

    @Test
    fun `a message that is no longer failed is abandoned`() = runTest {
        val id = repository.recordOutgoingMms(
            recipients = listOf("+15550000001"),
            subject = null,
            body = "already sent",
            sentAt = 1L,
            attachments = emptyList(),
        )
        repository.updateOutgoingStatus(id, MessageStatus.SENT)
        val coordinator = MmsSendCoordinator(FakePoster(200), FakeApn("http://mmsc.example"), context)

        assertEquals(MmsSendRetry.RetryResult.ABANDON, MmsSendRetry.retry(context, id, coordinator))
    }

    @Test
    fun `scheduling a retry is idempotent for a message`() {
        MmsSendRetry.schedule(context, 42L)
        MmsSendRetry.schedule(context, 42L)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(MmsSendRetry.uniqueWorkName(42L))
            .get()
        assertEquals(1, infos.size)
    }

    private class FakePoster(private val status: Int) : MmsPoster {
        override fun post(mmscUrl: String, pdu: ByteArray): Int = status
    }

    private class FakeApn(private val mmsc: String?) : MmscUrlResolver {
        override fun mmscUrl(): String? = mmsc
    }
}
