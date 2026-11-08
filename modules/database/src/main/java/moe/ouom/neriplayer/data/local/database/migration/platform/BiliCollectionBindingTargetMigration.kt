package moe.ouom.neriplayer.data.local.database.migration.platform

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 22 → 23：绑定表从「只支持专辑」扩展到「单曲 / 专辑 / 歌单」，旧行按 album 类型平移。
 *
 * 不能假定 v22 库里一定有这张表：中间的 22 版构建存在过「版本号已达 22 但实体尚未注册」的库，
 * 那种库升级上来时源表不存在，直接 INSERT 会抛 `no such table`。因此按实际表结构分流：
 * 缺表 → 直接建终版表；已是终版列 → 只补索引；旧专辑列 → 重建并平移数据。
 */
internal object BiliCollectionBindingTargetMigration : Migration(22, 23) {
    private const val TABLE = "bili_collection_binding"

    private val createTableSql = "CREATE TABLE IF NOT EXISTS `$TABLE` (" +
        "`target_key` TEXT NOT NULL, " +
        "`target_type` TEXT NOT NULL, " +
        "`target_id` INTEGER NOT NULL, " +
        "`target_name` TEXT NOT NULL, " +
        "`target_subtitle` TEXT NOT NULL, " +
        "`bvid` TEXT NOT NULL, " +
        "`avid` INTEGER NOT NULL, " +
        "`video_title` TEXT NOT NULL, " +
        "`tracks_json` TEXT NOT NULL, " +
        "`updated_at` INTEGER NOT NULL, " +
        "PRIMARY KEY(`target_key`))"

    private val createIndexSql =
        "CREATE INDEX IF NOT EXISTS `index_bili_collection_binding_target_id` " +
            "ON `$TABLE` (`target_id`)"

    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = tableColumns(db, TABLE)
        when {
            columns.isEmpty() -> {
                db.execSQL(createTableSql)
                db.execSQL(createIndexSql)
            }
            "target_key" in columns -> db.execSQL(createIndexSql)
            "album_key" in columns -> rebuildFromAlbumTable(db)
            else -> {
                // 无法识别的旧结构：重建为空表，避免升级后继续崩在读取上
                db.execSQL("DROP TABLE `$TABLE`")
                db.execSQL(createTableSql)
                db.execSQL(createIndexSql)
            }
        }
    }

    private fun rebuildFromAlbumTable(db: SupportSQLiteDatabase) {
        val staging = "${TABLE}_new"
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$staging` (" +
                "`target_key` TEXT NOT NULL, " +
                "`target_type` TEXT NOT NULL, " +
                "`target_id` INTEGER NOT NULL, " +
                "`target_name` TEXT NOT NULL, " +
                "`target_subtitle` TEXT NOT NULL, " +
                "`bvid` TEXT NOT NULL, " +
                "`avid` INTEGER NOT NULL, " +
                "`video_title` TEXT NOT NULL, " +
                "`tracks_json` TEXT NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`target_key`))"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO `$staging` (" +
                "`target_key`, `target_type`, `target_id`, `target_name`, `target_subtitle`, " +
                "`bvid`, `avid`, `video_title`, `tracks_json`, `updated_at`) " +
                "SELECT `album_key`, 'album', `album_id`, `album_name`, `album_artist`, " +
                "`bvid`, `avid`, `video_title`, `tracks_json`, `updated_at` " +
                "FROM `$TABLE`"
        )
        db.execSQL("DROP TABLE `$TABLE`")
        db.execSQL("ALTER TABLE `$staging` RENAME TO `$TABLE`")
        db.execSQL(createIndexSql)
    }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return@buildSet
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
}
