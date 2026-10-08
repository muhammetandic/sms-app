package com.oaktech.smsapp.sms.mms

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The real HTTP legs against a socket on the loopback interface.
 *
 * The MMSC network binding itself needs a carrier, but the seam it was moved
 * into has to keep the request on the wire exactly as before: request line,
 * headers, body, status back.
 */
class HttpMmsTransportTest {

    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        runCatching { server?.close() }
        server = null
    }

    @Test
    fun `a post reports the status the mmsc answered`() {
        val url = serve("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

        val status = HttpMmsPoster(DefaultRouteMmsConnectionOpener()).post(url, byteArrayOf(1, 2, 3))

        assertEquals(200, status)
    }

    @Test
    fun `a post carries the pdu bytes unchanged`() {
        val pdu = byteArrayOf(0x8C.toByte(), 0x81.toByte(), 0x11, 0x00, 0x0A)
        val url = serve("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

        HttpMmsPoster(DefaultRouteMmsConnectionOpener()).post(url, pdu)

        assertArrayEquals(pdu, capturedBody.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a non-2xx status is returned rather than swallowed`() {
        val url = serve("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

        val status = HttpMmsPoster(DefaultRouteMmsConnectionOpener()).post(url, byteArrayOf(1))

        assertEquals(500, status)
    }

    @Test
    fun `a get returns the response body`() {
        val url = serve("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello")

        val body = HttpMmsRetriever(DefaultRouteMmsConnectionOpener()).get(url)

        assertArrayEquals("hello".toByteArray(), body)
    }

    @Test
    fun `a get of a non-2xx response returns null`() {
        val url = serve("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

        assertNull(HttpMmsRetriever(DefaultRouteMmsConnectionOpener()).get(url))
    }

    private val capturedBody = CapturedBody()

    /** Serves exactly one request with [response], capturing what the client sent. */
    private fun serve(response: String): String {
        val socket = ServerSocket(0)
        server = socket
        val url = "http://127.0.0.1:${socket.localPort}/mms"
        Thread {
            try {
                socket.accept().use { connection ->
                    capturedBody.set(readRequest(connection.getInputStream()))
                    connection.getOutputStream().apply {
                        write(response.toByteArray())
                        flush()
                    }
                }
            } catch (_: Throwable) {
                // The socket is closed by tearDown once the test is done with it.
            }
        }.start()
        return url
    }

    /** Reads headers, then the Content-Length body, so the client can finish writing. */
    private fun readRequest(input: InputStream): ByteArray {
        val raw = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var headersEnd = -1
        var contentLength = 0
        var bodyStart = -1
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            raw.write(buffer, 0, read)
            val bytes = raw.toByteArray()
            if (headersEnd < 0) {
                headersEnd = bytes.endOfHeaders()
                if (headersEnd >= 0) {
                    val headers = String(bytes, 0, headersEnd, Charsets.ISO_8859_1)
                    contentLength = headers.lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                    bodyStart = headersEnd + 4
                }
            }
            if (headersEnd >= 0 && bytes.size - bodyStart >= contentLength) break
        }
        val bytes = raw.toByteArray()
        if (bodyStart < 0 || bodyStart >= bytes.size) return ByteArray(0)
        return bytes.copyOfRange(bodyStart, minOf(bytes.size, bodyStart + contentLength))
    }

    private fun ByteArray.endOfHeaders(): Int {
        for (index in 0 until size - 3) {
            if (this[index] == '\r'.code.toByte() && this[index + 1] == '\n'.code.toByte() &&
                this[index + 2] == '\r'.code.toByte() && this[index + 3] == '\n'.code.toByte()
            ) {
                return index
            }
        }
        return -1
    }

    private class CapturedBody {
        private val latch = CountDownLatch(1)
        private var body: ByteArray = ByteArray(0)

        fun set(bytes: ByteArray) {
            body = bytes
            latch.countDown()
        }

        fun get(timeout: Long, unit: TimeUnit): ByteArray {
            latch.await(timeout, unit)
            return body
        }
    }
}
