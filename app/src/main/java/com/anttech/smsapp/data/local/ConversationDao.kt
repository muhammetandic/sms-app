package com.anttech.smsapp.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Conversation row plus a snippet of its most recent message, for the thread list. */
data class ConversationSummary(
    val id: Long,
    @ColumnInfo(name = "address_key") val addressKey: String,
    @ColumnInfo(name = "display_address") val displayAddress: String,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "is_muted") val isMuted: Boolean,
    @ColumnInfo(name = "snippet") val snippet: String?,
)

@Dao
interface ConversationDao {

    /**
     * Preview of the newest message in the conversation, shared by the inbox and
     * spam queries so the two lists cannot drift apart. Referenced by both
     * because Room requires a compile-time constant, so the projection cannot be
     * assembled at runtime.
     */
    /**
     * The inbox: everything not moved to spam. Pinned rows float above the
     * rest, each group ordered by most recent message.
     *
     * Message-less conversations are excluded: the only way one exists is a
     * draft composed to a new recipient, and that draft belongs on the drafts
     * screen rather than as a blank row with no snippet. Once the draft is sent
     * the conversation has a message and joins the inbox normally.
     */
    @Query(
        "$SNIPPET_QUERY WHERE c.is_spam = 0 " +
            "AND EXISTS (SELECT 1 FROM messages m2 WHERE m2.conversation_id = c.id) " +
            "ORDER BY c.is_pinned DESC, c.last_message_at DESC",
    )
    fun observeConversations(): Flow<List<ConversationSummary>>

    /** The spam folder: the same rows, minus the inbox. */
    @Query("$SNIPPET_QUERY WHERE c.is_spam = 1 ORDER BY c.last_message_at DESC")
    fun observeSpamConversations(): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE address_key = :addressKey LIMIT 1")
    suspend fun getByAddressKey(addressKey: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    /**
     * Raises a conversation's most-recent timestamp, only when the new one is
     * newer. The guard keeps a replayed import (older rows) from pushing the
     * timestamp backwards, and lets one statement do the job of the read +
     * compare the old path ran per row.
     */
    @Query("UPDATE conversations SET last_message_at = :lastMessageAt WHERE id = :id AND last_message_at < :lastMessageAt")
    suspend fun updateLastMessageAt(id: Long, lastMessageAt: Long)

    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :id")
    suspend fun markRead(id: Long)

    /**
     * Marks a batch of conversations unread again. The count floors at one
     * rather than accumulating, so tapping "mark unread" in selection mode
     * restores a badge without turning the count into a tally of the button
     * presses.
     */
    @Query("UPDATE conversations SET unread_count = MAX(unread_count, 1) WHERE id IN (:ids)")
    suspend fun markUnread(ids: List<Long>)

    /**
     * Drops one from the unread count after an incoming message is deleted,
     * floored at zero so a stale count cannot produce a negative badge.
     */
    @Query("UPDATE conversations SET unread_count = MAX(unread_count - 1, 0) WHERE id = :id")
    suspend fun decrementUnread(id: Long)

    /**
     * Moves a conversation into or out of the spam folder. Read alongside the
     * move, because a message the user has just dismissed as spam should not
     * reappear as an unread badge in the spam folder itself.
     */
    @Query("UPDATE conversations SET is_spam = :isSpam, unread_count = 0 WHERE id = :id")
    suspend fun setSpam(id: Long, isSpam: Boolean)

    @Query("SELECT is_spam FROM conversations WHERE id = :id")
    suspend fun isSpam(id: Long): Boolean?

    /** Moves a batch of conversations into or out of the spam folder. */
    @Query("UPDATE conversations SET is_spam = :isSpam, unread_count = 0 WHERE id IN (:ids)")
    suspend fun setSpam(ids: List<Long>, isSpam: Boolean)

    /** Mutes or unmutes one conversation, silencing only its alerts. */
    @Query("UPDATE conversations SET is_muted = :isMuted WHERE id = :id")
    suspend fun setMuted(id: Long, isMuted: Boolean)

    @Query("SELECT is_muted FROM conversations WHERE id = :id")
    suspend fun isMuted(id: Long): Boolean?

    /** Pins or unpins a batch of conversations. */
    @Query("UPDATE conversations SET is_pinned = :isPinned WHERE id IN (:ids)")
    suspend fun setPinned(ids: List<Long>, isPinned: Boolean)

    /** Deletes a batch of conversations; their messages cascade with them. */
    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    private companion object {
        const val SNIPPET_QUERY = """
            SELECT c.id, c.address_key, c.display_address,
                   c.last_message_at, c.unread_count, c.is_pinned, c.is_muted,
                   (SELECT COALESCE(NULLIF(m.body, ''), '') FROM messages m
                     WHERE m.conversation_id = c.id
                     ORDER BY m.sent_at DESC LIMIT 1) AS snippet
              FROM conversations c
        """
    }
}
