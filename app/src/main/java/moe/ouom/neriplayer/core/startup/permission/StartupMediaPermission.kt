package moe.ouom.neriplayer.core.startup.permission

import android.os.Build

internal object StartupMediaPermission {
    fun permissionFor(sdkInt: Int = Build.VERSION.SDK_INT): String {
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            READ_MEDIA_AUDIO_PERMISSION
        } else {
            READ_EXTERNAL_STORAGE_PERMISSION
        }
    }

    private const val READ_MEDIA_AUDIO_PERMISSION = "android.permission.READ_MEDIA_AUDIO"
    private const val READ_EXTERNAL_STORAGE_PERMISSION = "android.permission.READ_EXTERNAL_STORAGE"
}
