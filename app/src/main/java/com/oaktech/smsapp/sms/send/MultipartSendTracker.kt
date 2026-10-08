package com.oaktech.smsapp.sms.send

/**
 * Aggregates per-part callbacks for one multi-part SMS into a single outcome.
 *
 * `SmsManager` invokes the sent intent once per part. A long message that splits
 * into three parts therefore produces three callbacks with independent result
 * codes, and any of them can fail on its own. The message as a whole is only
 * successful when every part succeeded.
 *
 * Instances are single-use and not thread-safe; callers own the synchronization.
 */
class MultipartSendTracker(
    private val expectedParts: Int,
) {
    private val results = arrayOfNulls<ResultCode>(expectedParts)
    private var seenParts = 0
    private var resolved = false

    init {
        require(expectedParts > 0) { "expectedParts must be positive, was $expectedParts" }
    }

    /**
     * Records the outcome for one part.
     *
     * @param index zero-based part index as passed to `divideMessage`
     * @return the aggregate outcome once every part has reported, otherwise null
     */
    fun onPartResult(index: Int, code: Int): SendOutcome? {
        if (resolved) return outcomeOrNull()
        if (index !in results.indices) return null
        if (results[index] != null) return null

        results[index] = ResultCode.fromPlatformCode(code)
        seenParts += 1
        if (seenParts < expectedParts) return null

        resolved = true
        return outcomeOrNull()
    }

    /**
     * Terminal failure for the whole message, e.g. no service or radio off.
     * Any parts that still report afterwards are ignored.
     */
    fun onFatalFailure(code: Int): SendOutcome {
        resolved = true
        results.fill(ResultCode.fromPlatformCode(code))
        return requireNotNull(outcomeOrNull())
    }

    /**
     * Marks the message as delivered. Delivery reports arrive after the sent
     * reports and are batched, so they may cover fewer parts than exist; any
     * part that already reported success is upgraded to DELIVERED. A part that
     * previously failed stays failed, since a delivery report cannot revive it.
     */
    fun markDelivered(): SendOutcome {
        resolved = true
        for (index in results.indices) {
            val current = results[index]
            if (current == null || current == ResultCode.SENT) {
                results[index] = ResultCode.DELIVERED
            }
        }
        return requireNotNull(outcomeOrNull())
    }

    val isResolved: Boolean
        get() = resolved

    private fun outcomeOrNull(): SendOutcome? {
        if (!resolved) return null
        val failedParts = results.count { it?.isFailure == true }
        val observed = results.filterNotNull()
        val reportedCode = results.firstNotNullOfOrNull { it?.takeIf { code -> code.isFailure } }
            ?: if (observed.isNotEmpty() && observed.all { it == ResultCode.DELIVERED }) {
                ResultCode.DELIVERED
            } else {
                ResultCode.SENT
            }

        return SendOutcome(
            state = if (failedParts > 0) SendState.FAILED else SendState.SENT,
            failedParts = failedParts,
            totalParts = expectedParts,
            code = reportedCode,
        )
    }
}

enum class SendState {
    SENT,
    FAILED,
}

data class SendOutcome(
    val state: SendState,
    val failedParts: Int,
    val totalParts: Int,
    val code: ResultCode,
) {
    val isSuccess: Boolean
        get() = state == SendState.SENT
}