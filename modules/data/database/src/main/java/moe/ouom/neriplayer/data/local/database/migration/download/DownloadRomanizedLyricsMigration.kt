package moe.ouom.neriplayer.data.local.database.migration.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DownloadRomanizedLyricsMigration : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `download_snapshot_metadata` " +
                "ADD COLUMN `romanized_lyric_path` TEXT"
        )
    }
}
