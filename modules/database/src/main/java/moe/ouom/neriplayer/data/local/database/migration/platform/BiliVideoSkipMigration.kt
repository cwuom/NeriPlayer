package moe.ouom.neriplayer.data.local.database.migration.platform

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object BiliVideoSkipMigration : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `bili_video_skip_rule` (
                `bvid` TEXT NOT NULL,
                `cid` INTEGER NOT NULL,
                `modified_at` INTEGER NOT NULL,
                `is_deleted` INTEGER NOT NULL,
                PRIMARY KEY(`bvid`, `cid`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_bili_video_skip_rule_modified_at`
            ON `bili_video_skip_rule` (`modified_at` DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `bili_video_skip_interval` (
                `bvid` TEXT NOT NULL,
                `cid` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                `start_ms` INTEGER NOT NULL,
                `end_ms` INTEGER NOT NULL,
                PRIMARY KEY(`bvid`, `cid`, `position`),
                FOREIGN KEY(`bvid`, `cid`)
                    REFERENCES `bili_video_skip_rule`(`bvid`, `cid`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `bili_video_skip_draft` (
                `target_key` TEXT NOT NULL,
                `bvid` TEXT NOT NULL,
                `cid` INTEGER NOT NULL,
                `start_text` TEXT NOT NULL,
                `end_text` TEXT NOT NULL,
                `modified_at` INTEGER NOT NULL,
                PRIMARY KEY(`target_key`)
            )
            """.trimIndent()
        )
    }
}
