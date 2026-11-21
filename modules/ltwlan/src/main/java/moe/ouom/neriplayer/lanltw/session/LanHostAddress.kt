package moe.ouom.neriplayer.lanltw.session

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

private fun Iterable<InetAddress>.lanIpv4(): String? = firstOrNull {
    it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress
}?.hostAddress

private fun LinkProperties.ipv4() = linkAddresses.map { it.address }.lanIpv4()

internal suspend fun lanHostAddress(connectivity: ConnectivityManager?): String {
    if (connectivity != null) {
        val address = try { connectivity.connectedLanAddress() } catch (_: SecurityException) { null }
        if (address != null) return address
    }
    // 热点接口可能未向 ConnectivityManager 注册，避免将蜂窝或隧道地址显示给成员
    return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && isLanTetherInterface(it.name) }
        .firstNotNullOfOrNull { it.inetAddresses.toList().lanIpv4() }.orEmpty()
}

internal fun isLanTetherInterface(name: String): Boolean =
    tetherInterface.matches(name)

private val tetherInterface = Regex("(ap|softap|swlan|wlan|eth|ap_br_wlan)[0-9]+")

private fun NetworkCapabilities?.isPhysicalLan(): Boolean =
    this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

private suspend fun ConnectivityManager.connectedLanAddress(): String? {
    val active = activeNetwork
    if (getNetworkCapabilities(active).isPhysicalLan()) {
        getLinkProperties(active)?.ipv4()?.let { return it }
    }
    val address = CompletableDeferred<String>()
    // 无互联网的 Wi-Fi 也能创建房间，默认路由可能属于 VPN 或蜂窝网络
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            linkProperties.ipv4()?.let(address::complete)
        }
    }
    registerNetworkCallback(lanNetworkRequest(), callback)
    return try { withTimeoutOrNull(1_000) { address.await() } } finally { unregisterNetworkCallback(callback) }
}

internal fun lanNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    .build()
