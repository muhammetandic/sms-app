package com.oaktech.smsapp.testing

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.Executor

/**
 * Executor that runs work inline on the calling thread.
 *
 * Room dispatches queries and invalidation callbacks on its own executor. With
 * the default arch-IO executor those land on a real background thread after the
 * test's virtual clock has already moved on, so a `Flow` never emits and Turbine
 * times out. Running them inline makes invalidation synchronous with the write
 * that triggered it, which is what the assertions are actually about.
 */
object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/**
 * In-memory Room database whose reads and invalidation run inline.
 *
 * Use instead of [Room.inMemoryDatabaseBuilder] in tests that observe a DAO
 * `Flow` and then write through it.
 */
fun <D : RoomDatabase> inlineInMemoryDatabase(
    context: Context,
    klass: Class<D>,
): D = Room.inMemoryDatabaseBuilder(context, klass)
    .setQueryExecutor(DirectExecutor)
    .setTransactionExecutor(DirectExecutor)
    .allowMainThreadQueries()
    .build()