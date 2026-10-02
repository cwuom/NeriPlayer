package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard

internal object SyncPlaybackClearPolicy {
    fun normalizeAfterClear(
        stat: SyncTrackStat,
        playbackStatsClearedAt: Long
    ): SyncTrackStat? {
        val normalizedShards = retainedShards(stat.counterShards, stat.firstPlayedAt, stat.lastPlayedAt, playbackStatsClearedAt)
            ?: return null
        if (playbackStatsClearedAt <= 0L || normalizedShards.isEmpty()) return stat.copy(counterShards = normalizedShards)
        val totals = totals(normalizedShards)
        return stat.copy(
            totalListenMs = totals.listenMs,
            playCount = totals.playCount,
            firstPlayedAt = totals.firstPlayedAt,
            lastPlayedAt = totals.lastPlayedAt,
            counterBaseListenMs = 0L,
            counterBasePlayCount = 0,
            counterShards = normalizedShards
        )
    }

    fun normalizeBucketAfterClear(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): SyncPlaybackStatBucket? {
        val normalizedShards = retainedShards(bucket.counterShards, bucket.firstPlayedAt, bucket.lastPlayedAt, playbackStatsClearedAt)
            ?: return null
        if (playbackStatsClearedAt <= 0L || normalizedShards.isEmpty()) return bucket.copy(counterShards = normalizedShards)
        val totals = totals(normalizedShards)
        return bucket.copy(
            totalListenMs = totals.listenMs,
            playCount = totals.playCount,
            firstPlayedAt = totals.firstPlayedAt,
            lastPlayedAt = totals.lastPlayedAt,
            counterBaseListenMs = 0L,
            counterBasePlayCount = 0,
            counterShards = normalizedShards
        )
    }

    private fun retainedShards(shards: List<SyncPlaybackCounterShard>, first: Long, last: Long, clearedAt: Long): List<SyncPlaybackCounterShard>? {
        val normalized = SyncCounterShardPolicy.normalizeCounterShards(shards)
        if (clearedAt <= 0L) return normalized
        if (last < clearedAt) return null
        if (normalized.isEmpty()) return normalized.takeIf { first in clearedAt..last }
        return currentEpochShards(normalized, clearedAt)
    }

    // 旧 epoch 的累计值无法分离清除前后增量，不能根据一次新播放把旧总量带回来
    private fun currentEpochShards(shards: List<SyncPlaybackCounterShard>, clearedAt: Long): List<SyncPlaybackCounterShard>? =
        shards.filter { it.epochStartedAt >= clearedAt && it.firstPlayedAt >= clearedAt }.takeIf { it.isNotEmpty() }

    private data class Totals(val listenMs: Long, val playCount: Int, val firstPlayedAt: Long, val lastPlayedAt: Long)

    private fun totals(shards: List<SyncPlaybackCounterShard>): Totals {
        var listenMs = 0L
        var playCount = 0
        var first = Long.MAX_VALUE
        var last = 0L
        for (shard in shards) {
            listenMs = SyncPlaybackCounterArithmetic.add(listenMs, shard.totalListenMs)
            playCount = SyncPlaybackCounterArithmetic.add(playCount, shard.playCount)
            first = minOf(first, shard.firstPlayedAt)
            last = maxOf(last, shard.lastPlayedAt)
        }
        return Totals(listenMs, playCount, first, last)
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

}
