package moe.ouom.neriplayer.util

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * 仅对手机锁定竖屏，平板等大屏设备保持系统默认方向。
 */
fun Activity.lockPortraitIfPhone() {
    val isPhone = resources.configuration.smallestScreenWidthDp < 600
    if (isPhone) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
