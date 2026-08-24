package moe.ouom.neriplayer.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun migrateFromVersion15ToVersion16CopiesPayloadAndDropsLegacyTables() {
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
            false,
            NeriUserDataDatabase.MIGRATION_15_FINAL
        )

        try {
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'download_operation'"
                )
            )
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'managed_library_item'"
                )
            )
            listOf(
                "download_pending_queue",
                "download_cancelled_key",
                "downloaded_song_catalog",
                "download_snapshot_entry",
                "download_snapshot_metadata",
                "managed_download_artifact"
            ).forEach { tableName ->
                assertEquals(
                    0L,
                    migrated.longFor(
                        "SELECT COUNT(*) FROM sqlite_master " +
                            "WHERE type = 'table' AND name = '$tableName'"
                    )
                )
            }
            assertEquals(
                0L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = 'file:song.flac'"
                )
            )
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '1|__local_files__|/song.flac'"
                )
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion15ToVersion16PreservesSnapshotEntriesInPayload() {
        helper.createDatabase(TEST_DATABASE_VERSION_15_SNAPSHOT_ENTRY_NAME, 15).apply {
            insertVersion15CatalogRow(
                catalogKey = "file:/song.flac",
                rootKey = "root",
                stableKey = "9|netease|",
                filePath = "/song.flac",
                fileSize = 42L
            )
            execSQL(
                """
                INSERT INTO download_snapshot_entry (
                  root_key, bucket, entry_key, display_position, name, reference,
                  media_uri, local_file_path, size_bytes, last_modified_ms, is_directory
                ) VALUES (
                  'root', 'audio', 'song.flac', 0, 'song.flac', '/song.flac',
                  'file:///song.flac', '/song.flac', 42, 10, 0
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_15_SNAPSHOT_ENTRY_NAME,
            16,
            false,
            NeriUserDataDatabase.MIGRATION_15_FINAL
        )

        try {
            val payload = JSONObject(
                migrated.stringFor(
                    "SELECT payload_json FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '9|netease|'"
                )
            )
            val entries = payload.getJSONArray("download_snapshot_entries")
            assertEquals(1, entries.length())
            assertEquals("song.flac", entries.getJSONObject(0).getString("name"))
            assertEquals("/song.flac", entries.getJSONObject(0).getString("reference"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrationPayloadCanBeDroppedBeforeRoomReopens() {
        helper.createDatabase(TEST_DATABASE_VERSION_15_DROP_NAME, 15).close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_15_DROP_NAME,
            16,
            false,
            NeriUserDataDatabase.MIGRATION_15_FINAL
        )
        try {
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = " +
                        "'legacy_download_upgrade_payload'"
                )
            )
            listOf(
                "download_pending_queue",
                "download_cancelled_key",
                "downloaded_song_catalog",
                "download_snapshot_entry",
                "download_snapshot_metadata",
                "managed_download_artifact"
            ).forEach { tableName ->
                assertEquals(
                    0L,
                    migrated.longFor(
                        "SELECT COUNT(*) FROM sqlite_master " +
                            "WHERE type = 'table' AND name = '$tableName'"
                    )
                )
            }
            migrated.execSQL("DROP TABLE legacy_download_upgrade_payload")
        } finally {
            migrated.close()
        }

        val reopened = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_15_DROP_NAME,
            16,
            true
        )
        try {
            assertEquals(
                0L,
                reopened.longFor(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = " +
                        "'legacy_download_upgrade_payload'"
                )
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun migrateFromVersion15ToVersion16RetainsDistinctBytesAsConflict() {
        helper.createDatabase(TEST_DATABASE_VERSION_15_CONFLICT_NAME, 15).apply {
            execSQL("ALTER TABLE downloaded_song_catalog ADD COLUMN content_hash TEXT")
            insertVersion15CatalogRow(
                catalogKey = "file:/first.flac",
                rootKey = "root-a",
                stableKey = "7|netease|",
                filePath = "/first.flac",
                fileSize = 10L
            )
            insertVersion15CatalogRow(
                catalogKey = "file:/second.flac",
                rootKey = "root-b",
                stableKey = "7|netease|",
                filePath = "/second.flac",
                fileSize = 20L
            )
            execSQL(
                "UPDATE downloaded_song_catalog SET content_hash = " +
                    "CASE catalog_key WHEN 'file:/first.flac' THEN 'sha256:first' " +
                    "ELSE 'sha256:second' END"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_15_CONFLICT_NAME,
            16,
            false,
            NeriUserDataDatabase.MIGRATION_15_FINAL
        )

        try {
            val payload = JSONObject(
                migrated.stringFor(
                    "SELECT payload_json FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '7|netease|'"
                )
            )
            val conflicts = payload.getJSONArray("legacyConflicts")
            assertEquals(1, conflicts.length())
            assertEquals(
                "SAME_STABLE_KEY_DIFFERENT_BYTES",
                conflicts.getJSONObject(0).getString("reason")
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion15ToVersion16ReusesSameStableKeyAndBytes() {
        helper.createDatabase(TEST_DATABASE_VERSION_15_DUPLICATE_NAME, 15).apply {
            execSQL("ALTER TABLE downloaded_song_catalog ADD COLUMN content_hash TEXT")
            insertVersion15CatalogRow(
                catalogKey = "file:/same-a.flac",
                rootKey = "root-a",
                stableKey = "8|netease|",
                filePath = "/same.flac",
                fileSize = 10L
            )
            insertVersion15CatalogRow(
                catalogKey = "file:/same-b.flac",
                rootKey = "root-b",
                stableKey = "8|netease|",
                filePath = "/same.flac",
                fileSize = 10L
            )
            execSQL(
                "UPDATE downloaded_song_catalog SET content_hash = 'sha256:same'"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_15_DUPLICATE_NAME,
            16,
            false,
            NeriUserDataDatabase.MIGRATION_15_FINAL
        )

        try {
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '8|netease|'"
                )
            )
            val payload = JSONObject(
                migrated.stringFor(
                    "SELECT payload_json FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '8|netease|'"
                )
            )
            assertFalse(payload.has("legacyConflicts"))
            assertTrue(payload.getJSONObject("downloaded_song_catalog").has("file_path"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion15ToVersion16DoesNotAssumeSameBytesWithoutHash() {
        helper.createDatabase(TEST_DATABASE_VERSION_15_UNVERIFIED_NAME, 15).apply {
            insertVersion15CatalogRow(
                catalogKey = "file:/same-a.flac",
                rootKey = "root-a",
                stableKey = "10|netease|",
                filePath = "/same.flac",
                fileSize = 10L
            )
            insertVersion15CatalogRow(
                catalogKey = "file:/same-b.flac",
                rootKey = "root-b",
                stableKey = "10|netease|",
                filePath = "/same.flac",
                fileSize = 10L
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_15_UNVERIFIED_NAME,
            16,
            false,
            NeriUserDataDatabase.MIGRATION_15_FINAL
        )

        try {
            val payload = JSONObject(
                migrated.stringFor(
                    "SELECT payload_json FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '10|netease|'"
                )
            )
            assertEquals(
                "SAME_STABLE_KEY_BYTES_UNVERIFIED",
                payload.getJSONArray("legacyConflicts")
                    .getJSONObject(0)
                    .getString("reason")
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromVersion15ToVersion16RetainsUnmatchedMetadataRow() {
        helper.createDatabase(TEST_DATABASE_VERSION_15_ORPHAN_METADATA_NAME, 15).apply {
            execSQL(
                """
                INSERT INTO download_snapshot_metadata (
                  root_key, audio_name, user_lyric_offset_ms, duration_ms, name
                ) VALUES ('root', 'orphan.flac', 0, 1000, 'Orphan')
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_VERSION_15_ORPHAN_METADATA_NAME,
            16,
            false,
            NeriUserDataDatabase.MIGRATION_15_FINAL
        )

        try {
            assertEquals(
                1L,
                migrated.longFor(
                    "SELECT COUNT(*) FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key LIKE 'legacy:download_snapshot_metadata:%'"
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

    private fun SupportSQLiteDatabase.insertVersion15CatalogRow(
        catalogKey: String,
        rootKey: String,
        stableKey: String,
        filePath: String,
        fileSize: Long
    ) {
        execSQL(
            """
            INSERT INTO downloaded_song_catalog (
              catalog_key, root_key, display_position, id, name, artist, album,
              file_path, file_size, download_time, stable_key,
              user_lyric_offset_ms, duration_ms
            ) VALUES (
              ${sqlText(catalogKey)}, ${sqlText(rootKey)}, 0, 7, 'Song', 'Artist',
              'Album', ${sqlText(filePath)}, $fileSize, 10, ${sqlText(stableKey)},
              0, 1000
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
        const val TEST_DATABASE_VERSION_15_DROP_NAME = "neri-user-data-migration-v15-drop-test"
        const val TEST_DATABASE_VERSION_15_CONFLICT_NAME =
            "neri-user-data-migration-v15-conflict-test"
        const val TEST_DATABASE_VERSION_15_DUPLICATE_NAME =
            "neri-user-data-migration-v15-duplicate-test"
        const val TEST_DATABASE_VERSION_15_SNAPSHOT_ENTRY_NAME =
            "neri-user-data-migration-v15-snapshot-entry-test"
        const val TEST_DATABASE_VERSION_15_UNVERIFIED_NAME =
            "neri-user-data-migration-v15-unverified-test"
        const val TEST_DATABASE_VERSION_15_ORPHAN_METADATA_NAME =
            "neri-user-data-migration-v15-orphan-metadata-test"
    }
}
