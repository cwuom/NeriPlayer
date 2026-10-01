package moe.ouom.neriplayer.data.model.traffic

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
