package com.anttech.smsapp.sms.block

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BlockedSendersTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `starts with no blocked senders`() {
        val blocks = BlockedSenders(context)
        assertFalse(blocks.isBlocked("+15551234567"))
        assertTrue(blocks.blocks.value.isEmpty())
    }

    @Test
    fun `a blocked sender is reported across instances`() {
        BlockedSenders(context).add("+15551234567")

        assertTrue(BlockedSenders(context).isBlocked("+15551234567"))
    }

    @Test
    fun `formatting differences still match the same sender`() {
        BlockedSenders(context).add("+1 (555) 123 4567")

        assertTrue(BlockedSenders(context).isBlocked("+15551234567"))
    }

    @Test
    fun `removing a sender unblocks them`() {
        val blocks = BlockedSenders(context)
        blocks.add("+15551234567")
        blocks.remove("+15551234567")

        assertFalse(BlockedSenders(context).isBlocked("+15551234567"))
    }

    @Test
    fun `duplicate adds are no-ops and stored once`() {
        val blocks = BlockedSenders(context)
        assertTrue(blocks.add("+15551234567"))
        assertFalse(blocks.add("+15551234567"))

        assertEquals(setOf("+15551234567"), BlockedSenders(context).blocks.value)
    }

    @Test
    fun `empty addresses cannot be blocked`() {
        val blocks = BlockedSenders(context)
        assertFalse(blocks.add(""))
        assertFalse(blocks.add("   "))

        assertTrue(BlockedSenders(context).blocks.value.isEmpty())
    }

    @Test
    fun `alphanumeric sender ids are matched case-insensitively`() {
        val blocks = BlockedSenders(context)
        assertTrue(blocks.add("NOTIFY"))

        assertTrue(BlockedSenders(context).isBlocked("notify"))
    }

    @Test
    fun `a number is stored in canonical form, not as typed`() {
        BlockedSenders(context).add("+1 (555) 123 4567")

        // The stored entry is the canonical key, so the same sender cannot be
        // added twice under two spellings and a later match uses the number a
        // thread would group it as.
        assertEquals(setOf("+15551234567"), BlockedSenders(context).blocks.value)
    }

    @Test
    fun `an alphanumeric sender is stored lowercased`() {
        BlockedSenders(context).add("Notify")

        assertEquals(setOf("notify"), BlockedSenders(context).blocks.value)
    }
}