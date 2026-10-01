package moe.ouom.neriplayer.data.traffic

import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTransportBoundaryTest {
    @Test
    fun `a missing validated billing class never gains a class from capability flags`() {
        assertEquals(null, resolveValidatedTrafficNetworkType(null, true, true))
        assertEquals(null, resolveDownloadNetworkType(null, null))
        assertEquals(null, resolveDownloadNetworkType(null, TrafficNetworkType.ROAMING))
    }

    @Test
    fun `nearby link transports remain legal without being direct internet interfaces`() {
        assertTrue(legal(hasWifiAware = true))
        assertTrue(legal(hasLowpan = true))
        assertTrue(legal(hasThread = true))
        assertTrue(legal(hasWifi = true))
        assertFalse(legal())
    }

    private fun legal(
        hasWifi: Boolean = false,
        hasWifiAware: Boolean = false,
        hasLowpan: Boolean = false,
        hasThread: Boolean = false
    ): Boolean = isLegalNetworkTransport(hasWifiTransport = hasWifi,
        hasCellularTransport = false, hasEthernetTransport = false,
        hasBluetoothTransport = false, hasWifiAwareTransport = hasWifiAware,
        hasLowpanTransport = hasLowpan, hasUsbTransport = false,
        hasSatelliteTransport = false, hasThreadTransport = hasThread, hasVpnTransport = false)
}
