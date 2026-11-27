package moe.ouom.neriplayer.core.player.engine.datasource

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlaybackRouteDiagnosticsTest {
    @Test
    fun `playback logs the connected route without exposing the media URL or proxy address`() {
        assertLoggedRoutes("m801.music.126.net")
    }

    @Test
    fun `automatic Bilibili fallback logs the connected CDN route`() {
        assertLoggedRoutes("upos-sz-mirrorcos.bilivideo.com")
        assertLoggedRoutes("upos-hz-mirrorakam.akamaized.net")
    }

    private fun assertLoggedRoutes(host: String) {
        MockWebServer().use { server ->
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            server.start(loopback, 0)
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, server.port))
            for (route in listOf(Proxy.NO_PROXY, proxy)) {
                server.enqueue(MockResponse.Builder().code(206).body("audio").build())
                val client = OkHttpClient.Builder()
                    .proxy(route)
                    .dns(object : Dns {
                        override fun lookup(hostname: String) = listOf(loopback)
                    })
                    .addNetworkInterceptor(PlaybackRouteDiagnostics)
                    .build()
                try {
                    val request = Request.Builder()
                        .url("http://$host:${server.port}/private?signed=private")
                        .build()
                    ShadowLog.clear()
                    client.newCall(request).execute().use { assertEquals("audio", it.body.string()) }
                    val messages = ShadowLog.getLogs().map { it.msg }.filter { it.contains("proxyType=") }
                    assertEquals(1, messages.size)
                    val message = messages.single()
                    assertTrue(message, message.contains("proxyType=${route.type()}"))
                    assertTrue(message, message.contains("responseCode=206"))
                    assertTrue(message, message.contains("responseHeadersMs="))
                    assertFalse(message, message.contains("private") || message.contains("127.0.0.1"))
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }
}
