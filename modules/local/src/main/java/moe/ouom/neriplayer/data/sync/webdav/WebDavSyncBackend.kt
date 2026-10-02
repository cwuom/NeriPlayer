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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.sync.host.observeCurrentSyncProtocol
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.api.sync.webdav.WebDavArchiveLease
import moe.ouom.neriplayer.api.sync.webdav.WebDavArchiveLeaseLostException
import moe.ouom.neriplayer.api.sync.webdav.WebDavArchiveListing
import moe.ouom.neriplayer.data.sync.archive.SyncPreparedArchive
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcJournal

internal class WebDavSyncBackend(
    private val webDavStorage: WebDavStorage,
    private val apiClient: WebDavApiClient,
    private val remoteUrl: String,
    private val decoder: SyncRemoteSnapshotDecoder,
    private val invalidBackup: () -> Exception,
    private val followUp: () -> Unit,
    private val archive: SyncArchiveRepository,
    private val datasetStore: SyncPlaybackDatasetStore = archive.playbackDatasets,
    private val authorizeLegacyMigration: suspend (ByteArray) -> Unit = {},
    private val currentProtocolObserved: suspend (Int) -> Unit = {},
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val uptimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) : SyncBackend<WebDavSyncBackend.Version> {
    private val manifestUrl = WebDavApiClient.buildSiblingFileUrl(remoteUrl, SyncArchiveRepository.MANIFEST_FILE_NAME)
    private val maintenanceScope = apiClient.archiveMaintenanceScope(manifestUrl)
    override val isFirstSync: Boolean get() = webDavStorage.getLastRemoteFingerprint() == null
    override val lastSyncTime: Long get() = webDavStorage.getLastSyncTime()
    override val mutationConflictMessage = "Local state changed during WebDAV sync"

    override suspend fun fetch(): Result<SyncDatasetRemoteSnapshot<Version>> = withArchiveLease({ it.dataset?.close() }) { lease ->
        fetchRemoteSnapshot(lease).mapCatching { snapshot -> protectObservedSnapshot(snapshot, lease) }
    }
    override suspend fun refetch(version: Version): Result<SyncDatasetRemoteSnapshot<Version>> = fetch()
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

    private suspend fun fetchRemoteSnapshot(lease: WebDavArchiveLease?): Result<SyncDatasetRemoteSnapshot<Version>> {
        val fetched = readFile(manifestUrl, lease)
        if (fetched.isFailure) {
            val error = fetched.exceptionOrNull()
            return if (error is WebDavFileNotFoundException) {
                fetchLegacySnapshot(lease)
            } else {
                Result.failure(error ?: IOException("Failed to fetch remote data"))
            }
        }
        val snapshot = fetched.getOrThrow()
        return decodeArchive(snapshot.content, Version(snapshot.version, false, snapshot.fingerprint), lease)
    }

    private suspend fun decodeArchive(
        content: ByteArray,
        version: Version,
        lease: WebDavArchiveLease?,
        requiresMigrationUpload: Boolean = false
    ): Result<SyncDatasetRemoteSnapshot<Version>> {
        val protocol = runCatching { SyncArchiveRepository.protocolVersion(content) }.getOrElse { return Result.failure(it) }
        if (protocol < 4) {
            try { authorizeLegacyMigration(content) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { return Result.failure(error) }
        }
        val dataset = archive.readDataset(content, datasetStore, decoder::sanitize, decoder::sanitize, verifyRemoteObjects = true) { path ->
            readFile(WebDavApiClient.buildSiblingFileUrl(remoteUrl, path), lease).map { it.content }
        }.onFailure { NPLogger.e(TAG, "Failed to read remote archive", it) }.getOrElse { return Result.failure(it) }
        return observeCurrentSyncProtocol(
            SyncDatasetRemoteSnapshot(
                dataset = sanitizeDataset(dataset),
                version = version.copy(knownPaths = archive.lastReferencedPaths),
                requiresMigrationUpload = requiresMigrationUpload || protocol < 4
            ), protocol, currentProtocolObserved
        )
    }

    private suspend fun fetchLegacySnapshot(lease: WebDavArchiveLease?): Result<SyncDatasetRemoteSnapshot<Version>> {
        val snapshot = readFile(remoteUrl, lease).getOrElse { return legacyReadFailure(it) }
        if (SyncArchiveRepository.isManifest(snapshot.content)) {
            return decodeArchive(snapshot.content, Version(null, true, snapshot.fingerprint), lease, requiresMigrationUpload = true)
        }
        val context = currentCoroutineContext()
        return decoder.decodeForMigration(snapshot.content, invalidBackup, authorizeLegacyMigration, archive::captureLegacyLyrics,
            checkActive = { context.ensureActive() }).map { data ->
            SyncDatasetRemoteSnapshot(
                dataset = datasetStore.fromLegacy(data),
                version = Version(null, createOnly = true, lastKnownFingerprint = snapshot.fingerprint),
                requiresMigrationUpload = true
            )
        }.onFailure { NPLogger.e(TAG, "Failed to parse remote data", it) }
    }

    private suspend fun legacyReadFailure(error: Throwable): Result<SyncDatasetRemoteSnapshot<Version>> =
        if (error is WebDavFileNotFoundException) {
            archive.captureLegacyLyrics(SyncData())
            observeCurrentSyncProtocol(SyncDatasetRemoteSnapshot(null, Version(token = null, createOnly = true)), observe = currentProtocolObserved)
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
            withArchiveLease({}) { lease -> uploadPrepared(prepared, version, lease) }
        }
    }

    private suspend fun uploadPrepared(prepared: SyncPreparedArchive, version: Version, lease: WebDavArchiveLease?): Result<Version> {
        for (obj in prepared.objects(version.knownPaths)) {
            currentCoroutineContext().ensureActive()
            val uploaded = uploadImmutableObject(obj.path, obj.content, lease)
            if (uploaded.isFailure) return Result.failure(uploaded.exceptionOrNull() ?: IOException("Failed to upload sync object"))
        }
        currentCoroutineContext().ensureActive()
        val publishedContent = SyncArchiveRepository.webDavPublicationContent(prepared.content)
        return writeFile(manifestUrl, publishedContent, lease, version.token, version.createOnly).mapCatching { written ->
            currentProtocolObserved(4)
            val published = Version(written.version, false, written.fingerprint, prepared.paths)
            if (lease != null && permitsGc(version, published)) {
                try { collectUnreferenced(publishedContent, version.knownPaths + prepared.paths, lease) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (lost: WebDavArchiveLeaseLostException) { throw lost }
                catch (conflict: WebDavContentConflictException) { throw conflict }
                catch (error: Exception) {
                    // 清单已发布，清理失败留待后续同步重试，不撤销已提交的数据
                    NPLogger.e(TAG, "WebDAV archive maintenance deferred", error)
                }
            }
            published
        }
    }

    private fun permitsGc(previous: Version, published: Version): Boolean {
        val etag = published.token?.etag ?: return false
        if (!WebDavArchiveListing.isStrongETag(etag)) return false
        if (previous.createOnly) return true
        val previousETag = previous.token?.etag ?: return false
        return etag != previousETag
    }

    private suspend fun collectUnreferenced(content: ByteArray, protectedPaths: Set<String>, lease: WebDavArchiveLease) {
        val current = archive.verifyRemoteClosure(content) { path ->
            readFile(WebDavApiClient.buildSiblingFileUrl(remoteUrl, path), lease).map { it.content }
        }.getOrThrow()
        require(current.all(protectedPaths::contains)) { "Published WebDAV closure does not match prepared archive" }
        val entries = apiClient.listArchiveFiles(lease).getOrThrow()
        var state = WebDavArchiveGcJournal.observe(webDavStorage.archiveGcState(maintenanceScope), entries,
            protectedPaths + current, wallMs(), uptimeMs())
        currentCoroutineContext().ensureActive()
        webDavStorage.saveArchiveGcState(maintenanceScope, state)
        for (entry in WebDavArchiveGcJournal.eligible(state)) {
            currentCoroutineContext().ensureActive()
            apiClient.deleteArchiveObject(entry, lease).getOrThrow()
            state = WebDavArchiveGcJournal.protect(state, setOf(entry.path))
            currentCoroutineContext().ensureActive()
            webDavStorage.saveArchiveGcState(maintenanceScope, state)
        }
    }

    private fun protectObservedSnapshot(snapshot: SyncDatasetRemoteSnapshot<Version>, lease: WebDavArchiveLease?): SyncDatasetRemoteSnapshot<Version> {
        if (lease == null) return snapshot
        try {
            val state = WebDavArchiveGcJournal.protect(webDavStorage.archiveGcState(maintenanceScope), snapshot.version.knownPaths)
            webDavStorage.saveArchiveGcState(maintenanceScope, state)
            return snapshot
        } catch (error: Exception) {
            try { snapshot.dataset?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private suspend fun <T : Any> withArchiveLease(dispose: (T) -> Unit, operation: suspend (WebDavArchiveLease?) -> Result<T>): Result<T> {
        var produced: T? = null
        return try {
            val context = currentCoroutineContext()
            val lease = apiClient.acquireArchiveLease(manifestUrl, webDavStorage.archiveLockSupported(maintenanceScope)) { context.ensureActive() }.getOrThrow()
            val result = lease.use {
                if (it != null) webDavStorage.rememberArchiveLock(maintenanceScope)
                operation(it).getOrThrow().also { value -> produced = value }
            }
            Result.success(result)
        } catch (cancelled: CancellationException) {
            produced?.let { value -> try { dispose(value) } catch (cleanup: Exception) { cancelled.addSuppressed(cleanup) } }
            throw cancelled
        } catch (error: Exception) {
            produced?.let { value -> try { dispose(value) } catch (cleanup: Exception) { error.addSuppressed(cleanup) } }
            Result.failure(error)
        }
    }

    private fun readFile(url: String, lease: WebDavArchiveLease?) = if (lease == null) apiClient.getFileContentStrict(url)
        else apiClient.getFileContentLocked(url, lease)

    private fun writeFile(url: String, bytes: ByteArray, lease: WebDavArchiveLease?, token: WebDavConcurrencyToken? = null, createOnly: Boolean = false) =
        if (lease == null) apiClient.updateFileContent(url, bytes, expectedVersion = token, createOnly = createOnly)
        else apiClient.updateFileContentLocked(url, bytes, lease, expectedVersion = token, createOnly = createOnly)

    private fun canSafelyPublish(version: Version): Boolean {
        if (version.createOnly) return true
        val etag = version.token?.etag?.trim() ?: return false
        return strongETag.matches(etag)
    }

    private fun uploadImmutableObject(path: String, content: ByteArray, lease: WebDavArchiveLease?): Result<Unit> {
        val url = WebDavApiClient.buildSiblingFileUrl(remoteUrl, path)
        val written = writeFile(url, content, lease, createOnly = true)
        return written.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error ->
                if (isExistingObjectConflict(error)) verifyExistingObject(url, content, lease) else Result.failure(error)
            }
        )
    }

    private fun isExistingObjectConflict(error: Throwable): Boolean =
        error is WebDavContentConflictException && error.statusCode == 412

    private fun verifyExistingObject(url: String, content: ByteArray, lease: WebDavArchiveLease?): Result<Unit> {
        // 相同内容可能已由另一台设备上传，确认字节一致后复用
        return readFile(url, lease).mapCatching { existing ->
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
