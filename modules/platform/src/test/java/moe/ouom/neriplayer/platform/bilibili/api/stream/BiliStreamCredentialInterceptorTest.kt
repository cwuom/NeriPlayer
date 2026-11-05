package moe.ouom.neriplayer.platform.bilibili.api.stream

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliStreamCredentialInterceptorTest {
    @Test
    fun `each redirect destination must remain trusted and encrypted`() {
        val origin = "https://cdn.bilivideo.com/audio"
        for (target in listOf(
            "https://bilivideo.com.attacker.example/audio",
            "https://external.example/audio",
            "http://cdn.bilivideo.com/audio"
        )) {
            assertNull(target, outgoingCookie(origin, target))
        }
        assertEquals("synthetic-cookie", outgoingCookie(origin, "https://edge.bilivideo.cn/audio"))
        assertEquals("synthetic-cookie", outgoingCookie("https://music.example/audio", "https://music.example/audio"))
    }

    @Test
    fun `real http redirects never forward stream credentials`() {
        val seenCookies = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            seenCookies += exchange.requestHeaders.getFirst("Cookie")
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", "http://external.example:${server.address.port}/final")
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.sendResponseHeaders(200, 0)
            }
            exchange.close()
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns { listOf(InetAddress.getLoopbackAddress()) }
            .addNetworkInterceptor(BiliStreamCredentialInterceptor())
            .build()
        server.start()
        try {
            val request = Request.Builder()
                .url("http://cdn.bilivideo.com:${server.address.port}/redirect")
                .header("Cookie", "synthetic-cookie")
                .build()
            client.newCall(request).execute().use { response -> assertEquals(200, response.code) }
            assertEquals(listOf<String?>(null, null), seenCookies)
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun outgoingCookie(origin: String, destination: String): String? {
        val call = OkHttpClient().newCall(Request.Builder().url(origin).build())
        val request = Request.Builder().url(destination).header("Cookie", "synthetic-cookie").build()
        val chain = java.lang.reflect.Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "call" -> call
                "request" -> request
                "proceed" -> Response.Builder().request(arguments!![0] as Request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
                else -> error("Unexpected chain operation: ${method.name}")
            }
        } as Interceptor.Chain
        return BiliStreamCredentialInterceptor().intercept(chain).use { it.request.header("Cookie") }
    }
}
