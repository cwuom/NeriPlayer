package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PlaybackStatHourMigration : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playback_stats_pending_delta ADD COLUMN played_zone TEXT")
        db.execSQL(
            "CREATE TABLE playback_stat_hour (hour_start_at INTEGER NOT NULL, identity_key TEXT NOT NULL, " +
                "local_hour INTEGER NOT NULL, listened_ms INTEGER NOT NULL, epoch_started_at INTEGER NOT NULL, " +
                "PRIMARY KEY(hour_start_at, identity_key, local_hour))"
        )
        db.execSQL("CREATE INDEX index_playback_stat_hour_identity_key ON playback_stat_hour(identity_key)")
    }
}
