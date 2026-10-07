package moe.ouom.neriplayer.data.model.youtube.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeMusicPlaybackQualityTest {

    @Test
    fun `quality settings map aliases ignoring case`() {
        assertEquals(YouTubeMusicPlaybackQuality.LOW, YouTubeMusicPlaybackQuality.fromSetting("low"))
        assertEquals(YouTubeMusicPlaybackQuality.LOW, YouTubeMusicPlaybackQuality.fromSetting("Standard"))
        assertEquals(YouTubeMusicPlaybackQuality.MEDIUM, YouTubeMusicPlaybackQuality.fromSetting("MEDIUM"))
        assertEquals(YouTubeMusicPlaybackQuality.HIGH, YouTubeMusicPlaybackQuality.fromSetting("high"))
        assertEquals(YouTubeMusicPlaybackQuality.HIGH, YouTubeMusicPlaybackQuality.fromSetting("higher"))
        listOf("very_high", "very-high", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster").forEach { key ->
            assertEquals(key, YouTubeMusicPlaybackQuality.VERY_HIGH, YouTubeMusicPlaybackQuality.fromSetting(key))
        }
    }

    @Test
    fun `missing or unknown settings use the highest quality`() {
        assertEquals(YouTubeMusicPlaybackQuality.VERY_HIGH, YouTubeMusicPlaybackQuality.fromSetting(null))
        assertEquals(YouTubeMusicPlaybackQuality.VERY_HIGH, YouTubeMusicPlaybackQuality.fromSetting("ultra"))
    }
}
