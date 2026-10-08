package com.oaktech.smsapp.sms.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduMapperTest {

    private val fallback = 1_700_000_000_000L

    private fun pdu(
        messageType: Int = MmsMessageType.NOTIFICATION_IND,
        from: String? = "+12025550134",
        date: Long? = 1_690_000_000_000L,
        subject: String? = "Get some",
        transactionId: String? = "t-1",
        contentLocation: String? = "/rc/1",
        parts: List<MmsPart> = emptyList(),
    ) = MmsPdu(
        messageType = messageType,
        transactionId = transactionId,
        from = from,
        date = date,
        subject = subject,
        contentLocation = contentLocation,
        parts = parts,
    )

    @Test
    fun `notification maps to an inbound mms with no attachments`() {
        val mms = MmsPduMapper.toInbound(pdu(), fallback)

        assertTrue(mms != null)
        if (mms == null) return
        assertEquals("+12025550134", mms.address)
        assertEquals("Get some", mms.subject)
        assertEquals("t-1", mms.transactionId)
        assertEquals("/rc/1", mms.contentLocation)
        assertEquals(1_690_000_000_000L, mms.timestampMillis)
        assertEquals("", mms.body)
        assertTrue(mms.attachments.isEmpty())
    }

    @Test
    fun `retrieve-conf extracts text and media but skips smil`() {
        val mms = MmsPduMapper.toInbound(
            pdu(
                messageType = MmsMessageType.RETRIEVE_CONF,
                parts = listOf(
                    MmsPart("application/smil", contentId = "smil", data = "<smil/>".toByteArray()),
                    MmsPart("text/plain", contentId = "0", data = "Foto bana".toByteArray()),
                    MmsPart("image/jpeg", contentId = "1", fileName = "foto.jpg", data = byteArrayOf(1, 2, 3)),
                ),
            ),
            fallback,
        )

        assertTrue(mms != null)
        if (mms == null) return
        assertEquals("Foto bana", mms.body)
        assertEquals(1, mms.attachments.size)
        assertEquals("foto.jpg", mms.attachments.first().fileName)
        assertEquals("image/jpeg", mms.attachments.first().contentType)
        assertEquals(3, mms.attachments.first().data?.size)
    }

    @Test
    fun `missing sender is not a storable message`() {
        assertNull(MmsPduMapper.toInbound(pdu(from = null), fallback))
        assertNull(MmsPduMapper.toInbound(pdu(from = ""), fallback))
    }

    @Test
    fun `non inbox message types are ignored`() {
        assertNull(MmsPduMapper.toInbound(pdu(messageType = MmsMessageType.SEND_REQ), fallback))
        assertNull(MmsPduMapper.toInbound(pdu(messageType = MmsMessageType.DELIVERY_IND), fallback))
    }

    @Test
    fun `missing date falls back to the receiver timestamp`() {
        val mms = MmsPduMapper.toInbound(pdu(date = null), fallback)

        assertTrue(mms != null)
        if (mms == null) return
        assertEquals(fallback, mms.timestampMillis)
    }
}