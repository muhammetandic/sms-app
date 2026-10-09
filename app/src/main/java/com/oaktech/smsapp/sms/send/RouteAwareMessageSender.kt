package com.oaktech.smsapp.sms.send

import com.oaktech.smsapp.data.AttachmentPart
import com.oaktech.smsapp.data.local.AddressNormalizer
import com.oaktech.smsapp.sms.mms.MmsRouting
import com.oaktech.smsapp.sms.mms.MmsSendCoordinator
import com.oaktech.smsapp.sms.mms.Route

/**
 * Routes a composed message to the SMS or MMS channel and hands it off.
 *
 * The SMS leg keeps the exact contract the SMS coordinator already had; the MMS
 * leg expands a display group address into its members and persists the message
 * together with whatever attachments the composer queued. [subject] is an MMS
 * affordance, so the SMS leg drops it rather than inventing a place for it.
 */
class RouteAwareMessageSender(
    private val sms: suspend (recipient: String, body: String) -> SendOutcome,
    private val mms: MmsSendCoordinator,
) {
    suspend fun send(
        recipient: String,
        subject: String?,
        body: String,
        attachments: List<AttachmentPart>,
    ): SendOutcome {
        val members = AddressNormalizer.membersOf(recipient)
        val route = MmsRouting.route(recipientCount = members.size, hasAttachments = attachments.isNotEmpty())
        return when (route) {
            Route.SMS -> sms(recipient, body)
            Route.MMS -> mms.send(
                recipients = if (members.isEmpty()) listOf(recipient) else members,
                subject = subject?.takeIf { it.isNotBlank() },
                body = body,
                attachments = attachments,
            )
        }
    }
}