package moe.ouom.neriplayer.core.player.playback

import android.app.Application
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.prefetch.replacePlaybackDemandCacheKey
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerManagerPlaybackFailureRecoveryTest {
    private val manager = PlayerManager

    @Test
    fun `single login-required track stops repeat all and releases pending playback`() = runTest {
        withPendingPlayback { fake, events ->
            manager.updateResumePlaybackRequested(true)

            playbackFailureRecoveryOwner.onResolutionFailure(
                10L, PlaybackResolutionFailure.LOGIN_REQUIRED, PlaybackCommandSource.LOCAL
            )
            runCurrent()

            assertStoppedWithQueueRetained(fake)
            val prompt = events.single() as PlayerEvent.ShowLoginPrompt
            assertTrue(prompt.message.isNotBlank())
            assertNull(manager.currentPlaybackDemandCacheKey)
            assertFalse(manager.playbackDemandArbiter.shouldYieldPrefetch(DEMAND_KEY))
        }
    }

    @Test
    fun `paused URL resolution failure clears pending playback without starting another track`() = runTest {
        withPendingPlayback { fake, events ->
            manager.updateResumePlaybackRequested(false)

            playbackFailureRecoveryOwner.onResolutionFailure(
                10L, PlaybackResolutionFailure.URL_UNAVAILABLE, PlaybackCommandSource.LOCAL
            )
            runCurrent()

            assertStoppedWithQueueRetained(fake)
            assertTrue(events.isEmpty())
            assertNull(manager.currentPlaybackDemandCacheKey)
            assertFalse(manager.playbackDemandArbiter.shouldYieldPrefetch(DEMAND_KEY))
        }
    }

    @Test
    fun `old login error leaves newer pending playback and cache demand untouched`() = runTest {
        withPendingPlayback { fake, events ->
            manager.playbackRequestToken = 11L
            manager.updateResumePlaybackRequested(true)

            playbackFailureRecoveryOwner.onResolutionFailure(
                10L, PlaybackResolutionFailure.LOGIN_REQUIRED, PlaybackCommandSource.LOCAL
            )
            runCurrent()

            assertTrue(manager.pendingMediaLoadActive)
            assertTrue(manager.resumePlaybackRequested)
            assertEquals(DEMAND_KEY, manager.currentPlaybackDemandCacheKey)
            assertTrue(manager.playbackDemandArbiter.shouldYieldPrefetch(DEMAND_KEY))
            assertEquals(0, manager.consecutivePlayFailures)
            assertTrue(fake.commands.isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    private fun assertStoppedWithQueueRetained(fake: FakeExoPlayer) {
        assertFalse(manager.pendingMediaLoadActive)
        assertFalse(manager.resumePlaybackRequested)
        assertFalse(fake.playWhenReady)
        assertTrue(fake.commands.contains("stop"))
        assertFalse(fake.commands.contains("play"))
        assertEquals(listOf(song), manager.currentPlaylist)
        assertEquals(0, manager.currentIndex)
        assertEquals(song, manager.currentSongFlow.value)
        assertEquals(Player.STATE_IDLE, manager.playerPlaybackStateFlow.value)
        assertNull(manager._currentMediaUrl.value)
    }

    private fun TestScope.withPendingPlayback(check: (FakeExoPlayer, List<PlayerEvent>) -> Unit) {
        val previousMainScope = manager.mainScope
        val previousIoScope = manager.ioScope
        val previousInitialized = manager.initialized
        val previousQueue = manager.currentQueueSnapshot()
        val previousSong = manager._currentSongFlow.value
        val previousPosition = manager._playbackPositionMs.value
        val previousRequestToken = manager.playbackRequestToken
        val previousResumeRequested = manager.resumePlaybackRequested
        val previousPendingLoad = manager.pendingMediaLoadActive
        val previousPendingPosition = manager.pendingMediaLoadPositionMs
        val previousPendingSeek = manager.pendingSeekPositionOrNull()
        val previousFailures = manager.consecutivePlayFailures
        val previousDemandKey = manager.currentPlaybackDemandCacheKey
        val previousRepeatMode = manager.repeatModeSetting
        val previousRememberProgress = manager.rememberLongFormPlaybackProgressEnabled
        val previousMediaUrl = manager._currentMediaUrl.value
        val previousAudioInfo = manager._currentPlaybackAudioInfo.value
        val previousUrlResolvedAt = manager.currentMediaUrlResolvedAtMs
        val previousIsPlaying = manager._isPlayingFlow.value
        val previousPlayWhenReady = manager._playWhenReadyFlow.value
        val previousPlayerState = manager._playerPlaybackStateFlow.value
        val previousCandidates = manager.activePlaybackCandidates
        val previousCandidateIndex = manager.activePlaybackUrlIndex
        val previousCandidatePosition = manager.activePlaybackResumePositionMs
        val previousCommandSource = manager.activePlaybackCommandSource
        val previousProgressBaseline = manager.playbackProgressBaselinePositionMs
        val previousProgressReported = manager.playbackProgressAdvanceReported
        val events = mutableListOf<PlayerEvent>()
        val fake = FakeExoPlayer()
        PlayerTestEnvironment.install()
        fake.installInto(manager, ApplicationProvider.getApplicationContext<Application>()) {
            try {
                manager.mainScope = backgroundScope
                manager.ioScope = backgroundScope
                manager.initialized = false
                manager.rememberLongFormPlaybackProgressEnabled = false
                manager.publishCurrentQueue(listOf(song), 0)
                manager._currentSongFlow.value = song
                manager._currentMediaUrl.value = null
                manager.playbackRequestToken = 10L
                manager.pendingMediaLoadActive = true
                manager.pendingMediaLoadPositionMs = 0L
                manager.consecutivePlayFailures = 0
                manager.repeatModeSetting = Player.REPEAT_MODE_ALL
                manager.replacePlaybackDemandCacheKey(DEMAND_KEY, "recovery_test")
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    manager.playerEventFlow.collect { events += it }
                }
                check(fake, events)
            } finally {
                backgroundScope.coroutineContext.cancelChildren()
                manager.replacePlaybackDemandCacheKey(previousDemandKey, "recovery_test_restore")
                manager.mainScope = previousMainScope
                manager.ioScope = previousIoScope
                manager.initialized = previousInitialized
                manager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
                manager._currentSongFlow.value = previousSong
                manager._playbackPositionMs.value = previousPosition
                manager.playbackRequestToken = previousRequestToken
                manager.updateResumePlaybackRequested(previousResumeRequested)
                manager.pendingMediaLoadActive = previousPendingLoad
                manager.pendingMediaLoadPositionMs = previousPendingPosition
                manager.clearPendingSeekPosition()
                previousPendingSeek?.let(manager::rememberPendingSeekPosition)
                manager.consecutivePlayFailures = previousFailures
                manager.repeatModeSetting = previousRepeatMode
                manager.rememberLongFormPlaybackProgressEnabled = previousRememberProgress
                manager._currentMediaUrl.value = previousMediaUrl
                manager._currentPlaybackAudioInfo.value = previousAudioInfo
                manager.currentMediaUrlResolvedAtMs = previousUrlResolvedAt
                manager._isPlayingFlow.value = previousIsPlaying
                manager._playWhenReadyFlow.value = previousPlayWhenReady
                manager._playerPlaybackStateFlow.value = previousPlayerState
                manager.activePlaybackCandidates = previousCandidates
                manager.activePlaybackUrlIndex = previousCandidateIndex
                manager.activePlaybackResumePositionMs = previousCandidatePosition
                manager.activePlaybackCommandSource = previousCommandSource
                manager.playbackProgressBaselinePositionMs = previousProgressBaseline
                manager.playbackProgressAdvanceReported = previousProgressReported
                PlayerTestEnvironment.reset()
            }
        }
    }

    private val song = SongItem(518L, "Recovery", "Artist", "Album", 0L, 120_000L, null)

    private companion object {
        const val DEMAND_KEY = "failure-recovery-test-cache"
    }
}
