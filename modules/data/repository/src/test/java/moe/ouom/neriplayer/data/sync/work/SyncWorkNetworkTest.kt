package moe.ouom.neriplayer.data.sync.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class SyncWorkNetworkTest {
    @Test
    fun `only an active internet network with validation permits sync`() {
        val context = mock(Context::class.java)
        assertFalse(hasValidatedSyncNetwork(context))
        val manager = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
        assertFalse(hasValidatedSyncNetwork(context))
        val network = mock(Network::class.java)
        `when`(manager.activeNetwork).thenReturn(network)
        assertFalse(hasValidatedSyncNetwork(context))
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(manager.getNetworkCapabilities(network)).thenReturn(capabilities)
        assertFalse(hasValidatedSyncNetwork(context))
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        assertFalse(hasValidatedSyncNetwork(context))
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        assertTrue(hasValidatedSyncNetwork(context))
    }
}
