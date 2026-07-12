package moe.ouom.neriplayer.data.sync.github

internal object SyncPlaybackStatsMergePolicy {
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
        if (playbackStatsClearedAt <= 0L) return stat.copy(
            counterShards = SyncPlaybackStatMapper.normalizeCounterShards(stat.counterShards)
        )

        val normalizedFirstPlayedAt = stat.firstPlayedAt
            .takeIf { it >= playbackStatsClearedAt && it <= stat.lastPlayedAt }
            ?: stat.lastPlayedAt
        val normalizedShards = SyncPlaybackStatMapper.normalizeCounterShards(
            stat.counterShards.filter { it.lastPlayedAt >= playbackStatsClearedAt }
        )
        return stat.copy(
            firstPlayedAt = normalizedFirstPlayedAt,
            counterBaseListenMs = if (stat.counterShards.isEmpty()) stat.counterBaseListenMs else 0L,
            counterBasePlayCount = if (stat.counterShards.isEmpty()) stat.counterBasePlayCount else 0,
            counterShards = normalizedShards
        )
    }

    private fun normalizeBucketAfterClear(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): SyncPlaybackStatBucket? {
        if (!shouldKeepAfterClear(bucket, playbackStatsClearedAt)) return null
        if (playbackStatsClearedAt <= 0L) return bucket.copy(
            counterShards = SyncPlaybackStatMapper.normalizeCounterShards(bucket.counterShards)
        )

        val normalizedFirstPlayedAt = bucket.firstPlayedAt
            .takeIf { it >= playbackStatsClearedAt && it <= bucket.lastPlayedAt }
            ?: bucket.lastPlayedAt
        val normalizedShards = SyncPlaybackStatMapper.normalizeCounterShards(
            bucket.counterShards.filter { it.lastPlayedAt >= playbackStatsClearedAt }
        )
        return bucket.copy(
            firstPlayedAt = normalizedFirstPlayedAt,
            counterBaseListenMs = if (bucket.counterShards.isEmpty()) bucket.counterBaseListenMs else 0L,
            counterBasePlayCount = if (bucket.counterShards.isEmpty()) bucket.counterBasePlayCount else 0,
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
