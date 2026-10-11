package moe.ouom.neriplayer.core.player.service.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import moe.ouom.neriplayer.common.logging.NPLogger

internal fun screenInteractiveForAction(action: String?): Boolean? = when (action) {
    Intent.ACTION_SCREEN_ON -> true
    Intent.ACTION_SCREEN_OFF -> false
    else -> null
}

/** 跟踪屏幕是否可交互, 让熄屏期间只服务不可见输出的刷新降频 */
internal class ScreenInteractiveMonitor(
    private val context: Context,
    private val onChanged: (Boolean) -> Unit,
) {
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        val powerManager = context.getSystemService(PowerManager::class.java)
        onChanged(powerManager?.isInteractive ?: true)
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenInteractiveForAction(intent.action)?.let(onChanged)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = screenReceiver
    }

    fun stop() {
        val registered = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(registered) }
            .onFailure { NPLogger.w("NERI-APS", "unregister screen state receiver failed", it) }
    }
}
