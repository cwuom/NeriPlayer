package moe.ouom.neriplayer.activity

import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt

/** 窗口底色跟随主题，系统栏保持透明且不叠加系统对比度遮罩，浅色模式下手势条才能沉浸 */
@Suppress("DEPRECATION")
internal fun Window.applyMainWindowBackground(isDark: Boolean) {
    val bgColor = if (isDark) "#121212".toColorInt() else Color.WHITE
    setBackgroundDrawable(bgColor.toDrawable())
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
}
