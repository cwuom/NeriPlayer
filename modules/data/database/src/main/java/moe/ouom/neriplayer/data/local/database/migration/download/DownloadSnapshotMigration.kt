package moe.ouom.neriplayer.data.local.database.migration.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DownloadSnapshotMigration : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_snapshot_entry` (
                `root_key` TEXT NOT NULL,
                `bucket` TEXT NOT NULL,
                `entry_key` TEXT NOT NULL,
                `display_position` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `reference` TEXT NOT NULL,
                `media_uri` TEXT NOT NULL,
                `local_file_path` TEXT,
                `size_bytes` INTEGER NOT NULL,
                `last_modified_ms` INTEGER NOT NULL,
                `is_directory` INTEGER NOT NULL,
                PRIMARY KEY(`root_key`, `bucket`, `entry_key`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_download_snapshot_entry_root_bucket_position`
            ON `download_snapshot_entry`
            (`root_key` ASC, `bucket` ASC, `display_position` ASC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_download_snapshot_entry_root_bucket_name`
            ON `download_snapshot_entry`
            (`root_key`, `bucket`, `name`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_download_snapshot_entry_root_reference`
            ON `download_snapshot_entry` (`root_key`, `reference`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_snapshot_metadata` (
                `root_key` TEXT NOT NULL,
                `audio_name` TEXT NOT NULL,
                `stable_key` TEXT,
                `song_id` INTEGER,
                `identity_album` TEXT,
                `name` TEXT,
                `artist` TEXT,
                `cover_url` TEXT,
                `matched_lyric` TEXT,
                `matched_translated_lyric` TEXT,
                `matched_lyric_source` TEXT,
                `matched_song_id` TEXT,
                `user_lyric_offset_ms` INTEGER NOT NULL,
                `custom_cover_url` TEXT,
                `custom_name` TEXT,
                `custom_artist` TEXT,
                `original_name` TEXT,
                `original_artist` TEXT,
                `original_cover_url` TEXT,
                `original_lyric` TEXT,
                `original_translated_lyric` TEXT,
                `media_uri` TEXT,
                `channel_id` TEXT,
                `audio_id` TEXT,
                `sub_audio_id` TEXT,
                `playlist_context_id` TEXT,
                `cover_path` TEXT,
                `lyric_path` TEXT,
                `translated_lyric_path` TEXT,
                `duration_ms` INTEGER NOT NULL,
                `download_finalized` INTEGER,
                PRIMARY KEY(`root_key`, `audio_name`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_download_snapshot_metadata_root_stable_key`
            ON `download_snapshot_metadata` (`root_key`, `stable_key`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_download_snapshot_metadata_root_song_id`
            ON `download_snapshot_metadata` (`root_key`, `song_id`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_download_snapshot_metadata_root_media_uri`
            ON `download_snapshot_metadata` (`root_key`, `media_uri`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_download_snapshot_metadata_root_remote_track`
            ON `download_snapshot_metadata`
            (`root_key`, `channel_id`, `audio_id`, `sub_audio_id`)
            """.trimIndent()
        )
    }
}
