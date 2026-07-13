package moe.ouom.neriplayer.core.startup.permission

import android.os.Build

internal object StartupNotificationPermission {
    val permission: String
        get() = POST_NOTIFICATIONS_PERMISSION

    fun shouldRequest(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU
    }

    private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
}
