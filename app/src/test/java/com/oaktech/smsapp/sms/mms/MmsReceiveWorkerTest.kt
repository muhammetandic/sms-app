package com.oaktech.smsapp.sms.mms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.MessageRepository
import com.oaktech.smsapp.data.local.SmsDatabase
import com.oaktech.smsapp.sms.block.BlockedSenders
import com.oaktech.smsapp.sms.receive.InboundMessage
import com.oaktech.smsapp.testing.RecordingTelephonyMessageWriter
import com.oaktech.smsapp.testing.inlineInMemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pre-fetch filter for an incoming MMS notification.
 *
 * A blocked sender or a spam-flagged thread must not cost a data session: the
 * body is only pulled from the MMSC when the message would actually be shown.
 * The predicate is exercised directly because the abrupt skip it causes (no
 * HTTP) is invisible in the store, which keeps the placeholder either way.
 */
@RunWith(RobolectricTestRunner::class)
class MmsReceiveWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

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
            RecordingTelephonyMessageWriter(),
        )
        DataGraph.overrideForTest(context, repository)
    }

    @After
    fun tearDown() {
        DataGraph.overrideForTest(context, null)
        database.close()
    }

    @Test
    fun `a blocked sender is filtered`() = runBlocking {
        BlockedSenders(context).add("+1 (555) 123-4567")

        assertTrue(MmsReceiveWorker.isFiltered(context, "+15551234567"))
    }

    @Test
    fun `an ordinary sender is not filtered`() = runBlocking {
        assertFalse(MmsReceiveWorker.isFiltered(context, "+15551234567"))
    }

    @Test
    fun `a conversation flagged as spam is filtered`() = runBlocking {
        val rowId = repository.recordInbound(
            InboundMessage("+15551234567", "buy now", 1L, parts = 1),
            partCount = 1,
        )
        val conversationId = repository.conversationIdForMessage(rowId!!)!!
        repository.setConversationSpam(conversationId, true)

        assertTrue(MmsReceiveWorker.isFiltered(context, "+15551234567"))
    }
}
