package moe.ouom.neriplayer.core.startup.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSyncWarningPlannerTest {
    @Test
    fun `shows warning once when github trace exists without valid configuration`() {
        assertEquals(
            StartupSyncWarningPlan(
                showWarning = true,
                hasShownWarning = true,
                resetDismissed = false
            ),
            StartupSyncWarningPlanner.plan(
                state = StartupSyncWarningState(
                    hasRepoInfo = true,
                    hasSyncHistory = false,
                    isConfigured = false,
                    isDismissed = false
                ),
                hasShownWarning = false
            )
        )
    }

    @Test
    fun `keeps warning hidden after it was shown in this startup`() {
        assertEquals(
            StartupSyncWarningPlan(
                showWarning = false,
                hasShownWarning = true,
                resetDismissed = false
            ),
            StartupSyncWarningPlanner.plan(
                state = StartupSyncWarningState(
                    hasRepoInfo = true,
                    hasSyncHistory = true,
                    isConfigured = false,
                    isDismissed = false
                ),
                hasShownWarning = true
            )
        )
    }

    @Test
    fun `does not show warning after user dismissed it`() {
        assertEquals(
            StartupSyncWarningPlan(
                showWarning = false,
                hasShownWarning = false,
                resetDismissed = false
            ),
            StartupSyncWarningPlanner.plan(
                state = StartupSyncWarningState(
                    hasRepoInfo = true,
                    hasSyncHistory = false,
                    isConfigured = false,
                    isDismissed = true
                ),
                hasShownWarning = false
            )
        )
    }

    @Test
    fun `resets dismissed marker when github becomes configured again`() {
        assertEquals(
            StartupSyncWarningPlan(
                showWarning = false,
                hasShownWarning = false,
                resetDismissed = true
            ),
            StartupSyncWarningPlanner.plan(
                state = StartupSyncWarningState(
                    hasRepoInfo = true,
                    hasSyncHistory = true,
                    isConfigured = true,
                    isDismissed = true
                ),
                hasShownWarning = true
            )
        )
    }
}
