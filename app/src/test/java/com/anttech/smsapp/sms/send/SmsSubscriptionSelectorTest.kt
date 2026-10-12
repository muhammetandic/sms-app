package com.anttech.smsapp.sms.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSubscriptionSelectorTest {

    @Test
    fun `no preference falls back to the platform default`() {
        assertEquals(7, SmsSubscriptionSelector.effectiveSubscriptionId(null, 7))
    }

    @Test
    fun `an invalid preference falls back to the platform default`() {
        assertEquals(7, SmsSubscriptionSelector.effectiveSubscriptionId(-1, 7))
        assertEquals(7, SmsSubscriptionSelector.effectiveSubscriptionId(0, 7))
    }

    @Test
    fun `a valid preference wins over the platform default`() {
        assertEquals(5, SmsSubscriptionSelector.effectiveSubscriptionId(5, 7))
    }

    @Test
    fun `only positive ids are valid subscription choices`() {
        assertFalse(SmsSubscriptionSelector.isValidSubscriptionId(-2))
        assertFalse(SmsSubscriptionSelector.isValidSubscriptionId(-1))
        assertFalse(SmsSubscriptionSelector.isValidSubscriptionId(0))
        assertTrue(SmsSubscriptionSelector.isValidSubscriptionId(1))
    }
}