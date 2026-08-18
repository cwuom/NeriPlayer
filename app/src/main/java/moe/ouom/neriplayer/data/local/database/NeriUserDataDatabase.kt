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
import moe.ouom.neriplayer.data.local.database.dao.LocalPlaylistPlaybackDao
import moe.ouom.neriplayer.data.local.database.dao.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.dao.FavoritePlaylistDao
import moe.ouom.neriplayer.data.local.database.dao.TrafficStatsDao
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.dao.PlaybackQueueDao
import moe.ouom.neriplayer.data.local.database.dao.BiliVideoSkipDao
import moe.ouom.neriplayer.data.local.database.dao.CoverUrlMappingDao
import moe.ouom.neriplayer.data.local.database.dao.DownloadRecoveryDao
import moe.ouom.neriplayer.data.local.database.dao.DownloadedSongCatalogDao
import moe.ouom.neriplayer.data.local.database.dao.DownloadSnapshotDao
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
import moe.ouom.neriplayer.data.local.database.entity.DownloadCancelledKeyEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadPendingQueueEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadedSongCatalogEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadSnapshotEntryEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadSnapshotMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackArtistEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackEntity

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
        DownloadPendingQueueEntity::class,
        DownloadCancelledKeyEntity::class,
        DownloadedSongCatalogEntity::class,
        CoverUrlMappingEntity::class,
        DownloadSnapshotEntryEntity::class,
        DownloadSnapshotMetadataEntity::class,
        PlatformPlaylistCacheEntity::class,
        PlatformPlaylistCacheTrackEntity::class,
        PlatformPlaylistCacheTrackArtistEntity::class
    ],
    version = 22,
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

    abstract fun downloadRecoveryDao(): DownloadRecoveryDao

    abstract fun downloadedSongCatalogDao(): DownloadedSongCatalogDao

    abstract fun coverUrlMappingDao(): CoverUrlMappingDao

    abstract fun downloadSnapshotDao(): DownloadSnapshotDao

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
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
                MIGRATION_20_21,
                MIGRATION_21_22
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

        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloaded_song_catalog` " +
                        "ADD COLUMN `matched_romanized_lyric` TEXT"
                )
                db.execSQL(
                    "ALTER TABLE `downloaded_song_catalog` " +
                        "ADD COLUMN `original_romanized_lyric` TEXT"
                )
                db.execSQL(
                    "ALTER TABLE `download_snapshot_metadata` " +
                        "ADD COLUMN `matched_romanized_lyric` TEXT"
                )
                db.execSQL(
                    "ALTER TABLE `download_snapshot_metadata` " +
                        "ADD COLUMN `original_romanized_lyric` TEXT"
                )
                db.execSQL(
                    "ALTER TABLE `download_pending_queue` " +
                        "ADD COLUMN `matched_romanized_lyric` TEXT"
                )
                db.execSQL(
                    "ALTER TABLE `download_pending_queue` " +
                        "ADD COLUMN `original_romanized_lyric` TEXT"
                )
            }
        }

        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addTextColumnIfMissing(
                    db = db,
                    tableName = "downloaded_song_catalog",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "downloaded_song_catalog",
                    columnName = "original_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_snapshot_metadata",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_snapshot_metadata",
                    columnName = "original_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_pending_queue",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_pending_queue",
                    columnName = "original_romanized_lyric"
                )
            }
        }

        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addTextColumnIfMissing(
                    db = db,
                    tableName = "downloaded_song_catalog",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "downloaded_song_catalog",
                    columnName = "original_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_snapshot_metadata",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_snapshot_metadata",
                    columnName = "original_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_pending_queue",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_pending_queue",
                    columnName = "original_romanized_lyric"
                )
            }
        }

        val MIGRATION_18_19: Migration = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addTextColumnIfMissing(
                    db = db,
                    tableName = "downloaded_song_catalog",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "downloaded_song_catalog",
                    columnName = "original_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_snapshot_metadata",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_snapshot_metadata",
                    columnName = "original_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_pending_queue",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_pending_queue",
                    columnName = "original_romanized_lyric"
                )
            }
        }

        val MIGRATION_19_20: Migration = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addTextColumnIfMissing(
                    db = db,
                    tableName = "downloaded_song_catalog",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "downloaded_song_catalog",
                    columnName = "original_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_snapshot_metadata",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_snapshot_metadata",
                    columnName = "original_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_pending_queue",
                    columnName = "matched_romanized_lyric"
                )
                addTextColumnIfMissing(
                    db = db,
                    tableName = "download_pending_queue",
                    columnName = "original_romanized_lyric"
                )
            }
        }

        /**
         * advances the schema identity after older builds wrote the same columns with a stale
         * Room identity hash; no user tables or rows are changed
         */
        val MIGRATION_20_21: Migration = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        /**
         * repairs databases that reached version 21 with the legacy Room identity hash
         */
        val MIGRATION_21_22: Migration = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
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
