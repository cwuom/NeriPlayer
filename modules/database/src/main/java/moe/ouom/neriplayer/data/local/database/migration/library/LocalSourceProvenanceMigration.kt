package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object LocalSourceProvenanceMigration : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `play_history` ADD COLUMN `explicit_local_source_reference` TEXT")
        db.execSQL("ALTER TABLE `playback_queue_song` ADD COLUMN `explicit_local_source_reference` TEXT")
    }
}
