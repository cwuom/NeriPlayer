package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsDeltaRows
import moe.ouom.neriplayer.data.local.database.store.stats.toDomain
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackCounterArithmetic

internal object PlaybackStatsDeltaPolicy {
    private const val MIN_LISTEN_MS_FOR_PLAY_COUNT = 30_000L

    fun apply(delta: PlaybackStatsPendingDeltaEntity, metadata: TrackStat, rows: PlaybackStatsDeltaRows): PlaybackStatsDeltaRows {
        val previous = rows.track?.toDomain()?.takeUnless { shouldStartNewStatsEpoch(it, delta.epochStartedAt) }
        val total = SyncPlaybackCounterArithmetic.add(previous?.totalListenMs ?: 0, delta.listenedMs)
        val increment = delta.playCountIncrement ?: countSession(delta, metadata, previous, total)
        val playedAt = delta.playedAt.coerceAtLeast(delta.epochStartedAt)
        val track = metadata.copy(
            durationMs = metadata.durationMs.takeIf { it > 0 } ?: previous?.durationMs ?: 0,
            totalListenMs = total,
            playCount = SyncPlaybackCounterArithmetic.add(previous?.playCount ?: 0, increment),
            firstPlayedAt = minPositive(previous?.firstPlayedAt ?: 0, playedAt),
            lastPlayedAt = maxOf(previous?.lastPlayedAt ?: 0, playedAt)
        ).toEntity()
        val existingBucket = rows.bucket?.takeIf { it.firstPlayedAt >= delta.epochStartedAt }
        val day = playbackStatsDayStartAt(delta.playedAt)
        val bucket = PlaybackStatBucketEntity(day, track.identityKey, track.id, track.name, track.artist, track.album,
            track.albumId, track.coverUrl, track.durationMs,
            SyncPlaybackCounterArithmetic.add(existingBucket?.totalListenMs ?: 0, delta.listenedMs),
            SyncPlaybackCounterArithmetic.add(existingBucket?.playCount ?: 0, increment),
            maxOf(existingBucket?.lastPlayedAt ?: 0, playedAt), minPositive(existingBucket?.firstPlayedAt ?: 0, playedAt),
            track.mediaUri, track.localFilePath, track.localFileName, track.customName, track.customArtist, track.customCoverUrl)
        val counter = PlaybackStatCounterShardEntity(track.identityKey, delta.deviceId, delta.epochStartedAt,
            SyncPlaybackCounterArithmetic.add(rows.counter?.totalListenMs ?: 0, delta.listenedMs),
            SyncPlaybackCounterArithmetic.add(rows.counter?.playCount ?: 0, increment),
            minPositive(rows.counter?.firstPlayedAt ?: 0, playedAt), maxOf(rows.counter?.lastPlayedAt ?: 0, playedAt))
        val daily = PlaybackStatDailyCounterShardEntity(day, track.identityKey, delta.deviceId, delta.epochStartedAt,
            SyncPlaybackCounterArithmetic.add(rows.dailyCounter?.totalListenMs ?: 0, delta.listenedMs),
            SyncPlaybackCounterArithmetic.add(rows.dailyCounter?.playCount ?: 0, increment),
            minPositive(rows.dailyCounter?.firstPlayedAt ?: 0, playedAt), maxOf(rows.dailyCounter?.lastPlayedAt ?: 0, playedAt))
        return PlaybackStatsDeltaRows(track, bucket, counter, daily)
    }

    private fun countSession(delta: PlaybackStatsPendingDeltaEntity, metadata: TrackStat, previous: TrackStat?, total: Long): Int {
        if (previous == null) return if (delta.listenedMs >= MIN_LISTEN_MS_FOR_PLAY_COUNT) 1 else 0
        val duration = metadata.durationMs.takeIf { it > 0 } ?: previous.durationMs
        val previousFull = previous.totalListenMs / maxOf(previous.durationMs, 1)
        val nextFull = total / maxOf(duration, 1)
        return if (delta.listenedMs >= MIN_LISTEN_MS_FOR_PLAY_COUNT || nextFull > previousFull) 1 else 0
    }

    private fun minPositive(left: Long, right: Long): Long = when {
        left <= 0 -> right
        right <= 0 -> left
        else -> minOf(left, right)
    }
}
