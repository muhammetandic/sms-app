package com.anttech.smsapp.contacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactAddressNormalizerTest {

    @Test
    fun `formatting is stripped leaving digits`() {
        assertTrue(ContactAddressNormalizer.normalize("+1 (555) 123-4567") == "+15551234567")
        assertTrue(ContactAddressNormalizer.normalize("555-123-4567") == "5551234567")
        assertTrue(ContactAddressNormalizer.normalize("(555) 123 4567") == "5551234567")
    }

    @Test
    fun `a leading plus is preserved because it changes the meaning`() {
        assertTrue(ContactAddressNormalizer.normalize("+15551234567").startsWith("+"))
        assertFalse(ContactAddressNormalizer.normalize("15551234567").startsWith("+"))
    }

    @Test
    fun `a number with no digits normalises to empty`() {
        assertTrue(ContactAddressNormalizer.normalize("+").isEmpty())
        assertTrue(ContactAddressNormalizer.normalize("abc").isEmpty())
    }

    @Test
    fun `whitespace around a number is ignored`() {
        assertTrue(ContactAddressNormalizer.normalize("  +15551234567  ") == "+15551234567")
    }

    @Test
    fun `the same number in different formats could be the same contact`() {
        assertTrue(ContactAddressNormalizer.couldBeSame("+1 (555) 123-4567", "15551234567"))
        assertTrue(ContactAddressNormalizer.couldBeSame("5551234567", "+15551234567"))
    }

    @Test
    fun `different numbers are not the same contact`() {
        assertFalse(ContactAddressNormalizer.couldBeSame("+15551234567", "+15557654321"))
    }

    @Test
    fun `a shared suffix does not imply the same contact`() {
        // 5551234567 and 15551234567 differ, but a shorter number that is a
        // suffix of a longer one is ambiguous: +441234567890 ends with 1234567890.
        // Treated as a possible match only, never as a confirmed one.
        assertTrue(ContactAddressNormalizer.couldBeSame("1234567890", "+441234567890"))
        assertFalse(ContactAddressNormalizer.couldBeSame("999", "+15551234567"))
    }

    @Test
    fun `an empty side never matches`() {
        assertFalse(ContactAddressNormalizer.couldBeSame("", "+15551234567"))
        assertFalse(ContactAddressNormalizer.couldBeSame("+15551234567", "  "))
    }

    @Test
    fun `identical numbers match`() {
        assertTrue(ContactAddressNormalizer.couldBeSame("+15551234567", "+1 555 123 4567"))
    }
}