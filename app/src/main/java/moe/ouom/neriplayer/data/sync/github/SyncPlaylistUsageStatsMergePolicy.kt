package moe.ouom.neriplayer.data.sync.github

import java.util.TreeMap
import moe.ouom.neriplayer.data.sync.model.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.sync.model.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistUsageStat

private const val LOCAL_PLAYLIST_BUCKET_RETENTION_DAYS = 400L
private const val MAX_LOCAL_PLAYLIST_PLAYBACK_BUCKETS = 8_000
private const val MILLIS_PER_DAY = 86_400_000L

internal data class LocalPlaylistPlaybackSyncResult(
    val stats: List<SyncLocalPlaylistPlaybackStat>,
    val buckets: List<SyncLocalPlaylistPlaybackBucket>
)

internal object SyncPlaylistUsageStatsMergePolicy {
    fun mergePlaylistUsageStats(
        local: List<SyncPlaylistUsageStat>,
        remote: List<SyncPlaylistUsageStat>
    ): List<SyncPlaylistUsageStat> {
        val merged = TreeMap<String, SyncPlaylistUsageStat>()
        (local.asSequence() + remote.asSequence())
            .mapNotNull(::sanitize)
            .forEach { stat ->
                val existing = merged[stat.playlistKey]
                if (existing == null) {
                    merged[stat.playlistKey] = stat
                    return@forEach
                }

                val counters = mergeCounters(
                    left = CounterInput(
                        totalCount = existing.openCount.toLong(),
                        firstOccurredAt = existing.firstOpenedAt,
                        lastOccurredAt = existing.lastOpenedAt,
                        counterBaseCount = existing.counterBaseOpenCount,
                        counterShards = existing.counterShards
                    ),
                    right = CounterInput(
                        totalCount = stat.openCount.toLong(),
                        firstOccurredAt = stat.firstOpenedAt,
                        lastOccurredAt = stat.lastOpenedAt,
                        counterBaseCount = stat.counterBaseOpenCount,
                        counterShards = stat.counterShards
                    )
                )
                val newest = if (stat.lastOpenedAt >= existing.lastOpenedAt) stat else existing
                val older = if (newest === stat) existing else stat
                merged[stat.playlistKey] = newest.copy(
                    source = newest.source.ifBlank { older.source },
                    id = newest.id.takeIf { it != 0L } ?: older.id,
                    subtype = newest.subtype ?: older.subtype,
                    name = newest.name.ifBlank { older.name },
                    coverUrl = newest.coverUrl ?: older.coverUrl,
                    trackCount = maxOf(newest.trackCount, older.trackCount).coerceAtLeast(0),
                    firstOpenedAt = counters.firstOccurredAt,
                    lastOpenedAt = counters.lastOccurredAt,
                    openCount = counters.totalCount.toBoundedInt(),
                    counterBaseOpenCount = counters.counterBaseCount,
                    counterShards = counters.counterShards,
                    fid = newest.fid.takeIf { it != 0L } ?: older.fid,
                    mid = newest.mid.takeIf { it != 0L } ?: older.mid,
                    browseId = newest.browseId ?: older.browseId,
                    playlistId = newest.playlistId ?: older.playlistId,
                    subtitle = newest.subtitle ?: older.subtitle
                )
            }
        return merged.values.toList()
    }

    fun mergeLocalPlaylistPlaybackStats(
        local: List<SyncLocalPlaylistPlaybackStat>,
        remote: List<SyncLocalPlaylistPlaybackStat>
    ): List<SyncLocalPlaylistPlaybackStat> {
        val merged = TreeMap<Long, SyncLocalPlaylistPlaybackStat>()
        (local.asSequence() + remote.asSequence())
            .mapNotNull(::sanitize)
            .forEach { stat ->
                val existing = merged[stat.playlistId]
                if (existing == null) {
                    merged[stat.playlistId] = stat
                    return@forEach
                }
                val counters = mergeCounters(
                    left = CounterInput(
                        totalCount = existing.totalPlayCount,
                        firstOccurredAt = existing.firstPlayedAt,
                        lastOccurredAt = existing.lastPlayedAt,
                        counterBaseCount = existing.counterBasePlayCount,
                        counterShards = existing.counterShards
                    ),
                    right = CounterInput(
                        totalCount = stat.totalPlayCount,
                        firstOccurredAt = stat.firstPlayedAt,
                        lastOccurredAt = stat.lastPlayedAt,
                        counterBaseCount = stat.counterBasePlayCount,
                        counterShards = stat.counterShards
                    )
                )
                merged[stat.playlistId] = SyncLocalPlaylistPlaybackStat(
                    playlistId = stat.playlistId,
                    totalPlayCount = counters.totalCount,
                    firstPlayedAt = counters.firstOccurredAt,
                    lastPlayedAt = counters.lastOccurredAt,
                    counterBasePlayCount = counters.counterBaseCount,
                    counterShards = counters.counterShards
                )
            }
        return merged.values.toList()
    }

