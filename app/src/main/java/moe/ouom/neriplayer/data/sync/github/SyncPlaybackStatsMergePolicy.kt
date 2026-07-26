package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.model.SyncTrackStat

internal object SyncPlaybackStatsMergePolicy {
    // 裁剪阈值必须与桌面端 sync/merge.rs 逐字一致，否则双端会互相把对方裁掉的数据补回来形成回声上传
    // playbackStats 上限
    const val MAX_TRACK_STATS = 2000
    // playbackStatBuckets 保留窗口（天）
    const val BUCKET_RETENTION_DAYS = 400L
    // playbackStatBuckets 数量上限
    const val MAX_STAT_BUCKETS = 8000
    private const val MILLIS_PER_DAY = 86_400_000L

    private data class CounterMergeResult(
        val totalListenMs: Long,
        val playCount: Int,
        val firstPlayedAt: Long,
        val lastPlayedAt: Long,
        val baseListenMs: Long,
        val basePlayCount: Int,
        val shards: List<SyncPlaybackCounterShard>
    )

    fun merge(
        local: List<SyncTrackStat>,
        remote: List<SyncTrackStat>,
        playbackStatsClearedAt: Long
    ): List<SyncTrackStat> {
        val merged = linkedMapOf<String, SyncTrackStat>()
        for (stat in (local + remote).mapNotNull { normalizeAfterClear(it, playbackStatsClearedAt) }) {
            val existing = merged[stat.identityKey]
            merged[stat.identityKey] = if (existing == null) {
                stat
            } else {
                mergeStat(existing, stat)
            }
        }
        return merged.values.toList()
    }

    fun mergeBuckets(
        local: List<SyncPlaybackStatBucket>,
        remote: List<SyncPlaybackStatBucket>,
        playbackStatsClearedAt: Long
    ): List<SyncPlaybackStatBucket> {
        val merged = linkedMapOf<Pair<Long, String>, SyncPlaybackStatBucket>()
        for (bucket in (local + remote).mapNotNull {
            normalizeBucketAfterClear(it, playbackStatsClearedAt)
        }) {
            val key = bucket.dayStartAt to bucket.identityKey
            val existing = merged[key]
            merged[key] = if (existing == null) {
                bucket
            } else {
                mergeBucket(existing, bucket)
            }
        }
        return merged.values.toList()
    }

    /**
     * 裁剪曲目统计：按 lastPlayedAt 降序保留，并列按 identityKey 升序，最多保留 MAX_TRACK_STATS 条
     * 幂等：trim(trim(x)) == trim(x)；确定性排序保证双端从同一合并结果得到同一保留集合
     * 裁剪时机固定在「merge 之后、序列化之前」
     */
    fun trimStats(stats: List<SyncTrackStat>): List<SyncTrackStat> {
        if (stats.size <= MAX_TRACK_STATS) return stats
        return stats
            .sortedWith(
                compareByDescending<SyncTrackStat> { it.lastPlayedAt }
                    .thenBy { it.identityKey }
            )
            .take(MAX_TRACK_STATS)
    }

    /**
     * 裁剪日桶：先按 400 天保留窗口过滤，再按数量上限 MAX_STAT_BUCKETS 截断
     * 窗口锚点取数据集内最大的 dayStartAt（绝不能用墙钟），cutoff = maxDayStartAt - 400 天
     * 超上限时按 dayStartAt 降序、playCount 降序、identityKey 升序截断
     * 幂等：最新一天恒在窗口内，锚点与 cutoff 重复裁剪不变，故 trim(trim(x)) == trim(x)
     */
    fun trimBuckets(buckets: List<SyncPlaybackStatBucket>): List<SyncPlaybackStatBucket> {
        if (buckets.isEmpty()) return buckets
        val maxDayStartAt = buckets.maxOf { it.dayStartAt }
        val cutoff = maxDayStartAt - BUCKET_RETENTION_DAYS * MILLIS_PER_DAY
        val windowed = buckets.filter { it.dayStartAt >= cutoff }
        if (windowed.size <= MAX_STAT_BUCKETS) return windowed
        return windowed
            .sortedWith(
                compareByDescending<SyncPlaybackStatBucket> { it.dayStartAt }
                    .thenByDescending { it.playCount }
                    .thenBy { it.identityKey }
            )
            .take(MAX_STAT_BUCKETS)
    }

