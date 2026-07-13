package moe.ouom.neriplayer.core.startup.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage

internal interface StartupSyncWarningStore {
    suspend fun loadState(): StartupSyncWarningState

    suspend fun setDismissed(dismissed: Boolean)
}

internal class StartupSyncWarningRepository(
    context: Context
) : StartupSyncWarningStore {
    private val appContext = context.applicationContext

    override suspend fun loadState(): StartupSyncWarningState = withContext(Dispatchers.IO) {
        val storage = SecureTokenStorage(appContext)
        StartupSyncWarningState(
            hasRepoInfo = !storage.getRepoOwner().isNullOrEmpty() || !storage.getRepoName().isNullOrEmpty(),
            hasSyncHistory = storage.getLastSyncTime() > 0,
            isConfigured = storage.isConfigured(),
            isDismissed = storage.isTokenWarningDismissed()
        )
    }

    override suspend fun setDismissed(dismissed: Boolean) {
        withContext(Dispatchers.IO) {
            SecureTokenStorage(appContext).setTokenWarningDismissed(dismissed)
        }
    }
}
