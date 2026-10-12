package com.anttech.smsapp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderColorsTest {

    /**
     * Icons are graphical objects, so WCAG asks for 3:1 against what they sit
     * on rather than the 4.5:1 body text needs.
     */
    @Test
    fun `folder tints read on both surfaces`() {
        for (color in listOf(SpamFolderColor, DraftsFolderColor)) {
            for (surface in listOf(PreviewLightColors.surface, PreviewDarkColors.surface)) {
                val ratio = contrastRatio(color, surface)
                assertTrue(
                    "contrast $ratio is below 3:1 for $color on $surface",
                    ratio >= 3.0,
                )
            }
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
