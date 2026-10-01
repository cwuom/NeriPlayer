package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object LocalPlaylistPlaybackMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_playlist_playback_stat` (
                `playlist_id` INTEGER NOT NULL,
                `total_play_count` INTEGER NOT NULL,
                `first_played_at` INTEGER NOT NULL,
                `last_played_at` INTEGER NOT NULL,
                `counter_base_play_count` INTEGER NOT NULL,
                PRIMARY KEY(`playlist_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_local_playlist_playback_stat_last_played`
            ON `local_playlist_playback_stat` (`last_played_at` DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_playlist_playback_bucket` (
                `playlist_id` INTEGER NOT NULL,
                `day_start_at` INTEGER NOT NULL,
                `play_count` INTEGER NOT NULL,
                `first_played_at` INTEGER NOT NULL,
                `last_played_at` INTEGER NOT NULL,
                `counter_base_play_count` INTEGER NOT NULL,
                PRIMARY KEY(`playlist_id`, `day_start_at`),
                FOREIGN KEY(`playlist_id`) REFERENCES
                    `local_playlist_playback_stat`(`playlist_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_local_playlist_playback_bucket_day`
            ON `local_playlist_playback_bucket`
            (`playlist_id` ASC, `day_start_at` ASC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_playlist_playback_counter_shard` (
                `playlist_id` INTEGER NOT NULL,
                `day_start_at` INTEGER NOT NULL,
                `device_id` TEXT NOT NULL,
                `epoch_started_at` INTEGER NOT NULL,
                `play_count` INTEGER NOT NULL,
                `first_played_at` INTEGER NOT NULL,
                `last_played_at` INTEGER NOT NULL,
                PRIMARY KEY(
                    `playlist_id`,
                    `day_start_at`,
                    `device_id`,
                    `epoch_started_at`
                ),
                FOREIGN KEY(`playlist_id`) REFERENCES
                    `local_playlist_playback_stat`(`playlist_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_local_playlist_playback_counter_scope`
            ON `local_playlist_playback_counter_shard`
            (`playlist_id`, `day_start_at`)
            """.trimIndent()
        )
    }
}
