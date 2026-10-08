package com.oaktech.smsapp.sms.mms

/**
 * An incoming MMS after parsing, ready for storage.
 *
 * The MMS case differs from SMS in one important way: the notification PDU
 * carries no media bytes at all, only the intent to deliver some. So
 * [attachments] is normally empty on arrival, and the thread shows a download
 * affordance until the bytes are actually fetched.
 */
data class InboundMms(
    val address: String,
    val body: String,
    val subject: String?,
    val timestampMillis: Long,
    val transactionId: String?,
    val attachments: List<MmsAttachment>,
    val contentLocation: String? = null,
)

/** One media part of an MMS, either already present or advertised but missing. */
data class MmsAttachment(
    val fileName: String,
    val contentType: String,
    val data: ByteArray?,
)

/**
 * Maps a decoded PDU to an [InboundMms].
 *
 * Only the two message types a phone actually receives are accepted; the rest
 * (send-conf for outgoing, delivery and read reports) are not messages to
 * store. The SMIL and text parts are consumed translating the multipart body;
 * every remaining part becomes an attachment.
 */
object MmsPduMapper {

    private const val SMIL = "application/smil"
    private const val TEXT_PLAIN = "text/plain"

    fun toInbound(pdu: MmsPdu, fallbackTimestamp: Long): InboundMms? {
        if (pdu.messageType != MmsMessageType.NOTIFICATION_IND &&
            pdu.messageType != MmsMessageType.RETRIEVE_CONF
        ) {
            return null
        }
        val from = pdu.from ?: return null
        if (from.isBlank()) return null

        val text = pdu.parts
            .firstOrNull { it.contentType == TEXT_PLAIN }
            ?.data
            ?.toString(Charsets.UTF_8)
            .orEmpty()

        val attachments = pdu.parts
            .filter { it.contentType != SMIL && it.contentType != TEXT_PLAIN }
            .map { part ->
                MmsAttachment(
                    fileName = part.fileName ?: "attachment-${part.contentId.orEmpty()}",
                    contentType = part.contentType,
                    data = part.data,
                )
            }

        return InboundMms(
            address = from,
            body = text,
            subject = pdu.subject,
            timestampMillis = pdu.date ?: fallbackTimestamp,
            transactionId = pdu.transactionId,
            attachments = attachments,
            contentLocation = pdu.contentLocation,
        )
    }
}