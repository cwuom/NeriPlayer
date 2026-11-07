package moe.ouom.neriplayer.lanltw.session

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** Locks supplement an existing playback service; they do not create a foreground service. */
class LanKeepAlive(context: Context) {
    private val app = context.applicationContext
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Suppress("DEPRECATION", "WakelockTimeout")
    fun acquire() {
        release()
        try {
            cpu = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeriPlayer:LanSession").apply { setReferenceCounted(false); acquire(15 * 60_000L) }
            wifi = app.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NeriPlayer:LanSession").apply { setReferenceCounted(false); acquire() }
        } catch (error: Exception) { release(); throw error }
    }
    fun renew() { cpu?.acquire(15 * 60_000L) }
    fun release() { cpu?.let { if (it.isHeld) it.release() }; wifi?.let { if (it.isHeld) it.release() }; cpu = null; wifi = null }
}
