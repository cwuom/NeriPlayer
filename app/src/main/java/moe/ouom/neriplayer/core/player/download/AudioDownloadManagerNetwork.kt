package moe.ouom.neriplayer.core.player.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.TrafficUsageSource
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType
import moe.ouom.neriplayer.data.traffic.downloadNetworkTypeOrNull
import moe.ouom.neriplayer.data.traffic.hasConfirmedInternetAccess

internal fun AudioDownloadManager.newDownloadTrafficAccumulator(): TrafficByteAccumulator {
    val appContext = AppContainer.applicationContext
    val networkType = appContext.currentTrafficNetworkType()
    return TrafficByteAccumulator(DOWNLOAD_TRAFFIC_FLUSH_BYTES) { bytes ->
        AppContainer.trafficStatsRepo.recordNetworkBytes(
            networkType = networkType,
            bytes = bytes,
            source = TrafficUsageSource.DOWNLOAD
        )
    }
}

internal fun AudioDownloadManager.persistDownloadNetworkGeneration(context: Context, generation: Long) {
    val persisted = context.applicationContext
        .getSharedPreferences(DOWNLOAD_NETWORK_POLICY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(NETWORK_GENERATION_PREF, generation.coerceAtLeast(0L))
        .commit()
    if (!persisted) {
        NPLogger.w(TAG, "持久化下载网络代次失败: generation=$generation")
    }
}

internal fun AudioDownloadManager.shouldNotifyRecoveryForConfirmedInternet(context: Context): Boolean {
    val confirmed = context.hasConfirmedInternetAccess()
    synchronized(networkRecoveryMonitorLock) {
        val shouldNotify = shouldTriggerNetworkRecovery(
            wasConfirmed = lastConfirmedInternetAccess,
            isConfirmed = confirmed
        )
        lastConfirmedInternetAccess = confirmed
        return shouldNotify
    }
}

internal fun AudioDownloadManager.handleDefaultDownloadNetworkCallback(
    context: Context,
    connectivityManager: ConnectivityManager,
    callbackNetwork: Network,
    reason: String
) {
    val activeNetwork = runCatching { connectivityManager.activeNetwork }
        .getOrElse { error ->
            NPLogger.d(
                TAG,
                "忽略网络回调: 无法读取 activeNetwork, error=${error.message}"
            )
            return
    }
    if (activeNetwork != callbackNetwork) {
        NPLogger.d(
            TAG,
            "忽略过时网络回调: callback=$callbackNetwork, " +
                "active=$activeNetwork, reason=$reason"
        )
        // 回调可能在系统切换默认网络的窗口内到达，直接以当前 active
        // 快照收敛一次，避免旧 WIFI 事件把策略留在错误状态
        val currentNetwork = activeNetwork ?: return
        val currentType = runCatching {
            connectivityManager.getNetworkCapabilities(currentNetwork)
                ?.downloadNetworkTypeOrNull()
        }.getOrElse { error ->
            NPLogger.d(
                TAG,
                "忽略过时网络回调的 active 快照: 无法读取 capabilities, " +
                    "error=${error.message}"
            )
            return
        } ?: return
        handleDefaultDownloadNetworkObserved(
            context = context,
            network = currentNetwork,
            networkType = currentType,
            reason = "${reason}_active_snapshot",
            activeNetworkKnown = true
        )
        return
    }
    val activeType = runCatching {
        connectivityManager.getNetworkCapabilities(activeNetwork)
            ?.downloadNetworkTypeOrNull()
    }.getOrElse { error ->
        NPLogger.d(
            TAG,
            "忽略网络回调: 无法读取 capabilities, error=${error.message}"
        )
        return
    } ?: run {
        NPLogger.d(TAG, "忽略网络回调: active capabilities 尚未稳定, reason=$reason")
        return
    }
    handleDefaultDownloadNetworkObserved(
        context = context,
        network = callbackNetwork,
        networkType = activeType,
        reason = reason,
        activeNetworkKnown = true
    )
}

internal fun AudioDownloadManager.handleDefaultDownloadNetworkObserved(
    context: Context,
    network: Network,
    networkType: TrafficNetworkType,
    reason: String,
    activeNetworkKnown: Boolean = true
) {
    val observation = downloadNetworkPolicyTracker.observeDefaultNetwork(
        networkKey = network,
        networkType = networkType,
        activeNetworkKey = network,
        activeNetworkKnown = activeNetworkKnown
    )
    if (observation.changed) {
        persistDownloadNetworkGeneration(context, observation.generation)
    }
    if (observation.becameWifi) {
        GlobalDownloadManager.onWifiBoundDownloadNetworkRestored(
            context = context,
            reason = reason,
            networkGeneration = observation.generation
        )
        GlobalDownloadManager.scheduleWifiRecoveryProbe(
            context = context,
            reason = reason
        )
    }
    if (observation.shouldPause) {
        interruptDownloadsForWifiLoss(
            networkType = networkType,
            reason = reason,
            networkGeneration = observation.generation
        )
    }
}

internal fun AudioDownloadManager.handleDefaultDownloadNetworkLost(
    context: Context,
    connectivityManager: ConnectivityManager,
    network: Network
) {
    val activeNetworkSnapshot = runCatching { connectivityManager.activeNetwork }
        .getOrElse { error ->
            NPLogger.d(
                TAG,
                "忽略网络丢失回调: 无法读取 activeNetwork, error=${error.message}"
            )
            return
        }
    val shouldPause = downloadNetworkPolicyTracker.onDefaultNetworkLost(
        networkKey = network,
        activeNetworkKey = activeNetworkSnapshot,
        activeNetworkKnown = true
    )
    val networkGeneration = downloadNetworkPolicyTracker.currentGeneration()
    persistDownloadNetworkGeneration(
        context = context,
        generation = networkGeneration
    )
    val nextNetworkType = context.currentDownloadNetworkTypeOrNull()
    if (nextNetworkType == TrafficNetworkType.WIFI) {
        // onLost 可能和新的 WIFI 回调竞态，这里补一次恢复触发
        // 避免漏掉回调后等待中的下载一直停住
        GlobalDownloadManager.scheduleWifiRecoveryProbe(
            context = context,
            reason = "network_lost_replacement_wifi"
        )
        return
    }
    if (!shouldPause) {
        return
    }
    // activeNetwork 切换窗口里网络类型可能暂时为空。此时只暂停传输，
    // 等后续 MOBILE/ROAMING 回调确认后再消费 WIFI 丢失边沿并弹出流量提示
    if (nextNetworkType != null) {
        downloadNetworkPolicyTracker.markWifiLossHandled()
    }
    interruptDownloadsForWifiLoss(
        networkType = nextNetworkType,
        reason = "network_lost",
        networkGeneration = networkGeneration
    )
}

internal fun AudioDownloadManager.interruptDownloadsForWifiLoss(
    networkType: TrafficNetworkType?,
    reason: String,
    networkGeneration: Long? = null
) {
    if (networkType != TrafficNetworkType.WIFI) {
        NPLogger.w(
            TAG,
            "WIFI 下载环境已切换，准备中断下载: reason=$reason, " +
                "nextType=${networkType ?: "UNKNOWN"}"
        )
        GlobalDownloadManager.interruptDownloadsForWifiDisconnected(
            callbackNetworkType = networkType,
            networkGeneration = networkGeneration
        )
    }
}
