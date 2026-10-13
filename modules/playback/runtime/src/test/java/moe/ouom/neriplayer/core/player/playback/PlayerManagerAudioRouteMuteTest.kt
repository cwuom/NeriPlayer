package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerManagerAudioRouteMuteTest {
    private val manager = PlayerManager
    private val previousMainScope = manager.mainScope
    private val fake = FakeExoPlayer()

    @Before
    fun setUp() {
        PlayerTestEnvironment.install()
    }

    @After
    fun tearDown() {
        manager.audioRouteMuteRestoreVolume = null
        manager.audioRouteMuteRequiresExplicitRestore = false
        manager._audioRouteMuteSuppressedFlow.value = false
        manager._isPlayingFlow.value = false
        manager.progressJob = null
        manager.volumeFadeJob?.cancel()
        manager.volumeFadeJob = null
        manager.pendingMediaLoadActive = false
        manager.mainScope = previousMainScope
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `route loss mutes the player and an explicit restore brings the captured volume back`() = runTest {
        manager.mainScope = backgroundScope
        fake.volume = 0.8f
        fake.installInto(manager) {
            manager.suppressPlaybackForAudioRouteLoss("unplugged")
            fake.volume = 0.3f
            manager.suppressPlaybackForAudioRouteLoss("unplugged_again")

            assertEquals(0f, fake.volume, 0f)
            assertTrue(manager._audioRouteMuteSuppressedFlow.value)

            manager.restoreAudioRouteMuteImpl()

            assertEquals(0.8f, fake.volume, 0f)
            assertFalse(manager._audioRouteMuteSuppressedFlow.value)
            assertNull(manager.audioRouteMuteRestoreVolume)
        }
    }

    @Test
    fun `route loss on a silent player does not enter the muted state`() = runTest {
        manager.mainScope = backgroundScope
        fake.volume = 0f
        fake.installInto(manager) {
            manager.suppressPlaybackForAudioRouteLoss("unplugged")

            assertFalse(manager._audioRouteMuteSuppressedFlow.value)
            assertNull(manager.audioRouteMuteRestoreVolume)
        }
    }

    @Test
    fun `route loss before the first fade step preserves an audible restore target`() = runTest {
        manager.mainScope = backgroundScope
        fake.volume = 0f
        fake.playWhenReady = true
        val fade = Job()
        manager.volumeFadeJob = fade
        fake.installInto(manager) {
            manager.suppressPlaybackForAudioRouteLoss("fade_start")

            assertTrue(fade.isCancelled)
            assertTrue(manager._audioRouteMuteSuppressedFlow.value)
            assertEquals(0f, fake.volume, 0f)
            manager.restorePlaybackAfterTransientAudioRouteLoss("route_back")
            assertEquals(1f, fake.volume, 0f)
        }
    }

    @Test
    fun `route loss during fade restores the target instead of a partial step`() = runTest {
        manager.mainScope = backgroundScope
        fake.volume = 0.05f
        fake.playWhenReady = true
        manager.volumeFadeJob = Job()
        fake.installInto(manager) {
            manager.suppressPlaybackForAudioRouteLoss("fade_in_progress")
            manager.restorePlaybackAfterTransientAudioRouteLoss("route_back")
            assertEquals(1f, fake.volume, 0f)
        }
    }

    @Test
    fun `route loss while preparing a silent track retains suppression until restoration`() = runTest {
        manager.mainScope = backgroundScope
        manager.pendingMediaLoadActive = true
        fake.volume = 0f
        fake.installInto(manager) {
            manager.suppressPlaybackForAudioRouteLoss("preparing")
            assertTrue(manager._audioRouteMuteSuppressedFlow.value)
            manager.restoreAudioRouteMuteImpl()
            assertEquals(1f, fake.volume, 0f)
        }
    }

    @Test
    fun `route loss stays muted while a pending fade out completes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        manager.mainScope = backgroundScope
        manager._isPlayingFlow.value = true
        try {
            fake.installInto(manager) {
                val fade = launch {
                    manager.fadeOutCurrentPlaybackIfNeeded(enabled = true, fadeOutDurationMs = 200L)
                }
                runCurrent()
                manager.suppressPlaybackForAudioRouteLoss("fade_out")
                advanceTimeBy(100L)
                runCurrent()
                assertEquals(0f, fake.volume, 0f)
                fade.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `restore without a captured volume only clears the muted flags`() = runTest {
        manager.mainScope = backgroundScope
        manager.audioRouteMuteRequiresExplicitRestore = true
        manager._audioRouteMuteSuppressedFlow.value = true
        fake.volume = 0.4f
        fake.installInto(manager) {
            manager.restoreAudioRouteMuteImpl()

            assertEquals(0.4f, fake.volume, 0f)
        }
        assertFalse(manager.audioRouteMuteRequiresExplicitRestore)
        assertFalse(manager._audioRouteMuteSuppressedFlow.value)
    }

    @Test
    fun `transient route recovery restores volume only for active playback`() = runTest {
        manager.mainScope = backgroundScope
        fake.installInto(manager) {
            fake.volume = 0f
            manager.audioRouteMuteRestoreVolume = 0.6f
            manager._audioRouteMuteSuppressedFlow.value = true
            manager.restorePlaybackAfterTransientAudioRouteLoss("route_back_idle")
            assertEquals(0f, fake.volume, 0f)
            assertFalse(manager._audioRouteMuteSuppressedFlow.value)

            fake.playWhenReady = true
            manager.audioRouteMuteRestoreVolume = 0.6f
            manager.restorePlaybackAfterTransientAudioRouteLoss("route_back_playing")
            assertEquals(0.6f, fake.volume, 0f)
            assertNull(manager.audioRouteMuteRestoreVolume)
        }
    }

    @Test
    fun `stopping progress updates cancels the running progress job`() = runTest {
        manager.mainScope = backgroundScope
        val running = Job()
        manager.progressJob = running
        manager.stopProgressUpdatesImpl()
        assertTrue(running.isCancelled)
        assertNull(manager.progressJob)

        manager.progressJob = Job().apply { complete() }
        manager.stopProgressUpdatesImpl()
        assertNull(manager.progressJob)

        manager.stopProgressUpdatesImpl()
        assertNull(manager.progressJob)
    }

    @Test
    fun `transient recovery keeps an explicit listener mute and ignores a missing capture`() = runTest {
        manager.mainScope = backgroundScope
        fake.installInto(manager) {
            fake.volume = 0f
            fake.playWhenReady = true
            manager.audioRouteMuteRestoreVolume = 0.5f
            manager.audioRouteMuteRequiresExplicitRestore = true
            manager.restorePlaybackAfterTransientAudioRouteLoss("listener")
            assertEquals(0f, fake.volume, 0f)
            assertEquals(0.5f, manager.audioRouteMuteRestoreVolume)

            manager.audioRouteMuteRequiresExplicitRestore = false
            manager.audioRouteMuteRestoreVolume = null
            manager._audioRouteMuteSuppressedFlow.value = true
            manager.restorePlaybackAfterTransientAudioRouteLoss("nothing_captured")
            assertFalse(manager._audioRouteMuteSuppressedFlow.value)
            assertEquals(0f, fake.volume, 0f)
        }
    }
}
