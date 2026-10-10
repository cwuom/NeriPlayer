package moe.ouom.neriplayer.core.player.watchdog

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.PlayerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStartupWatchdogCancellationTest {

    private val manager = PlayerManager
    private val previousJob = manager.playbackStartupWatchdogJob
    private val previousToken = manager.playbackStartupWatchdogToken

    @After
    fun tearDown() {
        manager.playbackStartupWatchdogJob = previousJob
        manager.playbackStartupWatchdogToken = previousToken
    }

    @Test
    fun `cancel stops the running startup watchdog and invalidates its token`() {
        val watchdog = Job()
        manager.playbackStartupWatchdogJob = watchdog
        manager.playbackStartupWatchdogToken = 11L

        manager.cancelPlaybackStartupWatchdog("user_pause")

        assertTrue(watchdog.isCancelled)
        assertNull(manager.playbackStartupWatchdogJob)
        assertEquals(12L, manager.playbackStartupWatchdogToken)
    }

    @Test
    fun `cancel without a running startup watchdog still invalidates the token`() {
        manager.playbackStartupWatchdogJob = null
        manager.playbackStartupWatchdogToken = 3L

        manager.cancelPlaybackStartupWatchdog("stop")

        assertNull(manager.playbackStartupWatchdogJob)
        assertEquals(4L, manager.playbackStartupWatchdogToken)

        val finished = Job().apply { complete() }
        manager.playbackStartupWatchdogJob = finished

        manager.cancelPlaybackStartupWatchdog("stop")

        assertFalse(finished.isCancelled)
        assertNull(manager.playbackStartupWatchdogJob)
        assertEquals(5L, manager.playbackStartupWatchdogToken)
    }
}
