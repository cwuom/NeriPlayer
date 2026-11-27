package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.TrackStat

data class PlaybackStatsRank(
    val name: String,
    val trackCount: Long,
    val totalListenMs: Long,
    val totalPlayCount: Long,
    val share: Double,
    val coverUrl: String? = null
)

data class PlaybackStatsInsights(
    val summary: PlaybackStatsSummary = PlaybackStatsSummary(),
    val topTrack: TrackStat? = null,
    val earliestListenAt: Long? = null,
    val artists: List<PlaybackStatsRank> = emptyList(),
    val albums: List<PlaybackStatsRank> = emptyList(),
    val artistCount: Long = 0,
    val albumCount: Long = 0,
    val activeDays: Long = 0,
    val weekdayListenMs: List<Long> = List(7) { 0L },
    val averageDailyListenMs: Long = 0,
    val averageListenMsPerPlay: Long = 0
)
