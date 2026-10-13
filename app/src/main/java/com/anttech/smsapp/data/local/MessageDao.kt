package com.anttech.smsapp.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One row where a search query matched, joined with its conversation. */
data class MessageSearchHit(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "address_key") val addressKey: String,
    @ColumnInfo(name = "display_address") val displayAddress: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
    @ColumnInfo(name = "direction") val direction: MessageDirection,
)

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY sent_at ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    /**
     * Provider ids of a conversation's messages, for mirroring a read-state or
     * delete write-back to the provider. Only the ids are read: loading whole
     * rows just to reach them would copy every body of a long thread for a
     * write that needs none of it.
     */
    @Query("SELECT provider_id FROM messages WHERE conversation_id = :conversationId AND provider_id IS NOT NULL")
    suspend fun providerIdsInConversation(conversationId: Long): List<Long>

    /** Provider ids across several conversations, for a batch write-back. */
    @Query("SELECT provider_id FROM messages WHERE conversation_id IN (:ids) AND provider_id IS NOT NULL")
    suspend fun providerIdsInConversations(ids: List<Long>): List<Long>

    @Query("SELECT COUNT(*) FROM messages WHERE dedupe_key = :dedupeKey")
    suspend fun countByDedupeKey(dedupeKey: String): Int

    /**
     * The local row already linked to a provider row, if any.
     */
    @Query("SELECT id FROM messages WHERE provider_id = :providerId LIMIT 1")
    suspend fun idForProvider(providerId: Long): Long?

    /** How many messages a conversation still holds, to spot the last one going. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countInConversation(conversationId: Long): Int

    /**
     * IGNORE rather than REPLACE: the unique index on `dedupe_key` makes a
     * duplicate insert a no-op instead of destroying the existing row and
     * handing it a new id.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Query(
        """
        UPDATE messages SET status = :status, provider_id = :providerId
         WHERE id = :id
        """,
    )
    suspend fun updateStatus(id: Long, status: MessageStatus, providerId: Long? = null)

    /**
     * Links a local row to the provider row it was mirrored into, which is what
     * turns on delete and read-state write-back for it.
     */
    @Query("UPDATE messages SET provider_id = :providerId WHERE id = :id")
    suspend fun updateProviderId(id: Long, providerId: Long)

    /**
     * Links an outgoing row to its provider copy *and* adopts that copy's
     * dedupe key, so the next provider import recognises a message this app
     * wrote instead of filing a second row under the outgoing nonce key.
     */
    @Query("UPDATE messages SET provider_id = :providerId, dedupe_key = :dedupeKey WHERE id = :id")
    suspend fun adoptSentProviderRow(id: Long, providerId: Long, dedupeKey: String)

    @Query("UPDATE messages SET status = :status WHERE status = 'SENDING' AND sent_at < :beforeMillis")
    suspend fun resolvePendingOutgoing(status: MessageStatus, beforeMillis: Long)

    /** Marks a still-SENDING row sent; does nothing once it resolved otherwise. */
    @Query("UPDATE messages SET status = :status WHERE id = :id AND status = 'SENDING'")
    suspend fun markIfSending(id: Long, status: MessageStatus)

    /**
     * The split happens after the row exists (a divide failure still has to
     * leave a visible FAILED bubble), so the real part count lands here
     * instead of at insert time.
     */
    @Query("UPDATE messages SET part_count = :partCount WHERE id = :id")
    suspend fun updatePartCount(id: Long, partCount: Int)

    /** Marks a still-SENDING row failed with the platform code. */
    @Query("UPDATE messages SET status = 'FAILED', error_code = :errorCode WHERE id = :id AND status = 'SENDING'")
    suspend fun failIfSending(id: Long, errorCode: Int)

    /**
     * Marks a row delivered the moment its report arrives, which is the only
     * time the current time is honest: a status written earlier would look as
     * if the delivery was predicted.
     */
    @Query("UPDATE messages SET status = 'DELIVERED', delivered_at = :deliveredAt WHERE id = :id")
    suspend fun updateDelivery(id: Long, deliveredAt: Long)

    /** Stores the platform code behind a failure alongside the FAILED status. */
    @Query("UPDATE messages SET status = 'FAILED', error_code = :errorCode WHERE id = :id")
    suspend fun updateError(id: Long, errorCode: Int)

    /**
     * Search over message bodies and the address of the conversation they sit
     * in. Contact display names are not queryable here (they live in another
     * app), so they are filtered at the presentation layer.
     *
     * Bodies are matched through the FTS index rather than `LIKE '%term%'`, so a
     * search no longer reads every stored message. `match` is the FTS expression
     * built from the query (see [ftsMatchExpression]); it is null when the query
     * holds no searchable token, and `MATCH NULL` then selects no rows without
     * error, leaving the address branch - a plain LIKE - as the whole search.
     *
     * The two branches are combined with UNION rather than a single OR because
     * an OR between an indexed match and an unindexed LIKE makes SQLite fall
     * back to scanning `messages`, which would throw the index away. As separate
     * branches each keeps its own access path: the FTS index for the body and,
     * for the address, a scan of the small `conversations` table feeding the
     * `conversation_id` index on `messages`. The address branch is written as a
     * subquery on `conversation_id` rather than as a LIKE against the joined
     * `conversations` row on purpose: the latter lets the planner instead scan
     * every message and test the address per row, which is the scan this query
     * exists to avoid. UNION also collapses a row that matched both.
     */
    @Query(
        """
        SELECT m.id AS message_id, m.conversation_id AS conversation_id,
               c.address_key AS address_key, c.display_address AS display_address,
               m.body AS body, m.sent_at AS sent_at, m.direction AS direction
          FROM messages m JOIN conversations c ON c.id = m.conversation_id
         WHERE m.id IN (SELECT rowid FROM messages_fts WHERE messages_fts MATCH :match)
         UNION
        SELECT m.id AS message_id, m.conversation_id AS conversation_id,
               c.address_key AS address_key, c.display_address AS display_address,
               m.body AS body, m.sent_at AS sent_at, m.direction AS direction
          FROM messages m JOIN conversations c ON c.id = m.conversation_id
         WHERE m.conversation_id IN (
                   SELECT id FROM conversations WHERE display_address LIKE '%' || :query || '%')
         ORDER BY sent_at DESC
         LIMIT 200
        """,
    )
    fun searchMessages(match: String?, query: String): Flow<List<MessageSearchHit>>

    /** Returns the number of rows removed, which is 0 when the id is already gone. */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long): Int
}