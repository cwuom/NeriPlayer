package moe.ouom.neriplayer.data.sync.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaybackStatsMergePolicyTest {
    @Test
    fun `clear barrier drops remote stats recorded before clear`() {
        val clearedAt = 1_000L
        val remote = trackStat(
            identityKey = "remote-old",
            firstPlayedAt = 100L,
            lastPlayedAt = 900L
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = emptyList(),
            remote = listOf(remote),
            playbackStatsClearedAt = clearedAt
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `clear barrier keeps stats created after clear`() {
        val clearedAt = 1_000L
        val remote = trackStat(
            identityKey = "remote-new",
            firstPlayedAt = 1_100L,
            lastPlayedAt = 1_200L
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = emptyList(),
            remote = listOf(remote),
            playbackStatsClearedAt = clearedAt
        )

        assertEquals(listOf(remote), merged)
    }

    @Test
    fun `clear barrier keeps stats updated after clear even when first play is old`() {
        val clearedAt = 1_000L
        val local = trackStat(
            identityKey = "local-resumed",
            firstPlayedAt = 100L,
            lastPlayedAt = 1_200L
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = listOf(local),
            remote = emptyList(),
            playbackStatsClearedAt = clearedAt
        )

        assertEquals(1, merged.size)
        assertEquals("local-resumed", merged.single().identityKey)
        assertEquals(1_200L, merged.single().firstPlayedAt)
        assertEquals(1_200L, merged.single().lastPlayedAt)
    }

    @Test
    fun `merge without clear barrier keeps larger counters`() {
        val local = trackStat(
            identityKey = "same",
            totalListenMs = 1_000L,
            playCount = 1,
            firstPlayedAt = 100L,
            lastPlayedAt = 200L
        )
        val remote = trackStat(
            identityKey = "same",
            totalListenMs = 2_000L,
            playCount = 3,
            firstPlayedAt = 50L,
            lastPlayedAt = 300L,
            name = "newer"
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = listOf(local),
            remote = listOf(remote),
            playbackStatsClearedAt = 0L
        )

        assertEquals(1, merged.size)
        assertEquals("newer", merged.single().name)
        assertEquals(2_000L, merged.single().totalListenMs)
        assertEquals(3, merged.single().playCount)
        assertEquals(50L, merged.single().firstPlayedAt)
        assertEquals(300L, merged.single().lastPlayedAt)
    }

    @Test
    fun `merge sharded counters sums independent device deltas once`() {
        val local = trackStat(
            identityKey = "same",
            totalListenMs = 1_500L,
            playCount = 12,
            firstPlayedAt = 100L,
            lastPlayedAt = 200L,
            counterBaseListenMs = 1_000L,
            counterBasePlayCount = 10,
            counterShards = listOf(counterShard("device-a", 500L, 2, 150L, 200L))
        )
        val remote = trackStat(
            identityKey = "same",
            totalListenMs = 1_700L,
            playCount = 13,
            firstPlayedAt = 100L,
            lastPlayedAt = 300L,
            name = "newer",
            counterBaseListenMs = 1_000L,
            counterBasePlayCount = 10,
            counterShards = listOf(counterShard("device-b", 700L, 3, 220L, 300L))
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = listOf(local),
            remote = listOf(remote),
            playbackStatsClearedAt = 0L
        )

        assertEquals(1, merged.size)
        assertEquals("newer", merged.single().name)
        assertEquals(2_200L, merged.single().totalListenMs)
        assertEquals(15, merged.single().playCount)
        assertEquals(2, merged.single().counterShards.size)

        val repeated = SyncPlaybackStatsMergePolicy.merge(
            local = merged,
            remote = listOf(remote),
            playbackStatsClearedAt = 0L
        )

        assertEquals(2_200L, repeated.single().totalListenMs)
        assertEquals(15, repeated.single().playCount)
    }

    @Test
    fun `merge mixed legacy and sharded counters does not double count legacy base`() {
        val legacyRemote = trackStat(
            identityKey = "same",
            totalListenMs = 2_000L,
            playCount = 10,
            firstPlayedAt = 100L,
            lastPlayedAt = 200L
        )
        val local = trackStat(
            identityKey = "same",
            totalListenMs = 2_300L,
            playCount = 11,
            firstPlayedAt = 100L,
            lastPlayedAt = 300L,
            counterBaseListenMs = 2_000L,
            counterBasePlayCount = 10,
            counterShards = listOf(counterShard("device-a", 300L, 1, 250L, 300L))
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = listOf(local),
            remote = listOf(legacyRemote),
            playbackStatsClearedAt = 0L
        )

        assertEquals(2_300L, merged.single().totalListenMs)
        assertEquals(11, merged.single().playCount)
        assertEquals(1, merged.single().counterShards.size)
    }

    @Test
    fun `merge buckets keeps per day breakdown for remote sync`() {
        val local = trackBucket(
            identityKey = "same",
            dayStartAt = 86_400_000L,
            totalListenMs = 1_000L,
            playCount = 1,
            firstPlayedAt = 86_401_000L,
            lastPlayedAt = 86_402_000L
        )
        val remote = trackBucket(
            identityKey = "same",
            dayStartAt = 86_400_000L,
            totalListenMs = 2_000L,
            playCount = 3,
            firstPlayedAt = 86_400_500L,
            lastPlayedAt = 86_403_000L,
            name = "newer"
        )

        val merged = SyncPlaybackStatsMergePolicy.mergeBuckets(
            local = listOf(local),
            remote = listOf(remote),
            playbackStatsClearedAt = 0L
        )

        assertEquals(1, merged.size)
        assertEquals(86_400_000L, merged.single().dayStartAt)
        assertEquals("newer", merged.single().name)
        assertEquals(2_000L, merged.single().totalListenMs)
        assertEquals(3, merged.single().playCount)
        assertEquals(86_400_500L, merged.single().firstPlayedAt)
        assertEquals(86_403_000L, merged.single().lastPlayedAt)
    }

    @Test
    fun `clear barrier drops stale buckets`() {
        val merged = SyncPlaybackStatsMergePolicy.mergeBuckets(
            local = emptyList(),
            remote = listOf(
                trackBucket(
                    identityKey = "old",
                    dayStartAt = 1_000L,
                    firstPlayedAt = 1_100L,
                    lastPlayedAt = 1_900L
                )
            ),
            playbackStatsClearedAt = 2_000L
        )

        assertTrue(merged.isEmpty())
    }

    private fun trackStat(
        identityKey: String,
        totalListenMs: Long = 1_000L,
        playCount: Int = 1,
        firstPlayedAt: Long,
        lastPlayedAt: Long,
        name: String = identityKey,
        counterBaseListenMs: Long = 0L,
        counterBasePlayCount: Int = 0,
        counterShards: List<SyncPlaybackCounterShard> = emptyList()
    ): SyncTrackStat {
        return SyncTrackStat(
            identityKey = identityKey,
            name = name,
            artist = "artist",
            album = "album",
            totalListenMs = totalListenMs,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            counterBaseListenMs = counterBaseListenMs,
            counterBasePlayCount = counterBasePlayCount,
            counterShards = counterShards
        )
    }

    private fun trackBucket(
        identityKey: String,
        dayStartAt: Long,
        totalListenMs: Long = 1_000L,
        playCount: Int = 1,
        firstPlayedAt: Long,
        lastPlayedAt: Long,
        name: String = identityKey
    ): SyncPlaybackStatBucket {
        return SyncPlaybackStatBucket(
            dayStartAt = dayStartAt,
            identityKey = identityKey,
            name = name,
            artist = "artist",
            album = "album",
            totalListenMs = totalListenMs,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt
        )
    }

    private fun counterShard(
        deviceId: String,
        totalListenMs: Long,
        playCount: Int,
        firstPlayedAt: Long,
        lastPlayedAt: Long
    ): SyncPlaybackCounterShard {
        return SyncPlaybackCounterShard(
            deviceId = deviceId,
            epochStartedAt = 0L,
            totalListenMs = totalListenMs,
            playCount = playCount,
            firstPlayedAt = firstPlayedAt,
            lastPlayedAt = lastPlayedAt
        )
    }
}
