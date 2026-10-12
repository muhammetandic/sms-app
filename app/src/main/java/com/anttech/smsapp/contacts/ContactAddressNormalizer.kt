package com.anttech.smsapp.contacts

/**
 * Number normalisation for contact matching.
 *
 * Contacts are stored in whatever form the contact's owner entered, so the same
 * number can be `+1 (555) 010-9999`, `5550109999` or `15550109999`. Matching
 * has to ignore those differences or a contact silently fails to resolve and the
 * row falls back to a raw number.
 *
 * Formatting differences only. This is deliberately not a full E.164 parser: it
 * must not invent a country code, because that would match the wrong contact.
 */
object ContactAddressNormalizer {

    /**
     * Comparable form of an address: digits only, with a leading `+` kept.
     *
     * A national number and the same number in international form stay
     * different, since without a country code there is no way to tell them
     * apart safely.
     */
    fun normalize(address: String): String {
        val trimmed = address.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (hasPlus && digits.isNotEmpty()) "+$digits" else digits
    }

    /**
     * True when [candidate] and [target] can refer to the same contact.
     *
     * Used as a second pass after a lookup miss, so it accepts a national number
     * matching an international one on the assumption both are the same length
     * or that the shorter is a suffix of the longer.
     */
    fun couldBeSame(candidate: String, target: String): Boolean {
        val left = normalize(candidate).removePrefix("+")
        val right = normalize(target).removePrefix("+")
        if (left.isEmpty() || right.isEmpty()) return false

        return when {
            left == right -> true
            left.length < right.length -> right.endsWith(left)
            right.length < left.length -> left.endsWith(right)
            else -> false
        }
    }
}