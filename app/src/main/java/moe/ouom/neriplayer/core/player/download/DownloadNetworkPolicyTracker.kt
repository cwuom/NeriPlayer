package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.data.traffic.TrafficNetworkType

/** keeps callback ordering from turning one Wi-Fi loss into duplicate pauses */
internal class DownloadNetworkPolicyTracker {
    internal data class NetworkObservationResult(
        val changed: Boolean,
        val shouldPause: Boolean,
        val becameWifi: Boolean,
        val generation: Long
    )

    private var currentDefaultNetworkKey: Any? = null
    private var currentTrafficNetworkType: TrafficNetworkType? = null
    private var wifiLossHandled = false
    private var networkGeneration = 0L

    @Synchronized
    fun seed(networkKey: Any?, networkType: TrafficNetworkType?) {
        currentDefaultNetworkKey = networkKey
        currentTrafficNetworkType = networkType
        wifiLossHandled = false
        networkGeneration = 0L
    }

    @Synchronized
    fun onDefaultNetworkObserved(
        networkKey: Any,
        networkType: TrafficNetworkType
    ): Boolean {
        return onDefaultNetworkObserved(
            networkKey = networkKey,
            networkType = networkType,
            activeNetworkKey = networkKey,
            activeNetworkKnown = true
        )
    }

    @Synchronized
    fun onDefaultNetworkObserved(
        networkKey: Any,
        networkType: TrafficNetworkType,
        activeNetworkKey: Any?,
        activeNetworkKnown: Boolean = true
    ): Boolean {
        return observeDefaultNetwork(
            networkKey = networkKey,
            networkType = networkType,
            activeNetworkKey = activeNetworkKey,
            activeNetworkKnown = activeNetworkKnown
        ).shouldPause
    }

    /** returns one coherent transition result for policy and recovery callers */
    @Synchronized
    fun observeDefaultNetwork(
        networkKey: Any,
        networkType: TrafficNetworkType,
        activeNetworkKey: Any?,
        activeNetworkKnown: Boolean = true
    ): NetworkObservationResult {
        if (!activeNetworkKnown || activeNetworkKey != networkKey) {
            return NetworkObservationResult(
                changed = false,
                shouldPause = false,
                becameWifi = false,
                generation = networkGeneration
            )
        }
        if (
            currentDefaultNetworkKey == networkKey &&
                currentTrafficNetworkType == networkType
        ) {
            return NetworkObservationResult(
                changed = false,
                shouldPause = false,
                becameWifi = false,
                generation = networkGeneration
            )
        }
        val previousNetworkType = currentTrafficNetworkType
        val shouldPause = currentTrafficNetworkType == TrafficNetworkType.WIFI &&
            networkType != TrafficNetworkType.WIFI &&
            !wifiLossHandled
        currentDefaultNetworkKey = networkKey
        currentTrafficNetworkType = networkType
        networkGeneration += 1L
        if (networkType == TrafficNetworkType.WIFI) {
            wifiLossHandled = false
        } else if (shouldPause) {
            wifiLossHandled = true
        }
        return NetworkObservationResult(
            changed = true,
            shouldPause = shouldPause,
            becameWifi = networkType == TrafficNetworkType.WIFI &&
                previousNetworkType != TrafficNetworkType.WIFI,
            generation = networkGeneration
        )
    }

    @Synchronized
    fun onDefaultNetworkLost(networkKey: Any): Boolean {
        return onDefaultNetworkLost(
            networkKey = networkKey,
            activeNetworkKey = null,
            activeNetworkKnown = true
        )
    }

    @Synchronized
    fun onDefaultNetworkLost(
        networkKey: Any,
        activeNetworkKey: Any?,
        activeNetworkKnown: Boolean = true
    ): Boolean {
        if (currentDefaultNetworkKey != networkKey) {
            return false
        }
        if (!activeNetworkKnown || (activeNetworkKey != null && activeNetworkKey == networkKey)) {
            return false
        }
        val wasWifi = currentTrafficNetworkType == TrafficNetworkType.WIFI
        currentDefaultNetworkKey = null
        // a new WIFI Network object must be treated as a real recovery even
        // when the old network was also WIFI
        currentTrafficNetworkType = null
        networkGeneration += 1L
        return wasWifi && !wifiLossHandled
    }

    @Synchronized
    fun markWifiLossHandled() {
        wifiLossHandled = true
    }

    @Synchronized
    fun currentGeneration(): Long = networkGeneration
}
