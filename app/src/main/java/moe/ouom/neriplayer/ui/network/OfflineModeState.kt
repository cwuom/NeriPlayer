package moe.ouom.neriplayer.ui.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.traffic.LikelyNetworkTransportAvailability
import moe.ouom.neriplayer.data.traffic.currentLikelyNetworkTransportAvailability
import moe.ouom.neriplayer.data.traffic.hasLikelyInternetAccess

private const val NETWORK_STATE_SETTLE_RECHECK_MS = 300L
private const val NETWORK_OFFLINE_CONFIRMATION_MS = 1_500L
private const val NETWORK_STATUS_LOG_TAG = "NERI-NetworkStatus"

private fun buildAllNetworkObservationRequest(): NetworkRequest {
    val builder = NetworkRequest.Builder()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return builder.clearCapabilities().build()
    }
    // API 28-29 lack clearCapabilities, so remove the constructor defaults that exclude VPN and restricted links
    return builder
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
}

internal enum class OfflineModeStateUpdate {
    SET_ONLINE,
    SET_OFFLINE,
    KEEP_CURRENT,
    CONFIRM_OFFLINE
}

internal fun resolveOfflineModeStateUpdate(
    currentlyOffline: Boolean,
    availability: LikelyNetworkTransportAvailability,
    isOfflineConfirmation: Boolean
): OfflineModeStateUpdate {
    return when (availability) {
        LikelyNetworkTransportAvailability.ONLINE -> OfflineModeStateUpdate.SET_ONLINE
        LikelyNetworkTransportAvailability.INDETERMINATE -> OfflineModeStateUpdate.KEEP_CURRENT
        LikelyNetworkTransportAvailability.OFFLINE -> {
            when {
                currentlyOffline -> OfflineModeStateUpdate.KEEP_CURRENT
                isOfflineConfirmation -> OfflineModeStateUpdate.SET_OFFLINE
                else -> OfflineModeStateUpdate.CONFIRM_OFFLINE
            }
        }
    }
}

internal fun shouldRefreshOfflineStateForLifecycleEvent(event: Lifecycle.Event): Boolean {
    return event == Lifecycle.Event.ON_RESUME
}

@Composable
fun rememberOfflineModeState(): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = remember(context) { context.applicationContext }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val offlineState = remember(appContext) {
        mutableStateOf(!appContext.hasLikelyInternetAccess())
    }

    DisposableEffect(appContext, lifecycleOwner) {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            onDispose { }
        } else {
            var disposed = false
            var pendingOfflineConfirmation: Runnable? = null
            var pendingSettleRecheck: Runnable? = null

            fun runOnMain(action: () -> Unit) {
                if (disposed) return
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    action()
                } else {
                    mainHandler.post {
                        if (!disposed) {
                            action()
                        }
                    }
                }
            }

            fun cancelPendingOfflineConfirmation() {
                pendingOfflineConfirmation?.let(mainHandler::removeCallbacks)
                pendingOfflineConfirmation = null
            }

            fun applyNetworkAvailability(
                availability: LikelyNetworkTransportAvailability,
                isOfflineConfirmation: Boolean
            ) {
                if (disposed) return
                val update = resolveOfflineModeStateUpdate(
                    currentlyOffline = offlineState.value,
                    availability = availability,
                    isOfflineConfirmation = isOfflineConfirmation
                )
                NPLogger.d(
                    NETWORK_STATUS_LOG_TAG,
                    "ui availability=$availability currentlyOffline=${offlineState.value} " +
                        "confirmation=$isOfflineConfirmation action=$update"
                )
                when (update) {
                    OfflineModeStateUpdate.SET_ONLINE -> {
                        cancelPendingOfflineConfirmation()
                        offlineState.value = false
                    }

                    OfflineModeStateUpdate.SET_OFFLINE -> {
                        pendingOfflineConfirmation = null
                        offlineState.value = true
                    }

                    OfflineModeStateUpdate.CONFIRM_OFFLINE -> {
                        if (pendingOfflineConfirmation != null) return
                        val confirmation = Runnable {
                            pendingOfflineConfirmation = null
                            if (!disposed) {
                                applyNetworkAvailability(
                                    availability = appContext
                                        .currentLikelyNetworkTransportAvailability(),
                                    isOfflineConfirmation = true
                                )
                            }
                        }
                        pendingOfflineConfirmation = confirmation
                        mainHandler.postDelayed(confirmation, NETWORK_OFFLINE_CONFIRMATION_MS)
                    }

                    OfflineModeStateUpdate.KEEP_CURRENT -> Unit
                }
            }

            fun updateOfflineState(isOfflineConfirmation: Boolean = false) {
                val availability = appContext.currentLikelyNetworkTransportAvailability()
                runOnMain {
                    applyNetworkAvailability(availability, isOfflineConfirmation)
                }
            }

            fun updateOfflineStateAfterSettled() {
                runOnMain {
                    pendingSettleRecheck?.let(mainHandler::removeCallbacks)
                    val recheck = Runnable {
                        pendingSettleRecheck = null
                        if (!disposed) {
                            updateOfflineState()
                        }
                    }
                    pendingSettleRecheck = recheck
                    mainHandler.postDelayed(recheck, NETWORK_STATE_SETTLE_RECHECK_MS)
                }
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
            val lifecycleObserver = LifecycleEventObserver { _, event ->
                if (shouldRefreshOfflineStateForLifecycleEvent(event)) {
                    updateOfflineStateAfterSettled()
                }
            }

            updateOfflineState()
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            val registered = runCatching {
                connectivityManager.registerNetworkCallback(
                    buildAllNetworkObservationRequest(),
                    callback
                )
                true
            }.getOrElse {
                // a default-network fallback preserves ordinary updates if broad registration fails
                runCatching {
                    connectivityManager.registerDefaultNetworkCallback(callback)
                    true
                }.getOrDefault(false)
            }

            onDispose {
                disposed = true
                pendingOfflineConfirmation?.let(mainHandler::removeCallbacks)
                pendingSettleRecheck?.let(mainHandler::removeCallbacks)
                pendingOfflineConfirmation = null
                pendingSettleRecheck = null
                lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
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
