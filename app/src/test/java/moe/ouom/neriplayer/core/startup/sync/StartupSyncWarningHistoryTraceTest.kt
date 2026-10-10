package moe.ouom.neriplayer.core.startup.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSyncWarningHistoryTraceTest {

    @Test
    fun `sync history alone counts as a previous GitHub sync trace`() {
        assertEquals(
            StartupSyncWarningPlan(showWarning = true, hasShownWarning = true, resetDismissed = false),
            StartupSyncWarningPlanner.plan(
                state = StartupSyncWarningState(
                    hasRepoInfo = false,
                    hasSyncHistory = true,
                    isConfigured = false,
                    isDismissed = false
                ),
                hasShownWarning = false
            )
        )
    }

    @Test
    fun `without any GitHub sync trace the warning stays hidden and keeps the shown flag`() {
        val neverSynced = StartupSyncWarningState(
            hasRepoInfo = false,
            hasSyncHistory = false,
            isConfigured = false,
            isDismissed = false
        )

        assertEquals(
            StartupSyncWarningPlan(showWarning = false, hasShownWarning = false, resetDismissed = false),
            StartupSyncWarningPlanner.plan(neverSynced, hasShownWarning = false)
        )
        assertEquals(
            StartupSyncWarningPlan(showWarning = false, hasShownWarning = true, resetDismissed = false),
            StartupSyncWarningPlanner.plan(neverSynced, hasShownWarning = true)
        )
    }
}
