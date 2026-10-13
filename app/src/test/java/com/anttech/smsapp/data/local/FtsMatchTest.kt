package com.anttech.smsapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsMatchTest {

    @Test
    fun `single word becomes a prefix term`() {
        assertEquals("hello*", ftsMatchExpression("hello"))
    }

    @Test
    fun `only the last word is a prefix, earlier words are exact phrases`() {
        assertEquals("\"hello\" \"dear\" wor*", ftsMatchExpression("hello dear wor"))
    }

    @Test
    fun `punctuation is split out rather than passed to the query language`() {
        assertEquals("\"90\" \"555\" 123*", ftsMatchExpression("+90 (555) 123"))
    }

    @Test
    fun `a query with no letters or digits has no expression`() {
        assertNull(ftsMatchExpression("   ...?!  "))
        assertNull(ftsMatchExpression(""))
    }

    @Test
    fun `an uppercase operator word is quoted so it cannot become an operator`() {
        // MATCH 'OR' is a syntax error; MATCH '"OR"' searches for the word.
        assertEquals("\"OR\" \"AND\" te*", ftsMatchExpression("OR AND te"))
    }

    @Test
    fun `turkish letters are kept as word characters`() {
        assertEquals("\"şeker\" gib*", ftsMatchExpression("şeker gib"))
    }
}
