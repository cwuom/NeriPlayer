package moe.ouom.neriplayer.data.local.database

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NeriUserDataDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun everyHistoricalUpgradeReachesTheCurrentRoomSchema() {
        for (version in 1 until NeriUserDataDatabase.FINAL_DB_VERSION) {
            val databaseName = "database-upgrade-from-v$version"
            helper.createDatabase(databaseName, version).close()
            helper.runMigrationsAndValidate(
                databaseName, NeriUserDataDatabase.FINAL_DB_VERSION, false,
                *allMigrations()
            ).use { database ->
                assertOnlyDeclaredAndUpgradeTables(
                    database,
                    retainsLegacyPayload = version < NeriUserDataDatabase.MIGRATION_15_FINAL.endVersion
                )
            }
        }
    }

    @Test
    fun releaseVersion15KeepsCancelledIdentityAndMetadataBeforeDroppingLegacyTables() {
        helper.createDatabase("database-upgrade-from-v15", 15).use { database ->
            database.execSQL("INSERT INTO download_cancelled_key VALUES ('song', 11)")
            database.execSQL(
                "INSERT INTO download_snapshot_metadata " +
                    "(root_key, audio_name, stable_key, user_lyric_offset_ms, duration_ms, romanized_lyric_path) " +
                    "VALUES ('root', 'song.flac', 'song', 25, 180000, 'song.rom.lrc')"
            )
        }
        helper.runMigrationsAndValidate(
            "database-upgrade-from-v15", NeriUserDataDatabase.FINAL_DB_VERSION, false,
            *allMigrations()
        ).use { database ->
            assertOnlyDeclaredAndUpgradeTables(database)
            database.query("SELECT payload_json FROM legacy_download_upgrade_payload WHERE stable_key = 'song'").use { cursor ->
                check(cursor.moveToFirst())
                val payload = JSONObject(cursor.getString(0))
                assertEquals("song", payload.getString("stableKey"))
                assertEquals("song.flac", payload.getString("audioFileName"))
                assertEquals(11L, payload.getJSONObject("download_cancelled_key").getLong("cancelled_at_ms"))
                val metadata = payload.getJSONObject("download_snapshot_metadata")
                assertEquals(180000L, metadata.getLong("duration_ms"))
                assertEquals(25L, metadata.getLong("user_lyric_offset_ms"))
                assertEquals("song.rom.lrc", metadata.getString("romanized_lyric_path"))
            }
            database.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                    "AND name IN ('download_cancelled_key', 'download_snapshot_metadata')"
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        }
    }

    private fun assertOnlyDeclaredAndUpgradeTables(
        database: SupportSQLiteDatabase,
        retainsLegacyPayload: Boolean = true
    ) {
        val schemaPath = "${NeriUserDataDatabase::class.java.name}/${NeriUserDataDatabase.FINAL_DB_VERSION}.json"
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open(schemaPath)
            .bufferedReader().use { JSONObject(it.readText()) }
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        val expectedTables = (0 until entities.length()).mapTo(mutableSetOf()) { index ->
            entities.getJSONObject(index).getString("tableName")
        }
        // 恢复旧下载前需要保留辅助表，仍然检查其它意外残留表
        if (retainsLegacyPayload) {
            expectedTables += setOf("legacy_download_upgrade_payload", "legacy_download_upgrade_quarantine")
        }
        val actualTables = buildSet {
            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT IN ('android_metadata', 'room_master_table', 'sqlite_sequence')"
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(expectedTables, actualTables)
    }

    @Test
    fun recoveryIndexUpgradeKeepsExistingOperationsAndTheirByteWatermark() {
        helper.createDatabase("database-upgrade-from-v17", 17).use { database ->
            database.execSQL(
                "INSERT INTO download_operation " +
                    "(operation_id, stable_key, library_id, state, queue_order, source_hint_json, " +
                    "staging_dir_name, bytes_written, retry_count, created_at_ms, updated_at_ms) " +
                    "VALUES ('operation', 'song', 'root', 'CORE_COMMITTED', 5, '{}', 'staging', 1024, 0, 10, 20)"
            )
        }
        helper.runMigrationsAndValidate(
            "database-upgrade-from-v17", NeriUserDataDatabase.FINAL_DB_VERSION, true,
            NeriUserDataDatabase.MIGRATION_17_18,
            NeriUserDataDatabase.MIGRATION_18_19,
            NeriUserDataDatabase.MIGRATION_19_20
        ).use { database ->
            database.query("SELECT state, bytes_written FROM download_operation WHERE operation_id = 'operation'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("CORE_COMMITTED", cursor.getString(0))
                assertEquals(1024L, cursor.getLong(1))
            }
            val indexColumns = mutableListOf<String>()
            database.query("SELECT name FROM pragma_index_info('index_download_operation_recovery_cursor') ORDER BY seqno").use { cursor ->
                while (cursor.moveToNext()) indexColumns += cursor.getString(0)
            }
            assertEquals(listOf("state", "stop_requested_by_user", "queue_order", "created_at_ms", "operation_id"), indexColumns)
        }
    }

    private fun allMigrations(): Array<Migration> = arrayOf(
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
        NeriUserDataDatabase.MIGRATION_13_14,
        NeriUserDataDatabase.MIGRATION_14_15,
        NeriUserDataDatabase.MIGRATION_15_FINAL,
        NeriUserDataDatabase.MIGRATION_16_17,
        NeriUserDataDatabase.MIGRATION_17_18,
        NeriUserDataDatabase.MIGRATION_18_19,
            NeriUserDataDatabase.MIGRATION_19_20
    )
}
