package moe.ouom.neriplayer.data.local.database.dao.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.stats.MAX_PLAYBACK_LISTENING_INTERVAL_MS
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackListeningIntervalEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHourTotal

data class PlaybackBucketHoursRow(
    val dayStartAt: Long, val identityKey: String,
    val hourlyTotals: List<SyncPlaybackHourTotal>, val localHourlyTotals: List<SyncPlaybackHourTotal>
)

private const val STORED_BUCKET_HOURS = "SELECT day_start_at AS dayStartAt, identity_key AS identityKey, " +
    "hourly_totals AS hourlyTotals, local_hourly_totals AS localHourlyTotals FROM playback_stat_bucket " +
    "WHERE (hourly_totals <> '[]' OR local_hourly_totals <> '[]')"

data class PlaybackStatsSummaryRow(
    val trackCount: Long,
    val totalPlayCount: Long,
    val totalListenMs: Long
)

data class PlaybackStatsHourRow(val localHour: Int, val listenedMs: Long)

data class PlaybackStatsRankRow(
    val name: String, val coverUrl: String?, val totalListenMs: Long, val totalPlayCount: Long,
    val trackCount: Long, val groupCount: Long
)

data class PlaybackStatsWeekdayRow(
    val weekday: Int, val activeDays: Long, val totalListenMs: Long
)

@Dao
interface PlaybackStatsDao {
    @Insert
    suspend fun insertListeningInterval(interval: PlaybackListeningIntervalEntity)

    @Query(
        "UPDATE playback_listening_interval SET end_at = :end WHERE id = (SELECT id FROM playback_listening_interval " +
            "WHERE identity_key = :key AND end_at = :start AND local_hour = :hour AND epoch_started_at = :epoch " +
            "AND start_at >= :end - $MAX_PLAYBACK_LISTENING_INTERVAL_MS LIMIT 1)"
    )
    suspend fun extendListeningInterval(key: String, start: Long, end: Long, hour: Int, epoch: Long): Int

    @Transaction
    suspend fun appendListeningInterval(interval: PlaybackListeningIntervalEntity) {
        if (extendListeningInterval(interval.identityKey, interval.startAt, interval.endAt,
                interval.localHour, interval.epochStartedAt) == 0) {
            insertListeningInterval(interval)
        }
    }

    @RawQuery
    suspend fun queryHourlyListening(query: SupportSQLiteQuery): List<PlaybackStatsHourRow>

    @Query("DELETE FROM playback_stat_hour WHERE epoch_started_at < :epoch")
    suspend fun clearHoursBeforeEpoch(epoch: Long)

    @Query("DELETE FROM playback_stat_hour WHERE identity_key IN (:keys)")
    suspend fun deleteTrackHours(keys: List<String>)

    @Query("DELETE FROM playback_stat_hour WHERE NOT EXISTS (SELECT 1 FROM playback_stat s WHERE s.identity_key = playback_stat_hour.identity_key)")
    suspend fun deleteOrphanHours()

    @Query("DELETE FROM playback_listening_interval WHERE epoch_started_at < :epoch")
    suspend fun clearIntervalsBeforeEpoch(epoch: Long)

    @Query("DELETE FROM playback_listening_interval WHERE identity_key IN (:keys)")
    suspend fun deleteTrackIntervals(keys: List<String>)

    @Query("DELETE FROM playback_listening_interval WHERE NOT EXISTS " +
        "(SELECT 1 FROM playback_stat s WHERE s.identity_key = playback_listening_interval.identity_key)")
    suspend fun deleteOrphanIntervals()

    @Query(
        "SELECT * FROM playback_stat " +
            "ORDER BY last_played_at DESC, identity_key ASC"
    )
    suspend fun getStats(): List<PlaybackStatEntity>

    @Query(
        "SELECT * FROM playback_stat_bucket " +
            "ORDER BY day_start_at ASC, identity_key ASC"
    )
    suspend fun getBuckets(): List<PlaybackStatBucketEntity>

