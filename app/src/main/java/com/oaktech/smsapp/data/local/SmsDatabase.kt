package com.oaktech.smsapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, DraftEntity::class],
    version = 14,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SmsDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun draftDao(): DraftDao

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

        /**
         * Adds the sender address to messages.
         *
         * Before this, a row said which conversation it belonged to but never
         * which member of a group wrote it. Existing inbound rows are backfilled
         * from their conversation's display address, which is correct for the
         * 1:1 threads that existed: sender and conversation were the same
         * number. Group threads store the sender on future inbound rows instead.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN address TEXT")
                db.execSQL(
                    "UPDATE messages SET address = " +
                        "(SELECT display_address FROM conversations " +
                        "WHERE conversations.id = messages.conversation_id) " +
                        "WHERE direction = 'INCOMING' AND address IS NULL",
                )
            }
        }

        /**
         * Gives drafts their own identity so one contact can have several
         * independent unsent messages. The old table used conversation_id as
         * its primary key, so it must be recreated rather than altered.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = OFF")
                db.execSQL(
                    "CREATE TABLE drafts_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "conversation_id INTEGER NOT NULL, " +
                        "body TEXT NOT NULL, " +
                        "pending_attachments TEXT)",
                )
                db.execSQL(
                    "INSERT INTO drafts_new (conversation_id, body, pending_attachments) " +
                        "SELECT conversation_id, body, pending_attachments FROM drafts",
                )
                db.execSQL("DROP TABLE drafts")
                db.execSQL("ALTER TABLE drafts_new RENAME TO drafts")
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        /**
         * Removes MMS: drops the multimedia columns from messages, the
         * unsent-attachment queue from drafts, and the attachments table.
         *
         * The app no longer sends, receives, renders or shares MMS, so none of
         * this state can be produced or read again. The two columns that survive
         * a rewrite (messages and drafts) are recreated rather than altered for
         * the same reason as [MIGRATION_9_10]: API 30 ships SQLite 3.32, which
         * has no `ALTER TABLE ... DROP COLUMN`. Messages are rebuilt with foreign
         * keys off so dropping the old table does not cascade-delete anything
         * that has already been copied (there are no child tables left once
         * attachments is gone, but the pragma keeps the recipe independent of
         * that). The attachments table is dropped outright.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = OFF")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversation_id` INTEGER NOT NULL, " +
                        "`dedupe_key` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, " +
                        "`sent_at` INTEGER NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`part_count` INTEGER NOT NULL, " +
                        "`provider_id` INTEGER, " +
                        "`delivered_at` INTEGER, " +
                        "`error_code` INTEGER, " +
                        "`sim_slot` INTEGER, " +
                        "FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO `messages_new` (" +
                        "`id`, `conversation_id`, `dedupe_key`, `body`, `sent_at`, " +
                        "`direction`, `status`, `part_count`, `provider_id`, `delivered_at`, " +
                        "`error_code`, `sim_slot`) " +
                        "SELECT `id`, `conversation_id`, `dedupe_key`, `body`, `sent_at`, " +
                        "`direction`, `status`, `part_count`, `provider_id`, `delivered_at`, " +
                        "`error_code`, `sim_slot` FROM `messages`",
                )
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversation_id_sent_at` " +
                        "ON `messages` (`conversation_id`, `sent_at`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_dedupe_key` " +
                        "ON `messages` (`dedupe_key`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drafts_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversation_id` INTEGER NOT NULL, " +
                        "`body` TEXT NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO `drafts_new` (`id`, `conversation_id`, `body`) " +
                        "SELECT `id`, `conversation_id`, `body` FROM `drafts`",
                )
                db.execSQL("DROP TABLE `drafts`")
                db.execSQL("ALTER TABLE `drafts_new` RENAME TO `drafts`")

                db.execSQL("DROP TABLE IF EXISTS `attachments`")
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        /**
         * Indexes drafts by conversation.
         *
         * A draft is looked up by its conversation on every thread open and every
         * editor keystroke (the DAO updates the latest row for a conversation),
         * and the old table had no index for that. The messages table has always
         * indexed its conversation column; this brings drafts in line. Purely an
         * index, so no row is rewritten.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_drafts_conversation_id` " +
                        "ON `drafts` (`conversation_id`)",
                )
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
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                )
                .build()
    }
}
