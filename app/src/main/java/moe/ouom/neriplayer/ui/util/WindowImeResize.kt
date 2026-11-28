package moe.ouom.neriplayer.ui.util

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * 页面停留期间让窗口改用 adjustResize, 由 Compose 布局按 IME inset 让位
 *
 * 主窗口未声明 windowSoftInputMode, 系统对 Compose 窗口选了 adjustPan;
 * 输入框落在屏幕下半部时整窗会被推走(且推过头), 顶栏和列表一起移出屏幕
 */
@Composable
internal fun ResizeWindowForImeEffect(enabled: Boolean) {
    val window = LocalActivity.current?.window
    DisposableEffect(window, enabled) {
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        WindowImeResizeRegistry.acquire(window)
        onDispose { WindowImeResizeRegistry.release(window) }
    }
}

private object WindowImeResizeRegistry {
    // 只在主线程的 Effect 中访问
    private val overrides = WeakHashMap<Window, SoftInputAdjustOverride>()

    fun acquire(window: Window) {
        val override = overrides.getOrPut(window) { SoftInputAdjustOverride() }
        override.acquire(window.attributes.softInputMode)?.let(window::setSoftInputMode)
    }

    fun release(window: Window) {
        val override = overrides[window] ?: return
        override.release(window.attributes.softInputMode)?.let(window::setSoftInputMode)
        if (!override.active) overrides.remove(window)
    }
}

/**
 * 转场时新旧页面会同时在组合中, 按引用计数只在第一个进入时切换, 最后一个离开时还原
 * 还原只改 adjust 位, 保留期间系统写入的 state 和导航标记
 */
internal class SoftInputAdjustOverride {
    private var holders = 0
    private var savedAdjust = 0

    val active: Boolean get() = holders > 0

    /** 返回需要写回窗口的新模式, 已经生效时返回 null */
    fun acquire(currentMode: Int): Int? {
        holders++
        if (holders > 1) return null
        savedAdjust = currentMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        return withAdjust(currentMode, ADJUST_RESIZE)
    }

    /** 返回需要写回窗口的还原模式, 仍有页面持有时返回 null */
    fun release(currentMode: Int): Int? {
        if (holders == 0) return null
        holders--
        if (holders > 0) return null
        return withAdjust(currentMode, restorableAdjust(savedAdjust))
    }

    private fun withAdjust(mode: Int, adjust: Int): Int =
        (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or adjust

    private companion object {
        // ViewRootImpl 收到 unspecified 会沿用上一次的 adjust 位, 写回原值等于停留在 resize;
        // 没有 View 滚动容器的 Compose 窗口原本由系统解析为 pan, 这里显式还原成它
        fun restorableAdjust(saved: Int): Int =
            if (saved == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            } else {
                saved
            }

        // API 30 起改用 inset 回调替代窗口缩放而标记弃用; 但 edge-to-edge 下它正是"不平移, 只分发 IME inset",
        // 且 API 28/29 只有这个模式才会把 IME inset 交给 Compose
        @Suppress("DEPRECATION")
        const val ADJUST_RESIZE = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
}
