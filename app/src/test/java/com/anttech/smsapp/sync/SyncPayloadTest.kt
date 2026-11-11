package com.anttech.smsapp.sync

import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageExportRow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The wire format on its own: what the backup file looks like and how it
 * survives the trip back.
 *
 * Robolectric because the format is JSON and the JSON implementation is
 * Android's `org.json`, which is a stub off-device.
 */
@RunWith(RobolectricTestRunner::class)
class SyncPayloadTest {

    private fun row(
        dedupeKey: String = "key-1",
        body: String = "hello",
        sentAt: Long = 1_000L,
        direction: MessageDirection = MessageDirection.INCOMING,
        partCount: Int = 1,
        addressKey: String = "+15551234567",
        displayAddress: String = "+15551234567",
    ) = MessageExportRow(
        dedupeKey = dedupeKey,
        body = body,
        sentAt = sentAt,
        direction = direction,
        partCount = partCount,
        addressKey = addressKey,
        displayAddress = displayAddress,
    )

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun `a record survives the round trip`() {
        val original = row(
            dedupeKey = "abc123",
            body = "meet at 8",
            sentAt = 1_700_000_000_000L,
            direction = MessageDirection.OUTGOING,
            partCount = 3,
            addressKey = "+447700900123",
            displayAddress = "07700 900123",
        )

        assertEquals(listOf(original), SyncPayload.decode(SyncPayload.encode("dev", 5L, listOf(original))).messages)
    }

    @Test
    fun `the payload is gzip, not plain json`() {
        val bytes = SyncPayload.encode("dev", 5L, listOf(row()))

        // 0x1f 0x8b is the gzip magic number. A plain-JSON file would start with
        // '{' and would not be compressed, which is the whole point of gzipping
        // a history on a phone's uplink.
        assertEquals(0x1f.toByte(), bytes[0])
        assertEquals(0x8b.toByte(), bytes[1])
    }

    @Test
    fun `the header names the device, the format and the export time`() {
        val text = gunzip(SyncPayload.encode("device-7", 1_700_000_000_000L, emptyList()))

        val header = text.lineSequence().first()
        assertTrue(header.contains("\"device\":\"device-7\""))
        assertTrue(header.contains("\"exportedAt\":1700000000000"))
        assertTrue(header.contains("\"v\":${SyncPayload.FORMAT_VERSION}"))
    }

    @Test
    fun `an empty store still writes a readable file`() {
        // A device with nothing to back up must still publish its header, or a
        // fresh phone could never learn that this device exists and has nothing.
        assertEquals(SyncContents(), SyncPayload.decode(SyncPayload.encode("dev", 5L, emptyList())))
    }

    /**
     * The one that would corrupt a whole file if it were wrong: a body with a
     * newline in it must not turn one record into two lines.
     */
    @Test
    fun `a body with newlines stays one record`() {
        val original = row(body = "line one\nline two\r\nline three")

        assertEquals(listOf(original), SyncPayload.decode(SyncPayload.encode("dev", 5L, listOf(original))).messages)
    }

    @Test
    fun `a record with non-ascii text survives`() {
        val original = row(body = "İyi günler 😀", addressKey = "+90", displayAddress = "Ayşe")

        assertEquals(listOf(original), SyncPayload.decode(SyncPayload.encode("dev", 5L, listOf(original))).messages)
    }

    @Test
    fun `blank lines between records are skipped`() {
        val body = "\n" + gunzip(SyncPayload.encode("dev", 5L, listOf(row())))

        assertEquals(1, SyncPayload.decode(gzip(body)).messages.size)
    }

    @Test
    fun `a record that is not json is dropped, not the file`() {
        val encoded = gunzip(SyncPayload.encode("dev", 5L, listOf(row(dedupeKey = "good"))))
        val corrupted = encoded + "this is not json\n"

        val records = SyncPayload.decode(gzip(corrupted)).messages

        assertEquals(listOf("good"), records.map { it.dedupeKey })
    }

    @Test
    fun `a record with no dedupe key is dropped`() {
        // Every field of a record has to be present to insert it; inventing a
        // key would write a row the merge could never match again.
        val raw = """{"b":"hi","t":1,"d":"IN","a":"+1","n":"+1"}"""

        assertEquals(emptyList<MessageExportRow>(), SyncPayload.decode(gzip("{\"v\":1}\n$raw\n")).messages)
    }

    @Test
    fun `a record with no address is dropped`() {
        val raw = """{"k":"abc","b":"hi","t":1,"d":"IN","n":"+1"}"""

        assertEquals(emptyList<MessageExportRow>(), SyncPayload.decode(gzip("{\"v\":1}\n$raw\n")).messages)
    }

    @Test
    fun `a record with an unknown direction is dropped`() {
        val raw = """{"k":"abc","b":"hi","t":1,"d":"SIDEWAYS","a":"+1","n":"+1"}"""

        assertEquals(emptyList<MessageExportRow>(), SyncPayload.decode(gzip("{\"v\":1}\n$raw\n")).messages)
    }

