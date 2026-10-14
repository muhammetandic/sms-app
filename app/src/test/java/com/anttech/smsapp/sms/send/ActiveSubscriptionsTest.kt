package com.anttech.smsapp.sms.send

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveSubscriptionsTest {

    @Test
    fun `label prefers displayName when present`() {
        val sub = SmsSubscription(1, "Turkcell", "Turkcell TR")
        assertEquals("Turkcell", sub.label)
    }

    @Test
    fun `label falls back to carrierName when displayName is blank`() {
        val sub = SmsSubscription(2, "  ", "Vodafone")
        assertEquals("Vodafone", sub.label)
    }

    @Test
    fun `label falls back to SIM id when both displayName and carrierName are blank`() {
        val sub = SmsSubscription(3, "", "")
        assertEquals("SIM 3", sub.label)
    }

    @Test
    fun `label falls back to SIM id when carrierName is null and displayName is whitespace`() {
        val sub = SmsSubscription(4, "   ", null)
        assertEquals("SIM 4", sub.label)
    }
}
