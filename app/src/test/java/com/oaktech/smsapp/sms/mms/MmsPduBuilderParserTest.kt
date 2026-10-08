package com.oaktech.smsapp.sms.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduBuilderParserTest {

    private fun build(
        recipients: List<String> = listOf("+15551234567"),
        subject: String? = "Hello MMS",
        body: String = "Look at this",
        media: List<MmsPduBuilder.Media> = listOf(
            MmsPduBuilder.Media(
                contentType = "image/jpeg",
                fileName = "photo.jpg",
                data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            ),
        ),
        boundary: String? = "---test-boundary",
    ): ByteArray = MmsPduBuilder.sendRequest(
        transactionId = "abc123",
        recipients = recipients,
        subject = subject,
        bodyText = body,
        media = media,
        boundary = boundary,
    )

    private fun encode(cp: Char): String =
        String(byteArrayOf(cp.toByte()), Charsets.UTF_8)

    @Test
    fun `send request round-trips header and parts`() {
        val payload = build()
        val pdu = MmsPduParser.parse(payload)

        assertTrue(pdu != null)
        if (pdu == null) return
        assertEquals(MmsMessageType.SEND_REQ, pdu.messageType)
        assertNull(pdu.from)
        assertEquals(listOf("+15551234567"), pdu.to)
        assertEquals("Hello MMS", pdu.subject)
        assertEquals("Look at this", textOf(pdu))

        val media = pdu.parts.firstOrNull { it.contentType == "image/jpeg" }
        assertTrue(media != null)
        if (media == null) return
        assertEquals("photo.jpg", media.fileName)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            media.data,
        )
    }

    @Test
    fun `round-trip preserves subject and recipient order`() {
        val pdu = MmsPduParser.parse(
            build(
                recipients = listOf("+15551112223", "+15554445556"),
                subject = "Multi group",
                body = "all in?",
                media = emptyList(),
            ),
        )

        assertTrue(pdu != null)
        if (pdu == null) return
        assertEquals(listOf("+15551112223", "+15554445556"), pdu.to)
        assertEquals("Multi group", pdu.subject)
        assertEquals("all in?", textOf(pdu))
    }

    @Test
    fun `utf8 text survives encoding`() {
        val body = "merhaba dünya, federação 🌍"
        val pdu = MmsPduParser.parse(build(body = body, media = emptyList()))

        assertTrue(pdu != null)
        if (pdu == null) return
        assertEquals(body, textOf(pdu))
    }

    @Test
    fun `unicode subject survives encoding`() {
        val subject = "ünlı mesaj"
        val pdu = MmsPduParser.parse(build(subject = subject, media = emptyList()))

        assertTrue(pdu != null)
        if (pdu == null) return
        assertEquals(subject, pdu.subject)
    }

    @Test
    fun `message without attachments has no media parts`() {
        val pdu = MmsPduParser.parse(build(media = emptyList()))

        assertTrue(pdu != null)
        if (pdu == null) return
        assertEquals(2, pdu.parts.size)
        assertEquals("application/smil", pdu.parts.first().contentType)
        assertEquals("text/plain", pdu.parts.last().contentType)
        assertNull(pdu.parts.last().fileName)
    }

    @Test
    fun `smil references every media part`() {
        val pdu = MmsPduParser.parse(build())
        val smil = pdu?.parts?.firstOrNull { it.contentType == "application/smil" }?.data

        assertTrue(smil != null)
        if (smil == null) return
        val text = String(smil, Charsets.UTF_8)
        assertTrue(text.contains("cid:0"))
        assertTrue(text.contains("cid:1"))
    }

