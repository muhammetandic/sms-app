package com.anttech.smsapp.sms.send

/**
 * Estimates how many SMS parts a body splits into, and how full the current
 * part is, so the composer can show a counter before the send.
 *
 * This mirrors the platform's own division rather than calling it: the radio's
 * `divideMessage` is device-only, and the rules are stable and well known. A
 * GSM-7 body (every character in the 7-bit default alphabet) packs 160 units
 * into one part and 153 into each part of a concatenation; anything else is
 * UCS-2 at 70/67. The extended GSM characters (`€`, `[`, `]`, `{`, `}`, `\`,
 * `~`, `|`, `^`) ride an escape and cost two units. The estimate is deliberately
 * exact for the cases the radio agrees on; it exists to warn, not to encode.
 */
object SmsSegments {

    const val GSM7_SINGLE_SEGMENT = 160
    const val GSM7_MULTI_SEGMENT = 153
    const val UCS2_SINGLE_SEGMENT = 70
    const val UCS2_MULTI_SEGMENT = 67

    enum class Encoding { GSM7, UCS2 }

    /**
     * @param length units the encoder sees: septets in GSM-7 (extended
     *   characters counted twice), UTF-16 code units in UCS-2.
     * @param segments total parts the body divides into.
     * @param segmentLimit capacity of one part in this encoding, which drops
     *   from the single-part size once the length forces a concatenation.
     */
    data class Estimate(
        val length: Int,
        val segments: Int,
        val segmentLimit: Int,
        val encoding: Encoding,
    ) {
        /** Units used in the part the caret is in, out of [segmentLimit]. */
        val usedInCurrentSegment: Int
            get() = if (segments <= 1) length else length - (segments - 1) * segmentLimit
    }

    fun estimate(body: String): Estimate {
        val gsm7 = body.all { it in GSM7_BASIC || it in GSM7_EXTENDED }
        return if (gsm7) {
            // Extended characters are two septets on the wire, so a 160-char
            // body full of them is really 320 units and splits.
            val length = body.length + body.count { it in GSM7_EXTENDED }
            build(length, GSM7_SINGLE_SEGMENT, GSM7_MULTI_SEGMENT, Encoding.GSM7)
        } else {
            // `String.length` is UTF-16 code units, which is exactly what UCS-2
            // counts, so a surrogate pair is the two units it occupies.
            build(body.length, UCS2_SINGLE_SEGMENT, UCS2_MULTI_SEGMENT, Encoding.UCS2)
        }
    }

    private fun build(length: Int, single: Int, multi: Int, encoding: Encoding): Estimate {
        val segments = if (length <= single) 1 else (length + multi - 1) / multi
        val limit = if (segments == 1) single else multi
        return Estimate(length, segments, limit, encoding)
    }

    private const val GSM7_BASIC =
        "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    private const val GSM7_EXTENDED = "\u000C^{}\\[~]|€"
}
