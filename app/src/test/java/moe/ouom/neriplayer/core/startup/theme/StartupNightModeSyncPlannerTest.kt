package moe.ouom.neriplayer.core.startup.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupNightModeSyncPlannerTest {
    @Test
    fun `light preference applies when resources are currently dark`() {
        val plan = StartupNightModeSyncPlanner.plan(
            forceDark = false,
            followSystemDark = false,
            systemDark = true,
            currentResourceDark = true
        )

        assertFalse(plan.useDark)
        assertTrue(plan.shouldApplyNightMode)
    }

    @Test
    fun `dark preference applies when resources are currently light`() {
        val plan = StartupNightModeSyncPlanner.plan(
            forceDark = true,
            followSystemDark = false,
            systemDark = false,
            currentResourceDark = false
        )

        assertTrue(plan.useDark)
        assertTrue(plan.shouldApplyNightMode)
    }

    @Test
    fun `auto preference follows system without applying when resources match`() {
        val plan = StartupNightModeSyncPlanner.plan(
            forceDark = false,
            followSystemDark = true,
            systemDark = true,
            currentResourceDark = true
        )

        assertTrue(plan.useDark)
        assertFalse(plan.shouldApplyNightMode)
    }

    @Test
    fun `force dark wins when legacy flags are both enabled`() {
        val plan = StartupNightModeSyncPlanner.plan(
            forceDark = true,
            followSystemDark = true,
            systemDark = false,
            currentResourceDark = false
        )

        assertTrue(plan.useDark)
        assertTrue(plan.shouldApplyNightMode)
    }
}
