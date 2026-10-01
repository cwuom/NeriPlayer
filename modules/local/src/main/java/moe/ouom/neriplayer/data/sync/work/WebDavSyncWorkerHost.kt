package moe.ouom.neriplayer.data.sync.work

import android.content.Context
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.SyncProvider
import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureKind
import moe.ouom.neriplayer.data.sync.host.SyncPlaybackActivity
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerFailureClassifier
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerHost
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncInProgressException
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncManager
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker

internal fun createWebDavWorkerHost(context: Context): SyncWorkerHost {
    val storage by lazy { WebDavStorage(context) }
    val notification = SyncFailureNotification(
        context, "webdav_sync_channel", 1002,
        CoreCommonR.string.webdav_sync_channel_name, CoreCommonR.string.webdav_sync_channel_desc, CoreCommonR.string.webdav_sync_failed_title
    )
    return SyncRepositoryWorkerHost(
        provider = SyncProvider.WEBDAV,
        readAutoSync = { storage.isAutoSyncEnabled() }, readConfigured = { storage.isConfigured() },
        readPlayback = { SyncPlaybackActivity.isActive }, readNetwork = { hasValidatedSyncNetwork(context) },
        defer = { WebDavSyncWorker.scheduleDelayedSync(context, initialDelayMs = 60_000L, appendToCurrentWork = true) },
        sync = { WebDavSyncManager.getInstance(context).performSync() },
        classifier = SyncWorkerFailureClassifier(mapOf(
            WebDavAuthException::class.java to SyncWorkerFailureKind.AUTHENTICATION,
            WebDavMissingConcurrencyTokenException::class.java to SyncWorkerFailureKind.MISSING_CONDITION,
            WebDavSyncInProgressException::class.java to SyncWorkerFailureKind.ALREADY_RUNNING
        )),
        readSilentFailure = { false }, notifyFailure = { notification.show(webDavFailureMessage(context, it)) }
    )
}

private fun webDavFailureMessage(context: Context, error: Throwable?): String =
    if (error is WebDavAuthException) context.getString(CoreCommonR.string.webdav_auth_failed)
    else error?.message ?: context.getString(CoreCommonR.string.webdav_sync_failed_message)
