package moe.ouom.neriplayer.core.startup.permission

import android.Manifest
import android.os.Build

internal object StartupNotificationPermission {
    val permission: String
        get() = Manifest.permission.POST_NOTIFICATIONS

    fun shouldRequest(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU
    }
}
