package moe.ouom.neriplayer.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackHourTotalsCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun version23ExportsMeasuredHoursAndPreservesOriginalIntervals() {
        val databaseName = "database-upgrade-measured-hours-v23"
        val hour = 3_600_000L
        val firstDay = hour + hour / 2
        val nextDay = firstDay + 25 * hour
        val captureInstance = "4bfa6851-f556-45c4-b92c-964350f1e51b"
        val metadata = "identity_key, id, name, artist, album, album_id, duration_ms, total_listen_ms, " +
            "play_count, last_played_at, first_played_at"
        helper.createDatabase(databaseName, 23).use { db ->
            db.execSQL("INSERT INTO migration_metadata (key,value,updated_at) VALUES ('playback_stats_capture_database_instance',?,0)",
                arrayOf(captureInstance))
            db.execSQL("INSERT INTO playback_stat ($metadata) VALUES ('a',1,'Song','Artist','Album',1,0,90000,1,?,?)",
                arrayOf(nextDay + hour, firstDay))
            for (day in listOf(firstDay, nextDay)) {
                db.execSQL("INSERT INTO playback_stat_bucket (day_start_at,$metadata) " +
                    "VALUES (?,'a',1,'Song','Artist','Album',1,0,90000,1,?,?)",
                    arrayOf(day, day + 24 * hour, day))
            }
            db.execSQL("INSERT INTO playback_stat_hour VALUES (?, 'a', 1, 900, 7)", arrayOf(firstDay + hour))
            fun addInterval(at: Long, length: Long, localHour: Int) {
                db.execSQL("INSERT INTO playback_listening_interval (identity_key,start_at,end_at,local_hour,epoch_started_at) " +
                    "VALUES ('a',?,?,?,7)", arrayOf<Any>(at, at + length, localHour))
            }
            addInterval(firstDay + hour + 2000, 100, 1)
            addInterval(firstDay + 2 * hour + 2000, 500, 1)
            addInterval(firstDay + 2 * hour + 8000, 75, 1)
            addInterval(nextDay + 500, 300, 0)
            db.execSQL("INSERT INTO playback_stat_hour VALUES (?, 'unmatched', 11, 600, 7)", arrayOf(nextDay))
        }
        helper.runMigrationsAndValidate(databaseName, 24, true, NeriUserDataDatabase.MIGRATION_23_24).use { db ->
            db.query("SELECT hourly_totals, local_hourly_totals, total_listen_ms FROM playback_stat_bucket ORDER BY day_start_at").use { rows ->
                assertTrue(rows.moveToFirst())
                val first = PlaybackHourTotalsCodec.decode(rows.getString(0))
                assertEquals(rows.getString(0), rows.getString(1))
                assertEquals(90000L, rows.getLong(2))
                assertEquals(listOf(1000L, 575L), first.map { it.totalListenMs })
                assertEquals(listOf(firstDay + hour, firstDay + 2 * hour + 2000), first.map { it.hourStartAt })
                assertEquals(listOf(1, 1), first.map { it.localHour })
                assertTrue(rows.moveToNext())
                val second = PlaybackHourTotalsCodec.decode(rows.getString(0)).single()
                assertEquals(rows.getString(0), rows.getString(1))
                assertEquals(300L, second.totalListenMs)
                assertEquals(nextDay + 500, second.hourStartAt)
                assertEquals(0, second.localHour)
                assertEquals(first.map { it.deviceId }.distinct(), listOf(second.deviceId))
                assertEquals("legacy-hour-$captureInstance", second.deviceId)
                assertEquals(7L, second.epochStartedAt)
            }
            db.query("SELECT (SELECT COUNT(*) FROM playback_stat_hour), (SELECT COUNT(*) FROM playback_listening_interval)").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
                assertEquals(4, it.getInt(1))
            }
        }
    }

    @Test
    fun version22PreservesLegacyHourTotalsWithoutInventingIntervals() {
        val databaseName = "database-upgrade-listening-intervals-v22"
        helper.createDatabase(databaseName, 22).use { database ->
            database.execSQL("INSERT INTO playback_stat_hour VALUES (5400000, 'track', 7, 900000, 0)")
        }
        helper.runMigrationsAndValidate(databaseName, 23, true, NeriUserDataDatabase.MIGRATION_22_23)
            .use { database ->
                database.query("SELECT hour_start_at, local_hour, listened_ms FROM playback_stat_hour").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(5400000L, cursor.getLong(0))
                    assertEquals(7, cursor.getInt(1))
                    assertEquals(900000L, cursor.getLong(2))
                }
                database.query("SELECT COUNT(*) FROM playback_listening_interval").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
    }

    @Test
    fun everyHistoricalUpgradeReachesTheCurrentRoomSchema() {
        for (version in 1 until NeriUserDataDatabase.FINAL_DB_VERSION) {
            val databaseName = "database-upgrade-from-v$version"
            helper.createDatabase(databaseName, version).close()
            helper.runMigrationsAndValidate(
                databaseName, NeriUserDataDatabase.FINAL_DB_VERSION, false,
                *NeriUserDataDatabase.allMigrations()
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
            *NeriUserDataDatabase.allMigrations()
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
            *NeriUserDataDatabase.allMigrations()
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

    @Test
    fun version20PreservesHistoryAndQueuesWithoutInventingLocalSourceProvenance() {
        val databaseName = "database-upgrade-source-provenance-v20"
        val reference = "content://media/external/audio/media/42"
        val lyricPayload = """{"revision":7,"edited":true}"""
        helper.createDatabase(databaseName, 20).use { database ->
            database.execSQL(
                "INSERT INTO play_history " +
                    "(identity_key, identity_id, identity_album, identity_media_uri, id, name, artist, album, " +
                    "album_id, duration_ms, resume_position_ms, played_at, media_uri, lyric_sync_payload_json) " +
                    "VALUES (?, 42, 'Album', ?, 42, 'History', 'Artist', 'Album', 0, 1000, 1500, 10, ?, ?)",
                arrayOf("42|Album|$reference", reference, reference, lyricPayload)
            )
            database.execSQL(
                "INSERT INTO playback_queue_song " +
                    "(queue_id, position, id, name, artist, album, album_id, duration_ms, user_lyric_offset_ms, " +
                    "media_uri, lyric_sync_payload_json) VALUES ('main', 0, 42, 'Queue', 'Artist', 'Album', 0, 1000, 25, ?, ?)",
                arrayOf(reference, lyricPayload)
            )
            database.execSQL(
                "INSERT INTO playback_queue_state " +
                    "(id, current_index, position_ms, should_resume_playback, updated_at) VALUES (1, 0, 1500, 1, 10)"
            )
        }

        helper.runMigrationsAndValidate(
            databaseName, NeriUserDataDatabase.FINAL_DB_VERSION, true, *NeriUserDataDatabase.allMigrations()
        ).use { database ->
            database.query("SELECT name, media_uri, resume_position_ms, lyric_sync_payload_json, " +
                "explicit_local_source_reference FROM play_history WHERE id = 42").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("History", cursor.getString(0))
                assertEquals(reference, cursor.getString(1))
                assertEquals(1500L, cursor.getLong(2))
                assertEquals(lyricPayload, cursor.getString(3))
                assertTrue(cursor.isNull(4))
            }
            database.query("SELECT name, media_uri, user_lyric_offset_ms, lyric_sync_payload_json, " +
                "explicit_local_source_reference FROM playback_queue_song WHERE queue_id = 'main' AND position = 0").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("Queue", cursor.getString(0))
                assertEquals(reference, cursor.getString(1))
                assertEquals(25L, cursor.getLong(2))
                assertEquals(lyricPayload, cursor.getString(3))
                assertTrue(cursor.isNull(4))
            }
            database.query("SELECT position_ms, should_resume_playback FROM playback_queue_state WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(1500L, cursor.getLong(0))
                assertEquals(1, cursor.getInt(1))
            }
        }
    }

    @Test
    fun version21KeepsListenTotalsAndPendingWorkWithoutInventingHourRecords() {
        val databaseName = "database-upgrade-listening-hours-v21"
        helper.createDatabase(databaseName, 21).use { database ->
            database.execSQL(
                "INSERT INTO playback_stat (identity_key, id, name, artist, album, album_id, " +
                    "duration_ms, total_listen_ms, play_count, last_played_at, first_played_at) " +
                    "VALUES ('track', 1, 'Track', 'Artist', 'Album', 0, 180000, 300000, 2, 900000, 1000)"
            )
            database.execSQL(
                "INSERT INTO playback_stats_pending_delta " +
                    "(id, sequence, track_json, listened_ms, played_at, epoch_started_at, device_id) " +
                    "VALUES ('pending', 7, '{}', 5000, 905000, 0, 'local')"
            )
        }
        helper.runMigrationsAndValidate(databaseName, 22, true, NeriUserDataDatabase.MIGRATION_21_22)
            .use { database ->
                database.query("SELECT total_listen_ms, play_count FROM playback_stat WHERE identity_key = 'track'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(300000L, cursor.getLong(0))
                        assertEquals(2, cursor.getInt(1))
                    }
                database.query("SELECT sequence, listened_ms, played_zone FROM playback_stats_pending_delta WHERE id = 'pending'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(7L, cursor.getLong(0))
                        assertEquals(5000L, cursor.getLong(1))
                        assertTrue(cursor.isNull(2))
                    }
                database.query("SELECT COUNT(*) FROM playback_stat_hour").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }
            }
    }
}
