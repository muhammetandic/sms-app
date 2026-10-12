package com.anttech.smsapp.notify

import android.content.Context
import androidx.core.content.edit

/** Stores which quick actions should be shown on incoming-message notifications. */
class NotificationActionPreferences(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(action: String): Boolean =
        preferences.getBoolean(keyFor(action), true)

    fun setEnabled(action: String, enabled: Boolean) {
        preferences.edit { putBoolean(keyFor(action), enabled) }
    }

    private fun keyFor(action: String): String = "$KEY_PREFIX$action"

    private companion object {
        const val PREFERENCES_NAME = "notification_action_preferences"
        const val KEY_PREFIX = "enabled_"
    }
}
