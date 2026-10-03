package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.io.IOException
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsHotPlaylistPreview
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.ui.screen.history.stats.StatsQueryClock
import moe.ouom.neriplayer.ui.screen.history.stats.rememberStatsQueryDay
import moe.ouom.neriplayer.ui.screen.history.stats.statsQueryDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryHotPlaylistPreviewStateTest {
    @Test
    fun `midnight refreshes both preview rows and summaries without a statistics write`() = runTest {
        val fixture = PreviewFixture(this)
        try {
            fixture.start()
            fixture.pump()
            fixture.assertPreview(1L)
            assertEquals(2, fixture.pageQueries.size)

            advanceTimeBy(12L * 3_600_000L)
            fixture.pump()

            fixture.assertPreview(2L)
            fixture.assertQueriesAt(nextMidnight, totalReads = 4)
            assertEquals(1L, fixture.revisions.value)
        } finally { fixture.close() }
    }

    @Test
    fun `a retained hot composition refreshes after a day in background but not on a same day resume`() = runTest {
        val fixture = PreviewFixture(this)
        try {
            fixture.start()
            fixture.pump()
            val original = fixture.result
            fixture.resumed.value = false
            fixture.pump()
            advanceTimeBy(60_000L)
            fixture.resumed.value = true
            fixture.pump()
            assertSame(original, fixture.result)
            assertEquals(2, fixture.pageQueries.size)

            fixture.resumed.value = false
            fixture.pump()
            fixture.clock.setTime(nextMidnight)
            fixture.resumed.value = true
            fixture.pump()

            fixture.assertPreview(2L)
            fixture.assertQueriesAt(nextMidnight, totalReads = 4)
            assertEquals(1L, fixture.revisions.value)
        } finally { fixture.close() }
    }

    @Test
    fun `a timezone event rereads both periods even when the query instant and local midnight match`() = runTest {
        val fixture = PreviewFixture(this, "Africa/Casablanca")
        try {
            fixture.start()
            fixture.pump()
            fixture.assertPreview(1L)
            val originalStart = statsQueryDay(firstDay, fixture.clock.timeZone()).key.startMillis
            assertEquals(Instant.parse("2026-07-01T23:00:00Z").toEpochMilli(), originalStart)

            fixture.clock.zone = TimeZone.getTimeZone("Europe/London")
            assertEquals(originalStart, statsQueryDay(firstDay, fixture.clock.timeZone()).key.startMillis)
            fixture.timeChanges.emit(Unit)
            fixture.pump()

            fixture.assertPreview(1L)
            fixture.assertQueriesAt(firstDay, totalReads = 4)
            assertEquals(1L, fixture.revisions.value)
        } finally { fixture.close() }
    }

    @Test
    fun `same day time changes preserve previews while revision and retry still reload them`() = runTest {
        val fixture = PreviewFixture(this)
        try {
            fixture.start()
            fixture.pump()
            val original = fixture.result
            fixture.clock.setTime(firstDay + 60_000L)
            fixture.timeChanges.emit(Unit)
            fixture.pump()
            assertSame(original, fixture.result)
            assertEquals(2, fixture.pageQueries.size)

            fixture.revisions.value = 2L
            fixture.pump()
            fixture.assertPreview(1L)
            assertEquals(4, fixture.pageQueries.size)
            fixture.retry++
            fixture.pump()
            fixture.assertPreview(1L)
            assertEquals(6, fixture.pageQueries.size)
            assertEquals(6, fixture.summaryQueries.size)
        } finally { fixture.close() }
        assertEquals(0, fixture.revisions.subscriptionCount.value)
        assertEquals(0, fixture.timeChanges.subscriptionCount.value)
    }

    @Test
    fun `a failed preview exposes the failure and retry reloads the same sampled day`() = runTest {
        val fixture = PreviewFixture(this)
        val failure = IOException("fixture database unavailable")
        fixture.pageFailure = failure
        try {
            fixture.start()
            fixture.pump()
            assertSame(failure, fixture.result?.exceptionOrNull())
            assertEquals(1, fixture.pageQueries.size)

            fixture.pageFailure = null
            fixture.retry++
            fixture.pump()

            fixture.assertPreview(1L)
            assertEquals(3, fixture.pageQueries.size)
            assertEquals(2, fixture.summaryQueries.size)
            assertEquals(listOf(firstDay, firstDay), fixture.pageQueries.takeLast(2).map { it.nowMillis })
        } finally { fixture.close() }
    }

    @Test
    fun `a cancelled read is not exposed as a data failure and a new day can load again`() = runTest {
        val fixture = PreviewFixture(this)
        fixture.pageFailure = CancellationException("fixture cancelled read")
        try {
            fixture.start()
            fixture.pump()
            assertNull(fixture.result)
            assertEquals(1, fixture.pageQueries.size)

            fixture.pageFailure = null
            fixture.clock.setTime(nextMidnight)
            fixture.timeChanges.emit(Unit)
            fixture.pump()

            fixture.assertPreview(2L)
            assertEquals(3, fixture.pageQueries.size)
            assertEquals(2, fixture.summaryQueries.size)
            assertEquals(listOf(nextMidnight, nextMidnight), fixture.pageQueries.takeLast(2).map { it.nowMillis })
        } finally { fixture.close() }
    }

    private class PreviewFixture(private val scope: TestScope, zoneId: String = "UTC") {
        val clock = TestClock(scope, firstDay, zoneId)
        val resumed = MutableStateFlow(true)
        val timeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val revisions = MutableStateFlow(1L)
        val pageQueries = mutableListOf<PlaybackStatsQuery>()
        val summaryQueries = mutableListOf<PlaybackStatsQuery>()
        var pageFailure: Exception? = null
        var retry by mutableIntStateOf(0)
        var result: Result<List<PlaybackStatsHotPlaylistPreview>>? = null
            private set
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private var frameTime = 0L
        private val repository = mock(PlaybackStatsRepository::class.java) { call ->
            when (call.method.name) {
                "getRevisionFlow" -> revisions
                "readPage" -> {
                    val query = call.getArgument<PlaybackStatsQuery>(0)
                    assertEquals(null, call.getArgument<Any?>(1))
                    assertEquals(4, call.getArgument<Int>(2))
                    assertEquals(false, call.getArgument<Boolean>(3))
                    pageQueries += query
                    pageFailure?.let { throw it }
                    val id = generation(query) * 100 + if (query.period == PlaybackStatsPeriod.WEEK) 7 else 30
                    PlaybackStatsPage(listOf(track(id)), null)
                }
                "readSummary" -> {
                    val query = call.getArgument<PlaybackStatsQuery>(0)
                    summaryQueries += query
                    val count = generation(query) * if (query.period == PlaybackStatsPeriod.WEEK) 10 else 100
                    PlaybackStatsSummary(trackCount = 1, totalPlayCount = count, totalListenMs = count * 60_000L,
                        hasAnyStats = true)
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }

        fun start() {
            composition.setContent {
                val day by rememberStatsQueryDay(clock, resumed, timeChanges)
                result = rememberHotPlaylists(retry, day, repository)
            }
        }

        fun pump() {
            repeat(4) {
                scope.runCurrent()
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                frameClock.sendFrame(frameTime++)
            }
            scope.runCurrent()
        }

        fun assertPreview(generation: Long) {
            val previews = requireNotNull(result).getOrThrow()
            assertEquals(listOf(PlaybackStatsPeriod.WEEK, PlaybackStatsPeriod.MONTH), previews.map { it.period })
            assertEquals(listOf(generation * 100 + 7, generation * 100 + 30), previews.map { it.tracks.single().id })
            assertEquals(listOf(generation * 10, generation * 100), previews.map { it.totalPlayCount })
            assertEquals(listOf(1L, 1L), previews.map { it.trackCount })
        }

        fun assertQueriesAt(nowMillis: Long, totalReads: Int) {
            assertEquals(totalReads, pageQueries.size)
            assertEquals(totalReads, summaryQueries.size)
            assertEquals(listOf(nowMillis, nowMillis), pageQueries.takeLast(2).map { it.nowMillis })
            assertEquals(pageQueries.takeLast(2), summaryQueries.takeLast(2))
            assertEquals(listOf(600_000L, 1_800_000L), pageQueries.takeLast(2).map { it.minimumListenMs })
            assertEquals(listOf(true, true), pageQueries.takeLast(2).map { it.requirePlayCount })
            assertEquals(listOf(PlaybackStatsSort.PLAY_COUNT, PlaybackStatsSort.PLAY_COUNT), pageQueries.takeLast(2).map { it.sort })
        }

        suspend fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
            recomposer.join()
            scope.runCurrent()
        }

        private fun generation(query: PlaybackStatsQuery): Long =
            if (query.nowMillis >= nextMidnight && query.nowMillis < nextMidnight + 86_400_000L) 2 else 1

        private fun track(id: Long) = TrackStat(id, "fixture-$id", "artist", "album", 0, null,
            60_000, 1_800_000, 1, firstDay, firstDay, null, null, null, null, null, null, "fixture|$id")
    }

    private class TestClock(private val scope: TestScope, initial: Long, zoneId: String) : StatsQueryClock {
        private var baseTime = initial - scope.testScheduler.currentTime
        var zone = TimeZone.getTimeZone(zoneId)
        override fun nowMillis() = baseTime + scope.testScheduler.currentTime
        override fun timeZone(): TimeZone = zone
        fun setTime(time: Long) { baseTime = time - scope.testScheduler.currentTime }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private companion object {
        val firstDay = Instant.parse("2026-07-02T12:00:00Z").toEpochMilli()
        val nextMidnight = Instant.parse("2026-07-03T00:00:00Z").toEpochMilli()
    }
}
