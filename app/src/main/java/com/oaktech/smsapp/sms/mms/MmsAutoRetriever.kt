package com.oaktech.smsapp.sms.mms

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the body of an MMS retrieve from the MMSC, mirroring [MmsPoster]. */
interface MmsRetriever {
    /** GETs [url] and returns the response body, or null on any failure. */
    fun get(url: String): ByteArray?
}

/**
 * GETs retrieve-conf bytes from the MMSC over HttpURLConnection.
 *
 * Like [HttpMmsPoster], this rides whatever transport Android currently routes
 * through; a carrier-grade stack would bind the socket to the
 * `NET_CAPABILITY_MMS` network first. The seam keeps that swap-in possible
 * without a live MMSC.
 */
class HttpMmsRetriever : MmsRetriever {

    override fun get(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = HttpMmsPoster.TIMEOUT_MILLIS
            connection.readTimeout = HttpMmsPoster.TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", HttpMmsPoster.HTTP_CONTENT_TYPE)
            if (connection.responseCode !in 200..299) return null
            return connection.inputStream.use { input ->
                ByteArrayOutputStream().use { out -> input.copyTo(out); out.toByteArray() }
            }
        } catch (throwable: Throwable) {
            return null
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Turns a notification.ind into the retrieve-conf bytes for it.
 *
 * A notification carries only the metadata (sender, subject, size) and the
 * X-Mms-Content-Location path; the body is fetched with a GET to the MMSC. The
 * MMSC base comes from the carrier APN through the same [MmscUrlResolver] seam
 * an outgoing send uses.
 */
class MmsAutoRetriever(
    private val retriever: MmsRetriever,
    private val mmscResolver: MmscUrlResolver,
) {
    fun retrieve(notification: MmsPdu): ByteArray? =
        notification.contentLocation?.let(::retrieve)

    fun retrieve(contentLocation: String): ByteArray? {
        val mmsc = mmscResolver.mmscUrl()?.trimEnd('/') ?: return null
        // Absolute locations (a CDN) skip the MMSC entirely; a relative path is
        // joined to it, tolerating a missing leading slash from sloppy carriers.
        val url = when {
            contentLocation.startsWith("http") -> contentLocation
            contentLocation.startsWith("/") -> "$mmsc$contentLocation"
            else -> "$mmsc/$contentLocation"
        }
        return retriever.get(url)
    }
}