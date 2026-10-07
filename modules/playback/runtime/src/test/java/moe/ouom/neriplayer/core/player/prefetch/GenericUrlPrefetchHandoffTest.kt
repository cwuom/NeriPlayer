package moe.ouom.neriplayer.core.player.prefetch

import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.runtime.prefetch.GENERIC_URL_PREFETCH_TTL_MS
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic

class GenericUrlPrefetchHandoffTest {

    private val manager = PlayerManager
    private val previousJob = manager.currentGenericUrlPrefetchJob
    private val previousKey = manager.currentGenericUrlPrefetchKey
    private lateinit var clock: MockedStatic<SystemClock>

    @Before
    fun setUp() {
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(NOW_MS)
        manager.genericUrlPrefetchCache.clear()
        manager.currentGenericUrlPrefetchJob = null
        manager.currentGenericUrlPrefetchKey = null
    }

    @After
    fun tearDown() {
        manager.genericUrlPrefetchCache.clear()
        manager.currentGenericUrlPrefetchJob = previousJob
        manager.currentGenericUrlPrefetchKey = previousKey
        clock.close()
    }

    @Test
    fun `fresh prefetched url is handed over exactly once`() = runTest {
        manager.genericUrlPrefetchCache.put(KEY, RESULT, NOW_MS)

        assertSame(RESULT, manager.consumeGenericUrlPrefetch(KEY, SONG))
        assertNull(manager.consumeGenericUrlPrefetch(KEY, SONG))
    }

    @Test
    fun `expired prefetched url is dropped instead of handed over`() = runTest {
        manager.genericUrlPrefetchCache.put(KEY, RESULT, NOW_MS - GENERIC_URL_PREFETCH_TTL_MS)

        assertNull(manager.consumeGenericUrlPrefetch(KEY, SONG))
    }

    @Test
    fun `in flight prefetch for the same key is awaited before handing over`() = runTest {
        val inFlight = launch { manager.genericUrlPrefetchCache.put(KEY, RESULT, NOW_MS) }
        manager.currentGenericUrlPrefetchJob = inFlight
        manager.currentGenericUrlPrefetchKey = KEY

        assertSame(RESULT, manager.consumeGenericUrlPrefetch(KEY, SONG))
        assertTrue(inFlight.isCompleted)
    }

    @Test
    fun `in flight prefetch that stores nothing yields no handover`() = runTest {
        val inFlight = launch { }
        manager.currentGenericUrlPrefetchJob = inFlight
        manager.currentGenericUrlPrefetchKey = KEY

        assertNull(manager.consumeGenericUrlPrefetch(KEY, SONG))
        assertTrue(inFlight.isCompleted)
    }

    @Test
    fun `prefetch for another key or a finished job is not awaited`() = runTest {
        val otherTrack = Job()
        manager.currentGenericUrlPrefetchJob = otherTrack
        manager.currentGenericUrlPrefetchKey = "other-key"

        assertNull(manager.consumeGenericUrlPrefetch(KEY, SONG))
        assertTrue(otherTrack.isActive)
        otherTrack.cancel()

        manager.currentGenericUrlPrefetchJob = Job().apply { complete() }
        manager.currentGenericUrlPrefetchKey = KEY

        assertNull(manager.consumeGenericUrlPrefetch(KEY, SONG))
    }

    @Test
    fun `cancelling an active prefetch cancels its job and forgets the key`() {
        val active = Job()
        manager.currentGenericUrlPrefetchJob = active
        manager.currentGenericUrlPrefetchKey = KEY

        manager.cancelGenericUrlPrefetch("queue_changed")

        assertTrue(active.isCancelled)
        assertNull(manager.currentGenericUrlPrefetchJob)
        assertNull(manager.currentGenericUrlPrefetchKey)
    }

    @Test
    fun `cancelling a finished or missing prefetch only clears the bookkeeping`() {
        val finished = Job().apply { complete() }
        manager.currentGenericUrlPrefetchJob = finished
        manager.currentGenericUrlPrefetchKey = KEY

        manager.cancelGenericUrlPrefetch("stop")

        assertFalse(finished.isCancelled)
        assertNull(manager.currentGenericUrlPrefetchJob)
        assertNull(manager.currentGenericUrlPrefetchKey)

        manager.currentGenericUrlPrefetchKey = "orphan-key"

        manager.cancelGenericUrlPrefetch("stop")

        assertNull(manager.currentGenericUrlPrefetchJob)
        assertNull(manager.currentGenericUrlPrefetchKey)
    }

    private companion object {
        const val NOW_MS = 500_000L
        const val KEY = "netease-42-exhigh"
        val RESULT = SongUrlResult.Success(url = "https://m801.music.126.net/42.mp3")
        val SONG = SongItem(
            id = 42L,
            name = "Song 42",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null
        )
    }
}
