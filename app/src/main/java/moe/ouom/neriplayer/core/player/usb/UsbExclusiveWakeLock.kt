package moe.ouom.neriplayer.core.player.usb

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import moe.ouom.neriplayer.util.NPLogger

internal object UsbExclusiveWakeLock {
    private const val TAG = "NERI-UsbWakeLock"
    private const val LOCK_TAG = "NeriPlayer:UsbExclusivePlayback"
    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    fun acquire(context: Context, reason: String) {
        synchronized(lock) {
            val playbackWakeLock = wakeLock ?: runCatching { createWakeLock(context) }
                .onFailure { error -> NPLogger.w(TAG, "create failed reason=$reason", error) }
                .getOrNull()
                ?.also { wakeLock = it }
                ?: return
            if (runCatching { playbackWakeLock.isHeld }.getOrDefault(false)) return
            runCatching { playbackWakeLock.acquire() }
                .onSuccess { NPLogger.d(TAG, "acquired reason=$reason") }
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
