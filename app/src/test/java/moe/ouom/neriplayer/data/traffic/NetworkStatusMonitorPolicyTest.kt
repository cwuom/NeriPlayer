package moe.ouom.neriplayer.data.traffic

import android.net.NetworkCapabilities
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusMonitorPolicyTest {
    @Test
    fun `direct network transports keep app online`() {
        val cases = listOf(
            directNetworkTransport(hasWifiTransport = true),
            directNetworkTransport(hasCellularTransport = true),
            directNetworkTransport(hasEthernetTransport = true),
            directNetworkTransport(hasBluetoothTransport = true),
            directNetworkTransport(hasUsbTransport = true),
            directNetworkTransport(hasSatelliteTransport = true)
        )

        cases.forEach { transport ->
            assertTrue(
                isDirectNetworkTransport(
                    hasWifiTransport = transport.hasWifiTransport,
                    hasCellularTransport = transport.hasCellularTransport,
                    hasEthernetTransport = transport.hasEthernetTransport,
                    hasBluetoothTransport = transport.hasBluetoothTransport,
                    hasUsbTransport = transport.hasUsbTransport,
                    hasSatelliteTransport = transport.hasSatelliteTransport
                )
            )
        }
    }

    @Test
    fun `missing direct network transport enters offline mode`() {
        assertFalse(
            isDirectNetworkTransport(
                hasWifiTransport = false,
                hasCellularTransport = false,
                hasEthernetTransport = false,
                hasBluetoothTransport = false,
                hasUsbTransport = false,
                hasSatelliteTransport = false
            )
        )
    }

    @Test
    fun `vpn transport never counts as a direct network interface`() {
        assertFalse(
            isDirectNetworkTransport(
                hasVpnTransport = true,
                hasWifiTransport = true,
                hasCellularTransport = false,
                hasEthernetTransport = false,
                hasBluetoothTransport = false,
                hasUsbTransport = false,
                hasSatelliteTransport = false
            )
        )
    }

    @Test
    fun `vpn transport alone is not legal network interface evidence`() {
        assertFalse(
            isLegalNetworkTransport(
                hasWifiTransport = false,
                hasCellularTransport = false,
                hasEthernetTransport = false,
                hasBluetoothTransport = false,
                hasWifiAwareTransport = false,
                hasLowpanTransport = false,
                hasUsbTransport = false,
                hasSatelliteTransport = false,
                hasThreadTransport = false,
                hasVpnTransport = true
            )
        )
    }

    @Test
    fun `unresolved interface capabilities keep the result indeterminate`() {
        assertEquals(
            LikelyNetworkTransportAvailability.INDETERMINATE,
            resolveNetworkInterfaceAvailability(
                hasActiveNetwork = true,
                interfaceScanCompleted = true,
                hasDirectNetworkInterface = false,
                hasUnresolvedNetworkInterface = true
            )
        )
    }

    @Test
    fun `incomplete interface enumeration stays indeterminate`() {
        assertEquals(
            LikelyNetworkTransportAvailability.INDETERMINATE,
            resolveNetworkInterfaceAvailability(
                hasActiveNetwork = true,
                interfaceScanCompleted = false,
                hasDirectNetworkInterface = false,
                hasUnresolvedNetworkInterface = false
            )
        )
    }

    @Test
    fun `a direct interface keeps the result online despite another unresolved entry`() {
        assertEquals(
            LikelyNetworkTransportAvailability.ONLINE,
            resolveNetworkInterfaceAvailability(
                hasActiveNetwork = true,
                interfaceScanCompleted = true,
                hasDirectNetworkInterface = true,
                hasUnresolvedNetworkInterface = true
            )
        )
    }

    @Test
    fun `offline requires a complete scan with no direct interfaces`() {
        assertEquals(
            LikelyNetworkTransportAvailability.OFFLINE,
            resolveNetworkInterfaceAvailability(
                hasActiveNetwork = true,
                interfaceScanCompleted = true,
                hasDirectNetworkInterface = false,
                hasUnresolvedNetworkInterface = false
            )
        )
    }

    @Test
    fun `unknown interface type cannot be used as offline evidence`() {
        assertEquals(
            LikelyNetworkTransportAvailability.INDETERMINATE,
            resolveNetworkInterfaceAvailability(
                hasActiveNetwork = true,
                interfaceScanCompleted = true,
                hasDirectNetworkInterface = false,
                hasUnresolvedNetworkInterface = true
            )
        )
    }

    @Test
    fun `missing active network stays offline despite stale direct interface`() {
        assertEquals(
            LikelyNetworkTransportAvailability.OFFLINE,
            resolveNetworkInterfaceAvailability(
                hasActiveNetwork = false,
                interfaceScanCompleted = true,
                hasDirectNetworkInterface = true,
                hasUnresolvedNetworkInterface = false
            )
        )
    }

    @Test
    fun `virtual-only active network stays offline`() {
        assertEquals(
            LikelyNetworkTransportAvailability.OFFLINE,
            resolveNetworkInterfaceAvailability(
                hasActiveNetwork = true,
                interfaceScanCompleted = true,
                hasDirectNetworkInterface = false,
                hasUnresolvedNetworkInterface = false
            )
        )
    }

    @Test
    fun `indeterminate capability access keeps network operations enabled`() {
        assertTrue(
            resolveLikelyInternetAccess(
                availability = LikelyNetworkTransportAvailability.INDETERMINATE
            )
        )
    }

    @Test
    fun `explicit offline evidence disables network operations`() {
        assertFalse(
            resolveLikelyInternetAccess(
                availability = LikelyNetworkTransportAvailability.OFFLINE
            )
        )
    }

    @Test
    fun `internet transport without validation cannot recover downloads`() {
        assertFalse(
            hasValidatedInternetCapability(
                hasInternetCapability = true,
                hasValidatedCapability = false
            )
        )
    }

    @Test
    fun `validated internet requires both capability flags`() {
        assertTrue(
            hasValidatedInternetCapability(
                hasInternetCapability = true,
                hasValidatedCapability = true
            )
        )
        assertFalse(
            hasValidatedInternetCapability(
                hasInternetCapability = false,
                hasValidatedCapability = true
            )
        )
    }

    @Test
    fun `unvalidated transport is not eligible for download network policy`() {
        assertEquals(
            null,
            resolveValidatedTrafficNetworkType(
                networkType = TrafficNetworkType.WIFI,
                hasInternetCapability = true,
                hasValidatedCapability = false
            )
        )
    }

    @Test
    fun `download policy keeps an unvalidated wifi transport recoverable`() {
        assertEquals(
            TrafficNetworkType.WIFI,
            resolveDownloadNetworkType(
                validatedType = null,
                transportType = TrafficNetworkType.WIFI
            )
        )
    }

    @Test
    fun `download policy never promotes an unvalidated cellular transport`() {
        assertEquals(
            null,
            resolveDownloadNetworkType(
                validatedType = null,
                transportType = TrafficNetworkType.MOBILE
            )
        )
    }

    @Test
    fun `validated mobile network remains mobile for download policy`() {
        assertEquals(
            TrafficNetworkType.MOBILE,
            resolveDownloadNetworkType(
                validatedType = TrafficNetworkType.MOBILE,
                transportType = TrafficNetworkType.WIFI
            )
        )
    }

    @Test
    fun `validated transport keeps its billing class`() {
        assertEquals(
            TrafficNetworkType.MOBILE,
            resolveValidatedTrafficNetworkType(
                networkType = TrafficNetworkType.MOBILE,
                hasInternetCapability = true,
                hasValidatedCapability = true
            )
        )
    }

    @Test
    fun `validated network type helper rejects unvalidated capabilities`() {
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .thenReturn(true)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .thenReturn(false)

        assertEquals(null, capabilities.validatedTrafficNetworkTypeOrNull())
    }

    @Test
    fun `validated network type helper accepts validated internet`() {
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .thenReturn(true)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .thenReturn(true)

        assertEquals(
            TrafficNetworkType.WIFI,
            capabilities.validatedTrafficNetworkTypeOrNull()
        )
    }

    private data class DirectNetworkTransport(
        val hasWifiTransport: Boolean = false,
        val hasCellularTransport: Boolean = false,
        val hasEthernetTransport: Boolean = false,
        val hasBluetoothTransport: Boolean = false,
        val hasUsbTransport: Boolean = false,
        val hasSatelliteTransport: Boolean = false
    )

    @Test
    fun `unmetered vpn without underlying transport is not treated as mobile data`() {
        // 挂 VPN 时 active network 可能既不报 WiFi 也不报蜂窝，
        // 过去一律兜底成 MOBILE，流量策略会把 YouTube 音质压到降级档
        assertEquals(
            TrafficNetworkType.WIFI,
            resolveTrafficNetworkType(
                hasCellularTransport = false,
                hasWifiTransport = false,
                hasEthernetTransport = false,
                isNotRoaming = false,
                isNotMetered = true
            )
        )
    }

    @Test
    fun `metered vpn without underlying transport still counts as mobile data`() {
        assertEquals(
            TrafficNetworkType.MOBILE,
            resolveTrafficNetworkType(
                hasCellularTransport = false,
                hasWifiTransport = false,
                hasEthernetTransport = false,
                isNotRoaming = true,
                isNotMetered = false
            )
        )
    }

    @Test
    fun `ethernet counts as wifi`() {
        assertEquals(
            TrafficNetworkType.WIFI,
            resolveTrafficNetworkType(
                hasCellularTransport = false,
                hasWifiTransport = false,
                hasEthernetTransport = true,
                isNotRoaming = false,
                isNotMetered = false
            )
        )
    }

    @Test
    fun `cellular keeps roaming and mobile split regardless of metering`() {
        assertEquals(
            TrafficNetworkType.MOBILE,
            resolveTrafficNetworkType(
                hasCellularTransport = true,
                hasWifiTransport = false,
                hasEthernetTransport = false,
                isNotRoaming = true,
                isNotMetered = true
            )
        )
        assertEquals(
            TrafficNetworkType.ROAMING,
            resolveTrafficNetworkType(
                hasCellularTransport = true,
                hasWifiTransport = false,
                hasEthernetTransport = false,
                isNotRoaming = false,
                isNotMetered = true
            )
        )
    }

    @Test
    fun `wifi transport wins over metered flag`() {
        assertEquals(
            TrafficNetworkType.WIFI,
            resolveTrafficNetworkType(
                hasCellularTransport = false,
                hasWifiTransport = true,
                hasEthernetTransport = false,
                isNotRoaming = false,
                isNotMetered = false
            )
        )
    }

    private fun directNetworkTransport(
        hasWifiTransport: Boolean = false,
        hasCellularTransport: Boolean = false,
        hasEthernetTransport: Boolean = false,
        hasBluetoothTransport: Boolean = false,
        hasUsbTransport: Boolean = false,
        hasSatelliteTransport: Boolean = false
    ): DirectNetworkTransport {
        return DirectNetworkTransport(
            hasWifiTransport = hasWifiTransport,
            hasCellularTransport = hasCellularTransport,
            hasEthernetTransport = hasEthernetTransport,
            hasBluetoothTransport = hasBluetoothTransport,
            hasUsbTransport = hasUsbTransport,
            hasSatelliteTransport = hasSatelliteTransport
        )
    }

}
