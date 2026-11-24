package moe.ouom.neriplayer.network.weblogin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWebLoginLauncherTest {
    @Test
    fun resultOrDisposalReleasesTheMainProcessGuard() {
        val launcher = ForegroundWebLoginGuard.Launcher("test")
        try {
            launcher.launch { assertTrue(ForegroundWebLoginGuard.isActive) }
            launcher.launch { error("duplicate login launch") }
            assertTrue(ForegroundWebLoginGuard.isActive)
            launcher.close()
            launcher.close()
            assertFalse(ForegroundWebLoginGuard.isActive)
        } finally {
            launcher.close()
        }
    }

    @Test
    fun failedLaunchReleasesTheGuardForTheNextAttempt() {
        val launcher = ForegroundWebLoginGuard.Launcher("test")
        try {
            val failure = runCatching { launcher.launch { error("activity unavailable") } }
            assertTrue(failure.isFailure)
            assertFalse(ForegroundWebLoginGuard.isActive)
            launcher.launch { assertTrue(ForegroundWebLoginGuard.isActive) }
        } finally {
            launcher.close()
        }
    }
}
