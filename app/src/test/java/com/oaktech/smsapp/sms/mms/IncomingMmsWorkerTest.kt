package com.oaktech.smsapp.sms.mms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The handoff from the WAP push broadcast to WorkManager.
 *
 * The MMSC legs themselves are covered by [HttpMmsTransportTest] against a
 * loopback socket; here the PDU is one the parser rejects, so the worker
 * exercises the whole queue (file in, file out) without touching the network.
 */
@RunWith(RobolectricTestRunner::class)
class IncomingMmsWorkerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `the pdu is written to the queue and the worker consumes it`() {
        assertTrue(IncomingMmsWorker.enqueue(context, byteArrayOf(0x01, 0x02)))

        val info = awaitFinishedWork()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertTrue("queue directory should be empty once handled", queueFiles().isEmpty())
    }

    @Test
    fun `pushes queue up rather than replacing each other`() {
        assertTrue(IncomingMmsWorker.enqueue(context, byteArrayOf(0x01)))
        assertTrue(IncomingMmsWorker.enqueue(context, byteArrayOf(0x02)))

        val infos = workManager.getWorkInfosForUniqueWork(IncomingMmsWorker.UNIQUE_WORK).get()
        assertEquals(2, infos.size)
    }

    private fun awaitFinishedWork(): WorkInfo {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val info = workManager.getWorkInfosForUniqueWork(IncomingMmsWorker.UNIQUE_WORK)
                .get()
                .firstOrNull()
            if (info != null && info.state.isFinished) return info
            Thread.sleep(POLL_MILLIS)
        }
        fail("queued MMS work did not finish")
        error("unreachable")
    }

    private fun queueFiles(): List<File> =
        File(context.cacheDir, IncomingMmsWorker.QUEUE_DIRECTORY).listFiles()?.toList().orEmpty()

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 25L
    }
}
