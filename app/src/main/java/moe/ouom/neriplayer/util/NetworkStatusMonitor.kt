package moe.ouom.neriplayer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType

@Composable
fun rememberOfflineModeState(): State<Boolean> {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val offlineState = remember(appContext) {
        mutableStateOf(!appContext.hasLikelyInternetAccess())
    }

    DisposableEffect(appContext) {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            offlineState.value = true
            onDispose { }
        } else {
            var disposed = false

            fun updateOfflineState() {
                val nextOffline = !connectivityManager.hasLikelyInternetAccess()
                if (disposed) return

                if (Looper.myLooper() == Looper.getMainLooper()) {
                    offlineState.value = nextOffline
                } else {
                    mainHandler.post {
                        if (!disposed) {
                            offlineState.value = nextOffline
                        }
                    }
                }
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateOfflineState()
                }

                override fun onLost(network: Network) {
                    updateOfflineState()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    updateOfflineState()
                }
            }

            updateOfflineState()
            val registered = runCatching {
                connectivityManager.registerDefaultNetworkCallback(callback)
                true
            }.getOrDefault(false)

            onDispose {
                disposed = true
                if (registered) {
                    runCatching {
                        connectivityManager.unregisterNetworkCallback(callback)
                    }
                }
            }
        }
    }

    return offlineState
}

fun Context.hasLikelyInternetAccess(): Boolean {
    val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
    return connectivityManager.hasLikelyInternetAccess()
}

fun Context.isOfflineModeNow(): Boolean = !hasLikelyInternetAccess()

fun Context.currentTrafficNetworkType(): TrafficNetworkType {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ?: return TrafficNetworkType.MOBILE
    return connectivityManager.currentTrafficNetworkType()
}

fun Context.isTrafficRiskNetworkNow(): Boolean {
    return when (currentTrafficNetworkType()) {
        TrafficNetworkType.MOBILE,
        TrafficNetworkType.ROAMING -> true
        TrafficNetworkType.WIFI -> false
    }
}

private fun ConnectivityManager.hasLikelyInternetAccess(): Boolean = runCatching {
    activeNetwork?.let { network ->
        if (hasInternetTransport(network)) {
            return@runCatching true
        }
    }
    anyKnownNetworkHasInternetTransport()
}.getOrDefault(false)

@Suppress("DEPRECATION")
private fun ConnectivityManager.anyKnownNetworkHasInternetTransport(): Boolean {
    // 有些系统会短暂不给 activeNetwork，这里保守兜底避免误进脱机模式
    return allNetworks.any { network -> hasInternetTransport(network) }
}

private fun ConnectivityManager.hasInternetTransport(network: Network): Boolean {
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasOnlineTransport()
}

private fun NetworkCapabilities.hasOnlineTransport(): Boolean {
    return hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

private fun ConnectivityManager.currentTrafficNetworkType(): TrafficNetworkType = runCatching {
    val activeNetwork = activeNetwork ?: return@runCatching TrafficNetworkType.MOBILE
    val capabilities = getNetworkCapabilities(activeNetwork)
        ?: return@runCatching TrafficNetworkType.MOBILE

    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        val isNotRoaming = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        return@runCatching if (isNotRoaming) {
            TrafficNetworkType.MOBILE
        } else {
            TrafficNetworkType.ROAMING
        }
    }

    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return@runCatching TrafficNetworkType.WIFI
    }

    TrafficNetworkType.MOBILE
}.getOrDefault(TrafficNetworkType.MOBILE)
