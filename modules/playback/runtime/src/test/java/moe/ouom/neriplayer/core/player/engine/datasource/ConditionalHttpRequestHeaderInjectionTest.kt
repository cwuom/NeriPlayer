@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.traffic.TrafficUsageSource
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.traffic.TrafficStatsRepository
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ConditionalHttpRequestHeaderInjectionTest {

    private val cookieRepo = mock(BiliCookieRepository::class.java).also {
        `when`(it.cookieFlow).thenReturn(MutableStateFlow(emptyMap()))
    }
    private val youtubeAuthRepo = mock(YouTubeAuthRepository::class.java).also {
        `when`(it.authFlow).thenReturn(MutableStateFlow(YouTubeAuthBundle()))
    }
    private val server = ScriptedHttpServer { spec ->
        ScriptedHttpResponse(
            code = 206,
            headers = mapOf(
                "Content-Range" to listOf("bytes ${spec.position}-${spec.position + 3}/4")
            ),
            body = ByteArray(4) { it.toByte() }
        )
    }
    private val factories = mutableListOf<ConditionalHttpDataSourceFactory>()

    @After
    fun closeFactories() {
        factories.forEach(ConditionalHttpDataSourceFactory::close)
    }

    @Test
    fun `bilibili stream hosts get the web referer and user agent`() {
        val sent = headersSentFor(
            mockHttpUri("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/42/audio.m4s?e=1"),
            linkedMapOf("Accept" to "*/*", "Referer" to "https://example.com")
        )

        assertEquals(
            mapOf(
                "Accept" to "*/*",
                "Referer" to "https://www.bilibili.com",
                "User-Agent" to BILI_WEB_USER_AGENT
            ),
            sent
        )
    }

    @Test
    fun `bilibili stream urls are recognised even when the uri has no parsed host`() {
        val hostless = mock(Uri::class.java).also {
            `when`(it.scheme).thenReturn("https")
            `when`(it.toString()).thenReturn("https://cn-hk-eq-01-13.bilivideo.com/live/1.flv")
        }

        val sent = headersSentFor(hostless, emptyMap())

        assertEquals(
            mapOf("Referer" to "https://www.bilibili.com", "User-Agent" to BILI_WEB_USER_AGENT),
            sent
        )
    }

    @Test
    fun `googlevideo playback drops credentials and explicit ranges but keeps the caller origin`() {
        val sent = headersSentFor(
            mockHttpUri("https://rr1---sn-abc.googlevideo.com/videoplayback?source=youtube&itag=251"),
            linkedMapOf(
                "Range" to "bytes=0-99",
                "Cookie" to "SID=secret",
                "Referer" to "https://www.youtube.com/",
                "User-Agent" to "NeriTest/1.0",
                "X-Trace" to "1"
            )
        )

        assertEquals(
            mapOf(
                "User-Agent" to "NeriTest/1.0",
                "X-Trace" to "1",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            ),
            sent
        )
    }

    @Test
    fun `googlevideo playback without a usable referer falls back to youtube music`() {
        val withoutReferer = mapOf("User-Agent" to "NeriTest/1.0")
        val blankReferer = mapOf("User-Agent" to "NeriTest/1.0", "Referer" to "/")

        for (original in listOf(withoutReferer, blankReferer)) {
            val sent = headersSentFor(
                mockHttpUri("https://rr1---sn-abc.googlevideo.com/videoplayback?source=youtube"),
                original
            )

            assertEquals(
                mapOf(
                    "User-Agent" to "NeriTest/1.0",
                    "Origin" to "https://music.youtube.com",
                    "Referer" to "https://music.youtube.com/"
                ),
                sent
            )
        }
    }

    @Test
    fun `other hosts are forwarded with the caller headers only`() {
        val original = mapOf("Accept" to "audio/*", "Referer" to "https://example.com/")

        assertEquals(original, headersSentFor(mockHttpUri("https://cdn.example.com/a.mp3"), original))
        assertEquals(
            original,
            headersSentFor(mockHttpUri("https://redirector.googlevideo.com/generate_204"), original)
        )
    }

    @Test
    fun `traffic aware factory reports the bytes of injected requests`() {
        val traffic = mock(TrafficStatsRepository::class.java).also {
            `when`(it.currentNetworkType()).thenReturn(TrafficNetworkType.WIFI)
        }
        val source = factory(traffic).createDataSource()

        source.open(httpGet(mockHttpUri("https://upos-sz-mirrorcos.bilivideo.com/a.m4s")))
        assertEquals(listOf<Byte>(0, 1, 2, 3), source.readToEnd().toList())
        source.close()

        verify(traffic).recordNetworkBytes(TrafficNetworkType.WIFI, 4L, TrafficUsageSource.PLAYBACK)
        assertEquals(
            "https://www.bilibili.com",
            server.requests.single().httpRequestHeaders["Referer"]
        )
    }

    private fun factory(traffic: TrafficStatsRepository? = null): ConditionalHttpDataSourceFactory {
        return ConditionalHttpDataSourceFactory(server, cookieRepo, youtubeAuthRepo, traffic)
            .also(factories::add)
    }

    private fun headersSentFor(uri: Uri, headers: Map<String, String>): Map<String, String> {
        val source = factory().createDataSource()
        source.open(httpGet(uri, headers = headers))
        source.close()
        return server.requests.last().httpRequestHeaders
    }

    private companion object {
        const val BILI_WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    }
}
