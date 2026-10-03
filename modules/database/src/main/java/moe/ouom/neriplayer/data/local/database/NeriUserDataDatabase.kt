package moe.ouom.neriplayer.data.local.database

import android.app.Application
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import moe.ouom.neriplayer.data.local.database.dao.LocalPlaylistDao
import moe.ouom.neriplayer.data.local.database.dao.PlayHistoryDao
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaylistUsageDao
import moe.ouom.neriplayer.data.local.database.dao.stats.LocalPlaylistPlaybackDao
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsSnapshotDao
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsEventReceiptEntity
import moe.ouom.neriplayer.data.local.database.migration.library.PlaybackStatsPagedMigration
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.dao.FavoritePlaylistDao
import moe.ouom.neriplayer.data.local.database.dao.stats.TrafficStatsDao
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.dao.PlaybackQueueDao
import moe.ouom.neriplayer.data.local.database.dao.BiliVideoSkipDao
import moe.ouom.neriplayer.data.local.database.dao.CoverUrlMappingDao
import moe.ouom.neriplayer.data.local.database.dao.DownloadOperationDao
import moe.ouom.neriplayer.data.local.database.dao.DownloadBatchDao
import moe.ouom.neriplayer.data.local.database.dao.ManagedLibraryItemDao
import moe.ouom.neriplayer.data.local.database.dao.ManagedDownloadArtifactDao
import moe.ouom.neriplayer.data.local.database.dao.PlatformPlaylistCacheDao
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistSongEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.TrafficStatsBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlayHistoryEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaylistUsageCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaylistUsageEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.LocalPlaylistPlaybackBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.LocalPlaylistPlaybackCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.LocalPlaylistPlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
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
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.ManagedLibraryItemEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackArtistEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackEntity

import moe.ouom.neriplayer.data.local.database.migration.library.PlayHistoryMigration
import moe.ouom.neriplayer.data.local.database.migration.library.LyricSyncPersistenceMigration
import moe.ouom.neriplayer.data.local.database.migration.library.PlaylistUsageMigration
import moe.ouom.neriplayer.data.local.database.migration.library.LocalPlaylistPlaybackMigration
import moe.ouom.neriplayer.data.local.database.migration.library.PlaybackStatsMigration
import moe.ouom.neriplayer.data.local.database.migration.library.FavoritePlaylistMigration
import moe.ouom.neriplayer.data.local.database.migration.library.TrafficStatsMigration
import moe.ouom.neriplayer.data.local.database.migration.library.PlaybackQueueMigration
import moe.ouom.neriplayer.data.local.database.migration.platform.BiliVideoSkipMigration
import moe.ouom.neriplayer.data.local.database.migration.download.DownloadQueueMigration
import moe.ouom.neriplayer.data.local.database.migration.download.DownloadedSongCatalogMigration
import moe.ouom.neriplayer.data.local.database.migration.library.CoverUrlMappingMigration
import moe.ouom.neriplayer.data.local.database.migration.download.DownloadSnapshotMigration
import moe.ouom.neriplayer.data.local.database.migration.platform.PlatformPlaylistCacheMigration
import moe.ouom.neriplayer.data.local.database.migration.download.DownloadRomanizedLyricsMigration
import moe.ouom.neriplayer.data.local.database.migration.download.DownloadFinalizationMigration
import moe.ouom.neriplayer.data.local.database.migration.download.DownloadBatchMigration
import moe.ouom.neriplayer.data.local.database.migration.download.DownloadRecoveryCursorMigration

private const val NERI_USER_DATA_FINAL_VERSION = 20

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
        PlaybackStatsSnapshotEntity::class,
        PlaybackStatsSnapshotTrackEntity::class,
        PlaybackStatsSnapshotBucketEntity::class,
        PlaybackStatsSnapshotCounterEntity::class,
        PlaybackStatsSnapshotDailyCounterEntity::class,
        PlaybackStatsSnapshotDeletedTrackEntity::class,
        PlaybackStatsSnapshotDeletedBucketEntity::class,
        PlaybackStatsPendingDeltaEntity::class,
        PlaybackStatsEventReceiptEntity::class,
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
        DownloadBatchEntity::class,
        DownloadBatchMemberEntity::class,
        ManagedLibraryItemEntity::class,
        PlatformPlaylistCacheEntity::class,
        PlatformPlaylistCacheTrackEntity::class,
        PlatformPlaylistCacheTrackArtistEntity::class
    ],
    version = NERI_USER_DATA_FINAL_VERSION,
    exportSchema = true
)
abstract class NeriUserDataDatabase : RoomDatabase() {
    abstract fun localPlaylistDao(): LocalPlaylistDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun playlistUsageDao(): PlaylistUsageDao

    abstract fun localPlaylistPlaybackDao(): LocalPlaylistPlaybackDao

    abstract fun playbackStatsDao(): PlaybackStatsDao

    abstract fun playbackStatsSnapshotDao(): PlaybackStatsSnapshotDao

    abstract fun favoritePlaylistDao(): FavoritePlaylistDao

    abstract fun trafficStatsDao(): TrafficStatsDao

    abstract fun syncMetadataDao(): SyncMetadataDao

    abstract fun playbackQueueDao(): PlaybackQueueDao

    abstract fun biliVideoSkipDao(): BiliVideoSkipDao

    abstract fun downloadOperationDao(): DownloadOperationDao
    abstract fun downloadBatchDao(): DownloadBatchDao

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

        fun create(context: Context): NeriUserDataDatabase {
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
                MIGRATION_15_FINAL,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20
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

        val MIGRATION_1_2: Migration = PlayHistoryMigration
        val MIGRATION_2_3: Migration = PlaylistUsageMigration
        val MIGRATION_3_4: Migration = LocalPlaylistPlaybackMigration
        val MIGRATION_4_5: Migration = PlaybackStatsMigration
        val MIGRATION_5_6: Migration = FavoritePlaylistMigration
        val MIGRATION_6_7: Migration = TrafficStatsMigration
        val MIGRATION_7_8: Migration = PlaybackQueueMigration
        val MIGRATION_8_9: Migration = BiliVideoSkipMigration
        val MIGRATION_9_10: Migration = DownloadQueueMigration
        val MIGRATION_10_11: Migration = DownloadedSongCatalogMigration
        val MIGRATION_11_12: Migration = CoverUrlMappingMigration
        val MIGRATION_12_13: Migration = DownloadSnapshotMigration
        val MIGRATION_13_14: Migration = PlatformPlaylistCacheMigration
        val MIGRATION_14_15: Migration = DownloadRomanizedLyricsMigration
        val MIGRATION_15_FINAL: Migration = DownloadFinalizationMigration
        val MIGRATION_16_17: Migration = DownloadBatchMigration
        val MIGRATION_17_18: Migration = DownloadRecoveryCursorMigration
        val MIGRATION_18_19: Migration = LyricSyncPersistenceMigration
        val MIGRATION_19_20: Migration = PlaybackStatsPagedMigration

        const val FINAL_DB_VERSION = NERI_USER_DATA_FINAL_VERSION
    }
}
