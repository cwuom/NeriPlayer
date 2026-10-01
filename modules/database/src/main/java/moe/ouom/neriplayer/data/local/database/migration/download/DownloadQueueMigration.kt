package moe.ouom.neriplayer.data.local.database.migration.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DownloadQueueMigration : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_pending_queue` (
                `stable_key` TEXT NOT NULL,
                `queue_order` INTEGER NOT NULL,
                `queued_at_ms` INTEGER NOT NULL,
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `album_id` INTEGER NOT NULL,
                `duration_ms` INTEGER NOT NULL,
                `cover_url` TEXT,
                `media_uri` TEXT,
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
                `local_file_name` TEXT,
                `local_file_path` TEXT,
                `channel_id` TEXT,
                `audio_id` TEXT,
                `sub_audio_id` TEXT,
                `playlist_context_id` TEXT,
                `source_stable_key` TEXT,
                `stream_url` TEXT,
                PRIMARY KEY(`stable_key`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_download_pending_queue_order`
            ON `download_pending_queue`
            (`queue_order` ASC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_cancelled_key` (
                `stable_key` TEXT NOT NULL,
                `cancelled_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`stable_key`)
            )
            """.trimIndent()
        )
    }
}
