package moe.ouom.neriplayer.network.proxy

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicProxySelectorTest {

    private val originalDefault: ProxySelector? = ProxySelector.getDefault()
    private val originalBypassProxy = DynamicProxySelector.bypassProxy
    private val uri = URI("https://music.163.com/api/song/enhance/player/url")
    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))

    @After
    fun restoreGlobalProxyState() {
        ProxySelector.setDefault(originalDefault)
        DynamicProxySelector.bypassProxy = originalBypassProxy
    }

    @Test
    fun `bypassing the proxy or a missing uri always connects directly`() {
        val system = RecordingSelector(mutableListOf(proxy))
        ProxySelector.setDefault(system)

        DynamicProxySelector.bypassProxy = false
        assertEquals(listOf(Proxy.NO_PROXY), DynamicProxySelector.select(null))
        DynamicProxySelector.bypassProxy = true
        assertEquals(listOf(Proxy.NO_PROXY), DynamicProxySelector.select(uri))
        assertTrue(system.selected.isEmpty())
    }

    @Test
    fun `system proxies are used when bypass is off`() {
        val system = RecordingSelector(mutableListOf(proxy))
        ProxySelector.setDefault(system)
        DynamicProxySelector.bypassProxy = false

        assertEquals(listOf(proxy), DynamicProxySelector.select(uri))
        assertEquals(listOf(uri), system.selected)
    }

    @Test
    fun `empty, missing or self referencing system selectors fall back to direct`() {
        DynamicProxySelector.bypassProxy = false

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
