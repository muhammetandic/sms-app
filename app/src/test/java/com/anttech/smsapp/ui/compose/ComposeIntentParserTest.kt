package com.anttech.smsapp.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeIntentParserTest {

    @Test
    fun `smsto uri yields the recipient`() {
        assertEquals(listOf("+15551234567"), ComposeIntentParser.recipientsFrom("smsto:+15551234567"))
    }

    @Test
    fun `sms uri yields the recipient`() {
        assertEquals(listOf("+15551234567"), ComposeIntentParser.recipientsFrom("sms:+15551234567"))
    }

    @Test
    fun `percent encoded contact recipient is decoded`() {
        assertEquals(
            listOf("+90 553 067 66 91"),
            ComposeIntentParser.recipientsFrom("smsto:%2B90%20553%20067%2066%2091"),
        )
    }

    @Test
    fun `literal plus in recipient remains plus`() {
        assertEquals(
            listOf("+905530676691"),
            ComposeIntentParser.recipientsFrom("smsto:+905530676691"),
        )
    }

    @Test
    fun `mms schemes are no longer recognized`() {
        // MMS support was removed; an mms: URI must not be parsed as a recipient
        // by a parser that only knows about sms and smsto.
        assertEquals(emptyList<String>(), ComposeIntentParser.recipientsFrom("mms:+15551234567"))
        assertEquals(emptyList<String>(), ComposeIntentParser.recipientsFrom("mmsto:+15551234567"))
    }

    @Test
    fun `multiple recipients are split on commas`() {
        assertEquals(
            listOf("+15551234567", "+15557654321"),
            ComposeIntentParser.recipientsFrom("smsto:+15551234567,+15557654321"),
        )
    }

    @Test
    fun `recipients are trimmed and blanks dropped`() {
        assertEquals(
            listOf("+15551234567", "+15557654321"),
            ComposeIntentParser.recipientsFrom("smsto: +15551234567 , +15557654321 , "),
        )
    }

    @Test
    fun `a bare scheme means open a blank composer`() {
        assertEquals(emptyList<String>(), ComposeIntentParser.recipientsFrom("smsto:"))
    }

    @Test
    fun `null and blank input yield no recipients`() {
        assertEquals(emptyList<String>(), ComposeIntentParser.recipientsFrom(null))
        assertEquals(emptyList<String>(), ComposeIntentParser.recipientsFrom(""))
    }

    @Test
    fun `a non sms scheme is not treated as a recipient`() {
        // A viewer's content URI here would be nonsense as a phone number, and
        // texting it to the "recipient" is worse than ignoring it.
        assertEquals(emptyList<String>(), ComposeIntentParser.recipientsFrom("content://media/external/images/1"))
        assertEquals(emptyList<String>(), ComposeIntentParser.recipientsFrom("https://example.com/+15551234567"))
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertEquals(listOf("+15551234567"), ComposeIntentParser.recipientsFrom("SMSTO:+15551234567"))
    }

    @Test
    fun `a body parameter is extracted`() {
        assertEquals("hello there", ComposeIntentParser.bodyFrom("smsto:+15551234567?body=hello%20there"))
    }

    @Test
    fun `a body is read even without a recipient`() {
        assertEquals("hi", ComposeIntentParser.bodyFrom("smsto:?body=hi"))
    }

    @Test
    fun `a missing or empty body is null`() {
        assertNull(ComposeIntentParser.bodyFrom("smsto:+15551234567"))
        assertNull(ComposeIntentParser.bodyFrom("smsto:+15551234567?body="))
        assertNull(ComposeIntentParser.bodyFrom(null))
    }

    @Test
    fun `the body parameter is found among other query parameters`() {
        assertEquals(
            "second",
            ComposeIntentParser.bodyFrom("smsto:+15551234567?subject=first&body=second&x=1"),
        )
    }

    @Test
    fun `recipient and body can both be present`() {
        val uri = "smsto:+15551234567?body=on%20my%20way"

        assertEquals(listOf("15551234567").map { "+$it" }, ComposeIntentParser.recipientsFrom(uri))
        assertEquals("on my way", ComposeIntentParser.bodyFrom(uri))
    }

    @Test
    fun `a plus encoded in the body survives decoding`() {
        assertEquals("1+1=2", ComposeIntentParser.bodyFrom("smsto:+15551234567?body=1%2B1%3D2"))
    }
}
