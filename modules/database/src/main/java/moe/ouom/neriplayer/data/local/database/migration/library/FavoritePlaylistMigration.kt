package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object FavoritePlaylistMigration : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorite_playlist` (
                `playlist_id` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `cover_url` TEXT,
                `track_count` INTEGER NOT NULL,
                `browse_id` TEXT,
                `remote_playlist_id` TEXT,
                `subtitle` TEXT,
                `added_time` INTEGER NOT NULL,
                `sort_order` INTEGER NOT NULL,
                `modified_at` INTEGER NOT NULL,
                `is_deleted` INTEGER NOT NULL,
                PRIMARY KEY(`playlist_id`, `source`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_favorite_playlist_sort`
            ON `favorite_playlist` (`sort_order` DESC, `modified_at` DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_favorite_playlist_visibility`
            ON `favorite_playlist` (`is_deleted` ASC, `sort_order` DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorite_playlist_song` (
                `playlist_id` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `display_position` INTEGER NOT NULL,
                `song_payload_json` TEXT NOT NULL,
                PRIMARY KEY(`playlist_id`, `source`, `display_position`),
                FOREIGN KEY(`playlist_id`, `source`)
                    REFERENCES `favorite_playlist`(`playlist_id`, `source`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_favorite_playlist_song_order`
            ON `favorite_playlist_song`
            (`playlist_id` ASC, `source` ASC, `display_position` ASC)
            """.trimIndent()
        )
    }
}
