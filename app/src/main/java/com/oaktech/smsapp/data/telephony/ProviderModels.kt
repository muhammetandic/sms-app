package com.oaktech.smsapp.data.telephony

/**
 * Row shape read from the Telephony provider.
 *
 * Kept separate from [com.oaktech.smsapp.data.local.MessageEntity] so the
 * provider layer does not leak into the rest of the app.
 */
data class ProviderMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val status: Int,
    val read: Boolean,
)

data class ProviderThread(
    val threadId: Long,
    val snippet: String,
    val date: Long,
    val messageCount: Int,
    val read: Boolean,
)

object SmsProviderTypes {
    const val MESSAGE_TYPE_INBOX = 1
    const val MESSAGE_TYPE_SENT = 2
    const val MESSAGE_TYPE_DRAFT = 3
    const val MESSAGE_TYPE_OUTBOX = 4
    const val MESSAGE_TYPE_FAILED = 5
    const val MESSAGE_TYPE_QUEUED = 6

    const val STATUS_NONE = -1
    const val STATUS_COMPLETE = 0
    const val STATUS_PENDING = 32
    const val STATUS_FAILED = 64

    fun isOutgoing(type: Int): Boolean =
        type == MESSAGE_TYPE_SENT || type == MESSAGE_TYPE_OUTBOX ||
            type == MESSAGE_TYPE_DRAFT || type == MESSAGE_TYPE_FAILED
}