package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackStatsPageStateTest {
    @Test
    fun `revision refresh marks the preserved result loading while an actual read is pending`() = runTest {
        val query = PlaybackStatsQuery(nowMillis = 1_000L)
        val revision = MutableStateFlow(1L)
        val repository = mock(PlaybackStatsRepository::class.java)
        val originalPage = PlaybackStatsPage(listOf(track("old")), null)
        val replacement = PlaybackStatsPage(listOf(track("updated")), null)
        val originalSummary = PlaybackStatsSummary(trackCount = 1L, totalPlayCount = 10L, hasAnyStats = true)
        val replacementSummary = originalSummary.copy(totalPlayCount = 20L)
        val readGate = PageReadGate()
        `when`(repository.revisionFlow).thenReturn(revision)
        `when`(repository.readSummary(query)).thenReturn(originalSummary, replacementSummary)
        `when`(repository.readPage(query, null, 100, false)).thenReturn(originalPage).thenAnswer(readGate.answer(replacement))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(query, StatsPageRequest(), repository)
            fixture.pump()
            assertFalse(requireNotNull(fixture.state).loading)

            revision.value = 2L
            fixture.pump()
            assertTrue("刷新已经进入实际 readPage，而非只等待 revision", readGate.entered.isCompleted)
            val pending = requireNotNull(fixture.state)
            assertTrue("读取期间 loading 应禁用旧行和分页", pending.loading)
            assertFalse(pending.failed)
            assertSame(originalPage, pending.page)
            assertSame(originalSummary, pending.summary)
            assertEquals(query, pending.loadedQuery)
            assertEquals(StatsPageRequest(), pending.loadedRequest)

            readGate.release.complete(Unit)
            fixture.pump()
            assertSame(replacement, fixture.state?.page)
            assertSame(replacementSummary, fixture.state?.summary)
            assertFalse(requireNotNull(fixture.state).loading)
            verify(repository, times(2)).readPage(query, null, 100, false)
        } finally {
            readGate.release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `query change cancels an already suspended read and the new empty result stays published`() = runTest {
        val originalQuery = PlaybackStatsQuery(nowMillis = 1_000L)
        val nextQuery = originalQuery.copy(period = PlaybackStatsPeriod.DAY)
        val revision = MutableStateFlow(1L)
        val repository = mock(PlaybackStatsRepository::class.java)
        val originalPage = PlaybackStatsPage(listOf(track("old")), null)
        val stalePage = PlaybackStatsPage(listOf(track("stale-revision")), null)
        val readGate = PageReadGate()
        `when`(repository.revisionFlow).thenReturn(revision)
        `when`(repository.readSummary(originalQuery)).thenReturn(PlaybackStatsSummary(trackCount = 1L, hasAnyStats = true))
        `when`(repository.readPage(originalQuery, null, 100, false)).thenReturn(originalPage).thenAnswer(readGate.answer(stalePage))
        `when`(repository.readSummary(nextQuery)).thenReturn(PlaybackStatsSummary(hasAnyStats = true))
        `when`(repository.readPage(nextQuery, null, 100, false)).thenReturn(PlaybackStatsPage(emptyList(), null))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(originalQuery, StatsPageRequest(), repository)
            fixture.pump()
            revision.value = 2L
            fixture.pump()
            assertTrue(readGate.entered.isCompleted)
            assertTrue(requireNotNull(fixture.state).loading)
            assertSame(originalPage, fixture.state?.page)

            fixture.query = nextQuery
            fixture.pump()
            assertTrue("新查询应取消已挂起的旧读取", readGate.cancelled)
            val completed = requireNotNull(fixture.state)
            assertEquals(nextQuery, completed.loadedQuery)
            assertEquals(emptyList<TrackStat>(), completed.page.tracks)
            assertFalse(completed.loading)
            assertFalse(completed.failed)

            readGate.release.complete(Unit)
            fixture.pump()
            assertSame("旧读取释放后不能覆盖新查询的空结果", completed, fixture.state)
            verify(repository, times(2)).readPage(originalQuery, null, 100, false)
            verify(repository, times(1)).readPage(nextQuery, null, 100, false)
        } finally {
            readGate.release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `period refresh retains the completed result and rank until the replacement is ready`() = runTest {
        val originalQuery = PlaybackStatsQuery(nowMillis = 1_000L)
        val nextQuery = originalQuery.copy(period = PlaybackStatsPeriod.MONTH)
        val revision = MutableSharedFlow<Long>()
        val cursor = PlaybackStatsCursor(1L, "before")
        val originalRequest = StatsPageRequest(cursor, offset = 100)
        val repository = mock(PlaybackStatsRepository::class.java)
        val originalPage = PlaybackStatsPage(listOf(track("old")), null, cursor)
        `when`(repository.revisionFlow).thenReturn(revision)
        `when`(repository.readSummary(originalQuery)).thenReturn(PlaybackStatsSummary(trackCount = 101L, hasAnyStats = true))
        `when`(repository.readPage(originalQuery, cursor, 100, false)).thenReturn(originalPage)
        `when`(repository.readSummary(nextQuery)).thenReturn(PlaybackStatsSummary(trackCount = 1L, hasAnyStats = true))
        `when`(repository.readPage(nextQuery, null, 100, false)).thenReturn(PlaybackStatsPage(listOf(track("new")), null))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(originalQuery, originalRequest, repository)
            fixture.pump()
            revision.emit(1L)
            fixture.pump()
            fixture.query = nextQuery
            fixture.request = StatsPageRequest()
            fixture.pump()

            val pending = requireNotNull(fixture.state)
            assertTrue(pending.loading)
            assertSame(originalPage, pending.page)
            assertEquals(originalQuery, pending.loadedQuery)
            assertEquals(originalRequest, pending.loadedRequest)

            revision.emit(2L)
            fixture.pump()
            val completed = requireNotNull(fixture.state)
            assertFalse(completed.loading)
            assertEquals(listOf("new"), completed.page.tracks.map { it.identityKey })
            assertEquals(nextQuery, completed.loadedQuery)
            assertEquals(StatsPageRequest(), completed.loadedRequest)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `rapid period changes publish only the final query and accept an empty result`() = runTest {
        val originalQuery = PlaybackStatsQuery(nowMillis = 1_000L)
        val skippedQuery = originalQuery.copy(period = PlaybackStatsPeriod.MONTH)
        val finalQuery = originalQuery.copy(period = PlaybackStatsPeriod.DAY)
        val revision = MutableSharedFlow<Long>()
        val repository = mock(PlaybackStatsRepository::class.java)
        `when`(repository.revisionFlow).thenReturn(revision)
        `when`(repository.readSummary(originalQuery)).thenReturn(PlaybackStatsSummary(trackCount = 1L, hasAnyStats = true))
        `when`(repository.readPage(originalQuery, null, 100, false)).thenReturn(PlaybackStatsPage(listOf(track("old")), null))
        `when`(repository.readSummary(finalQuery)).thenReturn(PlaybackStatsSummary(hasAnyStats = true))
        `when`(repository.readPage(finalQuery, null, 100, false)).thenReturn(PlaybackStatsPage(emptyList(), null))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(originalQuery, StatsPageRequest(), repository)
            fixture.pump()
            revision.emit(1L)
            fixture.pump()
            fixture.query = skippedQuery
            fixture.pump()
            fixture.query = finalQuery
            fixture.pump()
            assertTrue(requireNotNull(fixture.state).loading)
            assertEquals(listOf("old"), fixture.state?.page?.tracks?.map { it.identityKey })

            revision.emit(2L)
            fixture.pump()
            assertEquals(emptyList<TrackStat>(), fixture.state?.page?.tracks)
            assertFalse(requireNotNull(fixture.state).loading)
            assertEquals(finalQuery, fixture.state?.loadedQuery)
            verify(repository, never()).readSummary(skippedQuery)
            verify(repository, never()).readPage(skippedQuery, null, 100, false)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `failed replacement retains the last result and retry replaces it`() = runTest {
        val originalQuery = PlaybackStatsQuery(nowMillis = 1_000L)
        val nextQuery = originalQuery.copy(period = PlaybackStatsPeriod.YEAR)
        val revision = MutableSharedFlow<Long>()
        val repository = mock(PlaybackStatsRepository::class.java)
        `when`(repository.revisionFlow).thenReturn(revision)
        `when`(repository.readSummary(originalQuery)).thenReturn(PlaybackStatsSummary(trackCount = 1L, hasAnyStats = true))
        `when`(repository.readPage(originalQuery, null, 100, false)).thenReturn(PlaybackStatsPage(listOf(track("old")), null))
        `when`(repository.readSummary(nextQuery)).thenThrow(IllegalStateException("fixture read failed"))
        val fixture = PageCompositionFixture(this)
        try {
            fixture.start(originalQuery, StatsPageRequest(), repository)
            fixture.pump()
            revision.emit(1L)
            fixture.pump()
            fixture.query = nextQuery
            fixture.pump()
            revision.emit(2L)
            fixture.pump()
            assertTrue(requireNotNull(fixture.state).failed)
            assertFalse(requireNotNull(fixture.state).loading)
            assertEquals(listOf("old"), fixture.state?.page?.tracks?.map { it.identityKey })
            assertEquals(originalQuery, fixture.state?.loadedQuery)

            doReturn(PlaybackStatsSummary()).`when`(repository).readSummary(nextQuery)
            `when`(repository.readPage(nextQuery, null, 100, false)).thenReturn(PlaybackStatsPage(emptyList(), null))
            fixture.request = fixture.request.copy(retry = 1)
            fixture.pump()
            assertTrue(requireNotNull(fixture.state).loading)
            assertFalse(requireNotNull(fixture.state).failed)
            revision.emit(3L)
            fixture.pump()
            assertFalse(requireNotNull(fixture.state).failed)
            assertEquals(emptyList<TrackStat>(), fixture.state?.page?.tracks)
            assertEquals(nextQuery, fixture.state?.loadedQuery)
            assertEquals(1, fixture.state?.loadedRequest?.retry)
        } finally {
            fixture.close()
        }
    }

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

    private class PageReadGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
            private set

        fun answer(page: PlaybackStatsPage): Answer<Any?> = Answer { invocation ->
            require(invocation.method.name == "readPage")
            // Mockito 隐藏 suspend 参数，readPage 的原始调用固定使用 PlaybackStatsPage continuation
            @Suppress("UNCHECKED_CAST")
            val continuation = checkNotNull(invocation.rawArguments.last() as? Continuation<PlaybackStatsPage>)
            val read: suspend () -> PlaybackStatsPage = {
                entered.complete(Unit)
                try {
                    release.await()
                    page
                } catch (error: CancellationException) {
                    cancelled = true
                    throw error
                }
            }
            read.startCoroutineUninterceptedOrReturn(continuation)
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
        private val queryState = mutableStateOf(PlaybackStatsQuery(nowMillis = 0L))
        var query: PlaybackStatsQuery
            get() = queryState.value
            set(value) { queryState.value = value }
        var request: StatsPageRequest
            get() = requestState.value
            set(value) { requestState.value = value }

        fun start(query: PlaybackStatsQuery, request: StatsPageRequest, repository: PlaybackStatsRepository) {
            queryState.value = query
            requestState.value = request
            composition.setContent {
                stateHandle = rememberStatsPage(queryState.value, requestState, repository)
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
