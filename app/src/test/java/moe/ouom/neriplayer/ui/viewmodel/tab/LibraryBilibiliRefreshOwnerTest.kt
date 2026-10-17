package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBilibiliRefreshOwnerTest {
    @Test
    fun `manual refreshes for the same cookies share one load and can refresh after completion`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val result = CompletableDeferred<LibraryBilibiliLoadResult>()
        var loads = 0
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { cookies("a") }) {
            loads++
            result.await()
        }

        owner.refresh()
        owner.refresh()
        testScheduler.runCurrent()
        owner.refresh()
        testScheduler.runCurrent()

        assertTrue(state.value.biliRefreshing)
        assertEquals(1, loads)

        result.complete(loaded(11L))
        testScheduler.runCurrent()
        assertFalse(state.value.biliRefreshing)
        assertEquals(listOf(11L), state.value.biliPlaylists.map { it.mediaId })

        owner.refresh()
        testScheduler.runCurrent()
        assertEquals(2, loads)
        assertFalse(state.value.biliRefreshing)
    }

    @Test
    fun `old session completion cannot end a replacement that is still loading`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        var currentCookies = cookies("old")
        val oldResult = CompletableDeferred<LibraryBilibiliLoadResult>()
        val newResult = CompletableDeferred<LibraryBilibiliLoadResult>()
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { currentCookies }) { requested ->
            if (requested["SESSDATA"] == "old") {
                withContext(NonCancellable) { oldResult.await() }
            } else {
                newResult.await()
            }
        }

        owner.refresh()
        testScheduler.runCurrent()
        currentCookies = cookies("new")
        owner.refresh()
        testScheduler.runCurrent()
        oldResult.complete(loaded(1L))
        testScheduler.runCurrent()
        val whileReplacementLoads = state.value

        newResult.complete(loaded(2L))
        testScheduler.runCurrent()

        assertTrue(whileReplacementLoads.biliRefreshing)
        assertTrue(whileReplacementLoads.biliPlaylists.isEmpty())
        assertEquals(listOf(2L), state.value.biliPlaylists.map { it.mediaId })
        assertFalse(state.value.biliRefreshing)
    }

    @Test
    fun `old session result cannot overwrite a completed replacement`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        var currentCookies = cookies("old")
        val oldResult = CompletableDeferred<LibraryBilibiliLoadResult>()
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { currentCookies }) { requested ->
            if (requested["SESSDATA"] == "old") {
                withContext(NonCancellable) { oldResult.await() }
            } else {
                loaded(2L)
            }
        }

        owner.refresh()
        testScheduler.runCurrent()
        currentCookies = cookies("new")
        owner.refresh()
        testScheduler.runCurrent()
        val replacement = state.value.biliPlaylists
        oldResult.complete(loaded(1L))
        testScheduler.runCurrent()

        assertEquals(listOf(2L), replacement.map { it.mediaId })
        assertEquals(replacement, state.value.biliPlaylists)
        assertNull(state.value.biliError)
    }

    @Test
    fun `old session failure cannot replace the current session success`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        var currentCookies = cookies("old")
        val oldFinished = CompletableDeferred<Unit>()
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { currentCookies }) { requested ->
            if (requested["SESSDATA"] == "old") {
                withContext(NonCancellable) { oldFinished.await() }
                throw IOException("old session failed")
            }
            loaded(2L)
        }

        owner.refresh()
        testScheduler.runCurrent()
        currentCookies = cookies("new")
        owner.refresh()
        testScheduler.runCurrent()
        oldFinished.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf(2L), state.value.biliPlaylists.map { it.mediaId })
        assertNull(state.value.biliError)
        assertFalse(state.value.biliRefreshing)
    }

    @Test
    fun `logout clears refreshing and rejects a late session result`() = runTest {
        val state = MutableStateFlow(LibraryUiState(biliPlaylists = listOf(playlist(9L))))
        var currentCookies = cookies("old")
        val oldResult = CompletableDeferred<LibraryBilibiliLoadResult>()
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { currentCookies }) {
            withContext(NonCancellable) { oldResult.await() }
        }

        owner.refresh()
        testScheduler.runCurrent()
        currentCookies = emptyMap()
        owner.clear()
        val afterLogout = state.value
        oldResult.complete(loaded(1L))
        testScheduler.runCurrent()

        assertTrue(afterLogout.biliPlaylists.isEmpty())
        assertTrue(state.value.biliPlaylists.isEmpty())
        assertFalse(afterLogout.biliRefreshing)
        assertFalse(state.value.biliRefreshing)
        assertNull(state.value.biliError)
    }

    @Test
    fun `persisting a missing mid during loading keeps its cookie emission refresh`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val cookieFlow = MutableStateFlow(mapOf("SESSDATA" to "same-session"))
        val folders = CompletableDeferred<LibraryBilibiliLoadResult>()
        val loadedMids = mutableListOf<Long>()
        var navCalls = 0
        var persisted: LibraryBiliMid? = null
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { cookieFlow.value }) { requested ->
            val account = resolveLibraryBiliMid(
                cookies = requested,
                fetchLoginMid = { navCalls++; 42L },
                saveUserMid = { mid, requestedWith ->
                    assertEquals(cookieFlow.value, requestedWith)
                    cookieFlow.value = requestedWith + ("DedeUserID" to mid.toString())
                    true
                }
            )
            when (account) {
                is LibraryBiliMid.Ready -> {
                    loadedMids += account.mid
                    folders.await()
                }
                LibraryBiliMid.Persisted -> {
                    persisted = account
                    LibraryBilibiliLoadResult.Unchanged
                }
                else -> throw AssertionError("unexpected mid result: $account")
            }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cookieFlow.collect { owner.refresh() }
        }

        testScheduler.runCurrent()
        val whileFoldersLoad = state.value.biliRefreshing
        folders.complete(loaded(42L))
        testScheduler.runCurrent()

        assertEquals(LibraryBiliMid.Persisted, persisted)
        assertEquals(1, navCalls)
        assertEquals(listOf(42L), loadedMids)
        assertTrue(whileFoldersLoad)
        assertFalse(state.value.biliRefreshing)
        assertEquals(listOf(42L), state.value.biliPlaylists.map { it.mediaId })
    }

    @Test
    fun `current failure keeps cached folders and a retry clears the error`() = runTest {
        val state = MutableStateFlow(LibraryUiState(biliPlaylists = listOf(playlist(9L))))
        var fails = true
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { cookies("a") }) {
            if (fails) throw IOException("folders unavailable")
            loaded(11L)
        }

        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(listOf(9L), state.value.biliPlaylists.map { it.mediaId })
        assertEquals("folders unavailable", state.value.biliError)
        assertFalse(state.value.biliRefreshing)

        fails = false
        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(listOf(11L), state.value.biliPlaylists.map { it.mediaId })
        assertNull(state.value.biliError)
        assertFalse(state.value.biliRefreshing)
    }

    @Test
    fun `unchanged lookup preserves cached folders and missing account reports its error`() = runTest {
        val cached = listOf(playlist(9L))
        val state = MutableStateFlow(LibraryUiState(biliPlaylists = cached, biliError = "previous"))
        var result: LibraryBilibiliLoadResult = LibraryBilibiliLoadResult.Unchanged
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { cookies("a") }) { result }

        owner.refresh()
        testScheduler.runCurrent()
        assertEquals(cached, state.value.biliPlaylists)
        assertEquals("previous", state.value.biliError)
        assertFalse(state.value.biliRefreshing)

        result = LibraryBilibiliLoadResult.Failed("missing account")
        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(cached, state.value.biliPlaylists)
        assertEquals("missing account", state.value.biliError)
        assertFalse(state.value.biliRefreshing)
    }

    @Test
    fun `logout cancels a cooperative request without publishing a cancellation error`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val waiting = CompletableDeferred<LibraryBilibiliLoadResult>()
        var cancelled = false
        var retry = false
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { cookies("a") }) {
            if (retry) {
                loaded(2L)
            } else {
                try {
                    waiting.await()
                } finally {
                    cancelled = true
                }
            }
        }

        owner.refresh()
        testScheduler.runCurrent()
        owner.clear()
        testScheduler.runCurrent()

        assertTrue(cancelled)
        assertFalse(state.value.biliRefreshing)
        assertNull(state.value.biliError)

        retry = true
        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(listOf(2L), state.value.biliPlaylists.map { it.mediaId })
        assertFalse(state.value.biliRefreshing)
        assertNull(state.value.biliError)
    }

    @Test
    fun `logout rejects an error from a request that finishes cancellation late`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val oldFinished = CompletableDeferred<Unit>()
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { cookies("old") }) {
            withContext(NonCancellable) { oldFinished.await() }
            throw IllegalStateException("logged out session failed")
        }

        owner.refresh()
        testScheduler.runCurrent()
        owner.clear()
        oldFinished.complete(Unit)
        testScheduler.runCurrent()

        assertTrue(state.value.biliPlaylists.isEmpty())
        assertFalse(state.value.biliRefreshing)
        assertNull(state.value.biliError)
    }

    @Test
    fun `current unexpected failure is reported and retry can publish its result`() = runTest {
        val cached = listOf(playlist(9L))
        val state = MutableStateFlow(LibraryUiState(biliPlaylists = cached))
        var fails = true
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { cookies("a") }) {
            if (fails) throw IllegalStateException("unexpected response")
            loaded(2L)
        }

        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(cached, state.value.biliPlaylists)
        assertEquals("unexpected response", state.value.biliError)
        assertFalse(state.value.biliRefreshing)

        fails = false
        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(listOf(2L), state.value.biliPlaylists.map { it.mediaId })
        assertNull(state.value.biliError)
        assertFalse(state.value.biliRefreshing)
    }

    @Test
    fun `session replacement before dispatch loads only the latest cookie snapshot`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        var currentCookies = cookies("old")
        val requestedSessions = mutableListOf<String?>()
        val owner = LibraryBilibiliRefreshOwner(backgroundScope, state, { currentCookies }) { requested ->
            requestedSessions += requested["SESSDATA"]
            loaded(2L)
        }

        owner.refresh()
        currentCookies = cookies("new")
        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(listOf("new"), requestedSessions)
        assertEquals(listOf(2L), state.value.biliPlaylists.map { it.mediaId })
        assertFalse(state.value.biliRefreshing)
    }

    @Test
    fun `an inactive owner scope cannot start loading or change the refresh indicator`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val stoppedJob = Job().apply { cancel() }
        val stoppedScope = CoroutineScope(backgroundScope.coroutineContext + stoppedJob)
        var loads = 0
        val owner = LibraryBilibiliRefreshOwner(stoppedScope, state, { cookies("a") }) {
            loads++
            loaded(2L)
        }

        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(0, loads)
        assertTrue(state.value.biliPlaylists.isEmpty())
        assertFalse(state.value.biliRefreshing)
        assertNull(state.value.biliError)
    }

    private fun cookies(session: String) = mapOf("SESSDATA" to session, "DedeUserID" to "7")

    private fun loaded(id: Long) = LibraryBilibiliLoadResult.Loaded(listOf(playlist(id)))

    private fun playlist(id: Long) = BiliPlaylist(id, id, 7L, "Folder $id", 1, "")
}
