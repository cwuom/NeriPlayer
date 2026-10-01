package moe.ouom.neriplayer.data.traffic

import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.NetworkInterface
import moe.ouom.neriplayer.common.logging.NPLogger

private const val NETWORK_STATUS_LOG_TAG = "NERI-NetworkStatus"

fun Context.hasLikelyInternetAccess(): Boolean {
    return resolveLikelyInternetAccess(
        availability = currentLikelyNetworkTransportAvailability()
    )
}

fun Context.hasConfirmedInternetAccess(): Boolean {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ?: return false
    val activeNetwork = runCatching { connectivityManager.activeNetwork }
        .getOrNull()
        ?: return false
    val capabilities = runCatching {
        connectivityManager.getNetworkCapabilities(activeNetwork)
    }.getOrNull()
        ?: return false
    return hasValidatedInternetCapability(
        hasInternetCapability = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ),
        hasValidatedCapability = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )
    )
}

fun Context.isOfflineModeNow(): Boolean = !hasLikelyInternetAccess()

fun Context.currentTrafficNetworkType(): TrafficNetworkType {
    // 保留旧 API 的 MOBILE 回退，避免改变历史统计和 UI 序列化；下载策略
    // 使用可空快照，不能把未知状态当成移动网络
    return currentTransportTrafficNetworkTypeOrNull() ?: TrafficNetworkType.MOBILE
}

internal fun Context.currentTrafficNetworkTypeOrNull(): TrafficNetworkType? {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ?: return null
    return connectivityManager.currentValidatedTrafficNetworkTypeOrNull()
}

/**
 * 返回下载策略可用的网络类别
 *
 * 未验证窗口只能信任明确的 Wi-Fi 类传输，不能把未验证蜂窝当成可用移动网络
 */
fun Context.currentDownloadNetworkTypeOrNull(): TrafficNetworkType? {
    return resolveDownloadNetworkType(
        validatedType = currentTrafficNetworkTypeOrNull(),
        transportType = currentTransportTrafficNetworkTypeOrNull()
    )
}

/**
 * 下载策略只在未验证窗口保留明确的 Wi-Fi 类传输
 */
internal fun resolveDownloadNetworkType(
    validatedType: TrafficNetworkType?,
    transportType: TrafficNetworkType?
): TrafficNetworkType? {
    return validatedType ?: transportType?.takeIf { type -> type == TrafficNetworkType.WIFI }
}

internal fun Context.currentTransportTrafficNetworkTypeOrNull(): TrafficNetworkType? {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ?: return null
    return connectivityManager.currentTransportTrafficNetworkTypeOrNull()
}

fun Context.currentLikelyNetworkTransportAvailability(): LikelyNetworkTransportAvailability {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ?: return LikelyNetworkTransportAvailability.INDETERMINATE
    return connectivityManager.currentLikelyNetworkTransportAvailability()
}

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

    val scan = runCatching { scanNetworkInterfaces() }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read network interfaces failed: active=$activeNetwork " +
                "transports=$activeTransports error=${it.message}"
        )
        return LikelyNetworkTransportAvailability.INDETERMINATE
    }
    val availability = resolveNetworkInterfaceAvailability(
        hasActiveNetwork = true,
        interfaceScanCompleted = true,
        hasDirectNetworkInterface = DirectNetworkInterfaceState.AVAILABLE in scan.states,
        hasUnresolvedNetworkInterface = DirectNetworkInterfaceState.INDETERMINATE in scan.states
    )
    NPLogger.d(
        NETWORK_STATUS_LOG_TAG,
        "active=$activeNetwork transports=$activeTransports direct=false " +
            "interfaces=${scan.summaries.joinToString(separator = ",", limit = 8)} " +
            "result=$availability"
    )
    return availability
}

private data class NetworkInterfaceObservation(
    val state: DirectNetworkInterfaceState,
    val summary: String
)

private data class NetworkInterfaceScan(
    val states: Set<DirectNetworkInterfaceState>,
    val summaries: List<String>
)

// 兼容 VPN 底层接口观察，仍需读取系统完整网络列表
@Suppress("DEPRECATION")
private fun ConnectivityManager.scanNetworkInterfaces(): NetworkInterfaceScan {
    val observations = allNetworks.map { observeNetworkInterface(it) }
    return NetworkInterfaceScan(
        states = observations.map(NetworkInterfaceObservation::state).toSet(),
        summaries = observations.map(NetworkInterfaceObservation::summary)
    )
}

private fun ConnectivityManager.observeNetworkInterface(network: Network): NetworkInterfaceObservation {
    val capabilities = runCatching { getNetworkCapabilities(network) }.getOrElse {
        return NetworkInterfaceObservation(
            DirectNetworkInterfaceState.INDETERMINATE, "$network:capabilities_error"
        )
    } ?: return NetworkInterfaceObservation(DirectNetworkInterfaceState.UNAVAILABLE, "$network:gone")
    val summary = "$network:${capabilities.transportSummary()}"
    if (!capabilities.hasDirectNetworkTransport()) {
        return NetworkInterfaceObservation(DirectNetworkInterfaceState.UNAVAILABLE, summary)
    }
    val state = directNetworkInterfaceState(network)
    return NetworkInterfaceObservation(state, "$summary:${state.summary}")
}

