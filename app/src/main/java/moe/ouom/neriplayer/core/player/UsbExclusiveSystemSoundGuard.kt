package moe.ouom.neriplayer.core.player

import android.content.Context
import moe.ouom.neriplayer.util.NPLogger

/**
 * 记录独占会话生命周期，但不修改系统全局音量
 *
 * 全局静音会影响其他应用，而且截图、键盘等系统音效会反复触发回调
 */
internal object UsbExclusiveSystemSoundGuard {
    private const val TAG = "NERI-UsbSoundGuard"
    private val lock = Any()
    private var active = false

    fun activate(context: Context, reason: String) {
        val first = synchronized(lock) {
            val wasActive = active
            active = true
            !wasActive
        }
        NPLogger.i(
            TAG,
            "activate reason=$reason first=$first package=${context.applicationContext.packageName}"
        )
    }

    fun releaseWhenNativeIdle(context: Context, reason: String) {
        release(context, reason)
    }

    fun forceRelease(context: Context, reason: String) {
        release(context, reason)
    }

    private fun release(context: Context, reason: String) {
        val released = synchronized(lock) {
            if (!active) return
            active = false
            true
        }
        if (released) {
            NPLogger.i(
                TAG,
                "release reason=$reason package=${context.applicationContext.packageName}"
            )
        }
    }
}
