package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.resolvePlaybackStatsTimeRange
import moe.ouom.neriplayer.data.model.stats.TrackStat

import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsTimeRange
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsHotPlaylist


private const val HOT_PLAYLIST_MINUTE_MS = 60_000L
private const val WEEKLY_HOT_PLAYLIST_MIN_LISTEN_MS = 10 * HOT_PLAYLIST_MINUTE_MS
private const val MONTHLY_HOT_PLAYLIST_MIN_LISTEN_MS = 30 * HOT_PLAYLIST_MINUTE_MS

fun aggregatePlaybackStatBuckets(
    buckets: List<PlaybackStatBucket>,
    range: PlaybackStatsTimeRange
): List<TrackStat> {
    val startInclusive = range.startInclusive
    val aggregated = linkedMapOf<String, TrackStat>()
    buckets.asSequence()
        .filter { bucket ->
            startInclusive == null ||
                (bucket.dayStartAt >= startInclusive && bucket.dayStartAt < range.endExclusive)
        }
        .forEach { bucket ->
            val existing = aggregated[bucket.identityKey]
            aggregated[bucket.identityKey] = existing?.mergeWith(bucket) ?: bucket.toTrackStat()
        }
    return aggregated.values.toList()
}

fun aggregatePlaybackStatBucketsForPeriod(
    buckets: List<PlaybackStatBucket>,
    period: PlaybackStatsPeriod,
    nowMillis: Long = System.currentTimeMillis()
): List<TrackStat> {
    return aggregatePlaybackStatBuckets(
        buckets = buckets,
        range = period.resolvePlaybackStatsTimeRange(nowMillis)
    )
}

fun aggregatePlaybackStatsCompatForPeriod(
    stats: List<TrackStat>,
    period: PlaybackStatsPeriod,
    nowMillis: Long = System.currentTimeMillis()
): List<TrackStat> {
    if (period == PlaybackStatsPeriod.ALL) return stats

    val range = period.resolvePlaybackStatsTimeRange(nowMillis)
    val startInclusive = range.startInclusive ?: return stats
    return stats.filter { stat ->
        val firstPlayedAt = stat.firstPlayedAt.takeIf { it > 0L } ?: stat.lastPlayedAt
        firstPlayedAt >= startInclusive && stat.lastPlayedAt < range.endExclusive
    }
}

fun buildPlaybackStatsHotPlaylist(
    stats: List<TrackStat>,
    dailyStats: List<PlaybackStatBucket>,
    period: PlaybackStatsPeriod,
    nowMillis: Long = System.currentTimeMillis()
): PlaybackStatsHotPlaylist {
    val usesLegacyBreakdown = period != PlaybackStatsPeriod.ALL &&
        dailyStats.isEmpty() && stats.isNotEmpty()
    val periodStats = when {
        period == PlaybackStatsPeriod.ALL -> stats
        dailyStats.isEmpty() -> aggregatePlaybackStatsCompatForPeriod(
            stats = stats,
            period = period,
            nowMillis = nowMillis
        )
        else -> aggregatePlaybackStatBucketsForPeriod(
            buckets = dailyStats,
            period = period,
            nowMillis = nowMillis
        )
    }
    val minimumListenMs = period.hotPlaylistMinimumListenMs()
    val tracks = periodStats.asSequence()
        .filter { stat ->
            stat.playCount > 0 && stat.totalListenMs >= minimumListenMs
        }
        .sortedWith(
            compareByDescending<TrackStat> { stat -> stat.playCount }
                .thenByDescending { stat -> stat.totalListenMs }
                .thenByDescending { stat -> stat.lastPlayedAt }
                .thenBy { stat -> stat.identityKey }
        )
        .toList()

    return PlaybackStatsHotPlaylist(
        period = period,
        tracks = tracks,
        totalPlayCount = tracks.sumOf { stat -> stat.playCount.toLong() },
        totalListenMs = tracks.sumOf { stat -> stat.totalListenMs },
        usesLegacyBreakdown = usesLegacyBreakdown
    )
}

private fun PlaybackStatsPeriod.hotPlaylistMinimumListenMs(): Long = when (this) {
    PlaybackStatsPeriod.MONTH -> MONTHLY_HOT_PLAYLIST_MIN_LISTEN_MS
    else -> WEEKLY_HOT_PLAYLIST_MIN_LISTEN_MS
}

private fun TrackStat.mergeWith(bucket: PlaybackStatBucket): TrackStat {
    val summed = copy(
        totalListenMs = totalListenMs + bucket.totalListenMs,
        playCount = playCount + bucket.playCount,
        lastPlayedAt = maxOf(lastPlayedAt, bucket.lastPlayedAt),
        firstPlayedAt = minPositive(firstPlayedAt, bucket.firstPlayedAt)
    )
    return if (bucket.lastPlayedAt >= lastPlayedAt) summed.withMetadataFrom(bucket) else summed
}

/** The latest bucket names the track; its missing optional fields keep the values already known. */
private fun TrackStat.withMetadataFrom(latest: PlaybackStatBucket): TrackStat = copy(
    id = latest.id,
    name = latest.name,
    artist = latest.artist,
    album = latest.album,
    albumId = latest.albumId,
    coverUrl = preferred(latest.coverUrl, coverUrl),
    durationMs = if (latest.durationMs > 0L) latest.durationMs else durationMs,
    mediaUri = preferred(latest.mediaUri, mediaUri),
    localFilePath = preferred(latest.localFilePath, localFilePath),
    localFileName = preferred(latest.localFileName, localFileName),
    customName = preferred(latest.customName, customName),
    customArtist = preferred(latest.customArtist, customArtist),
    customCoverUrl = preferred(latest.customCoverUrl, customCoverUrl)
)

private fun preferred(value: String?, fallback: String?): String? = value ?: fallback

private fun PlaybackStatBucket.toTrackStat(): TrackStat {
    return TrackStat(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        coverUrl = coverUrl,
        durationMs = durationMs,
        totalListenMs = totalListenMs,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        localFileName = localFileName,
        customName = customName,
        customArtist = customArtist,
        customCoverUrl = customCoverUrl,
        identityKey = identityKey
    )
}

private fun minPositive(left: Long, right: Long): Long {
    return when {
        left <= 0L -> right
        right <= 0L -> left
        else -> minOf(left, right)
    }
}
