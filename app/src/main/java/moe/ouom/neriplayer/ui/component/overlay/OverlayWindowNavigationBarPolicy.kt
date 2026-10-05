package moe.ouom.neriplayer.ui.component.overlay

import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal val LocalOverlayNavigationBarHidden = compositionLocalOf { false }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApplyOverlayWindowNavigationBarPolicy() {
    val view = LocalView.current
    val window = remember(view) { view.findOverlayDialogWindow() } ?: return
    val hidden = LocalOverlayNavigationBarHidden.current
    val imeVisible = WindowInsets.isImeVisible
    val latestHidden = rememberUpdatedState(hidden)
    val latestImeVisible = rememberUpdatedState(imeVisible)
    val applyPolicy = remember(window) {
        Runnable {
            if (window.decorView.isAttachedToWindow && window.decorView.hasWindowFocus() &&
                !latestImeVisible.value
            ) {
                window.applyOverlayNavigationBarVisibility(latestHidden.value)
            }
        }
    }

    DisposableEffect(window, applyPolicy) {
        val decorView = window.decorView
        if (!latestImeVisible.value) {
            window.applyOverlayNavigationBarVisibility(latestHidden.value)
        }
        val observer = decorView.viewTreeObserver
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) decorView.post(applyPolicy)
        }
        observer.addOnWindowFocusChangeListener(focusListener)
        decorView.post(applyPolicy)
        onDispose {
            decorView.removeCallbacks(applyPolicy)
            val liveObserver = if (observer.isAlive) observer else decorView.viewTreeObserver
            if (liveObserver.isAlive) liveObserver.removeOnWindowFocusChangeListener(focusListener)
        }
    }

    // 使用 Compose 已有的 IME 观察，避免覆盖弹窗的 inset 分发监听
    LaunchedEffect(window, hidden, imeVisible) {
        if (!imeVisible) {
            withFrameNanos { }
            applyPolicy.run()
        }
    }
}

private fun View.findOverlayDialogWindow(): Window? {
    var current: View? = this
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent as? View
    }
    return null
}

private fun Window.applyOverlayNavigationBarVisibility(hidden: Boolean) {
    WindowInsetsControllerCompat(this, decorView).apply {
        systemBarsBehavior = if (hidden) {
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        if (hidden) hide(WindowInsetsCompat.Type.navigationBars())
        else show(WindowInsetsCompat.Type.navigationBars())
    }
}
