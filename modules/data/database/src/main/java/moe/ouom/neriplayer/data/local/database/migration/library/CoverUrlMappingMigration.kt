package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object CoverUrlMappingMigration : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cover_url_mapping` (
                `local_url` TEXT NOT NULL,
                `network_url` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`local_url`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_cover_url_mapping_updated_at`
            ON `cover_url_mapping` (`updated_at` DESC)
            """.trimIndent()
        )
    }
}
