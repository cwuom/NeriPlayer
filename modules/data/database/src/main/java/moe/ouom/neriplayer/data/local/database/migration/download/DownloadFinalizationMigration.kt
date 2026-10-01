package moe.ouom.neriplayer.data.local.database.migration.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import moe.ouom.neriplayer.data.local.database.migration.legacy.copyV15DownloadPayload
import moe.ouom.neriplayer.data.local.database.migration.legacy.dropLegacyDownloadProjectionTables

internal object DownloadFinalizationMigration : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addFinalDownloadColumns(db)
        createFinalDownloadTables(db)
        copyV15DownloadPayload(db)
        dropLegacyDownloadProjectionTables(db)
    }
}
