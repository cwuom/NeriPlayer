package moe.ouom.neriplayer.core.player.watchdog

import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerManagerAudioOffloadStallFallbackTest {
    private val manager = PlayerManager
    private val previousInitialized = manager.initialized
    private val previousResumeRequested = manager.resumePlaybackRequested
    private val previousSong = manager._currentSongFlow.value
    private val previousMediaUrl = manager._currentMediaUrl.value
    private val previousAudioInfo = manager._currentPlaybackAudioInfo.value
    private val previousRequiresPcm = manager.lastRequiresPcmAudioProcessing
    private val previousFallback = manager.audioOffloadStallFallbackActive
    private val previousAttempts = manager.startupStallRecoveryAttempts
    private val previousFailures = manager.consecutivePlayFailures
    private val previousMainScope = manager.mainScope

    @After
    fun tearDown() {
        manager.cancelPlaybackStartupWatchdog("test_teardown")
        manager.initialized = previousInitialized
        manager.updateResumePlaybackRequested(previousResumeRequested)
        manager._currentSongFlow.value = previousSong
        manager._currentMediaUrl.value = previousMediaUrl
        manager._currentPlaybackAudioInfo.value = previousAudioInfo
        manager.lastRequiresPcmAudioProcessing = previousRequiresPcm
        manager.audioOffloadStallFallbackActive = previousFallback
        manager.startupStallRecoveryAttempts = previousAttempts
        manager.consecutivePlayFailures = previousFailures
        manager.mainScope = previousMainScope
    }

    @Test
    fun `offload fallback only triggers once for an offloaded track buffering at the start`() {
        assertTrue(fallback())
        assertTrue(fallback(positionMs = PlayerManager.STARTUP_STALL_POSITION_TOLERANCE_MS))
        assertFalse(fallback(offloadEnabled = false))
        assertFalse(fallback(alreadyTried = true))
        assertFalse(fallback(playbackState = Player.STATE_READY))
        assertFalse(fallback(playbackState = Player.STATE_IDLE))
        assertFalse(fallback(positionMs = PlayerManager.STARTUP_STALL_POSITION_TOLERANCE_MS + 1L))
    }

    @Test
    fun `startup stall on an offloaded local track switches to pcm and re-prepares in place`() = runTest {
        val fake = FakeExoPlayer().apply {
            playbackState = Player.STATE_BUFFERING
            playWhenReady = true
            positionMs = 0L
            bufferedDurationMs = 30_000L
        }
        manager.mainScope = backgroundScope
        fake.installInto(manager) {
            manager.initialized = true
            manager.updateResumePlaybackRequested(true)
            manager._currentSongFlow.value = downloadedNeteaseSong()
            manager._currentMediaUrl.value = "file:///storage/emulated/0/Music/offload.mp3"
            manager._currentPlaybackAudioInfo.value = PlaybackAudioInfo(source = PlaybackAudioSource.LOCAL)
            manager.lastRequiresPcmAudioProcessing = false
            manager.audioOffloadStallFallbackActive = false
            manager.startupStallRecoveryAttempts = 0
            val failuresBefore = manager.consecutivePlayFailures

            manager.schedulePlaybackStartupWatchdog(reason = "test")
            testScheduler.advanceTimeBy(PlayerManager.STARTUP_STALL_REMOTE_TIMEOUT_MS + 1L)
            testScheduler.runCurrent()

            assertTrue(manager.audioOffloadStallFallbackActive)
            assertEquals(true, manager.lastRequiresPcmAudioProcessing)
            assertEquals(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                fake.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode
            )
            assertEquals(
                listOf("trackSelectionParameters", "stop", "seekTo(0,0)", "prepare", "playWhenReady=true"),
                fake.commands
            )
            assertEquals(failuresBefore, manager.consecutivePlayFailures)
        }
    }

    private fun fallback(
        offloadEnabled: Boolean = true,
        playbackState: Int = Player.STATE_BUFFERING,
        positionMs: Long = 0L,
        alreadyTried: Boolean = false
    ): Boolean = shouldTryAudioOffloadStallFallback(offloadEnabled, playbackState, positionMs, alreadyTried)

    private fun downloadedNeteaseSong() = SongItem(
        id = 425L,
        name = "Offload stall",
        artist = "Artist",
        album = "Album",
        albumId = 42L,
        durationMs = 180_000L,
        coverUrl = null
    )
}
