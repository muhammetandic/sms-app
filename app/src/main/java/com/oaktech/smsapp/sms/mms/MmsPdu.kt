package com.oaktech.smsapp.sms.mms

/**
 * A decoded MMS PDU as the app wants it: a header plus the parts of its body.
 *
 * Parsing and building are kept pure-Kotlin (no Android types) so the byte
 * layout can be exercised in a plain JVM test.
 */
data class MmsPdu(
    val messageType: Int,
    val transactionId: String? = null,
    val from: String? = null,
    val date: Long? = null,
    val subject: String? = null,
    val messageClass: String? = null,
    val messageSize: Long? = null,
    /**
     * X-Mms-Content-Location of a notification.ind: the path on the MMSC that a
     * retrieve must GET to pull the message body. Absent when the PDU already
     * carries the body (a retrieve-conf).
     */
    val contentLocation: String? = null,
    /** Destination members of a send-req, in PDU order. */
    val to: List<String> = emptyList(),
    val parts: List<MmsPart> = emptyList(),
)

/** One part of an MMS body: its MIME type, maybe a name, and the bytes. */
data class MmsPart(
    val contentType: String,
    val contentId: String? = null,
    val fileName: String? = null,
    val data: ByteArray? = null,
)

object MmsMessageType {
    const val SEND_REQ = 0x80
    const val SEND_CONF = 0x81
    const val NOTIFICATION_IND = 0x82
    const val NOTIFY_RESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84
    const val ACKNOWLEDGE_IND = 0x85
    const val DELIVERY_IND = 0x86
    const val READ_REQ = 0x87
    const val READ_REP = 0x88
}

object MmsMessageClass {
    const val PERSONAL = 0x80
    const val ADVERTISEMENT = 0x81
    const val INFORMATIONAL = 0x82
    const val AUTO = 0x83
}

/**
 * Status values the app send back to the MMSC in a notifyresp-ind.
 *
 * "retrieved" tells the MMSC the message was pulled and needs no retransmission;
 * "deferred" asks it to keep the message because this device will retrieve it
 * later (the manual download in the thread).
 */
object MmsNotifyStatus {
    const val RETRIEVED = 0x80
    const val REJECTED = 0x81
    const val DEFERRED = 0x82
    const val UNRECOGNIZED = 0x83
}

/** The subset of the WAP binary format this app produces, documented for the wire. */
internal object MmsWireCodes {
    // Header field codes.
    const val CONTENT_TYPE = 0x84
    const val DATE = 0x85
    const val FROM = 0x89
    const val MESSAGE_CLASS = 0x8A
    const val CONTENT_LOCATION = 0x8B
    const val MESSAGE_TYPE = 0x8C
    const val MMS_VERSION = 0x8D
    const val MESSAGE_SIZE = 0x8E
    const val STATUS = 0x95
    const val SUBJECT = 0x96
    const val TO = 0x97
    const val TRANSACTION_ID = 0x98

    // The address-present token inside a From header field.
    const val ADDRESS_PRESENT_TOKEN = 0x80
    // The charset-flag byte that opens an encoded-string with an explicit charset.
    const val CHARSET_FLAG = 0x81

    // Content-Type parameter codes.
    const val PARAM_CHARSET = 0x81
    const val PARAM_TYPE = 0x8A
    const val PARAM_START = 0x8B
    const val PARAM_BOUNDARY = 0x8C

    // Part header field codes inside a multipart body.
    const val PART_CONTENT_TYPE = 0x84
    const val PART_CONTENT_ID = 0x88
    const val PART_CONTENT_LOCATION = 0x8E

    const val QUOTE = 0x22

    // Short-integer token for UTF-8 in a charset parameter.
    const val CHARSET_UTF8 = 106

    const val TIMEOUT_MARKER = 0x1F

    // Content-type tokens this app uses; anything else travels as a quoted string.
    fun contentTypeToken(mime: String): Int? = when (mime) {
        "text/plain" -> 0x0A
        "image/jpeg" -> 0x1C
        "application/vnd.wap.multipart.related" -> 0x29
        else -> null
    }

    fun mimeForContentTypeToken(token: Int): String? = when (token) {
        0x0A -> "text/plain"
        0x1C -> "image/jpeg"
        0x29 -> "application/vnd.wap.multipart.related"
        else -> null
    }
}