    @Test
    fun `a record with no part count defaults to one`() {
        val raw = """{"k":"abc","b":"hi","t":1,"d":"IN","a":"+1","n":"+1"}"""

        assertEquals(1, SyncPayload.decode(gzip("{\"v\":1}\n$raw\n")).messages.single().partCount)
    }

    @Test
    fun `a record with no display address falls back to the key`() {
        val raw = """{"k":"abc","b":"hi","t":1,"d":"IN","a":"+1555"}"""

        assertEquals("+1555", SyncPayload.decode(gzip("{\"v\":1}\n$raw\n")).messages.single().displayAddress)
    }

    @Test
    fun `an unknown field is ignored`() {
        // The forward-compatibility guarantee: a newer build may add a key, and
        // an older one reading the file must not choke on it.
        val raw = """{"k":"abc","b":"hi","t":1,"d":"IN","a":"+1","n":"+1","future":"whatever"}"""

        assertEquals("abc", SyncPayload.decode(gzip("{\"v\":1}\n$raw\n")).messages.single().dedupeKey)
    }

    // ------------------------------------------------------ conversation filings

    private fun filing(
        addressKey: String = "+15551234567",
        displayAddress: String = "Ayşe",
        lastMessageAt: Long = 4_000L,
        isSpam: Boolean = false,
        isArchived: Boolean = false,
        drafts: List<String> = emptyList(),
    ) = SyncConversation(
        addressKey = addressKey,
        displayAddress = displayAddress,
        lastMessageAt = lastMessageAt,
        isSpam = isSpam,
        isArchived = isArchived,
        drafts = drafts,
    )

    private fun encoded(vararg conversations: SyncConversation): ByteArray =
        SyncPayload.encode("dev", 5L, emptyList(), conversations.toList())

    @Test
    fun `a conversation's folder and drafts survive the round trip`() {
        val original = filing(
            addressKey = "+447700900123",
            displayAddress = "Ayşe",
            lastMessageAt = 1_700_000_000_000L,
            isSpam = true,
            drafts = listOf("first draft", "second draft"),
        )

        assertEquals(SyncContents(conversations = listOf(original)), SyncPayload.decode(encoded(original)))
    }

    @Test
    fun `an archived conversation is distinguished from a spam one`() {
        val archived = filing(isArchived = true)

        val decoded = SyncPayload.decode(encoded(archived)).conversations.single()

        assertTrue(decoded.isArchived)
        assertTrue(!decoded.isSpam)
    }

    @Test
    fun `a draft with newlines stays one filing`() {
        // The same guarantee the message records have: a newline in a body must
        // not split one line into two and turn the tail into a lost record.
        val original = filing(drafts = listOf("line one\nline two\r\nline three"))

        assertEquals(SyncContents(conversations = listOf(original)), SyncPayload.decode(encoded(original)))
    }

    @Test
    fun `a filing with no address key is dropped`() {
        val raw = """{"conversation":"","name":"Ayşe","spam":true}"""

        assertTrue(SyncPayload.decode(gzip("{\"v\":1}\n$raw\n")).conversations.isEmpty())
    }

    @Test
    fun `a filing line is not read as a message`() {
        // A filing has no dedupe key, so an older build reading this file drops
        // it - which is why the added line needed no format bump. This build
        // must sort it into the other half of the contents instead.
        val contents = SyncPayload.decode(encoded(filing()))

        assertTrue(contents.messages.isEmpty())
        assertEquals(1, contents.conversations.size)
    }

    @Test
    fun `a file with only messages decodes to no filings`() {
        val contents = SyncPayload.decode(SyncPayload.encode("dev", 5L, listOf(row())))

        assertTrue(contents.conversations.isEmpty())
    }

    @Test
    fun `a file from a newer format is rejected rather than guessed at`() {
        val bytes = gzip("{\"v\":${SyncPayload.FORMAT_VERSION + 1},\"device\":\"d\"}\n")

        assertThrows(IllegalArgumentException::class.java) { SyncPayload.decode(bytes) }
    }

    @Test
    fun `a file with no header is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SyncPayload.decode(gzip("not a header\n")) }
    }

    @Test
    fun `bytes that are not gzip are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncPayload.decode("definitely not gzip".toByteArray())
        }
    }

    @Test
    fun `a truncated gzip stream is rejected`() {
        // A connection that dropped mid-download. The reader must not hang or
        // half-parse it; the caller keeps the cursor back and retries.
        val bytes = SyncPayload.encode("dev", 5L, listOf(row()))

        assertThrows(IllegalArgumentException::class.java) {
            SyncPayload.decode(bytes.copyOf(bytes.size / 2))
        }
    }

    @Test
    fun `a header that is not an object is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SyncPayload.decode(gzip("12345\n")) }
    }
}
