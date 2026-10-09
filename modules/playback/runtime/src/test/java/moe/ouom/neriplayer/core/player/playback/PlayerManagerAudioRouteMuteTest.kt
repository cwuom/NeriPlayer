package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
