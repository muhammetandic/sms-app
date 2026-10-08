package com.oaktech.smsapp.sms.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MmsAutoRetrieverTest {

    @Test
    fun `a relative content location is resolved against the mmsc base`() {
        val retriever = RecordingRetriever()
        val auto = MmsAutoRetriever(retriever, FakeApnResolver("http://mms.carrier.example"))
        val notification = notificationPdu("path/to/body")

        val result = auto.retrieve(notification)

        assertEquals("http://mms.carrier.example/path/to/body", retriever.requestedUrl)
        assertArrayEquals("pdu".toByteArray(), result)
    }

    @Test
    fun `an absolute content location bypasses the mmsc base`() {
        val retriever = RecordingRetriever()
        val auto = MmsAutoRetriever(retriever, FakeApnResolver("http://mms.carrier.example"))

        auto.retrieve(notificationPdu("https://cdn.example/retrieve/42"))

        assertEquals("https://cdn.example/retrieve/42", retriever.requestedUrl)
    }

    @Test
    fun `a null mmsc makes the retrieve impossible`() {
        val auto = MmsAutoRetriever(RecordingRetriever(), FakeApnResolver(null))

        assertNull(auto.retrieve(notificationPdu("/path")))
    }

    @Test
    fun `a notification without a content location yields nothing`() {
        val auto = MmsAutoRetriever(RecordingRetriever(), FakeApnResolver("http://mms.carrier.example"))

        assertNull(auto.retrieve(notificationPdu(null)))
    }

    @Test
    fun `a failed http get surfaces as a null retrieve`() {
        val auto = MmsAutoRetriever(FailingRetriever(), FakeApnResolver("http://mms.carrier.example"))

        assertNull(auto.retrieve("/path"))
    }

    private fun notificationPdu(contentLocation: String?) = MmsPdu(
        messageType = MmsMessageType.NOTIFICATION_IND,
        transactionId = "abc",
        contentLocation = contentLocation,
    )

    private class RecordingRetriever : MmsRetriever {
        var requestedUrl: String? = null
        override fun get(url: String): ByteArray? {
            requestedUrl = url
            return "pdu".toByteArray()
        }
    }

    private class FailingRetriever : MmsRetriever {
        override fun get(url: String): ByteArray? = null
    }

    private class FakeApnResolver(private val mmsc: String?) : MmscUrlResolver {
        override fun mmscUrl(): String? = mmsc
    }
}