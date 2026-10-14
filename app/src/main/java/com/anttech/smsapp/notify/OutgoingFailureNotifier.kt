package com.anttech.smsapp.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.anttech.smsapp.EXTRA_CONVERSATION_ID
import com.anttech.smsapp.MainActivity
import com.anttech.smsapp.R

/**
 * Alerts the user when an outgoing message fails to send.
 *
 * A failure the composer or thread can show inline is left to those screens.
 * This catches the ones they cannot: a result that arrives after the screen is
 * gone (the process was recreated for it) or a reply sent from a notification.
 * Without an alert the message would sit as a silent failed bubble the user has
 * no reason to open, so a message that never left would never be noticed.
 */
class OutgoingFailureNotifier(context: Context) {

    private val appContext = context.applicationContext

    /**
     * The POST_NOTIFICATIONS guard mirrors [IncomingMessageNotifier]: it is a
     * real runtime check on API 33+, while below 33 the permission does not exist
     * and reports as denied, so the SDK_INT term is required to keep Android 11
     * and 12 alerts working.
     */
    @SuppressLint("NotificationPermission")
    fun notify(recipient: String, conversationId: Long?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (!NotificationChannels.canPostNotifications(appContext)) return

        NotificationChannels.ensure(appContext)
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return

        val title = recipient.takeIf { it.isNotBlank() }?.let {
            appContext.getString(R.string.notification_send_failed_title, it)
        } ?: appContext.getString(R.string.notification_send_failed_title_generic)

        // Tapping opens the thread, which is where the failed bubble and its
        // resend action live; there is nothing useful to do from here itself.
        val contentIntent = PendingIntent.getActivity(
            appContext,
            NotificationIds.requestSlot(conversationId),
            Intent(appContext, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CONVERSATION_ID, conversationId ?: -1L)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, NotificationChannels.SEND_FAILURES)
            .setSmallIcon(R.drawable.ic_notification)
            // Red reads as failure without needing a word for it; the accent also
            // tints the app name and small icon to match.
            .setColor(ContextCompat.getColor(appContext, R.color.notification_action_delete))
            .setContentTitle(title)
            .setContentText(appContext.getString(R.string.notification_send_failed_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(appContext.getString(R.string.notification_send_failed_text)),
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(NotificationIds.forFailedSend(conversationId), notification)
    }
}
