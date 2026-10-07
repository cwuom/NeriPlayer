package moe.ouom.neriplayer.data.local.database.store.stats

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatsRoomCounterImportAccessTest {
    private val harness = PlaybackStatsSnapshotHarness()
    private val staged = harness.staged
    private val importer = PlaybackStatsRoomCounterImportAccess(harness.store)

    @Test
    fun `track counters fold duplicates and stored candidates of the same shard`() = runTest {
        staged.stage(counters = listOf(stagedCounter("a", "phone", 10, 500, 1, first = 100, last = 200), stagedCounter("a", "tablet", 10, 50, 1, first = 20, last = 30)))
        staged.stage(counters = listOf(stagedCounter("a", "phone", 10, 9_000, 9, first = 1, last = 2)), id = "other")

        importer.writeTracks(
            SNAPSHOT_ID,
            listOf(
                counter(stagedCounter("a", "phone", 10, 300, 3, first = 50, last = 400)),
                counter(stagedCounter("a", "phone", 10, 700, 2, first = 60, last = 300)),
                counter(stagedCounter("b", " ", 10, 100, 1, first = 1, last = 2))
            )
        )

        assertEquals(
            listOf(stagedCounter("a", "phone", 10, 700, 3, first = 50, last = 400), stagedCounter("a", "tablet", 10, 50, 1, first = 20, last = 30)),
            staged.storedCounters(SNAPSHOT_ID)
        )
        assertEquals(listOf(stagedCounter("a", "phone", 10, 9_000, 9, first = 1, last = 2)), staged.storedCounters("other"))
        val lookup = staged.candidateQueries.single()
        assertEquals(
            "WITH requested(identity_key,device_id,epoch_started_at) AS (VALUES (?,?,?)) SELECT s.* FROM requested " +
                "CROSS JOIN playback_stat_snapshot_counter s WHERE s.snapshot_id = ? AND s.identity_key = requested.identity_key " +
                "AND s.device_id = requested.device_id AND s.epoch_started_at = requested.epoch_started_at",
            lookup.sql
        )
        assertEquals(listOf<Any?>("a", "phone", 10L, SNAPSHOT_ID), lookup.boundArguments())
        assertEquals(listOf("begin", "commit", "end"), harness.room.transactionLog)
    }

    @Test
    fun `daily counters look candidates up in chunks that stay below the bind limit`() = runTest {
        staged.stage(dailyCounters = listOf(stagedDailyCounter(DAY_MS, "k0", "phone", 0, 5_000, 4, first = 5, last = 30)))
        val rows = List(200) { daily(stagedDailyCounter(DAY_MS, "k$it", "phone", 0, it + 1L, 1, first = 10, last = 20)) }

        importer.writeDaily(SNAPSHOT_ID, rows)

        val stored = staged.storedDailyCounters(SNAPSHOT_ID)
        assertEquals(200, stored.size)
        assertEquals(stagedDailyCounter(DAY_MS, "k0", "phone", 0, 5_000, 4, first = 5, last = 30), stored.single { it.identityKey == "k0" })
        assertEquals(stagedDailyCounter(DAY_MS, "k7", "phone", 0, 8, 1, first = 10, last = 20), stored.single { it.identityKey == "k7" })
        assertEquals(listOf(128 * 4 + 1, 72 * 4 + 1), staged.candidateQueries.map { it.argCount })
        assertTrue(staged.candidateQueries.all { it.sql.contains("CROSS JOIN playback_stat_snapshot_daily_counter s") })
        assertEquals(listOf<Any?>(SNAPSHOT_ID, SNAPSHOT_ID), staged.candidateQueries.map { it.boundArguments().last() })
    }

    @Test
    fun `pages beyond the budget or another snapshot are rejected and blank devices skip the transaction`() = runTest {
        val oversized = List(SYNC_PLAYBACK_PAGE_RECORDS + 1) { counter(stagedCounter("k$it", "phone", 0, 1, 1, first = 1, last = 2)) }
        val foreign = listOf(counter(stagedCounter("a", "phone", 0, 1, 1, first = 1, last = 2), id = "other"))
        val oversizedDaily = List(SYNC_PLAYBACK_PAGE_RECORDS + 1) { daily(stagedDailyCounter(DAY_MS, "k$it", "phone", 0, 1, 1, first = 1, last = 2)) }
        val foreignDaily = listOf(daily(stagedDailyCounter(DAY_MS, "a", "phone", 0, 1, 1, first = 1, last = 2), id = "other"))

        expectFailure<IllegalArgumentException> { importer.writeTracks(SNAPSHOT_ID, oversized) }
        expectFailure<IllegalArgumentException> { importer.writeTracks(SNAPSHOT_ID, foreign) }
        expectFailure<IllegalArgumentException> { importer.writeDaily(SNAPSHOT_ID, oversizedDaily) }
        expectFailure<IllegalArgumentException> { importer.writeDaily(SNAPSHOT_ID, foreignDaily) }
        importer.writeTracks(SNAPSHOT_ID, listOf(counter(stagedCounter("a", "", 0, 1, 1, first = 1, last = 2))))
        importer.writeDaily(SNAPSHOT_ID, listOf(daily(stagedDailyCounter(DAY_MS, "a", "  ", 0, 1, 1, first = 1, last = 2))))

        assertTrue(staged.storedCounters(SNAPSHOT_ID).isEmpty() && staged.storedDailyCounters(SNAPSHOT_ID).isEmpty())
        assertTrue(staged.candidateQueries.isEmpty())
        assertTrue(harness.room.transactionLog.isEmpty())
    }

    private fun counter(shard: PlaybackStatsSnapshotCounterData, id: String = SNAPSHOT_ID) = PlaybackStatsSnapshotCounterEntity(id, shard)

    private fun daily(shard: PlaybackStatsSnapshotDailyCounterData, id: String = SNAPSHOT_ID) = PlaybackStatsSnapshotDailyCounterEntity(id, shard)
}
