package com.oaktech.smsapp.sms.mms

import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
 * Posts over HttpURLConnection on whatever transport Android currently routes
 * mobile data through.
 *
 * A production MMS stack additionally drives [android.net.ConnectivityManager]
 * `requestNetwork(NET_CAPABILITY_MMS)` so the POST rides the MMSC's own
 * network. That callback dance cannot be exercised without a live carrier, so
 * it is deliberately kept out of this class and behind the [MmsPoster] seam;
 * the swap-in for on-device validation is the whole point of the interface.
 */
class HttpMmsPoster : MmsPoster {

    override fun post(mmscUrl: String, pdu: ByteArray): Int {
        val connection = URL(mmscUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", HTTP_CONTENT_TYPE)
            connection.setRequestProperty("Accept", HTTP_CONTENT_TYPE)
            connection.setConnectTimeout(TIMEOUT_MILLIS)
            connection.setReadTimeout(TIMEOUT_MILLIS)
            DataOutputStream(connection.outputStream).use { it.write(pdu) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val HTTP_CONTENT_TYPE = "application/vnd.wap.mms-message"
        const val TIMEOUT_MILLIS = 30_000
    }
}