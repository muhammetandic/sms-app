package com.anttech.smsapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconColorsTest {

    /**
     * The neutral role is the only one without a hue, so it must keep following
     * the scheme rather than quietly turning into a literal.
     */
    @Test
    fun `neutral follows the scheme`() {
        for ((name, scheme) in schemes()) {
            assertEquals("$name neutral", scheme.onSurfaceVariant, scheme.iconNeutral)
        }
    }

    /**
     * Icons are graphical objects, so WCAG asks for 3:1 against what they sit
     * on rather than the 4.5:1 body text needs. The hues are fixed mid-tones, so
     * each has to clear that bar against both the light and the dark surface,
     * not just the one it was picked on.
     */
    @Test
    fun `every hue reads on both surfaces`() {
        for ((name, scheme) in schemes()) {
            for ((role, color) in hues(scheme)) {
                for ((surfaceName, surface) in surfaces()) {
                    val ratio = contrastRatio(color, surface)
                    assertTrue(
                        "$name $role contrast $ratio is below 3:1 on the $surfaceName surface",
                        ratio >= 3.0,
                    )
                }
            }
        }
    }

    /**
     * The point of the palette is that the icons look different. Spam and delete
     * deliberately share the destructive red, drafts and unread the blue, and
     * restore and send the green; pin and mute each stand alone. So there should
     * be five distinct hues, and neutral must not be one of them.
     */
    @Test
    fun `the palette stays distinct`() {
        val scheme = PreviewLightColors
        assertEquals("spam and delete share red", scheme.iconSpam, scheme.iconDelete)
        assertEquals("drafts and unread share blue", scheme.iconDrafts, scheme.iconUnread)
        assertEquals("restore and send share green", scheme.iconRestore, scheme.iconSend)

        val distinct = setOf(
            scheme.iconSpam,
            scheme.iconPin,
            scheme.iconMute,
            scheme.iconDrafts,
            scheme.iconRestore,
        )
        assertEquals("spam/pin/mute/drafts/restore must all differ", 5, distinct.size)
        assertTrue("neutral must not double as a hue", scheme.iconNeutral !in distinct)
    }

    private fun schemes(): List<Pair<String, ColorScheme>> =
        listOf("light" to PreviewLightColors, "dark" to PreviewDarkColors)

    private fun surfaces(): List<Pair<String, Color>> =
        listOf("light" to PreviewLightColors.surface, "dark" to PreviewDarkColors.surface)

    private fun hues(scheme: ColorScheme): List<Pair<String, Color>> = listOf(
        "spam" to scheme.iconSpam,
        "delete" to scheme.iconDelete,
        "pin" to scheme.iconPin,
        "mute" to scheme.iconMute,
        "drafts" to scheme.iconDrafts,
        "unread" to scheme.iconUnread,
        "restore" to scheme.iconRestore,
        "send" to scheme.iconSend,
    )

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
