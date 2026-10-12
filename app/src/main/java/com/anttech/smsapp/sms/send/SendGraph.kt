package com.anttech.smsapp.sms.send

import android.content.Context
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
}