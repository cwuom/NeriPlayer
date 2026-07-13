package moe.ouom.neriplayer.core.startup.permission

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupNotificationPermissionTest {
    @Test
    fun `does not request notification permission before Android 13`() {
        assertFalse(
            StartupNotificationPermission.shouldRequest(
                sdkInt = Build.VERSION_CODES.TIRAMISU - 1
            )
        )
    }

    @Test
    fun `requests notification permission on Android 13 and above`() {
        assertTrue(
            StartupNotificationPermission.shouldRequest(
                sdkInt = Build.VERSION_CODES.TIRAMISU
            )
        )
    }
}
