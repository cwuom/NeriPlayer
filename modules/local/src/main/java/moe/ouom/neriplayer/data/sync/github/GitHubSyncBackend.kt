package moe.ouom.neriplayer.data.sync.github

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage


import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.GitHubArchiveHead
import moe.ouom.neriplayer.api.sync.github.GitHubArchiveProof
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.github.GitHubFileNotFoundException
import moe.ouom.neriplayer.api.sync.github.GitHubContentConflictException
import moe.ouom.neriplayer.api.sync.github.GitHubSyncHead

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.remote.SyncFallbackFileReader
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.runtime.SyncBackend
import moe.ouom.neriplayer.data.sync.host.observeCurrentSyncProtocol
import moe.ouom.neriplayer.data.model.sync.SyncData

internal class GitHubSyncBackend(
    private val storage: SecureTokenStorage,
    private val apiClient: GitHubApiClient,
    private val owner: String,
    private val repo: String,
    private val decoder: SyncRemoteSnapshotDecoder,
    private val invalidBackup: () -> Exception,
    private val followUp: () -> Unit,
    private val archive: SyncArchiveRepository,
    private val datasetStore: SyncPlaybackDatasetStore = archive.playbackDatasets,
    private val authorizeLegacyMigration: suspend (ByteArray) -> Unit = {},
    private val currentProtocolObserved: suspend (Int) -> Unit = {},
    private val metadataGuard: (() -> Unit) -> Boolean = storage.captureSyncMetadataGuard()
) : SyncBackend<GitHubSyncBackend.Version> {
    private val useDataSaver = storage.isDataSaverMode()
    private val preferredFileName = SyncDataSerializer.getFileName(useDataSaver)

    override val isFirstSync: Boolean get() = storage.getLastRemoteSha() == null
    override val lastSyncTime: Long get() = storage.getLastSyncTime()
    override val mutationConflictMessage = "Local state changed during GitHub sync"

    override suspend fun fetch(): Result<SyncDatasetRemoteSnapshot<Version>> =
        fetchRemoteSnapshot()

    override suspend fun refetch(version: Version): Result<SyncDatasetRemoteSnapshot<Version>> =
        fetchRemoteSnapshot()

    override suspend fun upload(data: SyncDataset, version: Version): Result<Version> =
        uploadLocalData(data, version)

    override fun remoteChanged(version: Version): Boolean {
        val lastSha = storage.getLastRemoteSha() ?: return false
        return version.sha != null && lastSha != version.sha
    }

    override fun isConflict(error: Throwable?): Boolean = error is GitHubContentConflictException
    override fun saveRemoteVersion(version: Version) { metadataGuard { version.sha?.let(storage::saveLastRemoteSha) } }
    override fun saveSyncTime(timestamp: Long) { metadataGuard { storage.saveLastSyncTime(timestamp) } }
    override fun saveCompletedSyncTime(timestamp: Long): Boolean = metadataGuard { storage.saveLastCompletedSyncTime(timestamp) }
    override fun scheduleFollowUp() {
        followUp()
    }
    override fun onFailure(error: Throwable) {
        if (error is TokenExpiredException) storage.clearToken()
    }

    data class Version(
        val sha: String?,
        val fileName: String,
        val branch: String? = null,
        val knownPaths: Set<String> = emptySet(),
        val archiveProof: GitHubArchiveProof? = null
    )

    private suspend fun fetchRemoteSnapshot(): Result<SyncDatasetRemoteSnapshot<Version>> {
        val started = System.nanoTime()
        val snapshot = apiClient.getArchiveHead(owner, repo).getOrElse { return Result.failure(it) }
        val head = snapshot.head
        val manifest = apiClient.getFileContentAtRef(owner, repo, SyncArchiveRepository.MANIFEST_FILE_NAME, head.sha)
            .getOrElse { error ->
                return if (error is GitHubFileNotFoundException) fetchLegacySnapshot(snapshot) else Result.failure(error)
            }
        NPLogger.i(TAG, "GitHub head and manifest read: ${(System.nanoTime() - started) / 1_000_000L} ms")
        return decodeArchive(manifest, snapshot)
    }

    private suspend fun fetchLegacySnapshot(snapshot: GitHubArchiveHead): Result<SyncDatasetRemoteSnapshot<Version>> {
        val head = snapshot.head
        val fetched = SyncFallbackFileReader.read(
            preferredFileName = preferredFileName,
            fallbackFileNames = SyncDataSerializer.getReadFallbackFileNames(useDataSaver),
            fetch = { fileName -> apiClient.getFileContentAtRef(owner, repo, fileName, head.sha) },
            isMissing = { it is GitHubFileNotFoundException }
        ).getOrElse { return Result.failure(it) }
        if (fetched == null) {
            archive.captureLegacyLyrics(SyncData())
            return observeCurrentSyncProtocol(SyncDatasetRemoteSnapshot(null, Version(head.sha, SyncArchiveRepository.MANIFEST_FILE_NAME, head.branch)), observe = currentProtocolObserved)
        }
        return decodeLegacyContent(fetched.content, snapshot)
    }

    private suspend fun decodeLegacyContent(content: ByteArray, snapshot: GitHubArchiveHead): Result<SyncDatasetRemoteSnapshot<Version>> {
        if (SyncArchiveRepository.isManifest(content)) {
            return decodeArchive(content, snapshot).map { it.copy(requiresMigrationUpload = true) }
        }
        val head = snapshot.head
        val context = currentCoroutineContext()
        // 损坏正文直接失败，文件名回退只用于远端文件不存在的情况
        return decoder.decodeForMigration(content, invalidBackup, authorizeLegacyMigration, archive::captureLegacyLyrics,
            checkActive = { context.ensureActive() }).map { data ->
            SyncDatasetRemoteSnapshot(
                dataset = datasetStore.fromLegacy(data),
                version = Version(head.sha, SyncArchiveRepository.MANIFEST_FILE_NAME, head.branch),
                requiresMigrationUpload = true
            )
        }.onFailure { NPLogger.e(TAG, "Failed to parse remote data, aborting sync without stale fallback", it) }
    }

    private suspend fun decodeArchive(content: ByteArray, snapshot: GitHubArchiveHead): Result<SyncDatasetRemoteSnapshot<Version>> {
        val protocol = runCatching { SyncArchiveRepository.protocolVersion(content) }.getOrElse { return Result.failure(it) }
        if (protocol < 4) {
            try { authorizeLegacyMigration(content) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { return Result.failure(error) }
        }
        val loaded = readArchiveDataset(content, snapshot).getOrElse { return Result.failure(it) }
        val head = snapshot.head
        return observeCurrentSyncProtocol(
            SyncDatasetRemoteSnapshot(
                dataset = sanitizeDataset(loaded.dataset),
                version = Version(head.sha, SyncArchiveRepository.MANIFEST_FILE_NAME, head.branch, archive.lastReferencedPaths, loaded.proof),
                requiresMigrationUpload = protocol < 4
            ), protocol, currentProtocolObserved
        )
    }

    private data class ArchiveRead(val dataset: SyncDataset, val proof: GitHubArchiveProof)

    private suspend fun readArchiveDataset(content: ByteArray, snapshot: GitHubArchiveHead): Result<ArchiveRead> {
        val readStarted = System.nanoTime()
        val proof = apiClient.getArchiveProof(owner, repo, snapshot).getOrElse { return Result.failure(it) }
        val objects = GitHubArchiveObjectReader(apiClient, owner, repo, snapshot.head.sha, proof.objectIds, currentCoroutineContext())
        return archive.readDataset(content, datasetStore, decoder::sanitize, decoder::sanitize,
            verifyRemoteObjects = true, cachedRemoteVerifier = objects::verifyCached, fetch = objects::fetch).also { result ->
            NPLogger.i(TAG, "GitHub archive read: ${(System.nanoTime() - readStarted) / 1_000_000L} ms, " +
                "cached=${objects.cachedObjects.get()}, downloaded=${objects.downloadedObjects.get()}, success=${result.isSuccess}")
        }.map { ArchiveRead(it, proof) }
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
        val head = resolveUploadHead(version).getOrElse { return Result.failure(it) }
        val prepareStarted = System.nanoTime()
        return archive.prepareCancellable(data).use { prepared ->
            NPLogger.i(TAG, "GitHub archive prepare: ${(System.nanoTime() - prepareStarted) / 1_000_000L} ms")
            val files = prepared.objects(version.knownPaths)
                .map { it.path to it.content } + sequenceOf(SyncArchiveRepository.MANIFEST_FILE_NAME to prepared.content)
            val publishStarted = System.nanoTime()
            apiClient.updateFilesContent(owner, repo, files, head, retainedArchivePaths = prepared.paths, archiveProof = version.archiveProof).mapCatching { sha ->
                currentProtocolObserved(4)
                Version(sha, SyncArchiveRepository.MANIFEST_FILE_NAME, head.branch, prepared.paths)
            }.also { result ->
                NPLogger.i(TAG, "GitHub archive publish: ${(System.nanoTime() - publishStarted) / 1_000_000L} ms, success=${result.isSuccess}")
            }
        }
    }

    private suspend fun resolveUploadHead(version: Version): Result<GitHubSyncHead> {
        val sha = version.sha
        val branch = version.branch
        if (sha != null && branch != null) return Result.success(GitHubSyncHead(branch, sha))
        return apiClient.getRepositoryHead(owner, repo).map { remote -> remote.copy(sha = sha ?: remote.sha) }
    }

    private companion object {
        const val TAG = "GitHubSyncManager"
    }
}
