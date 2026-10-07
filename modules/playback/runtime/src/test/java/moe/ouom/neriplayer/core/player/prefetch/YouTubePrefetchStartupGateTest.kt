package moe.ouom.neriplayer.core.player.prefetch

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubePrefetchStartupGateTest {

    private val manager = PlayerManager
    private val previousGate = YouTubeFeatureGate.isEnabled()
    private val previousJob = manager.currentYouTubePrefetchJob
    private val previousVideoIds = manager.currentYouTubePrefetchVideoIds
    private val running = Job()

    @Before
    fun setUp() {
        manager.currentYouTubePrefetchJob = running
        manager.currentYouTubePrefetchVideoIds = setOf("running")
    }

    @After
    fun tearDown() {
        running.cancel()
        YouTubeFeatureGate.update(previousGate)
        manager.currentYouTubePrefetchJob = previousJob
        manager.currentYouTubePrefetchVideoIds = previousVideoIds
    }

    @Test
    fun `disabled youtube leaves the running prefetch window untouched`() {
        YouTubeFeatureGate.update(false)

        manager.prefetchYouTubeQueueWindowImpl(PLAYLIST, startIndex = 0, source = "queue")
        manager.prefetchYouTubePlayableUrlWindowImpl(PLAYLIST, startIndex = 0, source = "queue")

        assertRunningPrefetchUntouched()
    }

    @Test
    fun `enabled youtube still waits for the application before prefetching`() {
        YouTubeFeatureGate.update(true)
        assertFalse(manager.isApplicationInitialized())

        manager.prefetchYouTubeQueueWindowImpl(PLAYLIST, startIndex = 0, source = "queue")
        manager.prefetchYouTubePlayableUrlWindowImpl(PLAYLIST, startIndex = 0, source = "queue")

        assertRunningPrefetchUntouched()
    }

    private fun assertRunningPrefetchUntouched() {
        assertSame(running, manager.currentYouTubePrefetchJob)
        assertTrue(running.isActive)
        assertEquals(setOf("running"), manager.currentYouTubePrefetchVideoIds)
    }

    private companion object {
        val PLAYLIST = listOf("first-video", "second-video").mapIndexed { index, videoId ->
            SongItem(
                id = index + 1L,
                name = "Track $index",
                artist = "Artist",
                album = "YouTube Music",
                albumId = 0L,
                durationMs = 240_000L,
                coverUrl = null,
                mediaUri = buildYouTubeMusicMediaUri(videoId)
            )
        }
    }
}
