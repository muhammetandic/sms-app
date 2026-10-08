package com.oaktech.smsapp.data.telephony

import com.oaktech.smsapp.data.local.AddressNormalizer
import com.oaktech.smsapp.data.local.MessageDirection
import com.oaktech.smsapp.data.local.MessageStatus

/**
 * Converts a provider row into the local store's shape.
 *
 * Kept separate from the DAO work so the mapping is testable without Room, and
 * so the dedupe key is defined in exactly one place.
 */
object ProviderMessageMapper {

    /**
     * Dedupe key for an imported OUTGOING provider message.
     *
     * Outgoing rows are keyed on their provider id because the app itself
     * records outgoing sends with a random nonce and never writes them to the
     * provider, so the only source of sent rows here is history from another
     * app. Incoming rows instead reuse the live path's address/date/body
     * fingerprint so a message this app already received over SMS_DELIVER is
     * never stored twice.
     *
     * The two provider tables hand out overlapping id ranges (each starts its
     * own _id sequence at 1), so rows read from the MMS table are namespaced
     * separately from SMS rows. The SMS namespace is deliberately left as
     * `provider:$id` so rows imported before this split keep matching on re-run.
     */
    fun dedupeKey(providerId: Long, mms: Boolean = false): String =
        if (mms) "provider:mms:$providerId" else "provider:$providerId"

    data class Mapped(
        val address: String,
        val body: String,
        val sentAt: Long,
        val direction: MessageDirection,
        val status: MessageStatus,
        val providerId: Long,
        val mms: Boolean,
    )

    /**
     * Null when the row cannot be shown: a provider message with no address and
     * no body is either an MMS stub or a corrupt row, and importing it would
     * put an empty bubble in a conversation.
     */
    fun map(message: ProviderMessage): Mapped? {
        if (message.address.isBlank() && message.body.isBlank()) return null
        // Some carriers store the recipient in a PDU-only field and leave the
        // address null. Without one there is no conversation to file it under.
        if (message.address.isBlank()) return null

        val outgoing = SmsProviderTypes.isOutgoing(message.type)
        return Mapped(
            address = message.address.trim(),
            body = message.body,
            sentAt = message.date,
            direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            status = statusFor(message, outgoing),
            providerId = message.id,
            mms = message.mms,
        )
    }

    /**
     * Status from the provider's own view of the message.
     *
     * `STATUS_PENDING` is a 0-100 percentage, not an enum, so only the
     * `STATUS_FAILED` marker is treated as a failure; anything else that is not
     * complete is reported as sent rather than stuck.
     */
    private fun statusFor(message: ProviderMessage, outgoing: Boolean): MessageStatus {
        if (!outgoing) return MessageStatus.DELIVERED

        // Checked before the status: a failed send is signalled by either field
        // depending on the device, and a message that failed should never be
        // reported as sent just because the status column says otherwise.
        if (message.type == SmsProviderTypes.MESSAGE_TYPE_FAILED ||
            message.status == SmsProviderTypes.STATUS_FAILED
        ) {
            return MessageStatus.FAILED
        }

        return when (message.status) {
            SmsProviderTypes.STATUS_COMPLETE -> MessageStatus.DELIVERED
            // STATUS_PENDING is a 0-100 percentage rather than an enum member,
            // so anything not complete and not failed is reported as sent.
            else -> MessageStatus.SENT
        }
    }

    /** Conversation key for a provider row, matching the live receive path. */
    fun conversationKeyFor(address: String): String = AddressNormalizer.conversationKey(address)
}