package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import androidx.annotation.StringRes
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.github.GitHubSyncBackend
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.sanitize.SyncDataSanitizer
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.common.locale.LanguageManager
import java.io.IOException

internal fun createGitHubSyncBackend(context: Context, storage: SecureTokenStorage): GitHubSyncBackend {
    val localized = LanguageManager.applyLanguage(context)
    val message = localized.getString(CoreCommonR.string.github_not_configured)
    val token = configuredValue(storage.getToken(), message)
    val owner = configuredValue(storage.getRepoOwner(), message)
    val repo = configuredValue(storage.getRepoName(), message)
    return GitHubSyncBackend(
        storage, createGitHubSyncClient(context, token), owner, repo, syncDecoder(context),
        invalidBackup = { invalidBackup(context, CoreCommonR.string.github_backup_file_invalid) },
        followUp = {
            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false, markMutation = false, appendToCurrentWork = true)
        }
    )
}

internal fun createWebDavSyncBackend(context: Context, storage: SecureTokenStorage): WebDavSyncBackend {
    val localized = LanguageManager.applyLanguage(context)
    val message = localized.getString(CoreCommonR.string.webdav_not_configured)
    val webDavStorage = WebDavStorage(context)
    val remoteUrl = configuredValue(webDavStorage.getRemoteFileUrl(), message)
    val username = configuredValue(webDavStorage.getUsername(), message)
    val password = configuredValue(webDavStorage.getPassword(), message)
    return WebDavSyncBackend(
        storage, webDavStorage, createWebDavSyncClient(context, username, password), remoteUrl, syncDecoder(context),
        invalidBackup = { invalidBackup(context, CoreCommonR.string.webdav_backup_file_invalid) },
        followUp = {
            WebDavSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false, markMutation = false, appendToCurrentWork = true)
        }
    )
}

private fun configuredValue(value: String?, message: String): String =
    value ?: throw IllegalStateException(message)

private fun syncDecoder(context: Context): SyncRemoteSnapshotDecoder =
    SyncRemoteSnapshotDecoder(SyncDataSanitizer(AndroidSyncSanitizationHost(context))::sanitize)

private fun invalidBackup(context: Context, @StringRes message: Int): IOException =
    IOException(LanguageManager.applyLanguage(context).getString(message))
