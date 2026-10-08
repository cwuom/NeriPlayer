package moe.ouom.neriplayer.core.player.policy.usb.keepalive

/** 只在后台音频锚点启动时调用：用户开启且已授予悬浮窗权限才挂保活窗口 */
internal fun shouldShowUsbExclusiveFloatingKeepAlive(
    preferenceEnabled: Boolean,
    overlayPermitted: Boolean
): Boolean = preferenceEnabled && overlayPermitted
