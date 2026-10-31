package moe.ouom.neriplayer.core.player.download.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BiliAudioDownloadRequestTest {
    @Test
    fun `trusted http and https streams keep the public playback headers`() {
        for (scheme in listOf("http", "https")) {
            for (host in listOf("cdn.bilivideo.com", "cdn.bilivideo.cn", "b-demo.edge.mountaintoys.cn")) {
                val url = "$scheme://$host/audio.m4a"
                val request = buildBiliAudioDownloadRequest(url, mapOf("SESSDATA" to "synthetic-session"))

                assertEquals(url, "https://www.bilibili.com", request.header("Referer"))
                assertEquals(
                    url,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    request.header("User-Agent")
                )
                if (scheme == "http") assertNull(url, request.header("Cookie"))
            }
        }
    }

    @Test
    fun `only trusted encrypted stream requests receive account cookies`() {
        val cookies = mapOf("SESSDATA" to "synthetic-session")
        for (url in listOf(
            "https://bilivideo.com.attacker.example/audio.m4a",
            "https://notbilivideo.com/audio.m4a",
            "https://library.example/audio.m4a",
            "http://cdn.bilivideo.com/audio.m4a"
        )) {
            val request = buildBiliAudioDownloadRequest(url, cookies)
            assertNull(url, request.header("Cookie"))
        }
        for (url in listOf(
            "https://cdn.bilivideo.com/audio.m4a",
            "https://cdn.bilivideo.cn/audio.m4a",
            "https://b-demo.edge.mountaintoys.cn/audio.m4a"
        )) {
            val request = buildBiliAudioDownloadRequest(url, cookies)
            assertEquals(url, "SESSDATA=synthetic-session", request.header("Cookie"))
            assertEquals("https://www.bilibili.com", request.header("Referer"))
        }
    }

    @Test
    fun `anonymous downloads do not add an empty cookie header`() {
        assertNull(buildBiliAudioDownloadRequest("https://cdn.bilivideo.com/audio.m4a", emptyMap()).header("Cookie"))
    }

    @Test
    fun `non http stream schemes are rejected before building a request`() {
        for (url in listOf("ftp://cdn.bilivideo.com/audio.m4a", "custom://cdn.bilivideo.com/audio.m4a")) {
            assertThrows(url, IllegalArgumentException::class.java) {
                buildBiliAudioDownloadRequest(url, mapOf("SESSDATA" to "synthetic-session"))
            }
        }
    }
}
