package com.oaktech.smsapp.sms.mms

/**
 * Decides whether a message travels as SMS or MMS.
 *
 * The rule a user expects: as long as every recipient can carry a text, plain
 * text stays on SMS (which this app can actually send without a carrier MMS
 * provision), and anything that needs the MMS channel — two or more recipients,
 * or an attachment — goes over it.
 */
object MmsRouting {

    fun route(recipientCount: Int, hasAttachments: Boolean): Route =
        if (recipientCount > 1 || hasAttachments) Route.MMS else Route.SMS

    /** Single participant, plain text: the SMS channel, which is always ready. */
    fun route(recipient: String, hasAttachments: Boolean): Route =
        route(recipientCount = if (recipient.isBlank()) 0 else 1, hasAttachments = hasAttachments)
}

enum class Route {
    SMS,
    MMS,
}