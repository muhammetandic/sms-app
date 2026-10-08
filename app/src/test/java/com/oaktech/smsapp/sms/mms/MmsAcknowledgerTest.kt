package com.oaktech.smsapp.sms.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MmsAcknowledgerTest {

    private class RecordingPoster : MmsPoster {
        val calls = mutableListOf<Pair<String, ByteArray>>()
        var response = 202

        override fun post(mmscUrl: String, pdu: ByteArray): Int {
            calls += mmscUrl to pdu
            return response
        }
    }

    private class StubResolver(private val url: String?) : MmscUrlResolver {
        override fun mmscUrl(): String? = url
    }

    @Test
    fun `notify response posts a retrieve-status pdu to the mmsc`() {
        val poster = RecordingPoster()
        val acknowledger = MmsAcknowledger(poster, StubResolver("http://mmsc.example"))

        acknowledger.notifyResponse("tid42", MmsNotifyStatus.RETRIEVED)

        assertEquals(1, poster.calls.size)
        val (url, pdu) = poster.calls.single()
        assertEquals("http://mmsc.example", url)
        val parsed = MmsPduParser.parse(pdu)
        assertTrue(parsed != null)
        assertEquals(MmsMessageType.NOTIFY_RESP_IND, parsed?.messageType)
        assertEquals("tid42", parsed?.transactionId)
    }

    @Test
    fun `acknowledge posts an acknowledge-ind pdu to the mmsc`() {
        val poster = RecordingPoster()
        val acknowledger = MmsAcknowledger(poster, StubResolver("http://mmsc.example"))

        acknowledger.acknowledge("tid42")

        assertEquals(1, poster.calls.size)
        val parsed = MmsPduParser.parse(poster.calls.single().second)
        assertTrue(parsed != null)
        assertEquals(MmsMessageType.ACKNOWLEDGE_IND, parsed?.messageType)
        assertEquals("tid42", parsed?.transactionId)
    }

    @Test
    fun `no network does not throw and posts nothing`() {
        val poster = RecordingPoster()
        val acknowledger = MmsAcknowledger(poster, StubResolver(null))

        acknowledger.notifyResponse("tid42", MmsNotifyStatus.DEFERRED)
        acknowledger.acknowledge("tid42")

        assertTrue(poster.calls.isEmpty())
    }

    @Test
    fun `a null transaction id posts nothing`() {
        val poster = RecordingPoster()
        val acknowledger = MmsAcknowledger(poster, StubResolver("http://mmsc.example"))

        acknowledger.notifyResponse(null, MmsNotifyStatus.RETRIEVED)
        acknowledger.acknowledge(null)

        assertTrue(poster.calls.isEmpty())
    }
}