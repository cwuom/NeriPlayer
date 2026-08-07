package moe.ouom.neriplayer.data.local.database

import android.app.Application
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import moe.ouom.neriplayer.data.local.database.dao.LocalPlaylistDao
import moe.ouom.neriplayer.data.local.database.dao.PlayHistoryDao
import moe.ouom.neriplayer.data.local.database.dao.PlaylistUsageDao
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlayHistoryEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistUsageCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistUsageEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncReplicaCheckpointEntity
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity

@Database(
    entities = [
        LocalPlaylistEntity::class,
        TrackEntity::class,
        PlaylistMemberEntity::class,
        PlaylistMemberTokenEntity::class,
        SyncOutboxEntity::class,
        SyncReplicaCheckpointEntity::class,
        MigrationMetadataEntity::class,
        PlayHistoryEntity::class,
        PlaylistUsageEntity::class,
        PlaylistUsageCounterShardEntity::class
    ],
    version = 3,
    exportSchema = true
)
internal abstract class NeriUserDataDatabase : RoomDatabase() {
    abstract fun localPlaylistDao(): LocalPlaylistDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun playlistUsageDao(): PlaylistUsageDao

    abstract fun syncMetadataDao(): SyncMetadataDao

    companion object {
        const val DATABASE_NAME = "neri_user_data.db"

        @Volatile
        private var instance: NeriUserDataDatabase? = null

        fun getInstance(context: Context): NeriUserDataDatabase {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { database ->
                    instance = database
                }
            }
        }

        internal fun create(context: Context): NeriUserDataDatabase {
            checkMainProcess(context)
            return Room.databaseBuilder(
                context.applicationContext,
                NeriUserDataDatabase::class.java,
                DATABASE_NAME
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        }

        private fun checkMainProcess(context: Context) {
            val expectedProcess = context.applicationInfo.processName
            val currentProcess = Application.getProcessName()
            check(currentProcess == expectedProcess) {
                "Neri user database may only be opened in the main process: " +
                    "current=$currentProcess expected=$expectedProcess"
            }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `play_history` (
                        `identity_key` TEXT NOT NULL,
                        `identity_id` INTEGER NOT NULL,
                        `identity_album` TEXT NOT NULL,
                        `identity_media_uri` TEXT,
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `album_id` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `resume_position_ms` INTEGER NOT NULL,
                        `cover_url` TEXT,
                        `media_uri` TEXT,
                        `matched_lyric` TEXT,
                        `matched_translated_lyric` TEXT,
                        `custom_cover_url` TEXT,
                        `custom_name` TEXT,
                        `custom_artist` TEXT,
                        `original_name` TEXT,
                        `original_artist` TEXT,
                        `original_cover_url` TEXT,
                        `original_lyric` TEXT,
                        `original_translated_lyric` TEXT,
                        `local_file_name` TEXT,
                        `local_file_path` TEXT,
                        `channel_id` TEXT,
                        `audio_id` TEXT,
                        `sub_audio_id` TEXT,
                        `source_stable_key` TEXT,
                        `played_at` INTEGER NOT NULL,
                        PRIMARY KEY(`identity_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_play_history_played_at`
                    ON `play_history` (`played_at` DESC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_play_history_identity_parts`
                    ON `play_history`
                    (`identity_id`, `identity_album`, `identity_media_uri`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_usage` (
                        `usage_key` TEXT NOT NULL,
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `pic_url` TEXT,
                        `track_count` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `last_opened` INTEGER NOT NULL,
                        `open_count` INTEGER NOT NULL,
                        `first_opened` INTEGER NOT NULL,
                        `counter_base_open_count` INTEGER NOT NULL,
                        `fid` INTEGER,
                        `mid` INTEGER,
                        `browse_id` TEXT,
                        `playlist_id` TEXT,
                        `subtype` TEXT,
                        `subtitle` TEXT,
                        PRIMARY KEY(`usage_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playlist_usage_last_opened`
                    ON `playlist_usage` (`last_opened` DESC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playlist_usage_source_id`
                    ON `playlist_usage` (`source`, `playlist_id`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_usage_counter_shard` (
                        `usage_key` TEXT NOT NULL,
                        `device_id` TEXT NOT NULL,
                        `epoch_started_at` INTEGER NOT NULL,
                        `play_count` INTEGER NOT NULL,
                        `first_played_at` INTEGER NOT NULL,
                        `last_played_at` INTEGER NOT NULL,
                        PRIMARY KEY(`usage_key`, `device_id`, `epoch_started_at`),
                        FOREIGN KEY(`usage_key`) REFERENCES `playlist_usage`(`usage_key`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playlist_usage_counter_usage_key`
                    ON `playlist_usage_counter_shard` (`usage_key`)
                    """.trimIndent()
                )
            }
        }
    }
}
