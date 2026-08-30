package moe.ouom.neriplayer.data.local.database

import android.app.Application
import android.database.Cursor
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale
import org.json.JSONObject
import moe.ouom.neriplayer.data.local.database.dao.LocalPlaylistDao
import moe.ouom.neriplayer.data.local.database.dao.PlayHistoryDao
import moe.ouom.neriplayer.data.local.database.dao.PlaylistUsageDao
import moe.ouom.neriplayer.data.local.database.dao.LocalPlaylistPlaybackDao
import moe.ouom.neriplayer.data.local.database.dao.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.dao.FavoritePlaylistDao
import moe.ouom.neriplayer.data.local.database.dao.TrafficStatsDao
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.dao.PlaybackQueueDao
import moe.ouom.neriplayer.data.local.database.dao.BiliVideoSkipDao
import moe.ouom.neriplayer.data.local.database.dao.CoverUrlMappingDao
import moe.ouom.neriplayer.data.local.database.dao.DownloadOperationDao
import moe.ouom.neriplayer.data.local.database.dao.ManagedLibraryItemDao
import moe.ouom.neriplayer.data.local.database.dao.ManagedDownloadArtifactDao
import moe.ouom.neriplayer.data.local.database.dao.PlatformPlaylistCacheDao
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistSongEntity
import moe.ouom.neriplayer.data.local.database.entity.TrafficStatsBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlayHistoryEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistUsageCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistUsageEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistPlaybackBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistPlaybackCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistPlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncReplicaCheckpointEntity
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackQueueSongEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackQueueStateEntity
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipDraftEntity
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipIntervalEntity
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipRuleEntity
import moe.ouom.neriplayer.data.local.database.entity.CoverUrlMappingEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.database.entity.ManagedLibraryItemEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackArtistEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackEntity

private const val NERI_USER_DATA_FINAL_VERSION = 16

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
        PlaylistUsageCounterShardEntity::class,
        LocalPlaylistPlaybackStatEntity::class,
        LocalPlaylistPlaybackBucketEntity::class,
        LocalPlaylistPlaybackCounterShardEntity::class,
        PlaybackStatEntity::class,
        PlaybackStatBucketEntity::class,
        PlaybackStatCounterShardEntity::class,
        PlaybackStatDailyCounterShardEntity::class,
        FavoritePlaylistEntity::class,
        FavoritePlaylistSongEntity::class,
        TrafficStatsBucketEntity::class,
        PlaybackQueueStateEntity::class,
        PlaybackQueueSongEntity::class,
        BiliVideoSkipRuleEntity::class,
        BiliVideoSkipIntervalEntity::class,
        BiliVideoSkipDraftEntity::class,
        CoverUrlMappingEntity::class,
        DownloadOperationEntity::class,
        ManagedLibraryItemEntity::class,
        PlatformPlaylistCacheEntity::class,
        PlatformPlaylistCacheTrackEntity::class,
        PlatformPlaylistCacheTrackArtistEntity::class
    ],
    version = NERI_USER_DATA_FINAL_VERSION,
    exportSchema = true
)
internal abstract class NeriUserDataDatabase : RoomDatabase() {
    abstract fun localPlaylistDao(): LocalPlaylistDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun playlistUsageDao(): PlaylistUsageDao

    abstract fun localPlaylistPlaybackDao(): LocalPlaylistPlaybackDao

    abstract fun playbackStatsDao(): PlaybackStatsDao

    abstract fun favoritePlaylistDao(): FavoritePlaylistDao

    abstract fun trafficStatsDao(): TrafficStatsDao

    abstract fun syncMetadataDao(): SyncMetadataDao

    abstract fun playbackQueueDao(): PlaybackQueueDao

    abstract fun biliVideoSkipDao(): BiliVideoSkipDao

    abstract fun downloadOperationDao(): DownloadOperationDao

    abstract fun managedLibraryItemDao(): ManagedLibraryItemDao

    abstract fun coverUrlMappingDao(): CoverUrlMappingDao

    abstract fun managedDownloadArtifactDao(): ManagedDownloadArtifactDao

