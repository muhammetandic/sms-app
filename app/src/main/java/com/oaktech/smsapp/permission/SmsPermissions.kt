package com.oaktech.smsapp.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The runtime permissions this app asks for, resolved per API level.
 *
 * `POST_NOTIFICATIONS` does not exist below API 33. Requesting it on Android 11
 * or 12 returns denied without a prompt and the notification is silently
 * dropped, so it is only ever requested where it exists.
 */
object SmsPermissions {

    /** Needed to show threads and receive SMS_DELIVER broadcasts. */
    val essential: List<String> = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        // The app does not process MMS, but the system's ROLE_SMS definition
        // requires a WAP_PUSH_DELIVER receiver, and the platform can drop that
        // broadcast unless the app holds this. Without it a fresh install can
        // fail to be made the default SMS app, which is what breaks SMS itself.
        Manifest.permission.RECEIVE_WAP_PUSH,
        Manifest.permission.READ_CONTACTS,
        // Names the active SIMs so the settings screen can offer a send-subscription
        // choice; without it the list is hidden rather than demanded separately.
        Manifest.permission.READ_PHONE_STATE,
    )

    /** Notification access, API 33+ only. */
    val notification: List<String> = listOf(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * Permissions to request in a single prompt. Notifications are requested
     * separately so a denial does not block reading and sending.
     */
    fun forCurrentPlatform(): List<String> =
        if (supportsRuntimeNotificationPermission()) essential + notification else essential

    fun supportsRuntimeNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: List<String> = essential): Boolean =
        permissions.all { isGranted(context, it) }

    fun missing(context: Context, permissions: List<String> = forCurrentPlatform()): List<String> =
        permissions.filterNot { isGranted(context, it) }

    /**
     * Permissions that only the default SMS app can meaningfully hold.
     *
     * Losing the SMS role revokes these at runtime, so the UI must treat a loss
     * as a permission loss too rather than assuming a granted flag is stable.
     * The set is the whole SMS group: acquiring the role is what grants them
     * again, which is why a role-loss revocation is answered by the "set as
     * default" flow rather than by a second permission prompt.
     */
    fun requiresSmsRole(permission: String): Boolean =
        permission == Manifest.permission.READ_SMS ||
            permission == Manifest.permission.RECEIVE_SMS ||
            permission == Manifest.permission.SEND_SMS ||
            permission == Manifest.permission.RECEIVE_WAP_PUSH
}