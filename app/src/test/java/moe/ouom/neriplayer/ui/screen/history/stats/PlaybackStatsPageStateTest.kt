package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackStatsPageStateTest {
    @Test
    fun `playing the only track on the last page recovers the first page after revision refresh`() = runTest {
        val query = PlaybackStatsQuery(nowMillis = 1_000L)
        val cursor = PlaybackStatsCursor(1L, "k099")
        val revision = MutableStateFlow(1L)
        val repository = mock(PlaybackStatsRepository::class.java)
        val first = track("k000")
        val last = track("k100")
        `when`(repository.revisionFlow).thenReturn(revision)
        `when`(repository.readSummary(query)).thenReturn(PlaybackStatsSummary(trackCount = 101L, hasAnyStats = true))
        `when`(repository.readPage(query, null, 100, false)).thenReturn(PlaybackStatsPage(listOf(first), cursor))
        `when`(repository.readPage(query, cursor, 100, false)).thenReturn(PlaybackStatsPage(listOf(last), null, cursor))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(query, StatsPageRequest(cursor = cursor, offset = 100), repository)
            fixture.pump()
            assertEquals(listOf("k100"), fixture.state?.page?.tracks?.map { it.identityKey })

            `when`(repository.readPage(query, cursor, 100, false)).thenReturn(PlaybackStatsPage(emptyList(), null))
            revision.value = 2L
            fixture.pump()

            verify(repository, times(2)).readPage(query, cursor, 100, false)
            assertEquals(listOf("k000"), fixture.state?.page?.tracks?.map { it.identityKey })
            assertEquals(StatsPageRequest(), fixture.request)
            assertFalse(requireNotNull(fixture.state).failed)
            verify(repository, times(1)).readPage(query, null, 100, false)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `changing page enters loading before reading the new cursor`() = runTest {
        val query = PlaybackStatsQuery(nowMillis = 1_000L)
        val cursor = PlaybackStatsCursor(1L, "k099")
        val repository = mock(PlaybackStatsRepository::class.java)
        val fixture = PageCompositionFixture(this)
        var loadingDuringRead = false
        `when`(repository.revisionFlow).thenReturn(MutableStateFlow(1L))
        `when`(repository.readSummary(query)).thenReturn(PlaybackStatsSummary(trackCount = 101L, hasAnyStats = true))
        `when`(repository.readPage(query, null, 100, false)).thenReturn(PlaybackStatsPage(listOf(track("k000")), cursor))
        `when`(repository.readPage(query, cursor, 100, false)).thenAnswer {
            loadingDuringRead = fixture.state?.loading == true
            PlaybackStatsPage(listOf(track("k100")), null, cursor)
        }
        try {
            fixture.start(query, StatsPageRequest(), repository)
            fixture.pump()
            assertFalse(requireNotNull(fixture.state).loading)

            fixture.request = StatsPageRequest(cursor, offset = 100)
            fixture.pump()

            assertTrue(loadingDuringRead)
            assertEquals(100, fixture.request.offset)
            assertEquals(listOf("k100"), fixture.state?.page?.tracks?.map { it.identityKey })
            assertFalse(requireNotNull(fixture.state).loading)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `retry resubscribes when revision initialization previously failed`() = runTest {
        val query = PlaybackStatsQuery(nowMillis = 1_000L)
        val repository = mock(PlaybackStatsRepository::class.java)
        var subscriptions = 0
        `when`(repository.revisionFlow).thenReturn(flow {
            subscriptions++
            if (subscriptions == 1) throw IOException("initialization failed")
            emit(1L)
            awaitCancellation()
        })
        `when`(repository.readSummary(query)).thenReturn(PlaybackStatsSummary(trackCount = 1L, hasAnyStats = true))
        `when`(repository.readPage(query, null, 100, false)).thenReturn(PlaybackStatsPage(listOf(track("k000")), null))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(query, StatsPageRequest(), repository)
            fixture.pump()
            assertTrue(requireNotNull(fixture.state).failed)
            assertEquals(1, subscriptions)

            fixture.request = fixture.request.copy(retry = 1)
            fixture.pump()

            assertEquals(2, subscriptions)
            assertEquals(listOf("k000"), fixture.state?.page?.tracks?.map { it.identityKey })
            assertFalse(requireNotNull(fixture.state).failed)
            verify(repository, times(1)).readPage(query, null, 100, false)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an empty preceding page resets its cursor even when its offset is zero`() = runTest {
        val query = PlaybackStatsQuery(nowMillis = 1_000L)
        val cursor = PlaybackStatsCursor(1L, "k099")
        val repository = mock(PlaybackStatsRepository::class.java)
        `when`(repository.revisionFlow).thenReturn(MutableStateFlow(1L))
        `when`(repository.readSummary(query)).thenReturn(PlaybackStatsSummary(trackCount = 1L, hasAnyStats = true))
        `when`(repository.readPage(query, cursor, 100, true)).thenReturn(PlaybackStatsPage(emptyList(), null))
        `when`(repository.readPage(query, null, 100, false)).thenReturn(PlaybackStatsPage(listOf(track("k000")), null))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(query, StatsPageRequest(cursor, before = true), repository)
            fixture.pump()

            assertEquals(listOf("k000"), fixture.state?.page?.tracks?.map { it.identityKey })
            assertEquals(StatsPageRequest(), fixture.request)
            verify(repository, times(1)).readPage(query, null, 100, false)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an empty first page after recovery is published without a read loop`() = runTest {
        val query = PlaybackStatsQuery(nowMillis = 1_000L)
        val cursor = PlaybackStatsCursor(1L, "k099")
        val revision = MutableStateFlow(1L)
        val repository = mock(PlaybackStatsRepository::class.java)
        `when`(repository.revisionFlow).thenReturn(revision)
        `when`(repository.readSummary(query)).thenReturn(PlaybackStatsSummary())
        `when`(repository.readPage(query, cursor, 100, false)).thenReturn(PlaybackStatsPage(emptyList(), null))
        `when`(repository.readPage(query, null, 100, false)).thenReturn(PlaybackStatsPage(emptyList(), null))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(query, StatsPageRequest(cursor, offset = 100), repository)
            fixture.pump()

            assertEquals(StatsPageRequest(), fixture.request)
            assertEquals(emptyList<TrackStat>(), fixture.state?.page?.tracks)
            assertFalse(requireNotNull(fixture.state).loading)
            verify(repository, times(1)).readPage(query, null, 100, false)
            fixture.pump()
            verify(repository, times(1)).readPage(query, null, 100, false)

            revision.value = 2L
            fixture.pump()
            verify(repository, times(2)).readPage(query, null, 100, false)
            verify(repository, times(1)).readPage(query, cursor, 100, false)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a failed home read after cursor recovery can retry with the reset request`() = runTest {
        val query = PlaybackStatsQuery(nowMillis = 1_000L)
        val cursor = PlaybackStatsCursor(1L, "k099")
        val repository = mock(PlaybackStatsRepository::class.java)
        `when`(repository.revisionFlow).thenReturn(MutableStateFlow(1L))
        `when`(repository.readSummary(query)).thenReturn(PlaybackStatsSummary(trackCount = 1L, hasAnyStats = true))
        `when`(repository.readPage(query, cursor, 100, false)).thenReturn(PlaybackStatsPage(emptyList(), null))
        `when`(repository.readPage(query, null, 100, false)).thenThrow(IllegalStateException("read failed"))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(query, StatsPageRequest(cursor, offset = 100), repository)
            fixture.pump()
            assertEquals(StatsPageRequest(), fixture.request)
            assertEquals(true, fixture.state?.failed)

            doReturn(PlaybackStatsPage(listOf(track("k000")), null)).`when`(repository).readPage(query, null, 100, false)
            fixture.request = fixture.request.copy(retry = 1)
            fixture.pump()

            assertEquals(listOf("k000"), fixture.state?.page?.tracks?.map { it.identityKey })
            assertFalse(requireNotNull(fixture.state).failed)
            verify(repository, times(2)).readPage(query, null, 100, false)
            verify(repository, times(1)).readPage(query, cursor, 100, false)
        } finally {
            fixture.close()
        }
    }

    private class PageCompositionFixture(private val scope: TestScope) {
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private var frameTime = 0L
        private var stateHandle: State<StatsPageState>? = null
        val state: StatsPageState? get() = stateHandle?.value
        private val requestState = mutableStateOf(StatsPageRequest())
        var request: StatsPageRequest
            get() = requestState.value
            set(value) { requestState.value = value }

        fun start(query: PlaybackStatsQuery, request: StatsPageRequest, repository: PlaybackStatsRepository) {
            requestState.value = request
            composition.setContent {
                stateHandle = rememberStatsPage(query, requestState, repository)
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

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private fun track(key: String) = TrackStat(
        id = 1L, name = key, artist = "artist", album = "album", coverUrl = null,
        durationMs = 180_000L, totalListenMs = 600_000L, playCount = 1,
        lastPlayedAt = 1_000L, firstPlayedAt = 1_000L, mediaUri = null,
        localFilePath = null, localFileName = null, customName = null,
        customArtist = null, customCoverUrl = null, identityKey = key
    )
}
