package com.anttech.smsapp.data.telephony

import android.util.Log
import com.anttech.smsapp.data.MessageRepository
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
    private val repository: MessageRepository,
    private val dataSource: SmsMessageSource,
    private val hasSmsRole: () -> Boolean,
) {
    private val mutex = Mutex()

    /**
     * True when the app may read the provider right now.
     *
     * Both conditions are needed: the role gates the provider's read permission,
     * and the query is skipped entirely when it is absent so a role loss does
     * not produce a wall of SecurityExceptions.
     */
    suspend fun importIfPossible(): Int = mutex.withLock {
        if (!hasSmsRole()) {
            Log.d(LogTags.STORE, "Skipping provider import: SMS role not held")
            return@withLock 0
        }

        val messages = runCatching { dataSource.queryAllMessages() }
            .onFailure { Log.w(LogTags.STORE, "Provider import query failed", it) }
            .getOrDefault(emptyList())

        if (messages.isEmpty()) return@withLock 0

        val inserted = repository.importFromProvider(messages)
        Log.d(LogTags.STORE, "Provider import added $inserted of ${messages.size} messages")
        inserted
    }
}