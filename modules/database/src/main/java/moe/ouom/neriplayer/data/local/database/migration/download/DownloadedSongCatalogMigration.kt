package moe.ouom.neriplayer.data.local.database.migration.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DownloadedSongCatalogMigration : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `downloaded_song_catalog` (
                `catalog_key` TEXT NOT NULL,
                `root_key` TEXT NOT NULL,
                `display_position` INTEGER NOT NULL,
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `file_path` TEXT NOT NULL,
                `file_size` INTEGER NOT NULL,
                `download_time` INTEGER NOT NULL,
                `cover_path` TEXT,
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
                `duration_ms` INTEGER NOT NULL,
                `stable_key` TEXT,
                `source_identity_album` TEXT,
                `source_media_uri` TEXT,
                `source_channel_id` TEXT,
                `source_audio_id` TEXT,
                `source_sub_audio_id` TEXT,
                `source_playlist_context_id` TEXT,
                PRIMARY KEY(`catalog_key`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_downloaded_song_catalog_root_position`
            ON `downloaded_song_catalog`
            (`root_key` ASC, `display_position` ASC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_downloaded_song_catalog_file_path`
            ON `downloaded_song_catalog` (`file_path`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_downloaded_song_catalog_media_uri`
            ON `downloaded_song_catalog` (`media_uri`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_downloaded_song_catalog_stable_key`
            ON `downloaded_song_catalog` (`stable_key`)
            """.trimIndent()
        )
    }
}
