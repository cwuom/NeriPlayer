package moe.ouom.neriplayer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberOfflineModeState(): State<Boolean> {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val offlineState = remember(appContext) {
        mutableStateOf(!appContext.hasValidatedInternet())
    }

    DisposableEffect(appContext) {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            offlineState.value = true
            onDispose { }
        } else {
            var disposed = false

            fun updateOfflineState() {
                val nextOffline = !connectivityManager.hasValidatedInternet()
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

fun Context.hasValidatedInternet(): Boolean {
    val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
    return connectivityManager.hasValidatedInternet()
}

fun Context.isOfflineModeNow(): Boolean = !hasValidatedInternet()

private fun ConnectivityManager.hasValidatedInternet(): Boolean = runCatching {
    val activeNetwork = activeNetwork ?: return@runCatching false
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return@runCatching false
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(false)
