package moe.ouom.neriplayer.data.sync.work

import android.content.Context
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.SyncProvider
import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureKind
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.sync.github.GitHubSyncInProgressException
import moe.ouom.neriplayer.data.sync.github.GitHubSyncManager
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.host.SyncPlaybackActivity
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerFailureClassifier
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerHost
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage

internal fun createGitHubWorkerHost(context: Context): SyncWorkerHost {
    val storage by lazy { SecureTokenStorage(context) }
    val notification = SyncFailureNotification(
        context, "github_sync_channel", 1001,
        CoreCommonR.string.github_sync_channel_name, CoreCommonR.string.github_sync_channel_desc, CoreCommonR.string.github_sync_failed_title
    )
    val delegate = SyncRepositoryWorkerHost(
        provider = SyncProvider.GITHUB,
        readAutoSync = { storage.isAutoSyncEnabled() }, readConfigured = { storage.isConfigured() },
        readProtocolUpgradeApproved = {
            SyncProtocolUpgradeRepository(context).canSyncTarget(SyncProtocolUpgradeRepository.githubTargetHash(
                storage.getRepoOwner().orEmpty(), storage.getRepoName().orEmpty()
            ))
        },
        readPlayback = { SyncPlaybackActivity.isActive }, readNetwork = { hasValidatedSyncNetwork(context) },
        defer = { GitHubSyncWorker.scheduleDelayedSync(context, initialDelayMs = 60_000L, appendToCurrentWork = true) },
        sync = { GitHubSyncManager.getInstance(context).performSync() },
        classifier = SyncWorkerFailureClassifier(mapOf(
            TokenExpiredException::class.java to SyncWorkerFailureKind.AUTHENTICATION,
            GitHubSyncInProgressException::class.java to SyncWorkerFailureKind.ALREADY_RUNNING
        )),
        readSilentFailure = { SettingsRepository(context).silentGitHubSyncFailureFlow.first() },
        notifyFailure = { notification.show(gitHubFailureMessage(context, it)) }
    )
    return GitHubRateLimitedWorkerHost(delegate, scheduleContinuation = { delayMillis, manual ->
        GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = manual,
            markMutation = false, initialDelayMs = delayMillis, appendToCurrentWork = true)
    })
}

private fun gitHubFailureMessage(context: Context, error: Throwable?): String =
    if (error is TokenExpiredException) context.getString(CoreCommonR.string.github_sync_token_expired)
    else error?.message ?: context.getString(CoreCommonR.string.github_sync_failed_message)
