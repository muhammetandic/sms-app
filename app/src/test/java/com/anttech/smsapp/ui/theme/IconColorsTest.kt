package com.anttech.smsapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconColorsTest {

    /**
     * Every icon role has to resolve to the scheme role it names, in both
     * schemes. This is what keeps the palette honest: an edit that swaps a role
     * for a literal, or points two roles at the same colour, fails here rather
     * than quietly repainting one screen.
     */
    @Test
    fun `icon roles follow the scheme`() {
        for ((name, scheme) in schemes()) {
            assertEquals("$name command", scheme.onSurface, scheme.iconCommand)
            assertEquals("$name indicator", scheme.onSurfaceVariant, scheme.iconIndicator)
            assertEquals("$name selected", scheme.primary, scheme.iconSelected)
        }
    }

    /**
     * Icons are graphical objects, so WCAG asks for 3:1 against what they sit
     * on rather than the 4.5:1 body text needs. The command and indicator roles
     * were already covered by the paired-roles test; selection tints the toolbar
     * with `primary` on the app bar's surface, which is new here.
     */
    @Test
    fun `icon roles read on the surface they sit on`() {
        for ((name, scheme) in schemes()) {
            for ((role, color) in iconRoles(scheme)) {
                val ratio = contrastRatio(color, scheme.surface)
                assertTrue(
                    "$name $role contrast $ratio is below 3:1 on the surface",
                    ratio >= 3.0,
                )
            }
        }
    }

    private fun schemes(): List<Pair<String, ColorScheme>> =
        listOf("light" to PreviewLightColors, "dark" to PreviewDarkColors)

    private fun iconRoles(scheme: ColorScheme): List<Pair<String, Color>> = listOf(
        "command" to scheme.iconCommand,
        "indicator" to scheme.iconIndicator,
        "selected" to scheme.iconSelected,
    )

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
