package com.anttech.smsapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The one palette every icon button draws from.
 *
 * Icons used to mix scheme roles with two one-off folder hues, so the same
 * command could read green in one place and grey in another. These extensions
 * map an icon's job to a scheme role instead: the colour follows the wallpaper
 * on a device with dynamic colour and the green ramp on a device without, and
 * every screen agrees because they all name the same three roles.
 *
 * These are plain properties of the scheme rather than composable getters, so a
 * test can pin them straight from [PreviewLightColors] and [PreviewDarkColors].
 */

/** The everyday action and navigation glyph, on the app bar and in list rows. */
internal val ColorScheme.iconCommand: Color get() = onSurface

/** The quieter tint for the status glyphs inside a conversation row. */
internal val ColorScheme.iconIndicator: Color get() = onSurfaceVariant

/**
 * The single tint every action takes while a selection is active, so a
 * selection toolbar reads as one mode rather than a row of separate commands.
 */
internal val ColorScheme.iconSelected: Color get() = primary
