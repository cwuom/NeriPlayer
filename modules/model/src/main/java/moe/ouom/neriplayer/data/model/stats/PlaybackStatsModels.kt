package moe.ouom.neriplayer.data.model.stats

enum class PlaybackStatsPeriod {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ALL
}

data class PlaybackStatsTimeRange(
    val startInclusive: Long?,
    val endExclusive: Long
)

data class PlaybackStatBucket(
    val dayStartAt: Long,
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0L,
    val coverUrl: String?,
    val durationMs: Long,
    val totalListenMs: Long,
    val playCount: Int,
    val lastPlayedAt: Long,
    val firstPlayedAt: Long,
    val mediaUri: String?,
    val localFilePath: String?,
    val localFileName: String?,
    val customName: String?,
    val customArtist: String?,
    val customCoverUrl: String?,
    val identityKey: String
)

data class PlaybackStatsHotPlaylist(
    val period: PlaybackStatsPeriod,
    val tracks: List<TrackStat>,
    val totalPlayCount: Long,
    val totalListenMs: Long,
    val usesLegacyBreakdown: Boolean
)
