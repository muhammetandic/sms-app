package com.oaktech.smsapp.data.local

/**
 * Canonical form of a phone number, used as the conversation key.
 *
 * Android reports the same number in several shapes: with and without a
 * country code, with spaces or dashes, and as a short code with no `+`. Grouping
 * threads by the raw string splits one person across several conversations, so
 * everything is normalized before it reaches the database.
 */
object AddressNormalizer {

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val hasPlus = trimmed.startsWith("+")
        val digits = buildString {
            for (character in trimmed) {
                if (character.isDigit()) append(character)
            }
        }
        if (digits.isEmpty()) return ""

        return if (hasPlus) "+$digits" else digits
    }

    /**
     * Alphanumeric sender ids used by OTPs and service providers have no digits
     * and no `+`, so they fall back to a trimmed, lowercased form.
     */
    fun conversationKey(raw: String): String {
        val normalized = normalize(raw)
        if (normalized.isNotEmpty()) return normalized
        return raw.trim().lowercase()
    }

    /**
     * Conversation key for a set of participants.
     *
     * A single participant must land in the same conversation as a plain SMS to
     * the same number, so MMS and SMS to one person share a thread. Two or more
     * form a group that is its own conversation, keyed on the sorted normalized
     * members so the order a contact list happens to list them in is irrelevant
     * to identity.
     */
    fun groupKey(members: List<String>): String {
        val normalized = members.map { conversationKey(it) }.sorted()
        return normalized.joinToString(separator = "+")
    }

    /** Splits a comma-joined recipient list back into members, for group sends. */
    fun membersOf(displayAddress: String): List<String> =
        displayAddress
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
}