package com.oaktech.smsapp.sms.send

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the platform result-code mapping. A wrong value here mislabels failed
 * sends as successful, which is the kind of bug that only shows up as messages
 * quietly never arriving.
 */
class ResultCodeTest {

    @Test
    fun `success maps to result ok`() {
        assertEquals(ResultCode.SENT, ResultCode.fromPlatformCode(Activity.RESULT_OK))
    }

    @Test
    fun `generic failure maps to code one`() {
        assertEquals(ResultCode.GENERIC_FAILURE, ResultCode.fromPlatformCode(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
        assertEquals(1, ResultCode.GENERIC_FAILURE.platformCode)
    }

    @Test
    fun `radio off is code two`() {
        assertEquals(2, ResultCode.RADIO_OFF.platformCode)
    }

    @Test
    fun `null pdu is code three`() {
        assertEquals(3, ResultCode.NULL_PDU.platformCode)
    }

    @Test
    fun `no service is code four`() {
        assertEquals(4, ResultCode.NO_SERVICE.platformCode)
    }

    @Test
    fun `limit exceeded is code five`() {
        assertEquals(5, ResultCode.LIMIT_EXCEEDED.platformCode)
    }

    @Test
    fun `no default sms app is code thirty two`() {
        assertEquals(SmsManager.RESULT_NO_DEFAULT_SMS_APP, ResultCode.NO_DEFAULT_SMS_APP.platformCode)
        assertEquals(32, ResultCode.NO_DEFAULT_SMS_APP.platformCode)
    }

    @Test
    fun `only result ok counts as success`() {
        assertFalse(ResultCode.NONE.isFailure.not())
        assertTrue(ResultCode.RADIO_OFF.isFailure)
        assertTrue(ResultCode.NO_SERVICE.isFailure)
        assertFalse(ResultCode.SENT.isFailure)
    }

    @Test
    fun `zero is not treated as success`() {
        // RESULT_CANCELED and RESULT_ERROR_NONE both deliver 0.
        val code = ResultCode.fromPlatformCode(0)

        assertTrue(code.isFailure)
    }

    @Test
    fun `unknown code is a failure rather than a silent success`() {
        val code = ResultCode.fromPlatformCode(4242)

        assertEquals(ResultCode.UNKNOWN, code)
        assertTrue(code.isFailure)
    }

    @Test
    fun `result cancelled is code twenty three`() {
        assertEquals(23, ResultCode.CANCELLED.platformCode)
    }

    @Test
    fun `no two codes claim the same platform value`() {
        val duplicates = ResultCode.entries.groupBy { it.platformCode }.filterValues { it.size > 1 }

        assertEquals(emptyList<Int>(), duplicates.keys.toList())
    }
}