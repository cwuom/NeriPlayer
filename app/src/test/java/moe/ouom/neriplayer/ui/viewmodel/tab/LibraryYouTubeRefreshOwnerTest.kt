package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryYouTubeRefreshOwnerTest {
    @Test
    fun `session change starts the new account without waiting for old account IO`() = runTest {
        withFixture {
            owner.refresh()
            testScheduler.runCurrent()
            owner.onSessionChanged()
            testScheduler.runCurrent()

            assertEquals(2, requests.size)
            assertTrue(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `late old account result stays hidden while the new account loads`() = runTest {
        withFixture {
            owner.refresh()
            testScheduler.runCurrent()
            owner.onSessionChanged()
            testScheduler.runCurrent()
            requests[0].complete(listOf(playlist("old-account")))
            testScheduler.runCurrent()
            val whileNewAccountLoads = state.value
            requests[1].complete(listOf(playlist("new-account")))
            testScheduler.runCurrent()

            assertTrue(whileNewAccountLoads.youtubeMusicPlaylists.isEmpty())
            assertTrue(whileNewAccountLoads.youtubeMusicRefreshing)
            assertNull(whileNewAccountLoads.youtubeMusicError)
            assertEquals(listOf("new-account"), state.value.youtubeMusicPlaylists.map { it.playlistId })
            assertFalse(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `old IO completion cannot end a replacement that is still loading`() = runTest {
        withFixture {
            owner.refresh()
            testScheduler.runCurrent()
            enabled = false
            owner.clear()
            enabled = true
            owner.refresh()
            testScheduler.runCurrent()

            requests[0].complete(listOf(playlist("old")))
            testScheduler.runCurrent()
            val whileReplacementLoads = state.value
            requests[1].complete(listOf(playlist("new")))
            testScheduler.runCurrent()

            assertTrue(whileReplacementLoads.youtubeMusicRefreshing)
            assertTrue(whileReplacementLoads.youtubeMusicPlaylists.isEmpty())
            assertEquals(listOf("new"), state.value.youtubeMusicPlaylists.map { it.playlistId })
            assertFalse(state.value.youtubeMusicRefreshing)
            assertNull(state.value.youtubeMusicError)
        }
    }

    @Test
    fun `disable clears refreshing before blocked IO can finish`() = runTest {
        withFixture {
            owner.refresh()
            testScheduler.runCurrent()
            enabled = false
            owner.clear()
            val afterDisable = state.value
            requests[0].complete(listOf(playlist("old")))
            testScheduler.runCurrent()

            assertFalse(afterDisable.youtubeMusicRefreshing)
            assertTrue(afterDisable.youtubeMusicPlaylists.isEmpty())
            assertNull(afterDisable.youtubeMusicError)
            assertTrue(state.value.youtubeMusicPlaylists.isEmpty())
            assertFalse(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `cancelled IO exception cannot clear a completed replacement or publish its error`() = runTest {
        withFixture {
            owner.refresh()
            testScheduler.runCurrent()
            enabled = false
            owner.clear()
            enabled = true
            owner.refresh()
            testScheduler.runCurrent()
            requests[1].complete(listOf(playlist("new")))
            testScheduler.runCurrent()
            requests[0].completeExceptionally(IOException("old request failed"))
            testScheduler.runCurrent()

            assertEquals(listOf("new"), state.value.youtubeMusicPlaylists.map { it.playlistId })
            assertNull(state.value.youtubeMusicError)
            assertFalse(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `cancelled unexpected exception cannot clear a completed replacement or publish its error`() = runTest {
        withFixture {
            owner.refresh()
            testScheduler.runCurrent()
            owner.clear()
            owner.refresh()
            testScheduler.runCurrent()
            requests[1].complete(listOf(playlist("new")))
            testScheduler.runCurrent()
            requests[0].completeExceptionally(IllegalStateException("old response failed"))
            testScheduler.runCurrent()

            assertEquals(listOf("new"), state.value.youtubeMusicPlaylists.map { it.playlistId })
            assertNull(state.value.youtubeMusicError)
            assertFalse(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `repeated refreshes during loading schedule just one followup`() = runTest {
        withFixture {
            owner.refresh()
            testScheduler.runCurrent()
            repeat(5) { owner.refresh() }
            testScheduler.runCurrent()
            val callsBeforeCompletion = requests.size
            requests[0].complete(listOf(playlist("first")))
            testScheduler.runCurrent()
            val whileFollowupLoads = state.value
            val callsDuringFollowup = requests.size
            requests[1].complete(listOf(playlist("second")))
            testScheduler.runCurrent()

            assertEquals(1, callsBeforeCompletion)
            assertEquals(2, callsDuringFollowup)
            assertEquals(2, requests.size)
            assertTrue(whileFollowupLoads.youtubeMusicRefreshing)
            assertEquals(listOf("first"), whileFollowupLoads.youtubeMusicPlaylists.map { it.playlistId })
            assertEquals(listOf("second"), state.value.youtubeMusicPlaylists.map { it.playlistId })
            assertFalse(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `clearing auth discards queued refreshes and a later explicit refresh still works`() = runTest {
        withFixture {
            owner.refresh()
            testScheduler.runCurrent()
            owner.refresh()
            owner.clear()
            requests[0].complete(listOf(playlist("old")))
            testScheduler.runCurrent()
            val callsAfterClear = requests.size
            val afterClear = state.value

            owner.refresh()
            testScheduler.runCurrent()
            requests[1].complete(listOf(playlist("restored")))
            testScheduler.runCurrent()

            assertEquals(1, callsAfterClear)
            assertTrue(afterClear.youtubeMusicPlaylists.isEmpty())
            assertNull(afterClear.youtubeMusicError)
            assertEquals(2, requests.size)
            assertEquals(listOf("restored"), state.value.youtubeMusicPlaylists.map { it.playlistId })
            assertFalse(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `current failure clears cached playlists and retry clears the error`() = runTest {
        withFixture(LibraryUiState(youtubeMusicPlaylists = listOf(playlist("cached")))) {
            owner.refresh()
            testScheduler.runCurrent()
            requests[0].completeExceptionally(IOException("library unavailable"))
            testScheduler.runCurrent()

            assertTrue(state.value.youtubeMusicPlaylists.isEmpty())
            assertEquals("library unavailable", state.value.youtubeMusicError)
            assertFalse(state.value.youtubeMusicRefreshing)

            owner.refresh()
            testScheduler.runCurrent()
            requests[1].complete(listOf(playlist("recovered")))
            testScheduler.runCurrent()

            assertEquals(listOf("recovered"), state.value.youtubeMusicPlaylists.map { it.playlistId })
            assertNull(state.value.youtubeMusicError)
            assertFalse(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `disabled YouTube cannot start a manual refresh`() = runTest {
        withFixture {
            enabled = false
            owner.refresh()
            testScheduler.runCurrent()

            assertTrue(requests.isEmpty())
            assertTrue(state.value.youtubeMusicPlaylists.isEmpty())
            assertNull(state.value.youtubeMusicError)
            assertFalse(state.value.youtubeMusicRefreshing)
        }
    }

    @Test
    fun `a cancelled scope cannot start loading or change the refresh indicator`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val stoppedJob = Job().apply { cancel() }
        val scope = CoroutineScope(backgroundScope.coroutineContext + stoppedJob)
        var loads = 0
        val owner = LibraryYouTubeRefreshOwner(scope, state, { true }) {
            loads++
            listOf(playlist("unexpected"))
        }

        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(0, loads)
        assertFalse(state.value.youtubeMusicRefreshing)
        assertTrue(state.value.youtubeMusicPlaylists.isEmpty())
        assertNull(state.value.youtubeMusicError)
    }

    @Test
    fun `immediate completion releases the owner and permits another refresh`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val state = MutableStateFlow(LibraryUiState())
        var loads = 0
        val owner = LibraryYouTubeRefreshOwner(scope, state, { true }) {
            loads++
            listOf(playlist("result-$loads"))
        }

        owner.refresh()
        assertEquals(listOf("result-1"), state.value.youtubeMusicPlaylists.map { it.playlistId })
        assertFalse(state.value.youtubeMusicRefreshing)
        owner.refresh()

        assertEquals(2, loads)
        assertEquals(listOf("result-2"), state.value.youtubeMusicPlaylists.map { it.playlistId })
        assertFalse(state.value.youtubeMusicRefreshing)
        assertNull(state.value.youtubeMusicError)
    }

    @Test
    fun `a refresh during immediate dispatch waits for the active load before its followup`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val state = MutableStateFlow(LibraryUiState())
        val events = mutableListOf<String>()
        var loads = 0
        lateinit var owner: LibraryYouTubeRefreshOwner
        owner = LibraryYouTubeRefreshOwner(scope, state, { true }) {
            val request = ++loads
            events += "start-$request"
            if (request == 1) {
                owner.refresh()
                yield()
            }
            events += "finish-$request"
            listOf(playlist("result-$request"))
        }

        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(listOf("start-1", "finish-1", "start-2", "finish-2"), events)
        assertEquals(listOf("result-2"), state.value.youtubeMusicPlaylists.map { it.playlistId })
        assertFalse(state.value.youtubeMusicRefreshing)
        assertNull(state.value.youtubeMusicError)
    }

    @Test
    fun `a loader that returns after cancellation cannot repopulate cleared auth`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val result = CompletableDeferred<List<YouTubeMusicPlaylist>>()
        val owner = LibraryYouTubeRefreshOwner(backgroundScope, state, { true }) {
            withContext(NonCancellable) { result.await() }
        }

        owner.refresh()
        testScheduler.runCurrent()
        owner.clear()
        result.complete(listOf(playlist("old")))
        testScheduler.runCurrent()

        assertTrue(state.value.youtubeMusicPlaylists.isEmpty())
        assertFalse(state.value.youtubeMusicRefreshing)
        assertNull(state.value.youtubeMusicError)
    }

    private fun TestScope.withFixture(
        initialState: LibraryUiState = LibraryUiState(),
        block: Fixture.() -> Unit
    ) {
        val fixture = Fixture(backgroundScope, testScheduler, initialState)
        try {
            fixture.block()
        } finally {
            fixture.owner.clear()
            fixture.requests.forEach { it.complete(emptyList()) }
            testScheduler.runCurrent()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        scheduler: TestCoroutineScheduler,
        initialState: LibraryUiState
    ) {
        var enabled = true
        val state = MutableStateFlow(initialState)
        val requests = mutableListOf<CompletableDeferred<List<YouTubeMusicPlaylist>>>()
        private val ioDispatcher = StandardTestDispatcher(scheduler, "youtube-library-io")
        val owner = LibraryYouTubeRefreshOwner(scope, state, { enabled }) {
            withContext(ioDispatcher) {
                val result = CompletableDeferred<List<YouTubeMusicPlaylist>>()
                requests += result
                // 模拟阻塞网络调用, 保留切回调用方 dispatcher 时的真实取消语义
                withContext(NonCancellable) { result.await() }
            }
        }
    }

    private fun playlist(id: String) = YouTubeMusicPlaylist("VL$id", id, "Playlist $id", "", "")
}
