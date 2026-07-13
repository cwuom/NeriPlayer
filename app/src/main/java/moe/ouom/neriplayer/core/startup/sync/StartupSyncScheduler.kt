package moe.ouom.neriplayer.core.startup.sync

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import kotlin.coroutines.CoroutineContext

internal class StartupSyncScheduler(
    context: Context,
    private val ioDispatcher: CoroutineContext,
    private val isStarted: () -> Boolean,
    private val scheduleGitHubSync: (Context) -> Unit = { targetContext ->
        GitHubSyncWorker.scheduleDelayedSync(
            context = targetContext,
            markMutation = false
        )
    },
    private val scheduleWebDavSync: (Context) -> Unit = { targetContext ->
        WebDavSyncWorker.scheduleDelayedSync(
            context = targetContext,
            markMutation = false
        )
    }
) {
    private val appContext = context.applicationContext

    suspend fun scheduleIfNeeded() {
        delay(StartupSyncPlanner.STARTUP_SYNC_SCHEDULE_DELAY_MS)
        if (!isStarted()) {
            return
        }

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
            scheduleGitHubSync(appContext)
        }
        if (!plan.scheduleWebDav) {
            return
        }
        if (plan.webDavStaggerDelayMs > 0L) {
            delay(plan.webDavStaggerDelayMs)
            if (!isStarted()) {
                return
            }
        }
        scheduleWebDavSync(appContext)
    }
}
