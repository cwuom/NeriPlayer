package moe.ouom.neriplayer.core.player.dlna.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.dlna.server.DlnaLanAddressResolver
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Manages Android lifecycle locks & LAN connectivity observation for DLNA discovery and casting:
 * - WifiManager.MulticastLock during SSDP discovery
 * - WifiManager.WifiLock + PowerManager.WakeLock during active DLNA casting
 * - ConnectivityManager.NetworkCallback for Wi-Fi/LAN changes and drop recovery
 */
internal class DlnaNetworkAndLockController(
    context: Context,
    private val onLanNetworkAvailable: () -> Unit,
    private val onLanNetworkLost: (Network?) -> Unit
) {
    companion object {
        private const val TAG = "NERI-DlnaLocks"
        private const val WAKE_LOCK_LEASE_TIMEOUT_MS = 10L * 60L * 1000L
        private const val WAKE_LOCK_RENEW_AFTER_MS = 3L * 60L * 1000L
    }

    private val appContext = context.applicationContext ?: context
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockLeaseAcquiredAtMs = 0L
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
    fun acquireCastLocks(forceRenew: Boolean = false) {
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
            if (pLock != null) {
                val nowMs = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
                    .takeIf { it > 0L } ?: (System.nanoTime() / 1_000_000L)
                val alreadyHeld = pLock.isHeld
                val needsRenew = forceRenew ||
                    !alreadyHeld ||
                    (nowMs - wakeLockLeaseAcquiredAtMs) !in 0 until WAKE_LOCK_RENEW_AFTER_MS
                if (needsRenew) {
                    // Timed lease periodically renewed in background so OEM WakeLock watchdogs
                    // (e.g. Samsung One UI MARs/Freecess) treat it as active rather than leaked.
                    pLock.acquire(WAKE_LOCK_LEASE_TIMEOUT_MS)
                    wakeLockLeaseAcquiredAtMs = nowMs
                }
            }
        }.onFailure { err ->
            NPLogger.w(TAG, "Failed to acquire WakeLock: ${err.message}")
        }
    }

    @Synchronized
    fun releaseCastLocks() {
        wakeLockLeaseAcquiredAtMs = 0L
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
    fun isCastWakeLockHeld(): Boolean =
        runCatching { wakeLock?.isHeld == true }.getOrDefault(false)

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
                    onLanNetworkLost(if (allLost) null else network)
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

    /**
     * Resolves the Wi-Fi / Ethernet [Network] carrying [targetHost] so UPnP sockets stay pinned
     * to LAN even when OEM "Intelligent Wi-Fi" shifts the default network to cellular (5G) while locked.
     */
    fun resolveLanNetworkForHost(targetHost: String?): Network? {
        val cm = connectivityManager ?: return null
        val candidates = LinkedHashSet<Network>()
        synchronized(this) {
            candidates.addAll(activeLanNetworks)
        }
        @Suppress("DEPRECATION")
        runCatching { cm.allNetworks }.getOrNull().orEmpty().forEach { net ->
            val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: return@forEach
            val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (isLan && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                candidates.add(net)
            }
        }
        if (candidates.isEmpty()) return null

        val targetIpv4 = targetHost?.trim()?.takeIf { it.isNotEmpty() }?.let { host ->
            runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull()
        }

        var bestSubnetNetwork: Network? = null
        var bestPrefix = -1
        val validLanNetworks = ArrayList<Network>(candidates.size)

        for (network in candidates) {
            val linkProps = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
            val ifaceName = linkProps.interfaceName.orEmpty()
            if (ifaceName.isNotEmpty() && DlnaLanAddressResolver.isNonLanTunnelOrCellularInterface(ifaceName)) {
                continue
            }
            var hasUsableIpv4 = false
            for (linkAddr in linkProps.linkAddresses) {
                val ipv4 = linkAddr.address as? Inet4Address ?: continue
                if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) continue
                hasUsableIpv4 = true
                if (targetIpv4 != null) {
                    val prefix = linkAddr.prefixLength.coerceIn(1, 32)
                    if (DlnaLanAddressResolver.sameIpv4Subnet(ipv4.address, targetIpv4.address, prefix) &&
                        prefix > bestPrefix
                    ) {
                        bestPrefix = prefix
                        bestSubnetNetwork = network
                    }
                }
            }
            if (hasUsableIpv4) {
                validLanNetworks.add(network)
            }
        }
        if (bestSubnetNetwork != null) return bestSubnetNetwork
        if (targetIpv4 != null) return validLanNetworks.firstOrNull()
        return validLanNetworks.singleOrNull()
    }

    fun createLanSocketFactory(targetHostProvider: () -> String?): SocketFactory =
        object : SocketFactory() {
            private fun resolveFactory(explicitHost: String? = null): SocketFactory =
                runCatching {
                    resolveLanNetworkForHost(explicitHost ?: targetHostProvider())?.socketFactory
                }.getOrNull() ?: getDefault()

            override fun createSocket(): Socket =
                runCatching { resolveFactory().createSocket() }
                    .getOrElse { getDefault().createSocket() }

            override fun createSocket(host: String?, port: Int): Socket =
                runCatching { resolveFactory(host).createSocket(host, port) }
                    .getOrElse { getDefault().createSocket(host, port) }

            override fun createSocket(
                host: String?,
                port: Int,
                localHost: InetAddress?,
                localPort: Int
            ): Socket =
                runCatching { resolveFactory(host).createSocket(host, port, localHost, localPort) }
                    .getOrElse { getDefault().createSocket(host, port, localHost, localPort) }

            override fun createSocket(host: InetAddress?, port: Int): Socket =
                runCatching { resolveFactory(host?.hostAddress).createSocket(host, port) }
                    .getOrElse { getDefault().createSocket(host, port) }

            override fun createSocket(
                address: InetAddress?,
                port: Int,
                localAddress: InetAddress?,
                localPort: Int
            ): Socket =
                runCatching {
                    resolveFactory(address?.hostAddress).createSocket(address, port, localAddress, localPort)
                }.getOrElse { getDefault().createSocket(address, port, localAddress, localPort) }
        }

    fun releaseAll() {
        releaseMulticastLock()
        releaseCastLocks()
        unregisterNetworkCallback()
    }
}
