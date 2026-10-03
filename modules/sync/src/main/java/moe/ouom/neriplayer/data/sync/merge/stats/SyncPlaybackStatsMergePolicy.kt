package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat

object SyncPlaybackStatsMergePolicy {
    fun merge(
        local: List<SyncTrackStat>,
        remote: List<SyncTrackStat>,
        playbackStatsClearedAt: Long
    ): List<SyncTrackStat> {
        val merged = linkedMapOf<String, SyncTrackStat>()
        for (stat in local.asSequence() + remote.asSequence()) {
            mergeTrack(merged[stat.identityKey], stat, playbackStatsClearedAt)?.let { merged[stat.identityKey] = it }
        }
        return merged.values.toList()
    }

    fun mergeBuckets(
        local: List<SyncPlaybackStatBucket>,
        remote: List<SyncPlaybackStatBucket>,
        playbackStatsClearedAt: Long
    ): List<SyncPlaybackStatBucket> {
        val merged = linkedMapOf<Pair<Long, String>, SyncPlaybackStatBucket>()
        for (bucket in local.asSequence() + remote.asSequence()) {
            val key = bucket.dayStartAt to bucket.identityKey
            mergeBucket(merged[key], bucket, playbackStatsClearedAt)?.let { merged[key] = it }
        }
        return merged.values.toList()
    }

    /**
     * 单调抬升兜底: 把"总"聚合计数抬升到不低于同曲分桶之和, 只增不减, 幂等
     * 与桌面端 lift_stats_to_bucket_totals 对齐, 用于消除"年 > 总"口径分裂
     * 合并语义整体基于 max (merge 用 max, lift 用 max) , 因此即使两端实现略有差异也收敛, 不产生回声
     */
    fun liftStatsToBucketTotals(stats: List<SyncTrackStat>, buckets: List<SyncPlaybackStatBucket>): List<SyncTrackStat> =
        SyncPlaybackBucketTotalsPolicy.liftStatsToBucketTotals(stats, buckets)

    /** 合并统计收尾结果，保留完整曲目统计与日桶 */
    data class FinalizedPlaybackStats(
        val stats: List<SyncTrackStat>,
        val buckets: List<SyncPlaybackStatBucket>
    )

    // 分片负责控制传输大小，统计合并不能通过丢弃旧记录控制容量
    fun finalizeMergedStats(
        mergedStats: List<SyncTrackStat>,
        mergedBuckets: List<SyncPlaybackStatBucket>
    ): FinalizedPlaybackStats {
        val liftedStats = liftStatsToBucketTotals(mergedStats, mergedBuckets)
        return FinalizedPlaybackStats(
            stats = SyncPlaybackStatsOrderPolicy.stats(liftedStats),
            buckets = SyncPlaybackStatsOrderPolicy.buckets(mergedBuckets)
        )
    }

    fun mergeTrack(existing: SyncTrackStat?, incoming: SyncTrackStat, clearedAt: Long): SyncTrackStat? {
        require(existing == null || existing.identityKey == incoming.identityKey)
        val left = existing?.let { SyncPlaybackClearPolicy.normalizeAfterClear(it, clearedAt) }
        val right = SyncPlaybackClearPolicy.normalizeAfterClear(incoming, clearedAt)
        return when {
            left == null -> right
            right == null -> left
            else -> mergeStat(left, right)
        }
    }

    fun mergeBucket(existing: SyncPlaybackStatBucket?, incoming: SyncPlaybackStatBucket, clearedAt: Long): SyncPlaybackStatBucket? {
        require(existing == null || (existing.dayStartAt == incoming.dayStartAt && existing.identityKey == incoming.identityKey))
        val left = existing?.let { SyncPlaybackClearPolicy.normalizeBucketAfterClear(it, clearedAt) }
        val right = SyncPlaybackClearPolicy.normalizeBucketAfterClear(incoming, clearedAt)
        return when {
            left == null -> right
            right == null -> left
            else -> mergeBucket(left, right)
        }
    }

