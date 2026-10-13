package moe.ouom.neriplayer.core.player.url

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.wake.PlaybackTransitionWakeLock
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerManagerUrlRefreshCooldownTest {
    private val manager = PlayerManager

    @Test
    fun `cooldown fallback ignores a replaced playback request for the same song`() = runTest {
        withPlaybackState {
            requestCooldownFallback()
            manager.playbackRequestToken++

            runCurrent()

            assertTrue(commands.isEmpty())
            assertEquals(40_000L, positionMs)
        }
    }

    @Test
    fun `cooldown fallback ignores a different current song`() = runTest {
        withPlaybackState {
            requestCooldownFallback()
            manager._currentSongFlow.value = song(519L)

            runCurrent()

            assertTrue(commands.isEmpty())
        }
    }

    @Test
    fun `cooldown fallback does not overwrite a newer seek on the same song`() = runTest {
        withPlaybackState {
            requestCooldownFallback()
            manager.playbackPositionGeneration++
            positionMs = 90_000L
            manager._playbackPositionMs.value = 90_000L

            runCurrent()

            assertTrue(commands.isEmpty())
            assertEquals(90_000L, manager._playbackPositionMs.value)
        }
    }

    @Test
    fun `cooldown fallback does not resume after the user pauses`() = runTest {
        withPlaybackState {
            manager.updateResumePlaybackRequested(true)
            requestCooldownFallback(resume = true)
            manager.updateResumePlaybackRequested(false)

            runCurrent()

            assertFalse(playWhenReady)
            assertTrue(commands.isEmpty())
        }
    }

    @Test
    fun `cooldown paused fallback does not pause a newer play request`() = runTest {
        withPlaybackState {
            requestCooldownFallback()
            manager.updateResumePlaybackRequested(true)
            playWhenReady = true

            runCurrent()

            assertTrue(playWhenReady)
            assertTrue(commands.isEmpty())
        }
    }

    @Test
    fun `current cooldown fallback keeps the requested seek and paused state`() = runTest {
        withPlaybackState {
            requestCooldownFallback()

            runCurrent()

            assertEquals(listOf("seekTo(12000)", "playWhenReady=false", "pause"), commands)
            assertEquals(12_000L, manager._playbackPositionMs.value)
            assertFalse(playWhenReady)
        }
    }

    @Test
    fun `cooldown failure cannot stop a newer playback request`() = runTest {
        withPlaybackState {
            manager.consecutivePlayFailures = manager.MAX_CONSECUTIVE_FAILURES - 1
            requestCooldownFailure()
            manager.playbackRequestToken++

            runCurrent()

            assertTrue(commands.isEmpty())
            assertEquals(manager.MAX_CONSECUTIVE_FAILURES - 1, manager.consecutivePlayFailures)
        }
    }

    @Test
    fun `cooldown failure cannot advance after a newer seek`() = runTest {
        withPlaybackState {
            requestCooldownFailure()
            manager.playbackPositionGeneration++
            positionMs = 90_000L
            manager._playbackPositionMs.value = 90_000L

            runCurrent()

            assertTrue(commands.isEmpty())
            assertEquals(0, manager.consecutivePlayFailures)
            assertEquals(90_000L, manager._playbackPositionMs.value)
        }
    }

    @Test
    fun `cooldown failure while paused clears pending work without advancing a playable queue`() = runTest {
        for ((resumeRequested, resumeAfterRefresh) in listOf(false to false, false to true, true to false)) {
            assertPausedFailureRetainsQueue(resumeRequested, resumeAfterRefresh)
        }
    }

    private fun TestScope.assertPausedFailureRetainsQueue(resumeRequested: Boolean, resumeAfterRefresh: Boolean) {
        withPlaybackState {
            val pendingIo = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()) + SupervisorJob())
            val previousStatsOwner = manager.playbackStatsOwner
            manager.playbackStatsOwner = mock(PlaybackStatsOwner::class.java)
            manager.ioScope = pendingIo
            manager.initialized = true
            val queue = listOf(song(518L), song(519L))
            manager.publishCurrentQueue(queue, 0)
            manager._currentSongFlow.value = queue.first()
            manager.updateResumePlaybackRequested(resumeRequested)
            manager.pendingMediaLoadActive = true
            manager.rememberPendingSeekPosition(12_000L)
            try {
                manager.refreshCurrentSongUrlImpl(
                    resumePositionMs = 12_000L,
                    allowFallback = false,
                    reason = "paused_seek_failed",
                    resumePlaybackAfterRefresh = resumeAfterRefresh
                )

                runCurrent()

                assertEquals(queue, manager.currentPlaylist)
                assertEquals(0, manager.currentIndex)
                assertEquals(queue.first(), manager._currentSongFlow.value)
                assertFalse(manager.resumePlaybackRequested)
                assertFalse(playWhenReady)
                assertFalse(manager.pendingMediaLoadActive)
                assertEquals(null, manager.pendingSeekPositionOrNull())
            } finally {
                pendingIo.cancel()
                manager.playJob?.cancel()
                PlaybackTransitionWakeLock.release(manager.playbackRequestToken, "cooldown_test")
                manager.playbackStatsOwner = previousStatsOwner
            }
        }
    }

    private fun requestCooldownFallback(resume: Boolean = false) {
        manager.refreshCurrentSongUrlImpl(
            resumePositionMs = 12_000L,
            allowFallback = true,
            reason = "cooldown_test",
            fallbackSeekPositionMs = 12_000L,
            resumePlaybackAfterRefresh = resume
        )
    }

    private fun requestCooldownFailure() {
        manager.refreshCurrentSongUrlImpl(
            resumePositionMs = 12_000L,
            allowFallback = false,
            reason = "cooldown_test"
        )
    }

    private fun TestScope.withPlaybackState(check: FakeExoPlayer.() -> Unit) {
        val previousMainScope = manager.mainScope
        val previousIoScope = manager.ioScope
        val previousSong = manager._currentSongFlow.value
        val previousPosition = manager._playbackPositionMs.value
        val previousRequestToken = manager.playbackRequestToken
        val previousPositionGeneration = manager.playbackPositionGeneration
        val previousResumeRequested = manager.resumePlaybackRequested
        val previousRefreshKey = manager.lastUrlRefreshKey
        val previousRefreshAt = manager.lastUrlRefreshAtMs
        val previousFailures = manager.consecutivePlayFailures
        val previousMediaUrl = manager._currentMediaUrl.value
        val previousAudioInfo = manager._currentPlaybackAudioInfo.value
        val previousIndex = manager.currentIndex
        val previousQueue = manager.currentPlaylist
        val previousInitialized = manager.initialized
        val previousPlayJob = manager.playJob
        val previousPendingSeek = manager.pendingSeekPositionOrNull()
        val previousDuration = manager.playbackDurationFlow.value
        val previousProgressJob = manager.progressJob
        val previousStartupJob = manager.playbackStartupWatchdogJob
        val previousStartupToken = manager.playbackStartupWatchdogToken
        val previousRuntimeJob = manager.playbackRuntimeWatchdogJob
        val previousRuntimeToken = manager.playbackRuntimeWatchdogToken
        val previousRuntimeAttempts = manager.playbackRuntimeStallRecoveryAttempts
        val previousRuntimePosition = manager.playbackRuntimeLastProgressPositionMs
        val previousRuntimeProgressAt = manager.playbackRuntimeLastProgressAtElapsedRealtimeMs
        val previousCandidates = manager.activePlaybackCandidates
        val previousCandidateIndex = manager.activePlaybackUrlIndex
        val previousCandidatePosition = manager.activePlaybackResumePositionMs
        val previousCommandSource = manager.activePlaybackCommandSource
        val previousStartupAttempts = manager.startupStallRecoveryAttempts
        val previousProgressBaseline = manager.playbackProgressBaselinePositionMs
        val previousProgressReported = manager.playbackProgressAdvanceReported
        val previousTrackEnd = manager.lastHandledTrackEndKey
        val previousIsPlaying = manager._isPlayingFlow.value
        val previousPlayWhenReady = manager._playWhenReadyFlow.value
        val previousPlaybackState = manager._playerPlaybackStateFlow.value
        val previousPendingMediaLoad = manager.pendingMediaLoadActive
        val previousMuteVolume = manager.audioRouteMuteRestoreVolume
        val previousExplicitMute = manager.audioRouteMuteRequiresExplicitRestore
        val previousMuted = manager._audioRouteMuteSuppressedFlow.value
        val fake = FakeExoPlayer().apply { positionMs = 40_000L }
        PlayerTestEnvironment.install()
        fake.installInto(manager, ApplicationProvider.getApplicationContext<Application>()) {
            try {
                manager.mainScope = backgroundScope
                manager.ioScope = backgroundScope
                manager.progressJob = null
                manager.playbackStartupWatchdogJob = null
                manager.playbackRuntimeWatchdogJob = null
                manager._currentSongFlow.value = song(518L)
                manager._playbackPositionMs.value = 40_000L
                manager.playbackRequestToken = 10L
                manager.playbackPositionGeneration = 20L
                manager.updateResumePlaybackRequested(false)
                manager.consecutivePlayFailures = 0
                manager.lastUrlRefreshKey = manager.computeCacheKey(requireNotNull(manager._currentSongFlow.value))
                manager.lastUrlRefreshAtMs = SystemClock.elapsedRealtime()
                fake.check()
            } finally {
                backgroundScope.coroutineContext.cancelChildren()
                manager.mainScope = previousMainScope
                manager.ioScope = previousIoScope
                manager._currentSongFlow.value = previousSong
                manager._playbackPositionMs.value = previousPosition
                manager.playbackRequestToken = previousRequestToken
                manager.playbackPositionGeneration = previousPositionGeneration
                manager.updateResumePlaybackRequested(previousResumeRequested)
                manager.lastUrlRefreshKey = previousRefreshKey
                manager.lastUrlRefreshAtMs = previousRefreshAt
                manager.consecutivePlayFailures = previousFailures
                manager._currentMediaUrl.value = previousMediaUrl
                manager._currentPlaybackAudioInfo.value = previousAudioInfo
                manager.currentIndex = previousIndex
                manager.publishCurrentQueue(previousQueue, previousIndex)
                manager.initialized = previousInitialized
                manager.playJob = previousPlayJob
                manager.clearPendingSeekPosition()
                previousPendingSeek?.let(manager::rememberPendingSeekPosition)
                manager.playbackProgressOwner.setDuration(previousDuration)
                manager.progressJob = previousProgressJob
                manager.playbackStartupWatchdogJob = previousStartupJob
                manager.playbackStartupWatchdogToken = previousStartupToken
                manager.playbackRuntimeWatchdogJob = previousRuntimeJob
                manager.playbackRuntimeWatchdogToken = previousRuntimeToken
                manager.playbackRuntimeStallRecoveryAttempts = previousRuntimeAttempts
                manager.playbackRuntimeLastProgressPositionMs = previousRuntimePosition
                manager.playbackRuntimeLastProgressAtElapsedRealtimeMs = previousRuntimeProgressAt
                manager.activePlaybackCandidates = previousCandidates
                manager.activePlaybackUrlIndex = previousCandidateIndex
                manager.activePlaybackResumePositionMs = previousCandidatePosition
                manager.activePlaybackCommandSource = previousCommandSource
                manager.startupStallRecoveryAttempts = previousStartupAttempts
                manager.playbackProgressBaselinePositionMs = previousProgressBaseline
                manager.playbackProgressAdvanceReported = previousProgressReported
                manager.lastHandledTrackEndKey = previousTrackEnd
                manager._isPlayingFlow.value = previousIsPlaying
                manager._playWhenReadyFlow.value = previousPlayWhenReady
                manager._playerPlaybackStateFlow.value = previousPlaybackState
                manager.pendingMediaLoadActive = previousPendingMediaLoad
                manager.audioRouteMuteRestoreVolume = previousMuteVolume
                manager.audioRouteMuteRequiresExplicitRestore = previousExplicitMute
                manager._audioRouteMuteSuppressedFlow.value = previousMuted
                PlayerTestEnvironment.reset()
            }
        }
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Cooldown $id",
        artist = "Test artist",
        album = "Test album",
        albumId = 0L,
        durationMs = 120_000L,
        coverUrl = null
    )
}
