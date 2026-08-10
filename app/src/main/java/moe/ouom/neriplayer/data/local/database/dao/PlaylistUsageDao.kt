package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.PlaylistUsageCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistUsageEntity

@Dao
internal interface PlaylistUsageDao {
    @Query("SELECT * FROM playlist_usage ORDER BY last_opened DESC, usage_key ASC")
    suspend fun getEntries(): List<PlaylistUsageEntity>

    @Query("SELECT * FROM playlist_usage_counter_shard ORDER BY usage_key ASC, device_id ASC")
    suspend fun getCounterShards(): List<PlaylistUsageCounterShardEntity>

    @Upsert
    suspend fun upsertEntries(entries: List<PlaylistUsageEntity>)

    @Upsert
    suspend fun upsertCounterShards(shards: List<PlaylistUsageCounterShardEntity>)

    @Query("DELETE FROM playlist_usage WHERE usage_key IN (:usageKeys)")
    suspend fun deleteEntries(usageKeys: List<String>)

    @Query("DELETE FROM playlist_usage_counter_shard WHERE usage_key IN (:usageKeys)")
    suspend fun deleteCounterShards(usageKeys: List<String>)

    @Query("DELETE FROM playlist_usage_counter_shard")
    suspend fun deleteAllCounterShards()

    @Query("DELETE FROM playlist_usage")
    suspend fun deleteAllEntries()
}
