package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaylistUsageStatsMergePolicyTest {

    @Test
    fun `offline playlist opens merge device shards without double counting`() {
        val local = SyncPlaylistUsageStat(
            playlistKey = "local:42",
            source = "local",
            id = 42L,
            name = "Local",
            trackCount = 3,
            openCount = 2,
            firstOpenedAt = 100L,
            lastOpenedAt = 200L,
            counterShards = listOf(counterShard("phone", 2, 100L, 200L))
        )
        val remote = local.copy(
            openCount = 3,
            firstOpenedAt = 150L,
            lastOpenedAt = 300L,
            counterShards = listOf(counterShard("tablet", 3, 150L, 300L))
        )

        val merged = SyncPlaylistUsageStatsMergePolicy
            .mergePlaylistUsageStats(listOf(local), listOf(remote))
            .single()

        assertEquals(5, merged.openCount)
        assertEquals(100L, merged.firstOpenedAt)
        assertEquals(300L, merged.lastOpenedAt)
        assertEquals(setOf("phone", "tablet"), merged.counterShards.map { it.deviceId }.toSet())
        assertEquals(
            listOf(merged),
            SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(listOf(merged), listOf(remote))
        )
    }

    @Test
    fun `playlist usage order does not derive from counter shard time`() {
        val local = SyncPlaylistUsageStat(
            playlistKey = "local:42",
            source = "local",
            id = 42L,
            name = "Local",
            trackCount = 3,
            openCount = 2,
            firstOpenedAt = 100L,
            lastOpenedAt = 200L,
            counterShards = listOf(counterShard("phone", 2, 100L, 400L))
        )
        val remote = local.copy(
            openCount = 3,
            firstOpenedAt = 150L,
            lastOpenedAt = 300L,
            counterShards = listOf(counterShard("tablet", 3, 150L, 500L))
        )

        val merged = SyncPlaylistUsageStatsMergePolicy
            .mergePlaylistUsageStats(listOf(local), listOf(remote))
            .single()

        assertEquals(100L, merged.firstOpenedAt)
        assertEquals(300L, merged.lastOpenedAt)
        assertEquals(500L, merged.counterShards.maxOf(SyncPlaybackCounterShard::lastPlayedAt))
    }

    @Test
    fun `local playlist totals and daily buckets merge offline device counts`() {
        val localStats = SyncLocalPlaylistPlaybackStat(
            playlistId = 7L,
            totalPlayCount = 2L,
            firstPlayedAt = 100L,
            lastPlayedAt = 200L,
            counterShards = listOf(counterShard("phone", 2, 100L, 200L))
        )
        val remoteStats = localStats.copy(
            totalPlayCount = 3L,
            firstPlayedAt = 150L,
            lastPlayedAt = 300L,
            counterShards = listOf(counterShard("tablet", 3, 150L, 300L))
        )
        val localBucket = SyncLocalPlaylistPlaybackBucket(
            dayStartAt = 86_400_000L,
            playlistId = 7L,
            playCount = 2L,
            firstPlayedAt = 100L,
            lastPlayedAt = 200L,
            counterShards = listOf(counterShard("phone", 2, 100L, 200L))
        )
        val remoteBucket = localBucket.copy(
            playCount = 3L,
            firstPlayedAt = 150L,
            lastPlayedAt = 300L,
            counterShards = listOf(counterShard("tablet", 3, 150L, 300L))
        )

        val finalized = SyncPlaylistUsageStatsMergePolicy.finalizeLocalPlaylistPlaybackStats(
            stats = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackStats(
                local = listOf(localStats),
                remote = listOf(remoteStats)
            ),
            buckets = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackBuckets(
                local = listOf(localBucket),
                remote = listOf(remoteBucket)
            )
        )

        assertEquals(5L, finalized.stats.single().totalPlayCount)
        assertEquals(5L, finalized.buckets.single().playCount)
        assertEquals(100L, finalized.stats.single().firstPlayedAt)
        assertEquals(300L, finalized.stats.single().lastPlayedAt)
    }

    private fun counterShard(
        deviceId: String,
        playCount: Int,
        firstPlayedAt: Long,
        lastPlayedAt: Long
    ): SyncPlaybackCounterShard {
        return SyncPlaybackCounterShard(
            deviceId = deviceId,
            playCount = playCount,
            firstPlayedAt = firstPlayedAt,
            lastPlayedAt = lastPlayedAt
        )
    }
}
