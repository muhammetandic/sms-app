package com.anttech.smsapp.sms.send

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.anttech.smsapp.data.DataGraph

/**
 * Holds the process-wide send coordinator and its in-flight trackers.
 *
 * Send results arrive through a manifest-declared receiver, so the coordinator
 * cannot be scoped to an Activity or Compose tree.
 */
object SendGraph {

    @Volatile
    private var coordinatorInstance: SmsSendCoordinator? = null

    fun coordinator(context: Context): SmsSendCoordinator =
        coordinatorInstance ?: synchronized(this) {
            coordinatorInstance ?: SmsSendCoordinator(
                context = context.applicationContext,
                transport = PlatformSmsTransport(context),
                isRoleHeld = { DataGraph.get(context.applicationContext).roleManager.isHeld() },
            ).also { coordinatorInstance = it }
        }

    /**
     * Installs a coordinator for the current test, or clears it with null.
     *
     * Send results reach a manifest-declared receiver rather than the caller, so
     * the coordinator has to be process-wide and a test cannot hand a fake to the
     * code under test any other way. Without this the only path a service like
     * RespondViaMessageService could take would be the real radio.
     */
    @VisibleForTesting
    internal fun overrideForTest(coordinator: SmsSendCoordinator?) {
        synchronized(this) { coordinatorInstance = coordinator }
    }
}