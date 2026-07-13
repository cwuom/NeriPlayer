package moe.ouom.neriplayer.core.startup.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupLogInitializerTest {
    @Test
    fun `enables file logging when dev mode is enabled`() {
        assertTrue(
            StartupLogInitializer.shouldEnableFileLogging(
                devModeEnabled = true,
                alwaysRecordLogsEnabled = false
            )
        )
    }

    @Test
    fun `enables file logging when always record logs is enabled`() {
        assertTrue(
            StartupLogInitializer.shouldEnableFileLogging(
                devModeEnabled = false,
                alwaysRecordLogsEnabled = true
            )
        )
    }

    @Test
    fun `keeps file logging disabled when both switches are off`() {
        assertFalse(
            StartupLogInitializer.shouldEnableFileLogging(
                devModeEnabled = false,
                alwaysRecordLogsEnabled = false
            )
        )
    }
}
