package moe.ouom.neriplayer.data.stats

import java.util.Calendar

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

fun PlaybackStatsPeriod.resolvePlaybackStatsTimeRange(
    nowMillis: Long = System.currentTimeMillis()
): PlaybackStatsTimeRange {
    if (this == PlaybackStatsPeriod.ALL) {
        return PlaybackStatsTimeRange(
            startInclusive = null,
            endExclusive = Long.MAX_VALUE
        )
    }

    val start = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        when (this@resolvePlaybackStatsTimeRange) {
            PlaybackStatsPeriod.DAY -> moveToDayStart()
            PlaybackStatsPeriod.WEEK -> moveToWeekStart()
            PlaybackStatsPeriod.MONTH -> moveToMonthStart()
            PlaybackStatsPeriod.YEAR -> moveToYearStart()
            PlaybackStatsPeriod.ALL -> Unit
        }
    }
    val end = start.clone() as Calendar
    when (this) {
        PlaybackStatsPeriod.DAY -> end.add(Calendar.DAY_OF_MONTH, 1)
        PlaybackStatsPeriod.WEEK -> end.add(Calendar.WEEK_OF_YEAR, 1)
        PlaybackStatsPeriod.MONTH -> end.add(Calendar.MONTH, 1)
        PlaybackStatsPeriod.YEAR -> end.add(Calendar.YEAR, 1)
        PlaybackStatsPeriod.ALL -> Unit
    }
    return PlaybackStatsTimeRange(
        startInclusive = start.timeInMillis,
        endExclusive = end.timeInMillis
    )
}

fun playbackStatsDayStartAt(millis: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        moveToDayStart()
    }.timeInMillis
}

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

private fun Calendar.moveToDayStart() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

private fun Calendar.moveToWeekStart() {
    firstDayOfWeek = Calendar.MONDAY
    moveToDayStart()
    while (get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) {
        add(Calendar.DAY_OF_MONTH, -1)
    }
}

private fun Calendar.moveToMonthStart() {
    set(Calendar.DAY_OF_MONTH, 1)
    moveToDayStart()
}

private fun Calendar.moveToYearStart() {
    set(Calendar.DAY_OF_YEAR, 1)
    moveToDayStart()
}

private fun TrackStat.mergeWith(bucket: PlaybackStatBucket): TrackStat {
    val latest = bucket.takeIf { it.lastPlayedAt >= lastPlayedAt }
    return copy(
        id = latest?.id ?: id,
        name = latest?.name ?: name,
        artist = latest?.artist ?: artist,
        album = latest?.album ?: album,
        albumId = latest?.albumId ?: albumId,
        coverUrl = latest?.coverUrl ?: coverUrl,
        durationMs = latest?.durationMs?.takeIf { it > 0L } ?: durationMs,
        totalListenMs = totalListenMs + bucket.totalListenMs,
        playCount = playCount + bucket.playCount,
        lastPlayedAt = maxOf(lastPlayedAt, bucket.lastPlayedAt),
        firstPlayedAt = minPositive(firstPlayedAt, bucket.firstPlayedAt),
        mediaUri = latest?.mediaUri ?: mediaUri,
        localFilePath = latest?.localFilePath ?: localFilePath,
        localFileName = latest?.localFileName ?: localFileName,
        customName = latest?.customName ?: customName,
        customArtist = latest?.customArtist ?: customArtist,
        customCoverUrl = latest?.customCoverUrl ?: customCoverUrl
    )
}

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
