package com.oaktech.smsapp.sms.receive

import java.security.MessageDigest

/**
 * Identity of an inbound message for deduplication.
 *
 * A default SMS app sees the same message more than once in normal operation:
 * the platform writes it to the Telephony provider and also hands the parts to
 * this app via SMS_DELIVER. Without a stable key the message lands in the
 * thread twice. Provider import uses this same key, so a message lived [sic]
 * through SMS_DELIVER and one imported from the provider collide and one is
 * dropped.
 */
data class InboundMessageKey(
    val address: String,
    val timestampMillis: Long,
    val body: String,
) {
    val hash: String
        get() = digest("$address|$timestampMillis|$body")

    companion object {
        fun of(message: InboundMessage): InboundMessageKey =
            of(message.address, message.timestampMillis, message.body)

        /**
         * Provider rows carry the same address/date/body fields as an SMS_DELIVER
         * part, so the import path derives the identical key. The address is
         * trimmed on both paths so a provider row and a live part for the same
         * message always hash alike.
         */
        fun of(address: String, timestampMillis: Long, body: String): InboundMessageKey =
            InboundMessageKey(address.trim(), timestampMillis, body)

        private fun digest(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}