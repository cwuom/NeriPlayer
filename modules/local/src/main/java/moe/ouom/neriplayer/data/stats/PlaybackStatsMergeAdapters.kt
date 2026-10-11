package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket

internal fun shouldKeepTrackStatAfterClear(stat: TrackStat, playbackStatsClearedAt: Long): Boolean {
    if (playbackStatsClearedAt <= 0L) return true
    return stat.lastPlayedAt >= playbackStatsClearedAt
}

internal fun shouldKeepDailyBucketAfterClear(
    bucket: PlaybackStatBucket,
    playbackStatsClearedAt: Long
): Boolean {
    if (playbackStatsClearedAt <= 0L) return true
    return bucket.lastPlayedAt >= playbackStatsClearedAt
}

internal fun shouldStartNewStatsEpoch(
    stat: TrackStat,
    playbackStatsClearedAt: Long
): Boolean {
    if (playbackStatsClearedAt <= 0L) return false
    val firstPlayedAt = stat.firstPlayedAt.takeIf { it > 0L } ?: stat.lastPlayedAt
    return firstPlayedAt < playbackStatsClearedAt || stat.lastPlayedAt < playbackStatsClearedAt
}

internal fun mergeDailyBucket(
    local: PlaybackStatBucket,
    remote: SyncPlaybackStatBucket
): PlaybackStatBucket {
    val remoteBucket = remote.toPlaybackStatBucket()
    val metadata = if (remote.lastPlayedAt > local.lastPlayedAt) remoteBucket else local
    return local.copy(
        id = metadata.id,
        name = metadata.name,
        artist = metadata.artist,
        album = metadata.album,
        albumId = metadata.albumId,
        coverUrl = metadata.coverUrl,
        durationMs = metadata.durationMs,
        totalListenMs = remoteBucket.totalListenMs,
        playCount = remoteBucket.playCount,
        lastPlayedAt = remoteBucket.lastPlayedAt,
        firstPlayedAt = remoteBucket.firstPlayedAt,
        mediaUri = metadata.mediaUri
    )
}

internal fun SyncPlaybackStatBucket.toPlaybackStatBucket(): PlaybackStatBucket {
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
        localFilePath = null,
        localFileName = null,
        customName = null,
        customArtist = null,
        customCoverUrl = null,
        identityKey = identityKey
    )
}