    fun mergeLocalPlaylistPlaybackBuckets(
        local: List<SyncLocalPlaylistPlaybackBucket>,
        remote: List<SyncLocalPlaylistPlaybackBucket>
    ): List<SyncLocalPlaylistPlaybackBucket> {
        val merged = TreeMap<Pair<Long, Long>, SyncLocalPlaylistPlaybackBucket>(
            compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second }
        )
        (local.asSequence() + remote.asSequence())
            .mapNotNull(::sanitize)
            .forEach { bucket ->
                val key = bucket.playlistId to bucket.dayStartAt
                val existing = merged[key]
                if (existing == null) {
                    merged[key] = bucket
                    return@forEach
                }
                val counters = mergeCounters(
                    left = CounterInput(
                        totalCount = existing.playCount,
                        firstOccurredAt = existing.firstPlayedAt,
                        lastOccurredAt = existing.lastPlayedAt,
                        counterBaseCount = existing.counterBasePlayCount,
                        counterShards = existing.counterShards
                    ),
                    right = CounterInput(
                        totalCount = bucket.playCount,
                        firstOccurredAt = bucket.firstPlayedAt,
                        lastOccurredAt = bucket.lastPlayedAt,
                        counterBaseCount = bucket.counterBasePlayCount,
                        counterShards = bucket.counterShards
                    )
                )
                merged[key] = SyncLocalPlaylistPlaybackBucket(
                    dayStartAt = bucket.dayStartAt,
                    playlistId = bucket.playlistId,
                    playCount = counters.totalCount,
                    firstPlayedAt = counters.firstOccurredAt,
                    lastPlayedAt = counters.lastOccurredAt,
                    counterBasePlayCount = counters.counterBaseCount,
                    counterShards = counters.counterShards
                )
            }
        return merged.values.toList()
    }

    fun finalizeLocalPlaylistPlaybackStats(
        stats: List<SyncLocalPlaylistPlaybackStat>,
        buckets: List<SyncLocalPlaylistPlaybackBucket>
    ): LocalPlaylistPlaybackSyncResult {
        val mergedBuckets = mergeLocalPlaylistPlaybackBuckets(buckets, emptyList())
        val mergedStats = mergeLocalPlaylistPlaybackStats(stats, emptyList())
        val bucketTotals = mergedBuckets.groupBy(SyncLocalPlaylistPlaybackBucket::playlistId)
            .mapValues { (_, playlistBuckets) ->
                playlistBuckets.fold(PlaylistBucketTotals()) { totals, bucket ->
                    totals.add(bucket)
                }
            }
        val statIds = mergedStats.mapTo(mutableSetOf()) { it.playlistId }
        val liftedStats = mergedStats.map { stat ->
            val totals = bucketTotals[stat.playlistId] ?: return@map stat
            stat.copy(
                totalPlayCount = maxOf(stat.totalPlayCount, totals.playCount),
                firstPlayedAt = minPositiveTimestamp(stat.firstPlayedAt, totals.firstPlayedAt),
                lastPlayedAt = maxOf(stat.lastPlayedAt, totals.lastPlayedAt)
            )
        } + bucketTotals
            .filterKeys { it !in statIds }
            .map { (playlistId, totals) ->
                SyncLocalPlaylistPlaybackStat(
                    playlistId = playlistId,
                    totalPlayCount = totals.playCount,
                    firstPlayedAt = totals.firstPlayedAt,
                    lastPlayedAt = totals.lastPlayedAt
                )
            }
        return LocalPlaylistPlaybackSyncResult(
            stats = liftedStats.sortedBy(SyncLocalPlaylistPlaybackStat::playlistId),
            buckets = trimLocalPlaylistPlaybackBuckets(mergedBuckets)
        )
    }

    fun sanitize(stat: SyncPlaylistUsageStat): SyncPlaylistUsageStat? {
        val playlistKey = stat.playlistKey.trim()
        if (playlistKey.isEmpty()) return null
        val counters = normalizeCounters(
            totalCount = stat.openCount.toLong(),
            firstOccurredAt = stat.firstOpenedAt,
            lastOccurredAt = stat.lastOpenedAt,
            counterBaseCount = stat.counterBaseOpenCount,
            counterShards = stat.counterShards
        )
        return stat.copy(
            playlistKey = playlistKey,
            source = stat.source.trim().ifBlank { playlistKey.substringBefore(':') },
            trackCount = stat.trackCount.coerceAtLeast(0),
            openCount = counters.totalCount.toBoundedInt(),
            firstOpenedAt = counters.firstOccurredAt,
            lastOpenedAt = counters.lastOccurredAt,
            counterBaseOpenCount = counters.counterBaseCount,
            counterShards = counters.counterShards
        )
    }

    fun sanitize(stat: SyncLocalPlaylistPlaybackStat): SyncLocalPlaylistPlaybackStat? {
        if (stat.playlistId == 0L) return null
        val counters = normalizeCounters(
            totalCount = stat.totalPlayCount,
            firstOccurredAt = stat.firstPlayedAt,
            lastOccurredAt = stat.lastPlayedAt,
            counterBaseCount = stat.counterBasePlayCount,
            counterShards = stat.counterShards
        )
        return stat.copy(
            totalPlayCount = counters.totalCount,
            firstPlayedAt = counters.firstOccurredAt,
            lastPlayedAt = counters.lastOccurredAt,
            counterBasePlayCount = counters.counterBaseCount,
            counterShards = counters.counterShards
        )
    }

    fun sanitize(bucket: SyncLocalPlaylistPlaybackBucket): SyncLocalPlaylistPlaybackBucket? {
        if (bucket.playlistId == 0L || bucket.dayStartAt < 0L) return null
        val counters = normalizeCounters(
            totalCount = bucket.playCount,
            firstOccurredAt = bucket.firstPlayedAt,
            lastOccurredAt = bucket.lastPlayedAt,
            counterBaseCount = bucket.counterBasePlayCount,
            counterShards = bucket.counterShards
        )
        return bucket.copy(
            playCount = counters.totalCount,
            firstPlayedAt = counters.firstOccurredAt,
            lastPlayedAt = counters.lastOccurredAt,
            counterBasePlayCount = counters.counterBaseCount,
            counterShards = counters.counterShards
        )
    }

    fun same(left: SyncPlaylistUsageStat, right: SyncPlaylistUsageStat): Boolean {
        return sanitize(left) == sanitize(right)
    }

    fun same(
        left: SyncLocalPlaylistPlaybackStat,
        right: SyncLocalPlaylistPlaybackStat
    ): Boolean {
        return sanitize(left) == sanitize(right)
    }

    fun same(
        left: SyncLocalPlaylistPlaybackBucket,
        right: SyncLocalPlaylistPlaybackBucket
    ): Boolean {
        return sanitize(left) == sanitize(right)
    }

    private fun normalizeCounters(
        totalCount: Long,
        firstOccurredAt: Long,
        lastOccurredAt: Long,
        counterBaseCount: Long,
        counterShards: List<SyncPlaybackCounterShard>
    ): MergedCounter {
        return mergeCounters(
            left = CounterInput(
                totalCount = totalCount,
                firstOccurredAt = firstOccurredAt,
                lastOccurredAt = lastOccurredAt,
                counterBaseCount = counterBaseCount,
                counterShards = counterShards
            ),
            right = CounterInput()
        )
    }

    private fun mergeCounters(left: CounterInput, right: CounterInput): MergedCounter {
        val shards = SyncPlaybackStatMapper.normalizeCounterShards(
            left.counterShards + right.counterShards
        )
        if (shards.isEmpty()) {
            return MergedCounter(
                totalCount = maxOf(left.totalCount, right.totalCount).coerceAtLeast(0L),
                firstOccurredAt = minPositiveTimestamp(
                    left.firstOccurredAt,
                    right.firstOccurredAt
                ),
                lastOccurredAt = maxOf(left.lastOccurredAt, right.lastOccurredAt),
                counterBaseCount = 0L,
                counterShards = emptyList()
            )
        }
        val shardCount = shards.fold(0L) { total, shard ->
            total.saturatingAdd(shard.playCount.toLong().coerceAtLeast(0L))
        }
        val baseCount = maxOf(
            effectiveCounterBase(left, shardCount),
            effectiveCounterBase(right, shardCount)
        )
        return MergedCounter(
            totalCount = maxOf(
                left.totalCount.coerceAtLeast(0L),
                right.totalCount.coerceAtLeast(0L),
                baseCount.saturatingAdd(shardCount)
            ),
            firstOccurredAt = minPositiveTimestamp(
                minPositiveTimestamp(left.firstOccurredAt, right.firstOccurredAt),
                shards.fold(0L) { earliest, shard ->
                    minPositiveTimestamp(earliest, shard.firstPlayedAt)
                }
            ),
            lastOccurredAt = maxOf(
                left.lastOccurredAt,
                right.lastOccurredAt,
                shards.maxOfOrNull(SyncPlaybackCounterShard::lastPlayedAt) ?: 0L
            ),
            counterBaseCount = baseCount,
            counterShards = shards
        )
    }

    private fun effectiveCounterBase(input: CounterInput, mergedShardCount: Long): Long {
        val totalCount = input.totalCount.coerceAtLeast(0L)
        val storedBase = input.counterBaseCount.coerceAtLeast(0L)
        if (input.counterShards.isEmpty()) return maxOf(storedBase, totalCount)
        return maxOf(storedBase, totalCount.minus(mergedShardCount).coerceAtLeast(0L))
    }

    private fun trimLocalPlaylistPlaybackBuckets(
        buckets: List<SyncLocalPlaylistPlaybackBucket>
    ): List<SyncLocalPlaylistPlaybackBucket> {
        val newestDayStartAt = buckets.maxOfOrNull(SyncLocalPlaylistPlaybackBucket::dayStartAt)
            ?: return emptyList()
        val cutoff = newestDayStartAt - LOCAL_PLAYLIST_BUCKET_RETENTION_DAYS * MILLIS_PER_DAY
        val retained = buckets.filter { it.dayStartAt >= cutoff }
        if (retained.size <= MAX_LOCAL_PLAYLIST_PLAYBACK_BUCKETS) return retained
        return retained
            .sortedWith(
                compareByDescending<SyncLocalPlaylistPlaybackBucket> { it.dayStartAt }
                    .thenByDescending { it.playCount }
                    .thenBy { it.playlistId }
            )
            .take(MAX_LOCAL_PLAYLIST_PLAYBACK_BUCKETS)
    }
}

