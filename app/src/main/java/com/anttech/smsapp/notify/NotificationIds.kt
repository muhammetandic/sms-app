package com.anttech.smsapp.notify

import android.app.NotificationManager
import android.content.Context

/**
 * Notification ids for the per-conversation alerts.
 *
 * Shared with [NotificationActionReceiver] so an action taken from a notification
 * can dismiss exactly the notification it came from. A `PendingIntent` does not
 * carry the notification id, so both sides have to derive it the same way.
 */
object NotificationIds {

    private const val CONVERSATION_BASE = 2_000

    /**
     * One alert per conversation, so a new message replaces the previous one
     * rather than stacking.
     *
     * Row ids are handed out one-by-one from 1, so the id is the conversation id
     * itself (plus the base): two conversations can never share a slot. Only
     * past 2^31 conversations — unreachable for a real install — does the Long
     * stop fitting an Int, and then a hash picks the slot instead of the old
     * modulo, which collided far earlier (any two ids 1000 apart).
     */
    fun forConversation(conversationId: Long?): Int = CONVERSATION_BASE + slot(conversationId)

    private const val FAILED_SEND_BASE = 4_000

    /**
     * One alert per conversation for a message that failed to send.
     *
     * In its own base range so it never collides with the incoming-message
     * alerts (2_000…); a failed send must be able to appear beside an incoming
     * one for the same thread without one replacing the other.
     */
    fun forFailedSend(conversationId: Long?): Int = FAILED_SEND_BASE + slot(conversationId)

    /**
     * The conversation-derived slot for PendingIntent request codes. Distinct
     * from the notification-id space so a notification id and a request code
     * never collide; the caller multiplies by its own action stride, so the
     * bounded mask keeps the product inside the positive Int range.
     */
    fun requestSlot(conversationId: Long?): Int = slot(conversationId) and 0x1FFFFF

    /**
     * A positive, collision-free slot per conversation. Sequential row ids map
     * to themselves below the mask; longer ids fall to the hash.
     */
    private fun slot(conversationId: Long?): Int {
        val id = conversationId ?: return 0
        return if (id <= 0x1FFFFFL) id.toInt() else (id.hashCode() and 0x1FFFFF)
    }
}

/**
 * Dismisses a conversation's alert, if one is showing.
 *
 * Called whenever a conversation becomes read - opening a thread, a batch
 * mark-read, or the notification's own Read action - because the store is
 * the source of truth and an alert left on screen would contradict it.
 * Cancelling an id with no notification is a no-op, so a read path never
 * has to check first.
 */
fun cancelConversationNotification(context: Context, conversationId: Long) {
    context.getSystemService(NotificationManager::class.java)
        ?.cancel(NotificationIds.forConversation(conversationId))
}
