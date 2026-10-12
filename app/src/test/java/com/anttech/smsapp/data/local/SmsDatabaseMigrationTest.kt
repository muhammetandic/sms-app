package com.anttech.smsapp.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real migration chain from every exported past schema up to the
 * current one, through Room's own migration machinery.
 *
 * Each [MigrationTestHelper.createDatabase] call rebuilds a database from an
 * exported schema JSON (the schemas directory is wired into the debug variant's
 * assets in build.gradle.kts so the helper can read it as a context asset),
 * then [MigrationTestHelper.runMigrationsAndValidate]
 * runs the chain and checks the result column-for-column against the current
 * exported schema. This catches the mistakes an add-migration-and-forget can
 * hide: a migration whose SQL does not reproduce what the entity now declares,
 * a missing defaults, or an accidentally reordered column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsDatabaseMigrationTest {

    /** The schema this test migrates up to; keep in lockstep with `version` on [SmsDatabase]. */
    private val currentVersion = 14

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmsDatabase::class.java,
    )

    @Test
    fun `migrating every released schema reaches the current schema`() {
        // Version 6's schema JSON was never committed, so there is no asset to
        // seed a v6 database from; every other start version has one.
        val startVersions = listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13)
        for (start in startVersions) {
            val name = "migration-$start"
            helper.createDatabase(name, start)
            helper.runMigrationsAndValidate(
                name,
                currentVersion,
                validateDroppedTables = true,
                *allMigrations.drop(start - 1).toTypedArray(),
            )
        }
    }

    @Test
    fun `migrating to the current schema preserves existing conversations`() = runTest {
        val name = "migration-data"
        helper.createDatabase(name, 1).use { db ->
            // At version 1 the conversations table only carried the base
            // columns; the flags were added by later migrations. The message
            // row guards against a migrate-to-current that drops the parent
            // table with foreign keys left on: that would cascade every
            // message out of the table.
            db.execSQL(
                "INSERT INTO conversations (id, address_key, display_address, last_message_at, unread_count) " +
                    "VALUES (1, '+15550000001', '+15550000001', 1000, 2)",
            )
            db.execSQL(
                "INSERT INTO messages (conversation_id, dedupe_key, body, sent_at, direction, status, part_count) " +
                    "VALUES (1, 'v1-a', 'hello', 1000, 'INCOMING', 'NONE', 1)",
            )
        }

        helper.runMigrationsAndValidate(name, currentVersion, true, *allMigrations)

        val upgraded = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SmsDatabase::class.java,
            name,
        ).addMigrations(*allMigrations).build()
        try {
            val conversation = upgraded.conversationDao().observeConversations().first().single()
            assertEquals("+15550000001", conversation.addressKey)
            assertEquals(2, conversation.unreadCount)
            assertEquals(1, upgraded.messageDao().observeMessages(1).first().size)
        } finally {
            upgraded.close()
        }
    }

    private companion object {
        val allMigrations = arrayOf(
            SmsDatabase.MIGRATION_1_2,
            SmsDatabase.MIGRATION_2_3,
            SmsDatabase.MIGRATION_3_4,
            SmsDatabase.MIGRATION_4_5,
            SmsDatabase.MIGRATION_5_6,
            SmsDatabase.MIGRATION_6_7,
            SmsDatabase.MIGRATION_7_8,
            SmsDatabase.MIGRATION_8_9,
            SmsDatabase.MIGRATION_9_10,
            SmsDatabase.MIGRATION_10_11,
            SmsDatabase.MIGRATION_11_12,
            SmsDatabase.MIGRATION_12_13,
            SmsDatabase.MIGRATION_13_14,
        )
    }
}
