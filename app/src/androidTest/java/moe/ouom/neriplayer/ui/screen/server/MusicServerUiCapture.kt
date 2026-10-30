package moe.ouom.neriplayer.ui.screen.server

import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun ComposeTestRule.captureMusicServerUi(fileName: String, hostView: View) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = captureMusicServerBitmap(hostView)
    try {
        instrumentation.targetContext.cacheDir.resolve(fileName).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally {
        bitmap.recycle()
    }
}

// 像素断言也必须读取已提交的画面，位图由调用方负责回收
internal fun ComposeTestRule.captureMusicServerBitmap(hostView: View): Bitmap {
    waitForIdle()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        captureCommittedMusicServerScreen(hostView)
    } else {
        error("committed music server screenshots require Android 10 or later")
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun ComposeTestRule.captureCommittedMusicServerScreen(hostView: View): Bitmap {
    check(Looper.myLooper() != Looper.getMainLooper()) { "capture must run outside the UI thread" }
    val frameCommitted = CountDownLatch(1)
    var registeredObserver: ViewTreeObserver? = null
    var frameCallback: Runnable? = null
    var frameRegisteredAt = 0L
    val wasDrawingEnabled = HardwareRendererCompat.isDrawingEnabled()
    try {
        if (!wasDrawingEnabled) HardwareRendererCompat.setDrawingEnabled(true)
        runOnUiThread {
            val focusedRoot = WindowInspector.getGlobalWindowViews().lastOrNull {
                it.isAttachedToWindow && it.hasWindowFocus() && it.isShown
            } ?: hostView
            check(focusedRoot.isAttachedToWindow) { "capture host is detached" }
            check(focusedRoot.isHardwareAccelerated) { "capture requires hardware rendering" }
            val observer = focusedRoot.viewTreeObserver
            val callback = Runnable { frameCommitted.countDown() }
            registeredObserver = observer
            frameCallback = callback
            observer.registerFrameCommitCallback(callback)
            frameRegisteredAt = System.nanoTime()
            focusedRoot.invalidate()
        }
        // 只等待当前窗口提交，不依赖根 View 的 Context 是否持有 Activity
        val remainingNanos = TimeUnit.MILLISECONDS.toNanos(MusicServerFrameCommitTimeoutMs) -
            (System.nanoTime() - frameRegisteredAt)
        if (!frameCommitted.await(remainingNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)) {
            throw AssertionError("music server capture frame did not commit within $MusicServerFrameCommitTimeoutMs ms")
        }
        return checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
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

private const val MusicServerFrameCommitTimeoutMs = 5_000L
