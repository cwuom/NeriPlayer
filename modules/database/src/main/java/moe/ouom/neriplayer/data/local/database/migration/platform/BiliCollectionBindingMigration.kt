package moe.ouom.neriplayer.data.local.database.migration.platform

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object BiliCollectionBindingMigration : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bili_collection_binding` (" +
                "`album_key` TEXT NOT NULL, " +
                "`album_id` INTEGER NOT NULL, " +
                "`album_name` TEXT NOT NULL, " +
                "`album_artist` TEXT NOT NULL, " +
                "`bvid` TEXT NOT NULL, " +
                "`avid` INTEGER NOT NULL, " +
                "`video_title` TEXT NOT NULL, " +
                "`tracks_json` TEXT NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`album_key`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_bili_collection_binding_album_id` " +
                "ON `bili_collection_binding` (`album_id`)"
        )
    }
}
