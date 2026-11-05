package moe.ouom.neriplayer.core.startup.sync

import android.content.Context
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import kotlin.coroutines.CoroutineContext

internal class StartupSyncScheduler(
    context: Context,
    private val ioDispatcher: CoroutineContext,
    private val scheduleGitHubSync: (Context, Long) -> Unit = { targetContext, initialDelayMs ->
        GitHubSyncWorker.scheduleDelayedSync(
            context = targetContext,
            markMutation = false,
            initialDelayMs = initialDelayMs,
            triggerByAppStartup = true
        )
    },
    private val scheduleWebDavSync: (Context, Long) -> Unit = { targetContext, initialDelayMs ->
        WebDavSyncWorker.scheduleDelayedSync(
            context = targetContext,
            markMutation = false,
            initialDelayMs = initialDelayMs,
            triggerByAppStartup = true
        )
    }
) {
    private val appContext = context.applicationContext

    suspend fun scheduleIfNeeded() {
        try {
            val plan = withContext(ioDispatcher) {
                val gitHubStorage = SecureTokenStorage(appContext)
                val webDavStorage = WebDavStorage(appContext)
                StartupSyncPlanner.plan(
                    gitHubConfigured = gitHubStorage.isConfigured(),
                    gitHubAutoSyncEnabled = gitHubStorage.isAutoSyncEnabled(),
                    webDavConfigured = webDavStorage.isConfigured(),
                    webDavAutoSyncEnabled = webDavStorage.isAutoSyncEnabled()
                )
            }

            if (plan.scheduleGitHub) {
                scheduleGitHubSync(appContext, 0L)
            }
            if (plan.scheduleWebDav) {
                scheduleWebDavSync(appContext, plan.webDavStaggerDelayMs)
            }
        } catch (_: SyncStorageUnavailableException) {
            // 保留原配置，后续启动或用户重试时再调度
        }
    }
}
