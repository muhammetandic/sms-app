package com.anttech.smsapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Backgrounds for the conversation list's letter badges.
 *
 * A per-sender colour is a wayfinding cue: the eye learns "the blue one" before
 * it reads the name. The scheme only offers two or three container roles, and a
 * fallback ramp has exactly one, so a scheme-derived badge repeats every other
 * row. These are fixed saturated mid-tones instead, chosen so white initials
 * stay legible in both light and dark themes; a tonal light container would
 * glare against the dark surface.
 *
 * [AvatarColorsTest] pins the white-on-colour contrast, so a new entry cannot
 * quietly become unreadable.
 */
internal val AvatarColors: List<Color> = listOf(
    Color(0xFF1565C0), // blue
    Color(0xFF2E7D32), // green
    Color(0xFFD32F2F), // red
    Color(0xFF7B1FA2), // purple
    Color(0xFFBF360C), // deep orange
    Color(0xFF00796B), // teal
    Color(0xFF303F9F), // indigo
    Color(0xFF5D4037), // brown
    Color(0xFFC2185B), // pink
    Color(0xFF455A64), // blue grey
)

/** The initial drawn on an [AvatarColors] badge. */
internal val OnAvatarColor: Color = Color.White

/**
 * The badge colour for [key], deterministic so a sender keeps its colour across
 * scrolls and restarts. Callers pass the stored address rather than the title,
 * so resolving a contact name does not recolour an established row.
 *
 * The hash bits are folded once before the modulo: a bare `hashCode() % size`
 * leans on the low bits, which for keys that share a long prefix would send a
 * run of senders to the same colour.
 */
internal fun avatarColorFor(key: String): Color {
    val hash = key.hashCode()
    val folded = hash xor (hash ushr 16)
    return AvatarColors[(folded and Int.MAX_VALUE) % AvatarColors.size]
}
