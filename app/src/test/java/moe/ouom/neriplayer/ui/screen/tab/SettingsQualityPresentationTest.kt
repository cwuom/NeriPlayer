package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsQualityPresentationTest {
    @Test
    fun `netease quality labels cover every supported tier and retain unknown values`() {
        mapOf(
            "standard" to R.string.settings_audio_quality_standard,
            "higher" to R.string.settings_audio_quality_higher,
            "exhigh" to R.string.settings_audio_quality_exhigh,
            "lossless" to R.string.settings_audio_quality_lossless,
            "hires" to R.string.quality_hires,
            "jyeffect" to R.string.settings_audio_quality_jyeffect,
            "sky" to R.string.settings_audio_quality_sky,
            "jymaster" to R.string.settings_audio_quality_jymaster
        ).forEach { (quality, label) ->
            assertEquals(label, neteaseQualityLabelRes(quality))
        }
        assertEquals(null, neteaseQualityLabelRes("future-tier"))
    }

    @Test
    fun `youtube quality labels cover every supported tier and retain unknown values`() {
        mapOf(
            "low" to R.string.settings_audio_quality_standard,
            "medium" to R.string.settings_audio_quality_medium,
            "high" to R.string.settings_audio_quality_high,
            "very_high" to R.string.quality_very_high
        ).forEach { (quality, label) ->
            assertEquals(label, youtubeQualityLabelRes(quality))
        }
        assertEquals(null, youtubeQualityLabelRes("future-tier"))
    }

    @Test
    fun `bili quality labels cover every supported tier and retain unknown values`() {
        mapOf(
            "dolby" to R.string.settings_audio_quality_dolby,
            "hires" to R.string.quality_hires,
            "lossless" to R.string.settings_audio_quality_lossless,
            "high" to R.string.settings_audio_quality_high,
            "medium" to R.string.settings_audio_quality_medium,
            "low" to R.string.settings_audio_quality_low
        ).forEach { (quality, label) ->
            assertEquals(label, biliQualityLabelRes(quality))
        }
        assertEquals(null, biliQualityLabelRes("future-tier"))
        listOf("dolby", "hires", "lossless", "high", "medium", "low").forEach { quality ->
            val sameHashUnknown = quality.dropLast(2) +
                (quality[quality.lastIndex - 1] + 1) +
                (quality.last() - 31)
            assertEquals(null, biliQualityLabelRes(sameHashUnknown))
        }
    }
}
