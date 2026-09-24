package moe.ouom.neriplayer.core.startup.app

import android.os.Build
import moe.ouom.neriplayer.shouldTrimUidtPendingJobs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessClassifierTest {
    @Test
    fun `matches configured main process`() {
        assertTrue(
            AppProcessClassifier.isMainProcess(
                currentProcessName = "moe.ouom.neriplayer",
                configuredMainProcessName = "moe.ouom.neriplayer",
                packageName = "moe.ouom.neriplayer"
            )
        )
    }

    @Test
    fun `falls back to package name when configured process is blank`() {
        assertTrue(
            AppProcessClassifier.isMainProcess(
                currentProcessName = "moe.ouom.neriplayer",
                configuredMainProcessName = "",
                packageName = "moe.ouom.neriplayer"
            )
        )
    }

    @Test
    fun `detects secondary process`() {
        assertFalse(
            AppProcessClassifier.isMainProcess(
                currentProcessName = "moe.ouom.neriplayer:web_login",
                configuredMainProcessName = "moe.ouom.neriplayer",
                packageName = "moe.ouom.neriplayer"
            )
        )
    }

    @Test
    fun `trims UIDT jobs only in the main process on supported Android`() {
        assertTrue(
            shouldTrimUidtPendingJobs(
                runningInMainProcess = true,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            )
        )
        assertFalse(
            shouldTrimUidtPendingJobs(
                runningInMainProcess = false,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            )
        )
        assertFalse(
            shouldTrimUidtPendingJobs(
                runningInMainProcess = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU
            )
        )
    }
}
