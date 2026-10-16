package com.anttech.smsapp.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.local.AddressNormalizer
import com.anttech.smsapp.data.local.ConversationEntity
import com.anttech.smsapp.data.local.ImportStateEntity
import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageEntity
import com.anttech.smsapp.data.local.MessageExportRow
import com.anttech.smsapp.data.local.MessageStatus
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.testing.MainDispatcherRule
import com.anttech.smsapp.testing.inlineInMemoryDatabase
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sync as a whole: what it publishes, what it merges, and what it refuses to
 * touch.
 *
 * Robolectric because the store is a real Room schema (the unique index on
 * `dedupe_key` is the whole basis of the merge) and the preferences are
 * `SharedPreferences`. Drive is a fake, which is the point of the [DriveClient]
 * seam: the orchestrating code - naming, cursors, merge rules - is what these
 * tests are about, and none of it needs a network.
 */
@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: SmsDatabase
    private lateinit var settings: SyncSettings
    private lateinit var drive: FakeDriveClient
    private lateinit var repository: SyncRepository

    /** A fixed clock, so a recorded timestamp is an assertion rather than a guess. */
    private val now = 1_700_000_000_000L

    private val sender = "+15551234567"
    private val senderKey get() = AddressNormalizer.conversationKey(sender)

    /** A file written by another phone in the same account. */
    private val peerFileName = "messages-peer-device.jsonl.gz"

    private val ownFileName get() = "${SyncRepository.FILE_PREFIX}${settings.deviceId()}.jsonl.gz"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = inlineInMemoryDatabase(context, SmsDatabase::class.java)
        settings = SyncSettings(context).apply { setEnabled(true) }
        drive = FakeDriveClient()
        repository = SyncRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            database = database,
            settings = settings,
            drive = drive,
            now = { now },
        )
    }

    @After
    fun tearDown() = database.close()

    // ---------------------------------------------------------------- publish

    @Test
    fun `a first sync publishes this device's file`() = runTest(mainDispatcherRule.testDispatcher) {
        seedLocalMessage(body = "mine")

        assertEquals(SyncOutcome.Success(0), repository.sync())

        val payload = SyncPayload.decode(drive.content(ownFileName)!!)
        assertEquals(listOf("mine"), payload.map { it.body })
    }

    @Test
    fun `a second sync replaces this device's file rather than adding another`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedLocalMessage(body = "first")
            repository.sync()
            seedLocalMessage(body = "second", sentAt = 2_000L)
            repository.sync()

            // One file per device is what keeps two phones from fighting over the
            // same name; the second write must find and replace the first.
            assertEquals(1, drive.creates)
            assertEquals(1, drive.replaces)
            assertEquals(setOf(ownFileName), drive.names())
        }

    @Test
    fun `the published file holds every stored message`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedLocalMessage(body = "one", sentAt = 1_000L)
            seedLocalMessage(body = "two", sentAt = 2_000L)
            seedLocalMessage(address = "+15559999999", body = "other", sentAt = 3_000L)

            repository.sync()

            val bodies = SyncPayload.decode(drive.content(ownFileName)!!).map { it.body }
            assertEquals(listOf("one", "two", "other"), bodies)
        }

    // ------------------------------------------------------------------ merge

    @Test
    fun `a peer's file is merged into the store`() = runTest(mainDispatcherRule.testDispatcher) {
        drive.put(peerFileName, peerFile(remoteRow(body = "hi from peer")))

        assertEquals(SyncOutcome.Success(1), repository.sync())

        val stored = storedMessages().single()
        assertEquals("hi from peer", stored.body)
        assertEquals(MessageDirection.INCOMING, stored.direction)
        // A message that arrived on the other phone did arrive; this one claims
        // no provider row here, because there is none.
        assertEquals(MessageStatus.DELIVERED, stored.status)
        assertNull(stored.providerId)
        assertNull(stored.deliveredAt)
    }

    @Test
    fun `a restored outgoing message is stored as sent, not delivered`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.put(peerFileName, peerFile(remoteRow(direction = MessageDirection.OUTGOING)))

            repository.sync()

            // No delivery report for this message ever reached this phone, so
            // claiming DELIVERED would assert something that did not happen.
            assertEquals(MessageStatus.SENT, storedMessages().single().status)
        }

    @Test
    fun `a restored conversation is not marked unread`() = runTest(mainDispatcherRule.testDispatcher) {
        drive.put(peerFileName, peerFile(remoteRow()))

        repository.sync()

        // The user has already lived through this history; badges for it would
        // be a lie they would then have to clear by hand.
        assertEquals(0, storedConversation()!!.unreadCount)
    }

    @Test
    fun `the same records arriving twice are stored once`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val payload = peerFile(remoteRow())
            drive.put(peerFileName, payload)
            assertEquals(SyncOutcome.Success(1), repository.sync())

            // A new revision carrying the same records: the cursor cannot help,
            // so the unique dedupe key is what has to absorb the repeat.
            drive.put(peerFileName, payload)

            assertEquals(SyncOutcome.Success(0), repository.sync())
            assertEquals(1, storedMessages().size)
        }

    @Test
    fun `records from two peers land once each`() = runTest(mainDispatcherRule.testDispatcher) {
        val record = remoteRow()
        drive.put(peerFileName, peerFile(record, device = "peer-a"))
        drive.put("messages-peer-b.jsonl.gz", peerFile(record, device = "peer-b"))

        // Whichever file is read first inserts it; the second is a no-op. The
        // count reports what the store gained, which is one, not two.
        assertEquals(SyncOutcome.Success(1), repository.sync())
        assertEquals(1, storedMessages().size)
    }

    @Test
    fun `messages from different addresses become separate conversations`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.put(
                peerFileName,
                peerFile(
                    remoteRow(address = "+15551234567"),
                    remoteRow(address = "+15557654321", body = "second"),
                ),
            )

            repository.sync()

            assertEquals(2, database.conversationDao().observeConversations().first().size)
        }

    @Test
    fun `every field comes across with the message`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.put(
                peerFileName,
                peerFile(
                    remoteRow(
                        body = "long one",
                        sentAt = 4_242L,
                        partCount = 3,
                        displayAddress = "Ayşe",
                    ),
                ),
            )

            repository.sync()

            val stored = storedMessages().single()
            assertEquals("long one", stored.body)
            assertEquals(4_242L, stored.sentAt)
            assertEquals(3, stored.partCount)
            assertEquals("Ayşe", storedConversation()!!.displayAddress)
        }

    // ----------------------------------------------------------------- cursor

    @Test
    fun `a peer file whose revision has not changed is not downloaded again`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.put(peerFileName, peerFile(remoteRow()))

            repository.sync()
            assertEquals(1, drive.downloadCount)

            repository.sync()

            // The whole point of the cursor: a steady-state sync over an
            // unchanged folder transfers nothing but this device's own snapshot.
            assertEquals(1, drive.downloadCount)
        }

    @Test
    fun `this device's own file is never read back`() = runTest(mainDispatcherRule.testDispatcher) {
        seedLocalMessage(body = "mine")
        repository.sync()
        seedLocalMessage(body = "later", sentAt = 2_000L)

        repository.sync()

        assertEquals(0, drive.downloadCount)
        assertEquals(2, storedMessages().size)
    }

    @Test
    fun `a file that is not part of the backup is ignored`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.put("notes.txt", "not ours".toByteArray())

            assertEquals(SyncOutcome.Success(0), repository.sync())
            assertEquals(0, drive.downloadCount)
        }

    @Test
    fun `an unreadable peer file is retried rather than written off`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.put(peerFileName, "not gzip at all".toByteArray())

            assertEquals(SyncOutcome.Success(0), repository.sync())
            assertEquals(1, drive.downloadCount)

            repository.sync()

            // The cursor must not advance past a file that was never read, or a
            // transient corruption would silently cost those messages forever.
            assertEquals(2, drive.downloadCount)
        }

    // ------------------------------------------------------- existing threads

    @Test
    fun `an existing conversation keeps its display address and its flags`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedLocalMessage(body = "mine")
            val existing = storedConversation()!!
            database.conversationDao().update(
                existing.copy(displayAddress = "Ayşe", isMuted = true, isPinned = true),
            )

            drive.put(peerFileName, peerFile(remoteRow(displayAddress = "+15551234567")))
            repository.sync()

            // The peer's plain number must not overwrite the local name, and its
            // flags are this phone's filing choices, not facts about the messages.
            val after = storedConversation()!!
            assertEquals("Ayşe", after.displayAddress)
            assertTrue(after.isMuted)
            assertTrue(after.isPinned)
        }

    @Test
    fun `a restored message advances the conversation it belongs to`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedLocalMessage(sentAt = 1_000L)
            drive.put(peerFileName, peerFile(remoteRow(sentAt = 9_000L)))

            repository.sync()

            assertEquals(9_000L, storedConversation()!!.lastMessageAt)
        }

    @Test
    fun `an older restored message does not move the timestamp backwards`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedLocalMessage(sentAt = 9_000L)
            drive.put(peerFileName, peerFile(remoteRow(sentAt = 1_000L)))

            repository.sync()

            assertEquals(9_000L, storedConversation()!!.lastMessageAt)
        }

    @Test
    fun `a restore does not disturb the provider import watermark`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The watermark belongs to the provider import. A restore that moved
            // it would make the import believe it had already read provider rows
            // it has never seen, and those messages would never be imported.
            database.importStateDao().save(ImportStateEntity(providerHighWater = 500L))
            drive.put(peerFileName, peerFile(remoteRow()))

            repository.sync()

            assertEquals(500L, database.importStateDao().providerHighWater())
        }

    // ---------------------------------------------------------------- outcome

    @Test
    fun `sync does nothing while it is switched off`() = runTest(mainDispatcherRule.testDispatcher) {
        settings.setEnabled(false)
        drive.put(peerFileName, peerFile(remoteRow()))

        assertEquals(SyncOutcome.Disabled, repository.sync())

        assertEquals(0, drive.listCount)
        assertEquals(0, drive.creates)
        assertTrue(storedMessages().isEmpty())
    }

    @Test
    fun `a revoked grant is reported as needing sign-in`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.failure = SignInRequiredException()

            assertEquals(SyncOutcome.NeedsSignIn, repository.sync())
            assertEquals(SyncStatus.NEEDS_SIGN_IN, settings.state.value.status)
        }

    @Test
    fun `a failed request is recorded with its reason`() = runTest(mainDispatcherRule.testDispatcher) {
        drive.failure = IOException("no route to host")

        val outcome = repository.sync()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(SyncStatus.FAILED, settings.state.value.status)
        assertEquals("no route to host", settings.state.value.lastError)
    }

    @Test
    fun `a successful sync records when it ran and what it gained`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.put(
                peerFileName,
                peerFile(remoteRow(body = "one"), remoteRow(body = "two", sentAt = 6_000L)),
            )

            repository.sync()

            assertEquals(SyncStatus.SUCCESS, settings.state.value.status)
            assertEquals(now, settings.state.value.lastSyncedAt)
            assertEquals(2, settings.state.value.lastMergedCount)
        }

    @Test
    fun `a revoked grant can be granted again and the next sync works`() =
        runTest(mainDispatcherRule.testDispatcher) {
            drive.failure = SignInRequiredException()
            repository.sync()
            assertEquals(SyncStatus.NEEDS_SIGN_IN, settings.state.value.status)

            // The failure must leave nothing behind that blocks the retry.
            drive.failure = null
            drive.put(peerFileName, peerFile(remoteRow()))

            assertEquals(SyncOutcome.Success(1), repository.sync())
            assertEquals(SyncStatus.SUCCESS, settings.state.value.status)
        }

    @Test
    fun `a failed sync still leaves this device's own file published next time`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedLocalMessage(body = "mine")
            drive.failure = IOException("flaky radio")
            repository.sync()
            assertTrue(drive.names().isEmpty())

            drive.failure = null
            seedLocalMessage(body = "later", sentAt = 2_000L)
            repository.sync()

            assertEquals(listOf("mine", "later"), SyncPayload.decode(drive.content(ownFileName)!!).map { it.body })
        }

    // ------------------------------------------------------------------ setup

    /** A row as another phone would have published it. */
    private fun remoteRow(
        address: String = "+15551234567",
        body: String = "from the other phone",
        sentAt: Long = 5_000L,
        direction: MessageDirection = MessageDirection.INCOMING,
        partCount: Int = 1,
        displayAddress: String? = null,
        dedupeKey: String? = null,
    ) = MessageExportRow(
        dedupeKey = dedupeKey ?: "remote-$address-$sentAt-$body",
        body = body,
        sentAt = sentAt,
        direction = direction,
        partCount = partCount,
        // The key is computed the way the app computes it, so a peer row for a
        // number this phone already knows lands in the same conversation.
        addressKey = AddressNormalizer.conversationKey(address),
        displayAddress = displayAddress ?: address,
    )

    private fun peerFile(vararg records: MessageExportRow, device: String = "peer") =
        SyncPayload.encode(device, exportedAt = 1L, messages = records.toList())

    private suspend fun seedLocalMessage(
        address: String = "+15551234567",
        body: String = "mine",
        sentAt: Long = 1_000L,
        direction: MessageDirection = MessageDirection.INCOMING,
    ) {
        val conversationDao = database.conversationDao()
        val key = AddressNormalizer.conversationKey(address)
        conversationDao.insertOrIgnore(
            ConversationEntity(addressKey = key, displayAddress = address, lastMessageAt = sentAt),
        )
        val conversationId = conversationDao.getByAddressKey(key)!!.id
        database.messageDao().insert(
            MessageEntity(
                conversationId = conversationId,
                dedupeKey = "local-$address-$sentAt-$body",
                body = body,
                sentAt = sentAt,
                direction = direction,
                status = if (direction == MessageDirection.INCOMING) {
                    MessageStatus.DELIVERED
                } else {
                    MessageStatus.SENT
                },
            ),
        )
    }

    private suspend fun storedMessages(address: String = sender): List<MessageEntity> {
        val conversationId = database.conversationDao().getByAddressKey(
            AddressNormalizer.conversationKey(address),
        )?.id ?: return emptyList()
        return database.messageDao().observeMessages(conversationId).first()
    }

    private suspend fun storedConversation(address: String = sender): ConversationEntity? =
        database.conversationDao().getByAddressKey(AddressNormalizer.conversationKey(address))

    /**
     * A Drive app-data folder in memory.
     *
     * Every write bumps that file's revision, which is what the cursor logic
     * reads, and the counters make "was this file actually transferred?" an
     * assertion rather than a guess.
     */
    private class FakeDriveClient : DriveClient {

        private val contents = linkedMapOf<String, ByteArray>()
        private val fileIds = mutableMapOf<String, String>()
        private val revisions = mutableMapOf<String, Long>()
        private var nextId = 1
        private var nextRevision = 100L

        var listCount = 0
            private set
        var downloadCount = 0
            private set
        var creates = 0
            private set
        var replaces = 0
            private set

        /** Injected failure for the next call, to exercise the error paths. */
        var failure: Exception? = null

        fun put(name: String, content: ByteArray) {
            fileIds.getOrPut(name) { "file-${nextId++}" }
            contents[name] = content
            revisions[name] = nextRevision++
        }

        fun content(name: String): ByteArray? = contents[name]

        fun names(): Set<String> = contents.keys.toSet()

        override suspend fun listAppData(): List<DriveFile> {
            listCount += 1
            failure?.let { throw it }
            return contents.map { (name, content) ->
                DriveFile(
                    id = fileIds.getValue(name),
                    name = name,
                    version = revisions.getValue(name),
                    size = content.size.toLong(),
                )
            }
        }

        override suspend fun download(fileId: String): ByteArray {
            downloadCount += 1
            failure?.let { throw it }
            val name = fileIds.entries.single { it.value == fileId }.key
            return contents.getValue(name)
        }

        override suspend fun upload(name: String, content: ByteArray, fileId: String?): DriveFile {
            if (fileId == null) {
                creates += 1
                fileIds.getOrPut(name) { "file-${nextId++}" }
            } else {
                replaces += 1
            }
            contents[name] = content
            val version = nextRevision++
            revisions[name] = version
            return DriveFile(
                id = fileIds.getValue(name),
                name = name,
                version = version,
                size = content.size.toLong(),
            )
        }
    }
}
