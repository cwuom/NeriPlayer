package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
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
    fun `clear barrier rejects legacy totals spanning both sides of the clear`() {
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

        assertTrue(merged.isEmpty())
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

    // ---- P1-2 单调抬升 (消除"年 > 总") ----

    @Test
    fun `lift raises stat total to bucket sum`() {
        val day = 86_400_000L
        val stats = listOf(
            trackStat(identityKey = "song", totalListenMs = 100L, playCount = 1, firstPlayedAt = 1L, lastPlayedAt = 2L)
        )
        val buckets = listOf(
            trackBucket(identityKey = "song", dayStartAt = 1 * day, totalListenMs = 120L, playCount = 2, firstPlayedAt = 1L, lastPlayedAt = 2L),
            trackBucket(identityKey = "song", dayStartAt = 2 * day, totalListenMs = 180L, playCount = 3, firstPlayedAt = 1L, lastPlayedAt = 2L)
        )

        val lifted = SyncPlaybackStatsMergePolicy.liftStatsToBucketTotals(stats, buckets)

        assertEquals(300L, lifted.single().totalListenMs)
        assertEquals(5, lifted.single().playCount)
    }

    @Test
    fun `lift never lowers stat total`() {
        val day = 86_400_000L
        val stats = listOf(
            trackStat(identityKey = "song", totalListenMs = 500L, playCount = 9, firstPlayedAt = 1L, lastPlayedAt = 2L)
        )
        val buckets = listOf(
            trackBucket(identityKey = "song", dayStartAt = 1 * day, totalListenMs = 120L, playCount = 2, firstPlayedAt = 1L, lastPlayedAt = 2L)
        )

        val lifted = SyncPlaybackStatsMergePolicy.liftStatsToBucketTotals(stats, buckets)

        assertEquals(500L, lifted.single().totalListenMs)
        assertEquals(9, lifted.single().playCount)
    }

    @Test
    fun `lift guarantees total not below bucket sum and is idempotent`() {
        val day = 86_400_000L
        val stats = listOf(
            trackStat(identityKey = "song", totalListenMs = 100L, playCount = 1, firstPlayedAt = 1L, lastPlayedAt = 2L)
        )
        val buckets = listOf(
            trackBucket(identityKey = "song", dayStartAt = 1 * day, totalListenMs = 200L, playCount = 4, firstPlayedAt = 1L, lastPlayedAt = 2L)
        )

        val once = SyncPlaybackStatsMergePolicy.liftStatsToBucketTotals(stats, buckets)
        val twice = SyncPlaybackStatsMergePolicy.liftStatsToBucketTotals(once, buckets)

        val bucketSum = buckets.filter { it.identityKey == "song" }.sumOf { it.totalListenMs }
        // "总">="同曲分桶之和", 杜绝"年 > 总"
        assertTrue(once.single().totalListenMs >= bucketSum)
        assertEquals(once, twice)
    }

    @Test
    fun `lift creates aggregate stat when legacy data only has daily buckets`() {
        val day = 86_400_000L
        val buckets = listOf(
            trackBucket(
                identityKey = "bucket-only",
                dayStartAt = day,
                totalListenMs = 120L,
                playCount = 2,
                firstPlayedAt = day + 1L,
                lastPlayedAt = day + 2L,
                name = "first"
            ),
            trackBucket(
                identityKey = "bucket-only",
                dayStartAt = 2 * day,
                totalListenMs = 180L,
                playCount = 3,
                firstPlayedAt = 2 * day + 1L,
                lastPlayedAt = 2 * day + 2L,
                name = "latest"
            )
        )

        val once = SyncPlaybackStatsMergePolicy.liftStatsToBucketTotals(emptyList(), buckets)
        val twice = SyncPlaybackStatsMergePolicy.liftStatsToBucketTotals(once, buckets)
        val finalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(emptyList(), buckets)

        assertEquals(1, once.size)
        assertEquals("bucket-only", once.single().identityKey)
        assertEquals("latest", once.single().name)
        assertEquals(300L, once.single().totalListenMs)
        assertEquals(5, once.single().playCount)
        assertEquals(day + 1L, once.single().firstPlayedAt)
        assertEquals(2 * day + 2L, once.single().lastPlayedAt)
        assertEquals(once, twice)
        assertEquals(once, finalized.stats)
    }

    @Test
    fun `bucket only stats from separate devices converge into one total`() {
        val day = 86_400_000L
        val localBuckets = listOf(
            trackBucket(
                identityKey = "bucket-only",
                dayStartAt = day,
                totalListenMs = 120L,
                playCount = 2,
                firstPlayedAt = day + 1L,
                lastPlayedAt = day + 2L
            )
        )
        val remoteBuckets = listOf(
            trackBucket(
                identityKey = "bucket-only",
                dayStartAt = 2 * day,
                totalListenMs = 180L,
                playCount = 3,
                firstPlayedAt = 2 * day + 1L,
                lastPlayedAt = 2 * day + 2L
            )
        )

        val finalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
            mergedStats = SyncPlaybackStatsMergePolicy.merge(
                local = emptyList(),
                remote = emptyList(),
                playbackStatsClearedAt = 0L
            ),
            mergedBuckets = SyncPlaybackStatsMergePolicy.mergeBuckets(
                local = localBuckets,
                remote = remoteBuckets,
                playbackStatsClearedAt = 0L
            )
        )
        val replayed = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
            mergedStats = SyncPlaybackStatsMergePolicy.merge(
                local = finalized.stats,
                remote = emptyList(),
                playbackStatsClearedAt = 0L
            ),
            mergedBuckets = SyncPlaybackStatsMergePolicy.mergeBuckets(
                local = finalized.buckets,
                remote = remoteBuckets,
                playbackStatsClearedAt = 0L
            )
        )

        assertEquals(1, finalized.stats.size)
        assertEquals(300L, finalized.stats.single().totalListenMs)
        assertEquals(5, finalized.stats.single().playCount)
        assertEquals(finalized, replayed)
    }

    // ---- M1 收尾顺序: lift 必须用"未裁剪"桶 (与桌面 three_way_merge 逐字对齐) ----

    @Test
    fun `finalizeMergedStats lifts using untrimmed buckets covering out-of-window sum`() {
        val day = 86_400_000L
        val newestDay = 500 * day
        val stats = listOf(
            trackStat(
                identityKey = "song",
                totalListenMs = 100L,
                playCount = 1,
                firstPlayedAt = 1L,
                lastPlayedAt = newestDay
            )
        )
        // 较早日桶仍参与总数，并与新日桶一起保留
        val buckets = listOf(
            trackBucket(
                identityKey = "song",
                dayStartAt = newestDay,
                totalListenMs = 120L,
                playCount = 2,
                firstPlayedAt = 1L,
                lastPlayedAt = newestDay
            ),
            trackBucket(
                identityKey = "song",
                dayStartAt = newestDay - 401 * day,
                totalListenMs = 50L,
                playCount = 1,
                firstPlayedAt = 1L,
                lastPlayedAt = newestDay - 401 * day
            )
        )

        val finalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(stats, buckets)

        // 全量桶之和 = 120 + 50 = 170, 2 + 1 = 3; 若回退成"先裁剪再抬升"则只会得到 120 / 2
        assertEquals(170L, finalized.stats.single().totalListenMs)
        assertEquals(3, finalized.stats.single().playCount)
        assertEquals(setOf(newestDay, newestDay - 401 * day), finalized.buckets.map { it.dayStartAt }.toSet())
    }

    @Test
    fun `finalizeMergedStats retains every identity represented by stats or buckets`() {
        val day = 86_400_000L
        val newestDay = 10 * day
        // 日桶中的独立曲目必须补入汇总，旧条目不能因为超出曾经的上限而丢失
        val stats = (1..(2_005)).map { i ->
            trackStat(identityKey = "s-$i", firstPlayedAt = 1L, lastPlayedAt = i.toLong())
        }
        val buckets = (1..(8_005)).map { i ->
            trackBucket(
                identityKey = "b-$i",
                dayStartAt = newestDay,
                playCount = i,
                firstPlayedAt = 1L,
                lastPlayedAt = newestDay
            )
        }

        val finalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(stats, buckets)

        assertEquals(10_010, finalized.stats.size)
        assertEquals(8_005, finalized.buckets.size)
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
