package com.anttech.smsapp.permission

import android.content.Context
import androidx.core.content.edit

/**
 * Records that the essential SMS permissions have been granted at least once.
 *
 * The first-launch gate must not block the app again later: the system revokes
 * the whole SMS group when the default-SMS role is taken away, and greeting
 * that with the first-run prompt hides the local message store behind a
 * permission the user can only get back by re-taking the role. This flag is the
 * difference between "has never been usable" and "was usable and something
 * revoked it", which no permission query can tell apart on its own — a denied
 * request and a revoked-after-role-loss grant look identical to
 * `checkSelfPermission`.
 */
object PermissionSetup {

    fun everGranted(context: Context): Boolean =
        preferences(context).getBoolean(KEY_GRANTED, false)

    fun markGranted(context: Context) {
        preferences(context).edit { putBoolean(KEY_GRANTED, true) }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "sms_setup"
    private const val KEY_GRANTED = "essential_granted"
}
