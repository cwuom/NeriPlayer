package moe.ouom.neriplayer.core.player.quality

import moe.ouom.neriplayer.core.player.PlayerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerManagerTrafficQualityExtensionsTest {

    @Test
    fun `effectiveYouTubeQuality falls back before player manager initialization`() {
        val previousQuality = PlayerManager.youtubePreferredQuality
        PlayerManager.youtubePreferredQuality = "very_high"
        try {
            assertEquals("very_high", PlayerManager.effectiveYouTubeQuality())
        } finally {
            PlayerManager.youtubePreferredQuality = previousQuality
        }
    }
}
