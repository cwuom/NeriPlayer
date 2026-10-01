package moe.ouom.neriplayer.data.local.database.migration.platform

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PlatformPlaylistCacheMigration : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createPlatformPlaylistCacheTables(db)
    }
}

private fun createPlatformPlaylistCacheTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `platform_playlist_cache` (
            `platform` TEXT NOT NULL,
            `cache_key` TEXT NOT NULL,
            `source_id` INTEGER,
            `alternate_key` TEXT,
            `kind` TEXT,
            `title` TEXT,
            `subtitle` TEXT,
            `creator_name` TEXT,
            `cover_url` TEXT,
            `play_count` INTEGER,
            `track_count` INTEGER NOT NULL,
            `total_count` INTEGER NOT NULL,
            `signature_primary` TEXT,
            `signature_secondary` TEXT,
            `has_more` INTEGER,
            `saved_at_ms` INTEGER NOT NULL,
            PRIMARY KEY(`platform`, `cache_key`)
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
        `index_platform_playlist_cache_source_id`
        ON `platform_playlist_cache` (`platform`, `source_id`)
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
        `index_platform_playlist_cache_saved_at`
        ON `platform_playlist_cache` (`platform`, `saved_at_ms`)
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `platform_playlist_cache_track` (
            `platform` TEXT NOT NULL,
            `cache_key` TEXT NOT NULL,
            `position` INTEGER NOT NULL,
            `item_id` INTEGER,
            `item_key` TEXT,
            `name` TEXT NOT NULL,
            `artist` TEXT NOT NULL,
            `album` TEXT NOT NULL,
            `album_id` INTEGER,
            `duration_ms` INTEGER NOT NULL,
            `cover_url` TEXT,
            `audio_id` TEXT,
            `uploader_mid` INTEGER,
            `added_at` INTEGER NOT NULL,
            PRIMARY KEY(`platform`, `cache_key`, `position`),
            FOREIGN KEY(`platform`, `cache_key`)
            REFERENCES `platform_playlist_cache`(`platform`, `cache_key`)
            ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
        `index_platform_playlist_cache_track_item_id`
        ON `platform_playlist_cache_track`
        (`platform`, `cache_key`, `item_id`)
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
        `index_platform_playlist_cache_track_item_key`
        ON `platform_playlist_cache_track`
        (`platform`, `cache_key`, `item_key`)
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `platform_playlist_cache_track_artist` (
            `platform` TEXT NOT NULL,
            `cache_key` TEXT NOT NULL,
            `track_position` INTEGER NOT NULL,
            `artist_position` INTEGER NOT NULL,
            `artist_id` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            PRIMARY KEY(
                `platform`,
                `cache_key`,
                `track_position`,
                `artist_position`
            ),
            FOREIGN KEY(`platform`, `cache_key`, `track_position`)
            REFERENCES `platform_playlist_cache_track`(
                `platform`,
                `cache_key`,
                `position`
            )
            ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
        `index_platform_playlist_cache_artist_track`
        ON `platform_playlist_cache_track_artist`
        (`platform`, `cache_key`, `track_position`)
        """.trimIndent()
    )
}
