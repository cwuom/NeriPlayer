package moe.ouom.neriplayer.data.local.database.migration.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DownloadRecoveryCursorMigration : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_operation_recovery_cursor` " +
                "ON `download_operation` (`state`, `stop_requested_by_user`, " +
                "`queue_order`, `created_at_ms`, `operation_id`)"
        )
    }
}
