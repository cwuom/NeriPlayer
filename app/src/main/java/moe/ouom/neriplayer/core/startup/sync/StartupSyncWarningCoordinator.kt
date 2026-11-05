package moe.ouom.neriplayer.core.startup.sync

import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException

internal data class StartupSyncWarningCheckResult(
    val showWarning: Boolean,
    val hasShownWarning: Boolean
)

internal class StartupSyncWarningCoordinator(
    private val store: StartupSyncWarningStore
) {
    suspend fun check(hasShownWarning: Boolean): StartupSyncWarningCheckResult = try {
        val plan = StartupSyncWarningPlanner.plan(
            state = store.loadState(),
            hasShownWarning = hasShownWarning
        )
        if (plan.resetDismissed) {
            store.setDismissed(false)
        }
        StartupSyncWarningCheckResult(
            showWarning = plan.showWarning,
            hasShownWarning = plan.hasShownWarning
        )
    } catch (_: SyncStorageUnavailableException) {
        StartupSyncWarningCheckResult(showWarning = false, hasShownWarning = hasShownWarning)
    }

    suspend fun dismissReminder() {
        try {
            store.setDismissed(true)
        } catch (_: SyncStorageUnavailableException) {
            // 下次可读时再判断提醒，不能把暂时不可用当成已忽略
        }
    }
}
