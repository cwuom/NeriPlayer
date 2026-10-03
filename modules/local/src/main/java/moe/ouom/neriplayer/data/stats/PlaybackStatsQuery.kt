package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat

enum class PlaybackStatsSort { PLAY_COUNT, LISTEN_TIME, RECENT, FIRST_PLAYED }

data class PlaybackStatsQuery(
    val period: PlaybackStatsPeriod = PlaybackStatsPeriod.ALL,
    val sort: PlaybackStatsSort = PlaybackStatsSort.PLAY_COUNT,
    val minimumListenMs: Long = 0,
    val requirePlayCount: Boolean = false,
    val nowMillis: Long = System.currentTimeMillis()
)

data class PlaybackStatsSummary(
    val trackCount: Long = 0,
    val totalPlayCount: Long = 0,
    val totalListenMs: Long = 0,
    val usesLegacyBreakdown: Boolean = false,
    val hasAnyStats: Boolean = false
)

data class PlaybackStatsCursor(val sortValue: Long, val identityKey: String, val secondaryValue: Long = 0, val tertiaryValue: Long = 0)

data class PlaybackStatsPage(
    val tracks: List<TrackStat>,
    val nextCursor: PlaybackStatsCursor?,
    val previousCursor: PlaybackStatsCursor? = null
)

fun hotPlaybackStatsQuery(period: PlaybackStatsPeriod, nowMillis: Long = System.currentTimeMillis()) = PlaybackStatsQuery(
    period = period, sort = PlaybackStatsSort.PLAY_COUNT,
    minimumListenMs = if (period == PlaybackStatsPeriod.MONTH) 30L * 60_000 else 10L * 60_000,
    requirePlayCount = true, nowMillis = nowMillis
)

data class PlaybackStatsHotPlaylistPreview(val period: PlaybackStatsPeriod, val tracks: List<TrackStat>, val summary: PlaybackStatsSummary) {
    val totalPlayCount: Long get() = summary.totalPlayCount
    val trackCount: Long get() = summary.trackCount
}
