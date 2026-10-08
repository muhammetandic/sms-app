package com.oaktech.smsapp.sms.mms

import java.io.ByteArrayOutputStream

/**
 * Decodes a WAP MMS PDU into [MmsPdu].
 *
 * The parser is deliberately tolerant: real carriers vary in how they encode
 * value-lengths, charsets and content-types, so an unrecognised field or part
 * header is skipped rather than failing the whole message. Anything that cannot
 * be reconciled with the header shape at all yields null, and the receiver
 * falls back to a placeholder message.
 */
object MmsPduParser {

    fun parse(bytes: ByteArray): MmsPdu? {
        if (bytes.isEmpty()) return null
        val reader = ByteReader(bytes)
        return try {
            parsePdu(reader)
        } catch (throwable: Throwable) {
            null
        }
    }

    private fun parsePdu(reader: ByteReader): MmsPdu {
        var messageType = 0
        var transactionId: String? = null
        var from: String? = null
        var date: Long? = null
        var subject: String? = null
        var messageClass: String? = null
        var messageSize: Long? = null
        var contentLocation: String? = null
        val to = mutableListOf<String>()
        var bodyContentType: String? = null
        var boundary: String? = null

        while (reader.hasRemaining()) {
            val code = reader.byte()
            if (code == 0) break

            when (code) {
                MmsWireCodes.MESSAGE_TYPE -> messageType = reader.byte()

                MmsWireCodes.MMS_VERSION -> skipOne(reader)

                MmsWireCodes.TRANSACTION_ID -> transactionId = reader.readTextString()

                MmsWireCodes.DATE -> date = reader.readLongInteger() * 1000

                MmsWireCodes.FROM -> from = reader.readFrom()

                MmsWireCodes.SUBJECT -> subject = reader.readEncodedString()

                MmsWireCodes.MESSAGE_CLASS -> messageClass = classname(reader.byte())

                MmsWireCodes.MESSAGE_SIZE -> messageSize = reader.readLongInteger()

                MmsWireCodes.CONTENT_LOCATION -> contentLocation = reader.readTextString()

                MmsWireCodes.TO -> to += reader.readEncodedString()

                MmsWireCodes.CONTENT_TYPE -> {
                    bodyContentType = reader.readContentType()
                    if (bodyContentType == MmsWireCodes.mimeForContentTypeToken(0x29) ||
                        bodyContentType == MULTIPART_MIME
                    ) {
                        boundary = reader.readBoundary()
                    }
                    // Content-Type is the last header before the body.
                    break
                }

                else -> skipField(reader, code)
        }
    }

    if (messageType == 0) throw IllegalStateException("no message-type header")

    // The header ends with a length-0 octet. The parsing above breaks on it in
    // the middle of the loop, so the CONTENT_TYPE branch leaves it unread; skip
    // one optional terminator before the multipart body regardless of which
    // route ended the header.
    if (reader.hasRemaining() && reader.peek() == 0) reader.byte()

    val parts = if (boundary != null) parseParts(reader) else emptyList()
    return MmsPdu(
        messageType = messageType,
        transactionId = transactionId,
        from = from,
        date = date,
        subject = subject,
        messageClass = messageClass,
        messageSize = messageSize,
        contentLocation = contentLocation,
        to = to,
        parts = parts,
    )
}

    private fun parseParts(reader: ByteReader): List<MmsPart> {
        val parts = mutableListOf<MmsPart>()
        while (reader.hasRemaining()) {
            val headerLength: Int = try {
                reader.byte()
            } catch (throwable: Throwable) {
                break
            }
            val contentLength = reader.readContentLength()
            if (contentLength < 0) break
            val header = reader.readBytes(headerLength)
            val data = if (contentLength > 0) reader.readBytes(contentLength) else ByteArray(0)

            parsePartHeader(ByteReader(header))?.let { (mime, contentId, fileName) ->
                parts += MmsPart(
                    contentType = mime,
                    contentId = contentId?.removePrefix("cid:"),
                    fileName = fileName,
                    data = data,
                )
            }
        }
        return parts
    }

