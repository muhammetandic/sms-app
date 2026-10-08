package com.oaktech.smsapp.sms.block

import android.content.Context
import com.oaktech.smsapp.data.local.AddressNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The set of senders the user has blocked, persisted across launches.
 *
 * Entries are stored in canonical conversation-key form, so an incoming message
 * matches however the carrier happened to format the originating address, and so
 * the same sender cannot be added twice under two spellings. Matching against a
 * blocked entry uses the same identity rule as conversation grouping, so a
 * blocked number is always the number a thread would group it as.
 */
class BlockedSenders(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _blocks = MutableStateFlow(load())
    val blocks: StateFlow<Set<String>> = _blocks.asStateFlow()

    /** Whether an arriving message from [address] should be silenced. */
    fun isBlocked(address: String): Boolean = canonical(address) in _blocks.value

    /** Blocks the sender; false when the address was empty or already blocked. */
    @Synchronized
    fun add(address: String): Boolean {
        val key = canonical(address)
        if (key.isEmpty() || key in _blocks.value) return false
        _blocks.value += key
        persist()
        return true
    }

    @Synchronized
    fun remove(address: String) {
        val key = canonical(address)
        _blocks.value -= key
        persist()
    }

    @Synchronized
    private fun persist() {
        preferences.edit().putStringSet(KEY_BLOCKED, _blocks.value).apply()
    }

    private fun load(): Set<String> =
        preferences.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    private fun canonical(address: String): String = AddressNormalizer.conversationKey(address)

    private companion object {
        const val PREFERENCES_NAME = "sms_blocks"
        const val KEY_BLOCKED = "blocked_senders"
    }
}