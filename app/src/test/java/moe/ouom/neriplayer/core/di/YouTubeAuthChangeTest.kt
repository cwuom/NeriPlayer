package moe.ouom.neriplayer.core.di

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.network.weblogin.ForegroundWebLoginGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeAuthChangeTest {
    @Test
    fun `account invalidation and refresh teardown precede browser state clearing`() = runTest {
        val events = mutableListOf<String>()
        withYouTubeAuthChange(
            updateAuth = {
                assertTrue(ForegroundWebLoginGuard.isActive)
                events += "updateAuth"
            },
            stopRefresh = {
                assertTrue(ForegroundWebLoginGuard.isActive)
                events += "stopRefresh"
            },
            clearWebState = {
                assertTrue(ForegroundWebLoginGuard.isActive)
                events += "clearWebState"
            }
        )

        assertEquals(listOf("updateAuth", "stopRefresh", "clearWebState"), events)
        assertFalse(ForegroundWebLoginGuard.isActive)
    }

    @Test
    fun `cancellation after the account change still waits for teardown and browser clearing`() = runTest {
        val enteredStop = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val caller = launch {
            withYouTubeAuthChange(
                updateAuth = { events += "updateAuth" },
                stopRefresh = { enteredStop.complete(Unit); releaseStop.await(); events += "stopRefresh" },
                clearWebState = { events += "clearWebState" }
            )
        }
        enteredStop.await()

        caller.cancel()
        runCurrent()
        assertFalse(caller.isCompleted)
        releaseStop.complete(Unit)
        caller.join()

        assertEquals(listOf("updateAuth", "stopRefresh", "clearWebState"), events)
        assertFalse(ForegroundWebLoginGuard.isActive)
    }

    @Test
    fun `a cancelled queued account change never starts its mutation`() = runTest {
        val stopping = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch {
            withYouTubeAuthChange(
                updateAuth = { events += "first" },
                stopRefresh = { stopping.complete(Unit); stopped.await() },
                clearWebState = {}
            )
        }
        stopping.await()
        val queued = launch {
            withYouTubeAuthChange(
                updateAuth = { events += "queued" },
                stopRefresh = {},
                clearWebState = {}
            )
        }
        runCurrent()

        queued.cancel()
        stopped.complete(Unit)
        first.join()
        queued.join()

        assertEquals(listOf("first"), events)
        assertFalse(ForegroundWebLoginGuard.isActive)
    }

    @Test
    fun `storage rejection leaves the browser intact and releases the refresh gate`() = runTest {
        val events = mutableListOf<String>()
        try {
            withYouTubeAuthChange(
                updateAuth = { throw IOException("synthetic storage rejection") },
                stopRefresh = { events += "stopRefresh" },
                clearWebState = { events += "clearWebState" }
            )
            throw AssertionError("The storage failure must propagate")
        } catch (error: IOException) {
            assertEquals("synthetic storage rejection", error.message)
        }

        assertTrue(events.isEmpty())
        assertFalse(ForegroundWebLoginGuard.isActive)
    }
}
