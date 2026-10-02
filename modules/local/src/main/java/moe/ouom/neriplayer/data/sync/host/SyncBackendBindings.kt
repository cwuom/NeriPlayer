package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import androidx.annotation.StringRes
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.github.GitHubSyncBackend
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.archive.SyncLegacyLyricRecovery
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.sync.sanitize.SyncDataSanitizer
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.common.locale.LanguageManager
import java.io.IOException
import java.io.File
import moe.ouom.neriplayer.data.sync.mapping.stats.SyncPlaybackStatMapping
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.model.sync.SyncData

internal fun createGitHubSyncBackend(context: Context, storage: SecureTokenStorage): GitHubSyncBackend {
    val localized = LanguageManager.applyLanguage(context)
    val message = localized.getString(CoreCommonR.string.github_not_configured)
    val token = configuredValue(storage.getToken(), message)
    val owner = configuredValue(storage.getRepoOwner(), message)
    val repo = configuredValue(storage.getRepoName(), message)
    val target = SyncProtocolUpgradeRepository.githubTargetHash(owner, repo)
    val upgrades = SyncProtocolUpgradeRepository(context)
    return GitHubSyncBackend(
        storage, createGitHubSyncClient(context, token), owner, repo, syncDecoder(context),
        invalidBackup = { invalidBackup(context, CoreCommonR.string.github_backup_file_invalid) },
        followUp = {
            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false, markMutation = false, appendToCurrentWork = true)
        },
        archive = archiveRepository(context, "github", "$owner/$repo", target),
        authorizeLegacyMigration = { content ->
            upgrades.requireLegacyMigration(SyncProtocolUpgradeChallenge(target, WebDavApiClient.calculateFingerprint(content)))
        },
        currentProtocolObserved = { upgrades.markCurrent(target) }
    )
}

internal fun createWebDavSyncBackend(context: Context): WebDavSyncBackend {
    val localized = LanguageManager.applyLanguage(context)
    val message = localized.getString(CoreCommonR.string.webdav_not_configured)
    val webDavStorage = WebDavStorage(context)
    val remoteUrl = configuredValue(webDavStorage.getRemoteFileUrl(), message)
    val username = configuredValue(webDavStorage.getUsername(), message)
    val password = configuredValue(webDavStorage.getPassword(), message)
    val target = SyncProtocolUpgradeRepository.webDavTargetHash(checkNotNull(webDavStorage.getServerUrl()), webDavStorage.getBasePath(), username)
    val upgrades = SyncProtocolUpgradeRepository(context)
    return WebDavSyncBackend(
        webDavStorage, createWebDavSyncClient(context, username, password), remoteUrl, syncDecoder(context),
        invalidBackup = { invalidBackup(context, CoreCommonR.string.webdav_backup_file_invalid) },
        followUp = {
            WebDavSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false, markMutation = false, appendToCurrentWork = true)
        },
        archive = archiveRepository(context, "webdav", remoteUrl, target),
        authorizeLegacyMigration = { content ->
            upgrades.requireLegacyMigration(SyncProtocolUpgradeChallenge(target, WebDavApiClient.calculateFingerprint(content)))
        },
        currentProtocolObserved = { upgrades.markCurrent(target) }
    )
}

private fun archiveRepository(context: Context, provider: String, identity: String, targetId: String): SyncArchiveRepository {
    val namespace = WebDavApiClient.calculateFingerprint(identity.toByteArray(Charsets.UTF_8))
    val storage = SecureTokenStorage(context.applicationContext)
    val recovery = object : SyncLegacyLyricRecovery {
        override fun isCompleted(sourceHash: String): Boolean = storage.isLegacyLyricArchiveRecovered(targetId, sourceHash)
        override fun recover(sourceHash: String, data: SyncData) {
            if (isCompleted(sourceHash)) return
            storage.retainLegacyLyrics(data)
            storage.markLegacyLyricArchiveRecovered(targetId, sourceHash)
        }
    }
    return SyncArchiveRepository(File(context.cacheDir, "sync-v3/$provider/$namespace"), legacyRecovery = recovery,
        beforeNormalization = storage::retainLegacyLyrics)
}

private fun configuredValue(value: String?, message: String): String =
    value ?: throw IllegalStateException(message)

private fun syncDecoder(context: Context): SyncRemoteSnapshotDecoder {
    val host = AndroidSyncSanitizationHost(context)
    val storage = SecureTokenStorage(context.applicationContext)
    return SyncRemoteSnapshotDecoder(
        SyncDataSanitizer(host)::sanitize,
        { SyncPlaybackStatMapping.sanitize(it, host) },
        { SyncPlaybackStatMapping.sanitize(it, host) },
        beforeSanitize = storage::retainLegacyLyrics
    )
}

private fun invalidBackup(context: Context, @StringRes message: Int): IOException =
    IOException(LanguageManager.applyLanguage(context).getString(message))
