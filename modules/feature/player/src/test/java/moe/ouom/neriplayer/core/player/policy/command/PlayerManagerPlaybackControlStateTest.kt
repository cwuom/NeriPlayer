package moe.ouom.neriplayer.core.player.policy.command

import moe.ouom.neriplayer.core.player.playback.resolveAudioRouteMuteRestoreVolume
import moe.ouom.neriplayer.core.player.playback.shouldDeferAudioRouteMuteRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerPlaybackControlStateTest {

    @Test
    fun `route mute only records a restorable nonzero volume`() {
        assertNull(
            resolveAudioRouteMuteRestoreVolume(
                currentVolume = 0f,
                existingRestoreVolume = null
            )
        )
        assertEquals(
            0.6f,
            resolveAudioRouteMuteRestoreVolume(
                currentVolume = 0.6f,
                existingRestoreVolume = null
            ) ?: -1f,
            0f
        )
        assertEquals(
            0.4f,
            resolveAudioRouteMuteRestoreVolume(
                currentVolume = 1f,
                existingRestoreVolume = 0.4f
            ) ?: -1f,
            0f
        )
    }

    @Test
    fun `listener route mute defers transient restoration until explicit user action`() {
        assertTrue(shouldDeferAudioRouteMuteRestore(requiresExplicitRestore = true))
        assertFalse(shouldDeferAudioRouteMuteRestore(requiresExplicitRestore = false))
    }
}
