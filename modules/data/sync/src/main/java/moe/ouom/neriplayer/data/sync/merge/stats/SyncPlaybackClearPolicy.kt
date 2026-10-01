package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat

internal object SyncPlaybackClearPolicy {
    fun shouldKeepAfterClear(stat: SyncTrackStat, playbackStatsClearedAt: Long): Boolean {
        if (playbackStatsClearedAt <= 0L) return true
        return stat.lastPlayedAt >= playbackStatsClearedAt
    }

    fun shouldKeepAfterClear(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): Boolean {
        if (playbackStatsClearedAt <= 0L) return true
        return bucket.lastPlayedAt >= playbackStatsClearedAt
    }

    fun normalizeAfterClear(
        stat: SyncTrackStat,
        playbackStatsClearedAt: Long
    ): SyncTrackStat? {
        if (!shouldKeepAfterClear(stat, playbackStatsClearedAt)) return null
        val counterShards = SyncCounterShardPolicy.normalizeCounterShards(stat.counterShards)
        if (playbackStatsClearedAt <= 0L) return stat.copy(
            counterShards = counterShards
        )

        val normalizedFirstPlayedAt = firstPlayedAfterClear(stat.firstPlayedAt, stat.lastPlayedAt, playbackStatsClearedAt)
        val normalizedShards = SyncCounterShardPolicy.normalizeCounterShards(
            counterShards.filter { it.lastPlayedAt >= playbackStatsClearedAt }
        )
        return stat.copy(
            firstPlayedAt = normalizedFirstPlayedAt,
            counterBaseListenMs = if (counterShards.isEmpty()) stat.counterBaseListenMs else 0L,
            counterBasePlayCount = if (counterShards.isEmpty()) stat.counterBasePlayCount else 0,
            counterShards = normalizedShards
        )
    }

    fun normalizeBucketAfterClear(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): SyncPlaybackStatBucket? {
        if (!shouldKeepAfterClear(bucket, playbackStatsClearedAt)) return null
        val counterShards = SyncCounterShardPolicy.normalizeCounterShards(bucket.counterShards)
        if (playbackStatsClearedAt <= 0L) return bucket.copy(
            counterShards = counterShards
        )

        val normalizedFirstPlayedAt = firstPlayedAfterClear(bucket.firstPlayedAt, bucket.lastPlayedAt, playbackStatsClearedAt)
        val normalizedShards = SyncCounterShardPolicy.normalizeCounterShards(
            counterShards.filter { it.lastPlayedAt >= playbackStatsClearedAt }
        )
        return bucket.copy(
            firstPlayedAt = normalizedFirstPlayedAt,
            counterBaseListenMs = if (counterShards.isEmpty()) bucket.counterBaseListenMs else 0L,
            counterBasePlayCount = if (counterShards.isEmpty()) bucket.counterBasePlayCount else 0,
            counterShards = normalizedShards
        )
    }

    fun counterBaseListenMs(
        stat: SyncTrackStat,
        playbackStatsClearedAt: Long
    ): Long {
        if (playbackStatsClearedAt > 0L && stat.counterShards.isNotEmpty()) return 0L
        if (stat.counterShards.isEmpty() && stat.counterBaseListenMs == 0L) {
            return stat.totalListenMs.coerceAtLeast(0L)
        }
        return stat.counterBaseListenMs.coerceAtLeast(0L)
    }

    fun counterBaseListenMs(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): Long {
        if (playbackStatsClearedAt > 0L && bucket.counterShards.isNotEmpty()) return 0L
        if (bucket.counterShards.isEmpty() && bucket.counterBaseListenMs == 0L) {
            return bucket.totalListenMs.coerceAtLeast(0L)
        }
        return bucket.counterBaseListenMs.coerceAtLeast(0L)
    }

    fun counterBasePlayCount(
        stat: SyncTrackStat,
        playbackStatsClearedAt: Long
    ): Int {
        if (playbackStatsClearedAt > 0L && stat.counterShards.isNotEmpty()) return 0
        if (stat.counterShards.isEmpty() && stat.counterBasePlayCount == 0) {
            return stat.playCount.coerceAtLeast(0)
        }
        return stat.counterBasePlayCount.coerceAtLeast(0)
    }

    fun counterBasePlayCount(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): Int {
        if (playbackStatsClearedAt > 0L && bucket.counterShards.isNotEmpty()) return 0
        if (bucket.counterShards.isEmpty() && bucket.counterBasePlayCount == 0) {
            return bucket.playCount.coerceAtLeast(0)
        }
        return bucket.counterBasePlayCount.coerceAtLeast(0)
    }

    private fun firstPlayedAfterClear(first: Long, last: Long, clearedAt: Long): Long =
        if (first >= clearedAt && first <= last) first else last
}
