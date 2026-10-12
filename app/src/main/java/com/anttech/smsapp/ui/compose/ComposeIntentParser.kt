package com.anttech.smsapp.ui.compose

/**
 * Parses `sms:` / `smsto:` URIs from a SENDTO intent.
 *
 * The scheme and the recipient arrive in different places depending on who sent
 * the intent, and getting this wrong means texting the wrong person, so every
 * shape is handled explicitly rather than assuming one convention.
 */
object ComposeIntentParser {

    /**
     * Recipients named by a SENDTO intent.
     *
     * Empty when the intent names no recipient, which is valid: "sms:" with no
     * target is a request to open a blank composer.
     */
    fun recipientsFrom(uri: String?): List<String> {
        if (uri.isNullOrBlank()) return emptyList()

        val schemeSeparator = uri.indexOf(':')
        val scheme = if (schemeSeparator > 0) uri.substring(0, schemeSeparator) else ""
        val remainder = if (schemeSeparator >= 0) uri.substring(schemeSeparator + 1) else uri

        // Only sms-family schemes carry a recipient. A random viewer's content
        // URI here would be nonsense to treat as a phone number.
        if (scheme.lowercase() !in SMS_SCHEMES) return emptyList()

        // The query string is not part of the address. Without this cut,
        // `smsto:+15551234567?body=hi` yields the recipient
        // "+15551234567?body=hi", which is then sent to as a phone number.
        val addressPart = remainder.substringBefore('?')

        return addressPart
            .split(',')
            // Contacts commonly launches SENDTO with the address percent-
            // encoded. Protect literal plus signs before URLDecoder, whose
            // form-decoding rules would otherwise turn them into spaces.
            .map { decodeAddress(it.trim()) }
            .filter { it.isNotEmpty() }
    }

    /** Body text supplied as `?body=`, or an inline `sms:number?body=text`. */
    fun bodyFrom(uri: String?): String? {
        if (uri.isNullOrBlank()) return null

        val bodyParameter = uri.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.substringBefore('=', "") == "body" }
            ?: return null

        return decodeQueryValue(bodyParameter.substringAfter('=', ""))
            .takeIf { it.isNotEmpty() }
    }

    private fun decodeQueryValue(value: String): String =
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun decodeAddress(value: String): String =
        java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())

    private val SMS_SCHEMES = setOf("sms", "smsto")
}
