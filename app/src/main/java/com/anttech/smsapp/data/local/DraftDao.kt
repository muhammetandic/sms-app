package com.anttech.smsapp.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Draft-only conversation row with the draft body as its preview snippet. */
data class DraftConversationSummary(
    @ColumnInfo(name = "draft_id") val draftId: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "address_key") val addressKey: String,
    @ColumnInfo(name = "display_address") val displayAddress: String,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "body") val body: String,
)

@Dao
interface DraftDao {

    @Query(
        "SELECT d.id AS draft_id, c.id AS conversation_id, c.address_key, c.display_address, c.last_message_at, d.body " +
            "FROM drafts d JOIN conversations c ON c.id = d.conversation_id " +
            "WHERE d.body <> '' AND c.is_spam = 0 ORDER BY c.last_message_at DESC",
    )
    fun observeDraftConversations(): Flow<List<DraftConversationSummary>>

    /**
     * The draft body for one conversation, or null when it has none.
     *
     * A missing row selects zero rows, so the flow emits an empty list until a
     * draft is written; the first item of that list is what the caller wants.
     */
    @Query("SELECT body FROM drafts WHERE conversation_id = :conversationId ORDER BY id DESC LIMIT 1")
    fun observe(conversationId: Long): Flow<List<String>>

    @Query("SELECT body FROM drafts WHERE conversation_id = :conversationId ORDER BY id DESC LIMIT 1")
    suspend fun get(conversationId: Long): String?

    /**
     * Rewrites the draft text. Returns the number of rows touched so the
     * repository can insert the row only when this is the first write.
     */
    @Query("UPDATE drafts SET body = :body WHERE id = (SELECT id FROM drafts WHERE conversation_id = :conversationId ORDER BY id DESC LIMIT 1)")
    suspend fun updateBody(conversationId: Long, body: String): Int

    /** Drops a draft row once its text has been cleared. */
    @Query("DELETE FROM drafts WHERE id = (SELECT id FROM drafts WHERE conversation_id = :conversationId ORDER BY id DESC LIMIT 1) AND body = ''")
    suspend fun deleteIfEmpty(conversationId: Long)

    /** Inserts a fresh draft row before its text has been written. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE id = :draftId")
    suspend fun byId(draftId: Long): DraftEntity?

    @Query("UPDATE drafts SET body = :body WHERE id = :draftId")
    suspend fun updateBodyById(draftId: Long, body: String): Int

    @Query("DELETE FROM drafts WHERE id = :draftId")
    suspend fun deleteById(draftId: Long)

    /**
     * Every non-empty draft with the address key of its conversation, for the
     * Drive backup.
     *
     * Ordered by row id so a conversation's several drafts keep the order they
     * were written in; an empty row is the composer's placeholder between a
     * draft being cleared and its row being dropped, and has nothing to carry.
     */
    @Query(
        """
        SELECT c.address_key AS address_key, d.body AS body
          FROM drafts d JOIN conversations c ON c.id = d.conversation_id
         WHERE d.body <> ''
         ORDER BY d.id ASC
        """,
    )
    suspend fun exportForSync(): List<DraftExportRow>
}

/** One stored draft with the address key of its conversation, for the backup. */
data class DraftExportRow(
    @ColumnInfo(name = "address_key") val addressKey: String,
    @ColumnInfo(name = "body") val body: String,
)
