package moe.ouom.neriplayer.core.player.usb.session

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import moe.ouom.neriplayer.common.logging.NPLogger

private const val LEASE_TIMEOUT_MS = 10L * 60L * 1000L
private const val LEASE_RENEW_AFTER_MS = LEASE_TIMEOUT_MS / 2

/** 保活每秒调用一次；租约过半才续期，剩余保护时间始终超过 5 分钟，又省去每秒一次的 binder 调用 */
internal fun shouldRenewUsbExclusiveWakeLockLease(held: Boolean, elapsedSinceRenewMs: Long): Boolean =
    !held || elapsedSinceRenewMs !in 0 until LEASE_RENEW_AFTER_MS

internal object UsbExclusiveWakeLock {
    private const val TAG = "NERI-UsbWakeLock"
    private const val LOCK_TAG = "NeriPlayer:UsbExclusivePlayback"
    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var leaseAcquiredAtMs = 0L

    fun acquire(
        context: Context,
        reason: String,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ) {
        synchronized(lock) {
            val playbackWakeLock = wakeLock ?: runCatching { createWakeLock(context) }
                .onFailure { error -> NPLogger.w(TAG, "create failed reason=$reason", error) }
                .getOrNull()
                ?.also { wakeLock = it }
                ?: return
            val alreadyHeld = runCatching { playbackWakeLock.isHeld }.getOrDefault(false)
            if (!shouldRenewUsbExclusiveWakeLockLease(alreadyHeld, nowElapsedMs - leaseAcquiredAtMs)) return
            runCatching { playbackWakeLock.acquire(LEASE_TIMEOUT_MS) }
                .onSuccess {
                    leaseAcquiredAtMs = nowElapsedMs
                    if (!alreadyHeld) {
                        NPLogger.d(TAG, "acquired reason=$reason timeoutMs=$LEASE_TIMEOUT_MS")
                    }
                }
                .onFailure { error -> NPLogger.w(TAG, "acquire failed reason=$reason", error) }
        }
    }

    fun release(reason: String) {
        synchronized(lock) {
            val playbackWakeLock = wakeLock ?: return
            if (!runCatching { playbackWakeLock.isHeld }.getOrDefault(false)) return
            runCatching { playbackWakeLock.release() }
                .onSuccess { NPLogger.d(TAG, "released reason=$reason") }
                .onFailure { error -> NPLogger.w(TAG, "release failed reason=$reason", error) }
        }
    }

    fun isHeld(): Boolean {
        return synchronized(lock) {
            wakeLock?.let { runCatching { it.isHeld }.getOrDefault(false) } == true
        }
    }

    private fun createWakeLock(context: Context): PowerManager.WakeLock {
        val powerManager = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }
}