    private fun parsePartHeader(reader: ByteReader): Triple<String, String?, String?>? {
        var mime = "application/octet-stream"
        var contentId: String? = null
        var fileName: String? = null

        while (reader.hasRemaining()) {
            val code = reader.byte()
            when (code) {
                MmsWireCodes.PART_CONTENT_TYPE -> mime = reader.readContentType()

                MmsWireCodes.PART_CONTENT_ID -> contentId = reader.readShortString()

                MmsWireCodes.PART_CONTENT_LOCATION -> fileName = reader.readShortString()

                else -> skipField(reader, code)
            }
        }
        return Triple(mime, contentId, fileName)
    }

    private fun classname(token: Int): String = when (token) {
        MmsMessageClass.PERSONAL -> "personal"
        MmsMessageClass.ADVERTISEMENT -> "advertisement"
        MmsMessageClass.INFORMATIONAL -> "informational"
        MmsMessageClass.AUTO -> "auto"
        else -> null
    } ?: "personal"

    /**
     * Fields whose value we do not read are skipped by their value-length. An
     * unknown field whose length is unreadable consumes the byte and stops on
     * the next, keeping the caller alive for malformed payloads.
     */
    private fun skipField(reader: ByteReader, code: Int) {
        if (code == MmsWireCodes.ADDRESS_PRESENT_TOKEN || code in 0x80..0x83) return
        val length = try {
            reader.readValueLength()
        } catch (throwable: Throwable) {
            0
        }
        if (length >= 0) reader.skip(length.coerceAtLeast(0))
    }

    private fun skipOne(reader: ByteReader) {
        // A single-octet value (e.g. the version token) or a length + token.
        val b = reader.byte()
        if (b < 0x80 && b > 0) reader.skip(b)
    }

    private const val MULTIPART_MIME = "application/vnd.wap.multipart.related"
}

/**
 * Sequential reader over a PDU. Tracks a position and throws [IndexOutOfBounds]
 * on under-run, which [MmsPduParser.parse] turns into a null result.
 */
internal class ByteReader(private val bytes: ByteArray) {
    private var pos = 0

    fun hasRemaining(): Boolean = pos < bytes.size

    fun peek(): Int = bytes[pos].toInt() and 0xFF

    fun byte(): Int = expect().toInt() and 0xFF

