package moe.ouom.neriplayer.core.download.execution

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiBoundDownloadWakeWorkerTest {
    @Test
    fun wifiWakeWorkRequiresWifiOrEthernetInternet() {
        val request = WifiBoundDownloadWakeWorker.buildRequest("wifi-wake-operation")
        val networkRequest = requireNotNull(request.workSpec.constraints.requiredNetworkRequest)

        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(networkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(networkRequest.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(networkRequest.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
}
