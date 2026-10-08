package com.oaktech.smsapp.sms.mms

import java.io.DataOutputStream

/**
 * The one seam the MMSC transaction needs.
 *
 * MMS send is an HTTP POST of the send-req PDU to the carrier's MMSC. Splitting
 * the network client out is what makes [MmsSendCoordinator] unit-testable; the
 * real leg ([HttpMmsPoster]) is a thin HttpURLConnection.
 */
interface MmsPoster {
    /**
     * POSTs the PDU to the MMSC and returns the HTTP status. Anything but a
     * 2xx counts as a failed transaction; a throw represents no transport.
     */
    fun post(mmscUrl: String, pdu: ByteArray): Int

    /** Whether an HTTP status is a successful MMSC transaction. */
    fun isSuccess(status: Int): Boolean = status in 200..299
}

/**
 * Posts over HttpURLConnection on the route [MmsConnectionOpener] picks, which
 * in production is the carrier's MMS bearer.
 *
 * The network binding lives in the opener rather than here so this class stays
 * a straight HTTP POST and the bearer's request/hold/release lifecycle is
 * written once for every MMSC leg (send, retrieve, acknowledge).
 */
class HttpMmsPoster(
    private val opener: MmsConnectionOpener = DefaultRouteMmsConnectionOpener(),
) : MmsPoster {

    override fun post(mmscUrl: String, pdu: ByteArray): Int = opener.open(mmscUrl) { connection ->
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", HTTP_CONTENT_TYPE)
        connection.setRequestProperty("Accept", HTTP_CONTENT_TYPE)
        connection.setConnectTimeout(TIMEOUT_MILLIS)
        connection.setReadTimeout(TIMEOUT_MILLIS)
        DataOutputStream(connection.outputStream).use { it.write(pdu) }
        connection.responseCode
    }

    companion object {
        const val HTTP_CONTENT_TYPE = "application/vnd.wap.mms-message"
        const val TIMEOUT_MILLIS = 30_000
    }
}