package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.PlayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerManagerNeteaseCacheKeyTest {

    @Test
    fun fallbackNamespaceDoesNotReuseLegacyNeteaseCache() {
        val legacyKey = PlayerManager.buildNeteasePlaybackCacheKey(
            songId = 123L,
            preferredQuality = "exhigh",
            useFallbackNamespace = false
        )
        val fallbackKey = PlayerManager.buildNeteasePlaybackCacheKey(
            songId = 123L,
            preferredQuality = "exhigh",
            useFallbackNamespace = true
        )

        assertEquals("netease-123-exhigh", legacyKey)
        assertEquals("netease-123-exhigh-fallback-v1", fallbackKey)
        assertNotEquals(legacyKey, fallbackKey)
    }

    @Test
    fun blankQualityUsesStableDefaultInBothNamespaces() {
        assertEquals(
            "netease-123-exhigh",
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "   ",
                useFallbackNamespace = false
            )
        )
        assertEquals(
            "netease-123-exhigh-fallback-v1",
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "   ",
                useFallbackNamespace = true
            )
        )
    }

    @Test
    fun previewCacheKeyDoesNotReusePlaybackCacheNamespaces() {
        val previewKey = PlayerManager.buildNeteasePreviewCacheKey(
            songId = 123L,
            preferredQuality = "exhigh"
        )

        assertEquals("netease-preview-v1-123-exhigh", previewKey)
        assertNotEquals(
            previewKey,
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "exhigh",
                useFallbackNamespace = false
            )
        )
        assertNotEquals(
            previewKey,
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "exhigh",
                useFallbackNamespace = true
            )
        )
    }
}