    @Query(
        "SELECT * FROM playback_stat_counter_shard " +
            "ORDER BY identity_key ASC, device_id ASC, epoch_started_at ASC"
    )
    suspend fun getCounterShards(): List<PlaybackStatCounterShardEntity>

    @Query(
        "SELECT * FROM playback_stat_daily_counter_shard " +
            "ORDER BY day_start_at ASC, identity_key ASC, device_id ASC, " +
            "epoch_started_at ASC"
    )
    suspend fun getDailyCounterShards(): List<PlaybackStatDailyCounterShardEntity>

    @Upsert
    suspend fun upsertStats(stats: List<PlaybackStatEntity>)

    @Upsert
    suspend fun upsertBuckets(buckets: List<PlaybackStatBucketEntity>)

    @Upsert
    suspend fun upsertCounterShards(shards: List<PlaybackStatCounterShardEntity>)

    @Upsert
    suspend fun upsertDailyCounterShards(
        shards: List<PlaybackStatDailyCounterShardEntity>
    )

    @Query(
        "DELETE FROM playback_stat_counter_shard " +
            "WHERE identity_key IN (:identityKeys)"
    )
    suspend fun deleteCounterShards(identityKeys: List<String>)

    @Query(
        "DELETE FROM playback_stat_daily_counter_shard " +
            "WHERE identity_key IN (:identityKeys)"
    )
    suspend fun deleteDailyCounterShards(identityKeys: List<String>)

    @Query(
        "DELETE FROM playback_stat_bucket WHERE identity_key IN (:identityKeys)"
    )
    suspend fun deleteBuckets(identityKeys: List<String>)

    @Query("DELETE FROM playback_stat WHERE identity_key IN (:identityKeys)")
    suspend fun deleteStats(identityKeys: List<String>)

    @Query("DELETE FROM playback_stat_daily_counter_shard")
    suspend fun deleteAllDailyCounterShards()

    @Query("DELETE FROM playback_stat_counter_shard")
    suspend fun deleteAllCounterShards()

    @Query("DELETE FROM playback_stat_bucket")
    suspend fun deleteAllBuckets()

    @Query("DELETE FROM playback_stat")
    suspend fun deleteAllStats()

    @Query("SELECT * FROM playback_stat WHERE identity_key = :key")
    suspend fun getTrack(key: String): PlaybackStatEntity?

    @Query("SELECT * FROM playback_stat_bucket WHERE day_start_at = :day AND identity_key = :key")
    suspend fun getBucket(day: Long, key: String): PlaybackStatBucketEntity?

    @Query(STORED_BUCKET_HOURS)
    suspend fun storedBucketHours(): List<PlaybackBucketHoursRow>

    @Query("$STORED_BUCKET_HOURS AND identity_key IN (:keys)")
    suspend fun storedBucketHoursForTracks(keys: List<String>): List<PlaybackBucketHoursRow>

    @Query("SELECT * FROM playback_stat_counter_shard WHERE identity_key = :key ORDER BY device_id, epoch_started_at")
    suspend fun getTrackCounters(key: String): List<PlaybackStatCounterShardEntity>

    @Query("SELECT * FROM playback_stat_daily_counter_shard WHERE day_start_at = :day AND identity_key = :key ORDER BY device_id, epoch_started_at")
    suspend fun getBucketCounters(day: Long, key: String): List<PlaybackStatDailyCounterShardEntity>

    @Query("SELECT * FROM playback_stat ORDER BY identity_key LIMIT :limit")
    suspend fun firstIdentityPage(limit: Int): List<PlaybackStatEntity>

    @Query("SELECT * FROM playback_stat WHERE identity_key > :after ORDER BY identity_key LIMIT :limit")
    suspend fun nextIdentityPage(after: String, limit: Int): List<PlaybackStatEntity>

    suspend fun identityPage(after: String?, limit: Int): List<PlaybackStatEntity> =
        if (after == null) firstIdentityPage(limit) else nextIdentityPage(after, limit)

    @Query("SELECT * FROM playback_stat_bucket ORDER BY day_start_at, identity_key LIMIT :limit")
    suspend fun firstBucketPage(limit: Int): List<PlaybackStatBucketEntity>