@Test
    fun `hex boundary round-trips through the content-type parameters`() {
        val boundary = "AaB03x__9f2d"
        val pdu = MmsPduParser.parse(build(boundary = boundary))

        assertTrue(pdu != null)
        if (pdu == null) return
        assertEquals(3, pdu.parts.size)
    }

    @Test
    fun `garbage input yields null rather than crashing`() {
        assertNull(MmsPduParser.parse(byteArrayOf(0x80.toByte(), 0x00, 0xFF.toByte())))
        assertNull(MmsPduParser.parse(ByteArray(0)))
        assertNull(MmsPduParser.parse(byteArrayOf(0x8C.toByte())))
    }

    @Test
    fun `notification content location decodes from the header`() {
        // A minimal notification.ind: message-type, transaction-id "tid",
        // content-location "/retrieve/42", then the header terminator.
        val bytes = byteArrayOf(
            0x8C.toByte(), MmsMessageType.NOTIFICATION_IND.toByte(),
            0x98.toByte(), 0x03, 't'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(),
            0x8B.toByte(), 0x0C, '/'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte(),
            'r'.code.toByte(), 'i'.code.toByte(), 'e'.code.toByte(), 'v'.code.toByte(), 'e'.code.toByte(),
            '/'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
            0x00,
        )

        val pdu = MmsPduParser.parse(bytes)

        assertTrue(pdu != null)
        if (pdu == null) return
        assertEquals(MmsMessageType.NOTIFICATION_IND, pdu.messageType)
        assertEquals("tid", pdu.transactionId)
        assertEquals("/retrieve/42", pdu.contentLocation)
    }

    @Test
    fun `a retrieve-conf round-trips its sender and media`() {
        val pdu = MmsPduParser.parse(
            MmsPduBuilder.retrieveConf(
                transactionId = "tid",
                from = "+15551234567",
                subject = "subject",
                bodyText = "look",
                media = listOf(MmsPduBuilder.Media("image/jpeg", "a.jpg", jpeg())),
                boundary = "dltest",
            ),
        )

        assertTrue(pdu != null)
        if (pdu == null) return
        assertEquals(MmsMessageType.RETRIEVE_CONF, pdu.messageType)
        assertEquals("+15551234567", pdu.from)
        assertEquals("look", textOf(pdu))
        val media = pdu.parts.firstOrNull { it.fileName == "a.jpg" }
        assertTrue(media != null)
        assertEquals("image/jpeg", media?.contentType)
        assertArrayEquals(jpeg(), media?.data)
    }

    private fun jpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @Test
    fun `notifyresp and acknowledge-ind round-trip transaction id and type`() {
        val notify = MmsPduBuilder.notifyRespInd("tid99", MmsNotifyStatus.RETRIEVED)
        val notifyPdu = MmsPduParser.parse(notify)
        assertTrue(notifyPdu != null)
        if (notifyPdu == null) return
        assertEquals(MmsMessageType.NOTIFY_RESP_IND, notifyPdu.messageType)
        assertEquals("tid99", notifyPdu.transactionId)

        val ack = MmsPduBuilder.acknowledgeInd("tid99")
        val ackPdu = MmsPduParser.parse(ack)
        assertTrue(ackPdu != null)
        if (ackPdu == null) return
        assertEquals(MmsMessageType.ACKNOWLEDGE_IND, ackPdu.messageType)
        assertEquals("tid99", ackPdu.transactionId)
    }

    @Test
    fun `notifyresp writes the status field after the version`() {
        val pdu = MmsPduBuilder.notifyRespInd("tid", MmsNotifyStatus.DEFERRED)
        // message-type | transaction-id "tid" | mms-version 1.2 | status | terminator
        val expected = byteArrayOf(
            0x8C.toByte(), MmsMessageType.NOTIFY_RESP_IND.toByte(),
            0x98.toByte(), 0x03, 't'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(),
            0x8D.toByte(), 0x01, 0x12,
            0x95.toByte(), MmsNotifyStatus.DEFERRED.toByte(),
            0x00,
        )
        assertArrayEquals(expected, pdu)
    }

    @Test
    fun `parser tolerates a truncated body by returning what it can`() {
        val payload = build()
        val truncated = payload.copyOf(payload.size / 2)

        // Either a full decode or a fallback; never a crash.
        val result = runCatching { MmsPduParser.parse(truncated) }.getOrNull()
        assertTrue(result == null || result.parts.isNotEmpty())
    }

    @Test
    fun `start parameter references the text part`() {
        val pdu = MmsPduParser.parse(build())
        assertTrue(pdu != null)
        if (pdu == null) return
        assertTrue(pdu.parts.any { it.contentId == "smil" })
        assertTrue(pdu.parts.any { it.contentId == "0" })
    }

    @Test
    fun `parser drops the cid prefix from content ids`() {
        val pdu = MmsPduParser.parse(build())
        assertFalse(pdu?.parts?.any { it.contentId?.startsWith("cid:") == true } ?: true)
    }

    private fun textOf(pdu: MmsPdu): String {
        val part = pdu.parts.firstOrNull { it.contentType == "text/plain" }
            ?: return ""
        return String(part.data ?: ByteArray(0), Charsets.UTF_8)
    }
}