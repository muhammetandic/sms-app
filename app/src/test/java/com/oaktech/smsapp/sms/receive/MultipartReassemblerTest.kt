package com.oaktech.smsapp.sms.receive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultipartReassemblerTest {

    private fun part(address: String, body: String, timestamp: Long = 1_000L) =
        SmsPart(address, body, timestamp)

    @Test
    fun `single part becomes one message`() {
        val result = MultipartReassembler.reassemble(listOf(part("+15551234567", "hello")))

        assertEquals("hello", result?.body)
        assertEquals("+15551234567", result?.address)
        assertEquals(1, result?.parts)
    }

    @Test
    fun `parts are joined in order without separators`() {
        val result = MultipartReassembler.reassemble(
            listOf(
                part("+15551234567", "This is a long "),
                part("+15551234567", "message split "),
                part("+15551234567", "into parts."),
            ),
        )

        assertEquals("This is a long message split into parts.", result?.body)
        assertEquals(3, result?.parts)
    }

    @Test
    fun `parts are trimmed of surrounding whitespace in address`() {
        val result = MultipartReassembler.reassemble(listOf(part("  +15551234567  ", "hi")))

        assertEquals("+15551234567", result?.address)
    }

    @Test
    fun `empty part list is rejected`() {
        assertNull(MultipartReassembler.reassemble(emptyList()))
    }

    @Test
    fun `blank address is rejected`() {
        assertNull(MultipartReassembler.reassemble(listOf(part("   ", "hi"))))
    }

    @Test
    fun `empty body part is rejected rather than silently dropped`() {
        val result = MultipartReassembler.reassemble(
            listOf(part("+1555", "first"), part("+1555", "")),
        )

        assertNull(result)
    }

    @Test
    fun `parts with differing timestamps are rejected as not one message`() {
        val result = MultipartReassembler.reassemble(
            listOf(part("+1555", "a", 1_000L), part("+1555", "b", 2_000L)),
        )

        assertNull(result)
    }

    @Test
    fun `implausible part count is rejected`() {
        val parts = (1..21).map { part("+1555", "chunk$it") }

        assertNull(MultipartReassembler.reassemble(parts))
    }

    @Test
    fun `alphanumeric sender id is preserved`() {
        val result = MultipartReassembler.reassemble(listOf(part("Google", "Your code is 1234")))

        assertEquals("Google", result?.address)
    }
}