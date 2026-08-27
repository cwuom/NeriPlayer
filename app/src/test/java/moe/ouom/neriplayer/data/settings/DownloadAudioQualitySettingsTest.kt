package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadAudioQualitySettingsTest {

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
