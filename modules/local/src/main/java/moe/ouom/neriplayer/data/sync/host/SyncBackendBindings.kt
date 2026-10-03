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
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy

internal fun createGitHubSyncBackend(
    context: Context,
    storage: SecureTokenStorage,
    expectedTargetId: String? = null
): GitHubSyncBackend {
    val metadataGuard = storage.captureSyncMetadataGuard()
    val configuredOwner = storage.getRepoOwner()
    val configuredRepo = storage.getRepoName()
    val capturedTarget = if (configuredOwner != null && configuredRepo != null)
        SyncProtocolUpgradeRepository.githubTargetHash(configuredOwner, configuredRepo) else null
    requireExpectedSyncTarget(expectedTargetId, capturedTarget)
    val localized = LanguageManager.applyLanguage(context)
    val message = localized.getString(CoreCommonR.string.github_not_configured)
    val token = configuredValue(storage.getToken(), message)
    val owner = configuredValue(configuredOwner, message)
    val repo = configuredValue(configuredRepo, message)
    val target = checkNotNull(capturedTarget)
    val upgrades = SyncProtocolUpgradeRepository(context)
    return GitHubSyncBackend(
        storage, createGitHubSyncClient(context, token), owner, repo, syncDecoder(context),
        invalidBackup = { invalidBackup(context, CoreCommonR.string.github_backup_file_invalid) },
        followUp = {
            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false, markMutation = false, appendToCurrentWork = true)
        },
        archive = archiveRepository(context, "github", "$owner/$repo", target),
        authorizeLegacyMigration = { content ->
            upgrades.requireLegacyMigration(migrationChallenge(target, content))
        },
        currentProtocolObserved = { version -> upgrades.markCurrent(target, version) },
        metadataGuard = metadataGuard
    )
}

internal fun createWebDavSyncBackend(context: Context, expectedTargetId: String? = null): WebDavSyncBackend {
    val webDavStorage = WebDavStorage(context)
    val metadataGuard = webDavStorage.captureSyncMetadataGuard()
    val serverUrl = webDavStorage.getServerUrl()?.takeIf { it.isNotBlank() }
    val basePath = webDavStorage.getBasePath()
    val configuredUsername = webDavStorage.getUsername()
    val capturedTarget = if (serverUrl != null && configuredUsername != null)
        SyncProtocolUpgradeRepository.webDavTargetHash(serverUrl, basePath, configuredUsername) else null
    requireExpectedSyncTarget(expectedTargetId, capturedTarget)
    val localized = LanguageManager.applyLanguage(context)
    val message = localized.getString(CoreCommonR.string.webdav_not_configured)
    val remoteUrl = configuredValue(serverUrl?.let { WebDavApiClient.buildRemoteFileUrl(it, basePath) }, message)
    val username = configuredValue(configuredUsername, message)
    val password = configuredValue(webDavStorage.getPassword(), message)
    val target = checkNotNull(capturedTarget)
    val upgrades = SyncProtocolUpgradeRepository(context)
    return WebDavSyncBackend(
        webDavStorage, createWebDavSyncClient(context, username, password), remoteUrl, syncDecoder(context),
        invalidBackup = { invalidBackup(context, CoreCommonR.string.webdav_backup_file_invalid) },
        followUp = {
            WebDavSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false, markMutation = false, appendToCurrentWork = true)
        },
        archive = archiveRepository(context, "webdav", remoteUrl, target),
        authorizeLegacyMigration = { content ->
            upgrades.requireLegacyMigration(migrationChallenge(target, content))
        },
        currentProtocolObserved = { version -> upgrades.markCurrent(target, version) },
        metadataGuard = metadataGuard
    )
}

private fun archiveRepository(context: Context, provider: String, identity: String, targetId: String): SyncArchiveRepository {
    val namespace = WebDavApiClient.calculateFingerprint(identity.toByteArray(Charsets.UTF_8))
    val storage = SecureTokenStorage(context.applicationContext)
    val recovery = object : SyncLegacyLyricRecovery {
        override fun preservedLyrics(): List<SyncSong> = storage.getLegacyLyricCandidates()
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

private fun requireExpectedSyncTarget(expectedTargetId: String?, configuredTargetId: String?) {
    check(expectedTargetId == null || expectedTargetId == configuredTargetId) { "Sync target changed" }
}

private fun syncDecoder(context: Context): SyncRemoteSnapshotDecoder {
    val host = AndroidSyncSanitizationHost(context)
    val storage = SecureTokenStorage(context.applicationContext)
    return SyncRemoteSnapshotDecoder(
        { data ->
            SyncDataSanitizer(host).sanitize(
                SyncSongLyricMergePolicy.prepareLegacy(data)
            )
        },
        { SyncPlaybackStatMapping.sanitize(it, host) },
        { SyncPlaybackStatMapping.sanitize(it, host) },
        beforeSanitize = storage::retainLegacyLyrics
    )
}

private fun migrationChallenge(targetId: String, content: ByteArray): SyncProtocolUpgradeChallenge {
    val version = if (SyncArchiveRepository.isManifest(content)) SyncArchiveRepository.protocolVersion(content) else 0
    return SyncProtocolUpgradeChallenge(targetId, WebDavApiClient.calculateFingerprint(content), version,
        SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION)
}

private fun invalidBackup(context: Context, @StringRes message: Int): IOException =
    IOException(LanguageManager.applyLanguage(context).getString(message))
