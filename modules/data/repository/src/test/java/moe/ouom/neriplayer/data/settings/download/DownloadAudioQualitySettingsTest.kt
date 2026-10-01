package moe.ouom.neriplayer.data.settings.download

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DownloadAudioQualitySettingsTest {

    @Test
    fun `saved Netease qualities retain all supported historical values`() {
        listOf("standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster")
            .forEach { quality ->
                assertEquals(quality, normalizeDownloadNeteaseAudioQuality(quality))
                assertEquals(
                    quality,
                    normalizeDownloadNeteaseAudioQuality("  ${quality.uppercase(Locale.ROOT)}  ")
                )
            }
    }

    @Test
    fun `saved YouTube qualities retain all supported historical values`() {
        listOf("low", "medium", "high", "very_high").forEach { quality ->
            assertEquals(quality, normalizeDownloadYouTubeAudioQuality(quality))
            assertEquals(
                quality,
                normalizeDownloadYouTubeAudioQuality("  ${quality.uppercase(Locale.ROOT)}  ")
            )
        }
    }

    @Test
    fun `saved Bilibili qualities retain all supported historical values`() {
        listOf("low", "medium", "high", "lossless", "hires", "dolby").forEach { quality ->
            assertEquals(quality, normalizeDownloadBiliAudioQuality(quality))
            assertEquals(
                quality,
                normalizeDownloadBiliAudioQuality("  ${quality.uppercase(Locale.ROOT)}  ")
            )
        }
    }

    @Test
    fun `missing blank and unknown qualities use each platform default`() {
        listOf(null, "", "  ", "future_quality", "very-high").forEach { value ->
            assertEquals(DEFAULT_DOWNLOAD_NETEASE_AUDIO_QUALITY, normalizeDownloadNeteaseAudioQuality(value))
            assertEquals(DEFAULT_DOWNLOAD_YOUTUBE_AUDIO_QUALITY, normalizeDownloadYouTubeAudioQuality(value))
            assertEquals(DEFAULT_DOWNLOAD_BILI_AUDIO_QUALITY, normalizeDownloadBiliAudioQuality(value))
        }
    }

    @Test
    fun `following playback quality ignores independent download values`() {
        val selection = resolveDownloadAudioQualitySelection(
            followsPlaybackQuality = true,
            playbackNeteaseQuality = "lossless",
            playbackYouTubeQuality = "very_high",
            playbackBiliQuality = "dolby",
            downloadNeteaseQuality = "standard",
            downloadYouTubeQuality = "low",
            downloadBiliQuality = "low"
        )

        assertEquals("lossless", selection.neteaseQuality)
        assertEquals("very_high", selection.youtubeQuality)
        assertEquals("dolby", selection.biliQuality)
    }

    @Test
    fun `independent download quality ignores playback values`() {
        val selection = resolveDownloadAudioQualitySelection(
            followsPlaybackQuality = false,
            playbackNeteaseQuality = "standard",
            playbackYouTubeQuality = "low",
            playbackBiliQuality = "low",
            downloadNeteaseQuality = "hires",
            downloadYouTubeQuality = "high",
            downloadBiliQuality = "lossless"
        )

        assertEquals("hires", selection.neteaseQuality)
        assertEquals("high", selection.youtubeQuality)
        assertEquals("lossless", selection.biliQuality)
    }

    @Test
    fun `invalid saved values fall back per platform`() {
        val selection = resolveDownloadAudioQualitySelection(
            followsPlaybackQuality = false,
            playbackNeteaseQuality = "ignored",
            playbackYouTubeQuality = "ignored",
            playbackBiliQuality = "ignored",
            downloadNeteaseQuality = "unexpected",
            downloadYouTubeQuality = "  ",
            downloadBiliQuality = null
        )

        assertEquals(DEFAULT_DOWNLOAD_NETEASE_AUDIO_QUALITY, selection.neteaseQuality)
        assertEquals(DEFAULT_DOWNLOAD_YOUTUBE_AUDIO_QUALITY, selection.youtubeQuality)
        assertEquals(DEFAULT_DOWNLOAD_BILI_AUDIO_QUALITY, selection.biliQuality)
    }
}
