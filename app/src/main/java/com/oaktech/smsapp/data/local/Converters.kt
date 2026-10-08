package com.oaktech.smsapp.data.local

import androidx.room.TypeConverter

class Converters {

    /**
     * Room would store a Boolean as 0/1 via INTEGER on its own, but naming the
     * converter keeps the column type explicit in the exported schema, which is
     * what the migration's ALTER TABLE is validated against.
     */
    @TypeConverter
    fun fromBoolean(value: Boolean): Int = if (value) 1 else 0

    @TypeConverter
    fun toBoolean(value: Int): Boolean = value != 0

    @TypeConverter
    fun fromDirection(direction: MessageDirection): String = direction.name

    @TypeConverter
    fun toDirection(value: String): MessageDirection =
        runCatching { MessageDirection.valueOf(value) }.getOrDefault(MessageDirection.INCOMING)

    @TypeConverter
    fun fromStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): MessageStatus =
        runCatching { MessageStatus.valueOf(value) }.getOrDefault(MessageStatus.SENT)

    @TypeConverter
    fun fromAttachmentState(state: AttachmentState): String = state.name

    @TypeConverter
    fun toAttachmentState(value: String): AttachmentState =
        runCatching { AttachmentState.valueOf(value) }.getOrDefault(AttachmentState.RETRIEVABLE)

    @TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name

    @TypeConverter
    fun toDownloadState(value: String): DownloadState =
        runCatching { DownloadState.valueOf(value) }.getOrDefault(DownloadState.NONE)
}