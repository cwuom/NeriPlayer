package moe.ouom.neriplayer.core.download.network

import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType

/** keeps callback ordering from turning one Wi-Fi loss into duplicate pauses */
class DownloadNetworkPolicyTracker {
    data class NetworkObservationResult(
        val changed: Boolean,
        val shouldPause: Boolean,
        val becameWifi: Boolean,
        val generation: Long
    )

    private var currentDefaultNetworkKey: Any? = null
    private var currentTrafficNetworkType: TrafficNetworkType? = null
    private var wifiLossHandled = false
    private var pendingWifiLoss = false
    private var networkGeneration = 0L

    @Synchronized
    fun seed(
        networkKey: Any?,
        networkType: TrafficNetworkType?,
        initialGeneration: Long = 0L
    ) {
        currentDefaultNetworkKey = networkKey
        currentTrafficNetworkType = networkType
        wifiLossHandled = false
        pendingWifiLoss = false
        networkGeneration = initialGeneration.coerceAtLeast(0L)
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
        if (!isCurrentObservation(networkKey, activeNetworkKey, activeNetworkKnown)) {
            return unchangedObservation()
        }
        if (isRepeatedObservation(networkKey, networkType)) return unchangedObservation()
        val previousNetworkType = currentTrafficNetworkType
        val shouldPause = shouldPauseForWifiLoss(networkType)
        currentDefaultNetworkKey = networkKey
        currentTrafficNetworkType = networkType
        networkGeneration += 1L
        updateWifiLossState(networkType, shouldPause)
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
        if (!canConfirmNetworkLoss(networkKey, activeNetworkKey, activeNetworkKnown)) return false
        val shouldPause = currentTrafficNetworkType == TrafficNetworkType.WIFI && !wifiLossHandled
        currentDefaultNetworkKey = null
        // a new WIFI Network object must be treated as a real recovery even
        // when the old network was also WIFI
        currentTrafficNetworkType = null
        if (shouldPause) {
            // 默认网络切换存在 activeNetwork 和 capabilities 都暂时为空的窗口
            // 先暂停传输，但保留 WIFI 丢失边沿，等移动网络确认后再触发流量提示
            pendingWifiLoss = true
        }
        networkGeneration += 1L
        return shouldPause
    }

    @Synchronized
    fun markWifiLossHandled() {
        wifiLossHandled = true
        pendingWifiLoss = false
    }

    @Synchronized
    fun currentGeneration(): Long = networkGeneration

    private fun isCurrentObservation(key: Any, activeKey: Any?, known: Boolean): Boolean =
        known && activeKey == key

    private fun isRepeatedObservation(key: Any, type: TrafficNetworkType): Boolean =
        currentDefaultNetworkKey == key && currentTrafficNetworkType == type

    private fun unchangedObservation() = NetworkObservationResult(
        changed = false, shouldPause = false, becameWifi = false, generation = networkGeneration
    )

    private fun shouldPauseForWifiLoss(type: TrafficNetworkType): Boolean =
        hadWifiLoss() && type != TrafficNetworkType.WIFI && !wifiLossHandled

    private fun hadWifiLoss(): Boolean =
        currentTrafficNetworkType == TrafficNetworkType.WIFI || pendingWifiLoss

    private fun updateWifiLossState(type: TrafficNetworkType, shouldPause: Boolean) {
        if (type == TrafficNetworkType.WIFI) {
            wifiLossHandled = false
            pendingWifiLoss = false
        } else if (shouldPause) {
            wifiLossHandled = true
            pendingWifiLoss = false
        }
    }

    private fun canConfirmNetworkLoss(key: Any, activeKey: Any?, known: Boolean): Boolean =
        known && !isStillActive(key, activeKey)

    private fun isStillActive(key: Any, activeKey: Any?): Boolean =
        activeKey != null && activeKey == key

}
