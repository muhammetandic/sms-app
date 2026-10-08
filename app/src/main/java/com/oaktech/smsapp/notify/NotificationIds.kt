package com.oaktech.smsapp.notify

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