    private fun mergeStat(existing: SyncTrackStat, stat: SyncTrackStat): SyncTrackStat {
        val newer = SyncPlaybackStatsMetadataPolicy.select(existing, stat)
        val counter = SyncPlaybackCounterMergePolicy.merge(
            existingTotalListenMs = existing.totalListenMs,
            existingPlayCount = existing.playCount,
            existingFirstPlayedAt = existing.firstPlayedAt,
            existingLastPlayedAt = existing.lastPlayedAt,
            existingBaseListenMs = SyncPlaybackClearPolicy.counterBaseListenMs(existing, playbackStatsClearedAt = 0L),
            existingBasePlayCount = SyncPlaybackClearPolicy.counterBasePlayCount(existing, playbackStatsClearedAt = 0L),
            existingShards = existing.counterShards,
            incomingTotalListenMs = stat.totalListenMs,
            incomingPlayCount = stat.playCount,
            incomingFirstPlayedAt = stat.firstPlayedAt,
            incomingLastPlayedAt = stat.lastPlayedAt,
            incomingBaseListenMs = SyncPlaybackClearPolicy.counterBaseListenMs(stat, playbackStatsClearedAt = 0L),
            incomingBasePlayCount = SyncPlaybackClearPolicy.counterBasePlayCount(stat, playbackStatsClearedAt = 0L),
            incomingShards = stat.counterShards
        )
        return SyncTrackStat(
            identityKey = stat.identityKey,
            name = newer.name,
            artist = newer.artist,
            album = newer.album,
            totalListenMs = counter.totalListenMs,
            playCount = counter.playCount,
            lastPlayedAt = counter.lastPlayedAt,
            firstPlayedAt = counter.firstPlayedAt,
            coverUrl = newer.coverUrl,
            durationMs = newer.durationMs,
            mediaUri = newer.mediaUri,
            id = newer.id,
            albumId = newer.albumId,
            counterBaseListenMs = counter.baseListenMs,
            counterBasePlayCount = counter.basePlayCount,
            counterShards = counter.shards
        )
    }

    private fun mergeBucket(
        existing: SyncPlaybackStatBucket,
        bucket: SyncPlaybackStatBucket
    ): SyncPlaybackStatBucket {
        val newer = SyncPlaybackStatsMetadataPolicy.select(existing, bucket)
        val counter = SyncPlaybackCounterMergePolicy.merge(
            existingTotalListenMs = existing.totalListenMs,
            existingPlayCount = existing.playCount,
            existingFirstPlayedAt = existing.firstPlayedAt,
            existingLastPlayedAt = existing.lastPlayedAt,
            existingBaseListenMs = SyncPlaybackClearPolicy.counterBaseListenMs(existing, playbackStatsClearedAt = 0L),
            existingBasePlayCount = SyncPlaybackClearPolicy.counterBasePlayCount(existing, playbackStatsClearedAt = 0L),
            existingShards = existing.counterShards,
            incomingTotalListenMs = bucket.totalListenMs,
            incomingPlayCount = bucket.playCount,
            incomingFirstPlayedAt = bucket.firstPlayedAt,
            incomingLastPlayedAt = bucket.lastPlayedAt,
            incomingBaseListenMs = SyncPlaybackClearPolicy.counterBaseListenMs(bucket, playbackStatsClearedAt = 0L),
            incomingBasePlayCount = SyncPlaybackClearPolicy.counterBasePlayCount(bucket, playbackStatsClearedAt = 0L),
            incomingShards = bucket.counterShards
        )
        return SyncPlaybackStatBucket(
            dayStartAt = existing.dayStartAt,
            identityKey = existing.identityKey,
            name = newer.name,
            artist = newer.artist,
            album = newer.album,
            totalListenMs = counter.totalListenMs,
            playCount = counter.playCount,
            lastPlayedAt = counter.lastPlayedAt,
            firstPlayedAt = counter.firstPlayedAt,
            coverUrl = newer.coverUrl,
            durationMs = newer.durationMs,
            mediaUri = newer.mediaUri,
            id = newer.id,
            albumId = newer.albumId,
            counterBaseListenMs = counter.baseListenMs,
            counterBasePlayCount = counter.basePlayCount,
            counterShards = counter.shards
        )
    }

}
