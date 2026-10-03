package moe.ouom.neriplayer.data.local.database.dao.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsEventReceiptEntity

private const val PLAYBACK_STAT_PAYLOAD_COLUMNS = "id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url"

@Dao
interface PlaybackStatsSnapshotDao {
    @Upsert
    suspend fun upsertSnapshot(snapshot: PlaybackStatsSnapshotEntity)

    @Query("SELECT * FROM playback_stats_snapshot WHERE id = :id")
    suspend fun getSnapshot(id: String): PlaybackStatsSnapshotEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM playback_stat_snapshot_track WHERE snapshot_id = :id) OR " +
        "EXISTS(SELECT 1 FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id) OR " +
        "EXISTS(SELECT 1 FROM playback_stat_snapshot_counter WHERE snapshot_id = :id) OR " +
        "EXISTS(SELECT 1 FROM playback_stat_snapshot_daily_counter WHERE snapshot_id = :id) OR " +
        "EXISTS(SELECT 1 FROM playback_stat_snapshot_deleted_track WHERE snapshot_id = :id) OR " +
        "EXISTS(SELECT 1 FROM playback_stat_snapshot_deleted_bucket WHERE snapshot_id = :id)")
    suspend fun hasDiffRows(id: String): Boolean

    @RawQuery
    suspend fun queryCounterCandidates(query: SupportSQLiteQuery): List<PlaybackStatsSnapshotCounterEntity>

    @RawQuery
    suspend fun queryDailyCounterCandidates(query: SupportSQLiteQuery): List<PlaybackStatsSnapshotDailyCounterEntity>

    @Query("DELETE FROM playback_stats_snapshot WHERE id = :id")
    suspend fun deleteSnapshot(id: String)

    @Upsert
    suspend fun upsertDeletedTracks(rows: List<PlaybackStatsSnapshotDeletedTrackEntity>)

    @Upsert
    suspend fun upsertDeletedBuckets(rows: List<PlaybackStatsSnapshotDeletedBucketEntity>)

    @Query("SELECT identity_key FROM playback_stat_snapshot_deleted_track WHERE snapshot_id = :id AND identity_key IN (:keys)")
    suspend fun deletedTrackKeys(id: String, keys: List<String>): List<String>

    @Query("DELETE FROM playback_stat_snapshot_deleted_track WHERE snapshot_id = :id AND identity_key IN (:keys)")
    suspend fun restoreDeletedTracks(id: String, keys: List<String>)

    @Query("DELETE FROM playback_stat_snapshot_deleted_track WHERE snapshot_id = :id")
    suspend fun deleteTrackTombstones(id: String)

    @Query("DELETE FROM playback_stat_snapshot_deleted_bucket WHERE snapshot_id = :id")
    suspend fun deleteBucketTombstones(id: String)

    @Query("DELETE FROM playback_stat WHERE identity_key IN (SELECT identity_key FROM playback_stat_snapshot_deleted_track WHERE snapshot_id = :id)")
    suspend fun applyTrackTombstones(id: String)

    @Query("DELETE FROM playback_stat_bucket WHERE (day_start_at, identity_key) IN (SELECT day_start_at, identity_key FROM playback_stat_snapshot_deleted_bucket WHERE snapshot_id = :id)")
    suspend fun applyBucketTombstones(id: String)

    @Query("DELETE FROM playback_stat_counter_shard WHERE identity_key IN (SELECT identity_key FROM playback_stat_snapshot_track WHERE snapshot_id = :id)")
    suspend fun deleteReplacedCounters(id: String)

    @Query("DELETE FROM playback_stat_daily_counter_shard WHERE (day_start_at, identity_key) IN (SELECT day_start_at, identity_key FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id)")
    suspend fun deleteReplacedDailyCounters(id: String)

    @Query("UPDATE playback_stat SET (" + PLAYBACK_STAT_PAYLOAD_COLUMNS + ") = (SELECT " + PLAYBACK_STAT_PAYLOAD_COLUMNS + " FROM playback_stat_snapshot_track s WHERE s.snapshot_id = :id AND s.identity_key = playback_stat.identity_key) WHERE identity_key IN (SELECT identity_key FROM playback_stat_snapshot_track WHERE snapshot_id = :id)")
    suspend fun updateDiffTracks(id: String)

    @Query("INSERT INTO playback_stat (identity_key, " + PLAYBACK_STAT_PAYLOAD_COLUMNS + ") SELECT identity_key, " + PLAYBACK_STAT_PAYLOAD_COLUMNS + " FROM playback_stat_snapshot_track s WHERE snapshot_id = :id AND NOT EXISTS (SELECT 1 FROM playback_stat p WHERE p.identity_key = s.identity_key)")
    suspend fun insertDiffTracks(id: String)

    @Query("UPDATE playback_stat_bucket SET (" + PLAYBACK_STAT_PAYLOAD_COLUMNS + ") = (SELECT " + PLAYBACK_STAT_PAYLOAD_COLUMNS + " FROM playback_stat_snapshot_bucket s WHERE s.snapshot_id = :id AND s.day_start_at = playback_stat_bucket.day_start_at AND s.identity_key = playback_stat_bucket.identity_key) WHERE (day_start_at, identity_key) IN (SELECT day_start_at, identity_key FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id)")
    suspend fun updateDiffBuckets(id: String)

    @Query("INSERT INTO playback_stat_bucket (day_start_at, identity_key, " + PLAYBACK_STAT_PAYLOAD_COLUMNS + ") SELECT day_start_at, identity_key, " + PLAYBACK_STAT_PAYLOAD_COLUMNS + " FROM playback_stat_snapshot_bucket s WHERE snapshot_id = :id AND NOT EXISTS (SELECT 1 FROM playback_stat_bucket p WHERE p.day_start_at = s.day_start_at AND p.identity_key = s.identity_key)")
    suspend fun insertDiffBuckets(id: String)

    @Query("SELECT id FROM playback_stats_snapshot WHERE owner_process_id != :owner LIMIT :limit")
    suspend fun abandonedSnapshotIds(owner: String, limit: Int): List<String>

    @Query("UPDATE playback_stats_snapshot SET sealed = :sealed, cleared_at = :clearedAt, counter_epoch_started_at = MAX(counter_epoch_started_at, :clearedAt) WHERE id = :id")
    suspend fun updateSnapshotState(id: String, sealed: Boolean, clearedAt: Long)

    @Query("INSERT INTO playback_stat_snapshot_track (snapshot_id, identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url) SELECT :id, identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url FROM playback_stat")
    suspend fun freezeTrack(id: String)

    @Query("INSERT INTO playback_stat (identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url) SELECT identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url FROM playback_stat_snapshot_track WHERE snapshot_id = :id")
    suspend fun publishTrack(id: String)

    @Query("DELETE FROM playback_stat_snapshot_track WHERE snapshot_id = :id")
    suspend fun deleteTrack(id: String)

    @Query("INSERT INTO playback_stat_snapshot_bucket (snapshot_id, day_start_at, identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url) SELECT :id, day_start_at, identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url FROM playback_stat_bucket")
    suspend fun freezeBucket(id: String)

    @Query("INSERT INTO playback_stat_bucket (day_start_at, identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url) SELECT day_start_at, identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id")
    suspend fun publishBucket(id: String)

    @Query("DELETE FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id")
    suspend fun deleteBucket(id: String)

    @Query("INSERT INTO playback_stat_snapshot_counter (snapshot_id, identity_key, device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at) SELECT :id, identity_key, device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at FROM playback_stat_counter_shard")
    suspend fun freezeCounter(id: String)

    @Query("INSERT INTO playback_stat_counter_shard (identity_key, device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at) SELECT identity_key, device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at FROM playback_stat_snapshot_counter WHERE snapshot_id = :id")
    suspend fun publishCounter(id: String)

    @Query("DELETE FROM playback_stat_snapshot_counter WHERE snapshot_id = :id")
    suspend fun deleteCounter(id: String)

    // 仅供完整读取后的旧 JSON 导入清理孤立分片
    @Query("DELETE FROM playback_stat_snapshot_counter WHERE snapshot_id = :id AND NOT EXISTS " +
        "(SELECT 1 FROM playback_stat_snapshot_track parent WHERE parent.snapshot_id = :id " +
        "AND parent.identity_key = playback_stat_snapshot_counter.identity_key)")
    suspend fun deleteLegacyOrphanCounters(id: String)

    @Query("INSERT INTO playback_stat_snapshot_daily_counter (snapshot_id, day_start_at, identity_key, device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at) SELECT :id, day_start_at, identity_key, device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at FROM playback_stat_daily_counter_shard")
    suspend fun freezeDailyCounter(id: String)

    @Query("INSERT INTO playback_stat_daily_counter_shard (day_start_at, identity_key, device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at) SELECT day_start_at, identity_key, device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at FROM playback_stat_snapshot_daily_counter WHERE snapshot_id = :id")
    suspend fun publishDailyCounter(id: String)

    @Query("DELETE FROM playback_stat_snapshot_daily_counter WHERE snapshot_id = :id")
    suspend fun deleteDailyCounter(id: String)

    @Query("DELETE FROM playback_stat_snapshot_daily_counter WHERE snapshot_id = :id AND NOT EXISTS " +
        "(SELECT 1 FROM playback_stat_snapshot_bucket parent WHERE parent.snapshot_id = :id " +
        "AND parent.day_start_at = playback_stat_snapshot_daily_counter.day_start_at " +
        "AND parent.identity_key = playback_stat_snapshot_daily_counter.identity_key)")
    suspend fun deleteLegacyOrphanDailyCounters(id: String)

    @Query("SELECT * FROM playback_stat_snapshot_track WHERE snapshot_id = :id ORDER BY identity_key LIMIT :limit")
    suspend fun firstTrackPage(id: String, limit: Int): List<PlaybackStatsSnapshotTrackEntity>

    @Query("SELECT * FROM playback_stat_snapshot_track WHERE snapshot_id = :id AND identity_key > :after ORDER BY identity_key LIMIT :limit")
    suspend fun nextTrackPage(id: String, after: String, limit: Int): List<PlaybackStatsSnapshotTrackEntity>

    suspend fun trackPage(id: String, after: String?, limit: Int): List<PlaybackStatsSnapshotTrackEntity> =
        if (after == null) firstTrackPage(id, limit) else nextTrackPage(id, after, limit)

    @Query("SELECT * FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id ORDER BY day_start_at, identity_key LIMIT :limit")
    suspend fun firstBucketPage(id: String, limit: Int): List<PlaybackStatsSnapshotBucketEntity>

    @Query("SELECT * FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id AND (day_start_at, identity_key) > (:afterDay, :afterIdentity) ORDER BY day_start_at, identity_key LIMIT :limit")
    suspend fun nextBucketPage(id: String, afterDay: Long, afterIdentity: String, limit: Int): List<PlaybackStatsSnapshotBucketEntity>

    suspend fun bucketPage(id: String, afterDay: Long?, afterIdentity: String?, limit: Int): List<PlaybackStatsSnapshotBucketEntity> =
        if (afterDay == null) firstBucketPage(id, limit) else nextBucketPage(id, afterDay, requireNotNull(afterIdentity), limit)

    @Query("SELECT * FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id ORDER BY identity_key, day_start_at LIMIT :limit")
    suspend fun firstBucketIdentityPage(id: String, limit: Int): List<PlaybackStatsSnapshotBucketEntity>

    @Query("SELECT * FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id AND (identity_key, day_start_at) > (:afterIdentity, :afterDay) ORDER BY identity_key, day_start_at LIMIT :limit")
    suspend fun nextBucketIdentityPage(id: String, afterIdentity: String, afterDay: Long, limit: Int): List<PlaybackStatsSnapshotBucketEntity>

    suspend fun bucketIdentityPage(id: String, afterIdentity: String?, afterDay: Long?, limit: Int): List<PlaybackStatsSnapshotBucketEntity> =
        if (afterIdentity == null) firstBucketIdentityPage(id, limit) else nextBucketIdentityPage(id, afterIdentity, requireNotNull(afterDay), limit)

    @Query("SELECT * FROM playback_stat_snapshot_counter WHERE snapshot_id = :id AND identity_key IN (:keys) ORDER BY identity_key, device_id, epoch_started_at")
    suspend fun trackCounters(id: String, keys: List<String>): List<PlaybackStatsSnapshotCounterEntity>

    @Query("SELECT * FROM playback_stat_snapshot_daily_counter WHERE snapshot_id = :id AND day_start_at = :day AND identity_key IN (:keys) ORDER BY identity_key, device_id, epoch_started_at")
    suspend fun dailyCounters(id: String, day: Long, keys: List<String>): List<PlaybackStatsSnapshotDailyCounterEntity>

    @Query("SELECT * FROM playback_stat_snapshot_track WHERE snapshot_id = :id AND identity_key IN (:keys)")
    suspend fun tracksByKeys(id: String, keys: List<String>): List<PlaybackStatsSnapshotTrackEntity>

    @Query("SELECT * FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id AND day_start_at = :day AND identity_key IN (:keys)")
    suspend fun bucketsByKeys(id: String, day: Long, keys: List<String>): List<PlaybackStatsSnapshotBucketEntity>

    @Upsert
    suspend fun upsertTracks(rows: List<PlaybackStatsSnapshotTrackEntity>)

    @Upsert
    suspend fun upsertBuckets(rows: List<PlaybackStatsSnapshotBucketEntity>)

    @Upsert
    suspend fun upsertCounters(rows: List<PlaybackStatsSnapshotCounterEntity>)

    @Upsert
    suspend fun upsertDailyCounters(rows: List<PlaybackStatsSnapshotDailyCounterEntity>)

    @Query("DELETE FROM playback_stat_snapshot_counter WHERE snapshot_id = :id AND identity_key IN (:keys)")
    suspend fun deleteCountersByKeys(id: String, keys: List<String>)

    @Query("DELETE FROM playback_stat_snapshot_daily_counter WHERE snapshot_id = :id AND day_start_at = :day AND identity_key IN (:keys)")
    suspend fun deleteDailyCountersByKeys(id: String, day: Long, keys: List<String>)

    @Query("DELETE FROM playback_stat_snapshot_track WHERE snapshot_id = :id AND identity_key IN (:keys)")
    suspend fun deleteTracksByKeys(id: String, keys: List<String>)

    @Query("DELETE FROM playback_stat_snapshot_bucket WHERE snapshot_id = :id AND day_start_at = :day AND identity_key IN (:keys)")
    suspend fun deleteBucketsByKeys(id: String, day: Long, keys: List<String>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: PlaybackStatsEventReceiptEntity)

    @Query("SELECT * FROM playback_stats_event_receipt WHERE id = :id")
    suspend fun receipt(id: String): PlaybackStatsEventReceiptEntity?

    @Query("DELETE FROM playback_stats_event_receipt WHERE id NOT IN " +
        "(SELECT id FROM playback_stats_event_receipt ORDER BY rowid DESC LIMIT :retainedCount) " +
        "AND NOT EXISTS (SELECT 1 FROM playback_stats_pending_delta p WHERE p.id = playback_stats_event_receipt.id " +
        "OR (playback_stats_event_receipt.id LIKE 'local-playlist-play:%' AND p.id = substr(playback_stats_event_receipt.id, 21)))")
    suspend fun pruneCompletedReceipts(retainedCount: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPendingDelta(delta: PlaybackStatsPendingDeltaEntity)

    @Query("SELECT * FROM playback_stats_pending_delta WHERE id = :id")
    suspend fun pendingDelta(id: String): PlaybackStatsPendingDeltaEntity?

    @Query("SELECT * FROM playback_stats_pending_delta ORDER BY sequence LIMIT :limit")
    suspend fun pendingDeltas(limit: Int): List<PlaybackStatsPendingDeltaEntity>

    @Query("SELECT COUNT(*) FROM playback_stats_pending_delta")
    suspend fun pendingDeltaCount(): Long

    @Query("DELETE FROM playback_stats_pending_delta WHERE id = :id")
    suspend fun deletePendingDelta(id: String)

    @Query("DELETE FROM playback_stats_pending_delta")
    suspend fun deleteAllPendingDeltas()
}
