package com.anttech.smsapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressNormalizerTest {

    @Test
    fun `plain national number is kept as digits`() {
        assertEquals("5551234567", AddressNormalizer.normalize("5551234567"))
    }

    @Test
    fun `international number keeps its plus prefix`() {
        assertEquals("+15551234567", AddressNormalizer.normalize("+1 555 123 4567"))
    }

    @Test
    fun `formatting characters are stripped`() {
        assertEquals("+15551234567", AddressNormalizer.normalize("+1 (555) 123-4567"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("+15551234567", AddressNormalizer.normalize("  +1 555 123 4567  "))
    }

    @Test
    fun `empty input yields empty string`() {
        assertEquals("", AddressNormalizer.normalize("   "))
    }

    @Test
    fun `short code with no digits falls back to trimmed lowercase form`() {
        assertEquals("google", AddressNormalizer.conversationKey("Google"))
    }

    @Test
    fun `alphanumeric sender id is trimmed and lowercased`() {
        assertEquals("my-bank", AddressNormalizer.conversationKey("  My-Bank  "))
    }

    @Test
    fun `conversation key uses normalized form when digits are present`() {
        assertEquals("+15551234567", AddressNormalizer.conversationKey("+1 (555) 123-4567"))
    }

    @Test
    fun `same number in different formats yields one conversation key`() {
        val a = AddressNormalizer.conversationKey("+1 555 123 4567")
        val b = AddressNormalizer.conversationKey("+1(555)123-4567")
        val c = AddressNormalizer.conversationKey("+1-555-123-4567")

        assertTrue(a == b && b == c)
    }
}