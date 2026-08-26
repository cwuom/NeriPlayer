package moe.ouom.neriplayer.data.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.NetworkInterface
import moe.ouom.neriplayer.core.logging.NPLogger

private const val NETWORK_STATUS_LOG_TAG = "NERI-NetworkStatus"

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

@Suppress("DEPRECATION")
private fun ConnectivityManager.currentLikelyNetworkTransportAvailability(): LikelyNetworkTransportAvailability {
    val activeNetwork = runCatching { this.activeNetwork }.getOrElse {
        NPLogger.d(NETWORK_STATUS_LOG_TAG, "read active network failed: ${it.message}")
        return LikelyNetworkTransportAvailability.INDETERMINATE
    } ?: run {
        NPLogger.d(NETWORK_STATUS_LOG_TAG, "active network unavailable: result=OFFLINE")
        return LikelyNetworkTransportAvailability.OFFLINE
    }

    val activeCapabilities = runCatching { getNetworkCapabilities(activeNetwork) }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read active capabilities failed: network=$activeNetwork error=${it.message}"
        )
        return LikelyNetworkTransportAvailability.INDETERMINATE
    } ?: run {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "active capabilities unavailable: network=$activeNetwork result=INDETERMINATE"
        )
        return LikelyNetworkTransportAvailability.INDETERMINATE
    }
    val activeTransports = activeCapabilities.transportSummary()
    if (activeCapabilities.hasDirectNetworkTransport()) {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "active=$activeNetwork transports=$activeTransports direct=true result=ONLINE"
        )
        return LikelyNetworkTransportAvailability.ONLINE
    }

    val networks = runCatching { allNetworks }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read network interfaces failed: active=$activeNetwork " +
                "transports=$activeTransports error=${it.message}"
        )
        return LikelyNetworkTransportAvailability.INDETERMINATE
    }
    var hasDirectNetworkInterface = false
    var hasUnresolvedNetworkInterface = false
    val interfaceSummaries = mutableListOf<String>()
    networks.forEach { network ->
        val capabilities = runCatching { getNetworkCapabilities(network) }.getOrElse {
            hasUnresolvedNetworkInterface = true
            interfaceSummaries += "$network:capabilities_error"
            return@forEach
        }
        if (capabilities == null) {
            interfaceSummaries += "$network:gone"
            return@forEach
        }
        if (!capabilities.hasDirectNetworkTransport()) {
            interfaceSummaries += "$network:${capabilities.transportSummary()}"
            return@forEach
        }

        when (directNetworkInterfaceState(network)) {
            DirectNetworkInterfaceState.AVAILABLE -> {
                hasDirectNetworkInterface = true
                interfaceSummaries += "$network:${capabilities.transportSummary()}:up"
            }

            DirectNetworkInterfaceState.UNAVAILABLE -> {
                interfaceSummaries += "$network:${capabilities.transportSummary()}:down"
            }

            DirectNetworkInterfaceState.INDETERMINATE -> {
                hasUnresolvedNetworkInterface = true
                interfaceSummaries += "$network:${capabilities.transportSummary()}:unknown"
            }
        }
    }
    val availability = resolveNetworkInterfaceAvailability(
        hasActiveNetwork = true,
        interfaceScanCompleted = true,
        hasDirectNetworkInterface = hasDirectNetworkInterface,
        hasUnresolvedNetworkInterface = hasUnresolvedNetworkInterface
    )
    NPLogger.d(
        NETWORK_STATUS_LOG_TAG,
        "active=$activeNetwork transports=$activeTransports direct=false " +
            "interfaces=${interfaceSummaries.joinToString(separator = ",", limit = 8)} " +
            "result=$availability"
    )
    return availability
}

@Suppress("DEPRECATION")
private fun ConnectivityManager.directNetworkInterfaceState(
    network: android.net.Network
): DirectNetworkInterfaceState {
    val networkInfo = runCatching { getNetworkInfo(network) }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read network state failed: network=$network error=${it.message}"
        )
        return DirectNetworkInterfaceState.INDETERMINATE
    } ?: return DirectNetworkInterfaceState.UNAVAILABLE
    if (!networkInfo.isConnected) return DirectNetworkInterfaceState.UNAVAILABLE

    val linkProperties = runCatching { getLinkProperties(network) }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read link properties failed: network=$network error=${it.message}"
        )
        return DirectNetworkInterfaceState.INDETERMINATE
    } ?: return DirectNetworkInterfaceState.UNAVAILABLE
    val interfaceName = linkProperties.interfaceName
        ?: return DirectNetworkInterfaceState.UNAVAILABLE
    if (linkProperties.linkAddresses.isEmpty() && linkProperties.routes.isEmpty()) {
        return DirectNetworkInterfaceState.UNAVAILABLE
    }

    val networkInterface = runCatching { NetworkInterface.getByName(interfaceName) }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read system interface failed: interface=$interfaceName error=${it.message}"
        )
        return DirectNetworkInterfaceState.INDETERMINATE
    }
    val interfaceIsUp = runCatching { networkInterface?.isUp ?: true }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read interface state failed: interface=$interfaceName error=${it.message}"
        )
        return DirectNetworkInterfaceState.INDETERMINATE
    }
    return if (interfaceIsUp) {
        DirectNetworkInterfaceState.AVAILABLE
    } else {
        DirectNetworkInterfaceState.UNAVAILABLE
    }
}

private enum class DirectNetworkInterfaceState {
    AVAILABLE,
    UNAVAILABLE,
    INDETERMINATE
}

private fun NetworkCapabilities.hasDirectNetworkTransport(): Boolean {
    return isDirectNetworkTransport(
        hasVpnTransport = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
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

private fun NetworkCapabilities.transportSummary(): String {
    val transports = buildList {
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            hasTransport(NetworkCapabilities.TRANSPORT_USB)
        ) {
            add("usb")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)
        ) {
            add("satellite")
        }
    }
    return transports.ifEmpty { listOf("other") }.joinToString(separator = "|")
}

internal enum class LikelyNetworkTransportAvailability {
    ONLINE,
    OFFLINE,
    INDETERMINATE
}

internal fun resolveNetworkInterfaceAvailability(
    hasActiveNetwork: Boolean,
    interfaceScanCompleted: Boolean,
    hasDirectNetworkInterface: Boolean,
    hasUnresolvedNetworkInterface: Boolean
): LikelyNetworkTransportAvailability {
    return when {
        !hasActiveNetwork -> LikelyNetworkTransportAvailability.OFFLINE
        hasDirectNetworkInterface -> LikelyNetworkTransportAvailability.ONLINE
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
    hasSatelliteTransport: Boolean,
    hasVpnTransport: Boolean = false
): Boolean {
    return !hasVpnTransport && (hasWifiTransport ||
        hasCellularTransport ||
        hasEthernetTransport ||
        hasBluetoothTransport ||
        hasUsbTransport ||
        hasSatelliteTransport)
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
        hasSatelliteTransport = hasSatelliteTransport,
        hasVpnTransport = hasVpnTransport
    ) || hasWifiAwareTransport ||
        hasLowpanTransport ||
        hasThreadTransport
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
