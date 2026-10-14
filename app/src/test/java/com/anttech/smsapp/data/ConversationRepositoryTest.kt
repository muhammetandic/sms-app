package com.anttech.smsapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.testing.DirectExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Conversation lookup and creation under real concurrency.
 *
 * [ConversationDao.insertOrIgnore] plus the unique index on `address_key` is
 * what keeps two workers from each creating a thread for the same person, so
 * the case has to be driven from more than one thread: a single-threaded test
 * scheduler would serialise the callers and pass whether or not the guard
 * worked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationRepositoryTest {

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository
    private val conversations get() = repository.conversations

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SmsDatabase::class.java,
        )
            .setQueryExecutor(DirectExecutor)
            .setTransactionExecutor(DirectExecutor)
            .allowMainThreadQueries()
            .build()
        repository = MessageRepository(
            database.conversationDao(),
            database.messageDao(),
            database.draftDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun conversationRowCount(): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM conversations")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    @Test
    fun `concurrent finds for one address create exactly one conversation`() = runBlocking {
        val address = "+15551234567"

        // Every caller waits on the gate and is released together, so the
        // inserts really do overlap rather than running one after another.
        val gate = CompletableDeferred<Unit>()
        val ids = withContext(Dispatchers.IO) {
            (1..CALLERS).map { index ->
                async {
                    gate.await()
                    conversations.findOrCreateConversation(address, BASE_TIMESTAMP + index)
                }
            }.also { gate.complete(Unit) }.awaitAll()
        }

        assertEquals("every caller must win the same conversation", 1, ids.toSet().size)
        assertEquals("the unique index admits exactly one row", 1, conversationRowCount())
        assertEquals(
            "the surviving row is the one every caller resolved",
            ids.first(),
            conversations.conversationIdFor(address),
        )
    }

    private companion object {
        const val CALLERS = 32
        const val BASE_TIMESTAMP = 1_700_000_000_000L
    }
}
