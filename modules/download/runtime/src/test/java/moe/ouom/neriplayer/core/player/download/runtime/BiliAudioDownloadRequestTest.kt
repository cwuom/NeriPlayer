package moe.ouom.neriplayer.core.player.download.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliAudioDownloadRequestTest {
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
}
