package moe.ouom.neriplayer.data.sync.webdav

import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage


import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavFileNotFoundException
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.runtime.SyncBackend
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class WebDavSyncBackend(
    private val webDavStorage: WebDavStorage,
    private val apiClient: WebDavApiClient,
    private val remoteUrl: String,
    private val decoder: SyncRemoteSnapshotDecoder,
    private val invalidBackup: () -> Exception,
    private val followUp: () -> Unit,
    private val archive: SyncArchiveRepository,
    private val datasetStore: SyncPlaybackDatasetStore = archive.playbackDatasets
) : SyncBackend<WebDavSyncBackend.Version> {
    private val manifestUrl = WebDavApiClient.buildSiblingFileUrl(remoteUrl, SyncArchiveRepository.MANIFEST_FILE_NAME)
    override val isFirstSync: Boolean get() = webDavStorage.getLastRemoteFingerprint() == null
    override val lastSyncTime: Long get() = webDavStorage.getLastSyncTime()
    override val mutationConflictMessage = "Local state changed during WebDAV sync"

    override suspend fun fetch(): Result<SyncDatasetRemoteSnapshot<Version>> = fetchRemoteSnapshot()
    override suspend fun refetch(version: Version): Result<SyncDatasetRemoteSnapshot<Version>> = fetchRemoteSnapshot()
    override suspend fun upload(data: SyncDataset, version: Version): Result<Version> = uploadLocalData(data, version)

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
        val lastKnownFingerprint: String? = null,
        val knownPaths: Set<String> = emptySet()
    )

    private suspend fun fetchRemoteSnapshot(): Result<SyncDatasetRemoteSnapshot<Version>> {
        val fetched = apiClient.getFileContentStrict(manifestUrl)
        if (fetched.isFailure) {
            val error = fetched.exceptionOrNull()
            return if (error is WebDavFileNotFoundException) {
                fetchLegacySnapshot()
            } else {
                Result.failure(error ?: IOException("Failed to fetch remote data"))
            }
        }
        val snapshot = fetched.getOrThrow()
        return decodeArchive(snapshot.content, Version(snapshot.version, false, snapshot.fingerprint))
    }

    private suspend fun decodeArchive(
        content: ByteArray,
        version: Version,
        requiresMigrationUpload: Boolean = false
    ): Result<SyncDatasetRemoteSnapshot<Version>> {
        return archive.readDataset(content, datasetStore, decoder::sanitize, decoder::sanitize) { path ->
            apiClient.getFileContentStrict(WebDavApiClient.buildSiblingFileUrl(remoteUrl, path)).map { it.content }
        }.map { dataset ->
            SyncDatasetRemoteSnapshot(
                dataset = sanitizeDataset(dataset),
                version = version.copy(knownPaths = archive.lastReferencedPaths),
                requiresMigrationUpload = requiresMigrationUpload
            )
        }.onFailure { NPLogger.e(TAG, "Failed to read remote archive", it) }
    }

    private suspend fun fetchLegacySnapshot(): Result<SyncDatasetRemoteSnapshot<Version>> {
        val snapshot = apiClient.getFileContentStrict(remoteUrl).getOrElse { return legacyReadFailure(it) }
        if (SyncArchiveRepository.isManifest(snapshot.content)) {
            return decodeArchive(snapshot.content, Version(null, true, snapshot.fingerprint), requiresMigrationUpload = true)
        }
        return decoder.decode(snapshot.content, invalidBackup).map { data ->
            SyncDatasetRemoteSnapshot(
                dataset = datasetStore.fromLegacy(data),
                version = Version(null, createOnly = true, lastKnownFingerprint = snapshot.fingerprint),
                requiresMigrationUpload = true
            )
        }.onFailure { NPLogger.e(TAG, "Failed to parse remote data", it) }
    }

    private fun legacyReadFailure(error: Throwable): Result<SyncDatasetRemoteSnapshot<Version>> =
        if (error is WebDavFileNotFoundException) {
            Result.success(SyncDatasetRemoteSnapshot(null, Version(token = null, createOnly = true)))
        } else {
            Result.failure(error)
        }

    private fun sanitizeDataset(dataset: SyncDataset): SyncDataset = try {
        SyncDataset(decoder.sanitize(dataset.data), dataset.playback)
    } catch (error: Exception) {
        dataset.close()
        throw error
    }

    private suspend fun uploadLocalData(
        data: SyncDataset,
        version: Version
    ): Result<Version> {
        if (!canSafelyPublish(version)) {
            return Result.failure(WebDavMissingConcurrencyTokenException(
                "WebDAV sync requires a strong ETag to prevent concurrent data loss"
            ))
        }
        return archive.prepareCancellable(data).use { prepared ->
            for (obj in prepared.objects(version.knownPaths)) {
                currentCoroutineContext().ensureActive()
                val uploaded = uploadImmutableObject(obj.path, obj.content)
                if (uploaded.isFailure) return Result.failure(uploaded.exceptionOrNull() ?: IOException("Failed to upload sync object"))
            }
            currentCoroutineContext().ensureActive()
            apiClient.updateFileContent(
                remoteUrl = manifestUrl,
                content = prepared.content,
                expectedVersion = version.token,
                createOnly = version.createOnly
            ).map { written -> Version(written.version, false, written.fingerprint, prepared.paths) }
        }
    }

    private fun canSafelyPublish(version: Version): Boolean {
        if (version.createOnly) return true
        val etag = version.token?.etag?.trim() ?: return false
        return strongETag.matches(etag)
    }

    private fun uploadImmutableObject(path: String, content: ByteArray): Result<Unit> {
        val url = WebDavApiClient.buildSiblingFileUrl(remoteUrl, path)
        val written = apiClient.updateFileContent(url, content, createOnly = true)
        return written.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error ->
                if (isExistingObjectConflict(error)) verifyExistingObject(url, content) else Result.failure(error)
            }
        )
    }

    private fun isExistingObjectConflict(error: Throwable): Boolean =
        error is WebDavContentConflictException && error.statusCode == 412

    private fun verifyExistingObject(url: String, content: ByteArray): Result<Unit> {
        // 相同内容可能已由另一台设备上传，确认字节一致后复用
        return apiClient.getFileContentStrict(url).mapCatching { existing ->
            require(existing.fingerprint == WebDavApiClient.calculateFingerprint(content)) {
                "Remote sync object does not match its content address"
            }
        }
    }

    private companion object {
        const val TAG = "WebDavSyncManager"
        val strongETag = Regex("\"[!#-~\\u0080-\\u00ff]*\"")
    }
}