@Suppress("DEPRECATION")
private fun ConnectivityManager.directNetworkInterfaceState(
    network: Network
): DirectNetworkInterfaceState {
    var operation = "network state"
    return runCatching {
        if (getNetworkInfo(network)?.isConnected != true) {
            return@runCatching DirectNetworkInterfaceState.UNAVAILABLE
        }
        operation = "link properties"
        val interfaceName = getLinkProperties(network)?.usableInterfaceName()
            ?: return@runCatching DirectNetworkInterfaceState.UNAVAILABLE
        observeSystemNetworkInterface(interfaceName)
    }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read $operation failed: network=$network error=${it.message}"
        )
        DirectNetworkInterfaceState.INDETERMINATE
    }
}

private fun observeSystemNetworkInterface(interfaceName: String): DirectNetworkInterfaceState {
    var operation = "system interface"
    return runCatching {
        val networkInterface = NetworkInterface.getByName(interfaceName)
        operation = "interface state"
        if (networkInterface?.isUp != false) {
            DirectNetworkInterfaceState.AVAILABLE
        } else {
            DirectNetworkInterfaceState.UNAVAILABLE
        }
    }.getOrElse {
        NPLogger.d(
            NETWORK_STATUS_LOG_TAG,
            "read $operation failed: interface=$interfaceName error=${it.message}"
        )
        DirectNetworkInterfaceState.INDETERMINATE
    }
}

private fun LinkProperties.usableInterfaceName(): String? =
    interfaceName?.takeIf { linkAddresses.isNotEmpty() || routes.isNotEmpty() }

private enum class DirectNetworkInterfaceState(val summary: String) {
    AVAILABLE("up"),
    UNAVAILABLE("down"),
    INDETERMINATE("unknown")
}

private fun NetworkCapabilities.hasDirectNetworkTransport(): Boolean {
    return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        supportedNetworkTransports().any { it.isDirect && hasTransport(it.type) }
}

private data class NetworkTransport(val type: Int, val summary: String, val isDirect: Boolean)

private val baseNetworkTransports = listOf(
    NetworkTransport(NetworkCapabilities.TRANSPORT_WIFI, "wifi", true),
    NetworkTransport(NetworkCapabilities.TRANSPORT_CELLULAR, "cellular", true),
    NetworkTransport(NetworkCapabilities.TRANSPORT_ETHERNET, "ethernet", true),
    NetworkTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH, "bluetooth", true),
    NetworkTransport(NetworkCapabilities.TRANSPORT_VPN, "vpn", false)
)

private fun supportedNetworkTransports(): List<NetworkTransport> = buildList {
    addAll(baseNetworkTransports)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(NetworkTransport(NetworkCapabilities.TRANSPORT_USB, "usb", true))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        add(NetworkTransport(NetworkCapabilities.TRANSPORT_SATELLITE, "satellite", true))
    }
}

private fun NetworkCapabilities.transportSummary(): String = supportedNetworkTransports()
    .filter { hasTransport(it.type) }
    .map(NetworkTransport::summary)
    .ifEmpty { listOf("other") }
    .joinToString(separator = "|")

enum class LikelyNetworkTransportAvailability {
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

internal fun hasValidatedInternetCapability(
    hasInternetCapability: Boolean,
    hasValidatedCapability: Boolean
): Boolean = hasInternetCapability && hasValidatedCapability

internal fun resolveValidatedTrafficNetworkType(
    networkType: TrafficNetworkType?,
    hasInternetCapability: Boolean,
    hasValidatedCapability: Boolean
): TrafficNetworkType? {
    return networkType.takeIf {
        it != null && hasValidatedInternetCapability(
            hasInternetCapability = hasInternetCapability,
            hasValidatedCapability = hasValidatedCapability
        )
    }
}

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

private fun ConnectivityManager.currentTransportTrafficNetworkTypeOrNull(): TrafficNetworkType? =
    runCatching {
        val activeNetwork = activeNetwork ?: return@runCatching null
        val capabilities = getNetworkCapabilities(activeNetwork)
            ?: return@runCatching null
        capabilities.trafficNetworkType()
    }.getOrNull()

private fun ConnectivityManager.currentValidatedTrafficNetworkTypeOrNull(): TrafficNetworkType? =
    runCatching {
        val activeNetwork = activeNetwork ?: return@runCatching null
        val capabilities = getNetworkCapabilities(activeNetwork)
            ?: return@runCatching null
        val networkType = capabilities.trafficNetworkType()
        resolveValidatedTrafficNetworkType(
            networkType = networkType,
            hasInternetCapability = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ),
            hasValidatedCapability = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
        )
    }.getOrNull()

internal fun NetworkCapabilities.trafficNetworkType(): TrafficNetworkType {
    return resolveTrafficNetworkType(
        hasCellularTransport = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        hasWifiTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        hasEthernetTransport = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        isNotRoaming = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
        isNotMetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    )
}

internal fun NetworkCapabilities.validatedTrafficNetworkTypeOrNull(): TrafficNetworkType? {
    return resolveValidatedTrafficNetworkType(
        networkType = trafficNetworkType(),
        hasInternetCapability = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        hasValidatedCapability = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    )
}

/**
 * 网络回调收敛使用的类别
 *
 * capability 尚未拿到 VALIDATED 时，保留 Wi-Fi 传输事实，等待可达性自行重试
 */
fun NetworkCapabilities.downloadNetworkTypeOrNull(): TrafficNetworkType? {
    return resolveDownloadNetworkType(
        validatedType = validatedTrafficNetworkTypeOrNull(),
        transportType = trafficNetworkType()
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
