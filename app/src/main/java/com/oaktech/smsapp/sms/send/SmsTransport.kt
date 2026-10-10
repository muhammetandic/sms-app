package com.oaktech.smsapp.sms.send

import android.app.PendingIntent

/**
 * The part of the platform SMS manager this app uses, behind an interface so
 * send orchestration can be tested without a device or a radio.
 */
interface SmsTransport {

    /** Whether the platform can accept a send at this moment. */
    fun canSend(): Boolean = true

    /** Splits a body into the PDUs that must be sent. */
    fun divideMessage(body: String): List<String>

    /**
     * Sends every part.
     *
     * @param sentIntents one intent per part, reporting the result of that part.
     *   Order matches the list returned by [divideMessage].
     * @param deliveryIntent reports that the message reached the network, and
     *   may fire once per part.
     */
    fun sendMultipart(
        destination: String,
        parts: List<String>,
        sentIntents: List<PendingIntent>,
        deliveryIntent: PendingIntent,
    )

    /**
     * The subscription the transport would send on, for recording which SIM a
     * message went out on. `INVALID_SUBSCRIPTION_ID` when there is none.
     */
    fun subscriptionId(): Int
}
