package com.oaktech.smsapp.sms.mms

import org.junit.Assert.assertEquals
import org.junit.Test

class MmsRoutingTest {

    @Test
    fun `plain text to one person stays on sms`() {
        assertEquals(Route.SMS, MmsRouting.route(recipientCount = 1, hasAttachments = false))
        assertEquals(Route.SMS, MmsRouting.route("+15550000001", hasAttachments = false))
    }

    @Test
    fun `an attachment always means mms`() {
        assertEquals(Route.MMS, MmsRouting.route(recipientCount = 1, hasAttachments = true))
        assertEquals(Route.MMS, MmsRouting.route("+15550000001", hasAttachments = true))
    }

    @Test
    fun `more than one recipient always means mms`() {
        assertEquals(Route.MMS, MmsRouting.route(recipientCount = 2, hasAttachments = false))
        assertEquals(Route.MMS, MmsRouting.route(recipientCount = 5, hasAttachments = false))
    }
}