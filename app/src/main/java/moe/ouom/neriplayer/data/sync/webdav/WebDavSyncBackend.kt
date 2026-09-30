package moe.ouom.neriplayer.data.sync.webdav

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncRemoteSnapshot
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.host.AndroidSyncSanitizationHost
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.remote.WebDavConditionalWrite
import moe.ouom.neriplayer.data.sync.runtime.SyncBackend
import moe.ouom.neriplayer.data.sync.sanitize.SyncDataSanitizer
import moe.ouom.neriplayer.util.platform.LanguageManager
import java.io.IOException

internal class WebDavSyncBackend(
    private val appContext: Context,
    private val storage: SecureTokenStorage
) : SyncBackend<WebDavSyncBackend.Version> {
    private val localizedContext = LanguageManager.applyLanguage(appContext)
    private val sanitizer = SyncDataSanitizer(AndroidSyncSanitizationHost(appContext))
    private val decoder = SyncRemoteSnapshotDecoder(sanitizer::sanitize)
    private val webDavStorage = WebDavStorage(appContext)
    private val remoteUrl = webDavStorage.getRemoteFileUrl()
        ?: throw IllegalStateException(localizedContext.getString(R.string.webdav_not_configured))
    private val username = webDavStorage.getUsername()
        ?: throw IllegalStateException(localizedContext.getString(R.string.webdav_not_configured))
    private val password = webDavStorage.getPassword()
        ?: throw IllegalStateException(localizedContext.getString(R.string.webdav_not_configured))
    private val apiClient = WebDavApiClient(appContext, username, password)

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
        WebDavSyncWorker.scheduleDelayedSync(appContext, triggerByUserAction = false, markMutation = false, appendToCurrentWork = true)
    }
    override fun onFailure(error: Throwable) = Unit

    data class Version(
        val token: WebDavApiClient.ConcurrencyToken?,
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
        return decoder.decode(snapshot.content) {
            IOException(LanguageManager.applyLanguage(appContext).getString(R.string.webdav_backup_file_invalid))
        }.map { data ->
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
        val localizedContext = LanguageManager.applyLanguage(appContext)
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
        return if (uploadResult.isSuccess) {
            val writeResult = uploadResult.getOrThrow()
            Result.success(
                Version(
                    token = writeResult.version,
                    createOnly = false,
                    lastKnownFingerprint = writeResult.fingerprint
                )
            )
        } else {
            Result.failure(
                uploadResult.exceptionOrNull()
                    ?: Exception(localizedContext.getString(R.string.sync_upload_failed))
            )
        }
    }

    private fun uploadFileWithConcurrencyFallback(
        remoteUrl: String,
        content: ByteArray,
        mediaType: String,
        version: Version
    ): Result<WebDavApiClient.WriteResult> = WebDavConditionalWrite.execute(
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
