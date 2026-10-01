package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.biliQualityLabelRes
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.neteaseQualityLabelRes
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.youtubeQualityLabelRes
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsQualityPresentationTest {
    @Test
    fun `netease quality labels cover every supported tier and retain unknown values`() {
        mapOf(
            "standard" to CoreCommonR.string.settings_audio_quality_standard,
            "higher" to CoreCommonR.string.settings_audio_quality_higher,
            "exhigh" to CoreCommonR.string.settings_audio_quality_exhigh,
            "lossless" to CoreCommonR.string.settings_audio_quality_lossless,
            "hires" to CoreCommonR.string.quality_hires,
            "jyeffect" to CoreCommonR.string.settings_audio_quality_jyeffect,
            "sky" to CoreCommonR.string.settings_audio_quality_sky,
            "jymaster" to CoreCommonR.string.settings_audio_quality_jymaster
        ).forEach { (quality, label) ->
            assertEquals(label, neteaseQualityLabelRes(quality))
        }
        assertEquals(null, neteaseQualityLabelRes("future-tier"))
    }

    @Test
    fun `youtube quality labels cover every supported tier and retain unknown values`() {
        mapOf(
            "low" to CoreCommonR.string.settings_audio_quality_standard,
            "medium" to CoreCommonR.string.settings_audio_quality_medium,
            "high" to CoreCommonR.string.settings_audio_quality_high,
            "very_high" to CoreCommonR.string.quality_very_high
        ).forEach { (quality, label) ->
            assertEquals(label, youtubeQualityLabelRes(quality))
        }
        assertEquals(null, youtubeQualityLabelRes("future-tier"))
    }

    @Test
    fun `bili quality labels cover every supported tier and retain unknown values`() {
        mapOf(
            "dolby" to CoreCommonR.string.settings_audio_quality_dolby,
            "hires" to CoreCommonR.string.quality_hires,
            "lossless" to CoreCommonR.string.settings_audio_quality_lossless,
            "high" to CoreCommonR.string.settings_audio_quality_high,
            "medium" to CoreCommonR.string.settings_audio_quality_medium,
            "low" to CoreCommonR.string.settings_audio_quality_low
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
