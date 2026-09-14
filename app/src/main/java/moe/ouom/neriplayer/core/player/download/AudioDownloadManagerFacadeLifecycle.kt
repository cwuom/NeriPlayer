package moe.ouom.neriplayer.core.player.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import moe.ouom.neriplayer.data.traffic.downloadNetworkTypeOrNull

internal fun AudioDownloadManager.initializeImpl(context: Context) {
    val appContext = context.applicationContext
    synchronized(networkRecoveryMonitorLock) {
        if (networkRecoveryMonitorRegistered) {
            return
        }
        val connectivityManager: ConnectivityManager =
            appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val initialNetwork = connectivityManager.activeNetwork
        val initialNetworkType = initialNetwork
            ?.let { network -> connectivityManager.getNetworkCapabilities(network) }
            ?.downloadNetworkTypeOrNull()
        val persistedNetworkGeneration = appContext
            .getSharedPreferences(DOWNLOAD_NETWORK_POLICY_PREFS, Context.MODE_PRIVATE)
            .getLong(NETWORK_GENERATION_PREF, 0L)
        downloadNetworkPolicyTracker.seed(
            networkKey = initialNetwork,
            networkType = initialNetworkType,
            initialGeneration = persistedNetworkGeneration
        )
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleDefaultDownloadNetworkCallback(
                    context = appContext,
                    connectivityManager = connectivityManager,
                    callbackNetwork = network,
                    reason = "network_available"
                )
                if (shouldNotifyRecoveryForConfirmedInternet(appContext)) {
                    notifyRecoveryOpportunity("network_available")
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                _networkCapabilities: NetworkCapabilities
            ) {
                handleDefaultDownloadNetworkCallback(
                    context = appContext,
                    connectivityManager = connectivityManager,
                    callbackNetwork = network,
                    reason = "network_capabilities_changed"
                )
                if (shouldNotifyRecoveryForConfirmedInternet(appContext)) {
                    notifyRecoveryOpportunity("network_available")
                }
            }

            override fun onLost(network: Network) {
                synchronized(networkRecoveryMonitorLock) {
                    lastConfirmedInternetAccess = false
                }
                handleDefaultDownloadNetworkLost(
                    context = appContext,
                    connectivityManager = connectivityManager,
                    network = network
                )
            }
        }
        val registered = runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)
        if (registered) {
            networkRecoveryMonitorRegistered = true
        }
    }
}
