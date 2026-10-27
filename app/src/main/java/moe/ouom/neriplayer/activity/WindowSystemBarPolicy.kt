package moe.ouom.neriplayer.activity

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

internal fun ComponentActivity.enableNeriEdgeToEdge() {
    // Activity 的默认 auto 样式会在配置变化后重新打开系统对比度遮罩
    WindowCompat.enableEdgeToEdge(window)
    window.applyNeriSystemBarTransparency()
    val isDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !isDark
        isAppearanceLightNavigationBars = !isDark
    }
}

@Suppress("DEPRECATION")
internal fun Window.applyNeriSystemBarTransparency() {
    // 弹窗只恢复栏背景，保留各自的布局、图标和键盘处理
    if (statusBarColor != Color.TRANSPARENT) statusBarColor = Color.TRANSPARENT
    if (navigationBarColor != Color.TRANSPARENT) navigationBarColor = Color.TRANSPARENT
    if (navigationBarDividerColor != Color.TRANSPARENT) navigationBarDividerColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        if (isStatusBarContrastEnforced) isStatusBarContrastEnforced = false
        if (isNavigationBarContrastEnforced) isNavigationBarContrastEnforced = false
    }
}
