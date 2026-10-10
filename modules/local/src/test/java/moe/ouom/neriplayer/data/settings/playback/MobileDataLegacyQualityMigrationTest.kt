package moe.ouom.neriplayer.data.settings.playback

import moe.ouom.neriplayer.data.model.settings.playback.DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY
import moe.ouom.neriplayer.data.model.settings.playback.DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY
import moe.ouom.neriplayer.data.model.settings.playback.DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileDataLegacyQualityMigrationTest {

    @Test
    fun `legacy preset keeps known levels and falls back for anything else`() {
        assertEquals("low", resolveLegacyMobileDataQualityPreset(" LOW ", "high"))
        assertEquals("medium", resolveLegacyMobileDataQualityPreset("Medium", "high"))
        assertEquals("high", resolveLegacyMobileDataQualityPreset("high", "low"))
        assertEquals("low", resolveLegacyMobileDataQualityPreset("off", "low"))
        assertEquals("medium", resolveLegacyMobileDataQualityPreset(null, "medium"))
    }

    @Test
    fun `legacy off switch follows the default quality while explicit levels do not`() {
        assertEquals(true, resolveLegacyMobileDataFollowDefaultAudioQuality(" Off"))
        assertEquals(false, resolveLegacyMobileDataFollowDefaultAudioQuality("low"))
        assertEquals(false, resolveLegacyMobileDataFollowDefaultAudioQuality("MEDIUM"))
        assertEquals(false, resolveLegacyMobileDataFollowDefaultAudioQuality("high "))
        assertNull(resolveLegacyMobileDataFollowDefaultAudioQuality("lossless"))
        assertNull(resolveLegacyMobileDataFollowDefaultAudioQuality(null))
    }

    @Test
    fun `legacy levels map onto netease mobile data qualities`() {
        assertEquals(
            DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY,
            resolveLegacyMobileDataNeteaseAudioQuality("low")
        )
        assertEquals("higher", resolveLegacyMobileDataNeteaseAudioQuality(" medium "))
        assertEquals("exhigh", resolveLegacyMobileDataNeteaseAudioQuality("HIGH"))
        assertNull(resolveLegacyMobileDataNeteaseAudioQuality("off"))
        assertNull(resolveLegacyMobileDataNeteaseAudioQuality(null))
    }

    @Test
    fun `legacy levels map onto youtube mobile data qualities`() {
        assertEquals(
            DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY,
            resolveLegacyMobileDataYouTubeAudioQuality("Low")
        )
        assertEquals("medium", resolveLegacyMobileDataYouTubeAudioQuality("medium"))
        assertEquals("high", resolveLegacyMobileDataYouTubeAudioQuality(" high"))
        assertNull(resolveLegacyMobileDataYouTubeAudioQuality("very_high"))
        assertNull(resolveLegacyMobileDataYouTubeAudioQuality(null))
    }

    @Test
    fun `legacy levels map onto bilibili mobile data qualities`() {
        assertEquals(
            DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY,
            resolveLegacyMobileDataBiliAudioQuality("LOW")
        )
        assertEquals("medium", resolveLegacyMobileDataBiliAudioQuality("Medium"))
        assertEquals("high", resolveLegacyMobileDataBiliAudioQuality("high"))
        assertNull(resolveLegacyMobileDataBiliAudioQuality("dolby"))
        assertNull(resolveLegacyMobileDataBiliAudioQuality(null))
    }

    @Test
    fun `mobile data quality normalizers reject unsupported values`() {
        assertEquals("lossless", normalizeMobileDataNeteaseAudioQuality(" Lossless "))
        assertEquals(DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY, normalizeMobileDataNeteaseAudioQuality("dolby"))
        assertEquals("very_high", normalizeMobileDataYouTubeAudioQuality("VERY_HIGH"))
        assertEquals(DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY, normalizeMobileDataYouTubeAudioQuality(null))
        assertEquals("dolby", normalizeMobileDataBiliAudioQuality("dolby"))
        assertEquals(DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY, normalizeMobileDataBiliAudioQuality("exhigh"))
    }
}
