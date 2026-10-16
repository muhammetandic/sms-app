package com.anttech.smsapp.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anttech.smsapp.data.DataGraph

/**
 * Durable adapter between the Drive sync and a process that may not be the one
 * that asked for it.
 *
 * A worker rather than a call straight from the UI because a sync is a network
 * round trip that has to survive the screen closing and wait for a connection,
 * exactly like the provider import. It resolves the store through [DataGraph]
 * because WorkManager constructs it, so nothing can inject it.
 */
class SyncWork(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result =
        when (val outcome = DataGraph.get(applicationContext).syncRepository.sync()) {
            // A settled state. Nothing was wrong, or nothing could be done about
            // it: retrying cannot create a Google grant, and only the settings
            // screen can, so a backoff loop here would just burn battery until
            // the user acts.
            is SyncOutcome.Success,
            SyncOutcome.Disabled,
            SyncOutcome.NeedsSignIn,
            -> Result.success()

            is SyncOutcome.Failed -> {
                Log.w(TAG, "Drive sync failed: ${outcome.message}")
                Result.retry()
            }
        }

    private companion object {
        const val TAG = "SyncWork"
    }
}
