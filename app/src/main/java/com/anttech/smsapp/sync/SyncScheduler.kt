package com.anttech.smsapp.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the WorkManager schedule for the Drive sync.
 *
 * Scheduling lives here rather than in [SyncRepository] or the settings
 * ViewModel on purpose: the repository stays a plain callable, and the
 * ViewModel stays free of WorkManager so it can be tested off-device. The
 * settings screen reconciles the schedule with the stored preference when it
 * opens and when the switch is flipped.
 *
 * Deliberately not called from `Application.onCreate`. WorkManager persists its
 * own queue across process death and reboots, so a startup pass would repair a
 * queue that is already correct, and it would start WorkManager in every JVM
 * test the suite runs.
 */
object SyncScheduler {

    /**
     * Automatic cadence. Drive offers no push (its change feed needs a public
     * webhook to watch), so a phone has to poll, and six hours is often enough
     * to be useful without waking the radio for a folder that mostly has not
     * changed.
     */
    private const val INTERVAL_HOURS = 6L

    private const val PERIODIC_WORK_NAME = "sms-drive-sync"

    /**
     * Makes the schedule match the stored preference: enqueues the periodic
     * sync when it is on, cancels it when it is off.
     *
     * Called every time the settings screen opens and every time the switch is
     * flipped, so the schedule recovers on its own if it was ever lost (a
     * WorkManager database reset, a restore from a device backup). `KEEP` leaves
     * an already-enqueued request alone, so re-running it cannot reset the
     * interval and push the next run out of reach of a user who opens the
     * screen often.
     */
    fun reconcile(context: Context) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        if (SyncSettings(appContext).isEnabled()) {
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWork>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(connected())
                    // A failed attempt should back off rather than hammer a
                    // radio that is present but not actually reaching Drive.
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    /** Sync must wait for a network; nothing else about the phone matters. */
    private fun connected(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private const val BACKOFF_SECONDS = 30L
}
