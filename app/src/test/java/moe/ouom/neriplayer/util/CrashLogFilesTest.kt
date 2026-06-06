package moe.ouom.neriplayer.util

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogFilesTest {

    @Test
    fun buildCrashLogFileName_usesFallbackPrefix() {
        val fileName = CrashLogFiles.buildCrashLogFileName(
            prefix = "   ",
            nowMillis = 1_700_000_000_000L,
            pid = 42
        )

        assertTrue(fileName.startsWith("crash_"))
        assertTrue(fileName.endsWith("_p42.txt"))
    }

    @Test
    fun buildCrashLogFileName_keepsRequestedPrefix() {
        val fileName = CrashLogFiles.buildCrashLogFileName(
            prefix = "native_crash",
            nowMillis = 1_700_000_000_000L,
            pid = 1024
        )

        assertTrue(fileName.matches(Regex("native_crash_\\d{8}_\\d{6}_p1024\\.txt")))
    }
}
