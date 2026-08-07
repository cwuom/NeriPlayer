package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import moe.ouom.neriplayer.data.traffic.TrafficStatsBucket

@Entity(
    tableName = "traffic_stats_bucket",
    indices = [
        Index(
            value = ["day_start_at"],
            orders = [Index.Order.DESC],
            name = "index_traffic_stats_bucket_day"
        )
    ]
)
internal data class TrafficStatsBucketEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "day_start_at")
    val dayStartAt: Long,
    @ColumnInfo(name = "wifi_bytes")
    val wifiBytes: Long,
    @ColumnInfo(name = "mobile_bytes")
    val mobileBytes: Long,
    @ColumnInfo(name = "roaming_bytes")
    val roamingBytes: Long,
    @ColumnInfo(name = "playback_network_bytes")
    val playbackNetworkBytes: Long,
    @ColumnInfo(name = "download_network_bytes")
    val downloadNetworkBytes: Long,
    @ColumnInfo(name = "cache_hit_bytes")
    val cacheHitBytes: Long,
    @ColumnInfo(name = "request_count")
    val requestCount: Int,
    @ColumnInfo(name = "cache_hit_count")
    val cacheHitCount: Int
)

internal fun TrafficStatsBucket.toEntity(): TrafficStatsBucketEntity {
    return TrafficStatsBucketEntity(
        dayStartAt = dayStartAt,
        wifiBytes = wifiBytes,
        mobileBytes = mobileBytes,
        roamingBytes = roamingBytes,
        playbackNetworkBytes = playbackNetworkBytes,
        downloadNetworkBytes = downloadNetworkBytes,
        cacheHitBytes = cacheHitBytes,
        requestCount = requestCount,
        cacheHitCount = cacheHitCount
    )
}

internal fun TrafficStatsBucketEntity.toDomain(): TrafficStatsBucket {
    return TrafficStatsBucket(
        dayStartAt = dayStartAt,
        wifiBytes = wifiBytes,
        mobileBytes = mobileBytes,
        roamingBytes = roamingBytes,
        playbackNetworkBytes = playbackNetworkBytes,
        downloadNetworkBytes = downloadNetworkBytes,
        cacheHitBytes = cacheHitBytes,
        requestCount = requestCount,
        cacheHitCount = cacheHitCount
    )
}
