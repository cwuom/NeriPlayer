package moe.ouom.neriplayer.core.startup.sync

internal data class StartupSyncWarningCheckResult(
    val showWarning: Boolean,
    val hasShownWarning: Boolean
)

internal class StartupSyncWarningCoordinator(
    private val store: StartupSyncWarningStore
) {
    suspend fun check(hasShownWarning: Boolean): StartupSyncWarningCheckResult {
        val plan = StartupSyncWarningPlanner.plan(
            state = store.loadState(),
            hasShownWarning = hasShownWarning
        )
        if (plan.resetDismissed) {
            store.setDismissed(false)
        }
        return StartupSyncWarningCheckResult(
            showWarning = plan.showWarning,
            hasShownWarning = plan.hasShownWarning
        )
    }

    suspend fun dismissReminder() {
        store.setDismissed(true)
    }
}
