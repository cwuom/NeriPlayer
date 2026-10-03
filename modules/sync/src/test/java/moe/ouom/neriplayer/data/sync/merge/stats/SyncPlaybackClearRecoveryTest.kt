package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaybackClearRecoveryTest {
    @Test
    fun `clear removes old totals even when a newer shard keeps the track alive`() {
        val old = shard("old", 0, 500, 40)
        val fresh = shard("fresh", 50, 7, 100)
        val track = track(507, listOf(old, fresh))
        val bucket = SyncPlaybackStatBucket(
            identityKey = "song", dayStartAt = 0, totalListenMs = 507, playCount = 2,
            firstPlayedAt = 10, lastPlayedAt = 100, counterShards = listOf(old, fresh)
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(listOf(track), listOf(track), 50).single()
        val mergedBucket = SyncPlaybackStatsMergePolicy.mergeBuckets(listOf(bucket), listOf(bucket), 50).single()

        assertEquals(7, merged.totalListenMs)
        assertEquals(1, merged.playCount)
        assertEquals(listOf(fresh), merged.counterShards)
        assertEquals(7, mergedBucket.totalListenMs)
        assertEquals(1, mergedBucket.playCount)
        assertEquals(100, merged.firstPlayedAt)
    }

    @Test
    fun `old epoch cannot reintroduce cumulative history by playing after clear`() {
        val resumedOldEpoch = track(507, listOf(shard("offline", 0, 507, 100)))
        assertTrue(SyncPlaybackStatsMergePolicy.merge(emptyList(), listOf(resumedOldEpoch), 50).isEmpty())
        val bucket = SyncPlaybackStatBucket(
            identityKey = "song", totalListenMs = 507, playCount = 1,
            firstPlayedAt = 10, lastPlayedAt = 100, counterShards = resumedOldEpoch.counterShards
        )
        assertTrue(SyncPlaybackStatsMergePolicy.mergeBuckets(emptyList(), listOf(bucket), 50).isEmpty())
    }

    @Test
    fun `unsharded cumulative history crossing clear is not treated as a fresh delta`() {
        val oldLegacy = track(507, emptyList())
        assertTrue(SyncPlaybackStatsMergePolicy.merge(emptyList(), listOf(oldLegacy), 50).isEmpty())
        val freshLegacy = oldLegacy.copy(firstPlayedAt = 60, totalListenMs = 7, playCount = 1)
        assertEquals(7, SyncPlaybackStatsMergePolicy.merge(emptyList(), listOf(freshLegacy), 50).single().totalListenMs)
    }

    @Test
    fun `independent device counters saturate instead of wrapping and losing counts`() {
        val left = track(Long.MAX_VALUE - 3, listOf(shard("left", 0, Long.MAX_VALUE - 3, 100)))
            .copy(playCount = Int.MAX_VALUE, counterShards = listOf(shard("left", 0, Long.MAX_VALUE - 3, 100).copy(playCount = Int.MAX_VALUE)))
        val right = track(10, listOf(shard("right", 0, 10, 100)))
        val merged = SyncPlaybackStatsMergePolicy.merge(listOf(left), listOf(right), 0).single()
        assertEquals(Long.MAX_VALUE, merged.totalListenMs)
        assertEquals(Int.MAX_VALUE, merged.playCount)
    }

    private fun track(total: Long, shards: List<SyncPlaybackCounterShard>) = SyncTrackStat(
        identityKey = "song", firstPlayedAt = 10, lastPlayedAt = 100,
        totalListenMs = total, playCount = shards.size, counterShards = shards
    )

    private fun shard(device: String, epoch: Long, total: Long, last: Long) = SyncPlaybackCounterShard(
        deviceId = device, epochStartedAt = epoch, totalListenMs = total, playCount = 1,
        firstPlayedAt = if (epoch >= 50) 100 else 10, lastPlayedAt = last
    )
}
