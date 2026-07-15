package moe.ouom.neriplayer.data.traffic

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
    fun `vpn transport alone does not keep app online`() {
        assertFalse(
            hasLikelyNetworkTransport(
                activeHasDirectTransport = false,
                activeHasVpnTransport = true,
                anyKnownHasDirectTransport = { false }
            )
        )
    }

    @Test
    fun `vpn default network follows underlying direct transport`() {
        assertTrue(
            hasLikelyNetworkTransport(
                activeHasDirectTransport = false,
                activeHasVpnTransport = true,
                anyKnownHasDirectTransport = { true }
            )
        )
    }

    @Test
    fun `active direct transport does not scan fallback networks`() {
        assertTrue(
            hasLikelyNetworkTransport(
                activeHasDirectTransport = true,
                activeHasVpnTransport = false,
                anyKnownHasDirectTransport = { error("fallback should not be evaluated") }
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
