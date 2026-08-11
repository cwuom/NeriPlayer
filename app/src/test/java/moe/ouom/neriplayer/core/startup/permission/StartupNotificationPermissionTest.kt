package moe.ouom.neriplayer.core.startup.permission

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupNotificationPermissionTest {
    @Test
    fun `permission name matches Android notification permission`() {
        assertEquals(
            "android.permission.POST_NOTIFICATIONS",
            StartupNotificationPermission.permission
        )
    }

    @Test
    fun `does not support notification permission before Android 13`() {
        assertFalse(
            StartupNotificationPermission.isSupported(
                sdkInt = Build.VERSION_CODES.TIRAMISU - 1
            )
        )
        assertFalse(StartupNotificationPermission.isSupported(sdkInt = Build.VERSION_CODES.Q))
    }

    @Test
    fun `supports notification permission on Android 13 and above`() {
        assertTrue(
            StartupNotificationPermission.isSupported(
                sdkInt = Build.VERSION_CODES.TIRAMISU
            )
        )
    }
}