    fun readBytes(count: Int): ByteArray {
        require(count >= 0 && pos + count <= bytes.size) { "read past end" }
        val out = bytes.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    fun skip(count: Int) {
        readBytes(count)
    }

    /** Text-string: short-length then that many bytes, decoded as UTF-8. */
    fun readTextString(): String {
        val length = byte()
        if (length >= 0x80) throw IllegalStateException("unsupported text-string length")
        return decode(readBytes(length))
    }

    /** Text or quoted string inside a part header. */
    fun readShortString(): String {
        val b = byte()
        return when {
            b == MmsWireCodes.QUOTE -> readQuotedString()
            b < 0x80 -> decode(readBytes(b))
            else -> throw IllegalStateException("unsupported short string")
        }
    }

    /**
     * Value-length: a short length below 0x80, else the 0x1F marker followed
     * by a 16-bit big-endian length.
     */
    fun readValueLength(): Int {
        val b = byte()
        return when {
            b < 0x80 -> b
            b == MmsWireCodes.TIMEOUT_MARKER -> {
                val hi = byte()
                val lo = byte()
                (hi shl 8) or lo
            }

            else -> throw IllegalStateException("unsupported value length")
        }
    }

    /**
     * Long-integer, used for Date and Message-Size. Emitted as a 4-byte length
     * followed by four bytes; some carriers write the 0x1F marker first, which
     * is tolerated.
     */
    fun readLongInteger(): Long {
        val marker = byte()
        val length = when {
            marker == MmsWireCodes.TIMEOUT_MARKER -> 4
            marker > 4 -> throw IllegalStateException("unsupported long integer length $marker")
            else -> marker
        }
        var value = 0L
        repeat(length) { value = (value shl 8) or readBytes(1)[0].toLong() and 0xFF }
        return value
    }

    /**
     * Content-length of a multipart entry: a length-octet count followed by
     * that many big-endian bytes, with a tolerance for the one-byte-literal
     * form some stacks emit.
     */
    fun readContentLength(): Int {
        val marker = byte()
        return when {
            marker in 1..4 -> readBytes(marker).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            marker < 0x80 -> marker
            else -> -1
        }
    }

    /**
     * Encoded-string: value-length, optional charset flag + charset, then a
     * quoted or token string consumed up to the value's end.
     */
    fun readEncodedString(): String {
        val end = pos + readValueLength()
        var value: String
        val b = byte()
        if (b == MmsWireCodes.CHARSET_FLAG) {
            var charset = byte()
            while ((charset and 0x80) != 0) charset = byte()
            // The charset form quotes its string, so the opening quote is the
            // next byte (unlike the other call sites, where it was just read).
            consumeQuote()
            value = readQuotedString()
        } else if (b == MmsWireCodes.QUOTE) {
            value = readQuotedString()
        } else {
            value = decode(readBytes((end - pos).coerceAtLeast(0)))
        }
        return value
    }

    private fun consumeQuote() {
        if (hasRemaining() && peek() == MmsWireCodes.QUOTE) pos += 1
    }

    /**
     * From header: value-length, optional address-present token, then an
     * encoded-string. Some networks omit the address-present token.
     */
    fun readFrom(): String? {
        readValueLength()
        if ((bytes[pos].toInt() and 0xFF) == MmsWireCodes.ADDRESS_PRESENT_TOKEN) pos += 1
        return readEncodedString()
    }

    /**
     * Content-type value: a well-known token (already in the map, including the
     * sub-0x80 tokens text/plain and image/jpeg), a quoted string, or a
     * length-prefixed string.
     */
    fun readContentType(): String {
        val b = byte()
        return when {
            b >= 0x80 -> MmsWireCodes.mimeForContentTypeToken(b) ?: "application/octet-stream"
            knownToken(b) -> tokenMimeTable.getValue(b)
            b == MmsWireCodes.QUOTE -> readQuotedString()
            b > 0 -> decode(readBytes(b))
            else -> "application/octet-stream"
        }
    }

    private fun knownToken(b: Int): Boolean = b in tokenMimeTable

    /**
     * Content-Type parameters following a multipart value: charset, type,
     * start and boundary. Unknown params are skipped by value-length.
     */
    fun readBoundary(): String? {
        var boundary: String? = null
        while (hasRemaining() && pos < bytes.size) {
            val code = byte()
            when (code) {
                MmsWireCodes.PARAM_TYPE, MmsWireCodes.PARAM_START, MmsWireCodes.PARAM_CHARSET -> skipParamValue()

                MmsWireCodes.PARAM_BOUNDARY -> boundary = readParamValue()

                else -> {
                    pos -= 1
                    return boundary
                }
            }
        }
        return boundary
    }

    private fun skipParamValue() {
        readParamValue()
    }

    private fun readParamValue(): String {
        val b = byte()
        return when {
            b == MmsWireCodes.QUOTE -> readQuotedString()
            b < 0x80 -> String(charArrayOf(b.toChar()))
            else -> ""
        }
    }

    private fun readQuotedString(): String {
        val out = ByteArrayOutputStream()
        while (hasRemaining()) {
            val b = byte()
            if (b == MmsWireCodes.QUOTE) break
            out.write(b)
        }
        return decode(out.toByteArray())
    }

    private fun decode(raw: ByteArray): String =
        runCatching { String(raw, Charsets.UTF_8) }.getOrDefault(String(raw, Charsets.ISO_8859_1))

    private fun expect(): Byte {
        if (pos >= bytes.size) throw IndexOutOfBoundsException("read past end")
        return bytes[pos++]
    }

    private companion object {
        /**
         * Sub-0x80 tokens cannot be told apart from a length prefix by the
         * high bit, so the handful this app actually sees are recognised up
         * front. Everything above 0x80 is dispatched through
         * [MmsWireCodes.mimeForContentTypeToken].
         */
        val tokenMimeTable: Map<Int, String> = mapOf(
            0x0A to "text/plain",
            0x1C to "image/jpeg",
            0x29 to "application/vnd.wap.multipart.related",
        )
    }
}