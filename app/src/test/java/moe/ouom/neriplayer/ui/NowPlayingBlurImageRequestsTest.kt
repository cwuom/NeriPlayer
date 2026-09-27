package moe.ouom.neriplayer.ui

import coil.request.CachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingBlurImageRequestsTest {
    @Test
    fun `missing blur strength uses no transformation strength`() {
        assertEquals(0f, normalizedNowPlayingBlurStrength(null))
        assertEquals(4f, normalizedNowPlayingBlurStrength(4f))
    }

    @Test
    fun `visible and prefetched blur requests share cache identity`() {
        assertEquals(
            "nowplaying-blur:https://example.com/cover:4.0:revision",
            nowPlayingBlurImageCacheKey("https://example.com/cover", 4f, "revision")
        )
        assertEquals(
            "nowplaying-blur:stable:null:revision",
            nowPlayingBlurImageCacheKey("stable", null, "revision")
        )
    }

    @Test
    fun `offline policy preserves local cache and blocks remote fetches`() {
        assertEquals(
            CachePolicy.DISABLED,
            nowPlayingBlurNetworkCachePolicy(true, "https://example.com/cover")
        )
        assertEquals(
            CachePolicy.ENABLED,
            nowPlayingBlurNetworkCachePolicy(true, "content://local/cover")
        )
        assertEquals(
            CachePolicy.ENABLED,
            nowPlayingBlurNetworkCachePolicy(false, "https://example.com/cover")
        )
    }

    @Test
    fun `neighbor prefetch is silent while blur is off and keeps enabled order`() {
        val queued = mutableListOf<String>()
        preloadNowPlayingBlurCovers(false, listOf("prev", "next"), queued::add)
        assertTrue(queued.isEmpty())
        preloadNowPlayingBlurCovers(true, emptyList(), queued::add)
        assertTrue(queued.isEmpty())
        preloadNowPlayingBlurCovers(true, listOf("prev", "next"), queued::add)
        assertEquals(listOf("prev", "next"), queued)
    }
}
