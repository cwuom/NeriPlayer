package moe.ouom.neriplayer.data.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

fun Context.hasLikelyInternetAccess(): Boolean {
    val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
    return connectivityManager.hasLikelyInternetAccess()
}

fun Context.isOfflineModeNow(): Boolean = !hasLikelyInternetAccess()

fun Context.currentTrafficNetworkType(): TrafficNetworkType {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ?: return TrafficNetworkType.MOBILE
    return connectivityManager.currentTrafficNetworkType()
}

fun Context.isTrafficRiskNetworkNow(): Boolean {
    return when (currentTrafficNetworkType()) {
        TrafficNetworkType.MOBILE,
        TrafficNetworkType.ROAMING -> true
        TrafficNetworkType.WIFI -> false
    }
}

private fun ConnectivityManager.hasLikelyInternetAccess(): Boolean = runCatching {
    val network = activeNetwork ?: return@runCatching false
    val capabilities = getNetworkCapabilities(network) ?: return@runCatching false
    hasLikelyNetworkTransport(
        activeHasDirectTransport = capabilities.hasDirectNetworkTransport(),
        activeHasVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        anyKnownHasDirectTransport = { anyKnownNetworkHasDirectTransport() }
    )
}.getOrDefault(false)

@Suppress("DEPRECATION")
private fun ConnectivityManager.anyKnownNetworkHasDirectTransport(): Boolean {
    return allNetworks.any { network ->
        getNetworkCapabilities(network)?.hasDirectNetworkTransport() == true
    }
}

private fun NetworkCapabilities.hasDirectNetworkTransport(): Boolean {
    return isDirectNetworkTransport(
        hasWifiTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        hasCellularTransport = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        hasEthernetTransport = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        hasBluetoothTransport = hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
        hasUsbTransport = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            hasTransport(NetworkCapabilities.TRANSPORT_USB),
        hasSatelliteTransport = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)
    )
}

internal fun hasLikelyNetworkTransport(
    activeHasDirectTransport: Boolean,
    activeHasVpnTransport: Boolean,
    anyKnownHasDirectTransport: () -> Boolean
): Boolean {
    if (activeHasDirectTransport) return true
    if (!activeHasVpnTransport) return false

    return anyKnownHasDirectTransport()
}

internal fun isDirectNetworkTransport(
    hasWifiTransport: Boolean,
    hasCellularTransport: Boolean,
    hasEthernetTransport: Boolean,
    hasBluetoothTransport: Boolean,
    hasUsbTransport: Boolean,
    hasSatelliteTransport: Boolean
): Boolean {
    return hasWifiTransport ||
        hasCellularTransport ||
        hasEthernetTransport ||
        hasBluetoothTransport ||
        hasUsbTransport ||
        hasSatelliteTransport
}

private fun ConnectivityManager.currentTrafficNetworkType(): TrafficNetworkType = runCatching {
    val activeNetwork = activeNetwork ?: return@runCatching TrafficNetworkType.MOBILE
    val capabilities = getNetworkCapabilities(activeNetwork)
        ?: return@runCatching TrafficNetworkType.MOBILE

    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        val isNotRoaming = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        return@runCatching if (isNotRoaming) {
            TrafficNetworkType.MOBILE
        } else {
            TrafficNetworkType.ROAMING
        }
    }

    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return@runCatching TrafficNetworkType.WIFI
    }

    TrafficNetworkType.MOBILE
}.getOrDefault(TrafficNetworkType.MOBILE)
