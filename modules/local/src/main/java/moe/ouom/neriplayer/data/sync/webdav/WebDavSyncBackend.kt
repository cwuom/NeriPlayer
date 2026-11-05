package moe.ouom.neriplayer.data.sync.webdav

import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage


import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavFileNotFoundException
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import moe.ouom.neriplayer.data.model.sync.transport.WebDavRemoteFileSnapshot
import moe.ouom.neriplayer.data.model.sync.transport.WebDavWriteResult
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
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcState

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
    private val uptimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val metadataGuard: (() -> Unit) -> Boolean = webDavStorage.captureSyncMetadataGuard()
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
        metadataGuard { version.lastKnownFingerprint?.let(webDavStorage::saveLastRemoteFingerprint) }
    }
    override fun saveSyncTime(timestamp: Long) { metadataGuard { webDavStorage.saveLastSyncTime(timestamp) } }
    override fun saveCompletedSyncTime(timestamp: Long): Boolean = metadataGuard { webDavStorage.saveLastCompletedSyncTime(timestamp) }
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
            .mapCatching { maintainFetchedArchive(snapshot.content, it, lease) }
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
        if (!canSafelyPublish(version) && opaqueCondition(version) == null && version.lastKnownFingerprint == null) {
            return Result.failure(WebDavMissingConcurrencyTokenException(
                "WebDAV sync requires an ETag or an observed manifest fingerprint"
            ))
        }
        return archive.prepareCancellable(data).use { prepared ->
            uploadWithLease(prepared, version)
        }
    }

    private suspend fun uploadWithLease(prepared: SyncPreparedArchive, version: Version): Result<Version> {
        var published: Version? = null
        val result = withArchiveLease({}) { lease ->
            requirePublicationProtection(version, lease)
            requireUnchangedManifest(version, lease)
            uploadPrepared(prepared, version, lease).onSuccess { published = it }
        }
        val completed = published ?: return result
        val releaseFailure = result.exceptionOrNull() ?: return result
        return reconcilePublication(completed, releaseFailure)
    }

    private fun requirePublicationProtection(version: Version, lease: WebDavArchiveLease?) {
        if (lease != null || canSafelyPublish(version) || opaqueCondition(version) != null) return
        throw WebDavMissingConcurrencyTokenException(
            "WebDAV updates require a finite LOCK or an ETag accepted by If-Match; " +
                "use a compatible WebDAV server or GitHub sync. Local data has been retained"
        )
    }

    private suspend fun requireUnchangedManifest(version: Version, lease: WebDavArchiveLease?) {
        // rclone、坚果云等服务器不一定检查 PUT 条件，发布前必须确认合并所用的版本
        val current = readFile(manifestUrl, lease).getOrElse { error ->
            if (error is WebDavFileNotFoundException) {
                if (version.createOnly) return
                throw WebDavContentConflictException(412, "WebDAV archive disappeared before publication")
            }
            throw error
        }
        requireMatchingManifest(version, current)
    }

    private fun requireMatchingManifest(version: Version, current: WebDavRemoteFileSnapshot) {
        if (version.createOnly || canSafelyPublish(version) && current.version.etag != version.token?.etag ||
            version.lastKnownFingerprint != null && current.fingerprint != version.lastKnownFingerprint) {
            throw WebDavContentConflictException(412, "WebDAV archive changed before publication")
        }
    }

    private suspend fun reconcilePublication(published: Version, releaseFailure: Throwable): Result<Version> {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (!canReconcileReleaseFailure(releaseFailure)) return Result.failure(releaseFailure)
        return try {
            // 上传已完成但释放结果不确定时，只核对原发布，不再次上传或清理
            val lease = apiClient.acquireArchiveLease(manifestUrl, knownSupported = true) { context.ensureActive() }
                .getOrThrow() ?: throw IOException("WebDAV publication recovery requires a finite archive lease")
            lease.use { verifyPublication(published, it) }
            context.ensureActive()
            Result.success(published)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error !== releaseFailure) releaseFailure.addSuppressed(error)
            Result.failure(releaseFailure)
        }
    }

    private fun canReconcileReleaseFailure(error: Throwable): Boolean = when (error) {
        is WebDavApiException -> error.statusCode == 408 || error.statusCode in 500..599
        is WebDavAuthException, is WebDavArchiveLeaseLostException -> false
        else -> error is IOException
    }

    private suspend fun verifyPublication(published: Version, lease: WebDavArchiveLease) {
        val expectedETag = published.token?.etag ?: throw WebDavMissingConcurrencyTokenException(
            "WebDAV publication recovery requires a strong ETag")
        require(WebDavArchiveListing.isStrongETag(expectedETag)) { "WebDAV publication recovery requires a strong ETag" }
        val current = readFile(manifestUrl, lease).getOrThrow()
        require(current.version.etag == expectedETag && current.fingerprint == published.lastKnownFingerprint) {
            "WebDAV publication changed before recovery"
        }
        val paths = archive.verifyRemoteClosure(current.content) { path ->
            readFile(WebDavApiClient.buildSiblingFileUrl(remoteUrl, path), lease).map { it.content }
        }.getOrThrow()
        require(paths == published.knownPaths) { "WebDAV publication recovery closure does not match the prepared archive" }
    }

    private suspend fun uploadPrepared(prepared: SyncPreparedArchive, version: Version, lease: WebDavArchiveLease?): Result<Version> {
        for (obj in prepared.objects(version.knownPaths)) {
            currentCoroutineContext().ensureActive()
            val uploaded = uploadImmutableObject(obj.path, obj.content, lease)
            if (uploaded.isFailure) return Result.failure(uploaded.exceptionOrNull() ?: IOException("Failed to upload sync object"))
        }
        currentCoroutineContext().ensureActive()
        val publishedContent = SyncArchiveRepository.webDavPublicationContent(prepared.content)
        return publishManifest(publishedContent, version, lease).mapCatching { written ->
            currentProtocolObserved(4)
            val published = Version(written.version, false, written.fingerprint, prepared.paths)
            if (lease != null && permitsGc(version, published)) {
                try { collectUnreferenced(publishedContent, prepared.paths, version.knownPaths, lease) }
                catch (error: Exception) { deferMaintenanceFailure(error) }
            }
            published
        }
    }

    private suspend fun publishManifest(content: ByteArray, version: Version, lease: WebDavArchiveLease?): Result<WebDavWriteResult> {
        if (lease != null) return writeFile(manifestUrl, content, lease, version.token, version.createOnly)
        return publishUnlocked(content, version).mapCatching { written ->
            written.copy(version = confirmUnlockedPublication(written.fingerprint).version)
        }
    }

    private fun publishUnlocked(content: ByteArray, version: Version): Result<WebDavWriteResult> {
        val opaque = opaqueCondition(version) ?: return writeFile(manifestUrl, content, null, version.token, version.createOnly)
        return apiClient.updateFileContent(manifestUrl, content, expectedVersion = opaque)
    }

    // 坚果云返回不带引号的 ETag，原样用于 If-Match 才能由服务端原子拒绝并发发布
    private fun opaqueCondition(version: Version): WebDavConcurrencyToken? {
        if (version.createOnly) return null
        val token = version.token ?: return null
        val etag = token.etag ?: return null
        return token.takeIf { !WebDavArchiveListing.isStrongETag(etag) && opaqueETag.matches(etag) }
    }

    private suspend fun confirmUnlockedPublication(fingerprint: String): WebDavRemoteFileSnapshot {
        // 回读只能发现已发生的覆盖，不能替代服务器对条件写入的原子检查
        val current = readFile(manifestUrl, null).getOrThrow()
        if (current.fingerprint != fingerprint) throw WebDavContentConflictException(412, "WebDAV archive changed during publication")
        return current
    }

    private fun permitsGc(previous: Version, published: Version): Boolean {
        val etag = published.token?.etag ?: return false
        if (!WebDavArchiveListing.isStrongETag(etag)) return false
        if (previous.createOnly) return true
        val previousETag = previous.token?.etag ?: return false
        return etag != previousETag
    }

    private suspend fun maintainFetchedArchive(
        content: ByteArray, snapshot: SyncDatasetRemoteSnapshot<Version>, lease: WebDavArchiveLease?
    ): SyncDatasetRemoteSnapshot<Version> {
        if (lease == null || snapshot.requiresMigrationUpload || !canSafelyPublish(snapshot.version)) return snapshot
        var version = snapshot.version
        try {
            val state = observeUnreferenced(version.knownPaths, emptySet(), lease)
            if (WebDavArchiveGcJournal.eligible(state).isEmpty()) return snapshot
            // 真正删除前刷新同一闭包的发布屏障，让锁外旧写入者的条件失效
            val publishedContent = SyncArchiveRepository.webDavPublicationContent(content)
            val written = writeFile(manifestUrl, publishedContent, lease, version.token).getOrThrow()
            version = Version(written.version, false, written.fingerprint, version.knownPaths)
            if (permitsGc(snapshot.version, version)) deleteEligible(state, lease)
        } catch (error: Exception) {
            try { deferMaintenanceFailure(error) }
            catch (unsafe: Exception) {
                try { snapshot.dataset?.close() } catch (cleanup: Exception) { unsafe.addSuppressed(cleanup) }
                throw unsafe
            }
        }
        return snapshot.copy(version = version)
    }

    private fun deferMaintenanceFailure(error: Exception) {
        when (error) {
            is CancellationException, is WebDavArchiveLeaseLostException, is WebDavContentConflictException -> throw error
            else -> NPLogger.e(TAG, "WebDAV archive maintenance deferred", error)
        }
    }

    private suspend fun collectUnreferenced(content: ByteArray, expectedPaths: Set<String>, previousPaths: Set<String>, lease: WebDavArchiveLease) {
        val current = archive.verifyRemoteClosure(content) { path ->
            readFile(WebDavApiClient.buildSiblingFileUrl(remoteUrl, path), lease).map { it.content }
        }.getOrThrow()
        require(current == expectedPaths) { "Published WebDAV closure does not match prepared archive" }
        deleteEligible(observeUnreferenced(current, previousPaths, lease), lease)
    }

    private suspend fun observeUnreferenced(current: Set<String>, previous: Set<String>, lease: WebDavArchiveLease): WebDavArchiveGcState {
        val entries = apiClient.listArchiveFiles(lease).getOrThrow()
        // 前代刚失去引用，重置旧候选年龄后开始新的完整宽限
        val baseline = WebDavArchiveGcJournal.protect(webDavStorage.archiveGcState(maintenanceScope), previous)
        val state = WebDavArchiveGcJournal.observe(baseline, entries, current, wallMs(), uptimeMs())
        currentCoroutineContext().ensureActive()
        webDavStorage.saveArchiveGcState(maintenanceScope, state)
        return state
    }

    private suspend fun deleteEligible(initial: WebDavArchiveGcState, lease: WebDavArchiveLease) {
        var state = initial
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

    private suspend fun readFile(url: String, lease: WebDavArchiveLease?) = apiClient.getArchiveFileContent(url, lease)

    private fun writeFile(url: String, bytes: ByteArray, lease: WebDavArchiveLease?, token: WebDavConcurrencyToken? = null, createOnly: Boolean = false): Result<WebDavWriteResult> {
        // 清单更新缺少强 ETag 时，只能在已取得的目录锁内写入
        val condition = token?.takeIf { it.etag?.let(WebDavArchiveListing::isStrongETag) == true }
        return if (lease == null) apiClient.updateFileContent(url, bytes, expectedVersion = condition, createOnly = createOnly)
        else apiClient.updateFileContentLocked(url, bytes, lease, expectedVersion = condition, createOnly = createOnly)
    }

    private fun canSafelyPublish(version: Version): Boolean {
        if (version.createOnly) return true
        val etag = version.token?.etag ?: return false
        return WebDavArchiveListing.isStrongETag(etag)
    }

    private suspend fun uploadImmutableObject(path: String, content: ByteArray, lease: WebDavArchiveLease?): Result<Unit> {
        val url = WebDavApiClient.buildSiblingFileUrl(remoteUrl, path)
        val written = writeFile(url, content, lease, createOnly = true)
        return written.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error ->
                if (isExistingObjectConflict(error)) verifyExistingObject(url, content, lease) else Result.failure(error)
            }
        )
    }

    private fun isExistingObjectConflict(error: Throwable?): Boolean =
        error is WebDavContentConflictException && error.statusCode == 412

    private suspend fun verifyExistingObject(url: String, content: ByteArray, lease: WebDavArchiveLease?): Result<Unit> {
        // 相同内容可能已由另一台设备上传，确认字节一致后复用
        return readFile(url, lease).mapCatching { existing ->
            require(existing.fingerprint == WebDavApiClient.calculateFingerprint(content)) {
                "Remote sync object does not match its content address"
            }
        }
    }

    private companion object {
        const val TAG = "WebDavSyncManager"
        val opaqueETag = Regex("[!#-+\\--~]+")
    }
}
