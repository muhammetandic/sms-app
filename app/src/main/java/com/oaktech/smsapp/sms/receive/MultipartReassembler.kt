package com.oaktech.smsapp.sms.receive

/**
 * A logical SMS, as opposed to a single PDU. Android delivers long messages
 * as several [SmsPart]s that belong to one conversation message.
 */
data class InboundMessage(
    val address: String,
    val body: String,
    val timestampMillis: Long,
    val parts: Int,
)

object MultipartReassembler {

    private const val MAX_PARTS = 20

    /**
     * Joins the parts of a single inbound message in the order Android
     * delivered them. Returns null when the parts cannot describe a message.
     */
    fun reassemble(parts: List<SmsPart>): InboundMessage? {
        if (parts.isEmpty()) return null

        val address = parts.first().originatingAddress
        if (address.isBlank()) return null

        if (parts.size > MAX_PARTS) return null

        val bodies = parts.map { it.messageBody }
        if (bodies.any { it.isEmpty() }) return null

        val timestamps = parts.map { it.timestampMillis }.distinct()
        if (timestamps.size > 1) return null

        return InboundMessage(
            address = address.trim(),
            body = bodies.joinToString(separator = ""),
            timestampMillis = timestamps.first(),
            parts = parts.size,
        )
    }
}