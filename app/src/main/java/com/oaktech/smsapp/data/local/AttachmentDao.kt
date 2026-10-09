package com.oaktech.smsapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE message_id = :messageId ORDER BY part_index ASC")
    fun observeForMessage(messageId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE message_id = :messageId ORDER BY part_index ASC")
    suspend fun getForMessage(messageId: Long): List<AttachmentEntity>

    /**
     * Every on-disk attachment path the store still references, for the orphan
     * sweep. Placeholders that never downloaded have a null path and are skipped.
     */
    @Query("SELECT file_path FROM attachments WHERE file_path IS NOT NULL")
    suspend fun allFilePaths(): List<String>

    /** All attachments of a conversation, for the thread's bubbles. */
    @Query(
        "SELECT a.* FROM attachments a JOIN messages m ON m.id = a.message_id " +
            "WHERE m.conversation_id = :conversationId ORDER BY a.part_index ASC",
    )
    fun observeForConversation(conversationId: Long): Flow<List<AttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    /** Marks a placeholder downloaded to disk, storing where the bytes are. */
    @Query(
        "UPDATE attachments SET state = 'RETRIEVED', file_path = :filePath, byte_size = :byteSize WHERE id = :id",
    )
    suspend fun markRetrieved(id: Long, filePath: String, byteSize: Long)

    @Update
    suspend fun update(attachment: AttachmentEntity)
}