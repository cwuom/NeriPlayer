package moe.ouom.neriplayer.data.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

fun Context.hasLikelyInternetAccess(): Boolean {
    return resolveLikelyInternetAccess(
        availability = currentLikelyNetworkTransportAvailability()
    )
}

fun Context.isOfflineModeNow(): Boolean = !hasLikelyInternetAccess()

fun Context.currentTrafficNetworkType(): TrafficNetworkType {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ?: return TrafficNetworkType.MOBILE
    return connectivityManager.currentTrafficNetworkType()
}

internal fun Context.currentLikelyNetworkTransportAvailability(): LikelyNetworkTransportAvailability {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ?: return LikelyNetworkTransportAvailability.INDETERMINATE
    return connectivityManager.currentLikelyNetworkTransportAvailability()
}

// allNetworks provides the complete interface snapshot needed for this strict offline rule
@Suppress("DEPRECATION")
private fun ConnectivityManager.currentLikelyNetworkTransportAvailability(): LikelyNetworkTransportAvailability {
    val networks = runCatching { allNetworks }.getOrElse {
        return LikelyNetworkTransportAvailability.INDETERMINATE
    }
    var hasLegalNetworkInterface = false
    var hasUnresolvedNetworkInterface = false
    networks.forEach { network ->
        val capabilities = runCatching { getNetworkCapabilities(network) }.getOrNull()
        if (capabilities == null) {
            hasUnresolvedNetworkInterface = true
        } else if (capabilities.hasLegalNetworkTransport()) {
            hasLegalNetworkInterface = true
        } else {
            // an unrecognized transport must not be mistaken for proof of being offline
            hasUnresolvedNetworkInterface = true
        }
    }
    return resolveNetworkInterfaceAvailability(
        interfaceScanCompleted = true,
        hasLegalNetworkInterface = hasLegalNetworkInterface,
        hasUnresolvedNetworkInterface = hasUnresolvedNetworkInterface
    )
}

private fun NetworkCapabilities.hasLegalNetworkTransport(): Boolean {
    return isLegalNetworkTransport(
        hasWifiTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        hasCellularTransport = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        hasEthernetTransport = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        hasBluetoothTransport = hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
        hasWifiAwareTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE),
        hasLowpanTransport = hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN),
        hasUsbTransport = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            hasTransport(NetworkCapabilities.TRANSPORT_USB),
        hasSatelliteTransport = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE),
        hasThreadTransport = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            hasTransport(NetworkCapabilities.TRANSPORT_THREAD),
        hasVpnTransport = hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    )
}

internal enum class LikelyNetworkTransportAvailability {
    ONLINE,
    OFFLINE,
    INDETERMINATE
}

internal fun resolveNetworkInterfaceAvailability(
    interfaceScanCompleted: Boolean,
    hasLegalNetworkInterface: Boolean,
    hasUnresolvedNetworkInterface: Boolean
): LikelyNetworkTransportAvailability {
    // only a complete interface snapshot may prove that offline mode is safe
    return when {
        hasLegalNetworkInterface -> LikelyNetworkTransportAvailability.ONLINE
        !interfaceScanCompleted || hasUnresolvedNetworkInterface -> {
            LikelyNetworkTransportAvailability.INDETERMINATE
        }

        else -> LikelyNetworkTransportAvailability.OFFLINE
    }
}

internal fun resolveLikelyInternetAccess(
    availability: LikelyNetworkTransportAvailability
): Boolean = availability != LikelyNetworkTransportAvailability.OFFLINE

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

internal fun isLegalNetworkTransport(
    hasWifiTransport: Boolean,
    hasCellularTransport: Boolean,
    hasEthernetTransport: Boolean,
    hasBluetoothTransport: Boolean,
    hasWifiAwareTransport: Boolean,
    hasLowpanTransport: Boolean,
    hasUsbTransport: Boolean,
    hasSatelliteTransport: Boolean,
    hasThreadTransport: Boolean,
    hasVpnTransport: Boolean
): Boolean {
    return isDirectNetworkTransport(
        hasWifiTransport = hasWifiTransport,
        hasCellularTransport = hasCellularTransport,
        hasEthernetTransport = hasEthernetTransport,
        hasBluetoothTransport = hasBluetoothTransport,
        hasUsbTransport = hasUsbTransport,
        hasSatelliteTransport = hasSatelliteTransport
    ) || hasWifiAwareTransport ||
        hasLowpanTransport ||
        hasThreadTransport ||
        hasVpnTransport
}

private fun ConnectivityManager.currentTrafficNetworkType(): TrafficNetworkType = runCatching {
    val activeNetwork = activeNetwork ?: return@runCatching TrafficNetworkType.MOBILE
    val capabilities = getNetworkCapabilities(activeNetwork)
        ?: return@runCatching TrafficNetworkType.MOBILE
    capabilities.trafficNetworkType()
}.getOrDefault(TrafficNetworkType.MOBILE)

internal fun NetworkCapabilities.trafficNetworkType(): TrafficNetworkType {
    return resolveTrafficNetworkType(
        hasCellularTransport = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        hasWifiTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        hasEthernetTransport = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        isNotRoaming = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
        isNotMetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    )
}

internal fun resolveTrafficNetworkType(
    hasCellularTransport: Boolean,
    hasWifiTransport: Boolean,
    hasEthernetTransport: Boolean,
    isNotRoaming: Boolean,
    isNotMetered: Boolean
): TrafficNetworkType {
    if (hasCellularTransport) {
        return if (isNotRoaming) TrafficNetworkType.MOBILE else TrafficNetworkType.ROAMING
    }
    if (hasWifiTransport || hasEthernetTransport) {
        return TrafficNetworkType.WIFI
    }
    // VPN 这类虚拟网络可能拿不到底层 transport，一律当移动数据会让
    // WiFi 上挂 VPN 的用户被流量策略降级音质，改用系统的计费标记判断
    return if (isNotMetered) TrafficNetworkType.WIFI else TrafficNetworkType.MOBILE
}
