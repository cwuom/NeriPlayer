package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.core.download.storage.*
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadParsedMetadataEntry
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadUnfinalizedCleanupPlanner
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitVerifier
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadStorageCommitWriter
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadTreeFileCommitter
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadDeleteGuard
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadDeletePolicy
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadReferenceDeleteExecutor
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadReferenceDeleteResult
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadMetadataCodec
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadCoverLookup
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import moe.ouom.neriplayer.core.download.storage.migration.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationCopyWorker
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationFinalizer
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationNamePlanner
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationPolicy
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetIndexBuilder
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteCleaner
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.download.storage.sidecar.ManagedDownloadLyricStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotCacheStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

internal object ManagedDownloadStorage {
    private const val TAG = "ManagedDownloadStorage"
    private const val LOG_HOT_AUDIO_HITS = false

    private val snapshotBuildLock = Any()
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = ManagedDownloadStorageSettings(
        defaultRootPathProvider = { context -> createDefaultRoot(context).dir.absolutePath }
    )
    private val snapshotCacheStore = ManagedDownloadSnapshotCacheStore(
        scope = snapshotScope,
        cacheKeyProvider = settings::snapshotCacheKey
    )
    private val treeChildRegistry = ManagedDownloadTreeChildRegistry(
        writeCacheValidateIntervalMs = FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS,
        treeCacheValidateIntervalMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS,
        treeWriteCacheValidateIntervalMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS,
        onTreeQueryFailed = {
            NPLogger.w(TAG, "查询目录子项失败，回退 DocumentFile 枚举: ${it.message}")
        }
    )
    private val treeDirectoryLocks = ConcurrentHashMap<String, Any>()
    private val rootResolver = ManagedDownloadRootResolver(treeDirectoryLocks)
    private val treeDirectories = ManagedDownloadTreeDirectories(
        treeChildRegistry = treeChildRegistry,
        locks = treeDirectoryLocks,
        tag = TAG
    )
    private val treeFileCommitter = ManagedDownloadTreeFileCommitter(
        treeChildRegistry = treeChildRegistry,
        tag = TAG,
        deleteContentReference = { context, reference, uri ->
            deleteContentReference(context, reference, uri)
        },
        verifyDocumentCommittedLength = { context, uri, expectedSizeBytes, description ->
            verifyDocumentCommittedLength(
                context = context,
                uri = uri,
                expectedSizeBytes = expectedSizeBytes,
                description = description
            )
        }
    )
    private val commitWriter = ManagedDownloadStorageCommitWriter(
        treeChildRegistry = treeChildRegistry,
        treeDirectories = treeDirectories,
        treeFileCommitter = treeFileCommitter,
        tag = TAG
    )
    private val migrationCopyWorker = ManagedDownloadMigrationCopyWorker(
        tag = TAG,
        openInputStream = { context, entry -> openStoredEntryInputStream(context, entry) },
        mimeTypeFor = ::migrationMimeTypeFor,
        writeRootStream = { context, root, displayName, mimeType, input, sourceEntry, targetNames, targetEntry, onProgress ->
            writeMigrationRootStream(
                context = context,
                root = root,
                displayName = displayName,
                mimeType = mimeType,
                input = input,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = targetEntry,
                onProgress = onProgress
            )
        },
        writeSubdirectoryStream = { context, root, subdirectory, displayName, mimeType, input, sourceEntry, targetNames, targetEntry, onProgress ->
            writeMigrationSubdirectoryStream(
                context = context,
                root = root,
                subdirectory = subdirectory,
                displayName = displayName,
                mimeType = mimeType,
                input = input,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = targetEntry,
                onProgress = onProgress
            )
        }
    )
    private val referenceDeleteExecutor = ManagedDownloadReferenceDeleteExecutor(
        tag = TAG,
        isReferenceAllowed = { reference, trustedReferences, managedFileRoots, managedTreeRoots ->
            isReferenceAllowedForManagedDelete(
                reference = reference,
                trustedReferences = trustedReferences,
                managedFileRoots = managedFileRoots,
                managedTreeRoots = managedTreeRoots
            )
        }
    )
    private val migrationFinalizer = ManagedDownloadMigrationFinalizer(
        tag = TAG,
        rewriteParallelism = ::migrationRewriteParallelism,
        deleteParallelism = ::migrationDeleteParallelism,
        readText = { context, reference -> readTextInternal(context, reference) },
        writeRootText = { context, root, displayName, content ->
            writeRootText(
                context = context,
                root = root,
                displayName = displayName,
                content = content,
                invalidateSnapshot = false
            )
        },
        deleteReference = { context, reference, root ->
            deleteInternal(
                context = context,
                reference = reference,
                allowedRoot = root,
                invalidateSnapshot = false
            )
        },
        rewriteMetadataReferences = ::rewriteManagedMetadataReferences
    )
    private val pendingAudioWriteNames = ManagedDownloadPendingAudioWriteNames()

    @Volatile
    private var startupRecoveryResult = StartupRecoveryResult()

    private val _startupRecoveryResults = MutableSharedFlow<StartupRecoveryResult>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    internal val startupRecoveryResults: SharedFlow<StartupRecoveryResult> = _startupRecoveryResults

