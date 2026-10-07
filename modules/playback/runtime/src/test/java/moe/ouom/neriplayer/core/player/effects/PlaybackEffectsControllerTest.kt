package moe.ouom.neriplayer.core.player.effects

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import moe.ouom.neriplayer.core.player.audio.processing.PlaybackVolumeBalanceState
import moe.ouom.neriplayer.core.player.audio.processing.PlaybackVolumeNormalizationState
import moe.ouom.neriplayer.data.model.playback.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyShort
import org.mockito.MockedConstruction
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
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
    fun `controller without an audio session reports flat defaults and creates no effects`() {
        AudioEffects().use { effects ->
            val state = controller.attachPlayer(null)

            assertNull(state.audioSessionId)
            assertFalse(state.equalizerAvailable)
            assertFalse(state.loudnessEnhancerAvailable)
            assertEquals(listOf(60, 230, 910, 3600, 14_000), state.bands.map { it.centerFreqHz })
            assertTrue(state.bands.all { it.levelMb == 0 })
            assertTrue(effects.equalizers.isEmpty())
            assertTrue(effects.enhancers.isEmpty())
        }
    }

    @Test
    fun `enabled equalizer applies custom levels with headroom on the player session`() {
        AudioEffects().use { effects ->
            controller.attachPlayer(playerWithSession(7))
            assertTrue(effects.equalizers.isEmpty())

            val state = controller.updateConfig(customEqualizer(300, -200, 0, 600, -1500))

            val equalizer = effects.equalizers.single()
            assertEquals(listOf(listOf<Any>(0, 7)), effects.equalizerArguments)
            listOf(-300, -800, -600, 0, -1800).forEachIndexed { band, level ->
                verify(equalizer).setBandLevel(band.toShort(), level.toShort())
            }
            assertTrue(effects.isEnabled(equalizer))
            assertEquals(7, state.audioSessionId)
            assertTrue(state.equalizerAvailable)
            assertEquals(-1200..1200, state.bandLevelRangeMb)
            assertEquals(listOf(300, -200, 0, 600, -1200), state.bands.map { it.levelMb })
        }
    }

    @Test
    fun `equalizer only rewrites bands whose applied level changed`() {
        AudioEffects().use { effects ->
            controller.attachPlayer(playerWithSession(7))
            controller.updateConfig(customEqualizer(300, -200, 0, 600, -1500))
            val equalizer = effects.equalizers.single()

            controller.onAudioSessionIdChanged(7)
            verify(equalizer, times(5)).setBandLevel(anyShort(), anyShort())

            controller.updateConfig(customEqualizer(300, 100, 0, 600, -1500))
            verify(equalizer).setBandLevel(1.toShort(), (-500).toShort())
            verify(equalizer, times(6)).setBandLevel(anyShort(), anyShort())

            controller.updateConfig(customEqualizer(300, 100, 0, 600, -1500).copy(presetId = PlaybackEqualizerPresetId.FLAT))
            listOf(0, 1, 2, 4).forEach { band ->
                verify(equalizer).setBandLevel(band.toShort(), 0.toShort())
            }
            verify(equalizer, times(10)).setBandLevel(anyShort(), anyShort())
            assertEquals(1, effects.equalizers.size)
        }
    }

    @Test
    fun `disabling the equalizer releases it but keeps it reported as available`() {
        AudioEffects().use { effects ->
            controller.attachPlayer(playerWithSession(7))
            val enabledConfig = customEqualizer(0, 0, 0, 0, 0)
            controller.updateConfig(enabledConfig)
            val equalizer = effects.equalizers.single()

            val state = controller.updateConfig(enabledConfig.copy(equalizerEnabled = false))

            assertFalse(effects.isEnabled(equalizer))
            verify(equalizer).release()
            assertTrue(state.equalizerAvailable)
            assertFalse(state.equalizerEnabled)

            controller.updateConfig(enabledConfig)
            assertEquals(2, effects.equalizers.size)
        }
    }

    @Test
    fun `moving to another audio session rebinds both effects`() {
        AudioEffects().use { effects ->
            controller.attachPlayer(playerWithSession(7))
            controller.updateConfig(customEqualizer(0, 0, 0, 0, 0).copy(loudnessGainMb = 600))
            val oldEqualizer = effects.equalizers.single()
            val oldEnhancer = effects.enhancers.single()

            val state = controller.onAudioSessionIdChanged(9)

            verify(oldEqualizer).release()
            verify(oldEnhancer).release()
            assertEquals(listOf(listOf<Any>(0, 7), listOf<Any>(0, 9)), effects.equalizerArguments)
            assertEquals(listOf(listOf<Any>(7), listOf<Any>(9)), effects.enhancerArguments)
            verify(effects.enhancers.last()).setTargetGain(600)
            assertEquals(9, state.audioSessionId)
            assertTrue(state.equalizerAvailable)
            assertTrue(state.loudnessEnhancerAvailable)
        }
    }

    @Test
    fun `unset or invalid session ids release the effects`() {
        AudioEffects().use { effects ->
            controller.attachPlayer(playerWithSession(7))
            controller.updateConfig(customEqualizer(0, 0, 0, 0, 0).copy(loudnessGainMb = 300))

            val unset = controller.onAudioSessionIdChanged(C.AUDIO_SESSION_ID_UNSET)

            verify(effects.equalizers.single()).release()
            verify(effects.enhancers.single()).release()
            assertNull(unset.audioSessionId)
            assertFalse(unset.equalizerAvailable)
            assertFalse(unset.loudnessEnhancerAvailable)
            assertNull(controller.onAudioSessionIdChanged(-3).audioSessionId)
            assertNull(controller.onAudioSessionIdChanged(null).audioSessionId)
            assertEquals(1, effects.equalizers.size)
            assertEquals(1, effects.enhancers.size)
        }
    }

    @Test
    fun `losing a session without effects leaves nothing to release`() {
        AudioEffects().use { effects ->
            controller.attachPlayer(playerWithSession(7))

            val state = controller.onAudioSessionIdChanged(null)

            assertNull(state.audioSessionId)
            assertTrue(effects.equalizers.isEmpty())
            assertTrue(effects.enhancers.isEmpty())
        }
    }

    @Test
    fun `loudness gain is clamped and drives the enhancer until it is zero`() {
        AudioEffects().use { effects ->
            controller.attachPlayer(playerWithSession(7))

            val boosted = controller.updateConfig(PlaybackSoundConfig(loudnessGainMb = 2_000))
            val enhancer = effects.enhancers.single()
            verify(enhancer).setTargetGain(1_500)
            assertTrue(effects.isEnabled(enhancer))
            assertEquals(1_500, boosted.loudnessGainMb)
            assertTrue(boosted.loudnessEnhancerAvailable)

            val muted = controller.updateConfig(PlaybackSoundConfig(loudnessGainMb = 0))
            verify(enhancer).setTargetGain(0)
            assertFalse(effects.isEnabled(enhancer))
            assertTrue(muted.loudnessEnhancerAvailable)

            controller.updateConfig(PlaybackSoundConfig(loudnessGainMb = -5))
            verify(enhancer, times(2)).setTargetGain(anyInt())
            assertEquals(1, effects.enhancers.size)
        }
    }

    @Test
    fun `effect failures are reported as unavailable`() {
        AudioEffects(failEqualizerCreation = true, failTargetGain = true).use { effects ->
            controller.attachPlayer(playerWithSession(7))

            val state = controller.updateConfig(customEqualizer(0, 0, 0, 0, 0).copy(loudnessGainMb = 900))

            assertTrue(state.equalizerEnabled)
            assertFalse(state.equalizerAvailable)
            assertFalse(state.loudnessEnhancerAvailable)
            verify(effects.enhancers.single(), never()).setEnabled(true)
        }
    }

    @Test
    fun `loudness enhancer that cannot be created is retried on the next gain change`() {
        AudioEffects(failEnhancerCreation = true).use { effects ->
            controller.attachPlayer(playerWithSession(7))

            val state = controller.updateConfig(PlaybackSoundConfig(loudnessGainMb = 900))
            assertFalse(state.loudnessEnhancerAvailable)
            controller.updateConfig(PlaybackSoundConfig(loudnessGainMb = 1_200))

            assertEquals(listOf(listOf<Any>(7), listOf<Any>(7)), effects.enhancerArguments)
            effects.enhancers.forEach { verify(it, never()).setTargetGain(anyInt()) }
        }
    }

    @Test
    fun `speed and pitch are normalized before they reach the player`() {
        AudioEffects().use {
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
    }

    @Test
    fun `volume balance and normalization are published until the controller is released`() {
        AudioEffects().use { effects ->
            controller.attachPlayer(playerWithSession(7))

            val state = controller.updateConfig(
                customEqualizer(0, 0, 0, 0, 0).copy(volumeBalance = -0.333f, volumeNormalizationEnabled = true)
            )
            assertEquals(-0.33f, state.volumeBalance)
            assertEquals(-0.33f, PlaybackVolumeBalanceState.current())
            assertTrue(PlaybackVolumeNormalizationState.current().enabled)

            val released = controller.release()

            verify(effects.equalizers.single()).release()
            assertEquals(0f, PlaybackVolumeBalanceState.current())
            assertFalse(PlaybackVolumeNormalizationState.current().enabled)
            assertNull(released.audioSessionId)
            assertFalse(released.equalizerAvailable)
        }
    }

    private fun customEqualizer(vararg levelsMb: Int) = PlaybackSoundConfig(
        equalizerEnabled = true,
        presetId = PlaybackEqualizerPresetId.CUSTOM,
        customBandLevelsMb = levelsMb.toList()
    )

    private fun playerWithSession(sessionId: Int): ExoPlayer = mock(ExoPlayer::class.java).also {
        `when`(it.audioSessionId).thenReturn(sessionId)
    }

    private class AudioEffects(
        failEqualizerCreation: Boolean = false,
        failEnhancerCreation: Boolean = false,
        failTargetGain: Boolean = false
    ) : AutoCloseable {
        private val enabledEffects = mutableSetOf<AudioEffect>()
        val equalizerArguments = mutableListOf<List<Any?>>()
        val enhancerArguments = mutableListOf<List<Any?>>()
        private val equalizerConstruction: MockedConstruction<Equalizer> =
            mockConstruction(Equalizer::class.java) { equalizer, context ->
                equalizerArguments += context.arguments()
                trackEnabled(equalizer)
                if (failEqualizerCreation) {
                    doThrow(UnsupportedOperationException("no equalizer")).`when`(equalizer).setEnabled(anyBoolean())
                }
                `when`(equalizer.numberOfBands).thenReturn(BAND_CENTERS_MILLI_HZ.size.toShort())
                `when`(equalizer.bandLevelRange).thenReturn(shortArrayOf(-1200, 1200))
                BAND_CENTERS_MILLI_HZ.forEachIndexed { band, center ->
                    `when`(equalizer.getCenterFreq(band.toShort())).thenReturn(center)
                }
            }
        private val enhancerConstruction: MockedConstruction<LoudnessEnhancer> =
            mockConstruction(LoudnessEnhancer::class.java) { enhancer, context ->
                enhancerArguments += context.arguments()
                trackEnabled(enhancer)
                if (failEnhancerCreation) {
                    doThrow(UnsupportedOperationException("no enhancer")).`when`(enhancer).setEnabled(anyBoolean())
                }
                if (failTargetGain) {
                    doThrow(IllegalStateException("enhancer busy")).`when`(enhancer).setTargetGain(anyInt())
                }
            }

        val equalizers: List<Equalizer> get() = equalizerConstruction.constructed()
        val enhancers: List<LoudnessEnhancer> get() = enhancerConstruction.constructed()

        fun isEnabled(effect: AudioEffect): Boolean = effect in enabledEffects

        private fun trackEnabled(effect: AudioEffect) {
            doAnswer { invocation ->
                if (invocation.getArgument<Boolean>(0)) enabledEffects += effect else enabledEffects -= effect
                AudioEffect.SUCCESS
            }.`when`(effect).setEnabled(anyBoolean())
            doAnswer { effect in enabledEffects }.`when`(effect).enabled
        }

        override fun close() {
            equalizerConstruction.close()
            enhancerConstruction.close()
        }
    }

    private companion object {
        val BAND_CENTERS_MILLI_HZ = listOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000)
    }
}
