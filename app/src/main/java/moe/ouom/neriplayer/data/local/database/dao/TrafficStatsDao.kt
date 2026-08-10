package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.TrafficStatsBucketEntity

@Dao
internal interface TrafficStatsDao {
    @Query(
        "SELECT * FROM traffic_stats_bucket " +
            "ORDER BY day_start_at ASC"
    )
    suspend fun getAll(): List<TrafficStatsBucketEntity>

    @Upsert
    suspend fun upsert(buckets: List<TrafficStatsBucketEntity>)

    @Query(
        "DELETE FROM traffic_stats_bucket WHERE day_start_at IN (:dayStartAt)"
    )
    suspend fun delete(dayStartAt: List<Long>)

    @Query("DELETE FROM traffic_stats_bucket")
    suspend fun deleteAll()
}
