package com.anttech.smsapp.sms.send

import com.anttech.smsapp.sms.send.SmsSegments.Encoding
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The composer's part counter has to agree with the radio, or it warns about the
 * wrong thing: the segment breakpoints and the GSM-7/UCS-2 split are the two
 * places that can silently disagree.
 */
class SmsSegmentsTest {

    @Test
    fun `a short gsm body is one part`() {
        val estimate = SmsSegments.estimate("hello")

        assertEquals(Encoding.GSM7, estimate.encoding)
        assertEquals(5, estimate.length)
        assertEquals(1, estimate.segments)
        assertEquals(160, estimate.segmentLimit)
        assertEquals(5, estimate.usedInCurrentSegment)
    }

    @Test
    fun `exactly one segment of ascii stays single`() {
        val estimate = SmsSegments.estimate("a".repeat(160))

        assertEquals(1, estimate.segments)
        assertEquals(160, estimate.usedInCurrentSegment)
    }

    @Test
    fun `one character past the limit splits into two parts`() {
        val estimate = SmsSegments.estimate("a".repeat(161))

        assertEquals(2, estimate.segments)
        assertEquals(153, estimate.segmentLimit)
        // 161 - 153: the overrun lands 8 units into the second part.
        assertEquals(8, estimate.usedInCurrentSegment)
    }

    @Test
    fun `a full two-part body does not spill into a third`() {
        assertEquals(2, SmsSegments.estimate("a".repeat(306)).segments)
        assertEquals(3, SmsSegments.estimate("a".repeat(307)).segments)
    }

    /**
     * The extended set costs two septets, so a body made of them splits at half
     * the visible character count.
     */
    @Test
    fun `extended gsm characters count double`() {
        val estimate = SmsSegments.estimate("€".repeat(80))

        assertEquals(160, estimate.length)
        assertEquals(1, estimate.segments)
        assertEquals(2, SmsSegments.estimate("€".repeat(81)).segments)
    }

    /**
     * The GSM-7 alphabet is not the Latin-1 block: uppercase `Ç` is in it but
     * lowercase `ç` is not, so a `ç` anywhere pushes the whole body to UCS-2.
     */
    @Test
    fun `turkish characters follow the gsm alphabet membership`() {
        val gsm = SmsSegments.estimate("Ç".repeat(160))
        assertEquals(Encoding.GSM7, gsm.encoding)
        assertEquals(1, gsm.segments)

        val ucs2 = SmsSegments.estimate("ç" + "a".repeat(69))
        assertEquals(Encoding.UCS2, ucs2.encoding)
        assertEquals(70, ucs2.length)
        assertEquals(1, ucs2.segments)
    }

    @Test
    fun `ucs2 splits at the smaller limits`() {
        val estimate = SmsSegments.estimate("ş" + "a".repeat(70))

        assertEquals(Encoding.UCS2, estimate.encoding)
        assertEquals(71, estimate.length)
        assertEquals(2, estimate.segments)
        assertEquals(67, estimate.segmentLimit)
        // 71 - 67: the overrun lands 4 units into the second part.
        assertEquals(4, estimate.usedInCurrentSegment)
    }

    /**
     * A surrogate pair is two UTF-16 units, which is exactly the two UCS-2 units
     * the encoder spends on it.
     */
    @Test
    fun `an emoji counts as two ucs2 units`() {
        val estimate = SmsSegments.estimate("😀")

        assertEquals(Encoding.UCS2, estimate.encoding)
        assertEquals(2, estimate.length)
        assertEquals(1, estimate.segments)
    }

    @Test
    fun `an empty body is a single empty part`() {
        val estimate = SmsSegments.estimate("")

        assertEquals(Encoding.GSM7, estimate.encoding)
        assertEquals(0, estimate.length)
        assertEquals(1, estimate.segments)
        assertEquals(0, estimate.usedInCurrentSegment)
    }
}
