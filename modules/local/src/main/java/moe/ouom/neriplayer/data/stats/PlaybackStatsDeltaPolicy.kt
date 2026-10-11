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
        val previous = previousTrack(rows, delta.epochStartedAt)
        val previousTotals = previous.totals()
        val total = SyncPlaybackCounterArithmetic.add(previousTotals.listenMs, delta.listenedMs)
        val increment = delta.playCountIncrement ?: countSession(delta, metadata, previous, total)
        val session = DeltaTotals(delta.listenedMs, increment, delta.playedAt.coerceAtLeast(delta.epochStartedAt))
        val trackTotals = previousTotals + session
        val track = metadata.copy(
            durationMs = resolvedDuration(metadata, previous),
            totalListenMs = trackTotals.listenMs,
            playCount = trackTotals.playCount,
            firstPlayedAt = trackTotals.firstPlayedAt,
            lastPlayedAt = trackTotals.lastPlayedAt
        ).toEntity()
        val day = playbackStatsDayStartAt(delta.playedAt)
        val bucketTotals = currentBucket(rows, delta.epochStartedAt).totals() + session
        val bucket = PlaybackStatBucketEntity(day, track.identityKey, track.id, track.name, track.artist, track.album,
            track.albumId, track.coverUrl, track.durationMs, bucketTotals.listenMs, bucketTotals.playCount,
            bucketTotals.lastPlayedAt, bucketTotals.firstPlayedAt,
            track.mediaUri, track.localFilePath, track.localFileName, track.customName, track.customArtist, track.customCoverUrl)
        val counterTotals = rows.counter.totals() + session
        val counter = PlaybackStatCounterShardEntity(track.identityKey, delta.deviceId, delta.epochStartedAt,
            counterTotals.listenMs, counterTotals.playCount, counterTotals.firstPlayedAt, counterTotals.lastPlayedAt)
        val dailyTotals = rows.dailyCounter.totals() + session
        val daily = PlaybackStatDailyCounterShardEntity(day, track.identityKey, delta.deviceId, delta.epochStartedAt,
            dailyTotals.listenMs, dailyTotals.playCount, dailyTotals.firstPlayedAt, dailyTotals.lastPlayedAt)
        return PlaybackStatsDeltaRows(track, bucket, counter, daily)
    }

    private fun previousTrack(rows: PlaybackStatsDeltaRows, epochStartedAt: Long): TrackStat? =
        rows.track?.toDomain()?.takeUnless { shouldStartNewStatsEpoch(it, epochStartedAt) }

    private fun currentBucket(rows: PlaybackStatsDeltaRows, epochStartedAt: Long): PlaybackStatBucketEntity? =
        rows.bucket?.takeIf { it.firstPlayedAt >= epochStartedAt }

    private fun resolvedDuration(metadata: TrackStat, previous: TrackStat?): Long =
        if (metadata.durationMs > 0) metadata.durationMs else previous?.durationMs ?: 0

    /** Saturating listen and play totals plus the first and last play inside one row. */
    private data class DeltaTotals(val listenMs: Long, val playCount: Int, val firstPlayedAt: Long, val lastPlayedAt: Long) {
        constructor(listenMs: Long, playCount: Int, playedAt: Long) : this(listenMs, playCount, playedAt, playedAt)

        operator fun plus(session: DeltaTotals) = DeltaTotals(
            SyncPlaybackCounterArithmetic.add(listenMs, session.listenMs),
            SyncPlaybackCounterArithmetic.add(playCount, session.playCount),
            minPositive(firstPlayedAt, session.firstPlayedAt),
            maxOf(lastPlayedAt, session.lastPlayedAt)
        )
    }

    private val NO_TOTALS = DeltaTotals(0, 0, 0, 0)

    private fun TrackStat?.totals() = if (this == null) NO_TOTALS else DeltaTotals(totalListenMs, playCount, firstPlayedAt, lastPlayedAt)
    private fun PlaybackStatBucketEntity?.totals() = if (this == null) NO_TOTALS else DeltaTotals(totalListenMs, playCount, firstPlayedAt, lastPlayedAt)
    private fun PlaybackStatCounterShardEntity?.totals() = if (this == null) NO_TOTALS else DeltaTotals(totalListenMs, playCount, firstPlayedAt, lastPlayedAt)
    private fun PlaybackStatDailyCounterShardEntity?.totals() = if (this == null) NO_TOTALS else DeltaTotals(totalListenMs, playCount, firstPlayedAt, lastPlayedAt)

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
