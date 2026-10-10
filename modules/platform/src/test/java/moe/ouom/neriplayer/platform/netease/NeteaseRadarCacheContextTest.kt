package moe.ouom.neriplayer.platform.netease

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseRadarCacheContextTest {

    @Test
    fun `radar cache context is public without a login cookie`() {
        assertEquals("public-v1", neteaseRadarCacheContext(emptyMap()))
        assertEquals("public-v1", neteaseRadarCacheContext(mapOf("MUSIC_U" to "   ", "__csrf" to "csrf")))
    }

    @Test
    fun `radar cache context fingerprints the trimmed login cookie`() {
        val expected = "account-sha256-v1:8c71d3460c78c6cbdf0d058e3b3783aa8d596157f1796fc46fe8a52d2943995c"

        assertEquals(expected, neteaseRadarCacheContext(mapOf("MUSIC_U" to "token-1")))
        assertEquals(expected, neteaseRadarCacheContext(mapOf("MUSIC_U" to " token-1 ")))
    }

    @Test
    fun `radar playlist cache key appends the context only when present`() {
        assertEquals("42", neteaseRadarPlaylistCacheKey(42L, radarCacheContext = null))
        assertEquals("42", neteaseRadarPlaylistCacheKey(42L, radarCacheContext = "  "))
        assertEquals("radar-v1/42/public-v1", neteaseRadarPlaylistCacheKey(42L, radarCacheContext = " public-v1 "))
    }
}
