package moe.ouom.neriplayer.data.traffic

import moe.ouom.neriplayer.data.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.resolvePlaybackStatsTimeRange

enum class TrafficNetworkType {
    WIFI,
    MOBILE,
    ROAMING
}

enum class TrafficUsageSource {
    PLAYBACK,
    DOWNLOAD
}

data class TrafficStatsBucket(
    val dayStartAt: Long,
    val wifiBytes: Long = 0L,
    val mobileBytes: Long = 0L,
    val roamingBytes: Long = 0L,
    val playbackNetworkBytes: Long = 0L,
    val downloadNetworkBytes: Long = 0L,
    val cacheHitBytes: Long = 0L,
    val requestCount: Int = 0,
    val cacheHitCount: Int = 0
)

data class TrafficStatsSummary(
    val wifiBytes: Long = 0L,
    val mobileBytes: Long = 0L,
    val roamingBytes: Long = 0L,
    val playbackNetworkBytes: Long = 0L,
    val downloadNetworkBytes: Long = 0L,
    val cacheHitBytes: Long = 0L,
    val requestCount: Int = 0,
    val cacheHitCount: Int = 0
) {
    val networkBytes: Long
        get() = wifiBytes + mobileBytes + roamingBytes

    val measuredPlaybackBytes: Long
        get() = playbackNetworkBytes + cacheHitBytes

    val cacheHitRate: Float
        get() {
            val denominator = measuredPlaybackBytes
            if (denominator <= 0L) return 0f
            return cacheHitBytes.toFloat() / denominator.toFloat()
        }

    val hasTrafficData: Boolean
        get() = networkBytes > 0L || cacheHitBytes > 0L
}

internal fun aggregateTrafficStatsForPeriod(
    buckets: List<TrafficStatsBucket>,
    period: PlaybackStatsPeriod,
    nowMillis: Long = System.currentTimeMillis()
): TrafficStatsSummary {
    val range = period.resolvePlaybackStatsTimeRange(nowMillis)
    val startInclusive = range.startInclusive
    return buckets
        .asSequence()
        .filter { bucket ->
            startInclusive == null ||
                (bucket.dayStartAt >= startInclusive && bucket.dayStartAt < range.endExclusive)
        }
        .fold(TrafficStatsSummary()) { acc, bucket ->
            TrafficStatsSummary(
                wifiBytes = acc.wifiBytes + bucket.wifiBytes,
                mobileBytes = acc.mobileBytes + bucket.mobileBytes,
                roamingBytes = acc.roamingBytes + bucket.roamingBytes,
                playbackNetworkBytes = acc.playbackNetworkBytes + bucket.playbackNetworkBytes,
                downloadNetworkBytes = acc.downloadNetworkBytes + bucket.downloadNetworkBytes,
                cacheHitBytes = acc.cacheHitBytes + bucket.cacheHitBytes,
                requestCount = acc.requestCount + bucket.requestCount,
                cacheHitCount = acc.cacheHitCount + bucket.cacheHitCount
            )
        }
}
