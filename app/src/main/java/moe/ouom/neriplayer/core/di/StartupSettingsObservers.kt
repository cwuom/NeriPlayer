package moe.ouom.neriplayer.core.di

import moe.ouom.neriplayer.data.model.settings.bootstrap.BootstrapSettingsSnapshot
import java.net.Proxy
import moe.ouom.neriplayer.network.proxy.DynamicProxySelector
import moe.ouom.neriplayer.network.proxy.NetworkDns
import moe.ouom.neriplayer.network.proxy.NetworkProxyEndpoint
import moe.ouom.neriplayer.network.proxy.parseDohEndpoint

internal data class NetworkRoute(val bypassProxy: Boolean, val proxyEndpoint: String, val dohEndpoint: String) {
    fun install() {
        DynamicProxySelector.overrideProxy = NetworkProxyEndpoint.parse(proxyEndpoint)?.toProxy()
            ?: if (bypassProxy) Proxy.NO_PROXY else null
        NetworkDns.configure(parseDohEndpoint(dohEndpoint))
    }
}

internal fun BootstrapSettingsSnapshot.networkRoute() = NetworkRoute(bypassProxy, proxyEndpoint, dohEndpoint)

internal fun applyNetworkRouteChange(
    next: NetworkRoute,
    currentValue: NetworkRoute,
    applyValue: (NetworkRoute) -> Unit,
    onChanged: () -> Unit
) {
    if (next == currentValue) return
    applyValue(next)
    onChanged()
}
