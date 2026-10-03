package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard

internal data class SyncPlaybackCounterMergeResult(
    val totalListenMs: Long,
    val playCount: Int,
    val firstPlayedAt: Long,
    val lastPlayedAt: Long,
    val baseListenMs: Long,
    val basePlayCount: Int,
    val shards: List<SyncPlaybackCounterShard>
)

internal object SyncPlaybackCounterMergePolicy {
    fun merge(
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
    ): SyncPlaybackCounterMergeResult {
        val shards = SyncCounterShardPolicy.normalizeCounterShards(existingShards + incomingShards)
        if (shards.isEmpty()) {
            return SyncPlaybackCounterMergeResult(
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
        val shardedListenMs = shards.fold(baseListenMs) { total, shard ->
            SyncPlaybackCounterArithmetic.add(total, shard.totalListenMs)
        }
        val shardedPlayCount = shards.fold(basePlayCount) { total, shard ->
            SyncPlaybackCounterArithmetic.add(total, shard.playCount)
        }
        return SyncPlaybackCounterMergeResult(
            totalListenMs = maxOf(shardedListenMs, existingTotalListenMs, incomingTotalListenMs)
                .coerceAtLeast(0L),
            playCount = maxOf(shardedPlayCount, existingPlayCount, incomingPlayCount).coerceAtLeast(0),
            firstPlayedAt = minPositivePlayedAt(
                minPositivePlayedAt(existingFirstPlayedAt, incomingFirstPlayedAt),
                firstShardPlayedAt(shards)
            ),
            lastPlayedAt = maxOf(
                existingLastPlayedAt,
                incomingLastPlayedAt,
                lastShardPlayedAt(shards)
            ),
            baseListenMs = baseListenMs,
            basePlayCount = basePlayCount,
            shards = shards
        )
    }

    private fun minPositivePlayedAt(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }

    private fun firstShardPlayedAt(shards: List<SyncPlaybackCounterShard>): Long =
        shards.map { it.firstPlayedAt }.filter { it > 0L }.minOrNull() ?: 0L

    private fun lastShardPlayedAt(shards: List<SyncPlaybackCounterShard>): Long =
        shards.maxOf { it.lastPlayedAt }
}
