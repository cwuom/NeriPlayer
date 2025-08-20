package moe.ouom.neriplayer.util

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * A ProxySelector that can be toggled at runtime to bypass the system proxy.
 * When [bypassProxy] is true, all requests use Proxy.NO_PROXY.
 * When false, it delegates to the system default ProxySelector.
 */
object DynamicProxySelector : ProxySelector() {
    @Volatile
    var bypassProxy: Boolean = true

    private val systemDefault: ProxySelector? = ProxySelector.getDefault()

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(Proxy.NO_PROXY)
        return if (bypassProxy) listOf(Proxy.NO_PROXY)
        else systemDefault?.select(uri).takeUnless { it.isNullOrEmpty() } ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        systemDefault?.connectFailed(uri, sa, ioe)
    }
}


