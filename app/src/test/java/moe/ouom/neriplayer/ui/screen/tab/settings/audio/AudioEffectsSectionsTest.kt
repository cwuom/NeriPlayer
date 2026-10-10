package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPresetIds
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsProfile
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSoundSection
import moe.ouom.neriplayer.data.model.playback.effects.ParametricEqBand
import moe.ouom.neriplayer.data.model.playback.effects.SpeakerOptimizerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsSectionsTest {
    private val tuned = AudioEffectsSound(
        preampDb = -3f,
        equalizerBandsDb = List(10) { 2f },
        parametricEnabled = true,
        parametricBands = listOf(ParametricEqBand()),
        bassDb = 4f,
        vocal = 0.5f,
        stereoWidth = 1.5f,
        reverb = 0.3f,
        compressorEnabled = true,
        outputGainDb = 2f
    )

    @Test
    fun `each section reset only touches its own controls`() {
        val eq = tuned.resetSection(AudioEffectsSection.EQUALIZER)
        assertEquals(0f, eq.preampDb)
        assertEquals(List(10) { 0f }, eq.equalizerBandsDb)
        assertTrue(eq.parametricBands.isEmpty())
        assertEquals(4f, eq.bassDb)

        val tone = tuned.resetSection(AudioEffectsSection.TONE)
        assertEquals(0f, tone.bassDb)
        assertEquals(0f, tone.vocal)
        assertEquals(1.5f, tone.stereoWidth)

        val space = tuned.resetSection(AudioEffectsSection.SPACE)
        assertEquals(1f, space.stereoWidth)
        assertEquals(0f, space.reverb)
        assertTrue(space.compressorEnabled)

        val dynamics = tuned.resetSection(AudioEffectsSection.DYNAMICS)
        assertFalse(dynamics.compressorEnabled)
        assertEquals(0f, dynamics.outputGainDb)
        assertEquals(List(10) { 2f }, dynamics.equalizerBandsDb)

        assertEquals(tuned, tuned.resetSection(AudioEffectsSection.PRESETS))
    }

    @Test
    fun `profile reset keeps the switch and names the result`() {
        val profile = AudioEffectsProfile(enabled = true, presetId = "rock", sound = tuned)

        val partial = profile.resetSection(AudioEffectsSection.EQUALIZER)
        assertTrue(partial.enabled)
        assertEquals(AudioEffectsPresetIds.CUSTOM, partial.presetId)

        val flat = AudioEffectsProfile(enabled = true, presetId = "bass_boost", sound = AudioEffectsSound(bassDb = 6f))
            .resetSection(AudioEffectsSection.TONE)
        assertEquals(AudioEffectsPresetIds.FLAT, flat.presetId)

        val untouched = AudioEffectsProfile(presetId = "rock", sound = tuned).resetSection(AudioEffectsSection.SPEAKER)
        assertEquals("rock", untouched.presetId)
    }

    @Test
    fun `speaker reset restores tuning but keeps it switched on`() {
        val reset = SpeakerOptimizerSettings(enabled = true, loudness = 1f, bassHarmonics = 0f).resetTuning()
        assertEquals(SpeakerOptimizerSettings(enabled = true), reset)
    }

    @Test
    fun `tab dots mark sections that differ from defaults`() {
        val profile = AudioEffectsProfile(enabled = true, presetId = AudioEffectsPresetIds.CUSTOM, sound = AudioEffectsSound(bassDb = 3f))
        val settings = AudioEffectsSettings(speaker = SpeakerOptimizerSettings(enabled = true))
        val normal = PlaybackSoundState()

        assertTrue(AudioEffectsSection.PRESETS.isModified(profile, settings, normal))
        assertTrue(AudioEffectsSection.TONE.isModified(profile, settings, normal))
        assertFalse(AudioEffectsSection.EQUALIZER.isModified(profile, settings, normal))
        assertTrue(AudioEffectsSection.SPEAKER.isModified(profile, settings, normal))
        assertFalse(AudioEffectsSection.SPEED.isModified(profile, settings, normal))
        assertTrue(AudioEffectsSection.SPEED.isModified(profile, settings, PlaybackSoundState(pitch = 1.1f)))
        assertFalse(AudioEffectsSection.ADVANCED.isModified(profile, settings, normal))
        assertFalse(AudioEffectsSection.PRESETS.isModified(AudioEffectsProfile(), AudioEffectsSettings(), normal))
    }

    @Test
    fun `switchable sections map to their own switches`() {
        assertFalse(AudioEffectsSection.PRESETS.switchable)
        assertFalse(AudioEffectsSection.ADVANCED.switchable)
        assertTrue(AudioEffectsSection.entries.filter { it.switchable }.all { it.resettable })

        val profile = AudioEffectsProfile(sound = AudioEffectsSound(spaceEnabled = false))
        val settings = AudioEffectsSettings(speaker = SpeakerOptimizerSettings(enabled = true), speedEnabled = false)
        assertFalse(AudioEffectsSection.SPACE.isEnabled(profile, settings))
        assertTrue(AudioEffectsSection.TONE.isEnabled(profile, settings))
        assertTrue(AudioEffectsSection.SPEAKER.isEnabled(profile, settings))
        assertFalse(AudioEffectsSection.SPEED.isEnabled(profile, settings))
        assertTrue(AudioEffectsSection.PRESETS.isEnabled(profile, settings))
    }

    @Test
    fun `turning a section on also turns effects on`() {
        val off = AudioEffectsProfile(enabled = false, sound = AudioEffectsSound(toneEnabled = false))

        val on = off.withSectionEnabled(AudioEffectsSoundSection.TONE, true)
        assertTrue(on.enabled)
        assertTrue(on.sound.toneEnabled)

        val stillOff = off.withSectionEnabled(AudioEffectsSoundSection.SPACE, false)
        assertFalse(stillOff.enabled)
    }

    @Test
    fun `speed section remembers the chosen speed while switched off`() {
        val playing = PlaybackSoundState(speed = 1.25f, pitch = 1.25f)

        val off = AudioEffectsSettings().toggleSpeedSection(false, playing)
        assertFalse(off.settings.speedEnabled)
        assertEquals(1.25f, off.settings.storedSpeed)
        assertEquals(1f, off.speed)
        assertEquals(1f, off.pitch)
        assertTrue(AudioEffectsSection.SPEED.isModified(AudioEffectsProfile(), off.settings, PlaybackSoundState()))

        val on = off.settings.toggleSpeedSection(true, PlaybackSoundState())
        assertTrue(on.settings.speedEnabled)
        assertEquals(1.25f, on.speed)
        assertEquals(1.25f, on.pitch)

        val reset = off.settings.withSpeedSectionDefaults()
        assertTrue(reset.speedEnabled)
        assertEquals(1f, reset.storedSpeed)
    }

    @Test
    fun `typed values accept everyday formats and stay in range`() {
        assertEquals(3.5f, AudioEffectsInputs.Db.parse("+3.5", -12f..12f))
        assertEquals(-2f, AudioEffectsInputs.Db.parse("-2 dB", -12f..12f))
        assertEquals(12f, AudioEffectsInputs.Db.parse("40", -12f..12f))
        assertEquals(0.75f, AudioEffectsInputs.Percent.parse("75%", 0f..1f))
        assertEquals(1.25f, AudioEffectsInputs.Multiplier.parse("1，25", 0.25f..3f))
        assertEquals(2.4f, AudioEffectsInputs.Db.parse("2.37", -12f..12f))
        assertNull(AudioEffectsInputs.Db.parse("abc", -12f..12f))
        assertNull(AudioEffectsInputs.Db.parse("", -12f..12f))
        assertEquals(1_000f, sliderToFrequency(AudioEffectsInputs.LogFrequency.parse("1000", 0f..1f)!!), 10f)
    }

    @Test
    fun `input dialog shows values in display units`() {
        assertEquals("150", AudioEffectsInputs.Percent.displayText(1.5f))
        assertEquals("-3.0", AudioEffectsInputs.Db.displayText(-3f))
        assertEquals(0f to 200f, AudioEffectsInputs.Percent.displayBounds(0f..2f))
        assertEquals(20f to 20_000f, AudioEffectsInputs.LogFrequency.displayBounds(0f..1f))
        assertEquals("-2", toggleInputSign("2"))
        assertEquals("2", toggleInputSign("-2"))
    }
}
