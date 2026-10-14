package com.anttech.smsapp.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.anttech.smsapp.EXTRA_CONVERSATION_ID
import com.anttech.smsapp.MainActivity
import com.anttech.smsapp.R
import com.anttech.smsapp.sms.receive.InboundMessage

/**
 * Posts the notification for a newly stored inbound message.
 *
 * The tap action goes straight to the thread as a `PendingIntent`. Since Android
 * 10 the app cannot start an activity from a background broadcast, so this must
 * carry the navigation itself rather than relying on tapping a notification
 * that then starts something.
 *
 * The buttons are a different shape of thing: each is a broadcast to
 * [NotificationActionReceiver], because a tap on a button is a request to change
 * stored data, not to navigate.
 */
class IncomingMessageNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val actionPreferences = NotificationActionPreferences(appContext)

    /**
     * The POST_NOTIFICATIONS check below is a real runtime guard, not a
     * suppression of a missing check: on API 33+ a denied permission means
     * [NotificationManager.notify] is a silent no-op, and below API 33 the
     * permission does not exist and reports as denied, so the guard has to stay
     * version-gated. Lint's NotificationPermission detector cannot follow a
     * guard that is itself wrapped in an SDK_INT comparison, so it reports a
     * false positive here.
     */
    @SuppressLint("NotificationPermission")
    fun notify(
        message: InboundMessage,
        messageId: Long,
        conversationId: Long?,
        displayName: String? = null,
    ) =
        postNotification(
            address = message.address,
            body = message.body,
            messageId = messageId,
            conversationId = conversationId,
            displayName = displayName,
        )

    private fun postNotification(
        address: String,
        body: String,
        messageId: Long,
        conversationId: Long?,
        displayName: String?,
    ) {
        // Guarded inline rather than behind the NotificationChannels helper so
        // the check is visible here. The SDK_INT term is required in practice:
        // POST_NOTIFICATIONS does not exist below API 33 and reports as denied
        // there, which would suppress every notification on Android 11 and 12.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (!NotificationChannels.canPostNotifications(appContext)) return

        NotificationChannels.ensure(appContext)
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return

        // The caller resolves the sender against the address book before calling
        // (it already runs off the main thread); a missing contact and a blank
        // name both fall back to the number rather than an empty title.
        val title = displayName?.takeIf { it.isNotBlank() } ?: address
        val text = bodyText(body)
        val conversation = conversationId ?: -1L

        val contentIntent = PendingIntent.getActivity(
            appContext,
            NotificationIds.forConversation(conversationId),
            Intent(appContext, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CONVERSATION_ID, conversationId ?: -1L)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(appContext, NotificationChannels.INCOMING_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            // Accent colour: the platform tints the notification's app name and
            // small icon from it. The action labels carry their own colour (see
            // colouredLabel), because the accent is a single colour for the whole
            // notification and could not tell the options apart.
            .setColor(ContextCompat.getColor(appContext, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        // Reply first, so the direct-reply slot is the primary action when it
        // is enabled. Each remaining action is independently configurable.
        if (actionPreferences.isEnabled(ACTION_REPLY)) {
            builder.addAction(replyAction(address, conversation, messageId))
        }
        if (actionPreferences.isEnabled(ACTION_MARK_READ)) {
            builder.addAction(
                action(
                    ACTION_MARK_READ,
                    R.drawable.ic_action_mark_read,
                    R.string.notification_action_read,
                    R.color.notification_action_read,
                    conversation,
                    messageId,
                ),
            )
        }
        if (actionPreferences.isEnabled(ACTION_MARK_SPAM)) {
            builder.addAction(
                action(
                    ACTION_MARK_SPAM,
                    R.drawable.ic_action_spam,
                    R.string.notification_action_spam,
                    R.color.notification_action_spam,
                    conversation,
                    messageId,
                ),
            )
        }
        if (actionPreferences.isEnabled(ACTION_DELETE)) {
            builder.addAction(
                action(
                    ACTION_DELETE,
                    R.drawable.ic_action_delete,
                    R.string.notification_action_delete,
                    R.color.notification_action_delete,
                    conversation,
                    messageId,
                ),
            )
        }

        val notification = builder.build()

        manager.notify(NotificationIds.forConversation(conversationId), notification)
    }

    /**
     * The direct-reply action, shaped as a remote input rather than a plain
     * broadcast like the others: the system renders a reply field for it and
     * delivers the typed text back inside the intent.
     *
     * The recipient is carried as an extra because a notification tap only knows
     * the conversation id; the thread resolves the address from the store, but a
     * background broadcast reply cannot afford that turn-around.
     */
    private fun replyAction(address: String, conversationId: Long, messageId: Long): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            R.drawable.ic_action_reply,
            colouredLabel(R.string.notification_action_reply, R.color.notification_action_reply),
            PendingIntent.getBroadcast(
                appContext,
                actionRequestCode(ACTION_REPLY, conversationId),
                NotificationActionReceiver.intent(
                    appContext,
                    ACTION_REPLY,
                    conversationId,
                    messageId,
                    recipient = address,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                ),
            ),
        ).addRemoteInput(
            RemoteInput.Builder(NotificationActionReceiver.KEY_QUICK_REPLY)
                .setLabel(appContext.getString(R.string.notification_action_reply))
                .build(),
        ).build()

    /**
     * One action button bound to a broadcast.
     *
     * The request code has to differ per action as well as per conversation:
     * `PendingIntent` equality ignores extras, so two buttons sharing a request
     * code would resolve to the same intent and the last one built would win.
     */
    private fun action(
        action: String,
        icon: Int,
        label: Int,
        labelColor: Int,
        conversationId: Long,
        messageId: Long,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(
        icon,
        colouredLabel(label, labelColor),
        PendingIntent.getBroadcast(
            appContext,
            actionRequestCode(action, conversationId),
            NotificationActionReceiver.intent(appContext, action, conversationId, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    /**
     * An action label in its own colour.
     *
     * A spanned title is the only supported way to colour a single action: the
     * notification accent ([NotificationCompat.Builder.setColor]) is one colour
     * for the whole notification, so it cannot set the options apart. The
     * platform templates may re-luminance a spanned title to keep it legible, so
     * these hues are a starting point rather than a guaranteed rendering.
     */
    private fun colouredLabel(@StringRes label: Int, @ColorRes color: Int): CharSequence =
        SpannableString(appContext.getString(label)).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(appContext, color)),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

    private fun actionRequestCode(action: String, conversationId: Long): Int {
        val slot = when (action) {
            NotificationActionReceiver.ACTION_REPLY -> 3
            NotificationActionReceiver.ACTION_MARK_READ -> 0
            NotificationActionReceiver.ACTION_MARK_SPAM -> 1
            else -> 2
        }
        // The stride is the number of distinct actions, so no two actions of the
        // same conversation can resolve to the same request code. The slot is
        // collision-free per conversation (sequential row id), unlike the old
        // modulo which collapsed any two conversations whose ids differed by 1000.
        return ACTION_REQUEST_BASE + (NotificationIds.requestSlot(conversationId) * ACTION_STRIDE) + slot
    }

    /**
     * The notification text for an incoming message. Falls back to a generic
     * line when the body is blank, so the notification is never empty.
     */
    private fun bodyText(body: String): String =
        body.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.notification_no_text)

    private companion object {
        const val ACTION_REQUEST_BASE = 100_000
        const val ACTION_STRIDE = 4

        const val ACTION_REPLY = NotificationActionReceiver.ACTION_REPLY
        const val ACTION_MARK_READ = NotificationActionReceiver.ACTION_MARK_READ
        const val ACTION_MARK_SPAM = NotificationActionReceiver.ACTION_MARK_SPAM
        const val ACTION_DELETE = NotificationActionReceiver.ACTION_DELETE
    }
}
