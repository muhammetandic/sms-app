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

    /**
     * Whether this row is a multimedia message rather than a plain SMS.
     *
     * MMS rows carry their text part in [body]; everything else that traveled
     * with it (images, audio, slides) lives in the attachments table, because
     * the bytes can be large and are never searched.
     */
    @ColumnInfo(name = "is_mms")
    val isMms: Boolean = false,

    /** The subject line from an MMS, if the message had one. */
    @ColumnInfo(name = "subject")
    val subject: String? = null,

    /**
     * Whether an inbound MMS still has media to fetch.
     *
     * Only meaningful for inbound MMS rows. NONE means the message streamed all
     * its parts (or is a text-only MMS with none to fetch); PENDING means media
     * was advertised but is not on disk yet, and the thread shows a download
     * affordance; RETRIEVED means the pending media has since been fetched.
     */
    @ColumnInfo(name = "download_state")
    val downloadState: DownloadState = DownloadState.NONE,

    /**
     * X-Mms-Transaction-ID of the inbound MMS, kept so a PENDING row can be
     * re-retrieved (and resolved) from the thread after a failed auto-fetch.
     */
    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,

    /**
     * X-Mms-Content-Location of a notification.ind: the MMSC path to GET for
     * the body. Only inbound MMS rows carry it, and only until the body lands.
     */
    @ColumnInfo(name = "content_location")
    val contentLocation: String? = null,
)

/**
 * A draft for one conversation, keyed by the conversation it belongs to.
 *
 * A row is written on every change of the composer and removed when the message
 * is sent or the draft is cleared, so a survive-and-restore across process death
 * is a plain row in the store rather than a saveable in the activity state.
 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: Long,

    @ColumnInfo(name = "body")
    val body: String,

    /**
     * The attachments picked into the composer but not yet sent, encoded as
     * JSON. Null when the draft carries none. Stored next to the text so a send
     * or a keystroke only has to touch one column and restarts restore both.
     */
    @ColumnInfo(name = "pending_attachments")
    val pendingAttachments: String? = null,
)

/**
 * One attachment of a message, usually an MMS media part.
 *
 * The bytes live in an app-private file (attachment storage, not a content
 * provider, so they survive the retiring of a transient `content://` uri and
 * can be shared through the app's own FileProvider). A row whose [filePath] is
 * null is a placeholder: the media was advertised but not yet fetched, and the
 * thread shows a download affordance for it.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["message_id"])],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: Long,

    @ColumnInfo(name = "content_type")
    val contentType: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_path")
    val filePath: String? = null,

    @ColumnInfo(name = "byte_size")
    val byteSize: Long = 0,

    @ColumnInfo(name = "state")
    val state: AttachmentState,

    /** Order within the message, matching the PDU part order. */
    @ColumnInfo(name = "part_index")
    val partIndex: Int = 0,
)

enum class AttachmentState {
    /** Bytes are on disk at [AttachmentEntity.filePath]. */
    RETRIEVED,

    /**
     * The media was advertised but is not on disk yet; the thread shows a
     * download action instead of a preview.
     */
    RETRIEVABLE,
}

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

/**
 * Lifecycle of an inbound MMS's media, tracked at the message level because a
 * notification arrives before (and independently of) the parts that belong to it.
 */
enum class DownloadState {
    /** Nothing to fetch: the parts arrived, or there never were any. */
    NONE,

    /** The message advertised media that is not on disk yet. */
    PENDING,

    /** The PENDING media has since been fetched. */
    RETRIEVED,
}