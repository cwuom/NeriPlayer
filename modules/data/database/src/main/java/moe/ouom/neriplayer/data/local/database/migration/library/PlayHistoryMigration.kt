package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PlayHistoryMigration : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `play_history` (
                `identity_key` TEXT NOT NULL,
                `identity_id` INTEGER NOT NULL,
                `identity_album` TEXT NOT NULL,
                `identity_media_uri` TEXT,
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `album_id` INTEGER NOT NULL,
                `duration_ms` INTEGER NOT NULL,
                `resume_position_ms` INTEGER NOT NULL,
                `cover_url` TEXT,
                `media_uri` TEXT,
                `matched_lyric` TEXT,
                `matched_translated_lyric` TEXT,
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
                `source_stable_key` TEXT,
                `played_at` INTEGER NOT NULL,
                PRIMARY KEY(`identity_key`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_play_history_played_at`
            ON `play_history` (`played_at` DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_play_history_identity_parts`
            ON `play_history`
            (`identity_id`, `identity_album`, `identity_media_uri`)
            """.trimIndent()
        )
    }
}
