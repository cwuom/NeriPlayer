package moe.ouom.neriplayer.data.sync.webdav

import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage


import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavFileNotFoundException
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import moe.ouom.neriplayer.data.model.sync.transport.WebDavWriteResult
import moe.ouom.neriplayer.data.model.sync.SyncRemoteSnapshot
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.remote.WebDavConditionalWrite
import moe.ouom.neriplayer.data.sync.runtime.SyncBackend
import java.io.IOException

internal class WebDavSyncBackend(
    private val storage: SecureTokenStorage,
    private val webDavStorage: WebDavStorage,
    private val apiClient: WebDavApiClient,
    private val remoteUrl: String,
    private val decoder: SyncRemoteSnapshotDecoder,
    private val invalidBackup: () -> Exception,
    private val followUp: () -> Unit
) : SyncBackend<WebDavSyncBackend.Version> {
    override val isFirstSync: Boolean get() = webDavStorage.getLastRemoteFingerprint() == null
    override val lastSyncTime: Long get() = webDavStorage.getLastSyncTime()
    override val mutationConflictMessage = "Local state changed during WebDAV sync"

    override suspend fun fetch(): Result<SyncRemoteSnapshot<Version>> = fetchRemoteSnapshot(remoteUrl)
    override suspend fun refetch(version: Version): Result<SyncRemoteSnapshot<Version>> = fetchRemoteSnapshot(remoteUrl)
    override suspend fun upload(data: SyncData, version: Version): Result<Version> = uploadLocalData(remoteUrl, data, version)

    override fun remoteChanged(version: Version): Boolean {
        val lastFingerprint = webDavStorage.getLastRemoteFingerprint() ?: return false
        return version.lastKnownFingerprint != null && lastFingerprint != version.lastKnownFingerprint
    }
    override fun isConflict(error: Throwable?): Boolean = error is WebDavContentConflictException
    override fun saveRemoteVersion(version: Version) {
        version.lastKnownFingerprint?.let(webDavStorage::saveLastRemoteFingerprint)
    }
    override fun saveSyncTime(timestamp: Long) { webDavStorage.saveLastSyncTime(timestamp) }
    override fun scheduleFollowUp() {
        followUp()
    }
    override fun onFailure(error: Throwable) = Unit

    data class Version(
        val token: WebDavConcurrencyToken?,
        val createOnly: Boolean,
        val lastKnownFingerprint: String? = null
    )

    private suspend fun fetchRemoteSnapshot(remoteUrl: String): Result<SyncRemoteSnapshot<Version>> {
        val fetched = apiClient.getFileContentStrict(remoteUrl)
        if (fetched.isFailure) {
            val error = fetched.exceptionOrNull()
            return if (error is WebDavFileNotFoundException) {
                Result.success(SyncRemoteSnapshot(null, Version(token = null, createOnly = true)))
            } else {
                Result.failure(error ?: IOException("Failed to fetch remote data"))
            }
        }
        val snapshot = fetched.getOrThrow()
        return decoder.decode(snapshot.content, invalidBackup).map { data ->
            SyncRemoteSnapshot(
                data = data,
                version = Version(snapshot.version, createOnly = false, lastKnownFingerprint = snapshot.fingerprint)
            )
        }.onFailure { NPLogger.e(TAG, "Failed to parse remote data", it) }
    }

    private suspend fun uploadLocalData(
        remoteUrl: String,
        data: SyncData,
        version: Version
    ): Result<Version> {
        val useDataSaver = storage.isDataSaverMode()
        val content = SyncDataSerializer.serialize(data, useDataSaver)
        // 省流传原始 GZIP(ProtoBuf) 字节; 非省流传 UTF-8 JSON 字节
        val mediaType = if (useDataSaver) {
            "application/octet-stream"
        } else {
            "application/json; charset=utf-8"
        }
        NPLogger.d(
            TAG,
            "Upload data size: ${content.size} bytes (DataSaver: $useDataSaver, WebDAV)"
        )

        val uploadResult = uploadFileWithConcurrencyFallback(
            remoteUrl = remoteUrl,
            content = content,
            mediaType = mediaType,
            version = version
        )
        return uploadResult.map { writeResult ->
            Version(
                token = writeResult.version,
                createOnly = false,
                lastKnownFingerprint = writeResult.fingerprint
            )
        }
    }

    private fun uploadFileWithConcurrencyFallback(
        remoteUrl: String,
        content: ByteArray,
        mediaType: String,
        version: Version
    ): Result<WebDavWriteResult> = WebDavConditionalWrite.execute(
        createOnly = version.createOnly,
        expectedFingerprint = version.lastKnownFingerprint,
        write = { unconditional ->
            if (unconditional) {
                NPLogger.w(TAG, "WebDAV server has no conditional token; writing after fingerprint revalidation")
            }
            apiClient.updateFileContent(
                remoteUrl = remoteUrl,
                content = content,
                mediaType = mediaType,
                expectedVersion = if (unconditional) null else version.token,
                createOnly = version.createOnly,
                allowUnconditionalWrite = unconditional
            )
        },
        readFingerprint = { apiClient.getFileContentStrict(remoteUrl).map { it.fingerprint } },
        isMissingToken = { it is WebDavMissingConcurrencyTokenException },
        conflictError = {
            WebDavContentConflictException(412, "WebDAV remote content changed while conditional tokens were unavailable")
        }
    )

    private companion object {
        const val TAG = "WebDavSyncManager"
    }
}
