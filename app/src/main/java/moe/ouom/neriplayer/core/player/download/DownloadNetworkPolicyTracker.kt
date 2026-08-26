package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.data.traffic.TrafficNetworkType

/** keeps callback ordering from turning one Wi-Fi loss into duplicate pauses */
internal class DownloadNetworkPolicyTracker {
    private var currentDefaultNetworkKey: Any? = null
    private var currentTrafficNetworkType: TrafficNetworkType? = null
    private var wifiLossHandled = false

    @Synchronized
    fun seed(networkKey: Any?, networkType: TrafficNetworkType?) {
        currentDefaultNetworkKey = networkKey
        currentTrafficNetworkType = networkType
        wifiLossHandled = false
    }

    @Synchronized
    fun onDefaultNetworkObserved(
        networkKey: Any,
        networkType: TrafficNetworkType
    ): Boolean {
        val shouldPause = currentTrafficNetworkType == TrafficNetworkType.WIFI &&
            networkType != TrafficNetworkType.WIFI &&
            !wifiLossHandled
        currentDefaultNetworkKey = networkKey
        currentTrafficNetworkType = networkType
        if (networkType == TrafficNetworkType.WIFI) {
            wifiLossHandled = false
        } else if (shouldPause) {
            wifiLossHandled = true
        }
        return shouldPause
    }

    @Synchronized
    fun onDefaultNetworkLost(networkKey: Any): Boolean {
        if (currentDefaultNetworkKey != networkKey) {
            return false
        }
        currentDefaultNetworkKey = null
        return currentTrafficNetworkType == TrafficNetworkType.WIFI && !wifiLossHandled
    }

    @Synchronized
    fun markWifiLossHandled() {
        wifiLossHandled = true
    }
}
