package moe.ouom.neriplayer.core.di.ltw

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherNetworkListener
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherNetworkMonitor

internal class AndroidListenTogetherNetworkMonitor(
    private val context: () -> Context
) : ListenTogetherNetworkMonitor {
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(listener: ListenTogetherNetworkListener) = synchronized(lock) {
        if (callback != null) return@synchronized
        val connectivityManager = connectivityManager() ?: return@synchronized
        val candidate = ListenTogetherDefaultNetworkCallback(listener)
        try {
            connectivityManager.registerDefaultNetworkCallback(candidate)
            callback = candidate
        } catch (error: RuntimeException) {
            NPLogger.w(TAG, "register default network callback failed: ${error.message}")
        }
    }

    override fun stop() = synchronized(lock) {
        val registered = callback ?: return@synchronized
        callback = null
        try {
            connectivityManager()?.unregisterNetworkCallback(registered)
        } catch (error: RuntimeException) {
            NPLogger.w(TAG, "unregister default network callback failed: ${error.message}")
        }
    }

    // 拿不到系统服务时无法判断，沿用乐观假设，避免重连被永久挂起
    override fun hasDefaultNetwork(): Boolean = try {
        connectivityManager()?.let { it.activeNetwork != null } ?: true
    } catch (error: RuntimeException) {
        NPLogger.w(TAG, "read default network failed: ${error.message}")
        true
    }

    private fun connectivityManager(): ConnectivityManager? =
        context().getSystemService(ConnectivityManager::class.java)

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}

private class ListenTogetherDefaultNetworkCallback(
    private val listener: ListenTogetherNetworkListener
) : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = listener.onDefaultNetworkAvailable(network)

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            listener.onDefaultNetworkValidated(network)
        }
    }

    override fun onLost(network: Network) = listener.onDefaultNetworkLost(network)
}
