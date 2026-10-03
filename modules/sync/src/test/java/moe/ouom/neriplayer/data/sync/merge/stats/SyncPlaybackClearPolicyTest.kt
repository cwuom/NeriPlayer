package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPlaybackClearPolicyTest {
    private val old = SyncPlaybackCounterShard("old", totalListenMs = 5, playCount = 1, lastPlayedAt = 40)
    private val current = SyncPlaybackCounterShard("current", epochStartedAt = 50, totalListenMs = 7, playCount = 2, firstPlayedAt = 60, lastPlayedAt = 100)

    @Test
    fun `legacy counters require a complete valid post clear time interval`() {
        val fresh = SyncTrackStat(firstPlayedAt = 50, lastPlayedAt = 100, totalListenMs = 7, playCount = 2)
        val bucket = SyncPlaybackStatBucket(firstPlayedAt = 50, lastPlayedAt = 100, totalListenMs = 7, playCount = 2)
        assertEquals(fresh, SyncPlaybackClearPolicy.normalizeAfterClear(fresh, 50))
        assertEquals(bucket, SyncPlaybackClearPolicy.normalizeBucketAfterClear(bucket, 50))
        for (first in listOf(0L, 49L, 101L)) {
            assertNull(SyncPlaybackClearPolicy.normalizeAfterClear(fresh.copy(firstPlayedAt = first), 50))
            assertNull(SyncPlaybackClearPolicy.normalizeBucketAfterClear(bucket.copy(firstPlayedAt = first), 50))
        }
    }

    @Test
    fun `clear normalizes first time and removes stale device shards for tracks and buckets`() {
        for (first in listOf(40L, 60L, 110L)) {
            val shards = listOf(old, current)
            val stat = SyncTrackStat(firstPlayedAt = first, lastPlayedAt = 100, counterBaseListenMs = 10,
                counterBasePlayCount = 3, counterShards = shards)
            val bucket = SyncPlaybackStatBucket(firstPlayedAt = first, lastPlayedAt = 100, counterBaseListenMs = 10,
                counterBasePlayCount = 3, counterShards = shards)
            val normalizedStat = SyncPlaybackClearPolicy.normalizeAfterClear(stat, 50)!!
            val normalizedBucket = SyncPlaybackClearPolicy.normalizeBucketAfterClear(bucket, 50)!!
            assertEquals(60L, normalizedStat.firstPlayedAt)
            assertEquals(normalizedStat.firstPlayedAt, normalizedBucket.firstPlayedAt)
            assertEquals(0L, normalizedStat.counterBaseListenMs)
            assertEquals(0, normalizedBucket.counterBasePlayCount)
            assertEquals(listOf("current"), normalizedBucket.counterShards.map { it.deviceId })
        }
        assertNull(SyncPlaybackClearPolicy.normalizeBucketAfterClear(SyncPlaybackStatBucket(lastPlayedAt = 49), 50))
        assertEquals(SyncPlaybackStatBucket(), SyncPlaybackClearPolicy.normalizeBucketAfterClear(SyncPlaybackStatBucket(), 0))
    }

    @Test
    fun `counter bases distinguish legacy totals explicit bases and post clear shards`() {
        for (shards in listOf(emptyList(), listOf(current))) for (clear in listOf(0L, 50L)) for (base in listOf(0, 3)) {
            val stat = SyncTrackStat(totalListenMs = 20, playCount = 5, counterBaseListenMs = base.toLong(),
                counterBasePlayCount = base, counterShards = shards)
            val bucket = SyncPlaybackStatBucket(totalListenMs = 20, playCount = 5, counterBaseListenMs = base.toLong(),
                counterBasePlayCount = base, counterShards = shards)
            val listen = if (clear > 0 && shards.isNotEmpty()) 0L else if (shards.isEmpty() && base == 0) 20L else base.toLong()
            val count = if (clear > 0 && shards.isNotEmpty()) 0 else if (shards.isEmpty() && base == 0) 5 else base
            assertEquals(listen, SyncPlaybackClearPolicy.counterBaseListenMs(stat, clear))
            assertEquals(listen, SyncPlaybackClearPolicy.counterBaseListenMs(bucket, clear))
            assertEquals(count, SyncPlaybackClearPolicy.counterBasePlayCount(stat, clear))
            assertEquals(count, SyncPlaybackClearPolicy.counterBasePlayCount(bucket, clear))
        }
    }

    @Test
    fun `missing first timestamps follow normalized shard time`() {
        val stat = SyncTrackStat(identityKey = "song", counterShards = listOf(current))
        val merged = SyncPlaybackStatsMergePolicy.merge(listOf(stat), listOf(stat), 0).single()
        assertEquals(60L, merged.firstPlayedAt)
        assertEquals(100L, merged.lastPlayedAt)
        assertEquals(7L, merged.totalListenMs)
        assertEquals(2, merged.playCount)
        val unknown = stat.copy(counterShards = listOf(current.copy(firstPlayedAt = 0, lastPlayedAt = 0)))
        assertEquals(0L, SyncPlaybackStatsMergePolicy.merge(listOf(unknown), listOf(unknown), 0).single().firstPlayedAt)
    }
}
