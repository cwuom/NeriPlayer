package moe.ouom.neriplayer.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
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
    fun migrateFromVersion1ToVersion15() {
        helper.createDatabase(TEST_DATABASE_NAME, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            15,
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
            NeriUserDataDatabase.MIGRATION_13_14,
            NeriUserDataDatabase.MIGRATION_14_15
        ).close()
    }

    @Test
    fun migrateFromVersion1ToVersion15KeepsExistingLocalPlaylistRows() {
        helper.createDatabase(TEST_DATABASE_WITH_DATA_NAME, 1).apply {
            insertVersion1LocalPlaylistFixture()
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_WITH_DATA_NAME,
            15,
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
            NeriUserDataDatabase.MIGRATION_13_14,
            NeriUserDataDatabase.MIGRATION_14_15
        )

        try {
            assertEquals(1L, migrated.longFor("SELECT COUNT(*) FROM local_playlist"))
            assertEquals(1L, migrated.longFor("SELECT COUNT(*) FROM track"))
            assertEquals(1L, migrated.longFor("SELECT COUNT(*) FROM playlist_member"))
            assertEquals(1L, migrated.longFor("SELECT COUNT(*) FROM playlist_member_token"))
            assertEquals(
                "old-device",
                migrated.stringFor(
                    "SELECT device_id FROM playlist_member_token " +
                        "WHERE playlist_id = 601 AND identity_key = '9001|NeteaseAlbum|'"
                )
            )
            assertEquals(
                42L,
                migrated.longFor(
                    "SELECT counter FROM playlist_member_token " +
                        "WHERE playlist_id = 601 AND identity_key = '9001|NeteaseAlbum|'"
                )
            )
            assertEquals(
                0L,
                migrated.longFor(
                    "SELECT token_index FROM playlist_member_token " +
                        "WHERE playlist_id = 601 AND identity_key = '9001|NeteaseAlbum|'"
                )
            )
            assertEquals(
                "旧Room歌单",
                migrated.stringFor("SELECT name FROM local_playlist WHERE playlist_id = 601")
            )
            assertEquals(
                "旧Room歌曲",
                migrated.stringFor("SELECT name FROM track WHERE identity_key = '9001|NeteaseAlbum|'")
            )
            assertEquals(
                "room_primary",
                migrated.stringFor(
                    "SELECT value FROM migration_metadata " +
                        "WHERE key = 'local_playlist_cutover_state'"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion14ToVersion15KeepsSnapshotMetadata() {
        helper.createDatabase(TEST_DATABASE_VERSION_14_NAME, 14).apply {
            execSQL(
                """
                INSERT INTO download_snapshot_metadata (
                  root_key, audio_name, user_lyric_offset_ms, duration_ms
                ) VALUES ('root', 'song.flac', 0, 180000)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_14_NAME,
            15,
            true,
            NeriUserDataDatabase.MIGRATION_14_15
        )

        try {
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM download_snapshot_metadata " +
                        "WHERE root_key = 'root' AND audio_name = 'song.flac'"
                )
            )
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info('download_snapshot_metadata') " +
                        "WHERE name = 'romanized_lyric_path'"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion15ToVersion16AddsRomanizedLyricColumns() {
        helper.createDatabase(TEST_DATABASE_VERSION_15_NAME, 15).apply {
            execSQL(
                """
                INSERT INTO downloaded_song_catalog (
                  catalog_key, root_key, display_position, id, name, artist, album,
                  file_path, file_size, download_time, user_lyric_offset_ms, duration_ms
                ) VALUES ('file:song.flac', 'root', 0, 1, 'Song', 'Artist', 'Album',
                  '/song.flac', 1, 10, 0, 1000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO download_snapshot_metadata (
                  root_key, audio_name, user_lyric_offset_ms, duration_ms
                ) VALUES ('root', 'song.flac', 0, 1000)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_15_NAME,
            16,
            true,
            NeriUserDataDatabase.MIGRATION_15_16
        )

        try {
            assertEquals(
                2L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info('downloaded_song_catalog') " +
                        "WHERE name IN ('matched_romanized_lyric', 'original_romanized_lyric')"
                )
            )
            assertEquals(
                2L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info('download_snapshot_metadata') " +
                        "WHERE name IN ('matched_romanized_lyric', 'original_romanized_lyric')"
                )
            )
            assertEquals(
                2L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info('download_pending_queue') " +
                        "WHERE name IN ('matched_romanized_lyric', 'original_romanized_lyric')"
                )
            )
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM downloaded_song_catalog " +
                        "WHERE matched_romanized_lyric IS NULL AND original_romanized_lyric IS NULL"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion16ToVersion17KeepsRomanizedLyricColumnsAndRows() {
        helper.createDatabase(TEST_DATABASE_VERSION_16_NAME, 16).apply {
            execSQL(
                """
                INSERT INTO downloaded_song_catalog (
                  catalog_key, root_key, display_position, id, name, artist, album,
                  file_path, file_size, download_time, matched_romanized_lyric,
                  original_romanized_lyric, user_lyric_offset_ms, duration_ms
                ) VALUES ('file:song.flac', 'root', 0, 1, 'Song', 'Artist', 'Album',
                  '/song.flac', 1, 10, 'roma', 'original roma', 0, 1000)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_16_NAME,
            17,
            true,
            NeriUserDataDatabase.MIGRATION_16_17
        )

        try {
            assertEquals(
                "roma",
                migrated.stringFor(
                    "SELECT matched_romanized_lyric FROM downloaded_song_catalog " +
                        "WHERE catalog_key = 'file:song.flac'"
                )
            )
            assertEquals(
                "original roma",
                migrated.stringFor(
                    "SELECT original_romanized_lyric FROM downloaded_song_catalog " +
                        "WHERE catalog_key = 'file:song.flac'"
                )
            )
            assertEquals(
                2L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info('download_pending_queue') " +
                        "WHERE name IN ('matched_romanized_lyric', 'original_romanized_lyric')"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion17ToVersion18KeepsExistingRows() {
        helper.createDatabase(TEST_DATABASE_VERSION_17_NAME, 17).apply {
            execSQL(
                """
                INSERT INTO downloaded_song_catalog (
                  catalog_key, root_key, display_position, id, name, artist, album,
                  file_path, file_size, download_time, matched_romanized_lyric,
                  original_romanized_lyric, user_lyric_offset_ms, duration_ms
                ) VALUES ('file:song.flac', 'root', 0, 1, 'Song', 'Artist', 'Album',
                  '/song.flac', 1, 10, 'roma', 'original roma', 0, 1000)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_17_NAME,
            18,
            true,
            NeriUserDataDatabase.MIGRATION_17_18
        )

        try {
            assertEquals(
                "roma",
                migrated.stringFor(
                    "SELECT matched_romanized_lyric FROM downloaded_song_catalog " +
                        "WHERE catalog_key = 'file:song.flac'"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion18ToVersion19RepairsLegacyRomanizedSchema() {
        helper.createDatabase(TEST_DATABASE_VERSION_18_NAME, 18).apply {
            execSQL(
                """
                INSERT INTO downloaded_song_catalog (
                  catalog_key, root_key, display_position, id, name, artist, album,
                  file_path, file_size, download_time, user_lyric_offset_ms, duration_ms
                ) VALUES ('file:song.flac', 'root', 0, 1, 'Song', 'Artist', 'Album',
                  '/song.flac', 1, 10, 0, 1000)
                """.trimIndent()
            )
            execSQL(
                "UPDATE room_master_table SET identity_hash = '3d734fb4f12dd32bbd4876b9556f8147'"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_18_NAME,
            19,
            true,
            NeriUserDataDatabase.MIGRATION_18_19
        )

        try {
            assertEquals(
                2L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info('downloaded_song_catalog') " +
                        "WHERE name IN ('matched_romanized_lyric', 'original_romanized_lyric')"
                )
            )
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM downloaded_song_catalog " +
                        "WHERE catalog_key = 'file:song.flac'"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion19WithLegacyIdentityHashToVersion20() {
        helper.createDatabase(TEST_DATABASE_VERSION_19_NAME, 19).apply {
            execSQL(
                "UPDATE room_master_table SET identity_hash = " +
                    "'3d734fb4f12dd32bbd4876b9556f8147'"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_19_NAME,
            20,
            true,
            NeriUserDataDatabase.MIGRATION_19_20
        )

        try {
            assertEquals(
                2L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info('downloaded_song_catalog') " +
                        "WHERE name IN ('matched_romanized_lyric', " +
                        "'original_romanized_lyric')"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion20WithLegacyIdentityHashToVersion21() {
        helper.createDatabase(TEST_DATABASE_VERSION_20_NAME, 20).apply {
            execSQL(
                "UPDATE room_master_table SET identity_hash = " +
                    "'3d734fb4f12dd32bbd4876b9556f8147'"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_20_NAME,
            21,
            true,
            NeriUserDataDatabase.MIGRATION_20_21
        )

        try {
            assertEquals(
                21L,
                migrated.longFor("PRAGMA user_version")
            )
            assertEquals(
                "361bf3ee3aec4a5d3d2059cab1c7f9f3",
                migrated.stringFor("SELECT identity_hash FROM room_master_table")
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion21WithLegacyIdentityHashToVersion22() {
        helper.createDatabase(TEST_DATABASE_VERSION_21_NAME, 21).apply {
            execSQL(
                "UPDATE room_master_table SET identity_hash = " +
                    "'3d734fb4f12dd32bbd4876b9556f8147'"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_21_NAME,
            22,
            true,
            NeriUserDataDatabase.MIGRATION_21_22
        )

        try {
            assertEquals(
                22L,
                migrated.longFor("PRAGMA user_version")
            )
            assertEquals(
                "361bf3ee3aec4a5d3d2059cab1c7f9f3",
                migrated.stringFor("SELECT identity_hash FROM room_master_table")
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion22WithLegacyIdentityHashToVersion23() {
        helper.createDatabase(TEST_DATABASE_VERSION_22_NAME, 22).apply {
            execSQL(
                "UPDATE room_master_table SET identity_hash = " +
                    "'3d734fb4f12dd32bbd4876b9556f8147'"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_22_NAME,
            23,
            true,
            NeriUserDataDatabase.MIGRATION_22_23
        )

        try {
            assertEquals(
                23L,
                migrated.longFor("PRAGMA user_version")
            )
            assertEquals(
                "361bf3ee3aec4a5d3d2059cab1c7f9f3",
                migrated.stringFor("SELECT identity_hash FROM room_master_table")
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion23AddsDownloadedRemoteAlbumColumn() {
        helper.createDatabase(TEST_DATABASE_VERSION_23_NAME, 23).close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_23_NAME,
            24,
            true,
            NeriUserDataDatabase.MIGRATION_23_24
        )

        try {
            assertEquals(24L, migrated.longFor("PRAGMA user_version"))
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info(" +
                        "'download_snapshot_metadata') WHERE name = 'album'"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion25ToVersion26CreatesManagedDownloadArtifactTable() {
        helper.createDatabase(TEST_DATABASE_VERSION_25_NAME, 25).close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_25_NAME,
            26,
            true,
            NeriUserDataDatabase.MIGRATION_25_26
        )

        try {
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'managed_download_artifact'"
                )
            )
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_table_info('managed_download_artifact') " +
                        "WHERE name = 'stable_key'"
                )
            )
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM pragma_index_list('managed_download_artifact') " +
                        "WHERE name = 'index_managed_download_artifact_artifact_id'"
                )
            )
        } finally {
            migrated.close()
        }
    }

    private fun SupportSQLiteDatabase.insertVersion1LocalPlaylistFixture() {
        val songPayload = sqlText(
            """
            {
              "id": 9001,
              "name": "旧Room歌曲",
              "artist": "artist",
              "album": "NeteaseAlbum",
              "albumId": 7,
              "durationMs": 180000,
              "coverUrl": null,
              "channelId": "netease",
              "audioId": "9001",
              "addedAt": 1700000000000
            }
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO local_playlist (
              playlist_id, name, display_position, custom_cover_url, modified_at,
              song_order_version, is_system
            ) VALUES (
              601, ${sqlText("旧Room歌单")}, 0, NULL, 1700000000000, 1, 0
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO track (
              identity_key, identity_id, identity_album, identity_media_uri,
              song_id, name, artist, album, album_id, duration_ms, cover_url,
              media_uri, channel_id, audio_id, sub_audio_id, source_stable_key,
              local_file_name, local_file_path, payload_schema_version,
              durable_payload_json
            ) VALUES (
              '9001|NeteaseAlbum|', 9001, 'NeteaseAlbum', NULL,
              9001, ${sqlText("旧Room歌曲")}, 'artist', 'NeteaseAlbum',
              7, 180000, NULL, NULL, 'netease', '9001', NULL, NULL,
              NULL, NULL, 1, $songPayload
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO playlist_member (
              playlist_id, identity_key, display_position, added_at,
              order_tie_break, playlist_context_id, member_payload_schema_version,
              member_payload_json
            ) VALUES (
              601, '9001|NeteaseAlbum|', 0, 1700000000000, 0,
              NULL, 1, $songPayload
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO playlist_member_token (
              playlist_id, identity_key, device_id, counter, token_index
            ) VALUES (
              601, '9001|NeteaseAlbum|', 'old-device', 42, 0
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO migration_metadata (
              key, value, updated_at
            ) VALUES (
              'local_playlist_cutover_state', 'room_primary', 1700000000000
            )
            """.trimIndent()
        )
    }

    private fun SupportSQLiteDatabase.longFor(query: String): Long {
        return this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    private fun SupportSQLiteDatabase.stringFor(query: String): String {
        return this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
    }

    private fun sqlText(value: String): String {
        return "'${value.replace("'", "''")}'"
    }

    private companion object {
        const val TEST_DATABASE_NAME = "neri-user-data-migration-test"
        const val TEST_DATABASE_WITH_DATA_NAME = "neri-user-data-migration-with-data-test"
        const val TEST_DATABASE_VERSION_14_NAME = "neri-user-data-migration-v14-test"
        const val TEST_DATABASE_VERSION_15_NAME = "neri-user-data-migration-v15-test"
        const val TEST_DATABASE_VERSION_16_NAME = "neri-user-data-migration-v16-test"
        const val TEST_DATABASE_VERSION_17_NAME = "neri-user-data-migration-v17-test"
        const val TEST_DATABASE_VERSION_18_NAME = "neri-user-data-migration-v18-test"
        const val TEST_DATABASE_VERSION_19_NAME = "neri-user-data-migration-v19-test"
        const val TEST_DATABASE_VERSION_20_NAME = "neri-user-data-migration-v20-test"
        const val TEST_DATABASE_VERSION_21_NAME = "neri-user-data-migration-v21-test"
        const val TEST_DATABASE_VERSION_22_NAME = "neri-user-data-migration-v22-test"
        const val TEST_DATABASE_VERSION_23_NAME = "neri-user-data-migration-v23-test"
        const val TEST_DATABASE_VERSION_25_NAME = "neri-user-data-migration-v25-test"
    }
}
