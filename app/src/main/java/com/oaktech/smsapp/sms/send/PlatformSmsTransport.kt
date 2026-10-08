package com.oaktech.smsapp.sms.send

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.oaktech.smsapp.sms.receive.LogTags

/**
 * [SmsTransport] backed by the platform SMS manager.
 *
 * Dual-SIM: the manager is pinned to the subscription the user picked in
 * settings, or to the default SMS subscription when none was picked, instead of
 * `SmsManager.getDefault()`. On a dual-SIM device with no default subscription
 * set, `getDefault()` can return the wrong SIM or throw, which surfaces as a
 * send that silently never arrives.
 */
class PlatformSmsTransport(
    context: Context,
    private val settings: SmsSubscriptionSettings = SmsSubscriptionSettings(context),
) : SmsTransport {

    private val appContext = context.applicationContext

    override fun divideMessage(body: String): List<String> = smsManager().divideMessage(body).toList()

    @SuppressLint("MissingPermission")
    override fun sendMultipart(
        destination: String,
        parts: List<String>,
        sentIntents: List<PendingIntent>,
        deliveryIntent: PendingIntent,
    ) {
        if (parts.isEmpty()) return

        if (!hasSendPermission()) {
            Log.e(LogTags.SEND, "SEND_SMS is not granted; refusing to send to $destination")
            return
        }

        val manager = smsManager()
        val sent = ArrayList(sentIntents)

        if (parts.size == 1) {
            manager.sendTextMessage(destination, null, parts.first(), sent.firstOrNull(), deliveryIntent)
        } else {
            // The sent-intent and delivery-intent lists must have one entry per part,
            // otherwise parts report against the wrong index or the platform throws
            // IllegalArgumentException (deliveryIntents.size != parts.size).
            val delivered = ArrayList(List(parts.size) { deliveryIntent })
            manager.sendMultipartTextMessage(
                destination,
                null,
                ArrayList(parts),
                ArrayList(sent),
                delivered,
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun subscriptionId(): Int = effectiveSubscriptionId()

    @SuppressLint("MissingPermission")
    private fun smsManager(): SmsManager =
        SmsManager.getSmsManagerForSubscriptionId(effectiveSubscriptionId())

    /**
     * The chosen subscription when one is set, otherwise the platform default.
     * The user's pick is re-read on every send, so changing it in settings does
     * not need a freshly constructed transport.
     */
    private fun effectiveSubscriptionId(): Int =
        SmsSubscriptionSelector.effectiveSubscriptionId(
            preferred = settings.preferredSubscriptionId(),
            default = defaultSmsSubscriptionId(),
        )

    private fun defaultSmsSubscriptionId(): Int =
        runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    private fun hasSendPermission(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
}