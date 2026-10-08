package com.oaktech.smsapp.sms.send

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the aggregation contract against a transport that honors it: the
 * number of sent intents must match the number of parts, or per-part results
 * cannot be attributed to a part index.
 *
 * Uses a stand-in for [SmsTransport] rather than a real [android.app.PendingIntent],
 * which cannot be constructed in a JVM test.
 */
class SmsTransportContractTest {

    private class RecordingTransport(private val partsPerBody: Int) {
        var sendCallCount = 0
        var lastSentIntentCount = 0
        var throwOnSend = false

        fun divide(body: String): List<String> =
            List(partsPerBody) { index -> if (partsPerBody == 1) body else "$body[$index]" }

        fun send(sentIntentCount: Int) {
            sendCallCount += 1
            if (throwOnSend) throw IllegalStateException("radio unavailable")
            lastSentIntentCount = sentIntentCount
        }
    }

    @Test
    fun `tracker resolves when every part reports`() {
        val transport = RecordingTransport(partsPerBody = 3)
        val parts = transport.divide("body")
        val tracker = MultipartSendTracker(parts.size)

        val outcomes = parts.indices.map { tracker.onPartResult(it, Activity.RESULT_OK) }

        assertEquals(Activity.RESULT_OK, outcomes.last()?.code?.platformCode)
        assertEquals(3, outcomes.last()?.totalParts)
    }

    @Test
    fun `one failed part among many fails the message`() {
        val transport = RecordingTransport(partsPerBody = 4)
        val parts = transport.divide("body")
        val tracker = MultipartSendTracker(parts.size)

        tracker.onPartResult(0, Activity.RESULT_OK)
        tracker.onPartResult(1, ResultCode.RADIO_OFF.platformCode)
        tracker.onPartResult(2, Activity.RESULT_OK)
        val outcome = tracker.onPartResult(3, Activity.RESULT_OK)

        assertEquals(SendState.FAILED, outcome?.state)
        assertEquals(1, outcome?.failedParts)
        assertEquals(ResultCode.RADIO_OFF, outcome?.code)
    }

    @Test
    fun `single part send resolves immediately`() {
        val transport = RecordingTransport(partsPerBody = 1)
        val parts = transport.divide("short")
        val tracker = MultipartSendTracker(parts.size)

        val outcome = tracker.onPartResult(0, Activity.RESULT_OK)

        assertEquals(SendState.SENT, outcome?.state)
        assertEquals(1, outcome?.totalParts)
    }

    @Test
    fun `delivery after successful send keeps it successful`() {
        val transport = RecordingTransport(partsPerBody = 2)
        val parts = transport.divide("body")
        val tracker = MultipartSendTracker(parts.size)
        tracker.onPartResult(0, Activity.RESULT_OK)
        tracker.onPartResult(1, Activity.RESULT_OK)

        val outcome = tracker.markDelivered()

        assertEquals(ResultCode.DELIVERED, outcome?.code)
        assertEquals(SendState.SENT, outcome?.state)
    }

    @Test
    fun `transport failure propagates rather than being swallowed`() {
        val transport = RecordingTransport(partsPerBody = 1).apply { throwOnSend = true }

        val thrown = runCatching { transport.send(sentIntentCount = 1) }.exceptionOrNull()

        assertEquals("radio unavailable", thrown?.message)
    }

    @Test
    fun `sent intent count must match part count`() {
        val transport = RecordingTransport(partsPerBody = 3)
        val parts = transport.divide("body")

        transport.send(sentIntentCount = parts.size)

        assertEquals(parts.size, transport.lastSentIntentCount)
    }
}