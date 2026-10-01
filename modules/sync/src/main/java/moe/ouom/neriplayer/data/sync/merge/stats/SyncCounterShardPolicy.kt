package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard

object SyncCounterShardPolicy {
    fun normalizeCounterShards(
        shards: List<SyncPlaybackCounterShard?>?
    ): List<SyncPlaybackCounterShard> {
        return shards.orEmpty()
            .asSequence()
            .filterNotNull()
            .filter { it.deviceId.isNotBlank() }
            .map { shard ->
                val lastPlayedAt = shard.lastPlayedAt.coerceAtLeast(0L)
                val firstPlayedAt = shard.firstPlayedAt.coerceAtLeast(0L).let {
                    if (lastPlayedAt > 0L && (it == 0L || it > lastPlayedAt)) lastPlayedAt else it
                }
                shard.copy(
                    epochStartedAt = shard.epochStartedAt.coerceAtLeast(0L),
                    totalListenMs = shard.totalListenMs.coerceAtLeast(0L),
                    playCount = shard.playCount.coerceAtLeast(0),
                    firstPlayedAt = firstPlayedAt,
                    lastPlayedAt = lastPlayedAt
                )
            }
            .groupBy { it.deviceId to it.epochStartedAt }
            .map { (_, snapshots) ->
                snapshots.reduce(::mergeCounterShard)
            }
            .sortedWith(compareBy<SyncPlaybackCounterShard> { it.deviceId }.thenBy { it.epochStartedAt })
    }

    private fun mergeCounterShard(
        left: SyncPlaybackCounterShard,
        right: SyncPlaybackCounterShard
    ): SyncPlaybackCounterShard {
        return left.copy(
            totalListenMs = maxOf(left.totalListenMs, right.totalListenMs),
            playCount = maxOf(left.playCount, right.playCount),
            firstPlayedAt = minPositivePlayedAt(left.firstPlayedAt, right.firstPlayedAt),
            lastPlayedAt = maxOf(left.lastPlayedAt, right.lastPlayedAt)
        )
    }

    private fun minPositivePlayedAt(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }
}
