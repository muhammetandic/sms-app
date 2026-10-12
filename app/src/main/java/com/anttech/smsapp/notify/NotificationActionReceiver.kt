package com.anttech.smsapp.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.sms.receive.LogTags
import com.anttech.smsapp.sms.send.SendGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles the action buttons on an incoming-message notification.
 *
 * Android 10+ blocks starting an activity from a background broadcast, so an
 * action cannot be an activity `PendingIntent` the way the tap target is: it has
 * to be a broadcast that finishes its work and leaves the UI alone.
 *
 * Each action ends by cancelling the notification it came from. The store is the
 * source of truth and the list is driven by a Room flow, so a stale notification
 * left on screen would contradict what the app shows.
 *
 * The reply action sends through [sendReply], injectable so tests can observe it
 * without needing a live radio; production falls back to the process-wide
 * coordinator.
 */
class NotificationActionReceiver(
    private val sendReply: (suspend (recipient: String, body: String) -> Unit)? = null,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION)
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)

        if (action == null || conversationId < 0) {
            Log.w(LogTags.RECEIVE, "Ignoring notification action with missing extras")
            return
        }

        val appContext = context.applicationContext
        // The database work is a suspend call, so the receiver cannot return
        // before it finishes without cutting the broadcast's ten second budget
        // short. goAsync keeps the process alive across it.
        val pending = runCatching { goAsync() }.getOrNull()
        inFlight.incrementAndGet()

        scope.launch {
            try {
                apply(appContext, intent, action, conversationId, messageId)
            } catch (throwable: Throwable) {
                Log.e(LogTags.RECEIVE, "Failed to apply notification action $action", throwable)
            } finally {
                inFlight.decrementAndGet()
                // Null in unit tests, where the broadcast never had a real
                // pending result to hold open. Guarded because this is a
                // process-wide scope: an escaping throwable would be unhandled
                // and attributed to whatever work happens to be running next.
                pending?.finish()
            }
        }
    }

    private suspend fun apply(
        context: Context,
        intent: Intent,
        action: String,
        conversationId: Long,
        messageId: Long,
    ) {
        val repository = DataGraph.get(context).messageRepository

        when (action) {
            ACTION_REPLY -> {
                // The remote input delivers the text inside the same intent the
                // button opened with, so it has to be read here rather than
                // re-sent from the notifier.
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_QUICK_REPLY)
                    ?.toString()
                val recipient = intent.getStringExtra(EXTRA_REPLY_RECIPIENT)
                if (reply.isNullOrBlank() || recipient.isNullOrBlank()) {
                    Log.w(LogTags.RECEIVE, "Reply action without text or recipient; ignoring")
                    cancel(context, conversationId)
                    return
                }
                sendReply?.invoke(recipient.trim(), reply)
                    ?: runCatching { SendGraph.coordinator(context).send(recipient.trim(), reply) }
                        .onFailure { Log.e(LogTags.SEND, "Quick reply failed to send", it) }
                // A reply answers the alert, so the thread is read and the
                // notification goes away with the sender.
                repository.markConversationRead(conversationId)
                cancel(context, conversationId)
                Log.d(LogTags.RECEIVE, "Replied from notification to $recipient")
                return
            }

            ACTION_MARK_READ -> repository.markConversationRead(conversationId)

            ACTION_MARK_SPAM -> {
                // The alert for a conversation the user just called spam would
                // keep reappearing on every future message from it, so silence it
                // as part of the same move.
                repository.setConversationSpam(conversationId, isSpam = true)
                cancel(context, conversationId)
                Log.d(LogTags.RECEIVE, "Moved conversation $conversationId to spam")
                return
            }

            ACTION_DELETE -> {
                // Delete the message that was notified about. If the action was
                // reached for a message id that is already gone, this is a no-op
                // and the conversation is left intact.
                if (messageId >= 0) repository.deleteMessage(messageId)
                cancel(context, conversationId)
                Log.d(LogTags.RECEIVE, "Deleted message $messageId from conversation $conversationId")
                return
            }

            else -> {
                Log.w(LogTags.RECEIVE, "Ignoring unknown notification action $action")
                return
            }
        }

        // Marking as read leaves the conversation in the inbox, so the alert is
        // replaced by nothing rather than dismissed silently elsewhere.
        cancel(context, conversationId)
        Log.d(LogTags.RECEIVE, "Marked conversation $conversationId read")
    }

    private fun cancel(context: Context, conversationId: Long) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(NotificationIds.forConversation(conversationId))
    }

    companion object {
        const val EXTRA_ACTION = "com.anttech.smsapp.extra.NOTIFICATION_ACTION"
        const val EXTRA_CONVERSATION_ID = "com.anttech.smsapp.extra.CONVERSATION_ID"
        const val EXTRA_MESSAGE_ID = "com.anttech.smsapp.extra.MESSAGE_ID"
        const val EXTRA_REPLY_RECIPIENT = "com.anttech.smsapp.extra.REPLY_RECIPIENT"
        const val KEY_QUICK_REPLY = "com.anttech.smsapp.QUICK_REPLY"

        const val ACTION_REPLY = "reply"
        const val ACTION_MARK_READ = "mark_read"
        const val ACTION_MARK_SPAM = "mark_spam"
        const val ACTION_DELETE = "delete"

        /**
         * Process-wide, mirroring [com.anttech.smsapp.sms.receive.SmsReceiveWorker]:
         * a broadcast can arrive while the process is being torn down, and a
         * fresh scope per action would be cancelled with it.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Actions currently applying, so tests can wait for the store to settle. */
        val inFlight = AtomicInteger(0)

        /**
         * Blocks until every in-flight action has finished, so a caller that tears
         * down the store cannot race work that is already on its way.
         *
         * Only meaningful in tests, which are single-threaded per class; a
         * production caller has no reason to wait.
         */
        fun awaitIdle() {
            while (inFlight.get() > 0) Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        private const val POLL_INTERVAL_MILLIS = 5L

        fun intent(
            context: Context,
            action: String,
            conversationId: Long,
            messageId: Long,
            recipient: String = "",
        ): Intent = Intent(context, NotificationActionReceiver::class.java)
            .putExtra(EXTRA_ACTION, action)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(EXTRA_MESSAGE_ID, messageId)
            .putExtra(EXTRA_REPLY_RECIPIENT, recipient)
    }
}
