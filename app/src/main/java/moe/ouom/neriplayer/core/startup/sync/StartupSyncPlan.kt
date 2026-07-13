package moe.ouom.neriplayer.core.startup.sync

internal data class StartupSyncPlan(
    val scheduleGitHub: Boolean,
    val scheduleWebDav: Boolean,
    val webDavStaggerDelayMs: Long
)

internal object StartupSyncPlanner {
    const val STARTUP_SYNC_SCHEDULE_DELAY_MS = 20_000L
    const val STARTUP_SYNC_STAGGER_DELAY_MS = 10_000L

    fun plan(
        gitHubConfigured: Boolean,
        gitHubAutoSyncEnabled: Boolean,
        webDavConfigured: Boolean,
        webDavAutoSyncEnabled: Boolean
    ): StartupSyncPlan {
        val scheduleGitHub = gitHubConfigured && gitHubAutoSyncEnabled
        val scheduleWebDav = webDavConfigured && webDavAutoSyncEnabled
        return StartupSyncPlan(
            scheduleGitHub = scheduleGitHub,
            scheduleWebDav = scheduleWebDav,
            webDavStaggerDelayMs = if (scheduleGitHub && scheduleWebDav) {
                STARTUP_SYNC_STAGGER_DELAY_MS
            } else {
                0L
            }
        )
    }
}
