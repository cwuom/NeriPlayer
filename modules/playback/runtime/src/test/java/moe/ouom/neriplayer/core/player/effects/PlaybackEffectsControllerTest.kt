package moe.ouom.neriplayer.core.player.effects

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import moe.ouom.neriplayer.core.player.audio.processing.PlaybackVolumeBalanceState
import moe.ouom.neriplayer.core.player.audio.processing.PlaybackVolumeNormalizationState
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlaybackEffectsControllerTest {
    private val controller = PlaybackEffectsController()

    @After
    fun tearDown() {
        PlaybackVolumeBalanceState.update(0f)
        PlaybackVolumeNormalizationState.updateEnabled(false)
    }

    @Test
    fun `system audio effects are never bound to the player session`() {
        mockConstruction(Equalizer::class.java).use { equalizers ->
            mockConstruction(LoudnessEnhancer::class.java).use { enhancers ->
                controller.attachPlayer(playerWithSession(7))
                controller.updateConfig(PlaybackSoundConfig(speed = 1.2f, volumeNormalizationEnabled = true))
                controller.release()

                assertTrue(equalizers.constructed().isEmpty())
                assertTrue(enhancers.constructed().isEmpty())
            }
        }
    }

    @Test
    fun `speed and pitch are normalized before they reach the player`() {
        val player = playerWithSession(7)
        controller.attachPlayer(player)
        verify(player).playbackParameters = PlaybackParameters(1f, 1f)

        val faster = controller.updateConfig(PlaybackSoundConfig(speed = 1.53f))
        verify(player).playbackParameters = PlaybackParameters(1.55f, 1f)
        assertEquals(1.55f, faster.speed)

        val lower = controller.updateConfig(PlaybackSoundConfig(speed = 1.55f, pitch = 0.1f))
        verify(player).playbackParameters = PlaybackParameters(1.55f, 0.25f)
        assertEquals(0.25f, lower.pitch)

        controller.updateConfig(PlaybackSoundConfig(speed = 1.55f, pitch = 0.25f))
        verify(player, times(3)).playbackParameters = any()
    }

    @Test
    fun `volume balance and normalization are published until the controller is released`() {
        controller.attachPlayer(playerWithSession(7))

        val state = controller.updateConfig(
            PlaybackSoundConfig(volumeBalance = -0.333f, volumeNormalizationEnabled = true)
        )
        assertEquals(-0.33f, state.volumeBalance)
        assertEquals(-0.33f, PlaybackVolumeBalanceState.current())
        assertTrue(PlaybackVolumeNormalizationState.current().enabled)

        val released = controller.release()

        assertEquals(0f, PlaybackVolumeBalanceState.current())
        assertFalse(PlaybackVolumeNormalizationState.current().enabled)
        assertEquals(-0.33f, released.volumeBalance)
    }

    private fun playerWithSession(sessionId: Int): ExoPlayer = mock(ExoPlayer::class.java).also {
        `when`(it.audioSessionId).thenReturn(sessionId)
    }
}
