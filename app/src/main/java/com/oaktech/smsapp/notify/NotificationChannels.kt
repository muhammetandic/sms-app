package com.oaktech.smsapp.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.oaktech.smsapp.R
import com.oaktech.smsapp.sms.receive.LogTags

/**
 * Notification channels, created lazily.
 *
 * Android 8+ drops notifications whose channel does not exist, so this must run
 * before the first notify call. `POST_NOTIFICATIONS` does not exist below API 33;
 * on Android 11 and 12 the permission is implicit and the check is skipped.
 */
object NotificationChannels {

    const val INCOMING_MESSAGES = "incoming_messages"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // A channel's name and description are only read when it is first
        // created; the system keeps whatever it was given, so changing these
        // strings does not rename the channel on an existing install. That is
        // why the id is left alone across releases.
        val channel = NotificationChannel(
            INCOMING_MESSAGES,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            enableVibration(true)
            setShowBadge(true)
        }

        manager.createNotificationChannel(channel)
        android.util.Log.d(LogTags.STORE, "Notification channel ready")
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompatShim.areNotificationsEnabled(context)
    }
}

internal object NotificationManagerCompatShim {
    fun areNotificationsEnabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
}