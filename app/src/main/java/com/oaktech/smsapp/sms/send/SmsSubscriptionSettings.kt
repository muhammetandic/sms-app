package com.oaktech.smsapp.sms.send

import android.content.Context
import androidx.core.content.edit

/**
 * The persisted choice of which SIM new SMS messages are sent on.
 *
 * The platform SMS manager sends on the system's default SMS subscription unless
 * told otherwise. This preference overrides that default; clearing it (null)
 * sends on whichever SIM the system currently uses.
 */
class SmsSubscriptionSettings(context: Context) {

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The chosen subscription id, or null to follow the system default. */
    fun preferredSubscriptionId(): Int? =
        preferences
            .getInt(KEY_PREFERRED_SUB_ID, NO_PREFERENCE)
            .takeIf { SmsSubscriptionSelector.isValidSubscriptionId(it) }

    /** Pins sending to [subscriptionId], or null to follow the system default. */
    fun setPreferredSubscriptionId(subscriptionId: Int?) {
        preferences.edit {
            if (subscriptionId != null && SmsSubscriptionSelector.isValidSubscriptionId(subscriptionId)) {
                putInt(KEY_PREFERRED_SUB_ID, subscriptionId)
            } else {
                remove(KEY_PREFERRED_SUB_ID)
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "sms_subscription"
        const val KEY_PREFERRED_SUB_ID = "preferred_sub_id"
        const val NO_PREFERENCE = -1
    }
}
