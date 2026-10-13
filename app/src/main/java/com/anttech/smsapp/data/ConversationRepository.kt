package com.anttech.smsapp.data

import com.anttech.smsapp.data.local.AddressNormalizer
import com.anttech.smsapp.data.local.ConversationDao
import com.anttech.smsapp.data.local.ConversationEntity
import com.anttech.smsapp.data.local.ConversationSummary
import com.anttech.smsapp.data.local.MessageDao
import com.anttech.smsapp.data.telephony.NoopTelephonyMessageWriter
import com.anttech.smsapp.data.telephony.ProviderRowRef
import com.anttech.smsapp.data.telephony.TelephonyMessageWriter
import kotlinx.coroutines.flow.Flow

/**
 * The conversation list and everything that maintains a conversation's own
 * state: its read/unread count, its spam, mute and pin flags, and the
 * last-message timestamp that orders the inbox.
 *
 * Split out of [MessageRepository] because the inbox, the spam folder and the
 * notification actions work in conversations and never touch a message body,
 * while the thread and compose screens work in messages. The two share exactly
 * one concern - finding or creating the conversation a message belongs to - and
 * that lives here, so the live receive path and the provider import cannot
 * disagree about which conversation a number maps to.
 */
class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    /** Mirrors local mutations back to the platform provider. Test default is a no-op. */
    private val telephonyWriter: TelephonyMessageWriter = NoopTelephonyMessageWriter(),
) {

    fun observeConversations(): Flow<List<ConversationSummary>> = conversationDao.observeConversations()

    fun observeSpamConversations(): Flow<List<ConversationSummary>> =
        conversationDao.observeSpamConversations()

    fun observeArchivedConversations(): Flow<List<ConversationSummary>> =
        conversationDao.observeArchivedConversations()

    /**
     * Marks a conversation read in the store and mirrors the change to every
     * provider message this app can identify in it, so the system inbox's badge
     * clears with this app's.
     */
    suspend fun markConversationRead(conversationId: Long) {
        telephonyWriter.markRead(messageDao.providerIdsInConversation(conversationId).toProviderRefs())
        conversationDao.markRead(conversationId)
    }

    /**
     * Marks a batch of conversations unread in the store and mirrors the change
     * to every provider message this app can identify in them, so the system
     * inbox's badge returns with this app's. The count is floored at one rather
     * than accumulated, matching [ConversationDao.markUnread].
     *
     * The provider ids are collected in one query and written back in one call,
     * rather than per conversation: selection mode can cover the whole list, and
     * a query plus a provider write per row is the difference between a tap and
     * a stall.
     */
    suspend fun setConversationsUnread(ids: Set<Long>) {
        if (ids.isEmpty()) return
        telephonyWriter.markUnread(messageDao.providerIdsInConversations(ids.toList()).toProviderRefs())
        conversationDao.markUnread(ids.toList())
    }

    /**
     * Marks a batch of conversations read in the store, mirroring the change to
     * the provider's copy of every message this app can identify. This is the
     * selection-mode counterpart of [markConversationRead].
     */
    suspend fun setConversationsRead(ids: Set<Long>) {
        if (ids.isEmpty()) return
        telephonyWriter.markRead(messageDao.providerIdsInConversations(ids.toList()).toProviderRefs())
        conversationDao.markRead(ids.toList())
    }

    /**
     * Marks every conversation read and clears the provider badges for all the
     * rows this app wrote. The provider ids are read in one query rather than
     * per conversation, because the whole store can be covered by one tap.
     */
    suspend fun markAllConversationsRead() {
        telephonyWriter.markRead(messageDao.allProviderIds().toProviderRefs())
        conversationDao.markAllRead()
    }

    /**
     * Moves a conversation into or out of the spam folder.
     *
     * Messages are kept rather than deleted so the folder is viewable and the
     * move is reversible; only the inbox/spam split changes.
     */
    suspend fun setConversationSpam(conversationId: Long, isSpam: Boolean) =
        conversationDao.setSpam(conversationId, isSpam)

    /** Whether a conversation is in the spam folder, so its arrivals stay quiet. */
    suspend fun isSpam(conversationId: Long): Boolean = conversationDao.isSpam(conversationId) == true

    /** Mutes or unmutes a conversation, silencing only its incoming alerts. */
    suspend fun setConversationMuted(conversationId: Long, isMuted: Boolean) =
        conversationDao.setMuted(conversationId, isMuted)

    /** Whether a conversation is muted, so its arrivals stay quiet. */
    suspend fun isMuted(conversationId: Long): Boolean = conversationDao.isMuted(conversationId) == true

    /**
     * Archives or unarchives a batch of conversations, hiding them from the
     * inbox without deleting them. Unarchiving is how the archive folder
     * restores a row. A no-op for an empty list, because Room rejects an empty
     * `IN` list.
     */
    suspend fun setConversationsArchived(ids: Collection<Long>, isArchived: Boolean) {
        if (ids.isEmpty()) return
        conversationDao.setArchived(ids.toList(), isArchived)
    }

    /**
     * Deletes a whole conversation from the store.
     *
     * The messages table cascades on the conversation id, so removing the row
     * also removes every message it holds. The inbox and the spam folder both
     * read this table, so the row disappears from whichever list showed it.
     * The provider rows behind any imported or sent messages are deleted first.
     *
     * [MessageRepository.deleteMessage] is preferred when only one message is
     * unwanted: this path discards the conversation's unread state too, so an
     * inbox badge can disappear together with the message the user is dismissing.
     */
    suspend fun deleteConversation(conversationId: Long) {
        telephonyWriter.delete(messageDao.providerIdsInConversation(conversationId).toProviderRefs())
        conversationDao.delete(conversationId)
    }

    /**
     * Pins or unpins a batch of conversations so they stay at the top of the
     * inbox. A no-op for an empty list, because room rejects an empty `IN` list.
     */
    suspend fun setConversationsPinned(ids: Collection<Long>, isPinned: Boolean) {
        if (ids.isEmpty()) return
        conversationDao.setPinned(ids.toList(), isPinned)
    }

    /**
     * Moves a batch of conversations into or out of the spam folder, keeping
     * the same semantics as a single move: unread counts are cleared in the
     * same write so the spam folder never shows a badge for the move itself.
     */
    suspend fun setConversationsSpam(ids: Collection<Long>, isSpam: Boolean) {
        if (ids.isEmpty()) return
        conversationDao.setSpam(ids.toList(), isSpam)
    }

    /**
     * Deletes a batch of conversations and every message they hold, from
     * whatever list (inbox or spam) currently shows them.
     */
    suspend fun deleteConversations(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        telephonyWriter.delete(messageDao.providerIdsInConversations(ids.toList()).toProviderRefs())
        conversationDao.delete(ids.toList())
    }

    /** Conversation id for a message row, needed to target a notification action. */
    suspend fun conversationIdForMessage(messageId: Long): Long? = messageDao.getById(messageId)?.conversationId

    /** Resolves the conversation for a sender, used to deep-link a notification. */
    suspend fun conversationIdFor(address: String): Long? =
        conversationDao.getByAddressKey(AddressNormalizer.conversationKey(address))?.id

    /**
     * Address to reply to, for a conversation already in the store.
     *
     * A notification tap carries a conversation id but no address, and sending to
     * the wrong address is not a recoverable mistake, so the thread screen refuses
     * to send until this resolves.
     */
    suspend fun recipientFor(conversationId: Long): String? =
        conversationDao.getById(conversationId)?.displayAddress?.takeIf { it.isNotBlank() }

    /**
     * The conversation for a participant set, if it exists, without creating it.
     * Used by the composer to keep a draft against an already-known recipient.
     */
    internal suspend fun conversationIdForMembers(members: List<String>): Long? =
        conversationDao.getByAddressKey(AddressNormalizer.groupKey(members))?.id

    /**
     * Resolves or creates the conversation for a participant set.
     *
     * A single member shares the plain-SMS conversation; two or more become a
     * group keyed on the sorted normalized members. The display address is the
     * member list as the user typed it, which is what the thread and the
     * notification show.
     */
    internal suspend fun findOrCreateConversation(members: List<String>, timestamp: Long): Long {
        val display = members.map { it.trim() }.filter { it.isNotBlank() }
        if (display.isEmpty()) error("Cannot create a conversation without any recipient")
        val key = AddressNormalizer.groupKey(display)

        conversationDao.getByAddressKey(key)?.let { existing ->
            if (timestamp > existing.lastMessageAt) {
                conversationDao.update(existing.copy(lastMessageAt = timestamp))
            }
            return existing.id
        }

        return conversationDao.insert(
            ConversationEntity(
                addressKey = key,
                displayAddress = display.joinToString(", "),
                lastMessageAt = timestamp,
            ),
        )
    }

    /** Resolves or creates the conversation for a single address. */
    internal suspend fun findOrCreateConversation(address: String, timestamp: Long): Long =
        findOrCreateConversation(listOf(address), timestamp)

    /**
     * Adds one to a conversation's unread count after an inbound row is stored,
     * and pulls an archived conversation back into the inbox.
     *
     * An archived thread is hidden but still ordinary mail, so a reply to one
     * belongs where the user will see it rather than buried in the archive.
     */
    internal suspend fun bumpUnread(conversationId: Long) {
        val conversation = conversationDao.getById(conversationId) ?: return
        conversationDao.update(
            conversation.copy(
                unreadCount = conversation.unreadCount + 1,
                isArchived = false,
            ),
        )
    }

    /**
     * Reconciles a conversation's derived state after one of its messages is
     * removed: an empty conversation is deleted, and an incoming message takes
     * one off the unread count.
     *
     * An outgoing delete must leave the counter alone, because only incoming
     * messages are ever counted unread. MAX keeps the count from going negative
     * if it was already out of step with the messages present.
     */
    internal suspend fun onMessageRemoved(conversationId: Long, wasIncoming: Boolean) {
        if (messageDao.countInConversation(conversationId) == 0) {
            conversationDao.delete(conversationId)
            return
        }
        if (wasIncoming) {
            conversationDao.decrementUnread(conversationId)
        }
    }

    /** Drops a conversation that no longer holds any message. */
    internal suspend fun deleteIfEmpty(conversationId: Long) {
        if (messageDao.countInConversation(conversationId) == 0) {
            conversationDao.delete(conversationId)
        }
    }

    /** Every conversation id, so a store-wide mark-all-read can cancel each alert. */
    suspend fun conversationIds(): List<Long> = conversationDao.allIds()

    /**
     * The provider rows behind a batch of local provider ids, for write-back.
     *
     * The ids come from a query that already dropped the nulls, so every one
     * here is a provider row this app can write to.
     */
    private fun List<Long>.toProviderRefs(): List<ProviderRowRef> = map(::ProviderRowRef)
}
