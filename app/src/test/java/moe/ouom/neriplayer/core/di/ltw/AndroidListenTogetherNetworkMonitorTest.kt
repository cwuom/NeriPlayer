package moe.ouom.neriplayer.core.di.ltw

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherNetworkListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AndroidListenTogetherNetworkMonitorTest {
    @Test
    fun `default network callbacks reach the listener and stop unregisters once`() {
        val connectivity = mock(ConnectivityManager::class.java)
        val context = contextWith(connectivity)
        val listener = RecordingListener()
        val monitor = AndroidListenTogetherNetworkMonitor { context }
        monitor.start(listener)
        monitor.start(RecordingListener())
        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(connectivity, times(1)).registerDefaultNetworkCallback(captor.capture())
        val callback = captor.value
        val network = mock(Network::class.java)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, capabilities(validated = false))
        callback.onCapabilitiesChanged(network, capabilities(validated = true))
        callback.onLost(network)
        assertEquals(listOf("available", "validated", "lost"), listener.events)

        monitor.stop()
        monitor.stop()
        verify(connectivity, times(1)).unregisterNetworkCallback(callback)
    }

    @Test
    fun `missing service or rejected registration leaves nothing registered`() {
        val unavailable = AndroidListenTogetherNetworkMonitor { contextWith(null) }
        unavailable.start(RecordingListener())
        unavailable.stop()

        val connectivity = mock(ConnectivityManager::class.java)
        doThrow(SecurityException("denied")).`when`(connectivity)
            .registerDefaultNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
        val rejected = AndroidListenTogetherNetworkMonitor { contextWith(connectivity) }
        rejected.start(RecordingListener())
        rejected.stop()
        rejected.start(RecordingListener())
        verify(connectivity, times(2)).registerDefaultNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
        verify(connectivity, never()).unregisterNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
    }

    @Test
    fun `failed unregister still lets a later session register again`() {
        val connectivity = mock(ConnectivityManager::class.java)
        doThrow(IllegalArgumentException("not registered")).`when`(connectivity)
            .unregisterNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
        val monitor = AndroidListenTogetherNetworkMonitor { contextWith(connectivity) }
        monitor.start(RecordingListener())
        monitor.stop()
        monitor.start(RecordingListener())
        verify(connectivity, times(2)).registerDefaultNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
    }

    private fun contextWith(connectivity: ConnectivityManager?): Context = mock(Context::class.java).also {
        `when`(it.getSystemService(ConnectivityManager::class.java)).thenReturn(connectivity)
    }

    private fun capabilities(validated: Boolean): NetworkCapabilities = mock(NetworkCapabilities::class.java).also {
        `when`(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(validated)
    }

    private class RecordingListener : ListenTogetherNetworkListener {
        val events = mutableListOf<String>()
        override fun onDefaultNetworkAvailable(network: Any) { events += "available" }
        override fun onDefaultNetworkValidated(network: Any) { events += "validated" }
        override fun onDefaultNetworkLost(network: Any) { events += "lost" }
    }
}
