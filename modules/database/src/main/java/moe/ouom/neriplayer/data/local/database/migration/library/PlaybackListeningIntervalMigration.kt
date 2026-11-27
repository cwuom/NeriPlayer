package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PlaybackListeningIntervalMigration : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE managed_library_item ADD COLUMN youtube_content_label TEXT")
        // 旧桶只有午夜时间戳，无法据此确定录制地日期
        for (table in listOf("playback_stat_bucket", "playback_stat_snapshot_bucket",
                "playback_stat_daily_counter_shard", "playback_stat_snapshot_daily_counter")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN recorded_local_date TEXT")
        }
        db.execSQL(
            "CREATE TABLE playback_listening_interval (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "identity_key TEXT NOT NULL, start_at INTEGER NOT NULL, end_at INTEGER NOT NULL, " +
                "local_hour INTEGER NOT NULL, epoch_started_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX index_playback_listening_interval_start_at ON playback_listening_interval(start_at)")
        db.execSQL("CREATE INDEX index_playback_listening_interval_identity_key_end_at " +
            "ON playback_listening_interval(identity_key, end_at)")
    }
}
