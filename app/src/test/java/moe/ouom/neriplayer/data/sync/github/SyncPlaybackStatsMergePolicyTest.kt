package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.model.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- P0-2 裁剪：曲目统计上限 ----

    @Test
    fun `trimStats caps to max keeping most recent`() {
        val stats = (1..(SyncPlaybackStatsMergePolicy.MAX_TRACK_STATS + 1)).map { i ->
            trackStat(identityKey = "k-$i", firstPlayedAt = 1L, lastPlayedAt = i.toLong())
        }

        val trimmed = SyncPlaybackStatsMergePolicy.trimStats(stats)

        assertEquals(SyncPlaybackStatsMergePolicy.MAX_TRACK_STATS, trimmed.size)
        // lastPlayedAt = 1 的一条被裁掉，仅保留 2..MAX+1
        assertEquals(2L, trimmed.minOf { it.lastPlayedAt })
    }

    @Test
    fun `trimStats keeps deterministic ascending identityKey on ties at boundary`() {
        val high = (1..(SyncPlaybackStatsMergePolicy.MAX_TRACK_STATS - 1)).map { i ->
            trackStat(identityKey = "high-$i", firstPlayedAt = 1L, lastPlayedAt = 1_000L + i)
        }
        val tieKept = trackStat(identityKey = "tie-a", firstPlayedAt = 1L, lastPlayedAt = 50L)
        val tieDropped = trackStat(identityKey = "tie-b", firstPlayedAt = 1L, lastPlayedAt = 50L)

        val trimmed = SyncPlaybackStatsMergePolicy.trimStats(high + tieDropped + tieKept)

        assertEquals(SyncPlaybackStatsMergePolicy.MAX_TRACK_STATS, trimmed.size)
        val keys = trimmed.map { it.identityKey }.toSet()
        assertTrue(keys.contains("tie-a"))
        assertFalse(keys.contains("tie-b"))
    }

    @Test
    fun `trimStats is idempotent`() {
        val stats = (1..(SyncPlaybackStatsMergePolicy.MAX_TRACK_STATS + 5)).map { i ->
            trackStat(identityKey = "k-$i", firstPlayedAt = 1L, lastPlayedAt = i.toLong())
        }

        val once = SyncPlaybackStatsMergePolicy.trimStats(stats)
        val twice = SyncPlaybackStatsMergePolicy.trimStats(once)

        assertEquals(once, twice)
    }

    @Test
    fun `trimStats under cap is unchanged`() {
        val stats = listOf(
            trackStat(identityKey = "a", firstPlayedAt = 1L, lastPlayedAt = 10L),
            trackStat(identityKey = "b", firstPlayedAt = 1L, lastPlayedAt = 20L)
        )

        assertEquals(stats, SyncPlaybackStatsMergePolicy.trimStats(stats))
    }

    // ---- P0-2 裁剪：日桶 400 天窗口 + 数量上限 ----

    @Test
    fun `trimBuckets drops buckets outside retention window`() {
        val day = 86_400_000L
        val newest = trackBucket(identityKey = "a", dayStartAt = 500 * day, firstPlayedAt = 1L, lastPlayedAt = 500 * day)
        val within = trackBucket(identityKey = "b", dayStartAt = 200 * day, firstPlayedAt = 1L, lastPlayedAt = 200 * day)
        val outside = trackBucket(identityKey = "c", dayStartAt = 50 * day, firstPlayedAt = 1L, lastPlayedAt = 50 * day)

        val trimmed = SyncPlaybackStatsMergePolicy.trimBuckets(listOf(newest, within, outside))

        val days = trimmed.map { it.dayStartAt }.toSet()
        assertTrue(days.contains(500 * day))
        assertTrue(days.contains(200 * day))
        assertFalse(days.contains(50 * day))
    }

    @Test
    fun `trimBuckets window anchor uses dataset max not wall clock`() {
        val day = 86_400_000L
        // 全部为「远古」日桶：若锚点用墙钟会被全部裁掉；用数据集内最大 dayStartAt 则应全部保留
        val ancient = listOf(
            trackBucket(identityKey = "a", dayStartAt = 1 * day, firstPlayedAt = 1L, lastPlayedAt = 1 * day),
            trackBucket(identityKey = "b", dayStartAt = 2 * day, firstPlayedAt = 1L, lastPlayedAt = 2 * day),
            trackBucket(identityKey = "c", dayStartAt = 3 * day, firstPlayedAt = 1L, lastPlayedAt = 3 * day)
        )

        val trimmed = SyncPlaybackStatsMergePolicy.trimBuckets(ancient)

        assertEquals(3, trimmed.size)
    }

    @Test
    fun `trimBuckets caps to max buckets by playCount`() {
        val day = 86_400_000L
        val buckets = (1..(SyncPlaybackStatsMergePolicy.MAX_STAT_BUCKETS + 1)).map { i ->
            trackBucket(
                identityKey = "k-$i",
                dayStartAt = 10 * day,
                playCount = i,
                firstPlayedAt = 1L,
                lastPlayedAt = 10 * day
            )
        }

        val trimmed = SyncPlaybackStatsMergePolicy.trimBuckets(buckets)

        assertEquals(SyncPlaybackStatsMergePolicy.MAX_STAT_BUCKETS, trimmed.size)
        // playCount 最小(=1)的一条按 playCount 降序被裁掉
        assertFalse(trimmed.any { it.playCount == 1 })
    }

    @Test
    fun `trimBuckets is idempotent across window and count`() {
        val day = 86_400_000L
        val buckets = buildList {
            addAll((1..(SyncPlaybackStatsMergePolicy.MAX_STAT_BUCKETS + 3)).map { i ->
                trackBucket(identityKey = "k-$i", dayStartAt = 500 * day, playCount = i, firstPlayedAt = 1L, lastPlayedAt = 500 * day)
            })
            add(trackBucket(identityKey = "old", dayStartAt = 10 * day, firstPlayedAt = 1L, lastPlayedAt = 10 * day))
        }

        val once = SyncPlaybackStatsMergePolicy.trimBuckets(buckets)
        val twice = SyncPlaybackStatsMergePolicy.trimBuckets(once)

        assertEquals(once, twice)
    }

    // ---- P1-2 单调抬升（消除「年 > 总」）----

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
        // 「总」>=「同曲分桶之和」，杜绝「年 > 总」
        assertTrue(once.single().totalListenMs >= bucketSum)
        assertEquals(once, twice)
    }

    // ---- M1 收尾顺序：lift 必须用「未裁剪」桶（与桌面 three_way_merge 逐字对齐）----

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
        // 同一曲：窗口内当天一桶 + 窗口外(>400 天)一桶。窗口外桶会被 trimBuckets 裁掉，
        // 但收尾必须先用「未裁剪」全量桶抬升，故「总」应覆盖两桶之和（与桌面一致）
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

        // 全量桶之和 = 120 + 50 = 170、2 + 1 = 3；若回退成「先裁剪再抬升」则只会得到 120 / 2
        assertEquals(170L, finalized.stats.single().totalListenMs)
        assertEquals(3, finalized.stats.single().playCount)
        // 窗口外桶已被裁剪，仅保留窗口内当天桶
        assertEquals(setOf(newestDay), finalized.buckets.map { it.dayStartAt }.toSet())
    }

    @Test
    fun `finalizeMergedStats applies stat and bucket count caps`() {
        val day = 86_400_000L
        val newestDay = 10 * day
        // 曲目统计与日桶数量分别超上限；收尾（GitHub 与 WebDAV 共用同一函数）应把两者裁到各自上限
        val stats = (1..(SyncPlaybackStatsMergePolicy.MAX_TRACK_STATS + 5)).map { i ->
            trackStat(identityKey = "s-$i", firstPlayedAt = 1L, lastPlayedAt = i.toLong())
        }
        val buckets = (1..(SyncPlaybackStatsMergePolicy.MAX_STAT_BUCKETS + 5)).map { i ->
            trackBucket(
                identityKey = "b-$i",
                dayStartAt = newestDay,
                playCount = i,
                firstPlayedAt = 1L,
                lastPlayedAt = newestDay
            )
        }

        val finalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(stats, buckets)

        assertEquals(SyncPlaybackStatsMergePolicy.MAX_TRACK_STATS, finalized.stats.size)
        assertEquals(SyncPlaybackStatsMergePolicy.MAX_STAT_BUCKETS, finalized.buckets.size)
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
