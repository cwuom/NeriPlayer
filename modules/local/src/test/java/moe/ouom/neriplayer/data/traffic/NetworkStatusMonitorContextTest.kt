package moe.ouom.neriplayer.data.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import java.net.NetworkInterface
import java.net.SocketException
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@Suppress("DEPRECATION")
class NetworkStatusMonitorContextTest {
    @Test
    fun `missing service stays indeterminate and keeps legacy billing fallback`() {
        val context = mock(Context::class.java)

        assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
            context.currentLikelyNetworkTransportAvailability())
        assertTrue(context.hasLikelyInternetAccess())
        assertFalse(context.isOfflineModeNow())
        assertFalse(context.hasConfirmedInternetAccess())
        assertEquals(TrafficNetworkType.MOBILE, context.currentTrafficNetworkType())
        assertEquals(null, context.currentDownloadNetworkTypeOrNull())
    }

    @Test
    fun `no active network immediately rejects stale enumerated interfaces`() {
        val fixture = Fixture()
        val stale = mock(Network::class.java)
        val staleCapabilities = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        `when`(fixture.manager.allNetworks).thenReturn(arrayOf(stale))
        `when`(fixture.manager.getNetworkCapabilities(stale)).thenReturn(staleCapabilities)

        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())
        assertTrue(fixture.context.isOfflineModeNow())
        assertFalse(fixture.context.hasConfirmedInternetAccess())
        assertEquals(null, fixture.context.currentDownloadNetworkTypeOrNull())
        verify(fixture.manager, never()).allNetworks
    }

    @Test
    fun `active network access failure cannot become offline evidence`() {
        val fixture = Fixture()
        `when`(fixture.manager.activeNetwork).thenThrow(IllegalStateException("unavailable"))

        assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
            fixture.context.currentLikelyNetworkTransportAvailability())
        assertFalse(fixture.context.hasConfirmedInternetAccess())
        assertEquals(null, fixture.context.currentDownloadNetworkTypeOrNull())
    }

    @Test
    fun `missing or unreadable active capabilities stay indeterminate`() {
        val fixture = Fixture()
        val active = mock(Network::class.java)
        `when`(fixture.manager.activeNetwork).thenReturn(active)

        assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
            fixture.context.currentLikelyNetworkTransportAvailability())
        assertFalse(fixture.context.hasConfirmedInternetAccess())
        assertEquals(null, fixture.context.currentDownloadNetworkTypeOrNull())

        `when`(fixture.manager.getNetworkCapabilities(active))
            .thenThrow(IllegalStateException("capabilities unavailable"))
        assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
            fixture.context.currentLikelyNetworkTransportAvailability())
        assertFalse(fixture.context.hasConfirmedInternetAccess())
        assertEquals(null, fixture.context.currentDownloadNetworkTypeOrNull())
    }

    @Test
    fun `direct default transports are online without validating internet or scanning`() {
        listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_BLUETOOTH
        ).forEach { transport ->
            val fixture = Fixture()
            fixture.activate(capabilities(transport))

            assertEquals(LikelyNetworkTransportAvailability.ONLINE,
                fixture.context.currentLikelyNetworkTransportAvailability())
            assertFalse(fixture.context.hasConfirmedInternetAccess())
            verify(fixture.manager, never()).allNetworks
        }
    }

    @Test
    fun `vpn remains virtual even if its capabilities advertise wifi`() {
        val fixture = Fixture()
        val active = fixture.activate(capabilities(NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_WIFI))
        `when`(fixture.manager.allNetworks).thenReturn(arrayOf(active))

        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())
        verify(fixture.manager, never()).getNetworkInfo(active)
    }

    @Test
    fun `unknown default transport with an empty interface list is offline`() {
        val fixture = Fixture()
        fixture.activate(capabilities())

        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())
    }

    @Test
    fun `failed enumeration is indeterminate rather than an empty successful scan`() {
        val fixture = Fixture()
        fixture.activate(capabilities(NetworkCapabilities.TRANSPORT_VPN))
        `when`(fixture.manager.allNetworks).thenThrow(IllegalStateException("scan failed"))

        assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
            fixture.context.currentLikelyNetworkTransportAvailability())
    }

    @Test
    fun `a disappeared interface is unavailable but unreadable capabilities are unresolved`() {
        val fixture = Fixture()
        fixture.activate(capabilities(NetworkCapabilities.TRANSPORT_VPN))
        val underlying = mock(Network::class.java)
        `when`(fixture.manager.allNetworks).thenReturn(arrayOf(underlying))

        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())
        `when`(fixture.manager.getNetworkCapabilities(underlying))
            .thenThrow(IllegalStateException("read failed"))
        assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
            fixture.context.currentLikelyNetworkTransportAvailability())
    }

    @Test
    fun `connected interface with addresses stays available if Java interface is missing`() {
        val fixture = Fixture()
        fixture.connectUnderlying()
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<NetworkInterface?> { NetworkInterface.getByName(INTERFACE_NAME) }
                .thenReturn(null)

            assertEquals(LikelyNetworkTransportAvailability.ONLINE,
                fixture.context.currentLikelyNetworkTransportAvailability())
        }
    }

    @Test
    fun `routes alone are sufficient link evidence and an up interface stays online`() {
        val fixture = Fixture()
        fixture.connectUnderlying()
        val route = mock(RouteInfo::class.java)
        `when`(fixture.link.linkAddresses).thenReturn(emptyList())
        `when`(fixture.link.routes).thenReturn(listOf(route))
        val networkInterface = mock(NetworkInterface::class.java)
        `when`(networkInterface.isUp).thenReturn(true)
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<NetworkInterface?> { NetworkInterface.getByName(INTERFACE_NAME) }
                .thenReturn(networkInterface)

            assertEquals(LikelyNetworkTransportAvailability.ONLINE,
                fixture.context.currentLikelyNetworkTransportAvailability())
        }
    }

    @Test
    fun `an explicitly down Java interface does not keep vpn online`() {
        val fixture = Fixture()
        fixture.connectUnderlying()
        val networkInterface = mock(NetworkInterface::class.java)
        `when`(networkInterface.isUp).thenReturn(false)
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<NetworkInterface?> { NetworkInterface.getByName(INTERFACE_NAME) }
                .thenReturn(networkInterface)

            assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
                fixture.context.currentLikelyNetworkTransportAvailability())
        }
    }

    @Test
    fun `unreadable Java interface or interface state stays indeterminate`() {
        val fixture = Fixture()
        fixture.connectUnderlying()
        val networkInterface = mock(NetworkInterface::class.java)
        `when`(networkInterface.isUp).thenThrow(SocketException("state failed"))
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<NetworkInterface?> { NetworkInterface.getByName(INTERFACE_NAME) }
                .thenThrow(SocketException("lookup failed"))
                .thenReturn(networkInterface)
            assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
                fixture.context.currentLikelyNetworkTransportAvailability())

            assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
                fixture.context.currentLikelyNetworkTransportAvailability())
        }
    }

    @Test
    fun `missing and disconnected NetworkInfo do not trigger link inspection`() {
        val fixture = Fixture()
        val underlying = fixture.connectUnderlying()
        `when`(fixture.manager.getNetworkInfo(underlying)).thenReturn(null)

        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())
        `when`(fixture.manager.getNetworkInfo(underlying)).thenReturn(fixture.info)
        `when`(fixture.info.isConnected).thenReturn(false)
        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())
        verify(fixture.manager, never()).getLinkProperties(underlying)
    }

    @Test
    fun `missing link properties interface name or addresses and routes means unavailable`() {
        val fixture = Fixture()
        val underlying = fixture.connectUnderlying()
        `when`(fixture.manager.getLinkProperties(underlying)).thenReturn(null)
        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())

        `when`(fixture.manager.getLinkProperties(underlying)).thenReturn(fixture.link)
        `when`(fixture.link.interfaceName).thenReturn(null)
        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())

        `when`(fixture.link.interfaceName).thenReturn(INTERFACE_NAME)
        `when`(fixture.link.linkAddresses).thenReturn(emptyList())
        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            fixture.context.currentLikelyNetworkTransportAvailability())
    }

    @Test
    fun `Android network state and link read failures remain unresolved`() {
        val fixture = Fixture()
        val underlying = fixture.connectUnderlying()
        `when`(fixture.manager.getNetworkInfo(underlying))
            .thenThrow(IllegalStateException("state failed"))
        assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
            fixture.context.currentLikelyNetworkTransportAvailability())

        doReturn(fixture.info).`when`(fixture.manager).getNetworkInfo(underlying)
        `when`(fixture.manager.getLinkProperties(underlying))
            .thenThrow(IllegalStateException("link failed"))
        assertEquals(LikelyNetworkTransportAvailability.INDETERMINATE,
            fixture.context.currentLikelyNetworkTransportAvailability())
    }

    @Test
    fun `available underlying interface wins over another unreadable entry`() {
        val fixture = Fixture()
        val available = fixture.connectUnderlying()
        val unresolved = mock(Network::class.java)
        `when`(fixture.manager.getNetworkCapabilities(unresolved))
            .thenThrow(IllegalStateException("unresolved"))
        `when`(fixture.manager.allNetworks).thenReturn(arrayOf(unresolved, available))
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<NetworkInterface?> { NetworkInterface.getByName(INTERFACE_NAME) }
                .thenReturn(null)

            assertEquals(LikelyNetworkTransportAvailability.ONLINE,
                fixture.context.currentLikelyNetworkTransportAvailability())
        }
    }

    @Test
    fun `download classification accepts unvalidated wifi but waits for validated cellular`() {
        val fixture = Fixture()
        fixture.activate(capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(TrafficNetworkType.WIFI, fixture.context.currentDownloadNetworkTypeOrNull())

        val mobile = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)
        `when`(mobile.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
            .thenReturn(true)
        fixture.activate(mobile)
        assertEquals(TrafficNetworkType.MOBILE, fixture.context.currentTrafficNetworkType())
        assertEquals(null, fixture.context.currentDownloadNetworkTypeOrNull())
        `when`(mobile.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(mobile.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        assertTrue(fixture.context.hasConfirmedInternetAccess())
        assertEquals(TrafficNetworkType.MOBILE, fixture.context.currentDownloadNetworkTypeOrNull())
        assertEquals(TrafficNetworkType.MOBILE, mobile.downloadNetworkTypeOrNull())

        `when`(mobile.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
            .thenReturn(false)
        assertEquals(TrafficNetworkType.ROAMING, fixture.context.currentDownloadNetworkTypeOrNull())
        `when`(mobile.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)
        assertFalse(fixture.context.hasConfirmedInternetAccess())
    }

    private class Fixture {
        val context = mock(Context::class.java)
        val manager = mock(ConnectivityManager::class.java)
        val info = mock(NetworkInfo::class.java)
        val link = mock(LinkProperties::class.java)

        init {
            val address = mock(LinkAddress::class.java)
            `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
            `when`(manager.allNetworks).thenReturn(emptyArray())
            `when`(info.isConnected).thenReturn(true)
            `when`(link.interfaceName).thenReturn(INTERFACE_NAME)
            `when`(link.linkAddresses).thenReturn(listOf(address))
            `when`(link.routes).thenReturn(emptyList())
        }

        fun activate(capabilities: NetworkCapabilities): Network {
            val active = mock(Network::class.java)
            `when`(manager.activeNetwork).thenReturn(active)
            `when`(manager.getNetworkCapabilities(active)).thenReturn(capabilities)
            return active
        }

        fun connectUnderlying(): Network {
            val active = activate(capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val underlying = mock(Network::class.java)
            val underlyingCapabilities = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
            `when`(manager.getNetworkCapabilities(underlying)).thenReturn(underlyingCapabilities)
            `when`(manager.getNetworkInfo(underlying)).thenReturn(info)
            `when`(manager.getLinkProperties(underlying)).thenReturn(link)
            `when`(manager.allNetworks).thenReturn(arrayOf(active, underlying))
            return underlying
        }
    }

    companion object {
        private const val INTERFACE_NAME = "neriplayer-test0"

        private fun capabilities(vararg transports: Int): NetworkCapabilities {
            val capabilities = mock(NetworkCapabilities::class.java)
            transports.forEach { transport ->
                `when`(capabilities.hasTransport(transport)).thenReturn(true)
            }
            return capabilities
        }
    }
}
