package moe.ouom.neriplayer.core.startup.sync

internal data class StartupSyncWarningState(
    val hasRepoInfo: Boolean,
    val hasSyncHistory: Boolean,
    val isConfigured: Boolean,
    val isDismissed: Boolean
)

internal data class StartupSyncWarningPlan(
    val showWarning: Boolean,
    val hasShownWarning: Boolean,
    val resetDismissed: Boolean
)

internal object StartupSyncWarningPlanner {
    fun plan(
        state: StartupSyncWarningState,
        hasShownWarning: Boolean
    ): StartupSyncWarningPlan {
        val hasPreviousGitHubSyncTrace = state.hasRepoInfo || state.hasSyncHistory
        val showWarning = hasPreviousGitHubSyncTrace &&
            !state.isConfigured &&
            !hasShownWarning &&
            !state.isDismissed

        if (showWarning) {
            return StartupSyncWarningPlan(
                showWarning = true,
                hasShownWarning = true,
                resetDismissed = false
            )
        }

        if (state.isConfigured) {
            return StartupSyncWarningPlan(
                showWarning = false,
                hasShownWarning = false,
                resetDismissed = state.isDismissed
            )
        }

        return StartupSyncWarningPlan(
            showWarning = false,
            hasShownWarning = hasShownWarning,
            resetDismissed = false
        )
    }
}
