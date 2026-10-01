package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PlaylistUsageMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlist_usage` (
                `usage_key` TEXT NOT NULL,
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `pic_url` TEXT,
                `track_count` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `last_opened` INTEGER NOT NULL,
                `open_count` INTEGER NOT NULL,
                `first_opened` INTEGER NOT NULL,
                `counter_base_open_count` INTEGER NOT NULL,
                `fid` INTEGER,
                `mid` INTEGER,
                `browse_id` TEXT,
                `playlist_id` TEXT,
                `subtype` TEXT,
                `subtitle` TEXT,
                PRIMARY KEY(`usage_key`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playlist_usage_last_opened`
            ON `playlist_usage` (`last_opened` DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playlist_usage_source_id`
            ON `playlist_usage` (`source`, `playlist_id`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlist_usage_counter_shard` (
                `usage_key` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `epoch_started_at` INTEGER NOT NULL,
                `play_count` INTEGER NOT NULL,
                `first_played_at` INTEGER NOT NULL,
                `last_played_at` INTEGER NOT NULL,
                PRIMARY KEY(`usage_key`, `device_id`, `epoch_started_at`),
                FOREIGN KEY(`usage_key`) REFERENCES `playlist_usage`(`usage_key`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_playlist_usage_counter_usage_key`
            ON `playlist_usage_counter_shard` (`usage_key`)
            """.trimIndent()
        )
    }
}