    abstract fun platformPlaylistCacheDao(): PlatformPlaylistCacheDao

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
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_FINAL
            ).build()
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

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_playlist_playback_stat` (
                        `playlist_id` INTEGER NOT NULL,
                        `total_play_count` INTEGER NOT NULL,
                        `first_played_at` INTEGER NOT NULL,
                        `last_played_at` INTEGER NOT NULL,
                        `counter_base_play_count` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_local_playlist_playback_stat_last_played`
                    ON `local_playlist_playback_stat` (`last_played_at` DESC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_playlist_playback_bucket` (
                        `playlist_id` INTEGER NOT NULL,
                        `day_start_at` INTEGER NOT NULL,
                        `play_count` INTEGER NOT NULL,
                        `first_played_at` INTEGER NOT NULL,
                        `last_played_at` INTEGER NOT NULL,
                        `counter_base_play_count` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`, `day_start_at`),
                        FOREIGN KEY(`playlist_id`) REFERENCES
                            `local_playlist_playback_stat`(`playlist_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_local_playlist_playback_bucket_day`
                    ON `local_playlist_playback_bucket`
                    (`playlist_id` ASC, `day_start_at` ASC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_playlist_playback_counter_shard` (
                        `playlist_id` INTEGER NOT NULL,
                        `day_start_at` INTEGER NOT NULL,
                        `device_id` TEXT NOT NULL,
                        `epoch_started_at` INTEGER NOT NULL,
                        `play_count` INTEGER NOT NULL,
                        `first_played_at` INTEGER NOT NULL,
                        `last_played_at` INTEGER NOT NULL,
                        PRIMARY KEY(
                            `playlist_id`,
                            `day_start_at`,
                            `device_id`,
                            `epoch_started_at`
                        ),
                        FOREIGN KEY(`playlist_id`) REFERENCES
                            `local_playlist_playback_stat`(`playlist_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_local_playlist_playback_counter_scope`
                    ON `local_playlist_playback_counter_shard`
                    (`playlist_id`, `day_start_at`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_stat` (
                        `identity_key` TEXT NOT NULL,
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `album_id` INTEGER NOT NULL,
                        `cover_url` TEXT,
                        `duration_ms` INTEGER NOT NULL,
                        `total_listen_ms` INTEGER NOT NULL,
                        `play_count` INTEGER NOT NULL,
                        `last_played_at` INTEGER NOT NULL,
                        `first_played_at` INTEGER NOT NULL,
                        `media_uri` TEXT,
                        `local_file_path` TEXT,
                        `local_file_name` TEXT,
                        `custom_name` TEXT,
                        `custom_artist` TEXT,
                        `custom_cover_url` TEXT,
                        PRIMARY KEY(`identity_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playback_stat_last_played`
                    ON `playback_stat` (`last_played_at` DESC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playback_stat_media_uri`
                    ON `playback_stat` (`media_uri`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_stat_bucket` (
                        `day_start_at` INTEGER NOT NULL,
                        `identity_key` TEXT NOT NULL,
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `album_id` INTEGER NOT NULL,
                        `cover_url` TEXT,
                        `duration_ms` INTEGER NOT NULL,
                        `total_listen_ms` INTEGER NOT NULL,
                        `play_count` INTEGER NOT NULL,
                        `last_played_at` INTEGER NOT NULL,
                        `first_played_at` INTEGER NOT NULL,
                        `media_uri` TEXT,
                        `local_file_path` TEXT,
                        `local_file_name` TEXT,
                        `custom_name` TEXT,
                        `custom_artist` TEXT,
                        `custom_cover_url` TEXT,
                        PRIMARY KEY(`day_start_at`, `identity_key`),
                        FOREIGN KEY(`identity_key`) REFERENCES
                            `playback_stat`(`identity_key`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playback_stat_bucket_day`
                    ON `playback_stat_bucket`
                    (`day_start_at` DESC, `identity_key` ASC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playback_stat_bucket_identity`
                    ON `playback_stat_bucket` (`identity_key`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_stat_counter_shard` (
                        `identity_key` TEXT NOT NULL,
                        `device_id` TEXT NOT NULL,
                        `epoch_started_at` INTEGER NOT NULL,
                        `total_listen_ms` INTEGER NOT NULL,
                        `play_count` INTEGER NOT NULL,
                        `first_played_at` INTEGER NOT NULL,
                        `last_played_at` INTEGER NOT NULL,
                        PRIMARY KEY(
                            `identity_key`,
                            `device_id`,
                            `epoch_started_at`
                        ),
                        FOREIGN KEY(`identity_key`) REFERENCES
                            `playback_stat`(`identity_key`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playback_stat_counter_identity`
                    ON `playback_stat_counter_shard` (`identity_key`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS
                    `playback_stat_daily_counter_shard` (
                        `day_start_at` INTEGER NOT NULL,
                        `identity_key` TEXT NOT NULL,
                        `device_id` TEXT NOT NULL,
                        `epoch_started_at` INTEGER NOT NULL,
                        `total_listen_ms` INTEGER NOT NULL,
                        `play_count` INTEGER NOT NULL,
                        `first_played_at` INTEGER NOT NULL,
                        `last_played_at` INTEGER NOT NULL,
                        PRIMARY KEY(
                            `day_start_at`,
                            `identity_key`,
                            `device_id`,
                            `epoch_started_at`
                        ),
                        FOREIGN KEY(`day_start_at`, `identity_key`)
                            REFERENCES `playback_stat_bucket`
                            (`day_start_at`, `identity_key`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_playback_stat_daily_counter_scope`
                    ON `playback_stat_daily_counter_shard`
                    (`day_start_at`, `identity_key`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_playlist` (
                        `playlist_id` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `cover_url` TEXT,
                        `track_count` INTEGER NOT NULL,
                        `browse_id` TEXT,
                        `remote_playlist_id` TEXT,
                        `subtitle` TEXT,
                        `added_time` INTEGER NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `modified_at` INTEGER NOT NULL,
                        `is_deleted` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`, `source`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_favorite_playlist_sort`
                    ON `favorite_playlist` (`sort_order` DESC, `modified_at` DESC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_favorite_playlist_visibility`
                    ON `favorite_playlist` (`is_deleted` ASC, `sort_order` DESC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_playlist_song` (
                        `playlist_id` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `display_position` INTEGER NOT NULL,
                        `song_payload_json` TEXT NOT NULL,
                        PRIMARY KEY(`playlist_id`, `source`, `display_position`),
                        FOREIGN KEY(`playlist_id`, `source`)
                            REFERENCES `favorite_playlist`(`playlist_id`, `source`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_favorite_playlist_song_order`
                    ON `favorite_playlist_song`
                    (`playlist_id` ASC, `source` ASC, `display_position` ASC)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `traffic_stats_bucket` (
                        `day_start_at` INTEGER NOT NULL,
                        `wifi_bytes` INTEGER NOT NULL,
                        `mobile_bytes` INTEGER NOT NULL,
                        `roaming_bytes` INTEGER NOT NULL,
                        `playback_network_bytes` INTEGER NOT NULL,
                        `download_network_bytes` INTEGER NOT NULL,
                        `cache_hit_bytes` INTEGER NOT NULL,
                        `request_count` INTEGER NOT NULL,
                        `cache_hit_count` INTEGER NOT NULL,
                        PRIMARY KEY(`day_start_at`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_traffic_stats_bucket_day`
                    ON `traffic_stats_bucket` (`day_start_at` DESC)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_queue_state` (
                        `id` INTEGER NOT NULL,
                        `current_index` INTEGER NOT NULL,
                        `media_url` TEXT,
                        `position_ms` INTEGER NOT NULL,
                        `should_resume_playback` INTEGER NOT NULL,
                        `repeat_mode` INTEGER,
                        `shuffle_enabled` INTEGER,
                        `shuffle_restore_index` INTEGER,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_queue_song` (
                        `queue_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `album_id` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `cover_url` TEXT,
                        `media_uri` TEXT,
                        `matched_lyric` TEXT,
                        `matched_translated_lyric` TEXT,
                        `matched_lyric_source` TEXT,
                        `matched_song_id` TEXT,
                        `user_lyric_offset_ms` INTEGER NOT NULL,
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
                        `playlist_context_id` TEXT,
                        `stream_url` TEXT,
                        PRIMARY KEY(`queue_id`, `position`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
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

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_pending_queue` (
                        `stable_key` TEXT NOT NULL,
                        `queue_order` INTEGER NOT NULL,
                        `queued_at_ms` INTEGER NOT NULL,
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `album_id` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `cover_url` TEXT,
                        `media_uri` TEXT,
                        `matched_lyric` TEXT,
                        `matched_translated_lyric` TEXT,
                        `matched_lyric_source` TEXT,
                        `matched_song_id` TEXT,
                        `user_lyric_offset_ms` INTEGER NOT NULL,
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
                        `playlist_context_id` TEXT,
                        `source_stable_key` TEXT,
                        `stream_url` TEXT,
                        PRIMARY KEY(`stable_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_download_pending_queue_order`
                    ON `download_pending_queue`
                    (`queue_order` ASC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_cancelled_key` (
                        `stable_key` TEXT NOT NULL,
                        `cancelled_at_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`stable_key`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloaded_song_catalog` (
                        `catalog_key` TEXT NOT NULL,
                        `root_key` TEXT NOT NULL,
                        `display_position` INTEGER NOT NULL,
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `file_path` TEXT NOT NULL,
                        `file_size` INTEGER NOT NULL,
                        `download_time` INTEGER NOT NULL,
                        `cover_path` TEXT,
                        `cover_url` TEXT,
                        `matched_lyric` TEXT,
                        `matched_translated_lyric` TEXT,
                        `matched_lyric_source` TEXT,
                        `matched_song_id` TEXT,
                        `user_lyric_offset_ms` INTEGER NOT NULL,
                        `custom_cover_url` TEXT,
                        `custom_name` TEXT,
                        `custom_artist` TEXT,
                        `original_name` TEXT,
                        `original_artist` TEXT,
                        `original_cover_url` TEXT,
                        `original_lyric` TEXT,
                        `original_translated_lyric` TEXT,
                        `media_uri` TEXT,
                        `duration_ms` INTEGER NOT NULL,
                        `stable_key` TEXT,
                        `source_identity_album` TEXT,
                        `source_media_uri` TEXT,
                        `source_channel_id` TEXT,
                        `source_audio_id` TEXT,
                        `source_sub_audio_id` TEXT,
                        `source_playlist_context_id` TEXT,
                        PRIMARY KEY(`catalog_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_downloaded_song_catalog_root_position`
                    ON `downloaded_song_catalog`
                    (`root_key` ASC, `display_position` ASC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_downloaded_song_catalog_file_path`
                    ON `downloaded_song_catalog` (`file_path`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_downloaded_song_catalog_media_uri`
                    ON `downloaded_song_catalog` (`media_uri`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_downloaded_song_catalog_stable_key`
                    ON `downloaded_song_catalog` (`stable_key`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cover_url_mapping` (
                        `local_url` TEXT NOT NULL,
                        `network_url` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`local_url`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_cover_url_mapping_updated_at`
                    ON `cover_url_mapping` (`updated_at` DESC)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_snapshot_entry` (
                        `root_key` TEXT NOT NULL,
                        `bucket` TEXT NOT NULL,
                        `entry_key` TEXT NOT NULL,
                        `display_position` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `reference` TEXT NOT NULL,
                        `media_uri` TEXT NOT NULL,
                        `local_file_path` TEXT,
                        `size_bytes` INTEGER NOT NULL,
                        `last_modified_ms` INTEGER NOT NULL,
                        `is_directory` INTEGER NOT NULL,
                        PRIMARY KEY(`root_key`, `bucket`, `entry_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_download_snapshot_entry_root_bucket_position`
                    ON `download_snapshot_entry`
                    (`root_key` ASC, `bucket` ASC, `display_position` ASC)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_download_snapshot_entry_root_bucket_name`
                    ON `download_snapshot_entry`
                    (`root_key`, `bucket`, `name`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_download_snapshot_entry_root_reference`
                    ON `download_snapshot_entry` (`root_key`, `reference`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_snapshot_metadata` (
                        `root_key` TEXT NOT NULL,
                        `audio_name` TEXT NOT NULL,
                        `stable_key` TEXT,
                        `song_id` INTEGER,
                        `identity_album` TEXT,
                        `name` TEXT,
                        `artist` TEXT,
                        `cover_url` TEXT,
                        `matched_lyric` TEXT,
                        `matched_translated_lyric` TEXT,
                        `matched_lyric_source` TEXT,
                        `matched_song_id` TEXT,
                        `user_lyric_offset_ms` INTEGER NOT NULL,
                        `custom_cover_url` TEXT,
                        `custom_name` TEXT,
                        `custom_artist` TEXT,
                        `original_name` TEXT,
                        `original_artist` TEXT,
                        `original_cover_url` TEXT,
                        `original_lyric` TEXT,
                        `original_translated_lyric` TEXT,
                        `media_uri` TEXT,
                        `channel_id` TEXT,
                        `audio_id` TEXT,
                        `sub_audio_id` TEXT,
                        `playlist_context_id` TEXT,
                        `cover_path` TEXT,
                        `lyric_path` TEXT,
                        `translated_lyric_path` TEXT,
                        `duration_ms` INTEGER NOT NULL,
                        `download_finalized` INTEGER,
                        PRIMARY KEY(`root_key`, `audio_name`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_download_snapshot_metadata_root_stable_key`
                    ON `download_snapshot_metadata` (`root_key`, `stable_key`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_download_snapshot_metadata_root_song_id`
                    ON `download_snapshot_metadata` (`root_key`, `song_id`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_download_snapshot_metadata_root_media_uri`
                    ON `download_snapshot_metadata` (`root_key`, `media_uri`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_download_snapshot_metadata_root_remote_track`
                    ON `download_snapshot_metadata`
                    (`root_key`, `channel_id`, `audio_id`, `sub_audio_id`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createPlatformPlaylistCacheTables(db)
            }
        }

        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `download_snapshot_metadata` " +
                        "ADD COLUMN `romanized_lyric_path` TEXT"
                )
            }
        }

        const val FINAL_DB_VERSION = NERI_USER_DATA_FINAL_VERSION

        val MIGRATION_15_FINAL: Migration = object : Migration(15, FINAL_DB_VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addFinalDownloadColumns(db)
                createFinalDownloadTables(db)
                copyV15DownloadPayload(db)
                dropLegacyDownloadProjectionTables(db)
            }
        }

        private fun addFinalDownloadColumns(db: SupportSQLiteDatabase) {
            addTextColumnIfMissing(db, "downloaded_song_catalog", "matched_romanized_lyric")
            addTextColumnIfMissing(db, "downloaded_song_catalog", "original_romanized_lyric")
            addTextColumnIfMissing(db, "download_snapshot_metadata", "album")
            addTextColumnIfMissing(db, "download_snapshot_metadata", "matched_romanized_lyric")
            addTextColumnIfMissing(db, "download_snapshot_metadata", "original_romanized_lyric")
            addIntegerColumnIfMissing(db, "download_snapshot_metadata", "created_at_ms")
            addTextColumnIfMissing(db, "download_snapshot_metadata", "created_at_source")
            addTextColumnIfMissing(db, "download_pending_queue", "matched_romanized_lyric")
            addTextColumnIfMissing(db, "download_pending_queue", "original_romanized_lyric")
        }

        private fun createFinalDownloadTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `download_operation` (
                    `operation_id` TEXT NOT NULL PRIMARY KEY,
                    `stable_key` TEXT NOT NULL,
                    `library_id` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `queue_order` INTEGER NOT NULL,
                    `source_hint_json` TEXT NOT NULL,
                    `staging_dir_name` TEXT NOT NULL,
                    `bytes_written` INTEGER NOT NULL,
                    `total_bytes` INTEGER,
                    `resume_json` TEXT,
                    `retry_count` INTEGER NOT NULL,
                    `next_retry_at_ms` INTEGER,
                    `last_error_code` TEXT,
                    `stop_requested_by_user` INTEGER NOT NULL DEFAULT 0,
                    `created_at_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    `host_process_token` TEXT,
                    `host_admitted_at_ms` INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_download_operation_state_queue`
                ON `download_operation` (`state`, `queue_order`)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_download_operation_host_process_library`
                ON `download_operation` (`host_process_token`, `library_id`)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `managed_library_item` (
                    `library_id` TEXT NOT NULL,
                    `stable_key` TEXT NOT NULL,
                    `artifact_id` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `lease_id` TEXT,
                    `audio_reference` TEXT,
                    `audio_name` TEXT,
                    `file_size` INTEGER,
                    `content_hash` TEXT,
                    `library_added_at_ms` INTEGER,
                    `source_created_at_ms` INTEGER,
                    `source_modified_at_ms` INTEGER,
                    `migrated_at_ms` INTEGER,
                    `finalized_at_ms` INTEGER,
                    `updated_at_ms` INTEGER NOT NULL DEFAULT 0,
                    `needs_reconcile` INTEGER NOT NULL DEFAULT 0,
                    `last_error_code` TEXT,
                    `metadata_name` TEXT,
                    `locator_hint` TEXT,
                    `title_preview` TEXT,
                    `artist_preview` TEXT,
                    `cover_key_preview` TEXT,
                    `downloaded_at_ms` INTEGER,
                    `metadata_revision` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`library_id`, `stable_key`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_managed_library_item_artifact`
                ON `managed_library_item` (`artifact_id`)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `legacy_download_upgrade_payload` (
                    `stable_key` TEXT NOT NULL PRIMARY KEY,
                    `payload_json` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `legacy_download_upgrade_quarantine` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `stable_key` TEXT NOT NULL,
                    `payload_json` TEXT NOT NULL,
                    `reason` TEXT NOT NULL,
                    `quarantined_at_ms` INTEGER NOT NULL,
                    UNIQUE(`stable_key`, `payload_json`)
                )
                """.trimIndent()
            )
        }

        private fun copyV15DownloadPayload(db: SupportSQLiteDatabase) {
            val catalogLookup = buildLegacyCatalogLookup(db)
            // 迁移在同一事务内执行，内存缓存可避免每行再次查询载荷表
            val payloadCache = loadLegacyPayloadCache(db)
            copyLegacyTableRows(
                db = db,
                tableName = "download_pending_queue",
                catalogLookup = catalogLookup,
                payloadCache = payloadCache
            )
            copyLegacyTableRows(
                db = db,
                tableName = "download_cancelled_key",
                catalogLookup = catalogLookup,
                payloadCache = payloadCache
            )
            copyLegacyTableRows(
                db = db,
                tableName = "downloaded_song_catalog",
                catalogLookup = catalogLookup,
                payloadCache = payloadCache
            )
            copyLegacyTableRows(
                db = db,
                tableName = "download_snapshot_entry",
                catalogLookup = catalogLookup,
                payloadCache = payloadCache
            )
            copyLegacyTableRows(
                db = db,
                tableName = "download_snapshot_metadata",
                catalogLookup = catalogLookup,
                payloadCache = payloadCache
            )
            copyLegacyTableRows(
                db = db,
                tableName = "managed_download_artifact",
                catalogLookup = catalogLookup,
                payloadCache = payloadCache
            )
        }

        private fun loadLegacyPayloadCache(
            db: SupportSQLiteDatabase
        ): MutableMap<String, JSONObject> {
            if (!hasTable(db, "legacy_download_upgrade_payload")) {
                return linkedMapOf()
            }
            val cache = linkedMapOf<String, JSONObject>()
            forEachLegacyBatch(
                db = db,
                tableName = "legacy_download_upgrade_payload",
                projection = "`stable_key`, `payload_json`"
            ) { cursor, _ ->
                val stableKey = cursorString(cursor, "stable_key") ?: return@forEachLegacyBatch
                val payload = runCatching {
                    JSONObject(cursorString(cursor, "payload_json") ?: return@runCatching null)
                }.getOrNull() ?: return@forEachLegacyBatch
                cache[stableKey] = payload
            }
            return cache
        }

        private fun dropLegacyDownloadProjectionTables(db: SupportSQLiteDatabase) {
            LEGACY_DOWNLOAD_PROJECTION_TABLES.forEach { tableName ->
                db.execSQL("DROP TABLE IF EXISTS `$tableName`")
            }
        }

        private fun copyLegacyTableRows(
            db: SupportSQLiteDatabase,
            tableName: String,
            catalogLookup: LegacyCatalogLookup,
            payloadCache: MutableMap<String, JSONObject>
        ) {
            if (!hasTable(db, tableName)) return
            val pendingWrites = LinkedHashMap<String, String>()
            forEachLegacyBatch(db = db, tableName = tableName) { cursor, columnNames ->
                val row = rowToJson(cursor, columnNames)
                val stableKey = resolveLegacyStableKey(
                    tableName = tableName,
                    cursor = cursor,
                    catalogLookup = catalogLookup
                ) ?: fallbackLegacyStableKey(tableName, cursor)
                val existing = payloadCache[stableKey] ?: JSONObject()
                val hadStableKey = existing.has("stableKey") &&
                    !existing.isNull("stableKey") &&
                    existing.optString("stableKey") == stableKey
                val changed = mergeLegacyRow(
                    payload = existing,
                    tableName = tableName,
                    stableKey = stableKey,
                    row = row
                )
                if (changed || !hadStableKey) {
                    existing.put("stableKey", stableKey)
                    val payloadJson = existing.toString()
                    pendingWrites[stableKey] = payloadJson
                    payloadCache[stableKey] = existing
                    if (pendingWrites.size >= LEGACY_PAYLOAD_UPSERT_BATCH_SIZE) {
                        flushPayloadWrites(db, pendingWrites)
                    }
                }
            }
            flushPayloadWrites(db, pendingWrites)
        }

        private fun flushPayloadWrites(
            db: SupportSQLiteDatabase,
            pendingWrites: LinkedHashMap<String, String>
        ) {
            while (pendingWrites.isNotEmpty()) {
                val batch = pendingWrites.entries
                    .take(LEGACY_PAYLOAD_UPSERT_BATCH_SIZE)
                val placeholders = List(batch.size) { "(?, ?)" }.joinToString(",")
                val statement = db.compileStatement(
                    "INSERT OR REPLACE INTO `legacy_download_upgrade_payload` " +
                        "(`stable_key`, `payload_json`) VALUES $placeholders"
                )
                statement.use { insertStatement ->
                    batch.forEachIndexed { index, entry ->
                        val parameterOffset = index * 2
                        insertStatement.bindString(parameterOffset + 1, entry.key)
                        insertStatement.bindString(parameterOffset + 2, entry.value)
                    }
                    insertStatement.executeInsert()
                }
                batch.forEach { entry -> pendingWrites.remove(entry.key) }
            }
        }

        private fun forEachLegacyBatch(
            db: SupportSQLiteDatabase,
            tableName: String,
            projection: String = "*",
            block: (Cursor, Array<String>) -> Unit
        ) {
            var useRowId = true
            var lastRowId: Long? = null
            var offset = 0
            var processedRows = 0
            while (true) {
                var rowsInBatch = 0
                val query = if (useRowId) {
                    if (lastRowId == null) {
                        "SELECT rowid AS `$LEGACY_ROW_ID_ALIAS`, $projection " +
                            "FROM `$tableName` ORDER BY rowid ASC " +
                            "LIMIT $LEGACY_MIGRATION_BATCH_SIZE"
                    } else {
                        "SELECT rowid AS `$LEGACY_ROW_ID_ALIAS`, $projection " +
                            "FROM `$tableName` WHERE rowid > ? ORDER BY rowid ASC " +
                            "LIMIT $LEGACY_MIGRATION_BATCH_SIZE"
                    }
                } else {
                    "SELECT $projection FROM `$tableName` " +
                        "LIMIT $LEGACY_MIGRATION_BATCH_SIZE OFFSET $offset"
                }
                val cursor = try {
                    if (useRowId && lastRowId != null) {
                        db.query(query, arrayOf(lastRowId.toString()))
                    } else {
                        db.query(query)
                    }
                } catch (error: Exception) {
                    if (!useRowId) throw error
                    // 少数旧库可能没有 ROWID，退回兼容分页
                    useRowId = false
                    lastRowId = null
                    offset = processedRows
                    db.query(
                        "SELECT $projection FROM `$tableName` " +
                            "LIMIT $LEGACY_MIGRATION_BATCH_SIZE OFFSET $offset"
                    )
                }
                cursor.use {
                    val rowIdIndex = if (useRowId) {
                        it.getColumnIndex(LEGACY_ROW_ID_ALIAS)
                    } else {
                        -1
                    }
                    val columnNames = it.columnNames
                    while (it.moveToNext()) {
                        rowsInBatch += 1
                        if (rowIdIndex >= 0) {
                            lastRowId = it.getLong(rowIdIndex)
                        }
                        block(it, columnNames)
                    }
                }
                processedRows += rowsInBatch
                if (rowsInBatch < LEGACY_MIGRATION_BATCH_SIZE) return
                if (!useRowId) offset += rowsInBatch
            }
        }

        private fun resolveLegacyStableKey(
            tableName: String,
            cursor: Cursor,
            catalogLookup: LegacyCatalogLookup
        ): String? {
            val directStableKey = cursorString(cursor, "stable_key")
            if (directStableKey != null) return directStableKey

            return when (tableName) {
                "downloaded_song_catalog" -> deriveCatalogStableKey(cursor)
                "download_snapshot_metadata" -> findCatalogStableKeyForAudioName(
                    catalogLookup = catalogLookup,
                    audioName = cursorString(cursor, "audio_name")
                )
                "download_snapshot_entry" -> findCatalogStableKeyForSnapshotEntry(
                    catalogLookup = catalogLookup,
                    reference = cursorString(cursor, "reference"),
                    mediaUri = cursorString(cursor, "media_uri"),
                    name = cursorString(cursor, "name")
                ) ?: deriveSnapshotEntryStableKey(cursor)
                else -> null
            }
        }

        private fun deriveCatalogStableKey(cursor: Cursor): String? {
            val id = cursorLong(cursor, "id")?.takeIf { it != 0L } ?: return null
            val filePath = cursorString(cursor, "file_path")
            val mediaUri = cursorString(cursor, "media_uri")
            val localReference = listOfNotNull(filePath, mediaUri)
                .firstOrNull(::isLegacyLocalReference)
                ?.let(::normalizeLegacyLocalReference)
                ?: return null
            return "$id|__local_files__|$localReference"
        }

        private fun deriveSnapshotEntryStableKey(cursor: Cursor): String? {
            val rootKey = cursorString(cursor, "root_key") ?: return null
            val entryKey = cursorString(cursor, "entry_key")
                ?: cursorString(cursor, "reference")
                ?: cursorString(cursor, "name")
                ?: return null
            return "legacy-snapshot:$rootKey:$entryKey"
        }

        private fun fallbackLegacyStableKey(
            tableName: String,
            cursor: Cursor
        ): String {
            val identityColumns = when (tableName) {
                "download_pending_queue", "download_cancelled_key" ->
                    listOf("stable_key", "queued_at_ms", "cancelled_at_ms")
                "downloaded_song_catalog" ->
                    listOf("catalog_key", "root_key", "display_position", "id")
                "download_snapshot_entry" ->
                    listOf("root_key", "bucket", "entry_key", "display_position")
                "download_snapshot_metadata" ->
                    listOf("root_key", "audio_name")
                "managed_download_artifact" ->
                    listOf("root_key", "stable_key", "artifact_id")
                else -> cursor.columnNames.toList()
            }
            val identity = identityColumns.mapNotNull { columnName ->
                cursorString(cursor, columnName)?.let { value ->
                    "$columnName=$value"
                }
            }.joinToString("|").ifBlank { "row-${cursor.position}" }
            return "legacy:$tableName:$identity"
        }

        private fun mergeLegacyRow(
            payload: JSONObject,
            tableName: String,
            stableKey: String,
            row: JSONObject
        ): Boolean {
            if (tableName == "download_snapshot_entry") {
                val entries = payload.optJSONArray("download_snapshot_entries")
                    ?: org.json.JSONArray().also {
                        payload.put("download_snapshot_entries", it)
                    }
                if (!containsJsonObject(entries, row)) {
                    entries.put(row)
                    return true
                }
                return false
            }
            val previous = payload.optJSONObject(tableName)
            if (previous == null) {
                payload.put(tableName, row)
                addCamelCaseAliases(payload, row)
                return true
            }

            return when (compareLegacyBytes(previous, row)) {
                true -> false
                false -> {
                    appendLegacyConflict(
                        payload = payload,
                        tableName = tableName,
                        stableKey = stableKey,
                        reason = "SAME_STABLE_KEY_DIFFERENT_BYTES",
                        previous = previous,
                        duplicate = row
                    )
                    true
                }
                null -> {
                    appendLegacyConflict(
                        payload = payload,
                        tableName = tableName,
                        stableKey = stableKey,
                        reason = "SAME_STABLE_KEY_BYTES_UNVERIFIED",
                        previous = previous,
                        duplicate = row
                    )
                    true
                }
            }
        }

        private fun compareLegacyBytes(first: JSONObject, second: JSONObject): Boolean? {
            val firstFingerprint = legacyByteFingerprint(first) ?: return null
            val secondFingerprint = legacyByteFingerprint(second) ?: return null
            return firstFingerprint == secondFingerprint
        }

        private fun legacyByteFingerprint(row: JSONObject): String? {
            val contentHash = listOf("content_hash", "contentHash")
                .asSequence()
                .map { key -> row.optString(key) }
                .firstOrNull(String::isNotBlank)
            if (contentHash != null) return "hash:${contentHash.trim()}"

            return null
        }

        private fun containsJsonObject(
            array: org.json.JSONArray,
            candidate: JSONObject
        ): Boolean {
            for (index in 0 until array.length()) {
                if (array.optJSONObject(index)?.toString() == candidate.toString()) {
                    return true
                }
            }
            return false
        }

        private fun appendLegacyConflict(
            payload: JSONObject,
            tableName: String,
            stableKey: String,
            reason: String,
            previous: JSONObject,
            duplicate: JSONObject
        ) {
            val conflicts = payload.optJSONArray("legacyConflicts") ?: org.json.JSONArray()
            conflicts.put(
                JSONObject().apply {
                    put("table", tableName)
                    put("stableKey", stableKey)
                    put("reason", reason)
                    put("firstFingerprint", legacyByteFingerprint(previous))
                    put("duplicateFingerprint", legacyByteFingerprint(duplicate))
                    put("firstReference", legacyReference(previous))
                    put("duplicateReference", legacyReference(duplicate))
                    put("previous", JSONObject(previous.toString()))
                    put("duplicate", JSONObject(duplicate.toString()))
                }
            )
            payload.put("legacyConflicts", conflicts)
        }

        private fun legacyReference(row: JSONObject): String? {
            return listOf(
                "media_uri",
                "mediaUri",
                "file_path",
                "filePath",
                "audio_reference",
                "audioReference"
            )
                .asSequence()
                .mapNotNull { key ->
                    row.opt(key)
                        ?.takeUnless { value -> value == JSONObject.NULL }
                        ?.toString()
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
                .firstOrNull()
        }

        private fun cursorString(cursor: Cursor, columnName: String): String? {
            val index = cursor.getColumnIndex(columnName)
            if (index < 0 || cursor.isNull(index)) return null
            return cursor.getString(index)?.trim()?.takeIf(String::isNotBlank)
        }

        private fun cursorLong(cursor: Cursor, columnName: String): Long? {
            val index = cursor.getColumnIndex(columnName)
            if (index < 0 || cursor.isNull(index)) return null
            return cursor.getLong(index)
        }

        private fun isLegacyLocalReference(reference: String): Boolean {
            return reference.startsWith("/") ||
                reference.startsWith("file:", ignoreCase = true) ||
                reference.startsWith("content:", ignoreCase = true)
        }

        private fun normalizeLegacyLocalReference(reference: String): String {
            if (!reference.startsWith("file://", ignoreCase = true)) return reference
            return runCatching { java.net.URI(reference).path }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: reference
        }

        private fun rowToJson(cursor: Cursor, columnNames: Array<String>): JSONObject {
            val rowIdIndex = cursor.getColumnIndex(LEGACY_ROW_ID_ALIAS)
            return JSONObject().apply {
                columnNames.forEachIndexed { index, columnName ->
                    if (index != rowIdIndex && columnName != LEGACY_ROW_ID_ALIAS) {
                        put(columnName, cursorValue(cursor, index))
                    }
                }
            }
        }

        private fun addCamelCaseAliases(target: JSONObject, row: JSONObject) {
            val keys = row.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val alias = snakeToCamel(key)
                if (alias.isNotBlank() && (!target.has(alias) || target.isNull(alias))) {
                    target.put(alias, row.get(key))
                }
                if (key == "audio_name" && (!target.has("audioFileName") || target.isNull("audioFileName"))) {
                    target.put("audioFileName", row.get(key))
                }
            }
        }

        private fun snakeToCamel(value: String): String {
            return buildString(value.length) {
                var uppercaseNext = false
                value.forEach { character ->
                    if (character == '_') {
                        uppercaseNext = true
                    } else if (uppercaseNext) {
                        append(character.uppercaseChar())
                        uppercaseNext = false
                    } else {
                        append(character)
                    }
                }
            }
        }

        private fun cursorValue(cursor: Cursor, index: Int): Any {
            if (cursor.isNull(index)) return JSONObject.NULL
            return when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                Cursor.FIELD_TYPE_BLOB -> String(cursor.getBlob(index), Charsets.ISO_8859_1)
                else -> cursor.getString(index)
            }
        }

        private fun hasTable(db: SupportSQLiteDatabase, tableName: String): Boolean {
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                arrayOf(tableName)
            ).use { cursor ->
                return cursor.moveToFirst()
            }
        }

        private fun tableColumnNames(
            db: SupportSQLiteDatabase,
            tableName: String
        ): Set<String> {
            val columns = linkedSetOf<String>()
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return columns
                while (cursor.moveToNext()) {
                    cursor.getString(nameIndex)?.let(columns::add)
                }
            }
            return columns
        }

        private data class LegacyCatalogLookup(
            val stableKeysByReference: Map<String, Set<String>>,
            val stableKeysByNormalizedName: Map<String, Set<String>>
        )

        private fun buildLegacyCatalogLookup(
            db: SupportSQLiteDatabase
        ): LegacyCatalogLookup {
            if (!hasTable(db, "downloaded_song_catalog")) {
                return LegacyCatalogLookup(emptyMap(), emptyMap())
            }
            val stableKeysByReference = linkedMapOf<String, MutableSet<String>>()
            val stableKeysByNormalizedName = linkedMapOf<String, MutableSet<String>>()
            val availableColumns = tableColumnNames(db, "downloaded_song_catalog")
            val projectionColumns = LEGACY_CATALOG_LOOKUP_COLUMNS.filter {
                it in availableColumns
            }
            if (projectionColumns.isEmpty()) {
                return LegacyCatalogLookup(emptyMap(), emptyMap())
            }
            val projection = projectionColumns.joinToString(", ") { "`$it`" }
            forEachLegacyBatch(
                db = db,
                tableName = "downloaded_song_catalog",
                projection = projection
            ) { cursor, _ ->
                val stableKey = cursorString(cursor, "stable_key")
                    ?: deriveCatalogStableKey(cursor)
                    ?: return@forEachLegacyBatch
                listOfNotNull(
                    cursorString(cursor, "file_path"),
                    cursorString(cursor, "media_uri"),
                    cursorString(cursor, "catalog_key")
                ).forEach { rawReference ->
                    val reference = rawReference.trim().takeIf(String::isNotBlank)
                        ?: return@forEach
                    stableKeysByReference.getOrPut(reference) { linkedSetOf() }
                        .add(stableKey)
                    normalizeLegacyBasename(reference)?.let { normalizedName ->
                        stableKeysByNormalizedName.getOrPut(
                            normalizedName
                        ) {
                            linkedSetOf()
                        }.add(stableKey)
                    }
                }
            }
            return LegacyCatalogLookup(
                stableKeysByReference = stableKeysByReference.mapValues { (_, keys) ->
                    keys.toSet()
                },
                stableKeysByNormalizedName = stableKeysByNormalizedName
                    .mapValues { (_, keys) -> keys.toSet() }
            )
        }

        private fun findCatalogStableKeyForAudioName(
            catalogLookup: LegacyCatalogLookup,
            audioName: String?
        ): String? {
            val normalizedName = normalizeLegacyBasename(audioName) ?: return null
            return catalogLookup.stableKeysByNormalizedName[normalizedName]?.singleOrNull()
        }

        private fun findCatalogStableKeyForSnapshotEntry(
            catalogLookup: LegacyCatalogLookup,
            reference: String?,
            mediaUri: String?,
            name: String?
        ): String? {
            val exactMatches = listOfNotNull(reference, mediaUri)
                .map(String::trim)
                .filter(String::isNotBlank)
                .flatMap { candidate ->
                    catalogLookup.stableKeysByReference[candidate].orEmpty()
                }
                .toSet()
            return when {
                exactMatches.size == 1 -> exactMatches.first()
                exactMatches.isNotEmpty() -> null
                else -> normalizeLegacyBasename(name)
                    ?.let(catalogLookup.stableKeysByNormalizedName::get)
                    ?.singleOrNull()
            }
        }

        private fun normalizeLegacyBasename(value: String?): String? {
            val normalized = value?.trim()?.trimEnd('/', '\\')
                ?.takeIf(String::isNotBlank)
                ?: return null
            return normalized
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringAfterLast(':')
                .takeIf(String::isNotBlank)
                ?.lowercase(Locale.ROOT)
        }

        private val LEGACY_DOWNLOAD_PROJECTION_TABLES = listOf(
            "download_pending_queue",
            "download_cancelled_key",
            "downloaded_song_catalog",
            "download_snapshot_entry",
            "download_snapshot_metadata",
            "managed_download_artifact"
        )

        private const val LEGACY_MIGRATION_BATCH_SIZE = 64
        private const val LEGACY_PAYLOAD_UPSERT_BATCH_SIZE = 48
        private const val LEGACY_ROW_ID_ALIAS = "__neriplayer_migration_rowid"

        private val LEGACY_CATALOG_LOOKUP_COLUMNS = listOf(
            "stable_key",
            "id",
            "file_path",
            "media_uri",
            "catalog_key"
        )

        private fun addIntegerColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String
        ) {
            val hasColumn = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                nameIndex >= 0 && generateSequence {
                    if (cursor.moveToNext()) cursor.getString(nameIndex) else null
                }.any { it == columnName }
            }
            if (!hasColumn) {
                db.execSQL(
                    "ALTER TABLE `$tableName` ADD COLUMN `$columnName` INTEGER"
                )
            }
        }

        private fun addTextColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String
        ) {
            val hasColumn = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                nameIndex >= 0 && generateSequence {
                    if (cursor.moveToNext()) cursor.getString(nameIndex) else null
                }.any { it == columnName }
            }
            if (!hasColumn) {
                db.execSQL(
                    "ALTER TABLE `$tableName` ADD COLUMN `$columnName` TEXT"
                )
            }
        }

        private fun createPlatformPlaylistCacheTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `platform_playlist_cache` (
                    `platform` TEXT NOT NULL,
                    `cache_key` TEXT NOT NULL,
                    `source_id` INTEGER,
                    `alternate_key` TEXT,
                    `kind` TEXT,
                    `title` TEXT,
                    `subtitle` TEXT,
                    `creator_name` TEXT,
                    `cover_url` TEXT,
                    `play_count` INTEGER,
                    `track_count` INTEGER NOT NULL,
                    `total_count` INTEGER NOT NULL,
                    `signature_primary` TEXT,
                    `signature_secondary` TEXT,
                    `has_more` INTEGER,
                    `saved_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`platform`, `cache_key`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_platform_playlist_cache_source_id`
                ON `platform_playlist_cache` (`platform`, `source_id`)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_platform_playlist_cache_saved_at`
                ON `platform_playlist_cache` (`platform`, `saved_at_ms`)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `platform_playlist_cache_track` (
                    `platform` TEXT NOT NULL,
                    `cache_key` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `item_id` INTEGER,
                    `item_key` TEXT,
                    `name` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `album_id` INTEGER,
                    `duration_ms` INTEGER NOT NULL,
                    `cover_url` TEXT,
                    `audio_id` TEXT,
                    `uploader_mid` INTEGER,
                    `added_at` INTEGER NOT NULL,
                    PRIMARY KEY(`platform`, `cache_key`, `position`),
                    FOREIGN KEY(`platform`, `cache_key`)
                    REFERENCES `platform_playlist_cache`(`platform`, `cache_key`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_platform_playlist_cache_track_item_id`
                ON `platform_playlist_cache_track`
                (`platform`, `cache_key`, `item_id`)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_platform_playlist_cache_track_item_key`
                ON `platform_playlist_cache_track`
                (`platform`, `cache_key`, `item_key`)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `platform_playlist_cache_track_artist` (
                    `platform` TEXT NOT NULL,
                    `cache_key` TEXT NOT NULL,
                    `track_position` INTEGER NOT NULL,
                    `artist_position` INTEGER NOT NULL,
                    `artist_id` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    PRIMARY KEY(
                        `platform`,
                        `cache_key`,
                        `track_position`,
                        `artist_position`
                    ),
                    FOREIGN KEY(`platform`, `cache_key`, `track_position`)
                    REFERENCES `platform_playlist_cache_track`(
                        `platform`,
                        `cache_key`,
                        `position`
                    )
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_platform_playlist_cache_artist_track`
                ON `platform_playlist_cache_track_artist`
                (`platform`, `cache_key`, `track_position`)
                """.trimIndent()
            )
        }
    }
}
