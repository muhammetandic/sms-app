package com.anttech.smsapp.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.anttech.smsapp.R
import com.anttech.smsapp.sms.receive.LogTags

/**
 * Notification channels, created lazily.
 *
 * Android 8+ drops notifications whose channel does not exist, so this must run
 * before the first notify call. `POST_NOTIFICATIONS` does not exist below API 33;
 * on Android 11 and 12 the permission is implicit and the check is skipped.
 */
object NotificationChannels {

    const val INCOMING_MESSAGES = "incoming_messages"

    /**
     * Alert an outgoing message failed to send. A separate channel from the
     * incoming one so a user can silence send failures without silencing new
     * messages (and vice versa); they are unrelated kinds of alert.
     */
    const val SEND_FAILURES = "send_failures"

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

        // A failed send is an error, not a message: default importance rather
        // than high, so it does not buzz like an arriving text.
        val failures = NotificationChannel(
            SEND_FAILURES,
            context.getString(R.string.notification_send_failed_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_send_failed_channel_description)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(failures)
        android.util.Log.d(LogTags.STORE, "Notification channels ready")
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