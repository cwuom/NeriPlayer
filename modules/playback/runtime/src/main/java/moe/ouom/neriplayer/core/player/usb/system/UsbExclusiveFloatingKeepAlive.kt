package moe.ouom.neriplayer.core.player.usb.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import moe.ouom.neriplayer.common.logging.NPLogger

/**
 * 部分厂商的后台冻结策略会放过挂有悬浮窗的进程；USB 独占后台播放时挂一个
 * 1px 透明窗口降低息屏冻结导致的断流，窗口不可触摸也不抢焦点
 */
@SuppressLint("StaticFieldLeak")
internal object UsbExclusiveFloatingKeepAlive {
    private const val TAG = "NERI-UsbFloatingKeepAlive"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var view: View? = null

    @Volatile
    private var showing = false

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = showing

    fun update(context: Context, show: Boolean, reason: String) {
        val app = context.applicationContext
        runOnMain {
            if (show) attach(app, reason) else detach(reason)
        }
    }

    fun hide(reason: String) = runOnMain { detach(reason) }

    private fun attach(context: Context, reason: String) {
        if (view != null) return
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        val keepAliveView = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            title = "NeriPlayerUsbKeepAlive"
        }
        runCatching { manager.addView(keepAliveView, params) }
            .onSuccess {
                windowManager = manager
                view = keepAliveView
                showing = true
                NPLogger.i(TAG, "floating keepalive shown reason=$reason")
            }
            .onFailure { NPLogger.w(TAG, "floating keepalive add failed reason=$reason: ${it.message}") }
    }

    private fun detach(reason: String) {
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
        view = null
        windowManager = null
        showing = false
        NPLogger.i(TAG, "floating keepalive hidden reason=$reason")
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
