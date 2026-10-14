package com.anttech.smsapp.sms.send

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager

/** One active phone subscription the user can choose to send on. */
data class SmsSubscription(
    val subscriptionId: Int,
    val displayName: CharSequence,
    val carrierName: CharSequence?,
) {
    /** Primary label for the settings row; always present. */
    val label: CharSequence
        get() = displayName.ifBlank { carrierName?.takeIf { it.isNotBlank() } ?: "SIM $subscriptionId" }
}

/**
 * The subscriptions currently active on the device.
 *
 * Empty without READ_PHONE_STATE or when the platform query fails, so callers
 * hide the choice rather than crash or offer a list that cannot be used.
 */
fun activeSmsSubscriptions(context: Context): List<SmsSubscription> {
    val granted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return emptyList()

    val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
    return runCatching {
        manager.activeSubscriptionInfoList
            ?.map { info ->
                val displayName = info.displayName?.takeIf { it.isNotBlank() }
                val carrierName = info.carrierName?.takeIf { it.isNotBlank() }
                SmsSubscription(
                    subscriptionId = info.subscriptionId,
                    displayName = displayName ?: carrierName ?: "SIM ${info.subscriptionId}",
                    carrierName = carrierName,
                )
            }
            .orEmpty()
    }.getOrDefault(emptyList())
}