package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TrafficStatsMigration : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `traffic_stats_bucket` (
                `day_start_at` INTEGER NOT NULL,
                `wifi_bytes` INTEGER NOT NULL,
                `mobile_bytes` INTEGER NOT NULL,
                `roaming_bytes` INTEGER NOT NULL,
                `playback_network_bytes` INTEGER NOT NULL,
                `download_network_bytes` INTEGER NOT NULL,
                `cache_hit_bytes` INTEGER NOT NULL,
                `request_count` INTEGER NOT NULL,
                `cache_hit_count` INTEGER NOT NULL,
                PRIMARY KEY(`day_start_at`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_traffic_stats_bucket_day`
            ON `traffic_stats_bucket` (`day_start_at` DESC)
            """.trimIndent()
        )
    }
}
