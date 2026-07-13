package moe.ouom.neriplayer.activity.sync

import android.content.Context
import moe.ouom.neriplayer.core.startup.sync.StartupSyncWarningRepository
import moe.ouom.neriplayer.core.startup.sync.StartupSyncWarningState

@Deprecated("use StartupSyncWarningRepository")
internal class GitHubSyncWarningRepository(
    context: Context
) {
    private val delegate = StartupSyncWarningRepository(context)

    suspend fun loadState(): StartupSyncWarningState = delegate.loadState()

    suspend fun setDismissed(dismissed: Boolean) {
        delegate.setDismissed(dismissed)
    }
}
