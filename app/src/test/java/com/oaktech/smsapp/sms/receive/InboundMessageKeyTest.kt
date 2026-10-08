package com.oaktech.smsapp.sms.receive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundMessageKeyTest {

    private fun message(
        address: String = "+15551234567",
        timestamp: Long = 1_700_000_000_000L,
        body: String = "hello",
    ) = InboundMessage(address = address, body = body, timestampMillis = timestamp, parts = 1)

    @Test
    fun `identical messages produce identical hashes`() {
        val first = InboundMessageKey.of(message())
        val second = InboundMessageKey.of(message())

        assertEquals(first.hash, second.hash)
    }

    @Test
    fun `different body produces different hash`() {
        val first = InboundMessageKey.of(message(body = "hello"))
        val second = InboundMessageKey.of(message(body = "hello!"))

        assertNotEquals(first.hash, second.hash)
    }

    @Test
    fun `different address produces different hash`() {
        val first = InboundMessageKey.of(message(address = "+15551234567"))
        val second = InboundMessageKey.of(message(address = "+15559999999"))

        assertNotEquals(first.hash, second.hash)
    }

    @Test
    fun `different timestamp produces different hash`() {
        val first = InboundMessageKey.of(message(timestamp = 1_700_000_000_000L))
        val second = InboundMessageKey.of(message(timestamp = 1_700_000_001_000L))

        assertNotEquals(first.hash, second.hash)
    }

    @Test
    fun `field boundaries are unambiguous`() {
        val first = InboundMessageKey.of(message(address = "+1555", body = "12345"))
        val second = InboundMessageKey.of(message(address = "+15551", body = "2345"))

        assertNotEquals(first.hash, second.hash)
    }

    @Test
    fun `hash is a stable lowercase hex string`() {
        val hash = InboundMessageKey.of(message()).hash

        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `part count does not affect identity`() {
        val single = InboundMessageKey.of(message().copy(parts = 1))
        val multipart = InboundMessageKey.of(message().copy(parts = 4))

        assertEquals(single.hash, multipart.hash)
    }
}