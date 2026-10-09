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
    /** True when the row came from the MMS table, so its dedupe key stays namespaced. */
    val mms: Boolean = false,
)

/**
 * One part of an MMS this app is writing into the provider.
 *
 * Text parts (the body, the SMIL layout) are stored inline through [text]; media
 * parts are written as bytes through the part URI the provider mints, so they
 * carry [filePath] instead. The provider only accepts a `_data` column from its
 * own package, which is why a path is never handed to it directly.
 */
data class ProviderMmsPart(
    val contentType: String,
    val contentId: String?,
    val fileName: String?,
    val filePath: String?,
    val text: String?,
)

/** One media file on disk, ready to become a provider part. */
data class ProviderMedia(
    val contentType: String,
    val fileName: String,
    val filePath: String,
)

/**
 * An MMS this app is writing into the provider.
 *
 * [address] is the conversation's address: the sender for an inbound message,
 * the first recipient of an outbound group. [recipients] is the full To list
 * for an outbound send and empty for an inbound one. The instant is in
 * milliseconds like everywhere in this app; the writer converts it to the
 * provider's seconds.
 */
data class ProviderMms(
    val address: String,
    val recipients: List<String>,
    val subject: String?,
    val timestampMillis: Long,
    val parts: List<ProviderMmsPart>,
    val outgoing: Boolean,
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