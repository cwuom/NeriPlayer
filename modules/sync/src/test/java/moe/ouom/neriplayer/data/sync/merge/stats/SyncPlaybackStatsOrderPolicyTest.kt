package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SyncPlaybackStatsOrderPolicyTest {
    @Test
    fun `finalize converges complete values and key order without changing inputs`() {
        val shard = SyncPlaybackCounterShard("device-a", 10, 20, 2, 100, 200)
        val a = SyncTrackStat(identityKey = "a", name = "first", totalListenMs = 80, playCount = 3, counterShards = listOf(shard))
        val b = SyncTrackStat(identityKey = "b", name = "second", totalListenMs = 100, playCount = 4, counterShards = listOf(shard))
        val firstDayA = SyncPlaybackStatBucket(dayStartAt = 10, identityKey = "a", name = "first", totalListenMs = 30, playCount = 1, counterShards = listOf(shard))
        val secondDayA = firstDayA.copy(dayStartAt = 20, totalListenMs = 50, playCount = 2)
        val firstDayB = firstDayA.copy(identityKey = "b", name = "second", totalListenMs = 100, playCount = 4)
        val tracks = mutableListOf(b, a)
        val buckets = mutableListOf(secondDayA, firstDayB, firstDayA)
        val originalTracks = tracks.toList()
        val originalBuckets = buckets.toList()

        val first = SyncPlaybackStatsMergePolicy.finalizeMergedStats(tracks, buckets)
        val second = SyncPlaybackStatsMergePolicy.finalizeMergedStats(tracks.asReversed(), buckets.asReversed())

        assertEquals(first, second)
        assertEquals(listOf(a, b), first.stats)
        assertEquals(listOf(firstDayA, firstDayB, secondDayA), first.buckets)
        assertEquals(originalTracks, tracks)
        assertEquals(originalBuckets, buckets)
        assertNotSame(buckets, first.buckets)
        assertSame(a, first.stats.first())
        assertSame(firstDayA, first.buckets.first())
    }

    @Test
    fun `already ordered statistics and buckets reuse their list references`() {
        val tracks = listOf(SyncTrackStat(identityKey = "a"), SyncTrackStat(identityKey = "b"))
        val buckets = listOf(
            SyncPlaybackStatBucket(dayStartAt = 10, identityKey = "a"),
            SyncPlaybackStatBucket(dayStartAt = 10, identityKey = "b"),
            SyncPlaybackStatBucket(dayStartAt = 20, identityKey = "a")
        )

        assertSame(tracks, SyncPlaybackStatsMergePolicy.finalizeMergedStats(tracks, emptyList()).stats)
        assertSame(buckets, SyncPlaybackStatsMergePolicy.finalizeMergedStats(tracks, buckets).buckets)
        assertSame(tracks, SyncPlaybackStatsOrderPolicy.stats(tracks))
        assertSame(buckets, SyncPlaybackStatsOrderPolicy.buckets(buckets))
    }

    @Test
    fun `empty and singleton finalized lists keep stable references`() {
        val emptyTracks = emptyList<SyncTrackStat>()
        val emptyBuckets = emptyList<SyncPlaybackStatBucket>()
        val oneTrack = listOf(SyncTrackStat(identityKey = "a"))
        val oneBucket = listOf(SyncPlaybackStatBucket(dayStartAt = 10, identityKey = "a"))

        val empty = SyncPlaybackStatsMergePolicy.finalizeMergedStats(emptyTracks, emptyBuckets)
        assertSame(emptyTracks, empty.stats)
        assertSame(emptyBuckets, empty.buckets)
        assertSame(oneTrack, SyncPlaybackStatsMergePolicy.finalizeMergedStats(oneTrack, emptyBuckets).stats)
        assertSame(oneBucket, SyncPlaybackStatsMergePolicy.finalizeMergedStats(oneTrack, oneBucket).buckets)
    }

    @Test
    fun `ordering follows full identity and day keys without overflow or record loss`() {
        val tracks = mutableListOf(
            SyncTrackStat(identityKey = "netease:2"),
            SyncTrackStat(identityKey = "netease:10"),
            SyncTrackStat(identityKey = "netease:2", name = "same key second value")
        )
        val buckets = mutableListOf(
            SyncPlaybackStatBucket(dayStartAt = Long.MAX_VALUE, identityKey = "a"),
            SyncPlaybackStatBucket(dayStartAt = Long.MIN_VALUE, identityKey = "b"),
            SyncPlaybackStatBucket(dayStartAt = Long.MIN_VALUE, identityKey = "a")
        )

        assertEquals(listOf(tracks[1], tracks[0], tracks[2]), SyncPlaybackStatsOrderPolicy.stats(tracks))
        assertEquals(listOf(buckets[2], buckets[1], buckets[0]), SyncPlaybackStatsOrderPolicy.buckets(buckets))
        assertEquals("netease:2", tracks.first().identityKey)
        assertEquals(Long.MAX_VALUE, buckets.first().dayStartAt)
    }

    @Test
    fun `finalize sorts bucket-only lifted identities and remains idempotent`() {
        val buckets = listOf(
            SyncPlaybackStatBucket(dayStartAt = 20, identityKey = "b", totalListenMs = 30, playCount = 1),
            SyncPlaybackStatBucket(dayStartAt = 10, identityKey = "a", totalListenMs = 40, playCount = 2)
        )

        val finalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(emptyList(), buckets)
        val repeated = SyncPlaybackStatsMergePolicy.finalizeMergedStats(finalized.stats, finalized.buckets)

        assertEquals(listOf("a", "b"), finalized.stats.map(SyncTrackStat::identityKey))
        assertEquals(listOf(40L, 30L), finalized.stats.map(SyncTrackStat::totalListenMs))
        assertEquals(finalized, repeated)
        assertSame(finalized.buckets, repeated.buckets)
    }
}
