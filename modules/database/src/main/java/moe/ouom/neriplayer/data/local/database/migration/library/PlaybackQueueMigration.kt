package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PlaybackQueueMigration : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_queue_state` (
                `id` INTEGER NOT NULL,
                `current_index` INTEGER NOT NULL,
                `media_url` TEXT,
                `position_ms` INTEGER NOT NULL,
                `should_resume_playback` INTEGER NOT NULL,
                `repeat_mode` INTEGER,
                `shuffle_enabled` INTEGER,
                `shuffle_restore_index` INTEGER,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_queue_song` (
                `queue_id` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
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
                `stream_url` TEXT,
                PRIMARY KEY(`queue_id`, `position`)
            )
            """.trimIndent()
        )
    }
}
