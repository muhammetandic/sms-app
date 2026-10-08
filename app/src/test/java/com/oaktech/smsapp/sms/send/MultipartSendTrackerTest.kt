package com.oaktech.smsapp.sms.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartSendTrackerTest {

    private val ok = android.app.Activity.RESULT_OK

    @Test
    fun `outcome is null until every part reports`() {
        val tracker = MultipartSendTracker(expectedParts = 3)

        assertNull(tracker.onPartResult(0, ok))
        assertNull(tracker.onPartResult(1, ok))
        assertEquals(SendState.SENT, tracker.onPartResult(2, ok)?.state)
    }

    @Test
    fun `parts reporting out of order still resolve`() {
        val tracker = MultipartSendTracker(expectedParts = 3)

        assertNull(tracker.onPartResult(2, ok))
        assertNull(tracker.onPartResult(0, ok))
        assertEquals(SendState.SENT, tracker.onPartResult(1, ok)?.state)
    }

    @Test
    fun `a single failed part fails the whole message`() {
        val tracker = MultipartSendTracker(expectedParts = 3)

        tracker.onPartResult(0, ok)
        tracker.onPartResult(1, android.app.Activity.RESULT_CANCELED)

        val outcome = tracker.onPartResult(2, ok)

        assertEquals(SendState.FAILED, outcome?.state)
        assertEquals(1, outcome?.failedParts)
        assertFalse(outcome?.isSuccess == true)
    }

    @Test
    fun `outcome reports how many parts failed`() {
        val tracker = MultipartSendTracker(expectedParts = 4)

        tracker.onPartResult(0, android.app.Activity.RESULT_CANCELED)
        tracker.onPartResult(1, android.app.Activity.RESULT_CANCELED)
        tracker.onPartResult(2, ok)

        val outcome = tracker.onPartResult(3, ok)

        assertEquals(SendState.FAILED, outcome?.state)
        assertEquals(2, outcome?.failedParts)
        assertEquals(4, outcome?.totalParts)
    }

    @Test
    fun `duplicate result for same part is ignored`() {
        val tracker = MultipartSendTracker(expectedParts = 2)

        tracker.onPartResult(0, ok)

        assertNull(tracker.onPartResult(0, ok))
        assertEquals(SendState.SENT, tracker.onPartResult(1, ok)?.state)
    }

    @Test
    fun `out of bounds part index is ignored`() {
        val tracker = MultipartSendTracker(expectedParts = 2)

        assertNull(tracker.onPartResult(5, ok))
        assertNull(tracker.onPartResult(-1, ok))
        assertNull(tracker.onPartResult(0, ok))
    }

    @Test
    fun `fatal failure resolves immediately without every part reporting`() {
        val tracker = MultipartSendTracker(expectedParts = 4)

        val outcome = tracker.onFatalFailure(ResultCode.NO_SERVICE.platformCode)

        assertEquals(SendState.FAILED, outcome?.state)
        assertEquals(4, outcome?.totalParts)
        assertTrue(tracker.isResolved)
    }

    @Test
    fun `late part result after resolution does not change outcome`() {
        val tracker = MultipartSendTracker(expectedParts = 2)
        tracker.onFatalFailure(ResultCode.RADIO_OFF.platformCode)

        val outcome = tracker.onPartResult(0, ok)

        assertEquals(SendState.FAILED, outcome?.state)
    }

    @Test
    fun `delivery report succeeds for a partially reported message`() {
        val tracker = MultipartSendTracker(expectedParts = 3)
        tracker.onPartResult(0, ok)

        val outcome = tracker.markDelivered()

        assertEquals(SendState.SENT, outcome?.state)
        assertTrue(outcome?.isSuccess == true)
    }

    @Test
    fun `delivery report does not mask an earlier failure`() {
        val tracker = MultipartSendTracker(expectedParts = 3)
        tracker.onPartResult(0, android.app.Activity.RESULT_CANCELED)

        val outcome = tracker.markDelivered()

        assertEquals(SendState.FAILED, outcome?.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non positive part count is rejected`() {
        MultipartSendTracker(expectedParts = 0)
    }

    @Test
    fun `unrecognized platform code maps to unknown failure`() {
        val tracker = MultipartSendTracker(expectedParts = 1)

        val outcome = tracker.onPartResult(0, 9999)

        assertEquals(SendState.FAILED, outcome?.state)
        assertEquals(ResultCode.UNKNOWN, outcome?.code)
    }
}