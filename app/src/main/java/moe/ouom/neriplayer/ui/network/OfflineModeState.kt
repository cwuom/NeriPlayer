package moe.ouom.neriplayer.ui.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import moe.ouom.neriplayer.data.traffic.hasLikelyInternetAccess

private const val NETWORK_STATE_SETTLE_RECHECK_MS = 300L

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
                val nextOffline = !appContext.hasLikelyInternetAccess()
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

            fun updateOfflineStateAfterSettled() {
                updateOfflineState()
                mainHandler.postDelayed(
                    {
                        if (!disposed) {
                            updateOfflineState()
                        }
                    },
                    NETWORK_STATE_SETTLE_RECHECK_MS
                )
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateOfflineStateAfterSettled()
                }

                override fun onLost(network: Network) {
                    updateOfflineStateAfterSettled()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    updateOfflineStateAfterSettled()
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
