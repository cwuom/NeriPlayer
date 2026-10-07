package moe.ouom.neriplayer.data.model.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSoundModelsTest {
    private val range = DEFAULT_EQUALIZER_BAND_LEVEL_RANGE_MB

    private fun bandLevels(
        presetId: String,
        bandCentersHz: List<Int>,
        customBandLevelsMb: List<Int> = emptyList(),
        bandLevelRangeMb: IntRange = range
    ) = resolvePlaybackEqualizerBandLevelsMb(presetId, customBandLevelsMb, bandCentersHz, bandLevelRangeMb)

    @Test
    fun `preset bands outside the anchor range hold the edge anchors`() {
        assertEquals(
            listOf(700, 700, 500, -300, -300),
            bandLevels(PlaybackEqualizerPresetId.BASS_BOOST, listOf(30, 60, 230, 14_000, 20_000))
        )
    }

    @Test
    fun `preset bands between anchors interpolate on a log frequency scale`() {
        assertEquals(listOf(601, 351), bandLevels(PlaybackEqualizerPresetId.BASS_BOOST, listOf(117, 455)))
    }

    @Test
    fun `preset levels are clamped to the device band range`() {
        assertEquals(
            listOf(500, -200),
            bandLevels(PlaybackEqualizerPresetId.BASS_BOOST, listOf(60, 14_000), bandLevelRangeMb = -200..500)
        )
    }

    @Test
    fun `unknown presets fall back to flat and custom levels are clamped per band`() {
        assertEquals(listOf(0, 0), bandLevels("missing", listOf(60, 230), customBandLevelsMb = listOf(900, 900)))
        assertEquals(
            listOf(1_500, -100, 0),
            bandLevels(PlaybackEqualizerPresetId.CUSTOM, listOf(60, 230, 910), customBandLevelsMb = listOf(2_000, -100))
        )
        assertEquals(emptyList<Int>(), bandLevels(PlaybackEqualizerPresetId.CUSTOM, emptyList(), listOf(100)))
    }

    @Test
    fun `presets with malformed anchors stay flat`() {
        val preset = PlaybackEqualizerPreset("broken", "Broken", listOf(3f, 3f))

        assertEquals(listOf(0, 0), preset.resolveBandLevelsMb(listOf(60, 910), range))
        assertEquals(emptyList<Int>(), preset.resolveBandLevelsMb(emptyList(), range))
    }

    @Test
    fun `volume balance is rounded to hundredths and clamped`() {
        assertEquals(0f, normalizePlaybackVolumeBalance(Float.NaN), 0f)
        assertEquals(0f, normalizePlaybackVolumeBalance(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.46f, normalizePlaybackVolumeBalance(0.456f), 0f)
        assertEquals(1f, normalizePlaybackVolumeBalance(3f), 0f)
        assertEquals(-1f, normalizePlaybackVolumeBalance(-3f), 0f)
    }

    @Test
    fun `equalizer frequency labels switch to kilohertz with one decimal`() {
        assertEquals("60Hz", formatEqualizerFrequencyLabel(60))
        assertEquals("999Hz", formatEqualizerFrequencyLabel(999))
        assertEquals("1kHz", formatEqualizerFrequencyLabel(1_000))
        assertEquals("3.6kHz", formatEqualizerFrequencyLabel(3_600))
        assertEquals("14kHz", formatEqualizerFrequencyLabel(14_000))
    }

    @Test
    fun `band level text round trips and ignores malformed entries`() {
        assertNull(encodePlaybackEqualizerBandLevels(emptyList()))
        assertEquals("300,-150,0", encodePlaybackEqualizerBandLevels(listOf(300, -150, 0)))
        assertEquals(listOf(300, -150, 0), decodePlaybackEqualizerBandLevels("300,-150,0"))
        assertEquals(listOf(100, -200, 300), decodePlaybackEqualizerBandLevels(" 100, -200,abc,,300 "))
        assertEquals(emptyList<Int>(), decodePlaybackEqualizerBandLevels(null))
        assertEquals(emptyList<Int>(), decodePlaybackEqualizerBandLevels("  "))
    }
}
