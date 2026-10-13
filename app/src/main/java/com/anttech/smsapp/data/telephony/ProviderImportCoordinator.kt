package com.anttech.smsapp.data.telephony

import android.util.Log
import com.anttech.smsapp.data.ProviderImportRepository
import com.anttech.smsapp.sms.receive.LogTags
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-time import of messages the provider already holds.
 *
 * Runs on first launch after the app takes the SMS role, and again on later
 * launches to pick up anything that arrived while another app held the role.
 * Concurrent callers are serialised, because the role check plus the query plus
 * the insert would otherwise run twice at once on startup.
 */
class ProviderImportCoordinator(
    private val repository: ProviderImportRepository,
    private val dataSource: SmsMessageSource,
    private val hasSmsRole: () -> Boolean,
) {
    private val mutex = Mutex()

    /**
     * Backfills provider history when there is anything to backfill.
     *
     * Returns the number of local rows added (0 when the role is not held, when
     * the provider is unreadable, or when the watermark shows nothing new). The
     * role check comes first so a role loss never reaches the provider and never
     * produces a wall of SecurityExceptions.
     */
    suspend fun importIfPossible(): Int = mutex.withLock {
        if (!hasSmsRole()) {
            Log.d(LogTags.STORE, "Skipping provider import: SMS role not held")
            return@withLock 0
        }

        // Nothing new since the last import means nothing to backfill, so the
        // cheap MAX(_id) probe stands in for the full read. Provider ids only
        // ever grow, and the mark stores the highest id an import has processed
        // (not just the highest it kept), so a run with nothing new is a SELECT
        // MAX on the provider and one local read.
        val providerMax = runCatching { dataSource.queryMaxMessageId() }
            .onFailure { Log.w(LogTags.STORE, "Provider max-id query failed", it) }
            .getOrNull()
            ?: return@withLock 0
        val watermark = repository.providerImportWatermark() ?: 0L
        if (providerMax <= watermark) {
            Log.d(LogTags.STORE, "Provider import skipped: no rows newer than $watermark")
            return@withLock 0
        }

        val messages = runCatching { dataSource.queryAllMessages() }
            .onFailure { Log.w(LogTags.STORE, "Provider import query failed", it) }
            .getOrDefault(emptyList())

        if (messages.isEmpty()) return@withLock 0

        val inserted = repository.importFromProvider(messages)
        // Record the highest id this pass saw, not the highest it stored: a row
        // it deliberately skipped (no address, or already held under a different
        // key) must not be re-read on every future launch.
        repository.recordProviderImportWatermark(messages.maxOf { it.id })
        Log.d(LogTags.STORE, "Provider import added $inserted of ${messages.size} messages")
        inserted
    }
}