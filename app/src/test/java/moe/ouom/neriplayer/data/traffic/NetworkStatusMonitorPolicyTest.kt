package moe.ouom.neriplayer.data.traffic

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
    fun `unresolved interface capabilities keep the result indeterminate`() {
        assertEquals(
            LikelyNetworkTransportAvailability.INDETERMINATE,
            resolveNetworkInterfaceAvailability(
                interfaceScanCompleted = true,
                hasLegalNetworkInterface = false,
                hasUnresolvedNetworkInterface = true
            )
        )
    }

    @Test
    fun `incomplete interface enumeration stays indeterminate`() {
        assertEquals(
            LikelyNetworkTransportAvailability.INDETERMINATE,
            resolveNetworkInterfaceAvailability(
                interfaceScanCompleted = false,
                hasLegalNetworkInterface = false,
                hasUnresolvedNetworkInterface = false
            )
        )
    }

    @Test
    fun `a legal interface keeps the result online despite another unresolved entry`() {
        assertEquals(
            LikelyNetworkTransportAvailability.ONLINE,
            resolveNetworkInterfaceAvailability(
                interfaceScanCompleted = true,
                hasLegalNetworkInterface = true,
                hasUnresolvedNetworkInterface = true
            )
        )
    }

    @Test
    fun `offline requires a complete scan with no legal interfaces`() {
        assertEquals(
            LikelyNetworkTransportAvailability.OFFLINE,
            resolveNetworkInterfaceAvailability(
                interfaceScanCompleted = true,
                hasLegalNetworkInterface = false,
                hasUnresolvedNetworkInterface = false
            )
        )
    }

    @Test
    fun `unknown interface type cannot be used as offline evidence`() {
        assertEquals(
            LikelyNetworkTransportAvailability.INDETERMINATE,
            resolveNetworkInterfaceAvailability(
                interfaceScanCompleted = true,
                hasLegalNetworkInterface = false,
                hasUnresolvedNetworkInterface = true
            )
        )
    }

    @Test
    fun `vpn and additional legal transports keep app online`() {
        val cases = listOf(
            legalNetworkTransport(hasVpnTransport = true),
            legalNetworkTransport(hasWifiAwareTransport = true),
            legalNetworkTransport(hasLowpanTransport = true),
            legalNetworkTransport(hasThreadTransport = true)
        )

        cases.forEach { transport ->
            assertTrue(
                isLegalNetworkTransport(
                    hasWifiTransport = transport.hasWifiTransport,
                    hasCellularTransport = transport.hasCellularTransport,
                    hasEthernetTransport = transport.hasEthernetTransport,
                    hasBluetoothTransport = transport.hasBluetoothTransport,
                    hasWifiAwareTransport = transport.hasWifiAwareTransport,
                    hasLowpanTransport = transport.hasLowpanTransport,
                    hasUsbTransport = transport.hasUsbTransport,
                    hasSatelliteTransport = transport.hasSatelliteTransport,
                    hasThreadTransport = transport.hasThreadTransport,
                    hasVpnTransport = transport.hasVpnTransport
                )
            )
        }
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

    private data class DirectNetworkTransport(
        val hasWifiTransport: Boolean = false,
        val hasCellularTransport: Boolean = false,
        val hasEthernetTransport: Boolean = false,
        val hasBluetoothTransport: Boolean = false,
        val hasUsbTransport: Boolean = false,
        val hasSatelliteTransport: Boolean = false
    )

    private data class LegalNetworkTransport(
        val hasWifiTransport: Boolean = false,
        val hasCellularTransport: Boolean = false,
        val hasEthernetTransport: Boolean = false,
        val hasBluetoothTransport: Boolean = false,
        val hasWifiAwareTransport: Boolean = false,
        val hasLowpanTransport: Boolean = false,
        val hasUsbTransport: Boolean = false,
        val hasSatelliteTransport: Boolean = false,
        val hasThreadTransport: Boolean = false,
        val hasVpnTransport: Boolean = false
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

    private fun legalNetworkTransport(
        hasWifiTransport: Boolean = false,
        hasCellularTransport: Boolean = false,
        hasEthernetTransport: Boolean = false,
        hasBluetoothTransport: Boolean = false,
        hasWifiAwareTransport: Boolean = false,
        hasLowpanTransport: Boolean = false,
        hasUsbTransport: Boolean = false,
        hasSatelliteTransport: Boolean = false,
        hasThreadTransport: Boolean = false,
        hasVpnTransport: Boolean = false
    ): LegalNetworkTransport {
        return LegalNetworkTransport(
            hasWifiTransport = hasWifiTransport,
            hasCellularTransport = hasCellularTransport,
            hasEthernetTransport = hasEthernetTransport,
            hasBluetoothTransport = hasBluetoothTransport,
            hasWifiAwareTransport = hasWifiAwareTransport,
            hasLowpanTransport = hasLowpanTransport,
            hasUsbTransport = hasUsbTransport,
            hasSatelliteTransport = hasSatelliteTransport,
            hasThreadTransport = hasThreadTransport,
            hasVpnTransport = hasVpnTransport
        )
    }
}
