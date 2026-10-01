package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage


import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.github.GitHubFileNotFoundException
import moe.ouom.neriplayer.api.sync.github.GitHubContentConflictException

import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncRemoteSnapshot
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.remote.SyncFallbackFileReader
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.runtime.SyncBackend
import java.io.IOException

internal class GitHubSyncBackend(
    private val storage: SecureTokenStorage,
    private val apiClient: GitHubApiClient,
    private val owner: String,
    private val repo: String,
    private val decoder: SyncRemoteSnapshotDecoder,
    private val invalidBackup: () -> Exception,
    private val followUp: () -> Unit
) : SyncBackend<GitHubSyncBackend.Version> {
    private val useDataSaver = storage.isDataSaverMode()
    private val preferredFileName = SyncDataSerializer.getFileName(useDataSaver)

    override val isFirstSync: Boolean get() = storage.getLastRemoteSha() == null
    override val lastSyncTime: Long get() = storage.getLastSyncTime()
    override val mutationConflictMessage = "Local state changed during GitHub sync"

    override suspend fun fetch(): Result<SyncRemoteSnapshot<Version>> =
        fetchRemoteSnapshot(preferredFileName, useDataSaver)

    override suspend fun refetch(version: Version): Result<SyncRemoteSnapshot<Version>> =
        fetchRemoteSnapshot(version.fileName, SyncDataSerializer.isBinaryFileName(version.fileName))

    override suspend fun upload(data: SyncData, version: Version): Result<Version> =
        uploadLocalData(data, version.sha, version.fileName).map { Version(it, version.fileName) }

    override fun remoteChanged(version: Version): Boolean {
        val lastSha = storage.getLastRemoteSha() ?: return false
        return version.sha != null && lastSha != version.sha
    }

    override fun isConflict(error: Throwable?): Boolean = error is GitHubContentConflictException
    override fun saveRemoteVersion(version: Version) { version.sha?.let(storage::saveLastRemoteSha) }
    override fun saveSyncTime(timestamp: Long) { storage.saveLastSyncTime(timestamp) }
    override fun scheduleFollowUp() {
        followUp()
    }
    override fun onFailure(error: Throwable) {
        if (error is TokenExpiredException) storage.clearToken()
    }

    data class Version(val sha: String?, val fileName: String)

    private suspend fun fetchRemoteSnapshot(
        preferredFileName: String,
        useDataSaver: Boolean
    ): Result<SyncRemoteSnapshot<Version>> {
        val fetched = SyncFallbackFileReader.read(
            preferredFileName = preferredFileName,
            fallbackFileNames = SyncDataSerializer.getReadFallbackFileNames(useDataSaver),
            fetch = { fileName -> apiClient.getFileContentStrict(owner, repo, fileName) },
            isMissing = { it is GitHubFileNotFoundException }
        )
        if (fetched.isFailure) return Result.failure(fetched.exceptionOrNull() ?: IOException("Failed to fetch remote data"))
        val remote = fetched.getOrThrow()
            ?: return Result.success(SyncRemoteSnapshot(null, Version(null, preferredFileName)))
        val (content, sha) = remote.content
        // 损坏正文直接失败，文件名回退只用于远端文件不存在的情况
        return decoder.decode(content, invalidBackup).map { data ->
            SyncRemoteSnapshot(
                data = data,
                version = Version(sha, preferredFileName),
                requiresMigrationUpload = remote.fileName != preferredFileName
            )
        }.onFailure { NPLogger.e(TAG, "Failed to parse remote data, aborting sync without stale fallback", it) }
    }

    private suspend fun uploadLocalData(
        data: SyncData,
        sha: String?,
        fileName: String
    ): Result<String> {
        val useDataSaver = SyncDataSerializer.isBinaryFileName(fileName)
        val content = SyncDataSerializer.serialize(data, useDataSaver)
        NPLogger.d(
            TAG,
            "Upload data size: ${content.size} bytes (DataSaver: $useDataSaver, File: $fileName)"
        )

        return apiClient.updateFileContent(owner, repo, content, sha, fileName)
    }

    private companion object {
        const val TAG = "GitHubSyncManager"
    }
}
