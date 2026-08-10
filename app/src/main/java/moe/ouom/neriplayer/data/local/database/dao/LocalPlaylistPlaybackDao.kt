package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistPlaybackBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistPlaybackCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistPlaybackStatEntity

@Dao
internal interface LocalPlaylistPlaybackDao {
    @Query(
        "SELECT * FROM local_playlist_playback_stat " +
            "ORDER BY playlist_id ASC"
    )
    suspend fun getStats(): List<LocalPlaylistPlaybackStatEntity>

    @Query(
        "SELECT * FROM local_playlist_playback_bucket " +
            "ORDER BY playlist_id ASC, day_start_at ASC"
    )
    suspend fun getBuckets(): List<LocalPlaylistPlaybackBucketEntity>

    @Query(
        "SELECT * FROM local_playlist_playback_counter_shard " +
            "ORDER BY playlist_id ASC, day_start_at ASC, device_id ASC"
    )
    suspend fun getCounterShards(): List<LocalPlaylistPlaybackCounterShardEntity>

    @Upsert
    suspend fun upsertStats(stats: List<LocalPlaylistPlaybackStatEntity>)

    @Upsert
    suspend fun upsertBuckets(buckets: List<LocalPlaylistPlaybackBucketEntity>)

    @Upsert
    suspend fun upsertCounterShards(
        shards: List<LocalPlaylistPlaybackCounterShardEntity>
    )

    @Query(
        "DELETE FROM local_playlist_playback_counter_shard " +
            "WHERE playlist_id IN (:playlistIds)"
    )
    suspend fun deleteCounterShards(playlistIds: List<Long>)

    @Query(
        "DELETE FROM local_playlist_playback_bucket WHERE playlist_id IN (:playlistIds)"
    )
    suspend fun deleteBuckets(playlistIds: List<Long>)

    @Query(
        "DELETE FROM local_playlist_playback_stat WHERE playlist_id IN (:playlistIds)"
    )
    suspend fun deleteStats(playlistIds: List<Long>)

    @Query("DELETE FROM local_playlist_playback_counter_shard")
    suspend fun deleteAllCounterShards()

    @Query("DELETE FROM local_playlist_playback_bucket")
    suspend fun deleteAllBuckets()

    @Query("DELETE FROM local_playlist_playback_stat")
    suspend fun deleteAllStats()
}
