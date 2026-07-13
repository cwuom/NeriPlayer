package moe.ouom.neriplayer.core.startup.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupPlannerTest {
    @Test
    fun `main process without safe mode initializes normal components`() {
        val plan = AppStartupPlanner.plan(
            runningInMainProcess = true,
            safeModeRequested = false
        )

        assertTrue(plan.shouldCapturePreviousAnr)
        assertTrue(plan.shouldInstallNativeCrashHandler)
        assertTrue(plan.shouldInitializeNormalComponents)
    }

    @Test
    fun `main process in safe mode skips native crash handler and normal components`() {
        val plan = AppStartupPlanner.plan(
            runningInMainProcess = true,
            safeModeRequested = true
        )

        assertTrue(plan.enterSafeMode)
        assertTrue(plan.shouldCapturePreviousAnr)
        assertFalse(plan.shouldInstallNativeCrashHandler)
        assertFalse(plan.shouldInitializeNormalComponents)
    }

    @Test
    fun `secondary process never enters safe mode or initializes normal components`() {
        val plan = AppStartupPlanner.plan(
            runningInMainProcess = false,
            safeModeRequested = true
        )

        assertFalse(plan.enterSafeMode)
        assertFalse(plan.shouldCapturePreviousAnr)
        assertFalse(plan.shouldInstallNativeCrashHandler)
        assertFalse(plan.shouldInitializeNormalComponents)
    }
}
