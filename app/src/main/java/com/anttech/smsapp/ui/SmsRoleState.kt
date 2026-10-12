package com.anttech.smsapp.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * Live view of whether this app holds the default SMS role.
 *
 * The role can be granted or revoked outside the app, including from Settings
 * while this process is still alive, so the value is re-read on every resume
 * rather than cached at startup. Callers also read [isHeld] directly to decide
 * whether to offer sending at all.
 */
class SmsRoleState internal constructor(
    private val context: Context,
    private val isRoleHeld: (Context) -> Boolean,
) {
    var isHeld by mutableStateOf(isRoleHeld(context))
        private set

    fun refresh() {
        isHeld = isRoleHeld(context)
    }

    /** The role service can publish the new holder shortly after the result returns. */
    suspend fun refreshAfterRequest() {
        repeat(5) { attempt ->
            refresh()
            if (isHeld || attempt == 4) return
            delay(200)
        }
    }

    fun requestIntent(): Intent? =
        context.getSystemService(RoleManager::class.java)
            ?.createRequestRoleIntent(RoleManager.ROLE_SMS)

    companion object {
        fun isDefaultSmsApp(context: Context): Boolean {
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) return false
            return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        }
    }
}

/** Returns a role state kept in sync with the lifecycle resume events. */
@Composable
fun rememberSmsRoleState(): SmsRoleState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val roleState = remember(context) { SmsRoleState(context, SmsRoleState::isDefaultSmsApp) }

    DisposableEffect(roleState, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) roleState.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return roleState
}