    private val _migrationProgressFlow = MutableStateFlow<MigrationProgress?>(null)
    val migrationProgressFlow: StateFlow<MigrationProgress?> = _migrationProgressFlow

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        createDefaultRoot(appContext)
        val stagingRecovery = cleanupStagingFiles(appContext)
        val pendingAudioRecovery = resolveStartupPendingAudioRecovery(appContext)
        val metadataRecovery = resolveStartupMetadataRecovery(appContext)
        startupRecoveryResult = StartupRecoveryResult(
            cleanedCount = stagingRecovery.cleanedCount +
                pendingAudioRecovery.cleanedCount +
                metadataRecovery.cleanedCount,
            failedCount = stagingRecovery.failedCount +
                pendingAudioRecovery.failedCount +
                metadataRecovery.failedCount
        )
        invalidateSnapshotCache()
    }

    private fun resolveStartupPendingAudioRecovery(context: Context): StartupRecoveryResult {
        val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
        val treeRootAvailable = resolveTreeRootBlocking(context, configuredUri) != null
        return if (shouldDeferStartupManagedCleanup(configuredUri, treeRootAvailable)) {
            schedulePendingAudioWriteCleanup(context)
            StartupRecoveryResult()
        } else {
            cleanupPendingAudioWrites(context)
        }
    }

    private fun schedulePendingAudioWriteCleanup(context: Context) {
        val appContext = context.applicationContext
        snapshotScope.launch {
            cleanupPendingAudioWrites(appContext)
        }
    }

    private fun resolveStartupMetadataRecovery(context: Context): StartupRecoveryResult {
        val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
        val treeRootAvailable = resolveTreeRootBlocking(context, configuredUri) != null
        if (shouldDeferStartupManagedCleanup(configuredUri, treeRootAvailable)) {
            scheduleUnfinalizedDownloadArtifactCleanup(context)
            return StartupRecoveryResult()
        }
        return cleanupUnfinalizedDownloadArtifacts(context)
    }

    private fun scheduleUnfinalizedDownloadArtifactCleanup(context: Context) {
        val appContext = context.applicationContext
        snapshotScope.launch {
            val result = cleanupUnfinalizedDownloadArtifacts(appContext)
            if (result.hasRecoveredEntries) {
                _startupRecoveryResults.tryEmit(result)
            }
        }
    }

    internal data class StartupRecoveryResult(
        val cleanedCount: Int = 0,
        val failedCount: Int = 0
    ) {
        val hasRecoveredEntries: Boolean
            get() = cleanedCount > 0 || failedCount > 0
    }

    internal data class PendingResumableDownload(
        val song: SongItem,
        val workingFile: File
    )

    internal data class PendingDownloadQueueEntry(
        val stableKey: String,
        val song: SongItem,
        val order: Int,
        val queuedAtMs: Long
    )

    data class StoredEntry(
        val name: String,
        val reference: String,
        val mediaUri: String,
        val localFilePath: String?,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val isDirectory: Boolean = false
    ) {
        val extension: String
            get() = name.substringAfterLast('.', "").lowercase()

        val nameWithoutExtension: String
            get() = name.substringBeforeLast('.', name)

        val playbackUri: String
            get() = mediaUri

        val displayName: String
            get() = name
    }

    data class MigrationResult(
        val movedFiles: Int,
        val skippedFiles: Int,
        val cleanupFailedFiles: Int = 0
    ) {
        val canSwitchDirectory: Boolean
            get() = skippedFiles == 0
    }

    enum class MigrationStage {
        PREPARING,
        COPYING,
        REWRITING_METADATA,
        CLEANING_UP,
        FINALIZING
    }

    data class MigrationProgress(
        val stage: MigrationStage,
        val totalFiles: Int,
        val processedFiles: Int,
        val copiedFiles: Int,
        val copiedBytes: Long,
        val totalBytes: Long,
        val metadataFilesProcessed: Int,
        val metadataFilesTotal: Int,
        val cleanupFilesProcessed: Int,
        val cleanupFilesTotal: Int,
        val currentFileName: String? = null
    ) {
        val stageProcessed: Int
            get() = when (stage) {
                MigrationStage.PREPARING -> 0
                MigrationStage.COPYING -> copiedFiles
                MigrationStage.REWRITING_METADATA -> metadataFilesProcessed
                MigrationStage.CLEANING_UP -> cleanupFilesProcessed
                MigrationStage.FINALIZING -> totalFiles
            }

        val stageTotal: Int
            get() = when (stage) {
                MigrationStage.PREPARING -> totalFiles
                MigrationStage.COPYING -> totalFiles
                MigrationStage.REWRITING_METADATA -> metadataFilesTotal
                MigrationStage.CLEANING_UP -> cleanupFilesTotal
                MigrationStage.FINALIZING -> totalFiles
            }

        val fraction: Float
            get() {
                val copyProgress = when {
                    totalFiles <= 0 -> 1f
                    totalBytes > 0L -> (copiedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                    else -> (copiedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
                }
                val rewriteProgress = when {
                    metadataFilesTotal <= 0 -> 1f
                    else -> (metadataFilesProcessed.toFloat() / metadataFilesTotal.toFloat()).coerceIn(0f, 1f)
                }
                val cleanupProgress = when {
                    cleanupFilesTotal <= 0 -> 1f
                    else -> (cleanupFilesProcessed.toFloat() / cleanupFilesTotal.toFloat()).coerceIn(0f, 1f)
                }
                return when (stage) {
                    MigrationStage.PREPARING -> 0.02f
                    MigrationStage.COPYING -> 0.02f + copyProgress * 0.83f
                    MigrationStage.REWRITING_METADATA -> 0.85f + rewriteProgress * 0.10f
                    MigrationStage.CLEANING_UP -> 0.95f + cleanupProgress * 0.04f
                    MigrationStage.FINALIZING -> 1f
                }.coerceIn(0f, 1f)
            }
    }

    internal data class TreeChildNameRefresh(
        val names: Set<String>,
        val isComplete: Boolean
    )

    data class DownloadLibrarySnapshot(
        val audioEntries: List<StoredEntry>,
        val audioEntriesByLookupKey: Map<String, StoredEntry>,
        val metadataEntriesByAudioName: Map<String, StoredEntry>,
        val metadataByAudioName: Map<String, DownloadedAudioMetadata>,
        val audioEntriesWithoutMetadata: List<StoredEntry>,
        val audioEntriesByStableKey: Map<String, List<StoredEntry>>,
        val audioEntriesBySongId: Map<Long, List<StoredEntry>>,
        val audioEntriesByMediaUri: Map<String, List<StoredEntry>>,
        val audioEntriesByRemoteTrackKey: Map<String, List<StoredEntry>>,
        val coverEntriesByName: Map<String, StoredEntry>,
        val lyricEntriesByName: Map<String, StoredEntry>,
        val knownReferences: Set<String>
    )

    internal enum class SnapshotEntryBucket {
        AUDIO,
        COVER,
        LYRIC
    }

    data class DownloadedAudioMetadata(
        val stableKey: String? = null,
        val songId: Long? = null,
        val identityAlbum: String? = null,
        val name: String? = null,
        val artist: String? = null,
        val coverUrl: String? = null,
        val matchedLyric: String? = null,
        val matchedTranslatedLyric: String? = null,
        val matchedLyricSource: String? = null,
        val matchedSongId: String? = null,
        val userLyricOffsetMs: Long = 0L,
        val customCoverUrl: String? = null,
        val customName: String? = null,
        val customArtist: String? = null,
        val originalName: String? = null,
        val originalArtist: String? = null,
        val originalCoverUrl: String? = null,
        val originalLyric: String? = null,
        val originalTranslatedLyric: String? = null,
        val mediaUri: String? = null,
        val channelId: String? = null,
        val audioId: String? = null,
        val subAudioId: String? = null,
        val coverPath: String? = null,
        val lyricPath: String? = null,
        val translatedLyricPath: String? = null,
        val durationMs: Long = 0L,
        val downloadFinalized: Boolean? = null
    )

    fun primeSettings(directoryUri: String?, directoryLabel: String?, fileNameTemplate: String? = null) {
        settings.prime(
            directoryUri = directoryUri,
            directoryLabel = directoryLabel,
            fileNameTemplate = fileNameTemplate
        )
        clearTreeDirectoryCache()
        invalidateSnapshotCache()
    }

    fun updateCustomDirectoryUri(uri: String?) {
        settings.updateDirectoryUri(uri)
        clearTreeDirectoryCache()
        invalidateSnapshotCache()
    }

    fun updateConfiguredTreeUri(uri: String?) {
        updateCustomDirectoryUri(uri)
    }

    fun updateCustomDirectoryLabel(label: String?) {
        settings.updateDirectoryLabel(label)
    }

    fun updateDownloadFileNameTemplate(template: String?) {
        settings.updateFileNameTemplate(template)
    }

    internal fun currentDownloadFileNameTemplate(): String? = settings.fileNameTemplate

    internal fun currentSnapshotCacheKey(context: Context): String {
        return snapshotCacheStore.currentKey(context)
    }

    internal fun ensureSnapshotCacheReady(context: Context): Boolean {
        return snapshotCacheStore.ensureReady(context)
    }

    internal fun cachedDownloadLibrarySnapshot(
        context: Context,
        restoreFromDisk: Boolean = true
    ): DownloadLibrarySnapshot? {
        return snapshotCacheStore.cachedSnapshot(context, restoreFromDisk)
    }

    internal fun directoryIdentity(uriString: String?): String? {
        return ManagedDownloadDirectoryIdentity.directoryIdentity(uriString)
    }

    internal fun areEquivalentDirectoryUris(first: String?, second: String?): Boolean {
        return ManagedDownloadDirectoryIdentity.areEquivalentDirectoryUris(first, second)
    }

    internal fun canonicalizeDirectoryUri(uriString: String?): String? {
        return ManagedDownloadDirectoryIdentity.normalizeConfiguredDirectoryUri(uriString)
    }

    fun describeConfiguredDirectory(
        context: Context,
        uriString: String? = settings.configuredDirectoryUri
    ): String {
        return settings.describeDirectory(context, uriString)
    }

    suspend fun hasMigratableDownloads(context: Context, directoryUri: String?): Boolean = withContext(Dispatchers.IO) {
        val root = resolveRoot(context, directoryUri) ?: return@withContext false
        collectManagedMigrationEntries(
            context = context,
            root = root,
            allowMetadataLessAudio = shouldIndexMetadataLessAudio(directoryUri)
        ).isNotEmpty()
    }

    suspend fun mayHaveMigratableDownloads(context: Context, directoryUri: String?): Boolean = withContext(Dispatchers.IO) {
        val root = resolveRoot(context, directoryUri) ?: return@withContext false
        collectManagedMigrationEntries(
            context = context,
            root = root,
            allowMetadataLessAudio = shouldIndexMetadataLessAudio(directoryUri)
        ).isNotEmpty()
    }

    suspend fun migrateManagedDownloads(
        context: Context,
        fromDirectoryUri: String?,
        toDirectoryUri: String?
    ): MigrationResult = withContext(Dispatchers.IO) {
        try {
            _migrationProgressFlow.value = null
            if (areEquivalentDirectoryUris(fromDirectoryUri, toDirectoryUri)) {
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }

            val sourceRoot = resolveRoot(context, fromDirectoryUri) ?: return@withContext MigrationResult(
                movedFiles = 0,
                skippedFiles = 0
            )
            val targetRoot = resolveRoot(context, toDirectoryUri)
                ?: throw IOException("目标下载目录不可用")

            val entries = collectManagedMigrationEntries(
                context = context,
                root = sourceRoot,
                allowMetadataLessAudio = shouldIndexMetadataLessAudio(fromDirectoryUri)
            )
            if (entries.isEmpty()) {
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }

            val metadataEntriesTotal = entries.count { it.entry.name.endsWith(METADATA_SUFFIX) }
            val progressTracker = ManagedMigrationProgressReporter(
                totalFiles = entries.size,
                totalBytes = entries.sumOf { it.entry.sizeBytes.coerceAtLeast(0L) },
                metadataFilesTotal = metadataEntriesTotal,
                onProgress = { progress -> _migrationProgressFlow.value = progress }
            )
            progressTracker.startPreparing(entries.firstOrNull()?.entry?.name)
            val targetIndex = buildMigrationTargetIndex(context, targetRoot)
            val namePlan = buildMigrationNamePlan(entries, targetIndex)

            val copyLimiter = Semaphore(migrationCopyParallelism(sourceRoot, targetRoot))
            val copyResults = coroutineScope {
                entries.map { migrationEntry ->
                    async(Dispatchers.IO) {
                        copyLimiter.withPermit {
                            migrationCopyWorker.copyEntry(
                                context = context,
                                targetRoot = targetRoot,
                                migrationEntry = migrationEntry,
                                targetIndex = targetIndex,
                                namePlan = namePlan,
                                progressTracker = progressTracker
                            )
                        }
                    }
                }.awaitAll()
            }
            val copiedEntries = copyResults.mapNotNull { it.copiedEntry }
            val skippedFiles = copyResults.count { it.copiedEntry == null }

            if (skippedFiles > 0) {
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                return@withContext MigrationResult(
                    movedFiles = 0,
                    skippedFiles = skippedFiles
                )
            }

            val rewriteFailedFiles = rewriteMigratedMetadataReferences(
                context = context,
                targetRoot = targetRoot,
                copiedEntries = copiedEntries,
                progressTracker = progressTracker
            )
            if (rewriteFailedFiles > 0) {
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                return@withContext MigrationResult(
                    movedFiles = 0,
                    skippedFiles = rewriteFailedFiles
                )
            }

            val cleanupFailedFiles = cleanupMigratedEntries(
                context = context,
                copiedEntries = copiedEntries,
                sourceRoot = sourceRoot,
                progressTracker = progressTracker
            )
            progressTracker.finishAll()

            invalidateSnapshotCache(context)

            MigrationResult(
                movedFiles = copiedEntries.size,
                skippedFiles = 0,
                cleanupFailedFiles = cleanupFailedFiles
            )
        } finally {
            _migrationProgressFlow.value = null
        }
    }

    fun releasePersistedDirectoryPermission(context: Context, uriString: String?) {
        val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            NPLogger.w(TAG, "释放下载目录权限失败: ${it.message}")
        }
    }

    fun hasDownloadedAudio(
        context: Context,
        song: SongItem,
        forceRefresh: Boolean = false
    ): Boolean {
        return findDownloadedAudioBlocking(context, song, forceRefresh) != null
    }

    fun buildDisplayBaseName(song: SongItem): String {
        return renderManagedDownloadBaseName(song, settings.fileNameTemplate)
    }

    internal fun buildWorkingFileName(songKey: String, fileName: String): String {
        return ManagedDownloadRecoveryFiles.buildWorkingFileName(songKey, fileName)
    }

    internal fun buildWorkingSongKeyHash(songKey: String): String {
        return ManagedDownloadRecoveryFiles.buildWorkingSongKeyHash(songKey)
    }

    fun createWorkingFile(context: Context, songKey: String, fileName: String): File {
        return ManagedDownloadRecoveryFiles.createWorkingFile(context, songKey, fileName)
    }

    internal fun buildWorkingHlsCheckpointFile(workingFile: File): File {
        return ManagedDownloadRecoveryFiles.buildWorkingHlsCheckpointFile(workingFile)
    }

    internal fun buildWorkingResumeMetadataFile(workingFile: File): File {
        return ManagedDownloadRecoveryFiles.buildWorkingResumeMetadataFile(workingFile)
    }

    internal fun shouldPreserveWorkingFileForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return ManagedDownloadRecoveryFiles.shouldPreserveWorkingFileForResume(entry, nowMs)
    }

    internal fun shouldPreserveWorkingCheckpointForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return ManagedDownloadRecoveryFiles.shouldPreserveWorkingCheckpointForResume(entry, nowMs)
    }

    internal fun shouldPreserveWorkingResumeMetadataForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return ManagedDownloadRecoveryFiles.shouldPreserveWorkingResumeMetadataForResume(entry, nowMs)
    }

    internal fun saveWorkingResumeMetadata(
        workingFile: File,
        song: SongItem
    ) {
        ManagedDownloadRecoveryFiles.saveWorkingResumeMetadata(workingFile, song)
    }

    internal fun deleteWorkingResumeMetadata(workingFile: File?) {
        ManagedDownloadRecoveryFiles.deleteWorkingResumeMetadata(workingFile)
    }

    internal fun deleteWorkingDownloadArtifacts(workingFile: File?) {
        ManagedDownloadRecoveryFiles.deleteWorkingDownloadArtifacts(workingFile)
    }

    internal fun deletePendingWorkingDownloadArtifacts(
        context: Context,
        songKeys: Collection<String>
    ): Set<String> {
        return ManagedDownloadRecoveryFiles.deletePendingWorkingDownloadArtifacts(context, songKeys)
    }

    internal fun deletePendingWorkingDownloadArtifactsInDirectory(
        stagingDir: File,
        songKeys: Collection<String>
    ): Set<String> {
        return ManagedDownloadRecoveryFiles.deletePendingWorkingDownloadArtifactsInDirectory(stagingDir, songKeys)
    }

    internal fun listPendingResumableDownloads(context: Context): List<PendingResumableDownload> {
        return ManagedDownloadRecoveryFiles.listPendingResumableDownloads(context)
    }

    internal fun upsertPendingDownloadQueue(
        context: Context,
        songs: List<SongItem>
    ) {
        ManagedDownloadRecoveryFiles.upsertPendingDownloadQueue(context, songs)
    }

    internal fun listPendingQueuedDownloads(context: Context): List<PendingDownloadQueueEntry> {
        return ManagedDownloadRecoveryFiles.listPendingQueuedDownloads(context)
    }

    internal fun removePendingDownloadQueueEntries(
        context: Context,
        songKeys: Collection<String>
    ) {
        ManagedDownloadRecoveryFiles.removePendingDownloadQueueEntries(context, songKeys)
    }

    internal fun clearPendingDownloadQueue(context: Context) {
        ManagedDownloadRecoveryFiles.clearPendingDownloadQueue(context)
    }

    internal fun markCancelledDownloadKeys(
        context: Context,
        songKeys: Collection<String>
    ) {
        ManagedDownloadRecoveryFiles.markCancelledDownloadKeys(context, songKeys)
    }

    internal fun listCancelledDownloadKeys(context: Context): Set<String> {
        return ManagedDownloadRecoveryFiles.listCancelledDownloadKeys(context)
    }

    internal fun removeCancelledDownloadKeys(
        context: Context,
        songKeys: Collection<String>
    ) {
        ManagedDownloadRecoveryFiles.removeCancelledDownloadKeys(context, songKeys)
    }

    internal fun clearCancelledDownloadKeys(context: Context) {
        ManagedDownloadRecoveryFiles.clearCancelledDownloadKeys(context)
    }

    internal fun upsertPendingDownloadQueueInFile(
        queueFile: File,
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadRecoveryFiles.upsertPendingDownloadQueueInFile(queueFile, songs, nowMs)
    }

    internal fun listPendingQueuedDownloadsFromFile(queueFile: File): List<PendingDownloadQueueEntry> {
        return ManagedDownloadRecoveryFiles.listPendingQueuedDownloadsFromFile(queueFile)
    }

    internal fun removePendingDownloadQueueEntriesFromFile(
        queueFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadRecoveryFiles.removePendingDownloadQueueEntriesFromFile(queueFile, songKeys, nowMs)
    }

    internal fun clearPendingDownloadQueueFile(
        queueFile: File,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadRecoveryFiles.clearPendingDownloadQueueFile(queueFile, nowMs)
    }

    internal fun markCancelledDownloadKeysInFile(
        keysFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadRecoveryFiles.markCancelledDownloadKeysInFile(keysFile, songKeys, nowMs)
    }

    internal fun listCancelledDownloadKeysFromFile(keysFile: File): Set<String> {
        return ManagedDownloadRecoveryFiles.listCancelledDownloadKeysFromFile(keysFile)
    }

    internal fun removeCancelledDownloadKeysFromFile(
        keysFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadRecoveryFiles.removeCancelledDownloadKeysFromFile(keysFile, songKeys, nowMs)
    }

    internal fun clearCancelledDownloadKeysFile(
        keysFile: File,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadRecoveryFiles.clearCancelledDownloadKeysFile(keysFile, nowMs)
    }

    internal fun listPendingResumableDownloadsInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): List<PendingResumableDownload> {
        return ManagedDownloadRecoveryFiles.listPendingResumableDownloadsInDirectory(stagingDir, nowMs)
    }

    internal fun consumeStartupRecoveryResult(): StartupRecoveryResult {
        val result = startupRecoveryResult
        startupRecoveryResult = StartupRecoveryResult()
        return result
    }

    fun cleanupStagingFiles(context: Context): StartupRecoveryResult {
        return ManagedDownloadRecoveryFiles.cleanupStagingFiles(context)
    }

    internal fun cleanupStagingFilesInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): StartupRecoveryResult {
        return ManagedDownloadRecoveryFiles.cleanupStagingFilesInDirectory(stagingDir, nowMs)
    }

    fun findAudio(
        context: Context,
        song: SongItem,
        forceRefresh: Boolean = false
    ): StoredEntry? {
        return findDownloadedAudioBlocking(context, song, forceRefresh)
    }

    fun peekDownloadedAudio(song: SongItem): StoredEntry? {
        return snapshotCacheStore.peekSnapshot()?.let { snapshot ->
            findAudioEntry(snapshot, song)
        }
    }

    fun peekCoverReference(audio: StoredEntry): String? {
        val snapshot = snapshotCacheStore.peekSnapshot() ?: return null
        return ManagedDownloadCoverLookup.findCoverReference(snapshot, audio)
    }

    fun buildCandidateBaseNames(song: SongItem): List<String> {
        return candidateManagedDownloadBaseNames(song, settings.fileNameTemplate)
    }

    suspend fun findDownloadedAudio(
        context: Context,
        song: SongItem,
        forceRefresh: Boolean = false
    ): StoredEntry? = withContext(Dispatchers.IO) {
        findDownloadedAudioBlocking(context, song, forceRefresh)
    }

    fun findDownloadedAudio(snapshot: DownloadLibrarySnapshot, song: SongItem): StoredEntry? {
        return findAudioEntry(snapshot, song)
    }

    suspend fun queryStoredEntry(context: Context, reference: String?): StoredEntry? = withContext(Dispatchers.IO) {
        val target = reference?.takeIf { it.isNotBlank() } ?: return@withContext null
        val cachedEntry = buildDownloadLibrarySnapshotBlocking(context).audioEntriesByLookupKey[target]
            ?: return@withContext null
        if (isReferenceAccessible(context, cachedEntry.playbackUri)) {
            return@withContext cachedEntry
        }
        buildDownloadLibrarySnapshotBlocking(context, forceRefresh = true).audioEntriesByLookupKey[target]
            ?.takeIf { refreshedEntry -> isReferenceAccessible(context, refreshedEntry.playbackUri) }
    }

    suspend fun listDownloadedAudio(context: Context): List<StoredEntry> = withContext(Dispatchers.IO) {
        buildDownloadLibrarySnapshotBlocking(context).audioEntries
    }

    suspend fun buildDownloadLibrarySnapshot(
        context: Context,
        forceRefresh: Boolean = false
    ): DownloadLibrarySnapshot = withContext(Dispatchers.IO) {
        buildDownloadLibrarySnapshotBlocking(context, forceRefresh)
    }

    fun isReferenceAccessible(context: Context, reference: String?): Boolean {
        return existsInternal(context, reference)
    }

    private fun findDownloadedAudioBlocking(
        context: Context,
        song: SongItem,
        forceRefresh: Boolean = false
    ): StoredEntry? {
        val snapshot = buildDownloadLibrarySnapshotBlocking(context, forceRefresh = forceRefresh)
        val entry = findAudioEntry(snapshot, song) ?: return null
        if (isReferenceAccessible(context, entry.playbackUri)) {
            return entry
        }
        if (forceRefresh) {
            return null
        }
        return findDownloadedAudioBlocking(context, song, forceRefresh = true)
    }

    private fun buildDownloadLibrarySnapshotBlocking(
        context: Context,
        forceRefresh: Boolean = false
    ): DownloadLibrarySnapshot = synchronized(snapshotBuildLock) {
        val cacheKey = snapshotCacheStore.currentKey(context)
        if (!forceRefresh) {
            snapshotCacheStore.cachedSnapshot(context, restoreFromDisk = false)
                ?.let { return@synchronized it }
            snapshotCacheStore.restoreFromDisk(context, expectedKey = cacheKey)
                ?.let { return@synchronized it }
        }

        val root = resolveRootBlocking(context)
        val rootEntries = listChildren(context, root).filterNot(StoredEntry::isDirectory)
        val audioEntries = rootEntries.filter { it.extension in audioExtensions }
        val metadataEntries = rootEntries.filter { it.name.endsWith(METADATA_SUFFIX) }
        val metadataEntriesByAudioName = metadataEntries.associateBy { entry ->
            entry.name.removeSuffix(METADATA_SUFFIX)
        }
        val metadataByAudioName = metadataEntries.mapNotNull { entry ->
            parseDownloadedAudioMetadata(context, entry)?.let { metadata ->
                entry.name.removeSuffix(METADATA_SUFFIX) to metadata
            }
        }.toMap()
        val coverEntries = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
        val lyricEntries = listSubdirectoryEntries(context, root, LYRIC_SUBDIRECTORY)
        val coverEntriesByName = coverEntries.associateBy(StoredEntry::name)
        val lyricEntriesByName = lyricEntries.associateBy(StoredEntry::name)
        val allowMetadataLessAudio = shouldIndexMetadataLessAudio()
        val managedAudioEntries = audioEntries.filter { entry ->
            shouldTreatAudioAsManaged(
                audioName = entry.name,
                metadataAudioNames = metadataEntriesByAudioName.keys,
                coverEntryNames = coverEntriesByName.keys,
                lyricEntryNames = lyricEntriesByName.keys,
                allowMetadataLessAudio = allowMetadataLessAudio
            )
        }
        val skippedForeignAudioCount = audioEntries.size - managedAudioEntries.size
        if (skippedForeignAudioCount > 0) {
            NPLogger.d(
                TAG,
                "跳过非托管音频扫描: total=${audioEntries.size}, managed=${managedAudioEntries.size}, skipped=$skippedForeignAudioCount"
            )
        }
        return@synchronized composeSnapshot(
            audioEntries = managedAudioEntries,
            metadataEntries = metadataEntriesByAudioName.values.toList(),
            metadataByAudioName = metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries
        ).also { snapshot ->
            snapshotCacheStore.putSnapshot(context, cacheKey, snapshot)
        }
    }

    private fun composeSnapshot(
        audioEntries: List<StoredEntry>,
        metadataEntries: List<StoredEntry>,
        metadataByAudioName: Map<String, DownloadedAudioMetadata>,
        coverEntries: List<StoredEntry>,
        lyricEntries: List<StoredEntry>
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = audioEntries,
            metadataEntries = metadataEntries,
            metadataByAudioName = metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries
        )
    }

    internal fun emptyDownloadLibrarySnapshot(): DownloadLibrarySnapshot {
        return composeSnapshot(
            audioEntries = emptyList(),
            metadataEntries = emptyList(),
            metadataByAudioName = emptyMap(),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )
    }

    private suspend fun rewriteMigratedMetadataReferences(
        context: Context,
        targetRoot: RootHandle,
        copiedEntries: List<CopiedMigrationEntry>,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): Int {
        return migrationFinalizer.rewriteMigratedMetadataReferences(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries,
            progressTracker = progressTracker
        )
    }

    private suspend fun cleanupMigratedEntries(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        sourceRoot: RootHandle,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): Int {
        return migrationFinalizer.cleanupMigratedEntries(
            context = context,
            copiedEntries = copiedEntries,
            sourceRoot = sourceRoot,
            progressTracker = progressTracker
        )
    }

    private suspend fun rollbackMigratedEntries(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        targetRoot: RootHandle
    ): Int {
        return migrationFinalizer.rollbackMigratedEntries(
            context = context,
            copiedEntries = copiedEntries,
            targetRoot = targetRoot
        )
    }

    internal fun rewriteManagedMetadataReferences(
        rawJson: String,
        referenceMap: Map<String, String>
    ): String {
        return ManagedDownloadMetadataCodec.rewriteManagedMetadataReferences(rawJson, referenceMap)
    }

    internal fun shouldTreatAudioAsManaged(
        audioName: String,
        metadataAudioNames: Set<String>,
        coverEntryNames: Set<String>,
        lyricEntryNames: Set<String>,
        allowMetadataLessAudio: Boolean
    ): Boolean {
        return ManagedDownloadManagedAudioPolicy.shouldTreatAudioAsManaged(
            audioName = audioName,
            metadataAudioNames = metadataAudioNames,
            coverEntryNames = coverEntryNames,
            lyricEntryNames = lyricEntryNames,
            allowMetadataLessAudio = allowMetadataLessAudio
        )
    }

    private fun shouldIndexMetadataLessAudio(): Boolean {
        return shouldIndexMetadataLessAudio(settings.configuredDirectoryUri)
    }

    suspend fun findMetadataForAudio(context: Context, audio: StoredEntry): StoredEntry? = withContext(Dispatchers.IO) {
        val snapshot = resolveSnapshotForIndexedLookup(context)
            ?: buildDownloadLibrarySnapshotBlocking(context)
        snapshot.metadataEntriesByAudioName[audio.name]
            ?: findMetadataByDirectLookup(context, audio)
    }

    private fun findMetadataForAudioBlocking(context: Context, audio: StoredEntry): StoredEntry? {
        val snapshot = resolveSnapshotForIndexedLookup(context)
        return snapshot?.metadataEntriesByAudioName?.get(audio.name)
            ?: findMetadataByDirectLookup(context, audio)
    }

    internal fun metadataReferenceForAudio(audio: StoredEntry): String? {
        val reference = audio.reference.takeIf(String::isNotBlank) ?: return null
        return "$reference$METADATA_SUFFIX"
    }

    private fun findMetadataByDirectLookup(context: Context, audio: StoredEntry): StoredEntry? {
        val metadataName = "${audio.name}$METADATA_SUFFIX"
        return when (val root = resolveRootBlocking(context)) {
            is RootHandle.FileRoot -> {
                val metadataFile = File(root.dir, metadataName)
                if (metadataFile.exists() && metadataFile.isFile) metadataFile.toStoredEntry() else null
            }
            is RootHandle.TreeRoot -> {
                treeChildRegistry.cachedTreeChild(context, root.tree, metadataName)
                    ?.takeUnless(QueriedTreeChild::isDirectory)
                    ?.toStoredEntry()
            }
        }
    }

    suspend fun saveMetadata(context: Context, audio: StoredEntry, json: String): Boolean = withContext(Dispatchers.IO) {
        saveMetadataBlocking(context, audio, json)
    }

    private fun saveMetadataBlocking(context: Context, audio: StoredEntry, json: String): Boolean {
        val metadata = parseDownloadedAudioMetadataJson(json)
        if (metadata == null) {
            invalidateSnapshotCache(context)
            return false
        }
        val metadataEntry = writeRootText(
            context = context,
            root = resolveRootBlocking(context),
            displayName = "${audio.name}$METADATA_SUFFIX",
            content = json,
            invalidateSnapshot = false
        )
        if (metadataEntry == null) {
            invalidateSnapshotCache(context)
            return false
        }
        val storedMetadata = readTextInternal(context, metadataEntry.reference)
            ?.let(::parseDownloadedAudioMetadataJson)
        if (!isMetadataWriteVerified(expected = metadata, actual = storedMetadata)) {
            invalidateSnapshotCache(context)
            NPLogger.w(TAG, "下载元数据写入读回校验失败: ${audio.name}")
            return false
        }
        if (!updateSnapshotCacheAfterMetadataWrite(context, metadataEntry, metadata)) {
            invalidateSnapshotCache(context)
        }
        return true
    }

    suspend fun markDownloadedAudioFinalized(context: Context, audio: StoredEntry): Boolean = withContext(Dispatchers.IO) {
        markDownloadedAudioFinalizedBlocking(context, audio)
    }

    private fun markDownloadedAudioFinalizedBlocking(context: Context, audio: StoredEntry): Boolean {
        val metadataEntry = findMetadataForAudioBlocking(context, audio) ?: return false
        val raw = readTextInternal(context, metadataEntry.reference) ?: return false
        val finalized = finalizedDownloadedMetadataJson(raw) ?: return false
        return runCatching {
            saveMetadataBlocking(context, audio, finalized)
        }.onFailure {
            NPLogger.w(TAG, "恢复下载元数据 finalized 标记失败: ${audio.name}, ${it.message}")
        }.getOrDefault(false)
    }

    suspend fun usesDocumentTree(context: Context): Boolean = withContext(Dispatchers.IO) {
        resolveRootBlocking(context) is RootHandle.TreeRoot
    }

    suspend fun readText(context: Context, reference: String): String? = withContext(Dispatchers.IO) {
        readTextInternal(context, reference)
    }

    suspend fun exists(context: Context, reference: String?): Boolean = withContext(Dispatchers.IO) {
        existsInternal(context, reference)
    }

    suspend fun deleteReference(context: Context, reference: String?): Boolean = withContext(Dispatchers.IO) {
        deleteInternal(context, reference)
    }

    suspend fun deleteReferences(context: Context, references: Collection<String?>): Set<String> = withContext(Dispatchers.IO) {
        deleteReferencesInternalConcurrently(
            context = context,
            references = references,
            invalidateSnapshot = true
        )
    }

    suspend fun saveAudioFromTemp(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?,
        expectedSizeBytes: Long? = null,
        seedMetadataJson: String? = null
    ): StoredEntry = withContext(Dispatchers.IO) {
        saveAudioFromTempBlocking(
            context = context,
            tempFile = tempFile,
            fileName = fileName,
            mimeType = mimeType,
            expectedSizeBytes = expectedSizeBytes,
            seedMetadataJson = seedMetadataJson
        )
    }

    private fun saveAudioFromTempBlocking(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?,
        expectedSizeBytes: Long?,
        seedMetadataJson: String?
    ): StoredEntry {
        val actualSizeBytes = tempFile.length().coerceAtLeast(0L)
        if (expectedSizeBytes != null && expectedSizeBytes > 0L && actualSizeBytes != expectedSizeBytes) {
            throw IOException("下载文件大小不匹配: $actualSizeBytes/$expectedSizeBytes")
        }
        val storedEntry = when (val root = resolveRootBlocking(context)) {
            is RootHandle.FileRoot -> {
                val finalName = treeChildRegistry.reserveUniqueFileChildName(root.dir, fileName)
                val pendingTarget = File(root.dir, buildPendingAudioWriteName(finalName))
                var seedMetadataEntry: StoredEntry? = null
                try {
                    tempFile.inputStream().use { input ->
                        pendingTarget.outputStream().use { output ->
                            input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                        }
                    }
                    verifyFileCommittedLength(
                        target = pendingTarget,
                        expectedSizeBytes = actualSizeBytes,
                        description = pendingTarget.name
                    )
                    seedMetadataEntry = writeSeedMetadataBeforeAudioCommit(
                        context = context,
                        root = root,
                        audioName = finalName,
                        seedMetadataJson = seedMetadataJson
                    )
                    val target = File(root.dir, finalName)
                    if (!pendingTarget.renameTo(target)) {
                        throw IOException("无法提交下载文件: $finalName")
                    }
                    val verifiedSize = verifyFileCommittedLength(
                        target = target,
                        expectedSizeBytes = actualSizeBytes,
                        description = finalName
                    )
                    target.toStoredEntry().copy(sizeBytes = verifiedSize)
                } catch (error: Throwable) {
                    if (pendingTarget.exists()) {
                        pendingTarget.delete()
                    }
                    deleteSeedMetadataAfterAudioCommitFailure(context, root, seedMetadataEntry)
                    treeChildRegistry.forgetFileChildName(root.dir, finalName)
                    throw error
                }
            }

            is RootHandle.TreeRoot -> {
                val finalName = treeChildRegistry.reserveUniqueTreeChildName(context, root.tree, fileName)
                var seedMetadataEntry: StoredEntry? = null
                var pendingTarget: DocumentFile? = null
                var pendingName: String? = null
                try {
                    val committedAtMs = System.currentTimeMillis()
                    val createdPendingName = buildPendingAudioWriteName(finalName)
                    pendingName = createdPendingName
                    pendingTarget = createRootFile(
                        context = context,
                        parent = root.tree,
                        desiredName = createdPendingName,
                        mimeType = mimeTypeFromName(finalName, mimeType),
                        replace = false
                    )
                    context.contentResolver.openOutputStream(pendingTarget.uri, "w")?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                        }
                    } ?: throw IOException("无法打开下载目录输出流")
                    verifyDocumentCommittedLength(
                        context = context,
                        uri = pendingTarget.uri,
                        expectedSizeBytes = actualSizeBytes,
                        description = "staging→SAF: $createdPendingName"
                    )
                    seedMetadataEntry = writeSeedMetadataBeforeAudioCommit(
                        context = context,
                        root = root,
                        audioName = finalName,
                        seedMetadataJson = seedMetadataJson
                    )
                    if (pendingTarget.renameTo(finalName)) {
                        val entry = verifiedTreeStoredEntry(
                            context = context,
                            target = pendingTarget,
                            expectedName = finalName,
                            expectedSizeBytes = actualSizeBytes,
                            fallbackLastModifiedMs = committedAtMs,
                            description = finalName
                        )
                        treeChildRegistry.forgetTreeChildName(root.tree, createdPendingName)
                        if (entry.name != finalName) {
                            treeChildRegistry.forgetTreeChildName(root.tree, finalName)
                        }
                        treeChildRegistry.rememberTreeChild(root.tree, entry)
                        entry
                    } else {
                        commitTreeAudioAfterRenameFailure(
                            context = context,
                            parent = root.tree,
                            pendingTarget = pendingTarget,
                            pendingName = createdPendingName,
                            finalName = finalName,
                            mimeType = mimeTypeFromName(finalName, mimeType),
                            tempFile = tempFile,
                            actualSizeBytes = actualSizeBytes,
                            committedAtMs = committedAtMs
                        )
                    }
                } catch (error: Throwable) {
                    pendingTarget?.let { target ->
                        deleteContentReference(context, target.uri.toString(), target.uri)
                    }
                    pendingName?.let { treeChildRegistry.forgetTreeChildName(root.tree, it) }
                    deleteSeedMetadataAfterAudioCommitFailure(context, root, seedMetadataEntry)
                    treeChildRegistry.forgetTreeChildName(root.tree, finalName)
                    throw error
                }
            }
        }
        if (tempFile.exists() && !tempFile.delete()) {
            NPLogger.w(TAG, "删除下载临时文件失败: ${tempFile.name}")
        }
        if (!updateSnapshotCacheAfterStoredEntryWrite(context, storedEntry, SnapshotEntryBucket.AUDIO)) {
            invalidateSnapshotCache(context)
        }
        seedMetadataJson
            ?.let(::parseDownloadedAudioMetadataJson)
            ?.let { metadata ->
                val metadataEntry = findMetadataForAudioBlocking(context, storedEntry)
                if (metadataEntry == null || !updateSnapshotCacheAfterMetadataWrite(context, metadataEntry, metadata)) {
                    invalidateSnapshotCache(context)
                }
            }
        return storedEntry
    }

    private fun writeSeedMetadataBeforeAudioCommit(
        context: Context,
        root: RootHandle,
        audioName: String,
        seedMetadataJson: String?
    ): StoredEntry? {
        val content = seedMetadataJson?.takeIf(String::isNotBlank) ?: return null
        return writeRootText(
            context = context,
            root = root,
            displayName = "$audioName$METADATA_SUFFIX",
            content = content,
            invalidateSnapshot = false
        )
    }

    private fun deleteSeedMetadataAfterAudioCommitFailure(
        context: Context,
        root: RootHandle,
        metadataEntry: StoredEntry?
    ) {
        metadataEntry ?: return
        runCatching {
            deleteInternal(
                context = context,
                reference = metadataEntry.reference,
                allowedRoot = root,
                invalidateSnapshot = false
            )
        }
    }

    fun commitCoverFile(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?
    ): StoredEntry? {
        val sourceFile = tempFile.takeIf(File::exists) ?: return null
        return writeSubdirectoryFileBlocking(
            context = context,
            subdirectory = COVER_SUBDIRECTORY,
            displayName = fileName,
            sourceFile = sourceFile,
            mimeType = mimeTypeFromName(fileName, mimeType)
        )
    }

    fun commitCoverBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String?
    ): StoredEntry? {
        if (bytes.isEmpty()) {
            return null
        }
        return writeSubdirectoryBytesBlocking(
            context = context,
            subdirectory = COVER_SUBDIRECTORY,
            displayName = fileName,
            bytes = bytes,
            mimeType = mimeTypeFromName(fileName, mimeType)
        )
    }

    suspend fun saveLyricText(
        context: Context,
        displayName: String,
        content: String
    ): String? = withContext(Dispatchers.IO) {
        saveLyricTextBlocking(context, displayName, content)
    }

    private fun saveLyricTextBlocking(context: Context, displayName: String, content: String): String? {
        return writeSubdirectoryBytesBlocking(
            context = context,
            subdirectory = LYRIC_SUBDIRECTORY,
            displayName = displayName,
            bytes = content.toByteArray(Charsets.UTF_8),
            mimeType = mimeTypeFromName(displayName, null)
        )?.reference
    }

    fun overwriteLyric(context: Context, fileName: String, content: String): String? {
        return saveLyricTextBlocking(context, fileName, content)
    }

    private fun resolveSnapshotForIndexedLookup(context: Context): DownloadLibrarySnapshot? {
        snapshotCacheStore.peekSnapshot()?.let { return it }
        if (ensureSnapshotCacheReady(context)) {
            snapshotCacheStore.peekSnapshot()?.let { return it }
        }
        return null
    }

    fun findLyricLocation(
        context: Context,
        songId: Long,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): String? {
        val snapshot = resolveSnapshotForIndexedLookup(context)
            ?: buildDownloadLibrarySnapshotBlocking(context)
        return ManagedDownloadLyricStore.findLyricLocation(
            snapshot = snapshot,
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            translated = translated
        )
    }

    fun writeLyrics(
        context: Context,
        songId: Long,
        baseName: String,
        content: String,
        translated: Boolean
    ): String? {
        val fileNameByName = ManagedDownloadLyricStore.lyricFileName(baseName, translated)
        NPLogger.d(TAG, "写入歌词文件: fileName=$fileNameByName, translated=$translated, songId=$songId")
        return overwriteLyric(context, fileNameByName, content)
    }

    fun readLyrics(context: Context, song: SongItem, translated: Boolean): String? {
        val snapshot = buildDownloadLibrarySnapshotBlocking(context)
        val resolvedAudio = findAudioEntry(snapshot, song)
        val resolvedMetadata = resolvedAudio?.let { snapshot.metadataByAudioName[it.name] }
        val embeddedLyric = ManagedDownloadLyricStore.selectedEmbeddedLyric(resolvedMetadata, translated)
        if (embeddedLyric != null && embeddedLyric.isBlank()) {
            return ""
        }
        val reference = ManagedDownloadLyricStore.resolveManagedLyricReference(
            context = context,
            snapshot = snapshot,
            song = song,
            resolvedAudio = resolvedAudio,
            resolvedMetadata = resolvedMetadata,
            translated = translated,
            fileNameTemplate = settings.fileNameTemplate,
            exists = ::existsInternal
        )
        if (reference != null) {
            return readTextInternal(context, reference)
        }
        return ManagedDownloadLyricStore.fallbackEmbeddedLyric(resolvedMetadata, translated)
    }

    fun toPlayableUri(reference: String?): String? {
        if (reference.isNullOrBlank()) return null
        return if (reference.startsWith("/")) {
            Uri.fromFile(File(reference)).toString()
        } else {
            reference
        }
    }

    suspend fun findCoverReference(context: Context, audio: StoredEntry): String? = withContext(Dispatchers.IO) {
        val snapshot = resolveSnapshotForIndexedLookup(context)
            ?: buildDownloadLibrarySnapshotBlocking(context)
        ManagedDownloadCoverLookup.findCoverReference(snapshot, audio)
    }

    suspend fun findReusableCoverReference(
        context: Context,
        song: SongItem,
        excludedAudioName: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val snapshot = resolveSnapshotForIndexedLookup(context)
            ?: buildDownloadLibrarySnapshotBlocking(context)
        findReusableCoverReference(
            snapshot = snapshot,
            song = song,
            excludedAudioName = excludedAudioName
        )
    }

    internal fun findReusableCoverReference(
        snapshot: DownloadLibrarySnapshot,
        song: SongItem,
        excludedAudioName: String? = null
    ): String? {
        return ManagedDownloadCoverLookup.findReusableCoverReference(snapshot, song, excludedAudioName)
    }

    private suspend fun resolveRoot(context: Context, directoryUriString: String?): RootHandle? = withContext(Dispatchers.IO) {
        resolveRootBlocking(context, directoryUriString)
    }

    private fun resolveRootBlocking(context: Context): RootHandle {
        return rootResolver.resolveConfiguredRoot(
            context = context,
            configuredDirectoryUri = settings.configuredDirectoryUri,
            onUnavailableTreeRoot = { configuredUri ->
                NPLogger.w(TAG, "自定义下载目录不可用，回退默认目录: $configuredUri")
            }
        )
    }

    private fun resolveRootBlocking(context: Context, directoryUriString: String?): RootHandle? {
        return rootResolver.resolveRoot(context, directoryUriString)
    }

    private fun findAudioEntry(
        snapshot: DownloadLibrarySnapshot,
        song: SongItem
    ): StoredEntry? {
        return ManagedDownloadStorageLookup.findAudioEntry(
            snapshot = snapshot,
            song = song,
            fileNameTemplate = settings.fileNameTemplate
        )?.let { result ->
            if (LOG_HOT_AUDIO_HITS) {
                NPLogger.d(TAG, "命中已下载音频(${result.hitType}): song=${song.displayName()}, file=${result.entry.name}")
            }
            result.entry
        }
    }

    private fun findAudioEntry(audioEntries: List<StoredEntry>, baseNames: List<String>): StoredEntry? {
        return ManagedDownloadStorageLookup.findAudioEntry(audioEntries, baseNames)
    }

    private fun listChildren(context: Context, root: RootHandle): List<StoredEntry> {
        return treeDirectories.listChildren(context, root)
    }

    private fun shouldIndexMetadataLessAudio(directoryUri: String?): Boolean {
        return normalizeDirectoryUri(directoryUri) == null
    }

    private fun collectManagedMigrationEntries(
        context: Context,
        root: RootHandle,
        allowMetadataLessAudio: Boolean
    ): List<ManagedMigrationEntry> {
        val rootEntries = listChildren(context, root).filterNot(StoredEntry::isDirectory)
        val metadataEntries = rootEntries.filter { it.name.endsWith(METADATA_SUFFIX) }
        val coverEntries = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
        val lyricEntries = listSubdirectoryEntries(context, root, LYRIC_SUBDIRECTORY)
        val metadataEntriesByAudioName = metadataEntries.associateBy { entry ->
            entry.name.removeSuffix(METADATA_SUFFIX)
        }
        val parsedMetadataByAudioName = metadataEntriesByAudioName.mapNotNull { (audioName, entry) ->
            parseDownloadedAudioMetadata(context, entry)?.let { metadata ->
                audioName to metadata
            }
        }.toMap()
        return ManagedDownloadMigrationEntryCollector.collect(
            rootEntries = rootEntries,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries,
            parsedMetadataByAudioName = parsedMetadataByAudioName,
            allowMetadataLessAudio = allowMetadataLessAudio
        )
    }

    private fun listSubdirectoryEntries(context: Context, root: RootHandle, subdirectory: String): List<StoredEntry> {
        return treeDirectories.listSubdirectoryEntries(context, root, subdirectory)
    }

    internal fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): List<String> {
        return ManagedDownloadStorageNaming.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            translated = translated
        )
    }

    private fun parseDownloadedAudioMetadata(
        context: Context,
        entry: StoredEntry
    ): DownloadedAudioMetadata? {
        val raw = readTextInternal(context, entry.reference) ?: return null
        return parseDownloadedAudioMetadataJson(raw)
    }

    internal fun serializeSnapshotCachePayload(
        cacheKey: String,
        snapshot: DownloadLibrarySnapshot
    ): String {
        return ManagedDownloadSnapshotIndex.serializePayload(cacheKey, snapshot)
    }

    internal fun deserializeSnapshotCachePayload(
        raw: String,
        expectedKey: String? = null
    ): Pair<String, DownloadLibrarySnapshot>? {
        return ManagedDownloadSnapshotIndex.deserializePayload(raw, expectedKey)
    }

    internal fun applyMetadataWriteToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        metadataEntry: StoredEntry,
        metadata: DownloadedAudioMetadata
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.applyMetadataWrite(snapshot, metadataEntry, metadata)
    }

    internal fun applyStoredEntryWriteToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        storedEntry: StoredEntry,
        bucket: SnapshotEntryBucket
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.applyStoredEntryWrite(snapshot, storedEntry, bucket)
    }

    internal fun applyReferenceDeletesToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        references: Set<String>
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.applyReferenceDeletes(snapshot, references)
    }

    private fun invalidateSnapshotCache(context: Context? = null) {
        snapshotCacheStore.invalidate(context)
    }

    private fun cleanupPendingAudioWrites(context: Context): StartupRecoveryResult {
        return ManagedDownloadPendingAudioWriteCleaner.cleanup(
            context = context,
            root = resolveRootBlocking(context),
            names = pendingAudioWriteNames,
            treeChildRegistry = treeChildRegistry,
            deleteTreeChild = { child ->
                deleteContentReference(
                    context = context,
                    reference = child.documentUri.toString(),
                    uri = child.documentUri
                )
            },
            tag = TAG
        )
    }

    internal fun cleanupUnfinalizedDownloadArtifacts(context: Context): StartupRecoveryResult {
        return runCatching {
            val root = resolveRootBlocking(context)
            val rootEntries = listChildren(context, root).filterNot(StoredEntry::isDirectory)
            val parsedMetadataEntries = rootEntries
                .filter { entry -> entry.name.endsWith(METADATA_SUFFIX) }
                .mapNotNull { entry ->
                    val metadata = parseDownloadedAudioMetadata(context, entry) ?: return@mapNotNull null
                    ManagedDownloadParsedMetadataEntry(entry, metadata)
                }
            val managedSidecarReferences = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
                .plus(listSubdirectoryEntries(context, root, LYRIC_SUBDIRECTORY))
                .mapTo(linkedSetOf(), StoredEntry::reference)
            val referencesToDelete = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
                rootEntries = rootEntries,
                parsedMetadataEntries = parsedMetadataEntries,
                managedSidecarReferences = managedSidecarReferences
            )
            if (referencesToDelete.isEmpty()) {
                return@runCatching StartupRecoveryResult()
            }
            var cleanedCount = 0
            var failedCount = 0
            referencesToDelete.forEach { reference ->
                val deleted = deleteInternal(
                    context = context,
                    reference = reference,
                    invalidateSnapshot = false
                )
                if (deleted) {
                    cleanedCount++
                } else {
                    failedCount++
                }
            }
            if (cleanedCount > 0 || failedCount > 0) {
                NPLogger.d(TAG, "清理未完成下载半成品完成: cleaned=$cleanedCount, failed=$failedCount")
            }
            StartupRecoveryResult(
                cleanedCount = cleanedCount,
                failedCount = failedCount
            )
        }.onFailure {
            NPLogger.w(TAG, "清理未完成下载半成品失败: ${it.message}")
        }.getOrDefault(StartupRecoveryResult())
    }

    internal fun isPendingAudioWriteName(name: String): Boolean {
        return pendingAudioWriteNames.isPendingAudioWriteName(name)
    }

    private fun buildPendingAudioWriteName(fileName: String): String {
        return pendingAudioWriteNames.buildPendingAudioWriteName(fileName)
    }

    internal fun mergeTreeChildNamesAfterRefresh(
        refreshedNames: Collection<String>,
        cachedNames: Collection<String>?,
        cachedNamesComplete: Boolean?,
        refreshedComplete: Boolean
    ): TreeChildNameRefresh {
        return ManagedDownloadTreeChildRegistry.mergeTreeChildNamesAfterRefresh(
            refreshedNames,
            cachedNames,
            cachedNamesComplete,
            refreshedComplete
        )
    }

    internal fun resolveTreeStoredName(actualName: String?, expectedName: String): String {
        return ManagedDownloadTreeNaming.resolveTreeStoredName(actualName, expectedName)
    }

    private fun createRootFile(
        context: Context,
        parent: DocumentFile,
        desiredName: String,
        mimeType: String,
        replace: Boolean
    ): DocumentFile {
        return treeFileCommitter.createRootFile(
            context = context,
            parent = parent,
            desiredName = desiredName,
            mimeType = mimeType,
            replace = replace
        )
    }

    private fun commitTreeAudioAfterRenameFailure(
        context: Context,
        parent: DocumentFile,
        pendingTarget: DocumentFile,
        pendingName: String,
        finalName: String,
        mimeType: String,
        tempFile: File,
        actualSizeBytes: Long,
        committedAtMs: Long
    ): StoredEntry {
        return treeFileCommitter.commitTreeAudioAfterRenameFailure(
            context = context,
            parent = parent,
            pendingTarget = pendingTarget,
            pendingName = pendingName,
            finalName = finalName,
            mimeType = mimeType,
            tempFile = tempFile,
            actualSizeBytes = actualSizeBytes,
            committedAtMs = committedAtMs
        )
    }

    internal fun documentCreateMimeType(desiredName: String, mimeType: String): String {
        return ManagedDownloadTreeNaming.documentCreateMimeType(desiredName, mimeType)
    }

    private fun writeMigrationRootStream(
        context: Context,
        root: RootHandle,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: StoredEntry,
        targetNames: Set<String>,
        targetEntry: StoredEntry? = null,
        onProgress: ((Long) -> Unit)? = null
    ): StoredWriteResult {
        return commitWriter.writeMigrationRootStream(
            context = context,
            root = root,
            displayName = displayName,
            mimeType = mimeType,
            input = input,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry,
            onProgress = onProgress
        )
    }

    internal fun verifiedCommittedByteCount(
        expectedSizeBytes: Long,
        reportedSizeBytes: Long?,
        countedSizeBytes: Long?,
        toleranceBytes: Long = 0L
    ): Long? {
        return ManagedDownloadCommitVerifier.verifiedCommittedByteCount(
            expectedSizeBytes = expectedSizeBytes,
            reportedSizeBytes = reportedSizeBytes,
            countedSizeBytes = countedSizeBytes,
            toleranceBytes = toleranceBytes
        )
    }

    private fun verifyFileCommittedLength(
        target: File,
        expectedSizeBytes: Long,
        description: String
    ): Long {
        return ManagedDownloadCommitIo.verifyFileCommittedLength(
            target = target,
            expectedSizeBytes = expectedSizeBytes,
            description = description
        )
    }

    private fun verifyDocumentCommittedLength(
        context: Context,
        uri: Uri,
        expectedSizeBytes: Long,
        description: String
    ): Long {
        return ManagedDownloadCommitIo.verifyDocumentCommittedLength(
            contentResolver = context.contentResolver,
            uri = uri,
            expectedSizeBytes = expectedSizeBytes,
            toleranceBytes = SAF_COMMITTED_SIZE_TOLERANCE_BYTES,
            bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
            description = description,
            onQueryFailure = { error -> NPLogger.w(TAG, "查询 SAF 目标大小失败: $uri, ${error.message}") },
            onCountFailure = { error -> NPLogger.w(TAG, "回读 SAF 目标失败: $uri, ${error.message}") }
        )
    }

    private fun verifiedTreeStoredEntry(
        context: Context,
        target: DocumentFile,
        expectedName: String,
        expectedSizeBytes: Long,
        fallbackLastModifiedMs: Long,
        description: String
    ): StoredEntry {
        return treeFileCommitter.verifiedTreeStoredEntry(
            context = context,
            target = target,
            expectedName = expectedName,
            expectedSizeBytes = expectedSizeBytes,
            fallbackLastModifiedMs = fallbackLastModifiedMs,
            description = description
        )
    }

    private fun buildMigrationTargetIndex(
        context: Context,
        targetRoot: RootHandle
    ): ManagedMigrationTargetIndex {
        return ManagedDownloadMigrationTargetIndexBuilder.build(
            targetRoot = targetRoot,
            listChildren = { root -> listChildren(context, root) },
            findSubdirectories = { root, desiredName, canonicalLast ->
                findSubdirectories(context, root, desiredName, canonicalLast)
            }
        )
    }

    private fun buildMigrationNamePlan(
        entries: List<ManagedMigrationEntry>,
        targetIndex: ManagedMigrationTargetIndex
    ) = ManagedDownloadMigrationNamePlanner.buildNamePlan(
        entries = entries.map(ManagedMigrationEntry::toRef),
        targetIndex = targetIndex
    )

    private fun writeSubdirectoryBytesBlocking(
        context: Context,
        subdirectory: String,
        displayName: String,
        bytes: ByteArray,
        mimeType: String
    ): StoredEntry? {
        return commitWriter.writeSubdirectoryBytes(
            context = context,
            root = resolveRootBlocking(context),
            subdirectory = subdirectory,
            displayName = displayName,
            bytes = bytes,
            mimeType = mimeType
        ).also { entry ->
            updateSnapshotAfterSubdirectoryWrite(context, subdirectory, entry)
        }
    }

    private fun writeSubdirectoryFileBlocking(
        context: Context,
        subdirectory: String,
        displayName: String,
        sourceFile: File,
        mimeType: String
    ): StoredEntry? {
        return commitWriter.writeSubdirectoryFile(
            context = context,
            root = resolveRootBlocking(context),
            subdirectory = subdirectory,
            displayName = displayName,
            sourceFile = sourceFile,
            mimeType = mimeType
        ).also { entry ->
            updateSnapshotAfterSubdirectoryWrite(context, subdirectory, entry)
        }
    }

    private fun updateSnapshotAfterSubdirectoryWrite(
        context: Context,
        subdirectory: String,
        entry: StoredEntry?
    ) {
        entry ?: return
        val bucket = when (subdirectory) {
            COVER_SUBDIRECTORY -> SnapshotEntryBucket.COVER
            LYRIC_SUBDIRECTORY -> SnapshotEntryBucket.LYRIC
            else -> null
        }
        if (bucket == null || !updateSnapshotCacheAfterStoredEntryWrite(context, entry, bucket)) {
            invalidateSnapshotCache(context)
        }
    }

    private fun writeMigrationSubdirectoryStream(
        context: Context,
        root: RootHandle,
        subdirectory: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: StoredEntry,
        targetNames: Set<String>,
        targetEntry: StoredEntry? = null,
        onProgress: ((Long) -> Unit)? = null
    ): StoredWriteResult {
        return commitWriter.writeMigrationSubdirectoryStream(
            context = context,
            root = root,
            subdirectory = subdirectory,
            displayName = displayName,
            mimeType = mimeType,
            input = input,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry,
            onProgress = onProgress
        )
    }

    private fun writeRootText(
        context: Context,
        root: RootHandle,
        displayName: String,
        content: String,
        invalidateSnapshot: Boolean = true
    ): StoredEntry? {
        val storedEntry = commitWriter.writeRootText(
            context = context,
            root = root,
            displayName = displayName,
            content = content
        )
        if (invalidateSnapshot) {
            invalidateSnapshotCache(context)
        }
        return storedEntry
    }

    private fun clearTreeDirectoryCache() {
        treeDirectories.clear()
        treeChildRegistry.clear()
        rootResolver.clearCache()
    }

    internal fun shouldCreateNoMediaMarker(subdirectory: String): Boolean {
        return ManagedDownloadTreeNaming.shouldCreateNoMediaMarker(subdirectory)
    }

    internal fun matchesManagedSubdirectoryName(actualName: String, desiredName: String): Boolean {
        return ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(actualName, desiredName)
    }

    private fun ensureManagedMediaScanIsolation(subdirectory: String, directory: File) {
        treeDirectories.ensureManagedMediaScanIsolation(subdirectory, directory)
    }

    private fun findSubdirectories(
        context: Context,
        root: RootHandle,
        desiredName: String,
        canonicalLast: Boolean = false
    ): List<RootHandle> {
        return treeDirectories.findSubdirectories(context, root, desiredName, canonicalLast)
    }

    private fun openStoredEntryInputStream(context: Context, entry: StoredEntry): InputStream? {
        entry.localFilePath?.let { localPath ->
            val file = File(localPath)
            if (file.exists()) {
                return file.inputStream()
            }
        }
        if (entry.reference.startsWith("/")) {
            val file = File(entry.reference)
            if (file.exists()) {
                return file.inputStream()
            }
        }
        val uri = runCatching { entry.reference.toUri() }.getOrNull() ?: return null
        return context.contentResolver.openInputStream(uri)
    }

    private fun migrationMimeTypeFor(entry: ManagedMigrationEntry): String {
        return ManagedDownloadMigrationPolicy.mimeTypeFor(entry.toRef())
    }

    private fun migrationCopyParallelism(sourceRoot: RootHandle, targetRoot: RootHandle): Int {
        return ManagedDownloadMigrationPolicy.copyParallelism(
            usesTreeRoot = sourceRoot is RootHandle.TreeRoot || targetRoot is RootHandle.TreeRoot
        )
    }

    private fun migrationRewriteParallelism(targetRoot: RootHandle): Int {
        return ManagedDownloadMigrationPolicy.rewriteParallelism(
            usesTreeRoot = targetRoot is RootHandle.TreeRoot
        )
    }

    private fun migrationDeleteParallelism(root: RootHandle): Int {
        return ManagedDownloadMigrationPolicy.deleteParallelism(
            usesTreeRoot = root is RootHandle.TreeRoot
        )
    }

    private fun normalizeDirectoryUri(uriString: String?): String? {
        return rootResolver.normalizeDirectoryUri(uriString)
    }

    private fun resolveTreeRootBlocking(context: Context, directoryUriString: String?): RootHandle.TreeRoot? {
        return rootResolver.resolveTreeRoot(context, directoryUriString)
    }

    private fun createDefaultRoot(context: Context): RootHandle.FileRoot {
        return rootResolver.createDefaultRoot(context)
    }

    internal fun createUniqueName(existingNames: Set<String>, desiredName: String): String {
        return ManagedDownloadStorageNaming.createUniqueName(existingNames, desiredName)
    }

    private fun readTextInternal(context: Context, reference: String): String? {
        return ManagedDownloadReferenceIo.readText(context, reference)
    }

    private fun existsInternal(context: Context, reference: String?): Boolean {
        return ManagedDownloadReferenceIo.exists(context, reference)
    }

    private fun buildManagedDeletePolicy(
        context: Context,
        allowedRoot: RootHandle? = null,
        trustedReferences: Set<String>? = null
    ): ManagedDownloadDeletePolicy {
        val roots = listOf(allowedRoot ?: resolveRootBlocking(context))
        val snapshotTrustedReferences = trustedReferences
            ?: if (allowedRoot == null) {
                cachedDownloadLibrarySnapshot(context)?.knownReferences.orEmpty()
            } else {
                emptySet()
            }
        return ManagedDownloadDeletePolicy(
            managedFileRoots = roots.mapNotNull { root ->
                (root as? RootHandle.FileRoot)?.dir?.absolutePath
            },
            managedTreeRoots = roots.mapNotNull { root ->
                (root as? RootHandle.TreeRoot)?.tree?.uri?.toString()
            },
            trustedReferences = snapshotTrustedReferences
        )
    }

    internal fun isReferenceAllowedForManagedDelete(
        reference: String,
        trustedReferences: Set<String>,
        managedFileRoots: Collection<String>,
        managedTreeRoots: Collection<String>
    ): Boolean {
        return ManagedDownloadDeleteGuard.isReferenceAllowedForManagedDelete(
            reference = reference,
            trustedReferences = trustedReferences,
            managedFileRoots = managedFileRoots,
            managedTreeRoots = managedTreeRoots,
            onTrustedReferenceOutsideManagedRoot = { normalizedReference ->
                NPLogger.w(TAG, "受信引用不在托管根内，拒绝删除: $normalizedReference")
            }
        )
    }

    internal fun isFileReferenceUnderManagedRoot(reference: String, managedRootPath: String): Boolean {
        return ManagedDownloadDeleteGuard.isFileReferenceUnderManagedRoot(reference, managedRootPath)
    }

    internal fun isDocumentReferenceUnderManagedTree(reference: String, managedTreeUri: String): Boolean {
        return ManagedDownloadDirectoryIdentity.isDocumentReferenceUnderManagedTree(reference, managedTreeUri)
    }

    internal fun isDocumentIdInsideManagedRoot(documentId: String, rootDocumentId: String): Boolean {
        return ManagedDownloadDirectoryIdentity.isDocumentIdInsideManagedRoot(documentId, rootDocumentId)
    }

    private fun deleteInternal(
        context: Context,
        reference: String?,
        allowedRoot: RootHandle? = null,
        invalidateSnapshot: Boolean = true
    ): Boolean {
        return deleteReferencesInternal(
            context = context,
            references = listOf(reference),
            allowedRoot = allowedRoot,
            invalidateSnapshot = invalidateSnapshot
        ).isNotEmpty()
    }

    private fun deleteReferencesInternal(
        context: Context,
        references: Collection<String?>,
        allowedRoot: RootHandle? = null,
        invalidateSnapshot: Boolean
    ): Set<String> {
        val deleteResult = referenceDeleteExecutor.deleteReferences(
            context = context,
            references = references,
            deletePolicy = buildManagedDeletePolicy(context, allowedRoot)
        )
        applyDeleteResultToSnapshot(context, deleteResult, invalidateSnapshot)
        return deleteResult.deletedReferences
    }

    private suspend fun deleteReferencesInternalConcurrently(
        context: Context,
        references: Collection<String?>,
        allowedRoot: RootHandle? = null,
        invalidateSnapshot: Boolean
    ): Set<String> {
        val deleteResult = referenceDeleteExecutor.deleteReferencesConcurrently(
            context = context,
            references = references,
            deletePolicy = buildManagedDeletePolicy(context, allowedRoot)
        )
        applyDeleteResultToSnapshot(context, deleteResult, invalidateSnapshot)
        return deleteResult.deletedReferences
    }

    private fun applyDeleteResultToSnapshot(
        context: Context,
        deleteResult: ManagedDownloadReferenceDeleteResult,
        invalidateSnapshot: Boolean
    ) {
        if (!invalidateSnapshot) {
            return
        }
        val deletedReferences = deleteResult.deletedReferences
        forgetDeletedReferencesFromCaches(deletedReferences)
        if (deleteResult.hasUnconfirmedDeletes) {
            invalidateSnapshotCache(context)
        } else if (deletedReferences.isNotEmpty() && !updateSnapshotCacheAfterDelete(context, deletedReferences)) {
            invalidateSnapshotCache(context)
        }
    }

    private fun forgetDeletedReferencesFromCaches(deletedReferences: Set<String>) {
        if (deletedReferences.isEmpty()) return
        treeChildRegistry.forgetDeletedReferences(deletedReferences)
        treeDirectories.forgetDeletedReferences(deletedReferences)
    }

    private fun deleteContentReference(
        context: Context,
        reference: String,
        uri: Uri
    ): Boolean {
        return referenceDeleteExecutor.deleteContentReference(
            context = context,
            reference = reference,
            uri = uri,
        )
    }

    internal fun isMissingManagedDocumentFailure(error: Throwable): Boolean {
        return ManagedDownloadReferenceIo.isMissingDocumentFailure(error)
    }

    internal fun mimeTypeFromName(name: String, fallback: String?): String {
        return ManagedDownloadStorageNaming.mimeTypeFromName(name, fallback)
    }

    internal fun parseWorkingResumeMetadataSong(rawJson: String): SongItem? {
        return ManagedDownloadRecoveryFiles.parseWorkingResumeMetadataSong(rawJson)
    }

    internal fun serializePendingDownloadQueuePayload(
        entries: List<PendingDownloadQueueEntry>,
        updatedAtMs: Long
    ): String {
        return ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(entries, updatedAtMs)
    }

    internal fun parsePendingDownloadQueuePayload(rawJson: String): List<PendingDownloadQueueEntry> {
        return ManagedDownloadStorageJsonCodec.parsePendingDownloadQueuePayload(rawJson)
    }

    internal fun serializeCancelledDownloadKeysPayload(
        songKeys: Set<String>,
        updatedAtMs: Long
    ): String {
        return ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(songKeys, updatedAtMs)
    }

    internal fun parseCancelledDownloadKeysPayload(rawJson: String): Set<String> {
        return ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload(rawJson)
    }

    internal fun parseDownloadedAudioMetadataJson(rawJson: String): DownloadedAudioMetadata? {
        return ManagedDownloadMetadataCodec.parseDownloadedAudioMetadataJson(rawJson)
    }

    internal fun finalizedDownloadedMetadataJson(rawJson: String): String? {
        return ManagedDownloadMetadataCodec.finalizedDownloadedMetadataJson(rawJson)
    }

    internal fun isMetadataWriteVerified(
        expected: DownloadedAudioMetadata,
        actual: DownloadedAudioMetadata?
    ): Boolean {
        return ManagedDownloadMetadataCodec.isMetadataWriteVerified(expected, actual)
    }

    private fun updateSnapshotCacheAfterMetadataWrite(
        context: Context,
        metadataEntry: StoredEntry,
        metadata: DownloadedAudioMetadata
    ): Boolean {
        return snapshotCacheStore.updateAfterMetadataWrite(context, metadataEntry, metadata)
    }

    private fun updateSnapshotCacheAfterStoredEntryWrite(
        context: Context,
        storedEntry: StoredEntry,
        bucket: SnapshotEntryBucket
    ): Boolean {
        return snapshotCacheStore.updateAfterStoredEntryWrite(context, storedEntry, bucket)
    }

    private fun updateSnapshotCacheAfterDelete(
        context: Context,
        deletedReferences: Set<String>
    ): Boolean {
        return snapshotCacheStore.updateAfterDelete(context, deletedReferences)
    }

    private fun File.toStoredEntry(): StoredEntry {
        return ManagedDownloadStoredEntryMapper.fromFile(this)
    }

    private fun QueriedTreeChild.toStoredEntry(): StoredEntry {
        return ManagedDownloadStoredEntryMapper.fromTreeChild(this)
    }

    internal fun storedEntryFromTreeChild(
        name: String,
        documentReference: String,
        sizeBytes: Long,
        lastModifiedMs: Long,
        isDirectory: Boolean
    ): StoredEntry {
        return ManagedDownloadStoredEntryMapper.fromTreeChild(
            name = name,
            documentReference = documentReference,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            isDirectory = isDirectory
        )
    }

    private fun QueriedTreeChild.toDocumentFile(context: Context): DocumentFile? {
        return treeChildRegistry.toDocumentFile(context, this)
    }

    private fun DocumentFile.toStoredEntry(
        knownName: String? = null,
        knownSizeBytes: Long? = null,
        knownLastModifiedMs: Long? = null,
        knownIsDirectory: Boolean? = null
    ): StoredEntry? {
        return ManagedDownloadStoredEntryMapper.fromDocumentFile(
            documentFile = this,
            knownName = knownName,
            knownSizeBytes = knownSizeBytes,
            knownLastModifiedMs = knownLastModifiedMs,
            knownIsDirectory = knownIsDirectory
        )
    }
}
