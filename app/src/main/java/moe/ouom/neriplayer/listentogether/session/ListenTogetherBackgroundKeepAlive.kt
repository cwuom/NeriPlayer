package moe.ouom.neriplayer.listentogether.session

import android.content.Context
import android.os.PowerManager
import moe.ouom.neriplayer.core.logging.NPLogger

internal object ListenTogetherBackgroundKeepAlive {
    private const val TAG = "NERI-ListenTogetherKeepAlive"
    private const val LOCK_TAG = "NeriPlayer:ListenTogether"
    private const val WAKE_LOCK_LEASE_MS = 2L * 60L * 1000L
    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    fun renew(context: Context, reason: String) {
        synchronized(lock) {
            val powerManager = context.applicationContext
                .getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return
            val currentWakeLock = wakeLock ?: runCatching {
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }.onFailure { error ->
                NPLogger.w(TAG, "create failed reason=$reason", error)
            }.getOrNull()?.also { wakeLock = it } ?: return
            runCatching {
                currentWakeLock.acquire(WAKE_LOCK_LEASE_MS)
            }.onFailure { error ->
                NPLogger.w(TAG, "renew failed reason=$reason", error)
            }
        }
    }

    fun release(reason: String) {
        synchronized(lock) {
            val currentWakeLock = wakeLock ?: return
            if (!runCatching { currentWakeLock.isHeld }.getOrDefault(false)) return
            runCatching { currentWakeLock.release() }
                .onFailure { error -> NPLogger.w(TAG, "release failed reason=$reason", error) }
        }
    }
}
