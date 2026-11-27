package moe.ouom.neriplayer.core.player.watchdog

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackBufferingWatchdogTest {
    private val manager = PlayerManager

    @Test
    fun `buffer fluctuations do not count as progress and a seek starts a new baseline`() {
        val refill = PlaybackBufferingProgress()
        fun observe(bufferedMs: Long, generation: Long = 1L) =
            refill.observe(Player.STATE_BUFFERING, bufferedMs, generation)

        assertFalse(observe(1_200L))
        assertFalse(observe(600L))
        assertFalse(observe(1_200L))
        assertTrue(observe(1_300L))
        assertFalse(observe(200L, generation = 2L))
        assertTrue(observe(300L, generation = 2L))
        assertFalse(refill.observe(Player.STATE_READY, 2_000L, generation = 2L))
        assertFalse(observe(50L, generation = 2L))
        assertTrue(observe(100L, generation = 2L))
    }

    @Test
    fun `mid-track rebuffer does not arm a second startup watchdog`() = runTest {
        withBufferingPlayer { fake ->
            fake.positionMs = 135_338L
            manager.playbackProgressAdvanceReported = true
            manager.schedulePlaybackStartupWatchdog("state_buffering")
            assertFalse(manager.playbackStartupWatchdogJob?.isActive == true)

            manager.resetPlaybackProgressAdvanceBaseline(fake.positionMs)
            manager.schedulePlaybackStartupWatchdog("refresh_applied")
            assertTrue(manager.playbackStartupWatchdogJob?.isActive == true)
        }
    }

    @Test
    fun `slow startup keeps its source until the buffer stops growing`() = runTest {
        withBufferingPlayer { fake ->
            manager.schedulePlaybackStartupWatchdog("media_resolved")
            testScheduler.runCurrent()
            repeat(5) {
                testScheduler.advanceTimeBy(3_000L)
                fake.bufferedDurationMs += 450L
                testScheduler.runCurrent()
            }
            assertEquals(0, manager.startupStallRecoveryAttempts)
            testScheduler.advanceTimeBy(PlayerManager.STARTUP_STALL_REMOTE_TIMEOUT_MS + 1L)
            assertTrue(manager.startupStallRecoveryAttempts > 0)
        }
    }

    @Test
    fun `refill after the early check gets a full hard timeout once it freezes`() = runTest {
        withBufferingPlayer { fake ->
            fake.bufferedDurationMs = 2_100L
            manager.schedulePlaybackStartupWatchdog("state_buffering")
            testScheduler.advanceTimeBy(6_000L)
            fake.bufferedDurationMs = 2_500L
            testScheduler.advanceTimeBy(10_499L)
            assertEquals(0, manager.startupStallRecoveryAttempts)
            testScheduler.advanceTimeBy(2L)
            assertTrue(manager.startupStallRecoveryAttempts > 0)
        }
    }

    @Test
    fun `runtime recovery waits for refill progress to stop`() = runTest {
        withBufferingPlayer { fake ->
            fake.positionMs = 135_338L
            manager.playbackProgressAdvanceReported = true
            manager.schedulePlaybackRuntimeWatchdog("state_buffering")
            testScheduler.runCurrent()
            repeat(6) {
                testScheduler.advanceTimeBy(3_000L)
                fake.bufferedDurationMs += 450L
                testScheduler.runCurrent()
            }
            assertEquals(0, manager.playbackRuntimeStallRecoveryAttempts)
            testScheduler.advanceTimeBy(13_500L)
            testScheduler.runCurrent()
            assertEquals(1, manager.playbackRuntimeStallRecoveryAttempts)
        }
    }

    private fun TestScope.withBufferingPlayer(block: (FakeExoPlayer) -> Unit) {
        val previousScope = manager.mainScope
        val previousInitialized = manager.initialized
        val previousResume = manager.resumePlaybackRequested
        val previousSong = manager._currentSongFlow.value
        val previousReported = manager.playbackProgressAdvanceReported
        val previousBaseline = manager.playbackProgressBaselinePositionMs
        val previousStartupJob = manager.playbackStartupWatchdogJob
        val previousStartupToken = manager.playbackStartupWatchdogToken
        val previousStartupAttempts = manager.startupStallRecoveryAttempts
        val previousRuntimeJob = manager.playbackRuntimeWatchdogJob
        val previousRuntimeToken = manager.playbackRuntimeWatchdogToken
        val previousRuntimeAttempts = manager.playbackRuntimeStallRecoveryAttempts
        val previousRuntimePosition = manager.playbackRuntimeLastProgressPositionMs
        val previousRuntimeProgressAt = manager.playbackRuntimeLastProgressAtElapsedRealtimeMs
        val previousPendingLoad = manager.pendingMediaLoadActive
        val previousOffload = manager.lastRequiresPcmAudioProcessing
        val previousFallback = manager.audioOffloadStallFallbackActive
        val fake = FakeExoPlayer().apply {
            playbackState = Player.STATE_BUFFERING
            playWhenReady = true
        }
        fake.installInto(manager) {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { testScheduler.currentTime + 1L }
                try {
                    manager.playbackStartupWatchdogJob = null
                    manager.playbackRuntimeWatchdogJob = null
                    manager.pendingMediaLoadActive = false
                    manager.mainScope = backgroundScope
                    manager.initialized = true
                    manager.updateResumePlaybackRequested(true)
                    manager._currentSongFlow.value = SongItem(
                        id = 425L, name = "FLAC", artist = "Artist", album = "Album",
                        albumId = 42L, durationMs = 180_000L, coverUrl = null
                    )
                    manager.resetPlaybackProgressAdvanceBaseline(0L)
                    manager.startupStallRecoveryAttempts = 0
                    manager.lastRequiresPcmAudioProcessing = false
                    manager.audioOffloadStallFallbackActive = false
                    manager.resetPlaybackRuntimeWatchdog("test_setup")
                    block(fake)
                } finally {
                    manager.cancelPlaybackStartupWatchdog("test_teardown")
                    manager.resetPlaybackRuntimeWatchdog("test_teardown")
                    manager.mainScope = previousScope
                    manager.initialized = previousInitialized
                    manager.updateResumePlaybackRequested(previousResume)
                    manager._currentSongFlow.value = previousSong
                    manager.playbackProgressAdvanceReported = previousReported
                    manager.playbackProgressBaselinePositionMs = previousBaseline
                    manager.playbackStartupWatchdogJob = previousStartupJob
                    manager.playbackStartupWatchdogToken = previousStartupToken
                    manager.startupStallRecoveryAttempts = previousStartupAttempts
                    manager.playbackRuntimeWatchdogJob = previousRuntimeJob
                    manager.playbackRuntimeWatchdogToken = previousRuntimeToken
                    manager.playbackRuntimeStallRecoveryAttempts = previousRuntimeAttempts
                    manager.playbackRuntimeLastProgressPositionMs = previousRuntimePosition
                    manager.playbackRuntimeLastProgressAtElapsedRealtimeMs = previousRuntimeProgressAt
                    manager.pendingMediaLoadActive = previousPendingLoad
                    manager.lastRequiresPcmAudioProcessing = previousOffload
                    manager.audioOffloadStallFallbackActive = previousFallback
                }
            }
        }
    }
}
