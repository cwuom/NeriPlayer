package moe.ouom.neriplayer.data.model.playback.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsSoundSectionsTest {
    private val tuned = AudioEffectsSound(
        preampDb = -2f,
        equalizerBandsDb = List(10) { 3f },
        bassDb = 4f,
        stereoWidth = 1.6f,
        reverb = 0.4f,
        outputGainDb = 2f,
        limiterCeilingDb = -3f
    ).withCompressorAmount(0.5f)

    @Test
    fun `switched off sections reach the DSP as defaults but keep their settings`() {
        val off = tuned.copy(toneEnabled = false, spaceEnabled = false)
        val effective = off.effective()

        assertEquals(0f, effective.bassDb)
        assertEquals(1f, effective.stereoWidth)
        assertEquals(0f, effective.reverb)
        assertEquals(List(10) { 3f }, effective.equalizerBandsDb)
        assertTrue(effective.compressorEnabled)
        assertEquals(4f, off.bassDb)
        assertEquals(1.6f, off.stereoWidth)
    }

    @Test
    fun `dynamics off still keeps the default clipping guard`() {
        val effective = tuned.copy(dynamicsEnabled = false).effective()

        assertFalse(effective.compressorEnabled)
        assertEquals(0f, effective.outputGainDb)
        assertTrue(effective.limiterEnabled)
        assertEquals(-1f, effective.limiterCeilingDb)
    }

    @Test
    fun `equalizer off removes bands, preamp and headphone correction`() {
        val withCorrection = tuned.copy(parametricEnabled = true, parametricBands = listOf(ParametricEqBand(gainDb = 5f)))
        val effective = withCorrection.copy(equalizerEnabled = false).effective()

        assertEquals(0f, effective.preampDb)
        assertTrue(effective.equalizerBandsDb.all { it == 0f })
        assertFalse(effective.parametricEnabled)
    }

    @Test
    fun `turning every changed section off lets the player bypass the DSP`() {
        val allOff = tuned.copy(equalizerEnabled = false, toneEnabled = false, spaceEnabled = false, dynamicsEnabled = false)
        assertTrue(allOff.isNeutral())
        assertFalse(tuned.isNeutral())

        val settings = AudioEffectsSettings(main = AudioEffectsProfile(enabled = true, sound = allOff))
        val resolution = settings.resolveDsp(AudioEffectsRuntimeContext(AudioOutputRoute.WIRED))
        assertFalse(resolution.active)
        assertEquals(AudioEffectsInactiveReason.NEUTRAL, resolution.inactiveReason)
    }

    @Test
    fun `DSP parameters follow the section switches`() {
        val settings = AudioEffectsSettings(
            main = AudioEffectsProfile(enabled = true, sound = tuned.copy(toneEnabled = false))
        )
        val params = settings.resolveDsp(AudioEffectsRuntimeContext(AudioOutputRoute.WIRED)).params

        assertEquals(0f, params[NeriDspParams.BASS_GAIN_DB])
        assertEquals(3f, params[NeriDspParams.GRAPHIC_EQ_GAIN_0])
        assertEquals(1.6f, params[NeriDspParams.STEREO_WIDTH])
    }

    @Test
    fun `section defaults reopen the section`() {
        val reset = tuned.copy(spaceEnabled = false).withSectionDefaults(AudioEffectsSoundSection.SPACE)
        assertTrue(reset.spaceEnabled)
        assertEquals(1f, reset.stereoWidth)
        assertEquals(4f, reset.bassDb)
        AudioEffectsSoundSection.entries.forEach { section ->
            assertFalse(tuned.withSectionEnabled(section, false).isSectionEnabled(section))
        }
    }

    @Test
    fun `remembered speed is kept within playable limits`() {
        val normalized = AudioEffectsSettings(storedSpeed = 99f, storedPitch = Float.NaN).normalized()
        assertEquals(4f, normalized.storedSpeed)
        assertEquals(1f, normalized.storedPitch)
    }
}
