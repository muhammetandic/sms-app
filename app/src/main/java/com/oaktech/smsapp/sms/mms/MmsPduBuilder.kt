package com.oaktech.smsapp.sms.mms

import java.io.ByteArrayOutputStream

/**
 * Encodes MMS send-req PDUs in the WAP binary format.
 *
 * The byte layout follows the MMS encapsulation used by the mobile MMS stacks
 * ([MmsWireCodes] documents each byte), and is exercised by round-tripping
 * through [MmsPduParser] in tests. The network leg ultimately validates against
 * a live carrier, which is why the builder is kept plain-Kotlin and free of
 * Android types.
 */
object MmsPduBuilder {

    /**
     * Builds a `m-send-req`. The multipart body always carries a SMIL slide and
     * a text part, followed by one part per [media] entry.
     *
     * @param boundary the multipart boundary; null picks a random one, which is
     *   what a real send wants. Tests pass a fixed boundary for golden bytes.
     */
    fun sendRequest(
        transactionId: String,
        recipients: List<String>,
        subject: String?,
        bodyText: String,
        media: List<Media>,
        boundary: String? = null,
    ): ByteArray {
        val usedBoundary = boundary
            ?: ("Ms_" + TIMESTAMP_HEX + "_" + kotlin.random.Random.nextInt(0x0000, 0xFFFF).toString(16))
        val writer = ByteWriter(1024)

        writer.write(MmsWireCodes.MESSAGE_TYPE)
        writer.write(MmsMessageType.SEND_REQ)

        writer.write(MmsWireCodes.MMS_VERSION)
        writer.write(0x01)
        writer.write(0x12)

        writer.write(MmsWireCodes.TRANSACTION_ID)
        writer.writeTextString(transactionId)

        writer.write(MmsWireCodes.DATE)
        writer.writeLongInteger(System.currentTimeMillis() / 1000)

        if (subject != null) {
            writer.write(MmsWireCodes.SUBJECT)
            writer.writeEncodedString(subject)
        }

        recipients.forEach { recipient ->
            writer.write(MmsWireCodes.TO)
            writer.writeEncodedString(recipient)
        }

        writer.writeBodyType(usedBoundary)

        writer.write(0x00)

        writer.writeParts(bodyText, media, usedBoundary)
        return writer.toByteArray()
    }

    /**
     * Builds a retrieve-conf as a carrier would send it: the kind of PDU the
     * auto-retriever is pointed at by an X-Mms-Content-Location. Kept on the
     * builder so the wire layout stays in one place; the discovery path on the
     * phone only ever parses these, it never constructs one for the network.
     */
    internal fun retrieveConf(
        transactionId: String,
        from: String,
        subject: String?,
        bodyText: String,
        media: List<Media>,
        boundary: String = "rc_$TIMESTAMP_HEX",
    ): ByteArray {
        val writer = ByteWriter(1024)

        writer.write(MmsWireCodes.MESSAGE_TYPE)
        writer.write(MmsMessageType.RETRIEVE_CONF)

        writer.write(MmsWireCodes.TRANSACTION_ID)
        writer.writeTextString(transactionId)

        writer.write(MmsWireCodes.FROM)
        writer.writeFromEncodedString(from)

        if (subject != null) {
            writer.write(MmsWireCodes.SUBJECT)
            writer.writeEncodedString(subject)
        }

        writer.writeBodyType(boundary)

        writer.write(0x00)

        writer.writeParts(bodyText, media, boundary)
        return writer.toByteArray()
    }

    /**
     * Builds a `m-notifyresp-ind`: the reply the client sends to the MMSC after
     * handling a notification, telling it the message was retrieved (or that the
     * device will pull it later). Carries only headers, echoing the
     * notification's transaction id so the MMSC can match the message.
     */
    internal fun notifyRespInd(transactionId: String, status: Int): ByteArray {
        val writer = ByteWriter(64)
        writer.write(MmsWireCodes.MESSAGE_TYPE)
        writer.write(MmsMessageType.NOTIFY_RESP_IND)
        writer.write(MmsWireCodes.TRANSACTION_ID)
        writer.writeTextString(transactionId)
        writer.write(MmsWireCodes.MMS_VERSION)
        writer.write(0x01)
        writer.write(0x12)
        writer.write(MmsWireCodes.STATUS)
        writer.write(status)
        writer.write(0x00)
        return writer.toByteArray()
    }

    /**
     * Builds an `m-acknowledge-ind`: sent after a retrieve-conf so the MMSC can
     * stop treating the transaction as outstanding (the reason carriers
     * retransmit otherwise).
     */
    internal fun acknowledgeInd(transactionId: String): ByteArray {
        val writer = ByteWriter(64)
        writer.write(MmsWireCodes.MESSAGE_TYPE)
        writer.write(MmsMessageType.ACKNOWLEDGE_IND)
        writer.write(MmsWireCodes.TRANSACTION_ID)
        writer.writeTextString(transactionId)
        writer.write(MmsWireCodes.MMS_VERSION)
        writer.write(0x01)
        writer.write(0x12)
        writer.write(0x00)
        return writer.toByteArray()
    }

    private fun ByteWriter.writeFromEncodedString(address: String) {
        // From-header value: an address-present token, then the mailbox encoded
        // exactly like the other string fields (length octet, then the charset
        // flag, charset and quoted string). The outer value-length covers the
        // address-present token, the mailbox's length octet and the string.
        val encoded = ByteArrayOutputStream().apply {
            write(MmsWireCodes.CHARSET_FLAG)
            write(MmsWireCodes.CHARSET_UTF8)
            write(MmsWireCodes.QUOTE)
            write(address.toByteArray(Charsets.UTF_8))
            write(MmsWireCodes.QUOTE)
        }.toByteArray()
        write(2 + encoded.size)
        write(MmsWireCodes.ADDRESS_PRESENT_TOKEN)
        write(encoded.size)
        writeBytes(encoded)
    }

