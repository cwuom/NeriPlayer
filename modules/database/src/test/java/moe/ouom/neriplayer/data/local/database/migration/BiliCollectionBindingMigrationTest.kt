package moe.ouom.neriplayer.data.local.database.migration

import moe.ouom.neriplayer.data.local.database.migration.legacy.LegacyMigrationDatabase
import moe.ouom.neriplayer.data.local.database.migration.platform.BiliCollectionBindingMigration
import moe.ouom.neriplayer.data.local.database.migration.platform.BiliCollectionBindingTargetMigration
import org.junit.Assert.assertEquals
import org.junit.Test

class BiliCollectionBindingMigrationTest {
    @Test
    fun `upgrade creates the album binding table and its album index`() {
        val fixture = LegacyMigrationDatabase()

        BiliCollectionBindingMigration.migrate(fixture.database)

        assertEquals(listOf(
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
                "PRIMARY KEY(`album_key`))",
            "CREATE INDEX IF NOT EXISTS `index_bili_collection_binding_album_id` " +
                "ON `bili_collection_binding` (`album_id`)"
        ), fixture.statements)
    }

    @Test
    fun `target migration creates the collection table when the v22 database never had it`() {
        val fixture = LegacyMigrationDatabase()

        BiliCollectionBindingTargetMigration.migrate(fixture.database)

        assertEquals(listOf(
            "CREATE TABLE IF NOT EXISTS `bili_collection_binding` (" +
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
                "PRIMARY KEY(`target_key`))",
            "CREATE INDEX IF NOT EXISTS `index_bili_collection_binding_target_id` " +
                "ON `bili_collection_binding` (`target_id`)"
        ), fixture.statements)
    }

    @Test
    fun `target migration rebuilds the table as collection bindings and keeps album rows`() {
        val fixture = LegacyMigrationDatabase(
            columns = mapOf(
                "bili_collection_binding" to listOf(
                    "album_key", "album_id", "album_name", "album_artist", "bvid",
                    "avid", "video_title", "tracks_json", "updated_at"
                )
            )
        )

        BiliCollectionBindingTargetMigration.migrate(fixture.database)

        assertEquals(listOf(
            "CREATE TABLE IF NOT EXISTS `bili_collection_binding_new` (" +
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
                "PRIMARY KEY(`target_key`))",
            "INSERT OR REPLACE INTO `bili_collection_binding_new` (" +
                "`target_key`, `target_type`, `target_id`, `target_name`, `target_subtitle`, " +
                "`bvid`, `avid`, `video_title`, `tracks_json`, `updated_at`) " +
                "SELECT `album_key`, 'album', `album_id`, `album_name`, `album_artist`, " +
                "`bvid`, `avid`, `video_title`, `tracks_json`, `updated_at` " +
                "FROM `bili_collection_binding`",
            "DROP TABLE `bili_collection_binding`",
            "ALTER TABLE `bili_collection_binding_new` RENAME TO `bili_collection_binding`",
            "CREATE INDEX IF NOT EXISTS `index_bili_collection_binding_target_id` " +
                "ON `bili_collection_binding` (`target_id`)"
        ), fixture.statements)
    }

    @Test
    fun `target migration keeps an already final table and only restores its index`() {
        val fixture = LegacyMigrationDatabase(
            columns = mapOf(
                "bili_collection_binding" to listOf(
                    "target_key", "target_type", "target_id", "target_name", "target_subtitle",
                    "bvid", "avid", "video_title", "tracks_json", "updated_at"
                )
            )
        )

        BiliCollectionBindingTargetMigration.migrate(fixture.database)

        assertEquals(listOf(
            "CREATE INDEX IF NOT EXISTS `index_bili_collection_binding_target_id` " +
                "ON `bili_collection_binding` (`target_id`)"
        ), fixture.statements)
    }
}
