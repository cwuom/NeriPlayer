package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object LyricSyncPersistenceMigration : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `play_history` ADD COLUMN `lyric_sync_payload_json` TEXT")
        db.execSQL("ALTER TABLE `playback_queue_song` ADD COLUMN `lyric_sync_payload_json` TEXT")
    }
}
