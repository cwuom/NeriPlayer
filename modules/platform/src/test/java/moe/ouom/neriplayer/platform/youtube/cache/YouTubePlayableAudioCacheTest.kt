package moe.ouom.neriplayer.platform.youtube.cache

import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableAudio
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class YouTubePlayableAudioCacheTest {
    private val cache = YouTubePlayableAudioCache()

    @Test
    fun `expired stream never returns after insertion`() {
        val expired = audio("https://rr1---sn.googlevideo.com/videoplayback?expire=1&c=VISIONOS")
        cache.put("video", "automatic|high", expired)

        assertNull(cache.get("video", "automatic|high"))
    }

    @Test
    fun `cached quality is checked against current preference`() {
        val tooLow = audio(bitrateKbps = 96)
        cache.put("video", "automatic|high", tooLow)

        assertNull(cache.get("video", "automatic|high"))
        cache.put("video", "automatic|high_m4a", tooLow.copy(mimeType = "audio/mp4"))
        assertEquals("audio/mp4", cache.get("video", "automatic|high_m4a")?.mimeType)
    }

    @Test
    fun `direct and strict recovery flags filter without evicting candidate`() {
        val unverifiedDirect = audio("https://rr1---sn.googlevideo.com/videoplayback?source=youtube&c=WEB_REMIX")
        cache.put("video", "automatic|high", unverifiedDirect)

        assertNull(cache.get("video", "automatic|high", avoidDirect = true))
        assertNull(cache.get("video", "automatic|high", allowUnverifiedDirectFallback = false))
        assertSame(unverifiedDirect, cache.get("video", "automatic|high"))

        val verifiedDirect = unverifiedDirect.copy(url = "${unverifiedDirect.url}&pot=token")
        cache.put("video", "automatic|high", verifiedDirect)
        assertSame(verifiedDirect, cache.get("video", "automatic|high", allowUnverifiedDirectFallback = false))
    }

    @Test
    fun `HLS remains available when direct is avoided`() {
        val hls = audio("https://manifest.googlevideo.com/playlist.m3u8")
            .copy(streamType = YouTubePlayableStreamType.HLS)
        cache.put("video", "automatic|high", hls)

        assertNull(cache.get("video", "automatic|high", requireDirect = true))
        assertSame(hls, cache.get("video", "automatic|high", avoidDirect = true))
        cache.clear()
        assertNull(cache.get("video", "automatic|high"))
    }

    @Test
    fun `oldest entry is evicted after bounded cache fills`() {
        repeat(65) { index -> cache.put("video-$index", "automatic|high", audio()) }

        assertNull(cache.get("video-0", "automatic|high"))
        assertEquals(160, cache.get("video-64", "automatic|high")?.bitrateKbps)
    }

    private fun audio(
        url: String = "https://rr1---sn.googlevideo.com/videoplayback?c=VISIONOS",
        bitrateKbps: Int = 160
    ) = YouTubePlayableAudio(url = url, mimeType = "audio/webm", bitrateKbps = bitrateKbps)
}