    /**
     * 单调抬升兜底：把「总」聚合计数抬升到不低于同曲分桶之和，只增不减、幂等
     * 与桌面端 lift_stats_to_bucket_totals 对齐，用于消除「年 > 总」口径分裂
     * 合并语义整体基于 max（merge 用 max，lift 用 max），因此即使两端实现略有差异也收敛，不产生回声
     */
    fun liftStatsToBucketTotals(
        stats: List<SyncTrackStat>,
        buckets: List<SyncPlaybackStatBucket>
    ): List<SyncTrackStat> {
        if (stats.isEmpty()) return stats
        val bucketListenByKey = HashMap<String, Long>()
        val bucketPlayByKey = HashMap<String, Long>()
        for (bucket in buckets) {
            val key = bucket.identityKey
            bucketListenByKey[key] =
                (bucketListenByKey[key] ?: 0L) + bucket.totalListenMs.coerceAtLeast(0L)
            bucketPlayByKey[key] =
                (bucketPlayByKey[key] ?: 0L) + bucket.playCount.coerceAtLeast(0).toLong()
        }
        return stats.map { stat ->
            val liftedListen = maxOf(stat.totalListenMs, bucketListenByKey[stat.identityKey] ?: 0L)
            val liftedPlayLong = maxOf(
                stat.playCount.toLong(),
                bucketPlayByKey[stat.identityKey] ?: 0L
            )
            val liftedPlay = liftedPlayLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (liftedListen == stat.totalListenMs && liftedPlay == stat.playCount) {
                stat
            } else {
                stat.copy(totalListenMs = liftedListen, playCount = liftedPlay)
            }
        }
    }

    /** 合并统计收尾结果：已抬升并裁剪的曲目统计与日桶 */
    data class FinalizedPlaybackStats(
        val stats: List<SyncTrackStat>,
        val buckets: List<SyncPlaybackStatBucket>
    )

    /**
     * 合并统计收尾：顺序必须与桌面 merge.rs three_way_merge 逐字一致——
     * 先用「未裁剪」的合并日桶做单调抬升，再分别裁剪日桶与曲目统计。
     * 若先裁剪再抬升，窗口外(>400 天)或超上限的日桶不会计入抬升，导致跨端「总」值不收敛、
     * 且 lift 兜底覆盖不到全量分桶之和（展示口径分裂）。集中在此处，保证多个调用点顺序不会再各自漂移。
     */
    fun finalizeMergedStats(
        mergedStats: List<SyncTrackStat>,
        mergedBuckets: List<SyncPlaybackStatBucket>
    ): FinalizedPlaybackStats {
        val liftedStats = liftStatsToBucketTotals(mergedStats, mergedBuckets)
        return FinalizedPlaybackStats(
            stats = trimStats(liftedStats),
            buckets = trimBuckets(mergedBuckets)
        )
    }

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

    private fun normalizeAfterClear(
        stat: SyncTrackStat,
        playbackStatsClearedAt: Long
    ): SyncTrackStat? {
        if (!shouldKeepAfterClear(stat, playbackStatsClearedAt)) return null
        val counterShards = SyncPlaybackStatMapper.normalizeCounterShards(stat.counterShards)
        if (playbackStatsClearedAt <= 0L) return stat.copy(
            counterShards = counterShards
        )

        val normalizedFirstPlayedAt = stat.firstPlayedAt
            .takeIf { it >= playbackStatsClearedAt && it <= stat.lastPlayedAt }
            ?: stat.lastPlayedAt
        val normalizedShards = SyncPlaybackStatMapper.normalizeCounterShards(
            counterShards.filter { it.lastPlayedAt >= playbackStatsClearedAt }
        )
        return stat.copy(
            firstPlayedAt = normalizedFirstPlayedAt,
            counterBaseListenMs = if (counterShards.isEmpty()) stat.counterBaseListenMs else 0L,
            counterBasePlayCount = if (counterShards.isEmpty()) stat.counterBasePlayCount else 0,
            counterShards = normalizedShards
        )
    }

