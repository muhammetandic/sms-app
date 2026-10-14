package com.anttech.smsapp.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactLabelTest {

    @Test
    fun `a stored group address splits into its recipients`() {
        assertEquals(
            listOf("+15551234567", "+15557654321"),
            "+15551234567, +15557654321".splitAddresses(),
        )
    }

    @Test
    fun `blank entries are dropped when splitting`() {
        assertEquals(listOf("+15551234567"), "+15551234567,  ,".splitAddresses())
        assertEquals(emptyList<String>(), "   ".splitAddresses())
    }

    @Test
    fun `a group whose recipients all resolve reads as names`() {
        assertEquals(
            "John Doe, Ayşe Yılmaz",
            joinContactLabels(
                listOf("+15551234567" to "John Doe", "+15557654321" to "Ayşe Yılmaz"),
            ),
        )
    }

    @Test
    fun `a single resolved recipient is just its name`() {
        assertEquals("John Doe", joinContactLabels(listOf("+15551234567" to "John Doe")))
    }

    @Test
    fun `an unresolved recipient keeps its number beside the resolved ones`() {
        assertEquals(
            "John Doe, +15557654321",
            joinContactLabels(listOf("+15551234567" to "John Doe", "+15557654321" to null)),
        )
    }

    @Test
    fun `a group where nothing resolves has no label`() {
        assertNull(
            joinContactLabels(listOf("+15551234567" to null, "+15557654321" to null)),
        )
    }

    @Test
    fun `an empty address has no label`() {
        assertNull(joinContactLabels(emptyList()))
    }
}
