package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatEntity

@Dao
internal interface PlaybackStatsDao {
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
}
