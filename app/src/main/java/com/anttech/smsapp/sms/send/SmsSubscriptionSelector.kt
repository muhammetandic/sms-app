package com.anttech.smsapp.sms.send

/**
 * Decides which subscription a send goes out on.
 *
 * The stored preference wins when it names a subscription id Android still
 * considers valid; otherwise the platform's default SMS subscription stands. A
 * cleared or stale preference can therefore never pin a message to a SIM that no
 * longer exists.
 *
 * The validity rule is kept here rather than calling
 * `SubscriptionManager.isValidSubscriptionId`: platform implementations of that
 * helper disagree about id 0 across API levels, and ids here come from
 * `SubscriptionManager.activeSubscriptionInfoList`, which are always positive.
 */
object SmsSubscriptionSelector {

    fun effectiveSubscriptionId(preferred: Int?, default: Int): Int =
        if (preferred != null && isValidSubscriptionId(preferred)) {
            preferred
        } else {
            default
        }

    fun isValidSubscriptionId(subscriptionId: Int): Boolean = subscriptionId > 0
}