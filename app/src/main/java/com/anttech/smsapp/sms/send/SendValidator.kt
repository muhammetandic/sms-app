package com.anttech.smsapp.sms.send

/**
 * Validation for user-supplied recipients and message bodies, kept free of
 * Android types so it can be unit tested.
 */
object SendValidator {

    const val MAX_BODY_LENGTH = 10_000
    const val MAX_PARTS = 10

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: Reason, val detail: String) : Result
    }

    enum class Reason {
        EMPTY_RECIPIENT,
        UNPARSEABLE_RECIPIENT,
        EMPTY_BODY,
        BODY_TOO_LONG,
    }

    fun validate(recipient: String, body: String): Result {
        val trimmedRecipient = recipient.trim()
        if (trimmedRecipient.isEmpty()) {
            return Result.Invalid(Reason.EMPTY_RECIPIENT, "No recipient")
        }

        val recipients = recipients(trimmedRecipient)
        if (recipients.isEmpty() || !recipients.all { isParseableRecipient(it) }) {
            return Result.Invalid(Reason.UNPARSEABLE_RECIPIENT, "Recipient cannot be parsed as a phone number")
        }

        if (body.isBlank()) {
            return Result.Invalid(Reason.EMPTY_BODY, "Message body is empty")
        }

        if (body.length > MAX_BODY_LENGTH) {
            return Result.Invalid(Reason.BODY_TOO_LONG, "Message body exceeds $MAX_BODY_LENGTH characters")
        }

        return Result.Valid
    }

    /**
     * The distinct recipients a recipient field names. A SENDTO can carry
     * several comma-separated addresses, and SMS has no group form, so each is
     * sent its own message; splitting here keeps that rule in one place.
     */
    fun recipients(recipient: String): List<String> =
        recipient.trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * A recipient is usable if it is either dialable or a short/alphanumeric
     * sender id. OTP messages arrive from names like `Google` that no phone
     * number parser accepts, so those are permitted as send targets too.
     */
    fun isParseableRecipient(recipient: String): Boolean {
        val trimmed = recipient.trim()
        if (trimmed.isEmpty()) return false

        val digits = trimmed.count { it.isDigit() }
        if (digits == 0) return trimmed.any { it.isLetter() } && trimmed.none { it.isWhitespace() }

        val leadingPlus = trimmed.startsWith("+")
        if (leadingPlus && digits == 1) return false

        // A short code or national number is 3 digits or more once separators
        // are removed; below that it is almost certainly a typo.
        return digits >= 3
    }
}