private data class CounterInput(
    val totalCount: Long = 0L,
    val firstOccurredAt: Long = 0L,
    val lastOccurredAt: Long = 0L,
    val counterBaseCount: Long = 0L,
    val counterShards: List<SyncPlaybackCounterShard> = emptyList()
)

private data class MergedCounter(
    val totalCount: Long,
    val firstOccurredAt: Long,
    val lastOccurredAt: Long,
    val counterBaseCount: Long,
    val counterShards: List<SyncPlaybackCounterShard>
)

private data class PlaylistBucketTotals(
    val playCount: Long = 0L,
    val firstPlayedAt: Long = 0L,
    val lastPlayedAt: Long = 0L
) {
    fun add(bucket: SyncLocalPlaylistPlaybackBucket): PlaylistBucketTotals {
        return copy(
            playCount = playCount.saturatingAdd(bucket.playCount.coerceAtLeast(0L)),
            firstPlayedAt = minPositiveTimestamp(firstPlayedAt, bucket.firstPlayedAt),
            lastPlayedAt = maxOf(lastPlayedAt, bucket.lastPlayedAt)
        )
    }
}

private fun Long.saturatingAdd(other: Long): Long {
    return if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
}

private fun Long.toBoundedInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private fun minPositiveTimestamp(left: Long, right: Long): Long {
    return when {
        left <= 0L -> right.coerceAtLeast(0L)
        right <= 0L -> left.coerceAtLeast(0L)
        else -> minOf(left, right)
    }
}
