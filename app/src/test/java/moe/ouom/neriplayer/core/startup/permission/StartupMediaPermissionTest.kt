package moe.ouom.neriplayer.core.startup.permission

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupMediaPermissionTest {
    @Test
    fun `uses legacy media permission before Android 13`() {
        assertEquals(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            StartupMediaPermission.permissionFor(Build.VERSION_CODES.TIRAMISU - 1)
        )
    }

    @Test
    fun `uses audio media permission on Android 13 and above`() {
        assertEquals(
            Manifest.permission.READ_MEDIA_AUDIO,
            StartupMediaPermission.permissionFor(Build.VERSION_CODES.TIRAMISU)
        )
    }
}
