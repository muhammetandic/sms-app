package com.oaktech.smsapp.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    @Test
    fun `ids differ for conversations a thousand apart`() {
        assertNotEquals(
            NotificationIds.forConversation(3L),
            NotificationIds.forConversation(1003L),
        )
        assertNotEquals(
            NotificationIds.forConversation(7L),
            NotificationIds.forConversation(1007L),
        )
    }

    @Test
    fun `ids stay within the positive int range`() {
        assertTrue(NotificationIds.forConversation(1L) > 0)
        assertTrue(NotificationIds.forConversation(0x1FFFFFL) > 0)
        assertTrue(NotificationIds.forConversation(0x1FFFFF + 5L) > 0)
        assertTrue(NotificationIds.forConversation(9_000_000_000L) > 0)
    }

    @Test
    fun `null conversation maps to a fixed slot`() {
        assertEquals(NotificationIds.forConversation(null), NotificationIds.forConversation(null))
        assertEquals(0, NotificationIds.requestSlot(null))
    }

    @Test
    fun `request slot is bounded for the action stride multiply`() {
        // The notifier multiplies the slot by its action stride; the product must
        // stay a positive Int for any conversation id.
        for (id in listOf(0L, 1L, 3L, 1003L, 0x1FFFFFL, 0x1FFFFF + 5L, 9_000_000_000L)) {
            val product = NotificationIds.requestSlot(id) * 4
            assertTrue("slot ${NotificationIds.requestSlot(id)} overflows after stride", product >= 0)
        }
    }
}