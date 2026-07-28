package moe.ouom.neriplayer.data.stats

private const val PLAYBACK_STATS_DAILY_RETENTION_DAYS = 400L
private const val PLAYBACK_STATS_MAX_DAILY_BUCKETS = 8_000
private const val MILLIS_PER_DAY = 86_400_000L

internal fun buildLegacyDailyStats(
    stats: List<TrackStat>,
    clearedAt: Long
): List<PlaybackStatBucket> {
    return stats.mapNotNull { stat ->
        if (!shouldKeepAfterClear(stat, clearedAt)) return@mapNotNull null

        val firstPlayedAt = stat.firstPlayedAt.takeIf { it > 0L } ?: stat.lastPlayedAt
        val lastPlayedAt = stat.lastPlayedAt
        if (firstPlayedAt <= 0L || lastPlayedAt <= 0L) return@mapNotNull null

        val firstDayStart = playbackStatsDayStartAt(firstPlayedAt)
        val lastDayStart = playbackStatsDayStartAt(lastPlayedAt)
        if (firstDayStart != lastDayStart) return@mapNotNull null

        stat.toPlaybackStatBucket(firstDayStart)
    }
}

internal fun recordPlaybackStatBucket(
    current: List<PlaybackStatBucket>,
    stat: TrackStat,
    listenedMs: Long,
    playCountIncrement: Int,
    playedAt: Long
): List<PlaybackStatBucket> {
    val dayStartAt = playbackStatsDayStartAt(playedAt)
    val existingIndex = current.indexOfFirst {
        it.dayStartAt == dayStartAt && it.identityKey == stat.identityKey
    }
    if (existingIndex < 0) {
        return current + stat.toPlaybackStatBucket(
            dayStartAt = dayStartAt,
            totalListenMs = listenedMs,
            playCount = playCountIncrement,
            firstPlayedAt = playedAt,
            lastPlayedAt = playedAt
        )
    }

    val existing = current[existingIndex]
    val updatedBucket = stat.toPlaybackStatBucket(
        dayStartAt = dayStartAt,
        totalListenMs = existing.totalListenMs + listenedMs,
        playCount = existing.playCount + playCountIncrement,
        firstPlayedAt = minPositiveTimestamp(existing.firstPlayedAt, playedAt),
        lastPlayedAt = maxOf(existing.lastPlayedAt, playedAt)
    )
    return current.toMutableList().apply {
        this[existingIndex] = updatedBucket
    }
}

internal fun trimPlaybackStatBuckets(
    buckets: List<PlaybackStatBucket>
): List<PlaybackStatBucket> {
    if (buckets.isEmpty()) return buckets
    val maxDayStartAt = buckets.maxOf { it.dayStartAt }
    val cutoff = maxDayStartAt - PLAYBACK_STATS_DAILY_RETENTION_DAYS * MILLIS_PER_DAY
    val windowed = buckets.filter { it.dayStartAt >= cutoff }
    if (windowed.size <= PLAYBACK_STATS_MAX_DAILY_BUCKETS) return windowed
    return windowed
        .sortedWith(
            compareByDescending<PlaybackStatBucket> { it.dayStartAt }
                .thenByDescending { it.playCount }
                .thenBy { it.identityKey }
        )
        .take(PLAYBACK_STATS_MAX_DAILY_BUCKETS)
}

private fun TrackStat.toPlaybackStatBucket(
    dayStartAt: Long,
    totalListenMs: Long = this.totalListenMs,
    playCount: Int = this.playCount,
    firstPlayedAt: Long = this.firstPlayedAt,
    lastPlayedAt: Long = this.lastPlayedAt
): PlaybackStatBucket {
    return PlaybackStatBucket(
        dayStartAt = dayStartAt,
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

private fun shouldKeepAfterClear(stat: TrackStat, playbackStatsClearedAt: Long): Boolean {
    if (playbackStatsClearedAt <= 0L) return true
    return stat.lastPlayedAt >= playbackStatsClearedAt
}

private fun minPositiveTimestamp(left: Long, right: Long): Long {
    return when {
        left <= 0L -> right
        right <= 0L -> left
        else -> minOf(left, right)
    }
}