    private fun ByteWriter.writeParts(bodyText: String, media: List<Media>, boundary: String) {
        val smil = smil(media.size)
        val text = bodyText.toByteArray(Charsets.UTF_8)

        // SMIL first, so parsers that key slide layout on the first part find it.
        writePart(
            contentType = SMIL_MIME,
            contentId = SMIL_CID,
            fileName = null,
            data = smil.toByteArray(Charsets.UTF_8),
        )
        writePart(
            contentType = "text/plain",
            contentId = TEXT_CID,
            fileName = null,
            data = text,
        )
        media.forEachIndexed { index, item ->
            writePart(
                contentType = item.contentType,
                contentId = "$TEXT_CID_INDEX_BASE${index + 1}", // "1", "2", ...
                fileName = item.fileName,
                data = item.data,
            )
        }
    }

    private fun ByteWriter.writePart(contentType: String, contentId: String, fileName: String?, data: ByteArray) {
        val header = ByteWriter(64).apply {
            write(MmsWireCodes.PART_CONTENT_TYPE)
            writeContentTypeValue(contentType)
            write(MmsWireCodes.PART_CONTENT_ID)
            writeTextString(contentId)
            if (fileName != null) {
                write(MmsWireCodes.PART_CONTENT_LOCATION)
                writeTextString(fileName)
            }
        }.toByteArray()

        write(header.size)
        writeContentLength(data.size)
        writeBytes(header)
        writeBytes(data)
    }

    /** SMIL that shows the text and then every media part as a slide. */
    internal fun smil(mediaCount: Int): String = buildString {
        append("<smil xmlns=\"http://www.w3.org/2001/SMIL20/Language\"><head><layout>")
        append("<root-layout width=\"320px\" height=\"480px\"/><region id=\"Text\" left=\"0\" top=\"0\" width=\"320px\" height=\"160px\" fit=\"scroll\"/>")
        append("<region id=\"Image\" left=\"0\" top=\"160px\" width=\"320px\" height=\"320px\" fit=\"me\"/>")
        append("</layout></head><body><par dur=\"indefinite\">")
        append("<text src=\"cid:$TEXT_CID\"/>")
        for (index in 1..mediaCount) {
            append("<img src=\"cid:$TEXT_CID_INDEX_BASE$index\"/>")
        }
        append("</par></body></smil>")
    }

    private fun ByteWriter.writeBodyType(boundary: String) {
        write(MmsWireCodes.CONTENT_TYPE)
        writeContentTypeValue(MULTIPART_MIME)
        write(MmsWireCodes.PARAM_TYPE)
        writeQuotedString("text/plain")
        write(MmsWireCodes.PARAM_START)
        writeQuotedString("cid:$TEXT_CID")
        write(MmsWireCodes.PARAM_BOUNDARY)
        writeQuotedString(boundary)
    }

    private fun ByteWriter.writeContentTypeValue(mime: String) {
        val token = MmsWireCodes.contentTypeToken(mime)
        if (token != null) {
            write(token)
        } else {
            writeQuotedString(mime)
        }
    }

    private fun ByteWriter.writeContentLength(length: Int) {
        when {
            length < 0x100 -> {
                write(0x01)
                write(length)
            }

            length < 0x10000 -> {
                write(0x02)
                write(length shr 8)
                write(length and 0xFF)
            }

            else -> {
                write(0x04)
                write(length ushr 24)
                write(length ushr 16 and 0xFF)
                write(length ushr 8 and 0xFF)
                write(length and 0xFF)
            }
        }
    }

    private fun ByteWriter.writeLongInteger(value: Long) {
        write(0x04)
        write(((value ushr 24) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    private fun ByteWriter.writeEncodedString(value: String) {
        val encoded = ByteArrayOutputStream().apply {
            write(MmsWireCodes.CHARSET_FLAG)
            write(MmsWireCodes.CHARSET_UTF8)
            write(MmsWireCodes.QUOTE)
            write(value.toByteArray(Charsets.UTF_8))
            write(MmsWireCodes.QUOTE)
        }.toByteArray()
        write(encoded.size)
        writeBytes(encoded)
    }

    private fun ByteWriter.writeTextString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write(bytes.size)
        writeBytes(bytes)
    }

    private fun ByteWriter.writeQuotedString(value: String) {
        write(MmsWireCodes.QUOTE)
        writeBytes(value.toByteArray(Charsets.UTF_8))
        write(MmsWireCodes.QUOTE)
    }

    data class Media(val contentType: String, val fileName: String, val data: ByteArray)

    private const val SMIL_MIME = "application/smil"
    private const val SMIL_CID = "smil"
    private const val TEXT_CID = "0"
    private const val TEXT_CID_INDEX_BASE = "1"
    private const val MULTIPART_MIME = "application/vnd.wap.multipart.related"

    private val TIMESTAMP_HEX: String
        get() = java.lang.Long.toHexString(System.currentTimeMillis() / 1000)
}

/**
 * A growable byte sink for assembling a PDU.
 */
internal class ByteWriter(initialCapacity: Int) {
    private val out = ByteArrayOutputStream(initialCapacity)

    fun write(byte: Int) = out.write(byte and 0xFF)

    fun writeBytes(bytes: ByteArray) = out.write(bytes, 0, bytes.size)

    fun toByteArray(): ByteArray = out.toByteArray()
}