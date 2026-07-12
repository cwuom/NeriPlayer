package moe.ouom.neriplayer.util

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

object DynamicProxySelector : ProxySelector() {
    var bypassProxy: Boolean
        get() = moe.ouom.neriplayer.util.network.DynamicProxySelector.bypassProxy
        set(value) {
            moe.ouom.neriplayer.util.network.DynamicProxySelector.bypassProxy = value
        }

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(Proxy.NO_PROXY)
        if (bypassProxy) return listOf(Proxy.NO_PROXY)
        return systemDefault()?.select(uri).takeUnless { it.isNullOrEmpty() }
            ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        systemDefault()?.connectFailed(uri, sa, ioe)
    }

    private fun systemDefault(): ProxySelector? {
        val current = getDefault()
        return if (
            current === this ||
            current === moe.ouom.neriplayer.util.network.DynamicProxySelector
        ) {
            null
        } else {
            current
        }
    }
}
