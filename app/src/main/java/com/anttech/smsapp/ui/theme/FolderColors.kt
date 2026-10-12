package com.anttech.smsapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Tints for the folder shortcuts in the conversations app bar.
 *
 * The app bar's other actions are commands and stay on the scheme's on-surface
 * colour; these two are destinations, so a fixed hue turns them into landmarks
 * the eye can find without reading the icon. They are fixed rather than scheme
 * roles because the scheme is dynamic on Android 12+ and would paint both the
 * same accent. Red marks the spam pile; blue keeps drafts plainly apart from it.
 *
 * [FolderColorsTest] pins both against the light and dark surfaces.
 */
internal val SpamFolderColor: Color = Color(0xFFD32F2F)

internal val DraftsFolderColor: Color = Color(0xFF1565C0)
