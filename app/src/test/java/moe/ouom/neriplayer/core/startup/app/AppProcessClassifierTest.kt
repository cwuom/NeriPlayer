package moe.ouom.neriplayer.core.startup.app

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
}
