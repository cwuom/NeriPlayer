package moe.ouom.neriplayer.data.ltw.session.connection

import android.content.Context
import android.os.PowerManager
import moe.ouom.neriplayer.common.logging.NPLogger

internal class ListenTogetherBackgroundKeepAlive {
    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    fun renew(context: Context, reason: String) {
        synchronized(lock) {
            val currentWakeLock = obtainWakeLock(context, reason) ?: return
            runCatching {
                currentWakeLock.acquire(WAKE_LOCK_LEASE_MS)
            }.onFailure { error ->
                NPLogger.w(TAG, "renew failed reason=$reason", error)
            }
        }
    }

    private fun obtainWakeLock(context: Context, reason: String): PowerManager.WakeLock? {
        wakeLock?.let { return it }
        val manager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        val created = runCatching { manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply { setReferenceCounted(false) } }
            .onFailure { NPLogger.w(TAG, "create failed reason=$reason", it) }
            .getOrNull()
        wakeLock = created
        return created
    }

    fun release(reason: String) {
        synchronized(lock) {
            val currentWakeLock = wakeLock ?: return
            if (!runCatching { currentWakeLock.isHeld }.getOrDefault(false)) return
            runCatching { currentWakeLock.release() }
                .onFailure { error -> NPLogger.w(TAG, "release failed reason=$reason", error) }
        }
    }

    private companion object {
        const val TAG = "NERI-ListenTogetherKeepAlive"
        const val LOCK_TAG = "NeriPlayer:ListenTogether"
        const val WAKE_LOCK_LEASE_MS = 2L * 60L * 1000L
    }
}
