package moe.ouom.neriplayer.activity

import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowInsetsController
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt

/** 窗口底色跟随主题，系统栏保持透明且不叠加系统对比度遮罩，浅色模式下手势条才能沉浸 */
internal fun Window.applyMainWindowBackground(isDark: Boolean) {
    val bgColor = if (isDark) "#121212".toColorInt() else Color.WHITE
    setBackgroundDrawable(bgColor.toDrawable())
    applyNeriSystemBarTransparency()
}

internal fun Window.applyMainWindowCaptionAppearance(useLightSystemBarIcons: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val mask = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
            WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        val appearance = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
            (if (useLightSystemBarIcons) 0 else WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS)
        insetsController?.setSystemBarsAppearance(appearance, mask)
    }
}
