package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatBucket

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
    val useRemoteMetadata = remote.lastPlayedAt > local.lastPlayedAt
    return local.copy(
        id = if (useRemoteMetadata) remoteBucket.id else local.id,
        name = if (useRemoteMetadata) remoteBucket.name else local.name,
        artist = if (useRemoteMetadata) remoteBucket.artist else local.artist,
        album = if (useRemoteMetadata) remoteBucket.album else local.album,
        albumId = if (useRemoteMetadata) remoteBucket.albumId else local.albumId,
        coverUrl = if (useRemoteMetadata) remoteBucket.coverUrl else local.coverUrl,
        durationMs = if (useRemoteMetadata) remoteBucket.durationMs else local.durationMs,
        totalListenMs = remoteBucket.totalListenMs,
        playCount = remoteBucket.playCount,
        lastPlayedAt = remoteBucket.lastPlayedAt,
        firstPlayedAt = remoteBucket.firstPlayedAt,
        mediaUri = if (useRemoteMetadata) remoteBucket.mediaUri else local.mediaUri
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
