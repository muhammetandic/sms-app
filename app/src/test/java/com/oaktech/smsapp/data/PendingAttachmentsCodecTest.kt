package com.oaktech.smsapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingAttachmentsCodecTest {

    private fun part(
        fileName: String = "pic.jpg",
        contentType: String = "image/jpeg",
        filePath: String? = "/tmp/pic.jpg",
        byteSize: Long = 10,
    ) = AttachmentPart(fileName, contentType, filePath, byteSize)

    @Test
    fun `an empty queue encodes to null`() {
        assertNull(PendingAttachmentsCodec.encode(emptyList()))
    }

    @Test
    fun `a queue round-trips through its json`() {
        val queue = listOf(
            part(fileName = "a.jpg", contentType = "image/jpeg", byteSize = 1),
            part(fileName = "b.mp4", contentType = "video/mp4", filePath = "/tmp/b.mp4", byteSize = 2),
        )

        val decoded = PendingAttachmentsCodec.decode(PendingAttachmentsCodec.encode(queue))

        assertEquals(queue, decoded)
    }

    @Test
    fun `null and blank payloads decode to an empty queue`() {
        assertTrue(PendingAttachmentsCodec.decode(null).isEmpty())
        assertTrue(PendingAttachmentsCodec.decode("").isEmpty())
        assertTrue(PendingAttachmentsCodec.decode("   ").isEmpty())
    }

    @Test
    fun `garbage json decodes to an empty queue instead of throwing`() {
        assertTrue(PendingAttachmentsCodec.decode("{not json").isEmpty())
        assertTrue(PendingAttachmentsCodec.decode("[]").isEmpty())
    }

    @Test
    fun `a null file path survives the round-trip`() {
        val attachment = part(filePath = null)

        val decoded = PendingAttachmentsCodec.decode(PendingAttachmentsCodec.encode(listOf(attachment)))

        assertEquals(listOf(attachment), decoded)
    }
}