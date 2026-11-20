package moe.ouom.neriplayer.core.player.dlna.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import moe.ouom.neriplayer.common.logging.NPLogger

/**
 * Manages Android lifecycle locks & LAN connectivity observation for DLNA discovery and casting:
 * - WifiManager.MulticastLock during SSDP discovery
 * - WifiManager.WifiLock + PowerManager.WakeLock during active DLNA casting
 * - ConnectivityManager.NetworkCallback for Wi-Fi/LAN changes and drop recovery
 */
internal class DlnaNetworkAndLockController(
    context: Context,
    private val onLanNetworkAvailable: () -> Unit,
    private val onLanNetworkLost: () -> Unit
) {
    companion object {
        private const val TAG = "NERI-DlnaLocks"
    }

    private val appContext = context.applicationContext ?: context
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val activeLanNetworks = LinkedHashSet<Network>()

    @Synchronized
    fun acquireMulticastLock() {
        runCatching {
            val lock = multicastLock ?: wifiManager?.createMulticastLock("NeriPlayer:DlnaSsdp")?.apply {
                setReferenceCounted(false)
            }?.also { multicastLock = it }
            if (lock != null && !lock.isHeld) {
                lock.acquire()
            }
        }.onFailure { err ->
            NPLogger.w(TAG, "Failed to acquire MulticastLock: ${err.message}")
        }
    }

    @Synchronized
    fun releaseMulticastLock() {
        runCatching {
            multicastLock?.let { if (it.isHeld) it.release() }
        }.onFailure { err ->
            NPLogger.w(TAG, "Failed to release MulticastLock: ${err.message}")
        }
    }

    @Synchronized
    fun acquireCastLocks() {
        runCatching {
            val wLock = wifiLock ?: wifiManager?.let { manager ->
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                manager.createWifiLock(mode, "NeriPlayer:DlnaCast").apply {
                    setReferenceCounted(false)
                }
            }?.also { wifiLock = it }
            if (wLock != null && !wLock.isHeld) {
                wLock.acquire()
            }
        }.onFailure { err ->
            NPLogger.w(TAG, "Failed to acquire WifiLock: ${err.message}")
        }

        runCatching {
            val pLock = wakeLock ?: powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NeriPlayer:DlnaCastWakeLock"
            )?.apply {
                setReferenceCounted(false)
            }?.also { wakeLock = it }
            if (pLock != null && !pLock.isHeld) {
                pLock.acquire(4 * 60 * 60 * 1000L) // bounded 4h lease renewed if needed
            }
        }.onFailure { err ->
            NPLogger.w(TAG, "Failed to acquire WakeLock: ${err.message}")
        }
    }

    @Synchronized
    fun releaseCastLocks() {
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
        }.onFailure { err ->
            NPLogger.w(TAG, "Failed to release WifiLock: ${err.message}")
        }
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure { err ->
            NPLogger.w(TAG, "Failed to release WakeLock: ${err.message}")
        }
    }

    @Synchronized
    fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = connectivityManager ?: return
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val becameAvailable = synchronized(this@DlnaNetworkAndLockController) {
                        val wasEmpty = activeLanNetworks.isEmpty()
                        activeLanNetworks.add(network)
                        wasEmpty
                    }
                    if (becameAvailable) {
                        onLanNetworkAvailable()
                    }
                }

                override fun onLost(network: Network) {
                    val allLost = synchronized(this@DlnaNetworkAndLockController) {
                        activeLanNetworks.remove(network)
                        activeLanNetworks.isEmpty()
                    }
                    if (allLost) {
                        onLanNetworkLost()
                    }
                }
            }
            networkCallback = cb
            cm.registerNetworkCallback(request, cb)
        }.onFailure { err ->
            networkCallback = null
            NPLogger.w(TAG, "Failed to register NetworkCallback: ${err.message}")
        }
    }

    @Synchronized
    fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        activeLanNetworks.clear()
        runCatching {
            connectivityManager?.unregisterNetworkCallback(cb)
        }
    }

    fun releaseAll() {
        releaseMulticastLock()
        releaseCastLocks()
        unregisterNetworkCallback()
    }
}
