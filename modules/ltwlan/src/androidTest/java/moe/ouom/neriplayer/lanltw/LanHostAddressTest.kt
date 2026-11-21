package moe.ouom.neriplayer.lanltw

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.lanltw.session.isLanTetherInterface
import moe.ouom.neriplayer.lanltw.session.lanNetworkRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanHostAddressTest {
    @Test fun manualAddressLooksForPhysicalLanEvenWhenInternetUsesAnotherNetwork() {
        val request = lanNetworkRequest()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN))

        listOf("wlan0", "eth0", "ap0", "softap1", "ap_br_wlan2").forEach { assertTrue(it, isLanTetherInterface(it)) }
        listOf("lo", "tun0", "wg0", "rmnet_data0", "veth0").forEach { assertFalse(it, isLanTetherInterface(it)) }
    }
}
