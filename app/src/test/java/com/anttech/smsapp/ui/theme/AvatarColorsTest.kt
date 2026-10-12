package com.anttech.smsapp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarColorsTest {

    @Test
    fun `white initials stay legible on every badge`() {
        for (color in AvatarColors) {
            val ratio = contrastRatio(color, OnAvatarColor)
            assertTrue(
                "contrast $ratio is below 4.5:1 for $color",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `a key keeps its colour`() {
        assertEquals(avatarColorFor("+15551234567"), avatarColorFor("+15551234567"))
    }

    @Test
    fun `neighbouring senders do not all share a colour`() {
        // A run of phone numbers is the realistic worst case: they share a long
        // prefix, so a hash that only used the low bits would collapse them.
        val used = (1..200).map { avatarColorFor("+1555000$it") }.toSet()

        assertTrue("only ${used.size} of ${AvatarColors.size} colours used", used.size >= AvatarColors.size - 2)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
