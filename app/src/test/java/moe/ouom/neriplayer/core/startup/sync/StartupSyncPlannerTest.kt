package moe.ouom.neriplayer.core.startup.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSyncPlannerTest {
    @Test
    fun `does not schedule anything when both providers are disabled`() {
        assertEquals(
            StartupSyncPlan(
                scheduleGitHub = false,
                scheduleWebDav = false,
                webDavStaggerDelayMs = 0L
            ),
            StartupSyncPlanner.plan(
                gitHubConfigured = true,
                gitHubAutoSyncEnabled = false,
                webDavConfigured = true,
                webDavAutoSyncEnabled = false
            )
        )
    }

    @Test
    fun `stagger webdav only when github also schedules`() {
        assertEquals(
            StartupSyncPlan(
                scheduleGitHub = true,
                scheduleWebDav = true,
                webDavStaggerDelayMs = StartupSyncPlanner.STARTUP_SYNC_STAGGER_DELAY_MS
            ),
            StartupSyncPlanner.plan(
                gitHubConfigured = true,
                gitHubAutoSyncEnabled = true,
                webDavConfigured = true,
                webDavAutoSyncEnabled = true
            )
        )
    }

    @Test
    fun `does not stagger webdav when github is unavailable`() {
        assertEquals(
            StartupSyncPlan(
                scheduleGitHub = false,
                scheduleWebDav = true,
                webDavStaggerDelayMs = 0L
            ),
            StartupSyncPlanner.plan(
                gitHubConfigured = false,
                gitHubAutoSyncEnabled = true,
                webDavConfigured = true,
                webDavAutoSyncEnabled = true
            )
        )
    }
}
