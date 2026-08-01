package moe.ouom.neriplayer.util.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverArtColorCacheKeyTest {
    @Test
    fun `netease cover variants share a cache key`() {
        assertEquals(
            normalizeCoverArtColorCacheKey(
                "http://p1.music.126.net/cover.jpg?param=140y140"
            ),
            normalizeCoverArtColorCacheKey(
                "https://p2.music.126.net/cover.jpg?param=500y500"
            )
        )
    }

    @Test
    fun `non netease image query remains part of the cache key`() {
        assertEquals(
            "https://example.com/cover.jpg?token=one",
            normalizeCoverArtColorCacheKey("https://example.com/cover.jpg?token=one")
        )
    }
}
