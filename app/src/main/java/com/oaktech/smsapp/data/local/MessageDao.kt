package com.oaktech.smsapp.data.local

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

    /** All rows of a conversation, for mirroring provider writes before a delete. */
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId")
    suspend fun getByConversation(conversationId: Long): List<MessageEntity>

    /** Rows across several conversations, for the batch delete write-back. */
    @Query("SELECT * FROM messages WHERE conversation_id IN (:ids)")
    suspend fun getByConversations(ids: List<Long>): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE dedupe_key = :dedupeKey")
    suspend fun countByDedupeKey(dedupeKey: String): Int

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

    /** Notifies the thread that an inbound MMS's media has (not) arrived. */
    @Query("UPDATE messages SET download_state = :state WHERE id = :id")
    suspend fun updateDownloadState(id: Long, state: DownloadState)

    /** Updates the body and subject of an MMS row after retrieval. */
    @Query("UPDATE messages SET body = :body, subject = COALESCE(:subject, subject) WHERE id = :id")
    suspend fun updateContent(id: Long, body: String, subject: String?)

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
     */
    @Query(
        """
        SELECT m.id AS message_id, m.conversation_id AS conversation_id,
               c.address_key AS address_key, c.display_address AS display_address,
               m.body AS body, m.sent_at AS sent_at, m.direction AS direction
          FROM messages m JOIN conversations c ON c.id = m.conversation_id
         WHERE m.body LIKE '%' || :query || '%'
            OR c.display_address LIKE '%' || :query || '%'
         ORDER BY m.sent_at DESC
         LIMIT 200
        """,
    )
    fun searchMessages(query: String): Flow<List<MessageSearchHit>>

    /** Returns the number of rows removed, which is 0 when the id is already gone. */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long): Int
}