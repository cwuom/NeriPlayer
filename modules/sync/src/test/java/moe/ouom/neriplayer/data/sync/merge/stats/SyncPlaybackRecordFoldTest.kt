package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPlaybackRecordFoldTest {
    @Test
    fun `streaming fold preserves independent counters under repeat and opposite order`() {
        val a = track("a", 3, 2)
        val b = track("b", 5, 3)
        for (input in listOf(listOf(a, b, a), listOf(b, a, b))) {
            val merged = input.fold(null as SyncTrackStat?) { current, incoming ->
                SyncPlaybackStatsMergePolicy.mergeTrack(current, incoming, 50)
            }!!
            assertEquals(8L, merged.totalListenMs)
            assertEquals(5, merged.playCount)
            assertEquals(listOf("a", "b"), merged.counterShards.map { it.deviceId })
        }
    }

    @Test
    fun `stale incoming and stale existing cannot displace a fresh epoch`() {
        val fresh = track("fresh", 7, 1)
        val old = fresh.copy(counterShards = fresh.counterShards.map { it.copy(epochStartedAt = 0) })
        assertEquals(fresh, SyncPlaybackStatsMergePolicy.mergeTrack(fresh, old, 50))
        assertEquals(fresh, SyncPlaybackStatsMergePolicy.mergeTrack(old, fresh, 50))
        assertNull(SyncPlaybackStatsMergePolicy.mergeTrack(old, old, 50))
    }

    @Test
    fun `daily bucket merge rejects stale epochs and preserves fresh independent device increments`() {
        fun bucket(device: String, listen: Long, count: Int) = SyncPlaybackStatBucket(
            dayStartAt = 50, identityKey = "song", firstPlayedAt = 60, lastPlayedAt = 100,
            totalListenMs = listen, playCount = count,
            counterShards = track(device, listen, count).counterShards
        )
        val a = bucket("a", 3, 2)
        val b = bucket("b", 5, 3)
        val old = a.copy(counterShards = a.counterShards.map { it.copy(epochStartedAt = 0) })
        assertEquals(a, SyncPlaybackStatsMergePolicy.mergeBucket(null, a, 50))
        assertEquals(a, SyncPlaybackStatsMergePolicy.mergeBucket(old, a, 50))
        assertEquals(a, SyncPlaybackStatsMergePolicy.mergeBucket(a, old, 50))
        assertNull(SyncPlaybackStatsMergePolicy.mergeBucket(old, old, 50))
        for ((left, right) in listOf(a to b, b to a)) {
            val result = SyncPlaybackStatsMergePolicy.mergeBucket(left, right, 50)!!
            assertEquals(8L, result.totalListenMs)
            assertEquals(5, result.playCount)
            assertEquals(listOf("a", "b"), result.counterShards.map { it.deviceId })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `daily bucket merge cannot combine the same song across two days`() {
        val bucket = SyncPlaybackStatBucket(dayStartAt = 1, identityKey = "song")
        SyncPlaybackStatsMergePolicy.mergeBucket(bucket, bucket.copy(dayStartAt = 2), 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `daily bucket merge cannot combine two songs on the same day`() {
        val bucket = SyncPlaybackStatBucket(dayStartAt = 1, identityKey = "song")
        SyncPlaybackStatsMergePolicy.mergeBucket(bucket, bucket.copy(identityKey = "other"), 0)
    }

    @Test
    fun `bucket fold accumulates all days without replacing existing track metadata`() {
        val fold = SyncPlaybackBucketTotalsPolicy.Fold("song")
        for (day in 0L until 100_000L) {
            fold.add(SyncPlaybackStatBucket(
                identityKey = "song", dayStartAt = day, name = "bucket",
                totalListenMs = 3, playCount = 2, firstPlayedAt = day + 1, lastPlayedAt = day + 1
            ))
        }
        val result = fold.lift(track("a", 1, 1).copy(name = "track"))!!
        assertEquals(300_000L, result.totalListenMs)
        assertEquals(200_000, result.playCount)
        assertEquals("track", result.name)
        assertEquals(60L, result.firstPlayedAt)
        assertEquals(100L, result.lastPlayedAt)
        val generated = fold.lift(null)!!
        assertEquals("bucket", generated.name)
        assertEquals(1L, generated.firstPlayedAt)
        assertEquals(100_000L, generated.lastPlayedAt)
    }

    @Test
    fun `latest bucket tie chooses the later day and saturates totals`() {
        val fold = SyncPlaybackBucketTotalsPolicy.Fold("song")
        fold.add(SyncPlaybackStatBucket(identityKey = "song", dayStartAt = 2, name = "late", lastPlayedAt = 9,
            totalListenMs = Long.MAX_VALUE, playCount = Int.MAX_VALUE))
        fold.add(SyncPlaybackStatBucket(identityKey = "song", dayStartAt = 1, name = "early", lastPlayedAt = 9,
            totalListenMs = 5, playCount = 1))
        val result = fold.lift(null)!!
        assertEquals("late", result.name)
        assertEquals(Long.MAX_VALUE, result.totalListenMs)
        assertEquals(Int.MAX_VALUE, result.playCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `track fold rejects a mismatched identity`() {
        SyncPlaybackStatsMergePolicy.mergeTrack(track("a", 1, 1), track("b", 1, 1).copy(identityKey = "other"), 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bucket fold rejects a mismatched identity`() {
        SyncPlaybackBucketTotalsPolicy.Fold("song").add(SyncPlaybackStatBucket(identityKey = "other"))
    }

    @Test
    fun `empty bucket fold leaves an existing track unchanged`() {
        val fold = SyncPlaybackBucketTotalsPolicy.Fold("song")
        assertNull(fold.lift(null))
        val original = track("a", 1, 1)
        assertEquals(original, fold.lift(original))
    }

    private fun track(device: String, listen: Long, count: Int) = SyncTrackStat(
        identityKey = "song", firstPlayedAt = 60, lastPlayedAt = 100,
        totalListenMs = listen, playCount = count,
        counterShards = listOf(SyncPlaybackCounterShard(
            deviceId = device, epochStartedAt = 50, firstPlayedAt = 60, lastPlayedAt = 100,
            totalListenMs = listen, playCount = count
        ))
    )
}
