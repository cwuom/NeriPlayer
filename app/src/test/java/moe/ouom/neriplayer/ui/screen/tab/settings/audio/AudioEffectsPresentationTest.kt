package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsBuiltInPresets
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsInactiveReason
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsProfile
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsRuntimeStats
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsPresentationTest {
    @Test
    fun `every built in preset has a display name`() {
        AudioEffectsBuiltInPresets.forEach { preset ->
            assertNotNull(preset.id, audioEffectsPresetNameRes(preset.id))
        }
        assertEquals(5, AudioEffectsPresetTabs.size)
    }

    @Test
    fun `status explains why processing is skipped`() {
        assertEquals(
            AudioEffectsStatus.Processing("48 kHz", "0.4%"),
            resolveAudioEffectsStatus(AudioEffectsRuntimeStats(active = true, sampleRate = 48_000, cpuLoadPercent = 0.42f))
        )
        val expected = mapOf(
            AudioEffectsInactiveReason.DISABLED to CoreCommonR.string.audio_effects_status_disabled,
            AudioEffectsInactiveReason.NEUTRAL to CoreCommonR.string.audio_effects_status_neutral,
            AudioEffectsInactiveReason.USB_EXCLUSIVE to CoreCommonR.string.audio_effects_status_usb,
            AudioEffectsInactiveReason.UNSUPPORTED_FORMAT to CoreCommonR.string.audio_effects_status_unsupported,
            AudioEffectsInactiveReason.NATIVE_UNAVAILABLE to CoreCommonR.string.audio_effects_status_native_unavailable
        )
        expected.forEach { (reason, res) ->
            assertEquals(
                AudioEffectsStatus.Bypassed(res),
                resolveAudioEffectsStatus(AudioEffectsRuntimeStats(active = false, inactiveReason = reason))
            )
        }
        assertTrue(shouldShowLimiterNotice(AudioEffectsRuntimeStats(active = true, limiterReductionDb = 1f)))
        assertFalse(shouldShowLimiterNotice(AudioEffectsRuntimeStats(active = true, limiterReductionDb = 0.2f)))
    }

    @Test
    fun `values are formatted for humans`() {
        assertEquals("44.1 kHz", formatSampleRateKhz(44_100))
        assertEquals("<0.1%", formatCpuLoad(0.05f))
        assertEquals("+3.5 dB", formatSignedDb(3.46f))
        assertEquals("-2.0 dB", formatSignedDb(-2f))
        assertEquals("0 dB", formatSignedDb(0.01f))
        assertEquals("150%", formatPercent(1.5f))
        assertEquals("120 Hz", formatFrequency(120f))
        assertEquals("2.5 kHz", formatFrequency(2_500f))
        assertEquals("4.0:1", formatRatio(4f))
        assertEquals("+2.0", formatSemitones(2f))
        assertEquals("1.25x", formatMultiplier(1.25f))
    }

    @Test
    fun `slider snapping lands on steps and returns to zero easily`() {
        assertEquals(0f, snapToStep(0.2f, 0.5f))
        assertEquals(1.5f, snapToStep(1.4f, 0.5f))
        assertEquals(-3f, snapToStep(-3.1f, 0.5f))
    }

    @Test
    fun `frequency slider uses a reversible log scale`() {
        assertEquals(0f, frequencyToSlider(20f), 0.0001f)
        assertEquals(1f, frequencyToSlider(20_000f), 0.0001f)
        assertEquals(1_000f, sliderToFrequency(frequencyToSlider(1_000f)), 10f)
        assertEquals(20f, sliderToFrequency(-1f))
    }

    @Test
    fun `graph geometry maps bands and gains both ways`() {
        val geometry = EqualizerGraphGeometry(width = 1_030f, height = 240f, left = 30f, top = 20f, bottom = 20f)
        assertEquals(80f, geometry.bandX(0), 0.01f)
        assertEquals(0, geometry.nearestBand(10f))
        assertEquals(9, geometry.nearestBand(2_000f))
        assertEquals(geometry.bandX(5), geometry.frequencyX(1_000f), 0.01f)
        assertEquals(120f, geometry.dbY(0f), 0.01f)
        assertEquals(20f, geometry.dbY(30f), 0.01f)
        assertEquals(6f, geometry.yDb(geometry.dbY(6f)), 0.01f)
        assertEquals(0f, geometry.yDb(121f), 0.01f)
    }

    @Test
    fun `enabling per device sound seeds the current device from the shared sound`() {
        val shared = AudioEffectsProfile(enabled = true, presetId = "rock", sound = AudioEffectsSound(bassDb = 3f))
        val settings = AudioEffectsSettings(main = shared)

        val enabled = settings.withPerOutput(true, AudioOutputRoute.BLUETOOTH)
        assertTrue(enabled.perOutputEnabled)
        assertEquals(shared, enabled.outputProfiles["bluetooth"])

        val reenabled = enabled.copy(outputProfiles = mapOf("bluetooth" to AudioEffectsProfile()))
            .withPerOutput(true, AudioOutputRoute.BLUETOOTH)
        assertEquals(AudioEffectsProfile(), reenabled.outputProfiles["bluetooth"])
        assertFalse(enabled.withPerOutput(false, AudioOutputRoute.BLUETOOTH).perOutputEnabled)
    }

    @Test
    fun `route and preset tab labels cover every option`() {
        AudioOutputRoute.entries.forEach { assertTrue(it.labelRes() != 0) }
        AudioEffectsPresetTabs.forEach { assertTrue(it.tabLabelRes() != 0) }
        assertEquals("user_42", newUserPresetId(42L))
    }
}
