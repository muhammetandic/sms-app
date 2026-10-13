package com.anttech.smsapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The palette every icon button draws from.
 *
 * Scheme roles alone cannot do this job: a command, a folder shortcut and a
 * destructive action would all resolve to the same two or three accents, so the
 * eye gets no help telling "delete" from "pin". Each icon therefore keeps a hue
 * of its own -- red for the spam pile and for delete, amber for a pinned row,
 * purple for a silenced one, blue for drafts and for mark-unread, teal for the
 * archive folder, green for the actions that put a message back, mark it read
 * or send one -- while navigation and plain commands stay neutral grey.
 *
 * The hues are fixed mid-tones rather than scheme roles for the same reason the
 * avatar colours are: the scheme is dynamic on Android 12+ and would paint
 * several of these the same accent. Each is chosen to clear 3:1 against both the
 * light and the dark surface, which [IconColorsTest] pins.
 *
 * [ColorScheme.iconNeutral] is the exception: the one role with no hue of its
 * own, so it follows the scheme and dims or brightens with it.
 *
 * These are plain properties of the scheme rather than composable getters, so a
 * test can read them straight from the light and dark preview schemes.
 */

/** Everyday navigation and commands that should not draw attention. */
internal val ColorScheme.iconNeutral: Color get() = onSurfaceVariant

/** The spam pile, and the destructive delete. */
internal val ColorScheme.iconSpam: Color get() = FolderRed
internal val ColorScheme.iconDelete: Color get() = FolderRed

/** A pinned conversation. */
internal val ColorScheme.iconPin: Color get() = PinAmber

/** A silenced conversation. */
internal val ColorScheme.iconMute: Color get() = MutePurple

/** Drafts, and the mark-unread action. */
internal val ColorScheme.iconDrafts: Color get() = FolderBlue
internal val ColorScheme.iconUnread: Color get() = FolderBlue

/** The archive folder. */
internal val ColorScheme.iconArchive: Color get() = FolderTeal

/** Put a message back in the inbox, mark a conversation read, or send one. */
internal val ColorScheme.iconRestore: Color get() = ActionGreen
internal val ColorScheme.iconSend: Color get() = ActionGreen
internal val ColorScheme.iconRead: Color get() = ActionGreen

private val FolderRed = Color(0xFFD32F2F)
private val PinAmber = Color(0xFFB26A00)
private val MutePurple = Color(0xFFAB47BC)
private val FolderBlue = Color(0xFF1565C0)
private val FolderTeal = Color(0xFF00796B)
private val ActionGreen = Color(0xFF2E7D32)
