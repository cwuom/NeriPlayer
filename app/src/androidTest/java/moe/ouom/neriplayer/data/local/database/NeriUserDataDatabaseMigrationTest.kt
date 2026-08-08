package moe.ouom.neriplayer.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriUserDataDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NeriUserDataDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateFromVersion1ToVersion14() {
        helper.createDatabase(EMPTY_SCHEMA_DATABASE_NAME, 1).close()

        migrateToCurrent(EMPTY_SCHEMA_DATABASE_NAME).close()
    }

    @Test
    fun migrateFromVersion1ToVersion14PreservesLocalPlaylistAndSyncRecords() {
        helper.createDatabase(DATA_PRESERVATION_DATABASE_NAME, 1).use { database ->
            insertVersion1Records(database)
        }

        migrateToCurrent(DATA_PRESERVATION_DATABASE_NAME).use { database ->
            database.query(
                "SELECT `name`, `song_order_version` FROM `local_playlist` " +
                    "WHERE `playlist_id` = 42"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Migration playlist", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
            }
            assertRowCount(database, "track", 1)
            assertRowCount(database, "playlist_member", 1)
            assertRowCount(database, "playlist_member_token", 1)
            assertRowCount(database, "sync_outbox", 1)
            assertRowCount(database, "sync_replica_checkpoint", 1)
            assertRowCount(database, "migration_metadata", 1)
        }
    }

    private fun migrateToCurrent(databaseName: String): SupportSQLiteDatabase {
        return helper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            NeriUserDataDatabase.MIGRATION_1_2,
            NeriUserDataDatabase.MIGRATION_2_3,
            NeriUserDataDatabase.MIGRATION_3_4,
            NeriUserDataDatabase.MIGRATION_4_5,
            NeriUserDataDatabase.MIGRATION_5_6,
            NeriUserDataDatabase.MIGRATION_6_7,
            NeriUserDataDatabase.MIGRATION_7_8,
            NeriUserDataDatabase.MIGRATION_8_9,
            NeriUserDataDatabase.MIGRATION_9_10,
            NeriUserDataDatabase.MIGRATION_10_11,
            NeriUserDataDatabase.MIGRATION_11_12,
            NeriUserDataDatabase.MIGRATION_12_13,
            NeriUserDataDatabase.MIGRATION_13_14
        )
    }

    private fun insertVersion1Records(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO `local_playlist` (
                `playlist_id`, `name`, `display_position`, `modified_at`,
                `song_order_version`, `is_system`
            ) VALUES (42, 'Migration playlist', 0, 1000, 2, 0)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `track` (
                `identity_key`, `identity_id`, `identity_album`, `song_id`,
                `name`, `artist`, `album`, `album_id`, `duration_ms`,
                `payload_schema_version`, `durable_payload_json`
            ) VALUES ('track-42', 42, 'album-42', 42, 'Migration track',
                'Migration artist', 'Migration album', 42, 180000, 1, '{}')
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `playlist_member` (
                `playlist_id`, `identity_key`, `display_position`, `added_at`,
                `order_tie_break`, `member_payload_schema_version`,
                `member_payload_json`
            ) VALUES (42, 'track-42', 0, 1000, 0, 1, '{}')
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `playlist_member_token` (
                `playlist_id`, `identity_key`, `device_id`, `counter`, `token_index`
            ) VALUES (42, 'track-42', 'device-1', 1, 0)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `sync_outbox` (
                `sequence`, `operation_id`, `expected_domain_revision`,
                `payload_version`, `mutation_payload_json`, `status`,
                `attempt_count`, `created_at`, `updated_at`
            ) VALUES (1, 'operation-1', 1, 1, '{}', 'PENDING', 0, 1000, 1000)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `sync_replica_checkpoint` (
                `transport_id`, `domain_revision`, `status`, `last_synced_at`,
                `last_attempt_at`
            ) VALUES ('github', 1, 'SYNCED', 1000, 1000)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `migration_metadata` (`key`, `value`, `updated_at`)
            VALUES ('migration-test', 'complete', 1000)
            """.trimIndent()
        )
    }

    private fun assertRowCount(
        database: SupportSQLiteDatabase,
        tableName: String,
        expectedCount: Int
    ) {
        database.query("SELECT COUNT(*) FROM `$tableName`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedCount, cursor.getInt(0))
        }
    }

    private companion object {
        const val EMPTY_SCHEMA_DATABASE_NAME = "neri-user-data-migration-empty-test"
        const val DATA_PRESERVATION_DATABASE_NAME = "neri-user-data-migration-data-test"
    }
}