    private fun normalizeBucketAfterClear(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): SyncPlaybackStatBucket? {
        if (!shouldKeepAfterClear(bucket, playbackStatsClearedAt)) return null
        val counterShards = SyncPlaybackStatMapper.normalizeCounterShards(bucket.counterShards)
        if (playbackStatsClearedAt <= 0L) return bucket.copy(
            counterShards = counterShards
        )

        val normalizedFirstPlayedAt = bucket.firstPlayedAt
            .takeIf { it >= playbackStatsClearedAt && it <= bucket.lastPlayedAt }
            ?: bucket.lastPlayedAt
        val normalizedShards = SyncPlaybackStatMapper.normalizeCounterShards(
            counterShards.filter { it.lastPlayedAt >= playbackStatsClearedAt }
        )
        return bucket.copy(
            firstPlayedAt = normalizedFirstPlayedAt,
            counterBaseListenMs = if (counterShards.isEmpty()) bucket.counterBaseListenMs else 0L,
            counterBasePlayCount = if (counterShards.isEmpty()) bucket.counterBasePlayCount else 0,
            counterShards = normalizedShards
        )
    }

    private fun mergeStat(existing: SyncTrackStat, stat: SyncTrackStat): SyncTrackStat {
        val newer = if (stat.lastPlayedAt >= existing.lastPlayedAt) stat else existing
        val counter = mergeCounters(
            existingTotalListenMs = existing.totalListenMs,
            existingPlayCount = existing.playCount,
            existingFirstPlayedAt = existing.firstPlayedAt,
            existingLastPlayedAt = existing.lastPlayedAt,
            existingBaseListenMs = counterBaseListenMs(existing, playbackStatsClearedAt = 0L),
            existingBasePlayCount = counterBasePlayCount(existing, playbackStatsClearedAt = 0L),
            existingShards = existing.counterShards,
            incomingTotalListenMs = stat.totalListenMs,
            incomingPlayCount = stat.playCount,
            incomingFirstPlayedAt = stat.firstPlayedAt,
            incomingLastPlayedAt = stat.lastPlayedAt,
            incomingBaseListenMs = counterBaseListenMs(stat, playbackStatsClearedAt = 0L),
            incomingBasePlayCount = counterBasePlayCount(stat, playbackStatsClearedAt = 0L),
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
        val newer = if (bucket.lastPlayedAt >= existing.lastPlayedAt) bucket else existing
        val counter = mergeCounters(
            existingTotalListenMs = existing.totalListenMs,
            existingPlayCount = existing.playCount,
            existingFirstPlayedAt = existing.firstPlayedAt,
            existingLastPlayedAt = existing.lastPlayedAt,
            existingBaseListenMs = counterBaseListenMs(existing, playbackStatsClearedAt = 0L),
            existingBasePlayCount = counterBasePlayCount(existing, playbackStatsClearedAt = 0L),
            existingShards = existing.counterShards,
            incomingTotalListenMs = bucket.totalListenMs,
            incomingPlayCount = bucket.playCount,
            incomingFirstPlayedAt = bucket.firstPlayedAt,
            incomingLastPlayedAt = bucket.lastPlayedAt,
            incomingBaseListenMs = counterBaseListenMs(bucket, playbackStatsClearedAt = 0L),
            incomingBasePlayCount = counterBasePlayCount(bucket, playbackStatsClearedAt = 0L),
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

    private fun mergeCounters(
        existingTotalListenMs: Long,
        existingPlayCount: Int,
        existingFirstPlayedAt: Long,
        existingLastPlayedAt: Long,
        existingBaseListenMs: Long,
        existingBasePlayCount: Int,
        existingShards: List<SyncPlaybackCounterShard>,
        incomingTotalListenMs: Long,
        incomingPlayCount: Int,
        incomingFirstPlayedAt: Long,
        incomingLastPlayedAt: Long,
        incomingBaseListenMs: Long,
        incomingBasePlayCount: Int,
        incomingShards: List<SyncPlaybackCounterShard>
    ): CounterMergeResult {
        val shards = SyncPlaybackStatMapper.normalizeCounterShards(existingShards + incomingShards)
        if (shards.isEmpty()) {
            return CounterMergeResult(
                totalListenMs = maxOf(existingTotalListenMs, incomingTotalListenMs).coerceAtLeast(0L),
                playCount = maxOf(existingPlayCount, incomingPlayCount).coerceAtLeast(0),
                firstPlayedAt = minPositivePlayedAt(existingFirstPlayedAt, incomingFirstPlayedAt),
                lastPlayedAt = maxOf(existingLastPlayedAt, incomingLastPlayedAt),
                baseListenMs = 0L,
                basePlayCount = 0,
                shards = emptyList()
            )
        }

        val baseListenMs = maxOf(existingBaseListenMs, incomingBaseListenMs).coerceAtLeast(0L)
        val basePlayCount = maxOf(existingBasePlayCount, incomingBasePlayCount).coerceAtLeast(0)
        val shardedListenMs = baseListenMs + shards.sumOf { it.totalListenMs.coerceAtLeast(0L) }
        val shardedPlayCount = basePlayCount + shards.sumOf { it.playCount.coerceAtLeast(0) }
        return CounterMergeResult(
            totalListenMs = maxOf(shardedListenMs, existingTotalListenMs, incomingTotalListenMs)
                .coerceAtLeast(0L),
            playCount = maxOf(shardedPlayCount, existingPlayCount, incomingPlayCount).coerceAtLeast(0),
            firstPlayedAt = minPositivePlayedAt(
                minPositivePlayedAt(existingFirstPlayedAt, incomingFirstPlayedAt),
                shards.map { it.firstPlayedAt }.filter { it > 0L }.minOrNull() ?: 0L
            ),
            lastPlayedAt = maxOf(
                existingLastPlayedAt,
                incomingLastPlayedAt,
                shards.maxOfOrNull { it.lastPlayedAt } ?: 0L
            ),
            baseListenMs = baseListenMs,
            basePlayCount = basePlayCount,
            shards = shards
        )
    }

    private fun counterBaseListenMs(
        stat: SyncTrackStat,
        playbackStatsClearedAt: Long
    ): Long {
        if (playbackStatsClearedAt > 0L && stat.counterShards.isNotEmpty()) return 0L
        if (stat.counterShards.isEmpty() && stat.counterBaseListenMs == 0L) {
            return stat.totalListenMs.coerceAtLeast(0L)
        }
        return stat.counterBaseListenMs.coerceAtLeast(0L)
    }

    private fun counterBaseListenMs(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): Long {
        if (playbackStatsClearedAt > 0L && bucket.counterShards.isNotEmpty()) return 0L
        if (bucket.counterShards.isEmpty() && bucket.counterBaseListenMs == 0L) {
            return bucket.totalListenMs.coerceAtLeast(0L)
        }
        return bucket.counterBaseListenMs.coerceAtLeast(0L)
    }

    private fun counterBasePlayCount(
        stat: SyncTrackStat,
        playbackStatsClearedAt: Long
    ): Int {
        if (playbackStatsClearedAt > 0L && stat.counterShards.isNotEmpty()) return 0
        if (stat.counterShards.isEmpty() && stat.counterBasePlayCount == 0) {
            return stat.playCount.coerceAtLeast(0)
        }
        return stat.counterBasePlayCount.coerceAtLeast(0)
    }

    private fun counterBasePlayCount(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): Int {
        if (playbackStatsClearedAt > 0L && bucket.counterShards.isNotEmpty()) return 0
        if (bucket.counterShards.isEmpty() && bucket.counterBasePlayCount == 0) {
            return bucket.playCount.coerceAtLeast(0)
        }
        return bucket.counterBasePlayCount.coerceAtLeast(0)
    }

    private fun minPositivePlayedAt(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }
}
