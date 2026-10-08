package com.oaktech.smsapp.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.content.res.Resources.Theme
import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Dark mode is driven by the system setting, so the failure mode is a resource
 * or theme that only ever describes light: the app renders a dark Compose scheme
 * inside a light platform window, and the launch frame flashes white on every
 * cold start.
 *
 * These tests assert the platform side, which lives in `themes.xml` and
 * `colors.xml` and is therefore invisible to anything Compose renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeNightTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun configured(nightMode: Int): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            // UI_MODE_NIGHT_SHIFT is not public, so it is restated here.
            this.uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (nightMode shl UI_MODE_NIGHT_SHIFT)
        }
        return context.createConfigurationContext(configuration)
    }

    @Test
    fun `window background is light by default`() {
        val background = configured(Configuration.UI_MODE_NIGHT_NO)
            .getColor(R.color.window_background)

        assertEquals(0xFFFBFDF8.toInt(), background)
    }

    @Test
    fun `window background is dark in night mode`() {
        val background = configured(Configuration.UI_MODE_NIGHT_YES)
            .getColor(R.color.window_background)

        assertEquals(0xFF101410.toInt(), background)
        assertTrue(
            "a night launch frame must not be the light surface",
            background != 0xFFFBFDF8.toInt(),
        )
    }

    /**
     * The light and dark windows have to be genuinely different, not two names
     * for the same colour. Asserting against the literals above is what catches
     * a copy-paste that leaves both variants identical.
     */
    @Test
    fun `the two window backgrounds differ`() {
        val light = configured(Configuration.UI_MODE_NIGHT_NO).getColor(R.color.window_background)
        val dark = configured(Configuration.UI_MODE_NIGHT_YES).getColor(R.color.window_background)

        assertNotEquals(light, dark)
    }

    /**
     * WCAG contrast ratio between two colours, from 1 (identical) to 21.
     *
     * Direction is deliberately not asserted: Material 3 legitimately inverts a
     * role's lightness between schemes, so `onPrimary` is white on the dark
     * green of the light scheme and near-black on the pale green of the dark
     * one. What has to hold in both is that the pair is readable.
     */
    private fun contrastRatio(
        foreground: androidx.compose.ui.graphics.Color,
        background: androidx.compose.ui.graphics.Color,
    ): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun assertReadable(
        what: String,
        foreground: androidx.compose.ui.graphics.Color,
        background: androidx.compose.ui.graphics.Color,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$what has a contrast ratio of $ratio, under the 4.5 that body text needs",
            ratio >= 4.5f,
        )
    }

    @Test
    fun `the dark scheme is dark where it should be`() {
        val dark = PreviewDarkColors

        // Only the surfaces are asserted by lightness. The text roles are
        // checked for contrast below, which is what actually matters.
        assertTrue("surface must be dark", dark.surface.luminance() < 0.2f)
        assertTrue("background must be dark", dark.background.luminance() < 0.2f)
        assertTrue("onSurface must be light", dark.onSurface.luminance() > 0.5f)
        assertTrue("onBackground must be light", dark.onBackground.luminance() > 0.5f)
    }

    @Test
    fun `the light scheme is light where it should be`() {
        val light = PreviewLightColors

        assertTrue("surface must be light", light.surface.luminance() > 0.8f)
        assertTrue("background must be light", light.background.luminance() > 0.8f)
        assertTrue("onSurface must be dark", light.onSurface.luminance() < 0.5f)
    }

    /**
     * Every foreground/background pair the UI actually renders has to stay
     * readable, in both schemes. A role copied from the wrong scheme is the
     * failure this catches: it renders, it just cannot be read.
     */
    @Test
    fun `paired roles stay legible in both schemes`() {
        for ((name, scheme) in listOf("dark" to PreviewDarkColors, "light" to PreviewLightColors)) {
            assertReadable("$name onSurface on surface", scheme.onSurface, scheme.surface)
            assertReadable(
                "$name onSurfaceVariant on surface",
                scheme.onSurfaceVariant,
                scheme.surface,
            )
            assertReadable("$name onPrimary on primary", scheme.onPrimary, scheme.primary)
            assertReadable(
                "$name onPrimaryContainer on primaryContainer",
                scheme.onPrimaryContainer,
                scheme.primaryContainer,
            )
            assertReadable(
                "$name onSecondaryContainer on secondaryContainer",
                scheme.onSecondaryContainer,
                scheme.secondaryContainer,
            )
            assertReadable(
                "$name onTertiaryContainer on tertiaryContainer",
                scheme.onTertiaryContainer,
                scheme.tertiaryContainer,
            )
            assertReadable(
                "$name onErrorContainer on errorContainer",
                scheme.onErrorContainer,
                scheme.errorContainer,
            )
            // Badge pairs onError on error (its container is colorScheme.error,
            // confirmed from BadgeTokens rather than assumed), the avatar uses
            // onSecondaryContainer on secondaryContainer, and the incoming
            // message bubbles sit on surfaceContainerHigh.
            assertReadable("$name onError on error", scheme.onError, scheme.error)
            assertReadable(
                "$name onSecondaryContainer on surfaceContainerHigh",
                scheme.onSecondaryContainer,
                scheme.surfaceContainerHigh,
            )
            assertReadable(
                "$name onSurface on surfaceContainerHigh",
                scheme.onSurface,
                scheme.surfaceContainerHigh,
            )
        }
    }

    /**
     * Resolves an attribute out of the theme that would actually be applied.
     *
     * The colour tests above cannot see a missing `values-night/themes.xml`:
     * `windowBackground` points at the night-qualified *colour*, which stays
     * dark even when the night *theme* falls back to the light one. What is
     * actually lost is the status bar form, since the light platform theme
     * asks for dark icons over a now-dark window.
     */
    private fun themed(nightMode: Int): Theme {
        val themedContext = configured(nightMode)
        return ContextThemeWrapper(themedContext, R.style.Theme_SmsApp).theme
    }

    /**
     * `obtainStyledAttributes` rather than `Theme.hasValue`/`getValue`: those are
     * not part of the public `Resources.Theme` API on this SDK level, and a
     * typed array also tells an absent attribute from an explicit `false`,
     * which matters here because "unset" is the bug.
     */
    private fun Theme.lightStatusBar(nightMode: Int): Boolean {
        val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar))
        attributes.use { return it.getBoolean(0, false) }
    }

    @Test
    fun `the platform theme asks for dark status bar icons in day mode`() {
        assertTrue(
            "the day theme must request dark status bar icons over a light window",
            themed(Configuration.UI_MODE_NIGHT_NO).lightStatusBar(Configuration.UI_MODE_NIGHT_NO),
        )
    }

    @Test
    fun `the platform theme asks for light status bar icons in night mode`() {
        assertFalse(
            "the night theme must not request dark status bar icons, or they vanish " +
                "over the dark window before Compose takes over",
            themed(Configuration.UI_MODE_NIGHT_YES).lightStatusBar(Configuration.UI_MODE_NIGHT_YES),
        )
    }

    /**
     * The distinguishing pair: the two themes must disagree about the status bar
     * form. This is the assertion that fails when `values-night/themes.xml` is
     * deleted, since a missing night theme silently inherits the light one
     * while the night-qualified window background still resolves dark.
     */
    @Test
    fun `the day and night platform themes differ in status bar form`() {
        assertNotEquals(
            "the two platform themes must not request the same status bar form",
            themed(Configuration.UI_MODE_NIGHT_NO).lightStatusBar(Configuration.UI_MODE_NIGHT_NO),
            themed(Configuration.UI_MODE_NIGHT_YES).lightStatusBar(Configuration.UI_MODE_NIGHT_YES),
        )
    }

    /** The theme has to point at the colour resource, not a hard-coded value. */
    @Test
    fun `both platform themes resolve the window background to the right value`() {
        for ((mode, expected) in listOf(
            Configuration.UI_MODE_NIGHT_NO to 0xFFFBFDF8.toInt(),
            Configuration.UI_MODE_NIGHT_YES to 0xFF101410.toInt(),
        )) {
            val resolved = ContextThemeWrapper(configured(mode), R.style.Theme_SmsApp)
                .obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
                .use { it.getColor(0, 0) }
            assertNotEquals("windowBackground must resolve to a colour, not 0", 0, resolved)
            assertEquals("window background for uiMode $mode", expected, resolved)
        }
    }

    private companion object {
        /** Bit offset of the night flag within `uiMode`; not exposed by the SDK. */
        const val UI_MODE_NIGHT_SHIFT = 0x20
    }

    /** Relative luminance, the standard sRGB weighting from WCAG. */
    private fun androidx.compose.ui.graphics.Color.luminance(): Float {
        fun channel(value: Float): Float =
            if (value <= 0.03928f) value / 12.92f
            else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
    }

    /** Unused, but keeps the locale-dependent resource path honest on import. */
    @Test
    fun `locale does not change the window background`() {
        Locale.setDefault(Locale("tr", "TR"))
        try {
            val background = configured(Configuration.UI_MODE_NIGHT_YES)
                .getColor(R.color.window_background)
            assertEquals(0xFF101410.toInt(), background)
        } finally {
            Locale.setDefault(Locale.ENGLISH)
        }
    }
}
