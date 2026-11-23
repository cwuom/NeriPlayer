package moe.ouom.neriplayer.network.proxy

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicProxySelectorTest {

    private val originalDefault: ProxySelector? = ProxySelector.getDefault()
    private val originalOverride = DynamicProxySelector.overrideProxy
    private val uri = URI("https://music.163.com/api/song/enhance/player/url")
    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))

    @After
    fun restoreGlobalProxyState() {
        ProxySelector.setDefault(originalDefault)
        DynamicProxySelector.overrideProxy = originalOverride
    }

    @Test
    fun `explicit HTTP and SOCKS routes take priority over direct and system modes`() {
        val system = RecordingSelector(mutableListOf(proxy))
        ProxySelector.setDefault(system)
        for (value in listOf("http://proxy.local:8080", "socks5://[::1]:1080")) {
            val endpoint = checkNotNull(NetworkProxyEndpoint.parse(value))
            val route = endpoint.toProxy()
            assertEquals(value, endpoint.uri)
            DynamicProxySelector.overrideProxy = route
            assertEquals(listOf(route), DynamicProxySelector.select(uri))
            assertTrue(DynamicProxySelector.isProxyHost(endpoint.host))
        }
        assertTrue(system.selected.isEmpty())
    }

    @Test
    fun `music API and media requests reach the configured HTTP proxy`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val requests = mutableListOf<URI>()
        server.createContext("/") { exchange ->
            requests += exchange.requestURI
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val client = OkHttpClient.Builder().proxySelector(DynamicProxySelector).build()
        server.start()
        try {
            DynamicProxySelector.overrideProxy = Proxy(Proxy.Type.HTTP, server.address)
            val urls = listOf("http://api.test.invalid/browse", "http://stream.test.invalid/media")
            for (url in urls) {
                client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(204, it.code) }
            }
            assertEquals(urls.map(::URI), requests)
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `invalid ports, paths and credentials are not proxy endpoints`() {
        for (value in listOf("http://host", "http://host:0", "socks5://host:65536", "http://host:99/path",
            "http://user@proxy:80", "https://host:443", "http://host:99?q=x")) {
            assertEquals(value, null, NetworkProxyEndpoint.parse(value))
        }
    }

    @Test
    fun `bypassing the proxy or a missing uri always connects directly`() {
        val system = RecordingSelector(mutableListOf(proxy))
        ProxySelector.setDefault(system)

        DynamicProxySelector.overrideProxy = null
        assertEquals(listOf(Proxy.NO_PROXY), DynamicProxySelector.select(null))
        DynamicProxySelector.overrideProxy = Proxy.NO_PROXY
        assertEquals(listOf(Proxy.NO_PROXY), DynamicProxySelector.select(uri))
        assertTrue(system.selected.isEmpty())
    }

    @Test
    fun `system proxies are used when bypass is off`() {
        val system = RecordingSelector(mutableListOf(proxy))
        ProxySelector.setDefault(system)
        DynamicProxySelector.overrideProxy = null

        assertEquals(listOf(proxy), DynamicProxySelector.select(uri))
        assertEquals(listOf(uri), system.selected)
        assertTrue(DynamicProxySelector.isProxyHost("PROXY.EXAMPLE."))
        val encrypted = Dns { error("proxy names must not be sent to DoH") }
        assertSame(Dns.SYSTEM, resolverFor("PROXY.EXAMPLE.", Dns.SYSTEM, encrypted))
        assertSame(encrypted, resolverFor("music.youtube.com", Dns.SYSTEM, encrypted))
    }

    @Test
    fun `empty, missing or self referencing system selectors fall back to direct`() {
        DynamicProxySelector.overrideProxy = null

        ProxySelector.setDefault(RecordingSelector(mutableListOf()))
        assertEquals(listOf(Proxy.NO_PROXY), DynamicProxySelector.select(uri))
        ProxySelector.setDefault(RecordingSelector(null))
        assertEquals(listOf(Proxy.NO_PROXY), DynamicProxySelector.select(uri))
        ProxySelector.setDefault(DynamicProxySelector)
        assertEquals(listOf(Proxy.NO_PROXY), DynamicProxySelector.select(uri))
    }

    @Test
    fun `connection failures are reported to a distinct system selector only`() {
        val system = RecordingSelector(mutableListOf(proxy))
        val address = InetSocketAddress.createUnresolved("proxy.example", 8080)
        val failure = IOException("connection refused")
        ProxySelector.setDefault(system)
        DynamicProxySelector.overrideProxy = null

        DynamicProxySelector.connectFailed(uri, address, failure)
        ProxySelector.setDefault(DynamicProxySelector)
        DynamicProxySelector.connectFailed(uri, address, IOException("not forwarded"))

        assertEquals(listOf(Triple(uri, address, failure)), system.failures)
    }

    private class RecordingSelector(
        private val proxies: MutableList<Proxy>?
    ) : ProxySelector() {
        val selected = mutableListOf<URI?>()
        val failures = mutableListOf<Triple<URI?, SocketAddress?, IOException?>>()

        override fun select(uri: URI?): MutableList<Proxy>? {
            selected += uri
            return proxies
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            failures += Triple(uri, sa, ioe)
        }
    }
}
