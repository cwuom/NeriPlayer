package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackStatsQueryStateTest {
    @Test
    fun `statistics query and page reset when the clock enters another local day`() = runTest {
        for (period in listOf(PlaybackStatsPeriod.DAY, PlaybackStatsPeriod.WEEK, PlaybackStatsPeriod.MONTH)) {
            val fixture = QueryCompositionFixture(this, firstDay) { now, zone ->
                rememberPlaybackStatsQuery(period, PlaybackStatsSort.PLAY_COUNT, now, zone)
            }
            try {
                fixture.start()
                fixture.pump()
                fixture.request = StatsPageRequest(offset = 100)
                fixture.pump()
                assertEquals(100, fixture.request.offset)

                fixture.nowMillis = nextDay
                fixture.pump()

                assertEquals(nextDay, fixture.query.nowMillis)
                assertEquals(0, fixture.request.offset)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `hot playlist query and page reset when a resumed clock has advanced a day`() = runTest {
        for (period in listOf(PlaybackStatsPeriod.WEEK, PlaybackStatsPeriod.MONTH)) {
            val fixture = QueryCompositionFixture(this, firstDay) { now, zone ->
                rememberHotPlaybackStatsQuery(period, now, zone)
            }
            try {
                fixture.start()
                fixture.pump()
                fixture.request = StatsPageRequest(offset = 100)
                fixture.pump()

                fixture.nowMillis = nextDay
                fixture.pump()

                assertEquals(nextDay, fixture.query.nowMillis)
                assertEquals(0, fixture.request.offset)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `a same day clock update preserves the query and selected page`() = runTest {
        val fixture = QueryCompositionFixture(this, firstDay) { now, zone ->
            rememberPlaybackStatsQuery(PlaybackStatsPeriod.DAY, PlaybackStatsSort.PLAY_COUNT, now, zone)
        }
        try {
            fixture.start()
            fixture.pump()
            val query = fixture.query
            fixture.request = StatsPageRequest(offset = 100)
            fixture.pump()

            fixture.nowMillis += 60_000L
            fixture.pump()

            assertSame(query, fixture.query)
            assertEquals(100, fixture.request.offset)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `midnight timer follows short and long daylight saving days`() = runTest {
        val days = listOf("2026-03-28T23:00:00Z" to 23L, "2026-10-24T22:00:00Z" to 25L)
        for ((instant, hours) in days) {
            val start = Instant.parse(instant).toEpochMilli()
            val clock = TestClock(this, start, "Europe/Berlin")
            val fixture = QueryCompositionFixture(this, start, clock) { now, zone ->
                rememberPlaybackStatsQuery(PlaybackStatsPeriod.DAY, PlaybackStatsSort.PLAY_COUNT, now, zone)
            }
            try {
                fixture.start()
                fixture.pump()
                val original = fixture.query
                fixture.request = StatsPageRequest(offset = 100)
                fixture.pump()

                advanceTimeBy(hours * 3_600_000L - 1L)
                fixture.pump()
                assertSame(original, fixture.query)
                assertEquals(100, fixture.request.offset)

                advanceTimeBy(1L)
                fixture.pump()
                assertEquals(start + hours * 3_600_000L, fixture.query.nowMillis)
                assertEquals(0, fixture.request.offset)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `resume preserves the same day page and refreshes after a day in background`() = runTest {
        val clock = TestClock(this, firstDay)
        val fixture = QueryCompositionFixture(this, firstDay, clock) { now, zone ->
            rememberHotPlaybackStatsQuery(PlaybackStatsPeriod.WEEK, now, zone)
        }
        try {
            fixture.start()
            fixture.pump()
            val original = fixture.query
            fixture.request = StatsPageRequest(offset = 100)
            fixture.pump()
            fixture.resumed.value = false
            fixture.pump()
            val reads = clock.reads
            advanceTimeBy(60_000L)
            fixture.pump()
            assertEquals(reads, clock.reads)
            assertEquals(0, fixture.timeChanges.subscriptionCount.value)

            fixture.resumed.value = true
            fixture.pump()
            assertSame(original, fixture.query)
            assertEquals(100, fixture.request.offset)

            fixture.resumed.value = false
            fixture.pump()
            clock.setTime(nextDay)
            fixture.resumed.value = true
            fixture.pump()
            assertEquals(nextDay, fixture.query.nowMillis)
            assertEquals(0, fixture.request.offset)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `time change refreshes after clock rollback and rearms midnight within the same day`() = runTest {
        val clock = TestClock(this, nextDay)
        val fixture = QueryCompositionFixture(this, nextDay, clock) { now, zone ->
            rememberPlaybackStatsQuery(PlaybackStatsPeriod.MONTH, PlaybackStatsSort.PLAY_COUNT, now, zone)
        }
        try {
            fixture.start()
            fixture.pump()
            fixture.request = StatsPageRequest(offset = 100)
            fixture.pump()
            clock.setTime(firstDay)
            fixture.timeChanges.emit(Unit)
            fixture.pump()
            assertEquals(firstDay, fixture.query.nowMillis)
            assertEquals(0, fixture.request.offset)

            val original = fixture.query
            fixture.request = StatsPageRequest(offset = 100)
            fixture.pump()
            clock.setTime(Instant.parse("2026-10-01T23:59:00Z").toEpochMilli())
            fixture.timeChanges.emit(Unit)
            fixture.pump()
            assertSame(original, fixture.query)
            assertEquals(100, fixture.request.offset)
            advanceTimeBy(60_000L)
            fixture.pump()
            assertEquals(Instant.parse("2026-10-02T00:00:00Z").toEpochMilli(), fixture.query.nowMillis)
            assertEquals(0, fixture.request.offset)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a zone change rereads data when the query timestamp and current midnight stay equal`() = runTest {
        val clock = TestClock(this, firstDay, "Africa/Casablanca")
        val query = PlaybackStatsQuery(PlaybackStatsPeriod.YEAR, nowMillis = firstDay)
        val repository = mock(PlaybackStatsRepository::class.java)
        `when`(repository.revisionFlow).thenReturn(MutableStateFlow(1L))
        `when`(repository.readSummary(query)).thenReturn(PlaybackStatsSummary())
        `when`(repository.readPage(query, null, 100, false)).thenReturn(PlaybackStatsPage(emptyList(), null))
        val fixture = QueryCompositionFixture(this, firstDay, clock, repository) { now, zone ->
            rememberPlaybackStatsQuery(PlaybackStatsPeriod.YEAR, PlaybackStatsSort.PLAY_COUNT, now, zone)
        }
        try {
            fixture.start()
            fixture.pump()
            val original = fixture.query
            val midnight = statsQueryDay(firstDay, clock.timeZone()).key.startMillis
            fixture.request = StatsPageRequest(offset = 100)
            fixture.pump()
            verify(repository, times(2)).readPage(query, null, 100, false)

            clock.zone = TimeZone.getTimeZone("Europe/London")
            assertEquals(midnight, statsQueryDay(firstDay, clock.timeZone()).key.startMillis)
            fixture.timeChanges.emit(Unit)
            fixture.pump()

            assertEquals(original, fixture.query)
            assertEquals(0, fixture.request.offset)
            verify(repository, times(3)).readPage(query, null, 100, false)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `all time query keeps its selected page when the date or zone changes`() = runTest {
        val clock = TestClock(this, firstDay)
        val fixture = QueryCompositionFixture(this, firstDay, clock) { now, zone ->
            rememberPlaybackStatsQuery(PlaybackStatsPeriod.ALL, PlaybackStatsSort.PLAY_COUNT, now, zone)
        }
        try {
            fixture.start()
            fixture.pump()
            val original = fixture.query
            fixture.request = StatsPageRequest(offset = 100)
            fixture.pump()
            clock.setTime(nextDay)
            clock.zone = TimeZone.getTimeZone("Asia/Taipei")
            fixture.timeChanges.emit(Unit)
            fixture.pump()
            assertSame(original, fixture.query)
            assertEquals(100, fixture.request.offset)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `disposing the page cancels its midnight timer and event subscription`() = runTest {
        val clock = TestClock(this, firstDay)
        val fixture = QueryCompositionFixture(this, firstDay, clock) { now, zone ->
            rememberHotPlaybackStatsQuery(PlaybackStatsPeriod.MONTH, now, zone)
        }
        try {
            fixture.start()
            fixture.pump()
            assertEquals(1, fixture.timeChanges.subscriptionCount.value)
        } finally {
            fixture.close()
        }
        runCurrent()
        val reads = clock.reads

        assertEquals(0, fixture.timeChanges.subscriptionCount.value)
        fixture.timeChanges.emit(Unit)
        advanceTimeBy(2L * 86_400_000L)
        runCurrent()
        assertEquals(reads, clock.reads)
    }

    private class QueryCompositionFixture(
        private val scope: TestScope,
        initialTime: Long,
        private val clock: StatsQueryClock? = null,
        private val repository: PlaybackStatsRepository? = null,
        private val queryFactory: @Composable (Long, TimeZone) -> PlaybackStatsQuery
    ) {
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private var frameTime = 0L
        var nowMillis by mutableStateOf(initialTime)
        val resumed = MutableStateFlow(true)
        val timeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        lateinit var query: PlaybackStatsQuery
        private lateinit var requestState: MutableState<StatsPageRequest>
        var request: StatsPageRequest
            get() = requestState.value
            set(value) { requestState.value = value }

        fun start() {
            composition.setContent {
                val day = if (clock == null) statsQueryDay(nowMillis, TimeZone.getTimeZone("UTC"))
                    else rememberStatsQueryDay(clock, resumed, timeChanges).value
                query = queryFactory(day.nowMillis, clock?.timeZone() ?: TimeZone.getTimeZone("UTC"))
                val dayKey = day.key.takeUnless { query.period == PlaybackStatsPeriod.ALL }
                requestState = remember(query, dayKey) { mutableStateOf(StatsPageRequest()) }
                if (repository != null) rememberStatsPage(query, requestState, repository)
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

        suspend fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
            recomposer.join()
        }
    }

    private class TestClock(private val scope: TestScope, initial: Long, zoneId: String = "UTC") : StatsQueryClock {
        private var baseTime = initial - scope.testScheduler.currentTime
        var zone: TimeZone = TimeZone.getTimeZone(zoneId)
        var reads = 0
            private set
        override fun nowMillis(): Long {
            reads++
            return baseTime + scope.testScheduler.currentTime
        }
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

    private val firstDay = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
    private val nextDay = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
}
