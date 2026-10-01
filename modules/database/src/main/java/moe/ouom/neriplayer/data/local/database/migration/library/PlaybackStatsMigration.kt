package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PlaybackStatsMigration : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_stat` (
                `identity_key` TEXT NOT NULL,
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `album_id` INTEGER NOT NULL,
                `cover_url` TEXT,
                `duration_ms` INTEGER NOT NULL,
                `total_listen_ms` INTEGER NOT NULL,
                `play_count` INTEGER NOT NULL,
                `last_played_at` INTEGER NOT NULL,
                `first_played_at` INTEGER NOT NULL,
                `media_uri` TEXT,
                `local_file_path` TEXT,
                `local_file_name` TEXT,
                `custom_name` TEXT,
                `custom_artist` TEXT,
                `custom_cover_url` TEXT,
                PRIMARY KEY(`identity_key`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playback_stat_last_played`
            ON `playback_stat` (`last_played_at` DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playback_stat_media_uri`
            ON `playback_stat` (`media_uri`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_stat_bucket` (
                `day_start_at` INTEGER NOT NULL,
                `identity_key` TEXT NOT NULL,
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `album_id` INTEGER NOT NULL,
                `cover_url` TEXT,
                `duration_ms` INTEGER NOT NULL,
                `total_listen_ms` INTEGER NOT NULL,
                `play_count` INTEGER NOT NULL,
                `last_played_at` INTEGER NOT NULL,
                `first_played_at` INTEGER NOT NULL,
                `media_uri` TEXT,
                `local_file_path` TEXT,
                `local_file_name` TEXT,
                `custom_name` TEXT,
                `custom_artist` TEXT,
                `custom_cover_url` TEXT,
                PRIMARY KEY(`day_start_at`, `identity_key`),
                FOREIGN KEY(`identity_key`) REFERENCES
                    `playback_stat`(`identity_key`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playback_stat_bucket_day`
            ON `playback_stat_bucket`
            (`day_start_at` DESC, `identity_key` ASC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playback_stat_bucket_identity`
            ON `playback_stat_bucket` (`identity_key`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_stat_counter_shard` (
                `identity_key` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `epoch_started_at` INTEGER NOT NULL,
                `total_listen_ms` INTEGER NOT NULL,
                `play_count` INTEGER NOT NULL,
                `first_played_at` INTEGER NOT NULL,
                `last_played_at` INTEGER NOT NULL,
                PRIMARY KEY(
                    `identity_key`,
                    `device_id`,
                    `epoch_started_at`
                ),
                FOREIGN KEY(`identity_key`) REFERENCES
                    `playback_stat`(`identity_key`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playback_stat_counter_identity`
            ON `playback_stat_counter_shard` (`identity_key`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS
            `playback_stat_daily_counter_shard` (
                `day_start_at` INTEGER NOT NULL,
                `identity_key` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `epoch_started_at` INTEGER NOT NULL,
                `total_listen_ms` INTEGER NOT NULL,
                `play_count` INTEGER NOT NULL,
                `first_played_at` INTEGER NOT NULL,
                `last_played_at` INTEGER NOT NULL,
                PRIMARY KEY(
                    `day_start_at`,
                    `identity_key`,
                    `device_id`,
                    `epoch_started_at`
                ),
                FOREIGN KEY(`day_start_at`, `identity_key`)
                    REFERENCES `playback_stat_bucket`
                    (`day_start_at`, `identity_key`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playback_stat_daily_counter_scope`
            ON `playback_stat_daily_counter_shard`
            (`day_start_at`, `identity_key`)
            """.trimIndent()
        )
    }
}
