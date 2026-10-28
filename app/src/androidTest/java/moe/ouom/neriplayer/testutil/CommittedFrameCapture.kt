package moe.ouom.neriplayer.testutil

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.graphics.HardwareRendererCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// 成功返回的位图由调用方负责回收
@RequiresApi(Build.VERSION_CODES.Q)
internal fun ComposeContentTestRule.captureCommittedFrame(hostView: View, boundsInRoot: Rect): Bitmap {
    check(Looper.myLooper() != Looper.getMainLooper()) { "capture must run outside the UI thread" }
    val boundsInWindow = AndroidRect(
        boundsInRoot.left.roundToInt(), boundsInRoot.top.roundToInt(),
        boundsInRoot.right.roundToInt(), boundsInRoot.bottom.roundToInt()
    )
    require(!boundsInWindow.isEmpty) { "capture bounds must not be empty" }
    val frameCommitted = CountDownLatch(1)
    var registeredObserver: ViewTreeObserver? = null
    var frameCallback: Runnable? = null
    var frameRegisteredAt = 0L
    val wasDrawingEnabled = HardwareRendererCompat.isDrawingEnabled()
    try {
        if (!wasDrawingEnabled) HardwareRendererCompat.setDrawingEnabled(true)
        val window = runOnUiThread {
            check(hostView.isAttachedToWindow) { "capture host is detached" }
            check(hostView.isHardwareAccelerated) { "capture requires hardware rendering" }
            val window = hostView.activityWindow()
            val location = IntArray(2)
            hostView.getLocationInWindow(location)
            boundsInWindow.offset(location[0], location[1])
            val observer = hostView.viewTreeObserver
            val callback = Runnable {
                Log.i(CaptureLogTag, "frame commit elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - frameRegisteredAt)}")
                frameCommitted.countDown()
            }
            registeredObserver = observer
            frameCallback = callback
            observer.registerFrameCommitCallback(callback)
            frameRegisteredAt = System.nanoTime()
            hostView.invalidate()
            window
        }
        // 同步注册后再计提交预算，截图不能推进冻结的 Compose 时钟
        val remainingNanos = TimeUnit.MILLISECONDS.toNanos(FrameCommitTimeoutMs) - (System.nanoTime() - frameRegisteredAt)
        if (!frameCommitted.await(remainingNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)) {
            throw AssertionError("capture frame did not commit within $FrameCommitTimeoutMs ms")
        }
        return copyCommittedFrame(window, boundsInWindow)
    } finally {
        try {
            runOnUiThread {
                val observer = registeredObserver
                val callback = frameCallback
                if (observer != null && observer.isAlive && callback != null) {
                    observer.unregisterFrameCommitCallback(callback)
                }
            }
        } finally {
            if (!wasDrawingEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }
}

private fun copyCommittedFrame(window: Window, boundsInWindow: AndroidRect): Bitmap {
    val bitmap = Bitmap.createBitmap(boundsInWindow.width(), boundsInWindow.height(), Bitmap.Config.ARGB_8888)
    val copyFinished = CountDownLatch(1)
    val ownership = Any()
    var abandoned = false
    var completed = false
    var copyResult = PixelCopy.ERROR_UNKNOWN
    try {
        PixelCopy.request(window, boundsInWindow, bitmap, { result ->
            synchronized(ownership) {
                copyResult = result
                completed = true
                // 超时后仍可能有异步写入，等复制回调再回收目标
                if (abandoned) bitmap.recycle()
            }
            copyFinished.countDown()
        }, Handler(Looper.getMainLooper()))
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
    try {
        if (!copyFinished.await(PixelCopyTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw AssertionError("capture PixelCopy did not complete within $PixelCopyTimeoutMs ms")
        }
        if (copyResult != PixelCopy.SUCCESS) throw AssertionError("capture PixelCopy failed: $copyResult")
        return bitmap
    } catch (failure: Throwable) {
        synchronized(ownership) {
            abandoned = true
            if (completed) bitmap.recycle()
        }
        throw failure
    }
}

private fun View.activityWindow(): Window {
    var hostContext = context
    while (hostContext is ContextWrapper) {
        if (hostContext is Activity) return hostContext.window
        hostContext = hostContext.baseContext
    }
    error("capture host does not have an Activity window")
}

private const val CaptureLogTag = "CommittedFrameCapture"
private const val FrameCommitTimeoutMs = 5_000L
private const val PixelCopyTimeoutMs = 1_000L
