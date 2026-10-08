package com.oaktech.smsapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, DraftEntity::class, AttachmentEntity::class],
    version = 10,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SmsDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun draftDao(): DraftDao

    abstract fun attachmentDao(): AttachmentDao

    companion object {
        private const val NAME = "sms.db"

        /**
         * Adds the spam flag to existing conversations. Every existing row is
         * ordinary mail, so the new column defaults to 0 and nothing is hidden
         * by upgrading.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN is_spam INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds the pinned flag to existing conversations. Every existing row is
         * unpinned, so the new column defaults to 0 and the inbox keeps its
         * current ordering after upgrading.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds the delivery lifecycle columns to messages and the drafts table.
         *
         * Every existing row upgrades to having sent but never received a
         * delivery report, which is why delivered_at and error_code default to
         * null: the older rows simply lack the data. There were no drafts before
         * this migration, so the table starts empty.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN delivered_at INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN error_code INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN sim_slot INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS drafts (" +
                        "conversation_id INTEGER NOT NULL PRIMARY KEY, body TEXT NOT NULL)",
                )
            }
        }

        /**
         * Adds the MMS flags to messages and the attachments table.
         *
         * Every existing row is a plain SMS: is_mms defaults to 0 and subject to
         * null, which is also exactly what an upgrade from a pre-MMS build must
         * produce for its existing rows. There were no attachments before this
         * migration, so the table starts empty.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN is_mms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN subject TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attachments (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "message_id INTEGER NOT NULL, " +
                        "content_type TEXT NOT NULL, " +
                        "file_name TEXT NOT NULL, " +
                        "file_path TEXT, " +
                        "byte_size INTEGER NOT NULL, " +
                        "state TEXT NOT NULL, " +
                        "part_index INTEGER NOT NULL, " +
                        "FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_message_id ON attachments (message_id)")
            }
        }

        /**
         * Adds the MMS media lifecycle column to messages.
         *
         * Existing rows are pre-MMS SMS or already-streamed MMS; both have
         * nothing pending, so the column defaults to NONE and no row is
         * accidentally offered a download action.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN download_state TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        /**
         * Adds the retrieve metadata to messages.
         *
         * Only inbound MMS rows need these, and none existed before MMS, so
         * every row upgrades with nulls and no notification is re-offered a
         * download it already served.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN transaction_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN content_location TEXT")
            }
        }

        /**
         * Adds the mute flag to existing conversations. Every existing row is
         * unmuted, so the new column defaults to 0 and no conversation starts
         * silencing its alerts.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN is_muted INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds the composer's unsent-attachment queue to existing drafts. Rows
         * written before this migration simply have no queue, so the column is
         * nullable and every existing draft starts with none.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN pending_attachments TEXT")
            }
        }

        /**
         * Drops the `contact_id` column from conversations.
         *
         * The column has existed since version 1 but has never been written to:
         * contact display names are resolved at runtime instead of being
         * persisted. It is removed with the table-recreate recipe rather than
         * `ALTER TABLE ... DROP COLUMN` because that statement needs SQLite
         * 3.35+ while API 30 (this app's minSdk) ships 3.32. Recreating the
         * table also needs foreign keys off for a moment, otherwise dropping
         * the parent table would cascade-delete every message in it.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = OFF")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversations_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`address_key` TEXT NOT NULL, " +
                        "`display_address` TEXT NOT NULL, " +
                        "`last_message_at` INTEGER NOT NULL, " +
                        "`unread_count` INTEGER NOT NULL, " +
                        "`is_spam` INTEGER NOT NULL, " +
                        "`is_pinned` INTEGER NOT NULL, " +
                        "`is_muted` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO `conversations_new` (" +
                        "`id`, `address_key`, `display_address`, `last_message_at`, " +
                        "`unread_count`, `is_spam`, `is_pinned`, `is_muted`) " +
                        "SELECT `id`, `address_key`, `display_address`, `last_message_at`, " +
                        "`unread_count`, `is_spam`, `is_pinned`, `is_muted` FROM `conversations`",
                )
                db.execSQL("DROP TABLE `conversations`")
                db.execSQL("ALTER TABLE `conversations_new` RENAME TO `conversations`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_conversations_address_key` ON `conversations` (`address_key`)",
                )
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        @Volatile
        private var instance: SmsDatabase? = null

        fun get(context: Context): SmsDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): SmsDatabase =
            Room.databaseBuilder(context, SmsDatabase::class.java, NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .build()
    }
}