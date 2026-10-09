package com.oaktech.smsapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {

    /**
     * The draft body for one conversation, or null when it has none.
     *
     * A missing row selects zero rows, so the flow emits an empty list until a
     * draft is written; the first item of that list is what the caller wants.
     */
    @Query("SELECT body FROM drafts WHERE conversation_id = :conversationId")
    fun observe(conversationId: Long): Flow<List<String>>

    @Query("SELECT body FROM drafts WHERE conversation_id = :conversationId")
    suspend fun get(conversationId: Long): String?

    @Query("SELECT * FROM drafts WHERE conversation_id = :conversationId")
    suspend fun row(conversationId: Long): DraftEntity?

    /** The composer's unsent-attachment queue, or the empty list when none. */
    @Query("SELECT pending_attachments FROM drafts WHERE conversation_id = :conversationId")
    fun observePending(conversationId: Long): Flow<List<String?>>

    /**
     * Every non-empty attachment queue in the store, for the orphan sweep: a
     * queued file is still referenced even though it has no message row yet.
     */
    @Query("SELECT pending_attachments FROM drafts WHERE pending_attachments IS NOT NULL")
    suspend fun allPendingAttachments(): List<String>

    /**
     * Rewrites the draft text. Returns the number of rows touched so the
     * repository can insert the row only when this is the first write.
     */
    @Query("UPDATE drafts SET body = :body WHERE conversation_id = :conversationId")
    suspend fun updateBody(conversationId: Long, body: String): Int

    /** Rewrites the attachment queue; see [updateBody] for the row count. */
    @Query("UPDATE drafts SET pending_attachments = :pending WHERE conversation_id = :conversationId")
    suspend fun updatePending(conversationId: Long, pending: String?): Int

    /**
     * Drops a draft row once both halves have been cleared, so a blank text
     * that is keeping an attachment queue does not delete the queue with it.
     */
    @Query("DELETE FROM drafts WHERE conversation_id = :conversationId AND body = '' AND pending_attachments IS NULL")
    suspend fun deleteIfEmpty(conversationId: Long)

    /** Inserts a fresh draft row before either half has been written before. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE conversation_id = :conversationId")
    suspend fun clear(conversationId: Long)
}