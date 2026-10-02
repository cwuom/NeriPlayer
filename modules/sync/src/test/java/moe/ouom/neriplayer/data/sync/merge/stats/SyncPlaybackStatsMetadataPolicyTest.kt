package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaybackStatsMetadataPolicyTest {
    @Test
    fun `equal timestamp track metadata converges both directions without changing counter merge`() {
        val left = stat().copy(
            name = "a", artist = "left artist", album = "left album", coverUrl = "left cover",
            durationMs = 20, mediaUri = "left media", id = 1, albumId = 2,
            totalListenMs = 150, playCount = 6, counterBaseListenMs = 100, counterBasePlayCount = 5,
            counterShards = listOf(SyncPlaybackCounterShard("device-a", 0, 50, 1, 100, 200))
        )
        val right = stat().copy(
            name = "z", artist = "right artist", album = "right album", coverUrl = "right cover",
            durationMs = 30, mediaUri = "right media", id = 3, albumId = 4,
            totalListenMs = 170, playCount = 7, counterBaseListenMs = 100, counterBasePlayCount = 5,
            counterShards = listOf(SyncPlaybackCounterShard("device-b", 0, 70, 2, 100, 200))
        )

        val merged = merge(left, right)
        assertEquals(merged, merge(right, left))
        assertEquals(metadata(right), metadata(merged))
        assertEquals(220L, merged.totalListenMs)
        assertEquals(8, merged.playCount)
        assertEquals(100L, merged.counterBaseListenMs)
        assertEquals(5, merged.counterBasePlayCount)
        assertEquals(left.counterShards + right.counterShards, merged.counterShards)
        assertEquals(merged, merge(merged, left))
        assertEquals(merged, merge(right, merged))
        assertEquals(merged, merge(merge(left, merged), right))
    }

    @Test
    fun `equal timestamp bucket metadata converges both directions and repeated round trips`() {
        val left = bucket().copy(
            name = "a", artist = "left artist", album = "left album", coverUrl = "left cover",
            durationMs = 20, mediaUri = "left media", id = 1, albumId = 2,
            totalListenMs = 150, playCount = 6, counterBaseListenMs = 100, counterBasePlayCount = 5,
            counterShards = listOf(SyncPlaybackCounterShard("device-a", 0, 50, 1, 100, 200))
        )
        val right = bucket().copy(
            name = "z", artist = "right artist", album = "right album", coverUrl = "right cover",
            durationMs = 30, mediaUri = "right media", id = 3, albumId = 4,
            totalListenMs = 170, playCount = 7, counterBaseListenMs = 100, counterBasePlayCount = 5,
            counterShards = listOf(SyncPlaybackCounterShard("device-b", 0, 70, 2, 100, 200))
        )

        val merged = merge(left, right)
        assertEquals(merged, merge(right, left))
        assertEquals(metadata(right), metadata(merged))
        assertEquals(220L, merged.totalListenMs)
        assertEquals(8, merged.playCount)
        assertEquals(left.dayStartAt, merged.dayStartAt)
        assertEquals(100L, merged.counterBaseListenMs)
        assertEquals(5, merged.counterBasePlayCount)
        assertEquals(left.counterShards + right.counterShards, merged.counterShards)
        assertEquals(merged, merge(merged, left))
        assertEquals(merged, merge(right, merged))
        assertEquals(merged, merge(merge(left, merged), right))
    }

    @Test
    fun `every adopted track metadata field participates in equal timestamp ordering`() {
        val original = stat()
        val changed = listOf(
            original.copy(name = "z"), original.copy(artist = "z"), original.copy(album = "z"),
            original.copy(coverUrl = ""), original.copy(durationMs = 11),
            original.copy(mediaUri = ""), original.copy(id = 11), original.copy(albumId = 11)
        )

        for (candidate in changed) {
            val forward = merge(original, candidate)
            assertEquals(metadata(candidate), metadata(forward))
            assertEquals(forward, merge(candidate, original))
            assertEquals(forward, merge(forward, original))
        }
    }

    @Test
    fun `every adopted bucket metadata field participates in equal timestamp ordering`() {
        val original = bucket()
        val changed = listOf(
            original.copy(name = "z"), original.copy(artist = "z"), original.copy(album = "z"),
            original.copy(coverUrl = ""), original.copy(durationMs = 11),
            original.copy(mediaUri = ""), original.copy(id = 11), original.copy(albumId = 11)
        )

        for (candidate in changed) {
            val forward = merge(original, candidate)
            assertEquals(metadata(candidate), metadata(forward))
            assertEquals(forward, merge(candidate, original))
            assertEquals(forward, merge(forward, original))
        }
    }

    @Test
    fun `last played time has priority over all metadata tie breakers`() {
        val oldStat = stat().copy(name = "z", lastPlayedAt = 199)
        val newStat = stat().copy(name = "a", lastPlayedAt = 200)
        val oldBucket = bucket().copy(name = "z", lastPlayedAt = 199)
        val newBucket = bucket().copy(name = "a", lastPlayedAt = 200)

        assertEquals(metadata(newStat), metadata(merge(oldStat, newStat)))
        assertEquals(merge(oldStat, newStat), merge(newStat, oldStat))
        assertEquals(metadata(newBucket), metadata(merge(oldBucket, newBucket)))
        assertEquals(merge(oldBucket, newBucket), merge(newBucket, oldBucket))
    }

    @Test
    fun `metadata tie breakers preserve clear barriers and legacy max counters`() {
        val oldStat = stat().copy(name = "z", lastPlayedAt = 199, totalListenMs = 1_000, playCount = 20)
        val newStat = stat().copy(name = "a", firstPlayedAt = 200, lastPlayedAt = 200, totalListenMs = 30, playCount = 1)
        val oldBucket = bucket().copy(name = "z", lastPlayedAt = 199, totalListenMs = 1_000, playCount = 20)
        val newBucket = bucket().copy(name = "a", firstPlayedAt = 200, lastPlayedAt = 200, totalListenMs = 30, playCount = 1)

        val uncleared = merge(oldStat, newStat)
        assertEquals(1_000L, uncleared.totalListenMs)
        assertEquals(20, uncleared.playCount)
        val clearedStat = SyncPlaybackStatsMergePolicy.merge(listOf(oldStat), listOf(newStat), 200).single()
        val clearedBucket = SyncPlaybackStatsMergePolicy.mergeBuckets(listOf(oldBucket), listOf(newBucket), 200).single()
        assertEquals(30L, clearedStat.totalListenMs)
        assertEquals(1, clearedStat.playCount)
        assertEquals(200L, clearedStat.firstPlayedAt)
        assertEquals(30L, clearedBucket.totalListenMs)
        assertEquals(1, clearedBucket.playCount)
        assertEquals(200L, clearedBucket.firstPlayedAt)
    }

    private fun stat() = SyncTrackStat(
        identityKey = "same", name = "a", artist = "a", album = "a", durationMs = 10,
        id = 10, albumId = 10, totalListenMs = 30, playCount = 1, firstPlayedAt = 100, lastPlayedAt = 200
    )

    private fun bucket() = SyncPlaybackStatBucket(
        dayStartAt = 10, identityKey = "same", name = "a", artist = "a", album = "a", durationMs = 10,
        id = 10, albumId = 10, totalListenMs = 30, playCount = 1, firstPlayedAt = 100, lastPlayedAt = 200
    )

    private fun merge(left: SyncTrackStat, right: SyncTrackStat): SyncTrackStat =
        SyncPlaybackStatsMergePolicy.merge(listOf(left), listOf(right), 0).single()

    private fun merge(left: SyncPlaybackStatBucket, right: SyncPlaybackStatBucket): SyncPlaybackStatBucket =
        SyncPlaybackStatsMergePolicy.mergeBuckets(listOf(left), listOf(right), 0).single()

    private fun metadata(stat: SyncTrackStat): List<Any?> = listOf(
        stat.name, stat.artist, stat.album, stat.coverUrl, stat.durationMs, stat.mediaUri, stat.id, stat.albumId
    )

    private fun metadata(bucket: SyncPlaybackStatBucket): List<Any?> = listOf(
        bucket.name, bucket.artist, bucket.album, bucket.coverUrl, bucket.durationMs, bucket.mediaUri, bucket.id, bucket.albumId
    )
}