    @Query("SELECT * FROM playback_stat_bucket WHERE (day_start_at, identity_key) > (:day, :identity) ORDER BY day_start_at, identity_key LIMIT :limit")
    suspend fun nextBucketPage(day: Long, identity: String, limit: Int): List<PlaybackStatBucketEntity>

    suspend fun bucketPage(day: Long?, identity: String?, limit: Int): List<PlaybackStatBucketEntity> =
        if (day == null) firstBucketPage(limit) else nextBucketPage(day, requireNotNull(identity), limit)

    @Query("SELECT * FROM playback_stat_bucket ORDER BY identity_key, day_start_at LIMIT :limit")
    suspend fun firstBucketIdentityPage(limit: Int): List<PlaybackStatBucketEntity>

    @Query("SELECT * FROM playback_stat_bucket WHERE (identity_key, day_start_at) > (:identity, :day) ORDER BY identity_key, day_start_at LIMIT :limit")
    suspend fun nextBucketIdentityPage(identity: String, day: Long, limit: Int): List<PlaybackStatBucketEntity>

    suspend fun bucketIdentityPage(identity: String?, day: Long?, limit: Int): List<PlaybackStatBucketEntity> =
        if (identity == null) firstBucketIdentityPage(limit) else nextBucketIdentityPage(identity, requireNotNull(day), limit)

    @Query("SELECT * FROM playback_stat WHERE identity_key IN (:keys)")
    suspend fun tracksByKeys(keys: List<String>): List<PlaybackStatEntity>

    @Query("SELECT * FROM playback_stat_counter_shard WHERE identity_key IN (:keys) ORDER BY identity_key, device_id, epoch_started_at")
    suspend fun trackCountersByKeys(keys: List<String>): List<PlaybackStatCounterShardEntity>

    @Query("SELECT * FROM playback_stat_daily_counter_shard WHERE day_start_at = :day AND identity_key IN (:keys) ORDER BY identity_key, device_id, epoch_started_at")
    suspend fun dailyCountersByKeys(day: Long, keys: List<String>): List<PlaybackStatDailyCounterShardEntity>

    @Query("SELECT * FROM playback_stat_counter_shard WHERE identity_key = :key AND device_id = :deviceId AND epoch_started_at = :epoch")
    suspend fun getOwnedCounter(key: String, deviceId: String, epoch: Long): PlaybackStatCounterShardEntity?

    @Query("SELECT * FROM playback_stat_daily_counter_shard WHERE day_start_at = :day AND identity_key = :key AND device_id = :deviceId AND epoch_started_at = :epoch")
    suspend fun getOwnedDailyCounter(day: Long, key: String, deviceId: String, epoch: Long): PlaybackStatDailyCounterShardEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM playback_stat_bucket LIMIT 1)")
    suspend fun hasBuckets(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM playback_stat LIMIT 1)")
    suspend fun hasStats(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM playback_stat s WHERE NOT EXISTS " +
        "(SELECT 1 FROM playback_stat_bucket b WHERE b.identity_key = s.identity_key) LIMIT 1)")
    suspend fun hasLegacyStats(): Boolean

    @Query("SELECT value FROM migration_metadata WHERE key = 'playback_stats_revision'")
    fun observeRevision(): Flow<String?>

    @Query("SELECT value FROM migration_metadata WHERE key = 'playback_stats_cleared_at'")
    fun observeClearedAt(): Flow<String?>

    @RawQuery
    suspend fun queryStats(query: SupportSQLiteQuery): List<PlaybackStatEntity>

    @RawQuery
    suspend fun queryRanking(query: SupportSQLiteQuery): List<PlaybackStatsRankRow>

    @RawQuery
    suspend fun queryWeekdays(query: SupportSQLiteQuery): List<PlaybackStatsWeekdayRow>

    @RawQuery
    suspend fun queryFirstListenAt(query: SupportSQLiteQuery): Long?

    @RawQuery
    suspend fun querySummary(query: SupportSQLiteQuery): PlaybackStatsSummaryRow
}
