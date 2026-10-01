package moe.ouom.neriplayer.data.local.database.migration.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import moe.ouom.neriplayer.data.local.database.migration.addIntegerColumnIfMissing
import moe.ouom.neriplayer.data.local.database.migration.addTextColumnIfMissing

internal object DownloadBatchMigration : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addTextColumnIfMissing(db, "download_operation", "batch_id")
        addIntegerColumnIfMissing(db, "download_operation", "batch_generation")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_operation_batch` " +
                "ON `download_operation` (`batch_id`, `batch_generation`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_batch` (
                `batch_id` TEXT NOT NULL,
                `generation` INTEGER NOT NULL,
                `total_count` INTEGER NOT NULL,
                `state_bits` INTEGER NOT NULL,
                `clear_epoch` INTEGER NOT NULL,
                `network_generation` INTEGER,
                `updated_at_ms` INTEGER NOT NULL,
                `created_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`batch_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_batch_generation` " +
                "ON `download_batch` (`generation`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_batch_state_updated` " +
                "ON `download_batch` (`state_bits`, `updated_at_ms`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_batch_member` (
                `batch_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `stable_key` TEXT NOT NULL,
                `terminal_bits` INTEGER NOT NULL,
                `max_fraction_milli` INTEGER NOT NULL,
                `initially_completed` INTEGER NOT NULL,
                `operation_id` TEXT,
                `attempt_id` INTEGER,
                `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`batch_id`, `stable_key`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_batch_member_order` " +
                "ON `download_batch_member` (`batch_id`, `ordinal`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_batch_member_terminal` " +
                "ON `download_batch_member` (`batch_id`, `terminal_bits`, `ordinal`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_batch_member_operation` " +
                "ON `download_batch_member` (`operation_id`)"
        )
    }
}
