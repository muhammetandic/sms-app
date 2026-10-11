package com.oaktech.smsapp.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One conversation, keyed by a normalized phone number rather than the
 * Telephony provider's `thread_id`. The provider reuses and renumbers thread
 * ids across devices and resets, so they are not a durable local key.
 */
@Entity(
    tableName = "conversations",
    indices = [Index(value = ["address_key"], unique = true)],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "address_key")
    val addressKey: String,

    @ColumnInfo(name = "display_address")
    val displayAddress: String,

    @ColumnInfo(name = "last_message_at")
    val lastMessageAt: Long,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,

    /**
     * Moved to the spam folder by the user. Kept in the store rather than
     * deleted, so the folder is viewable and the conversation can be restored;
     * the main list queries filter on this.
     */
    @ColumnInfo(name = "is_spam")
    val isSpam: Boolean = false,

    /**
     * Pinned by the user so the conversation stays at the top of the inbox
     * regardless of message recency. Persisted because pins are meant to be
     * durable across restarts, unlike the in-memory selection.
     */
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,

    /**
     * Muted by the user so new messages are stored but do not alert. Unlike
     * spam, a muted conversation stays in the inbox and keeps counting unread;
     * only the notification is suppressed.
     */
    @ColumnInfo(name = "is_muted")
    val isMuted: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "sent_at"]),
        Index(value = ["dedupe_key"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "conversation_id")
    val conversationId: Long,

    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "sent_at")
    val sentAt: Long,

    @ColumnInfo(name = "direction")
    val direction: MessageDirection,

    @ColumnInfo(name = "status")
    val status: MessageStatus,

    @ColumnInfo(name = "part_count")
    val partCount: Int = 1,

    @ColumnInfo(name = "provider_id")
    val providerId: Long? = null,

    /**
     * When the delivery report for an outgoing message arrived, if it did. Kept
     * alongside the DELIVERED status so the thread's details sheet can show the
     * moment rather than only the fact.
     */
    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    /**
     * The platform result code behind a FAILED status, stored so the thread's
     * details sheet can name the failure type instead of only showing "failed".
     * Values are the app-level codes in [com.oaktech.smsapp.sms.send.ResultCode].
     */
    @ColumnInfo(name = "error_code")
    val errorCode: Int? = null,

    /**
     * The subscription id the message was sent on, for the details sheet.
     * Only meaningful for outgoing rows; incoming rows store null and the sheet
     * shows nothing rather than guessing.
     */
    @ColumnInfo(name = "sim_slot")
    val simSlot: Int? = null,
)

/**
 * A draft belonging to a conversation. Several rows may share one conversation
 * because a contact can have multiple independent unsent messages.
 *
 * A row is written on every change of the composer and removed when the message
 * is sent or the draft is cleared, so a survive-and-restore across process death
 * is a plain row in the store rather than a saveable in the activity state.
 */
@Entity(
    tableName = "drafts",
    indices = [Index(value = ["conversation_id"])],
)
data class DraftEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "conversation_id")
    val conversationId: Long,

    @ColumnInfo(name = "body")
    val body: String,
)

enum class MessageDirection {
    INCOMING,
    OUTGOING,
}

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
}
