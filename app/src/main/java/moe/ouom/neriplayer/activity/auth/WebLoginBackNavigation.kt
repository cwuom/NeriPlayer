package moe.ouom.neriplayer.activity.auth

import androidx.activity.OnBackPressedCallback

/**
 * 网页还能后退时才拦截返回；退到第一页后关闭回调，交给系统关闭页面并播放预测性返回动画
 *
 * 网页历史变化后调用 [refresh]，让回调状态跟上当前页面
 */
internal class WebLoginBackNavigation(
    private val canGoBack: () -> Boolean,
    private val goBack: () -> Unit
) {
    val callback: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (canGoBack()) goBack()
            refresh()
        }
    }

    fun refresh() {
        callback.isEnabled = canGoBack()
    }
}
