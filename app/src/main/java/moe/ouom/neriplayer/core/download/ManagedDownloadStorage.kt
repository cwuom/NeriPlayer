package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadParsedMetadataEntry
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadUnfinalizedCleanupPlanner
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_MANIFEST_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_INDEX_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.REMOTE_COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.SAF_COMMITTED_SIZE_TOLERANCE_BYTES
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.audioExtensions
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
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadCoverLookup
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadMetadataCodec
import moe.ouom.neriplayer.core.download.storage.migration.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationCopyWorker
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException
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
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.download.storage.sidecar.ManagedDownloadLyricStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotCacheStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndex
import moe.ouom.neriplayer.core.download.index.ManagedLibraryIndexEntry
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import org.json.JSONObject
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle

internal object ManagedDownloadStorage {
    private const val TAG = "ManagedDownloadStorage"
    private const val LEGACY_DOWNLOAD_ROOT_PATH = "/storage/emulated/0/neriplayer-download"
    private const val LEGACY_DOWNLOAD_ROOT_RELATIVE_PATH = "neriplayer-download"
    private const val LOG_HOT_AUDIO_HITS = false
    private const val SIDECAR_REFRESH_THROTTLE_MS = 400L
    private const val FAST_LYRICS_SLOW_LOG_MS = 120L

    private val snapshotBuildLock = Any()
    private val sidecarRefreshLock = Any()
    private val snapshotWarmupLock = Any()
    private val batchReferenceDeleteMutex = Mutex()
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var snapshotWarmupJob: Job? = null
    private var snapshotWarmupKey: String? = null
    private var snapshotWarmupRefreshSidecars: Boolean = false
    private val settings = ManagedDownloadStorageSettings(
        defaultRootPathProvider = { context ->
            ManagedDownloadRootResolver.defaultRootDirectory(context).absolutePath
        }
    )
    private val snapshotCacheStore = ManagedDownloadSnapshotCacheStore(
        scope = snapshotScope,
        cacheKeyProvider = ::resolveSnapshotCacheKey
    )
    private val treeChildRegistry = ManagedDownloadTreeChildRegistry(
        writeCacheValidateIntervalMs = FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS,
        treeCacheValidateIntervalMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS,
        treeWriteCacheValidateIntervalMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS,
        onTreeQueryFailed = {
            NPLogger.w(
                TAG,
                "查询目录子项失败，回退 DocumentFile 枚举: " +
                    "${it.javaClass.simpleName}: ${it.message}",
                it
            )
        }
    )
    private val treeDirectoryLocks = ConcurrentHashMap<String, Any>()
    private val rootResolver = ManagedDownloadRootResolver(treeDirectoryLocks)
    private val treeDirectories = ManagedDownloadTreeDirectories(
        treeChildRegistry = treeChildRegistry,
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
        openInputStream = { context, entry -> openStoredEntryInputStream(context, entry) },
        writeRootText = { context, root, displayName, content ->
            writeRootText(
                context = context,
                root = root,
                displayName = displayName,
                content = content,
                invalidateSnapshot = false
            )
        },
        restoreLastModified = ::restoreStoredEntryLastModified,
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

    @Volatile
    private var lastSidecarRefreshKey: String? = null

    @Volatile
    private var lastSidecarRefreshAtMs: Long = 0L

    private val _startupRecoveryResults = MutableSharedFlow<StartupRecoveryResult>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    internal val startupRecoveryResults: SharedFlow<StartupRecoveryResult> = _startupRecoveryResults

    private val _migrationProgressFlow = MutableStateFlow<MigrationProgress?>(null)
    val migrationProgressFlow: StateFlow<MigrationProgress?> = _migrationProgressFlow

    // sidecar 目录刷新完成后通知播放页重新读取当前歌曲, 不让首帧等待 SAF
    private val _lyricsRefreshVersion = MutableStateFlow(0L)
    internal val lyricsRefreshVersion: StateFlow<Long> = _lyricsRefreshVersion

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        snapshotScope.launch {
            val result = runCatching {
                if (settings.configuredDirectoryUri.isNullOrBlank()) {
                    createDefaultRoot(appContext)
                }
                val stagingRecovery = cleanupStagingFiles(appContext)
                val pendingAudioRecovery = resolveStartupPendingAudioRecovery(appContext)
                val metadataRecovery = resolveStartupMetadataRecovery(appContext)
                StartupRecoveryResult(
                    cleanedCount = stagingRecovery.cleanedCount +
                        pendingAudioRecovery.cleanedCount +
                        metadataRecovery.cleanedCount,
                    failedCount = stagingRecovery.failedCount +
                        pendingAudioRecovery.failedCount +
                        metadataRecovery.failedCount
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "后台初始化下载存储失败: ${error.message}")
            }.getOrDefault(StartupRecoveryResult())
            startupRecoveryResult = result
            if (result.hasRecoveredEntries) {
                _startupRecoveryResults.tryEmit(result)
            }
            // 进程重启只清空内存索引, 保留持久化缓存供首屏预览和重建回退
            scheduleSnapshotWarmup(appContext)
        }
    }

    internal fun scheduleLyricsRefresh(context: Context) {
        scheduleSnapshotWarmup(context, refreshSidecars = true)
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
        val workingFile: File,
        val operationId: String? = null
    )

    internal data class WorkingResumeFingerprint(
        val sourceUrl: String? = null,
        val etag: String? = null,
        val lastModified: String? = null,
        val expectedContentLength: Long? = null
    ) {
        val validator: String?
            get() = etag?.takeIf(String::isNotBlank)
                ?: lastModified?.takeIf(String::isNotBlank)
    }

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

        val canReleasePreviousPermission: Boolean
            get() = canSwitchDirectory && cleanupFailedFiles == 0
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
        val knownReferences: Set<String>,
        /** root 子项查询是否完整，false 时不能把空结果当成目录事实 */
        val rootEntriesComplete: Boolean = true
    )

    data class DownloadedLyricsBundle(
        val lyric: String?,
        val translatedLyric: String?,
        val romanizedLyric: String?,
        val hasOriginalSidecar: Boolean = false,
        val hasTranslatedSidecar: Boolean = false,
        val hasRomanizedSidecar: Boolean = false
    )

    internal enum class SnapshotEntryBucket {
        AUDIO,
        COVER,
        LYRIC
    }

    internal enum class LyricKind {
        ORIGINAL,
        TRANSLATED,
        ROMANIZED
    }

    data class DownloadedAudioMetadata(
        val stableKey: String? = null,
        val songId: Long? = null,
        val identityAlbum: String? = null,
        val album: String? = null,
        val name: String? = null,
        val artist: String? = null,
        val coverUrl: String? = null,
        val matchedLyric: String? = null,
        val matchedTranslatedLyric: String? = null,
        val matchedRomanizedLyric: String? = null,
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
        val originalRomanizedLyric: String? = null,
        val mediaUri: String? = null,
        val channelId: String? = null,
        val audioId: String? = null,
        val subAudioId: String? = null,
        val playlistContextId: String? = null,
        val coverPath: String? = null,
        val lyricPath: String? = null,
        val translatedLyricPath: String? = null,
        val romanizedLyricPath: String? = null,
        val durationMs: Long = 0L,
        val downloadTimeMs: Long? = null,
        val downloadFinalized: Boolean? = null,
        val createdAtMs: Long? = null,
        val createdAtSource: String? = null,
        val artifactId: String? = null,
        val operationId: String? = null,
        val artifactState: String? = null,
        val audioFileName: String? = null,
        val libraryId: String? = null,
        val libraryAddedAtMs: Long? = null,
        val sourceCreatedAtMs: Long? = null,
        val sourceModifiedAtMs: Long? = null
    )

    fun primeSettings(directoryUri: String?, directoryLabel: String?, fileNameTemplate: String? = null) {
        settings.prime(
            directoryUri = directoryUri,
            directoryLabel = directoryLabel,
            fileNameTemplate = fileNameTemplate
        )
        val generation = LocalStorageRootGeneration.update(directoryUri)
        LocalAssetInvalidationBus.publishRootChanged(generation)
        clearTreeDirectoryCache()
        invalidateSnapshotCache()
    }

    fun updateCustomDirectoryUri(uri: String?) {
        settings.updateDirectoryUri(uri)
        val generation = LocalStorageRootGeneration.update(uri)
        LocalAssetInvalidationBus.publishRootChanged(generation)
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

    internal fun configuredDirectoryUri(): String? = settings.configuredDirectoryUri

    internal fun currentSnapshotCacheKey(context: Context): String {
        return snapshotCacheStore.currentKey(context)
    }

    internal fun ensureSnapshotCacheReady(context: Context): Boolean {
        return snapshotCacheStore.ensureReady(context)
    }

    internal fun cachedDownloadLibrarySnapshot(
        context: Context,
        restorePersisted: Boolean = true
    ): DownloadLibrarySnapshot? {
        return snapshotCacheStore.cachedSnapshot(context, restorePersisted)
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
        try {
            val root = resolveRoot(context, directoryUri) ?: return@withContext true
            collectManagedMigrationEntries(
                context = context,
                root = root,
                allowMetadataLessAudio = shouldIndexMetadataLessAudio(directoryUri)
            ).isNotEmpty()
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: Exception) {
            // 迁移前探测失败时必须保守弹窗, 不能把未知状态当成空目录
            NPLogger.w(TAG, "迁移前目录探测失败, 保留迁移确认: ${error.message}", error)
            true
        }
    }

    suspend fun migrateManagedDownloads(
        context: Context,
        fromDirectoryUri: String?,
        toDirectoryUri: String?,
        minimumSourceEntryCount: Int = 0,
        onTargetVerified: suspend () -> Unit = {}
    ): MigrationResult = withContext(Dispatchers.IO) {
        try {
            _migrationProgressFlow.value = null
            if (areEquivalentDirectoryUris(fromDirectoryUri, toDirectoryUri)) {
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }

            val sourceRoot = resolveRoot(context, fromDirectoryUri)
                ?: throw ManagedDownloadMigrationException.permanent("源下载目录不可用")
            val targetRoot = resolveRoot(context, toDirectoryUri)
                ?: throw ManagedDownloadMigrationException.permanent("目标下载目录不可用")

            val entries = collectManagedMigrationEntries(
                context = context,
                root = sourceRoot,
                allowMetadataLessAudio = shouldIndexMetadataLessAudio(fromDirectoryUri)
            )
            if (entries.isEmpty()) {
                if (minimumSourceEntryCount > 0) {
                    throw ManagedDownloadMigrationException.permanent(
                        "源下载目录未返回已缓存的下载文件"
                    )
                }
                onTargetVerified()
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }

            val metadataEntriesTotal = entries.count { ManagedDownloadTreeNaming.isMetadataName(it.entry.name) }
            val progressTracker = ManagedMigrationProgressReporter(
                totalFiles = entries.size,
                totalBytes = entries.sumOf { it.entry.sizeBytes.coerceAtLeast(0L) },
                metadataFilesTotal = metadataEntriesTotal,
                onProgress = { progress -> _migrationProgressFlow.value = progress }
            )
            progressTracker.startPreparing(entries.firstOrNull()?.entry?.name)
            val targetIndex = buildMigrationTargetIndex(context, targetRoot)
            val sourceMetadataByAudioName = entries
                .asSequence()
                .filter { entry -> entry.subdirectory == null && entry.metadata != null }
                .associate { entry -> entry.entry.name to requireNotNull(entry.metadata) }
            val namePlan = buildMigrationNamePlan(
                entries = entries,
                targetIndex = targetIndex,
                sourceMetadataByAudioName = sourceMetadataByAudioName
            )

            val copyResults = coroutineScope {
                val entriesChannel = kotlinx.coroutines.channels.Channel<ManagedMigrationEntry>(
                    capacity = migrationCopyParallelism(sourceRoot, targetRoot)
                )
                val workers = List(migrationCopyParallelism(sourceRoot, targetRoot)) {
                    async(Dispatchers.IO) {
                        buildList {
                            for (migrationEntry in entriesChannel) {
                                add(
                                    migrationCopyWorker.copyEntry(
                                        context = context,
                                        targetRoot = targetRoot,
                                        migrationEntry = migrationEntry,
                                        targetIndex = targetIndex,
                                        namePlan = namePlan,
                                        progressTracker = progressTracker
                                    )
                                )
                            }
                        }
                    }
                }
                entries.forEach { entry ->
                    entriesChannel.send(entry)
                }
                entriesChannel.close()
                workers.awaitAll().flatten()
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

            val verificationFailedFiles = verifyMigratedEntries(
                context = context,
                targetRoot = targetRoot,
                copiedEntries = copiedEntries
            )
            if (verificationFailedFiles > 0) {
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                return@withContext MigrationResult(
                    movedFiles = 0,
                    skippedFiles = verificationFailedFiles
                )
            }

            try {
                onTargetVerified()
            } catch (error: Throwable) {
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                throw error
            }

            val cleanupFailedFiles = cleanupMigratedEntries(
                context = context,
                copiedEntries = copiedEntries,
                sourceRoot = sourceRoot,
                targetsAlreadyVerified = true,
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

    internal fun buildWorkingFileName(
        songKey: String,
        fileName: String,
        operationId: String? = null
    ): String {
        return ManagedDownloadRecoveryFiles.buildWorkingFileName(songKey, fileName, operationId)
    }

    internal fun buildWorkingSongKeyHash(songKey: String): String {
        return ManagedDownloadRecoveryFiles.buildWorkingSongKeyHash(songKey)
    }

    fun createWorkingFile(
        context: Context,
        songKey: String,
        fileName: String,
        operationId: String? = null
    ): File {
        return ManagedDownloadRecoveryFiles.createWorkingFile(
            context,
            songKey,
            fileName,
            operationId
        )
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
        song: SongItem,
        operationId: String? = null
    ) {
        ManagedDownloadRecoveryFiles.saveWorkingResumeMetadata(workingFile, song, operationId)
    }

    internal fun findWorkingFileForResume(
        context: Context,
        songKey: String
    ): File? {
        return ManagedDownloadRecoveryFiles.listPendingResumableDownloads(context)
            .firstOrNull { pending -> pending.song.stableKey() == songKey }
            ?.workingFile
    }

    internal fun readWorkingResumeFingerprint(workingFile: File): WorkingResumeFingerprint? {
        return ManagedDownloadRecoveryFiles.readWorkingResumeFingerprint(workingFile)
    }

    internal fun updateWorkingResumeFingerprint(
        workingFile: File,
        fingerprint: WorkingResumeFingerprint
    ) {
        ManagedDownloadRecoveryFiles.updateWorkingResumeFingerprint(workingFile, fingerprint)
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

    /**
     * 判断歌曲是否落在托管下载目录, 不恢复索引也不列举整棵目录
     */
    internal fun isLikelyManagedDownloadSong(context: Context, song: SongItem): Boolean {
        if (hasManagedDownloadIdentityHint(song)) {
            return true
        }
        if (peekDownloadedAudio(song) != null) {
            return true
        }
        val configuredRoot = ManagedDownloadRootResolver.defaultRootDirectory(context).absolutePath
        val directPaths = listOfNotNull(song.localFilePath, song.mediaUri)
            .mapNotNull { reference ->
                when {
                    reference.startsWith("/") -> reference
                    reference.startsWith("file:", ignoreCase = true) -> {
                        runCatching { reference.toUri().path }.getOrNull()
                    }
                    else -> null
                }
            }
        if (directPaths.any { directPath ->
                isPathInside(directPath, configuredRoot) ||
                    isPathInside(directPath, LEGACY_DOWNLOAD_ROOT_PATH)
            }
        ) {
            return true
        }
        val configuredTree = settings.configuredDirectoryUri
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { it.toUri() }.getOrNull() }
        val songReferences = listOfNotNull(song.mediaUri, song.localFilePath)
            .filter { it.startsWith("content://", ignoreCase = true) }
            .mapNotNull { rawReference -> runCatching { rawReference.toUri() }.getOrNull() }
        val treeDocumentId = configuredTree
            ?.let { tree -> runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() }
        if (
            isMediaStoreSongWithinManagedRoot(
                context = context,
                songReferences = songReferences,
                treeDocumentId = treeDocumentId
            )
        ) {
            return true
        }
        if (songReferences.any { songReference ->
                val documentId = runCatching {
                    DocumentsContract.getDocumentId(songReference)
                }.getOrNull()
                documentId != null && isKnownManagedDownloadDocumentId(
                    documentId = documentId,
                    treeDocumentId = treeDocumentId
                )
            }
        ) {
            return true
        }
        if (configuredTree != null && songReferences.isNotEmpty()) {
            if (!treeDocumentId.isNullOrBlank()) {
                val treeAuthority = configuredTree.authority
                if (songReferences.any { songReference ->
                        songReference.authority == treeAuthority &&
                            isDocumentWithinManagedTree(
                                context = context,
                                songReference = songReference,
                                treeDocumentId = treeDocumentId
                            )
                    }
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 首屏只使用内存和 URI 线索判断下载来源, 不查询 MediaStore 或 SAF provider
     */
    internal fun isLikelyManagedDownloadSongFast(
        context: Context,
        song: SongItem
    ): Boolean {
        if (!LocalSongSupport.isLocalSong(song, null)) {
            return false
        }
        if (hasManagedDownloadIdentityHint(song) || peekDownloadedAudio(song) != null) {
            return true
        }

        val configuredRoot = settings.configuredDirectoryUri
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { it.toUri() }.getOrNull() }
        val songTree = listOfNotNull(song.mediaUri, song.localFilePath)
            .asSequence()
            .mapNotNull(::managedDownloadTreeUri)
            .firstOrNull()
        if (configuredRoot != null && songTree != null) {
            return areEquivalentDirectoryUris(songTree.toString(), configuredRoot.toString())
        }

        val defaultRoot = runCatching {
            ManagedDownloadRootResolver.defaultRootDirectory(context).absolutePath
        }.getOrNull()
        val directPaths = listOfNotNull(song.localFilePath, song.mediaUri)
            .mapNotNull { reference ->
                when {
                    reference.startsWith("/") -> reference
                    reference.startsWith("file:", ignoreCase = true) -> {
                        runCatching { reference.toUri().path }.getOrNull()
                    }
                    else -> null
                }
            }
        return directPaths.any { path ->
            (defaultRoot != null && isPathInside(path, defaultRoot)) ||
                isPathInside(path, LEGACY_DOWNLOAD_ROOT_PATH)
        }
    }

    /**
     * 下载歌曲在目录索引恢复前仍保留远端来源标识, 可据此走下载侧载快路径
     */
    internal fun hasManagedDownloadIdentityHint(song: SongItem): Boolean {
        if (!LocalSongSupport.isLocalSong(song, null)) {
            return false
        }
        val rawChannel = song.channelId?.trim()?.takeIf(String::isNotBlank)
        if (rawChannel?.equals("local", ignoreCase = true) == true) {
            return false
        }
        val sourceChannel = rawChannel
            ?.trim()
            ?.takeIf { !it.equals("local", ignoreCase = true) }
        if (song.remoteSourceIdentityOrNull() != null) {
            return true
        }
        if (
            sourceChannel != null &&
                (
                    !song.audioId.isNullOrBlank() ||
                        !song.subAudioId.isNullOrBlank() ||
                        song.id > 0L
                    )
        ) {
            return true
        }
        return hasManagedDownloadPathHint(song)
    }

    private fun hasManagedDownloadPathHint(song: SongItem): Boolean {
        return listOfNotNull(song.localFilePath, song.mediaUri).any { reference ->
            val raw = reference.trim().replace('\\', '/')
            val decoded = runCatching { Uri.decode(raw) }.getOrNull() ?: raw
            val normalized = "/${decoded.trim('/')}/"
                .replace("//", "/")
                .lowercase()
            normalized.contains("/neriplayer-download/")
        }
    }

    internal fun isKnownManagedDownloadDocumentId(
        documentId: String,
        treeDocumentId: String?
    ): Boolean {
        val relativeDocumentId = documentId
            .substringAfter(':', missingDelimiterValue = documentId)
            .replace('\\', '/')
            .trim('/')
        return isManagedDownloadRelativePath(
            relativePath = relativeDocumentId,
            treeDocumentId = treeDocumentId
        )
    }

    internal fun isManagedDownloadRelativePath(
        relativePath: String?,
        treeDocumentId: String?
    ): Boolean {
        val normalizedRelativePath = relativePath
            ?.replace('\\', '/')
            ?.trim()
            ?.trim('/')
            ?.takeIf(String::isNotBlank)
            ?: return false
        val managedRoots = buildList {
            treeDocumentId
                ?.substringAfter(':', missingDelimiterValue = treeDocumentId)
                ?.replace('\\', '/')
                ?.trim('/')
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            add(LEGACY_DOWNLOAD_ROOT_RELATIVE_PATH)
        }.distinct()
        return managedRoots.any { managedRoot ->
            normalizedRelativePath == managedRoot ||
                normalizedRelativePath.startsWith("$managedRoot/") ||
                normalizedRelativePath.endsWith("/$managedRoot") ||
                normalizedRelativePath.contains("/$managedRoot/")
        }
    }

    private fun isMediaStoreSongWithinManagedRoot(
        context: Context,
        songReferences: List<Uri>,
        treeDocumentId: String?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        val mediaStoreReference = songReferences.firstOrNull { reference ->
            reference.authority.equals("media", ignoreCase = true)
        } ?: return false
        val relativePath = try {
            context.contentResolver.query(
                mediaStoreReference,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return isManagedDownloadRelativePath(
            relativePath = relativePath,
            treeDocumentId = treeDocumentId
        )
    }

    private fun isDocumentWithinManagedTree(
        context: Context,
        songReference: Uri,
        treeDocumentId: String
    ): Boolean {
        val songDocumentId = try {
            DocumentsContract.getDocumentId(songReference)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (
            songDocumentId == treeDocumentId ||
                songDocumentId?.startsWith("$treeDocumentId/") == true
        ) {
            return true
        }
        return try {
            DocumentsContract.findDocumentPath(
                context.contentResolver,
                songReference
            )?.path?.any { documentId -> documentId == treeDocumentId } == true
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 将 SAF URI 或 document ID 归一为可用于侧载文件匹配的音频文件名
     */
    internal fun normalizeManagedAudioFileName(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (value.startsWith("/")) {
            return File(value).name.takeIf(String::isNotBlank)
        }
        val segment: String = runCatching {
            value.toUri().lastPathSegment
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: value.substringAfterLast('/')
        val decoded = runCatching { Uri.decode(segment) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: runCatching {
                java.net.URLDecoder.decode(segment, Charsets.UTF_8.name())
            }.getOrDefault(segment)
        return decoded
            .substringAfterLast('/')
            .substringAfterLast(':')
            .takeIf(String::isNotBlank)
    }

    /**
     * 优先读取 provider 的真实 display name, 避免把 opaque document ID 当成文件名
     */
    internal fun resolveManagedAudioDisplayName(
        context: Context,
        song: SongItem
    ): String? {
        val rawFileName = song.localFileName?.trim()?.takeIf(String::isNotBlank)
        val normalizedHint = normalizeManagedAudioFileName(rawFileName)
            ?: normalizeManagedAudioFileName(song.localFilePath)
            ?: normalizeManagedAudioFileName(song.mediaUri)
        val contentUri = listOfNotNull(song.mediaUri, song.localFilePath)
            .firstOrNull { it.startsWith("content://", ignoreCase = true) }
            ?.let { runCatching { it.toUri() }.getOrNull() }
        val hintLooksUsable = (
            normalizedHint
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.let { it in moe.ouom.neriplayer.core.download.storage.audioExtensions }
                == true
            )
        if (contentUri == null || (hintLooksUsable && '%' !in rawFileName.orEmpty())) {
            return normalizedHint
        }
        val queriedName = try {
            context.contentResolver.query(
                contentUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf(String::isNotBlank)
                } else {
                    null
                }
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return queriedName
            ?: try {
                DocumentFile.fromSingleUri(context, contentUri)?.name
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            ?: normalizedHint
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

    internal suspend fun findDownloadedAudioByName(
        context: Context,
        name: String,
        forceRefresh: Boolean = false
    ): StoredEntry? = withContext(Dispatchers.IO) {
        val normalizedName = name.trim().takeIf(String::isNotBlank) ?: return@withContext null
        buildDownloadLibrarySnapshotBlocking(context, forceRefresh)
            .audioEntries
            .firstOrNull { entry -> entry.name == normalizedName }
    }

    internal suspend fun findDownloadedAudioByCandidateBaseNames(
        context: Context,
        candidateBaseNames: List<String>
    ): StoredEntry? = withContext(Dispatchers.IO) {
        val normalizedBaseNames = candidateBaseNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedBaseNames.isEmpty()) {
            return@withContext null
        }
        val root = resolveRootBlocking(context)
        val refresh = treeDirectories.refreshRootEntries(context, root)
        if (!refresh.isComplete) {
            NPLogger.w(TAG, "取消清理跳过不完整根目录查询: candidates=${normalizedBaseNames.size}")
            return@withContext null
        }
        findAudioEntry(
            audioEntries = refresh.entries,
            baseNames = normalizedBaseNames
        )
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

    suspend fun refreshStoredEntry(
        context: Context,
        reference: String?
    ): StoredEntry? = withContext(Dispatchers.IO) {
        val target = reference?.takeIf { it.isNotBlank() } ?: return@withContext null
        val cachedEntry = buildDownloadLibrarySnapshotBlocking(context)
            .audioEntriesByLookupKey[target]
        val directEntry = refreshStoredEntryDirect(
            context = context,
            reference = target,
            cachedEntry = cachedEntry
        )
        if (directEntry != null) {
            updateSnapshotCacheAfterStoredEntryWrite(
                context = context,
                storedEntry = directEntry,
                bucket = SnapshotEntryBucket.AUDIO
            )
            return@withContext directEntry
        }
        buildDownloadLibrarySnapshotBlocking(context, forceRefresh = true)
            .audioEntriesByLookupKey[target]
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

    internal suspend fun persistFastIndex(
        context: Context,
        snapshot: DownloadLibrarySnapshot
    ) = withContext(Dispatchers.IO) {
        if (!snapshot.rootEntriesComplete) return@withContext
        val libraryId = ensureManagedLibraryManifestBlocking(context)
        val entries = snapshot.audioEntries.mapNotNull { audio ->
            val metadata = snapshot.metadataByAudioName[audio.name]
            val stableKey = metadata?.stableKey?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ManagedLibraryIndexEntry(
                stableKey = stableKey,
                artifactId = metadata.artifactId ?: "managed:$libraryId:$stableKey",
                audioName = audio.name,
                audioReference = audio.reference,
                metadataName = snapshot.metadataEntriesByAudioName[audio.name]?.name,
                state = metadata.artifactState
                    ?: if (metadata.downloadFinalized == true) "FINALIZED" else "CORE_COMMITTED",
                downloadTimeMs = metadata.downloadTimeMs,
                updatedAtMs = metadata.sourceModifiedAtMs
                    ?: metadata.createdAtMs
                    ?: audio.lastModifiedMs,
                songId = metadata.songId,
                title = metadata.name,
                artist = metadata.artist,
                album = metadata.album ?: metadata.identityAlbum,
                mediaUri = metadata.mediaUri,
                channelId = metadata.channelId,
                audioId = metadata.audioId,
                subAudioId = metadata.subAudioId,
                playlistContextId = metadata.playlistContextId,
                durationMs = metadata.durationMs.takeIf { it > 0L },
                coverPath = metadata.coverPath
            )
        }
        val entriesByShard = entries.groupBy { entry -> ManagedLibraryFastIndex.shardFor(entry.stableKey) }
        val nowMs = System.currentTimeMillis()
        (0 until ManagedLibraryFastIndex.DEFAULT_SHARD_COUNT).forEach { shardNumber ->
            val shard = shardNumber.toString().padStart(2, '0')
            val payload = ManagedLibraryFastIndex.encode(
                libraryId = libraryId,
                shard = shard,
                entries = entriesByShard[shard].orEmpty(),
                generatedAtMs = nowMs
            )
            writeSubdirectoryBytesBlocking(
                context = context,
                subdirectory = MANAGED_LIBRARY_INDEX_DIR_NAME,
                displayName = "shard-$shard.json",
                bytes = payload.toByteArray(Charsets.UTF_8),
                mimeType = "application/json"
            )
        }
    }

    internal suspend fun readFastIndex(
        context: Context
    ): List<ManagedLibraryIndexEntry> = withContext(Dispatchers.IO) {
        readFastIndexBlocking(context)
    }

    internal suspend fun restoreFastIndexPreview(
        context: Context
    ): DownloadLibrarySnapshot? = withContext(Dispatchers.IO) {
        if (!restoreFastIndexPreviewBlocking(context)) {
            return@withContext null
        }
        snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = false
        )
    }

    private fun readFastIndexBlocking(context: Context): List<ManagedLibraryIndexEntry> {
        val root = resolveRootBlocking(context)
        val libraryId = findRootNamedEntry(root, MANAGED_LIBRARY_MANIFEST_FILE_NAME)
            ?.let { entry -> readTextInternal(context, entry.reference) }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.optString("libraryId")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        return listSubdirectoryEntries(context, root, MANAGED_LIBRARY_INDEX_DIR_NAME)
            .asSequence()
            .filter { entry -> entry.name.startsWith("shard-") && entry.name.endsWith(".json") }
            .mapNotNull { entry ->
                readTextInternal(context, entry.reference)
                    ?.let(ManagedLibraryFastIndex::decode)
            }
            .filter { shard -> shard.libraryId == libraryId }
            .flatMap { shard -> shard.entries.asSequence() }
            .toList()
    }

    private fun restoreFastIndexPreviewBlocking(context: Context): Boolean {
        val entries = runCatching { readFastIndexBlocking(context) }
            .getOrElse { error ->
                NPLogger.w(TAG, "读取 Managed SAF fast index 失败，回退完整重建: ${error.message}")
                return false
            }
        if (entries.isEmpty()) return false
        val root = resolveRootBlocking(context)
        val audioEntries = entries.mapNotNull { entry ->
            val currentReference = findRootNamedEntry(root, entry.audioName)?.reference
                ?: (root as? RootHandle.FileRoot)
                    ?.let { entry.audioReference.takeIf(String::isNotBlank) }
                ?: return@mapNotNull null
            StoredEntry(
                name = entry.audioName,
                reference = currentReference,
                mediaUri = currentReference,
                localFilePath = currentReference.takeIf { it.startsWith("/") },
                sizeBytes = 0L,
                lastModifiedMs = entry.updatedAtMs,
                isDirectory = false
            )
        }
        if (audioEntries.isEmpty()) return false
        val entriesByAudioName = entries.associateBy(ManagedLibraryIndexEntry::audioName)
        val metadataByAudioName = audioEntries.associate { audio ->
            val entry = entriesByAudioName.getValue(audio.name)
            entry.audioName to DownloadedAudioMetadata(
                stableKey = entry.stableKey,
                songId = entry.songId,
                album = entry.album,
                name = entry.title,
                artist = entry.artist,
                mediaUri = entry.mediaUri,
                channelId = entry.channelId,
                audioId = entry.audioId,
                subAudioId = entry.subAudioId,
                playlistContextId = entry.playlistContextId,
                durationMs = entry.durationMs ?: 0L,
                coverPath = entry.coverPath,
                downloadTimeMs = entry.downloadTimeMs,
                downloadFinalized = entry.state in setOf("FINALIZED", "COMPLETE"),
                createdAtMs = entry.updatedAtMs,
                createdAtSource = "INDEX_PREVIEW",
                artifactId = entry.artifactId,
                artifactState = entry.state,
                audioFileName = entry.audioName
            )
        }
        val cacheKey = snapshotCacheStore.currentKey(context)
        snapshotCacheStore.putSnapshot(
            context = context,
            cacheKey = cacheKey,
            snapshot = composeSnapshot(
                audioEntries = audioEntries,
                metadataEntries = emptyList(),
                metadataByAudioName = metadataByAudioName,
                coverEntries = emptyList(),
                lyricEntries = emptyList(),
                rootEntriesComplete = false
            )
        )
        NPLogger.d(TAG, "使用 Managed SAF fast index 发布预览: entries=${entries.size}")
        return true
    }

    internal suspend fun refreshDownloadSidecarSnapshot(
        context: Context,
        snapshot: DownloadLibrarySnapshot,
        forceRefresh: Boolean = false
    ): DownloadLibrarySnapshot = withContext(Dispatchers.IO) {
        refreshDownloadSidecarSnapshotBlocking(
            context = context,
            snapshot = snapshot,
            respectThrottle = !forceRefresh
        )
    }

    private fun refreshDownloadSidecarSnapshotBlocking(
        context: Context,
        snapshot: DownloadLibrarySnapshot,
        respectThrottle: Boolean,
        refreshCovers: Boolean = true
    ): DownloadLibrarySnapshot {
        val activeSnapshot = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = false
        ) ?: return snapshot
        val cacheKey = snapshotCacheStore.currentKey(context)
        if (
            shouldSkipRedundantForcedSidecarRefresh(
                requestedSnapshot = snapshot,
                activeSnapshot = activeSnapshot,
                respectThrottle = respectThrottle
            )
        ) {
            return activeSnapshot
        }
        synchronized(sidecarRefreshLock) {
            val nowMs = System.currentTimeMillis()
            if (
                respectThrottle &&
                    lastSidecarRefreshKey == cacheKey &&
                    nowMs - lastSidecarRefreshAtMs < SIDECAR_REFRESH_THROTTLE_MS
            ) {
                return activeSnapshot
            }
            val root = resolveRootBlocking(context)
            val coverRefresh = if (refreshCovers) {
                val standardCovers = treeDirectories.refreshSubdirectoryEntries(
                    context = context,
                    root = root,
                    subdirectory = COVER_SUBDIRECTORY
                )
                val remoteCovers = treeDirectories.refreshSubdirectoryEntries(
                    context = context,
                    root = root,
                    subdirectory = REMOTE_COVER_SUBDIRECTORY
                )
                ManagedDownloadTreeDirectories.SubdirectoryEntriesRefresh(
                    entries = standardCovers.entries + remoteCovers.entries,
                    isComplete = standardCovers.isComplete && remoteCovers.isComplete
                )
            } else {
                ManagedDownloadTreeDirectories.SubdirectoryEntriesRefresh(
                    entries = activeSnapshot.coverEntriesByName.values.toList(),
                    isComplete = true
                )
            }
            val lyricRefresh = treeDirectories.refreshSubdirectoryEntries(
                context = context,
                root = root,
                subdirectory = LYRIC_SUBDIRECTORY
            )
            if (!coverRefresh.isComplete || !lyricRefresh.isComplete) {
                NPLogger.w(
                    TAG,
                    "下载侧载目录刷新不完整，保留旧索引: " +
                        "coversComplete=${coverRefresh.isComplete}, " +
                        "lyricsComplete=${lyricRefresh.isComplete}"
                )
                return activeSnapshot
            }
            lastSidecarRefreshKey = cacheKey
            lastSidecarRefreshAtMs = System.currentTimeMillis()
            val updatedSnapshot = ManagedDownloadSnapshotIndex.applySidecarRefresh(
                snapshot = activeSnapshot,
                coverEntries = coverRefresh.entries,
                lyricEntries = lyricRefresh.entries
            )
            if (updatedSnapshot !== activeSnapshot) {
                snapshotCacheStore.putSnapshot(
                    context = context,
                    cacheKey = cacheKey,
                    snapshot = updatedSnapshot
                )
                notifyLyricsRefresh()
            }
            return updatedSnapshot
        }
    }

    fun isReferenceAccessible(context: Context, reference: String?): Boolean {
        return existsInternal(context, reference)
    }

    suspend fun hasReadableReference(
        context: Context,
        reference: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val target = reference?.takeIf(String::isNotBlank) ?: return@withContext false
        val entry = refreshStoredEntryDirect(
            context = context,
            reference = target,
            cachedEntry = null
        ) ?: return@withContext false
        hasReadableContent(context, entry)
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

    private fun refreshStoredEntryDirect(
        context: Context,
        reference: String,
        cachedEntry: StoredEntry?
    ): StoredEntry? {
        if (reference.startsWith("/")) {
            return File(reference)
                .takeIf { file -> file.exists() && file.isFile }
                ?.toStoredEntry()
        }
        val uri = runCatching { reference.toUri() }.getOrNull() ?: return null
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path
                ?.let(::File)
                ?.takeIf { file -> file.exists() && file.isFile }
                ?.toStoredEntry()
        }
        return ManagedDownloadReferenceIo.resolveDocumentFile(context, uri)
            ?.takeIf { document -> document.exists() && document.isFile }
            ?.toStoredEntry(knownName = cachedEntry?.name)
    }

    private fun buildDownloadLibrarySnapshotBlocking(
        context: Context,
        forceRefresh: Boolean = false
    ): DownloadLibrarySnapshot = synchronized(snapshotBuildLock) {
        // 先确认配置目录仍可写, 避免权限失效时恢复旧索引并误认为目录正常
        val root = resolveRootBlocking(context)
        val cacheKey = snapshotCacheStore.currentKey(context)
        val cachedSnapshot = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = true
        )
        if (!forceRefresh) {
            cachedSnapshot?.let { return@synchronized it }
        }

        val rootRefresh = treeDirectories.refreshRootEntries(context, root)
        val rootEntries = rootRefresh.entries.filterNot(StoredEntry::isDirectory)
        val audioEntries = rootEntries.filter { it.extension in audioExtensions }
        val metadataEntries = rootEntries.filter { ManagedDownloadTreeNaming.isMetadataName(it.name) }
        val metadataEntriesByAudioName = metadataEntries
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                    audioName to entry
                }
            }
            .groupBy { it.first }
            .mapValues { (audioName, entries) ->
                entries.minWithOrNull(
                    compareBy<Pair<String, StoredEntry>>(
                        { ManagedDownloadTreeNaming.metadataNameOrdinal(it.second.name, audioName) ?: Int.MAX_VALUE },
                        { it.second.name }
                    )
                )!!.second
            }
        var reusedMetadataCount = 0
        val metadataByAudioName = buildMap {
            metadataEntriesByAudioName.forEach { (audioName, entry) ->
                val cachedEntry = cachedSnapshot?.metadataEntriesByAudioName?.get(audioName)
                val cachedMetadata = cachedSnapshot?.metadataByAudioName?.get(audioName)
                val metadata = if (
                    canReuseCachedDownloadedMetadata(
                        cachedEntry = cachedEntry,
                        currentEntry = entry,
                        cachedMetadata = cachedMetadata
                    )
                ) {
                    reusedMetadataCount++
                    cachedMetadata
                } else {
                    parseDownloadedAudioMetadata(context, entry)
                }
                if (metadata != null) {
                    put(audioName, metadata)
                }
            }
        }
        if (forceRefresh && reusedMetadataCount > 0) {
            NPLogger.d(
                TAG,
                "刷新下载目录复用未变化 metadata: reused=$reusedMetadataCount, total=${metadataEntries.size}"
            )
        }
        val coverEntries = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
            .plus(listSubdirectoryEntries(context, root, REMOTE_COVER_SUBDIRECTORY))
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
        val canonicalAudioEntries = ManagedDownloadStorageLookup.selectCanonicalAudioEntries(
            audioEntries = managedAudioEntries,
            metadataByAudioName = metadataByAudioName
        )
        val skippedForeignAudioCount = audioEntries.size - managedAudioEntries.size
        if (skippedForeignAudioCount > 0) {
            NPLogger.d(
                TAG,
                "跳过非托管音频扫描: total=${audioEntries.size}, managed=${managedAudioEntries.size}, skipped=$skippedForeignAudioCount"
            )
        }
        return@synchronized composeSnapshot(
            audioEntries = canonicalAudioEntries,
            metadataEntries = metadataEntriesByAudioName.values.toList(),
            metadataByAudioName = metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries,
            rootEntriesComplete = rootRefresh.isComplete
        ).also { snapshot ->
            snapshotCacheStore.putSnapshot(context, cacheKey, snapshot)
        }
    }

    internal fun canReuseCachedDownloadedMetadata(
        cachedEntry: StoredEntry?,
        currentEntry: StoredEntry,
        cachedMetadata: DownloadedAudioMetadata?
    ): Boolean {
        return cachedMetadata != null &&
            cachedEntry != null &&
            cachedEntry.reference == currentEntry.reference &&
            cachedEntry.sizeBytes == currentEntry.sizeBytes &&
            cachedEntry.lastModifiedMs > 0L &&
            cachedEntry.lastModifiedMs == currentEntry.lastModifiedMs
    }

    internal fun shouldSkipRedundantForcedSidecarRefresh(
        requestedSnapshot: DownloadLibrarySnapshot,
        activeSnapshot: DownloadLibrarySnapshot?,
        respectThrottle: Boolean
    ): Boolean {
        return !respectThrottle && activeSnapshot === requestedSnapshot
    }

    private fun composeSnapshot(
        audioEntries: List<StoredEntry>,
        metadataEntries: List<StoredEntry>,
        metadataByAudioName: Map<String, DownloadedAudioMetadata>,
        coverEntries: List<StoredEntry>,
        lyricEntries: List<StoredEntry>,
        rootEntriesComplete: Boolean = true
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = audioEntries,
            metadataEntries = metadataEntries,
            metadataByAudioName = metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries,
            rootEntriesComplete = rootEntriesComplete
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
        targetsAlreadyVerified: Boolean = false,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): Int {
        return migrationFinalizer.cleanupMigratedEntries(
            context = context,
            copiedEntries = copiedEntries,
            sourceRoot = sourceRoot,
            targetsAlreadyVerified = targetsAlreadyVerified,
            progressTracker = progressTracker
        )
    }

    private suspend fun verifyMigratedEntries(
        context: Context,
        targetRoot: RootHandle,
        copiedEntries: List<CopiedMigrationEntry>
    ): Int {
        return migrationFinalizer.verifyMigratedEntries(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries
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
        val pendingMetadataName = "${audio.name}$PENDING_METADATA_SUFFIX"
        return when (val root = resolveRootBlocking(context)) {
            is RootHandle.FileRoot -> {
                val metadataFile = File(root.dir, metadataName)
                if (metadataFile.exists() && metadataFile.isFile) {
                    metadataFile.toStoredEntry()
                } else {
                    val pendingFile = File(root.dir, pendingMetadataName)
                    if (pendingFile.exists() && pendingFile.isFile) {
                        pendingFile.toStoredEntry()
                    } else {
                    root.dir.listFiles()
                        ?.asSequence()
                        ?.filter { file ->
                            ManagedDownloadTreeNaming.metadataNameOrdinal(file.name, audio.name) != null
                        }
                        ?.minWithOrNull(
                            compareBy<File>(
                                { ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, audio.name) ?: Int.MAX_VALUE },
                                { it.name }
                            )
                        )
                        ?.takeIf(File::isFile)
                        ?.toStoredEntry()
                    }
                }
            }
            is RootHandle.TreeRoot -> {
                val children = treeChildRegistry.cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = 0L
                )
                children.asSequence()
                    .filterNot(QueriedTreeChild::isDirectory)
                    .filter { child -> child.name == metadataName }
                    .firstOrNull()
                    ?.toStoredEntry()
                    ?: children.asSequence()
                    .filterNot(QueriedTreeChild::isDirectory)
                    .filter { child ->
                        ManagedDownloadTreeNaming.metadataNameOrdinal(child.name, audio.name) != null
                    }
                    .minWithOrNull(
                        compareBy<QueriedTreeChild>(
                            { ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, audio.name) ?: Int.MAX_VALUE },
                            { it.name }
                        )
                    )
                        ?.toStoredEntry()
            }
        }
    }

    suspend fun saveMetadata(context: Context, audio: StoredEntry, json: String): Boolean = withContext(Dispatchers.IO) {
        saveMetadataBlocking(context, audio, json)
    }

    internal suspend fun ensureManagedLibraryManifest(context: Context): String =
        withContext(Dispatchers.IO) {
            ensureManagedLibraryManifestBlocking(context)
        }

    internal suspend fun writePendingAudioMetadata(
        context: Context,
        audioName: String,
        json: String
    ): Boolean = withContext(Dispatchers.IO) {
        ensureManagedLibraryManifestBlocking(context)
        val root = resolveRootBlocking(context)
        val written = writeTextThroughBackend(
            context = context,
            root = root,
            displayName = "$audioName$PENDING_METADATA_SUFFIX",
            content = json
        ) ?: writeRootText(
            context = context,
            root = root,
            displayName = "$audioName$PENDING_METADATA_SUFFIX",
            content = json,
            invalidateSnapshot = false
        )
        written != null
    }

    internal suspend fun promoteCoreAudioMetadata(
        context: Context,
        audio: StoredEntry,
        json: String
    ): Boolean = withContext(Dispatchers.IO) {
        val root = resolveRootBlocking(context)
        writeTextThroughBackend(
            context = context,
            root = root,
            displayName = "${audio.name}$METADATA_SUFFIX",
            content = json
        ) ?: writeRootText(
            context = context,
            root = root,
            displayName = "${audio.name}$METADATA_SUFFIX",
            content = json,
            invalidateSnapshot = false
        ) ?: return@withContext false
        deleteRootNamedEntry(root, "${audio.name}$PENDING_METADATA_SUFFIX")
        invalidateSnapshotCache(context)
        true
    }

    internal suspend fun deletePendingAudioMetadata(
        context: Context,
        audioName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedName = audioName.trim().takeIf(String::isNotBlank)
            ?: return@withContext false
        val root = resolveRootBlocking(context)
        val pendingName = "$normalizedName$PENDING_METADATA_SUFFIX"
        if (findRootNamedEntry(root, pendingName) == null) {
            return@withContext true
        }
        deleteRootNamedEntry(root, pendingName)
        invalidateSnapshotCache(context)
        findRootNamedEntry(root, pendingName) == null
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

    private fun ensureManagedLibraryManifestBlocking(context: Context): String {
        val root = resolveRootBlocking(context)
        val existing = findRootNamedEntry(root, MANAGED_LIBRARY_MANIFEST_FILE_NAME)
            ?.let { entry -> readTextInternal(context, entry.reference) }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.optString("libraryId")
            ?.takeIf(String::isNotBlank)
        if (existing != null) {
            return existing
        }
        val libraryId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("schemaVersion", 1)
            put("libraryId", libraryId)
            put("layoutVersion", 1)
            put("createdAtMs", System.currentTimeMillis())
            put("indexFormatVersion", 1)
        }.toString()
        val written = writeRootText(
            context = context,
            root = root,
            displayName = MANAGED_LIBRARY_MANIFEST_FILE_NAME,
            content = payload,
            invalidateSnapshot = false
        )
        if (written == null) {
            throw IOException("无法写入 Managed SAF root manifest")
        }
        return libraryId
    }

    private fun findRootNamedEntry(root: RootHandle, displayName: String): StoredEntry? {
        return when (root) {
            is RootHandle.FileRoot -> {
                File(root.dir, displayName).takeIf { it.isFile }?.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                root.tree.findFile(displayName)?.toStoredEntry()
            }
        }
    }

    private fun deleteRootNamedEntry(root: RootHandle, displayName: String) {
        when (root) {
            is RootHandle.FileRoot -> {
                runCatching { File(root.dir, displayName).delete() }
            }

            is RootHandle.TreeRoot -> {
                runCatching {
                    root.tree.findFile(displayName)?.delete()
                    treeChildRegistry.forgetTreeChildName(root.tree, displayName)
                }
            }
        }
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
        // SAF 配置目录失去权限时必须停止下载, 不能隐式切换到私有目录
        resolveRootBlocking(context) is RootHandle.TreeRoot
    }

    /**
     * 存储 root 是否仍可解析: 用于区分"确实没有下载"与"SAF 列举瞬时失败"
     * 未配置自定义 SAF 目录时使用应用私有目录, 始终可解析; 配置了 SAF 树目录时
     * 只有该树仍可解析才算可用 (树不可解析时不允许回退到其他目录)
     */
    suspend fun isStorageRootResolvable(context: Context): Boolean = withContext(Dispatchers.IO) {
        val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
        if (configuredUri.isNullOrBlank()) {
            true
        } else {
            resolveTreeRootBlocking(context, configuredUri) != null
        }
    }

    /**
     * 当前配置目录对应的稳定 root 标识 ("tree:<identity>" 或 "file:<path>")
     * 用于判定一次扫描的 root 是否与既有 catalog 所属 root 一致: 切换/重置下载目录后 root 会改变
     * 据此可把"换目录后的真空"与"同目录瞬时空列举失败"区分开; 等价 URI 归一到同一 identity
     * 因此对同一底层目录的重新选择仍视为同 root
     */
    suspend fun currentSnapshotRootKey(context: Context): String = withContext(Dispatchers.IO) {
        resolveSnapshotCacheKey(context)
    }

    suspend fun readText(context: Context, reference: String): String? = withContext(Dispatchers.IO) {
        readTextInternal(context, reference)
    }

    suspend fun exists(context: Context, reference: String?): Boolean = withContext(Dispatchers.IO) {
        existsInternal(context, reference)
    }

    suspend fun hasReadableContent(
        context: Context,
        entry: StoredEntry
    ): Boolean = withContext(Dispatchers.IO) {
        if (entry.isDirectory) {
            return@withContext false
        }
        if (entry.sizeBytes > 0L) {
            return@withContext existsInternal(context, entry.reference)
        }
        try {
            val input = openStoredEntryInputStream(context, entry) ?: return@withContext false
            input.use { stream -> stream.read() != -1 }
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteReference(context: Context, reference: String?): Boolean = withContext(Dispatchers.IO) {
        deleteInternal(context, reference)
    }

    suspend fun deleteReferences(context: Context, references: Collection<String?>): Set<String> =
        withContext(Dispatchers.IO) {
            batchReferenceDeleteMutex.withLock {
                deleteReferencesInternalConcurrently(
                    context = context,
                    references = references,
                    invalidateSnapshot = true
                )
            }
        }

    suspend fun saveAudioFromTemp(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?,
        expectedSizeBytes: Long? = null,
        transferSizeVerified: Boolean = false,
        seedMetadataJson: String? = null
    ): StoredEntry = withContext(Dispatchers.IO) {
        saveAudioFromTempBlocking(
            context = context,
            tempFile = tempFile,
            fileName = fileName,
            mimeType = mimeType,
            expectedSizeBytes = expectedSizeBytes,
            transferSizeVerified = transferSizeVerified,
            seedMetadataJson = seedMetadataJson
        )
    }

    private fun saveAudioFromTempBlocking(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?,
        expectedSizeBytes: Long?,
        transferSizeVerified: Boolean,
        seedMetadataJson: String?
    ): StoredEntry {
        val actualSizeBytes = tempFile.length().coerceAtLeast(0L)
        if (actualSizeBytes <= 0L) {
            throw IOException("下载文件为空: ${tempFile.name}")
        }
        if (shouldRejectTransferSize(
                expectedSizeBytes = expectedSizeBytes,
                actualSizeBytes = actualSizeBytes,
                transferSizeVerified = transferSizeVerified
            )
        ) {
            throw IOException("下载文件大小不匹配: $actualSizeBytes/$expectedSizeBytes")
        }
        if (
            transferSizeVerified &&
            expectedSizeBytes != null &&
            !ManagedDownloadSizePolicy.isTransferSizeComplete(
                expectedSizeBytes = expectedSizeBytes,
                actualSizeBytes = actualSizeBytes
            )
        ) {
            NPLogger.d(
                TAG,
                "提交阶段忽略传输期长度提示: file=${tempFile.name}, " +
                    "actual=$actualSizeBytes, expected=$expectedSizeBytes, " +
                    "transferAlreadyVerified=$transferSizeVerified"
            )
        }
        val storedEntry = when (val root = resolveRootBlocking(context)) {
            is RootHandle.FileRoot -> {
                val existingAudio = findExistingAudioForSeedStableKey(context, seedMetadataJson)
                val finalName = existingAudio?.name
                    ?: treeChildRegistry.reserveUniqueFileChildName(root.dir, fileName)
                val pendingTarget = File(root.dir, buildPendingAudioWriteName(finalName))
                val audioEntry = try {
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
                    val target = File(root.dir, finalName)
                    replaceFileTarget(pendingTarget, target, finalName)
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
                    treeChildRegistry.forgetFileChildName(root.dir, finalName)
                    throw error
                }
                writeSeedMetadataAfterAudioCommit(
                    context = context,
                    root = root,
                    audioName = finalName,
                    seedMetadataJson = seedMetadataJson
                )
                audioEntry
            }

            is RootHandle.TreeRoot -> {
                val existingAudio = findExistingAudioForSeedStableKey(context, seedMetadataJson)
                val finalName = existingAudio?.name
                    ?: treeChildRegistry.reserveUniqueTreeChildName(context, root.tree, fileName)
                var pendingTarget: DocumentFile? = null
                var pendingName: String? = null
                val audioEntry = try {
                    val committedAtMs = System.currentTimeMillis()
                    val createdPendingName = buildPendingAudioWriteName(finalName)
                    pendingName = createdPendingName
                    val createdPendingTarget = createRootFile(
                        context = context,
                        parent = root.tree,
                        desiredName = createdPendingName,
                        mimeType = mimeTypeFromName(finalName, mimeType),
                        replace = false
                    )
                    pendingTarget = createdPendingTarget
                    pendingName = treeFileCommitter.resolvedTreeStoredName(
                        createdPendingTarget,
                        createdPendingName
                    )
                    context.contentResolver.openOutputStream(createdPendingTarget.uri, "w")?.use { output ->
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
                    treeChildRegistry.forgetTreeChildName(root.tree, finalName)
                    throw error
                }
                writeSeedMetadataAfterAudioCommit(
                    context = context,
                    root = root,
                    audioName = finalName,
                    seedMetadataJson = seedMetadataJson
                )
                audioEntry
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

    private fun findExistingAudioForSeedStableKey(
        context: Context,
        seedMetadataJson: String?
    ): StoredEntry? {
        val stableKey = seedMetadataJson
            ?.let(::parseDownloadedAudioMetadataJson)
            ?.stableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val cached = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = false
        )
        val snapshot = cached ?: buildDownloadLibrarySnapshotBlocking(context)
        return ManagedDownloadStorageLookup.selectCanonicalAudioEntries(
            audioEntries = snapshot.audioEntriesByStableKey[stableKey].orEmpty(),
            metadataByAudioName = snapshot.metadataByAudioName
        ).maxWithOrNull(
            compareByDescending<StoredEntry> { it.lastModifiedMs }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.name }
        )
    }

    private fun replaceFileTarget(
        pending: File,
        target: File,
        displayName: String
    ) {
        try {
            Files.move(
                pending.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(
                    pending.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (error: Exception) {
                throw IOException("无法提交下载文件: $displayName", error)
            }
        } catch (error: Exception) {
            throw IOException("无法提交下载文件: $displayName", error)
        }
    }

    private fun writeSeedMetadataAfterAudioCommit(
        context: Context,
        root: RootHandle,
        audioName: String,
        seedMetadataJson: String?
    ) {
        val content = seedMetadataJson?.takeIf(String::isNotBlank) ?: return
        try {
            writeRootText(
                context = context,
                root = root,
                displayName = "$audioName$METADATA_SUFFIX",
                content = content,
                invalidateSnapshot = false
            )
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "音频已提交但 seed metadata 写入失败，保留音频等待收尾重试: " +
                    "audio=$audioName, error=${error.message}",
                error
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

    suspend fun persistRemoteCoverBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String?
    ): String? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) {
            return@withContext null
        }
        writeSubdirectoryBytesBlocking(
            context = context,
            subdirectory = REMOTE_COVER_SUBDIRECTORY,
            displayName = fileName,
            bytes = bytes,
            mimeType = mimeTypeFromName(fileName, mimeType)
        )?.reference
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

    internal fun writeRomanizedLyrics(
        context: Context,
        songId: Long,
        baseName: String,
        content: String
    ): String? {
        val fileName = ManagedDownloadLyricStore.romanizedLyricFileName(baseName)
        NPLogger.d(TAG, "写入音译歌词文件: fileName=$fileName, songId=$songId")
        return overwriteLyric(context, fileName, content)
    }

    fun readLyrics(context: Context, song: SongItem, translated: Boolean): String? {
        val lyrics = readLyricsBundle(context, song)
        return if (translated) lyrics.translatedLyric else lyrics.lyric
    }

    fun readRomanizedLyrics(context: Context, song: SongItem): String? {
        return readLyricsBundle(context, song).romanizedLyric
    }

    fun readLyricsBundle(context: Context, song: SongItem): DownloadedLyricsBundle {
        val fastLyrics = readLyricsBundleFastInternal(
            context = context,
            song = song,
            allowColdSafProbe = false
        )
        if (!hasManagedLocalReference(song)) {
            return fastLyrics
        }
        val cachedSnapshot = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = false
        )
        val cachedAudio = cachedSnapshot?.let { snapshot -> findAudioEntry(snapshot, song) }
        if (cachedAudio != null) {
            if (hasCompleteLyricsSidecars(fastLyrics)) {
                return fastLyrics
            }

            // 音频索引可以比 Lyrics 目录先完成, 首轮读取必须补一次轻量侧载刷新
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "playback lyric sidecar refresh: song=${song.name}, " +
                    "original=${fastLyrics.hasOriginalSidecar}, " +
                    "translated=${fastLyrics.hasTranslatedSidecar}, " +
                    "romanized=${fastLyrics.hasRomanizedSidecar}"
            )
            val refreshedSnapshot = refreshDownloadSidecarSnapshotBlocking(
                context = context,
                snapshot = cachedSnapshot,
                respectThrottle = true,
                refreshCovers = false
            )
            val refreshedLyrics = resolveDownloadedLyricsBundle(
                context = context,
                song = song,
                snapshot = refreshedSnapshot,
                readText = { reference -> readTextInternal(context, reference) },
                exists = ::existsInternal
            )
            val refreshedDirectLyrics = if (hasCompleteLyricsSidecars(refreshedLyrics)) {
                refreshedLyrics
            } else {
                // 索引刷新可能被 provider 节流或并发扫描保守地保留旧值，当前歌曲仍需直读
                readLyricsBundleFromManagedRootFast(
                    context = context,
                    song = song,
                    metadataOverride = null
                )
            }
            return mergeLyricsBundles(
                preferred = refreshedDirectLyrics,
                fallback = mergeLyricsBundles(
                    preferred = refreshedLyrics,
                    fallback = fastLyrics
                )
            )
        }

        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "playback lyric backfill waits indexed snapshot: cached=${cachedSnapshot != null}, " +
                "song=${song.name}"
        )
        buildDownloadLibrarySnapshotBlocking(
            context = context,
            forceRefresh = cachedSnapshot != null
        )
        val completedLyrics = readLyricsBundleFastInternal(
            context = context,
            song = song,
            allowColdSafProbe = false
        )
        if (hasCompleteLyricsSidecars(completedLyrics)) {
            return completedLyrics
        }
        val directLyrics = readLyricsBundleFromManagedRootFast(
            context = context,
            song = song,
            metadataOverride = null
        )
        val mergedLyrics = mergeLyricsBundles(
            preferred = directLyrics,
            fallback = completedLyrics
        )
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "playback lyric backfill completed: original=${mergedLyrics.hasOriginalSidecar}, " +
                "translated=${mergedLyrics.hasTranslatedSidecar}, " +
                "romanized=${mergedLyrics.hasRomanizedSidecar}, song=${song.name}"
        )
        return mergedLyrics
    }

    private fun hasCompleteLyricsSidecars(bundle: DownloadedLyricsBundle): Boolean {
        return bundle.hasOriginalSidecar &&
            bundle.hasTranslatedSidecar &&
            bundle.hasRomanizedSidecar
    }

    private fun mergeLyricsBundles(
        preferred: DownloadedLyricsBundle,
        fallback: DownloadedLyricsBundle
    ): DownloadedLyricsBundle {
        fun selectValue(
            preferredValue: String?,
            preferredHasSidecar: Boolean,
            fallbackValue: String?,
            fallbackHasSidecar: Boolean
        ): String? {
            return when {
                preferredHasSidecar -> preferredValue
                fallbackHasSidecar -> fallbackValue
                else -> preferredValue ?: fallbackValue
            }
        }

        return DownloadedLyricsBundle(
            lyric = selectValue(
                preferredValue = preferred.lyric,
                preferredHasSidecar = preferred.hasOriginalSidecar,
                fallbackValue = fallback.lyric,
                fallbackHasSidecar = fallback.hasOriginalSidecar
            ),
            translatedLyric = selectValue(
                preferredValue = preferred.translatedLyric,
                preferredHasSidecar = preferred.hasTranslatedSidecar,
                fallbackValue = fallback.translatedLyric,
                fallbackHasSidecar = fallback.hasTranslatedSidecar
            ),
            romanizedLyric = selectValue(
                preferredValue = preferred.romanizedLyric,
                preferredHasSidecar = preferred.hasRomanizedSidecar,
                fallbackValue = fallback.romanizedLyric,
                fallbackHasSidecar = fallback.hasRomanizedSidecar
            ),
            hasOriginalSidecar = preferred.hasOriginalSidecar || fallback.hasOriginalSidecar,
            hasTranslatedSidecar =
                preferred.hasTranslatedSidecar || fallback.hasTranslatedSidecar,
            hasRomanizedSidecar = preferred.hasRomanizedSidecar || fallback.hasRomanizedSidecar
        )
    }

    /**
     * 读取下载歌词的首屏快路径, 优先恢复持久化索引以避免 SAF 全目录枚举
     */
    internal fun readLyricsBundleFast(
        context: Context,
        song: SongItem,
        allowColdSafProbe: Boolean = true
    ): DownloadedLyricsBundle {
        return readLyricsBundleFastInternal(
            context = context,
            song = song,
            allowColdSafProbe = allowColdSafProbe
        )
    }

    internal fun hasIndexedDownloadedSong(context: Context, song: SongItem): Boolean {
        val snapshot = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = false
        ) ?: return false
        return findAudioEntry(snapshot, song) != null
    }

    private fun hasManagedLocalReference(song: SongItem): Boolean {
        return song.localFilePath?.isNotBlank() == true ||
            song.mediaUri?.startsWith("/", ignoreCase = false) == true ||
            song.mediaUri?.startsWith("file:", ignoreCase = true) == true ||
            song.mediaUri?.startsWith("content:", ignoreCase = true) == true
    }

    private fun readLyricsBundleFastInternal(
        context: Context,
        song: SongItem,
        allowColdSafProbe: Boolean
    ): DownloadedLyricsBundle {
        // 原文、翻译和罗马字必须共用一次快照解析, 避免首屏重复查询下载目录
        val snapshot = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = false
        )
        if (snapshot == null) {
            scheduleSnapshotWarmup(context)
            val directSidecar = readLyricsBundleFromDirectSongPathFast(context, song)
            val coldSafSidecar = if (
                !allowColdSafProbe ||
                directSidecar.hasOriginalSidecar ||
                    directSidecar.hasTranslatedSidecar ||
                    directSidecar.hasRomanizedSidecar ||
                    !song.mediaUri.orEmpty().startsWith("content://", ignoreCase = true)
            ) {
                DownloadedLyricsBundle(null, null, null)
            } else {
                // 首次从 SAF 树恢复歌曲时没有可用索引, 仅查询当前歌曲的目录并缓存结果
                readLyricsBundleFromManagedRootFast(
                    context = context,
                    song = song,
                    metadataOverride = null
                )
            }
            return mergeLyricsBundles(
                preferred = mergeLyricsBundles(
                    preferred = directSidecar,
                    fallback = coldSafSidecar
                ),
                fallback = DownloadedLyricsBundle(
                    lyric = song.matchedLyric ?: song.originalLyric,
                    translatedLyric = song.matchedTranslatedLyric
                        ?: song.originalTranslatedLyric,
                    romanizedLyric = song.matchedRomanizedLyric
                        ?: song.originalRomanizedLyric
                )
            )
        }
        val indexed = resolveDownloadedLyricsBundle(
            context = context,
            song = song,
            snapshot = snapshot,
            readText = { reference -> readTextInternal(context, reference) },
            exists = ::existsInternal
        )
        val indexedBundle = DownloadedLyricsBundle(
            lyric = if (indexed.hasOriginalSidecar) {
                indexed.lyric
            } else {
                indexed.lyric ?: song.matchedLyric ?: song.originalLyric
            },
            translatedLyric = if (indexed.hasTranslatedSidecar) {
                indexed.translatedLyric
            } else {
                indexed.translatedLyric
                    ?: song.matchedTranslatedLyric
                    ?: song.originalTranslatedLyric
            },
            romanizedLyric = if (indexed.hasRomanizedSidecar) {
                indexed.romanizedLyric
            } else {
                indexed.romanizedLyric
                    ?: song.matchedRomanizedLyric
                    ?: song.originalRomanizedLyric
            },
            hasOriginalSidecar = indexed.hasOriginalSidecar,
            hasTranslatedSidecar = indexed.hasTranslatedSidecar,
            hasRomanizedSidecar = indexed.hasRomanizedSidecar
        )
        if (
            !allowColdSafProbe ||
                hasCompleteLyricsSidecars(indexedBundle)
        ) {
            return indexedBundle
        }

        // 编辑器需要识别用户刚刚新建或删除的 Lyrics 文件, 播放首屏不会走这条冷探测
        val directSidecar = readLyricsBundleFromManagedRootFast(
            context = context,
            song = song,
            metadataOverride = null
        )
        return mergeLyricsBundles(
            preferred = directSidecar,
            fallback = indexedBundle
        )
    }

    /**
     * 冷启动索引尚未恢复时只按歌曲绝对路径读取三个侧载文件
     */
    private fun readLyricsBundleFromDirectSongPathFast(
        context: Context,
        song: SongItem
    ): DownloadedLyricsBundle {
        val sourcePath = listOfNotNull(song.localFilePath, song.mediaUri)
            .asSequence()
            .mapNotNull { reference ->
                when {
                    reference.startsWith("/") -> reference
                    reference.startsWith("file:", ignoreCase = true) -> {
                        runCatching { reference.toUri().path }.getOrNull()
                    }
                    else -> null
                }
            }
            .firstOrNull()
            ?: return DownloadedLyricsBundle(null, null, null)
        val audioFile = File(sourcePath)
        val parent = audioFile.parentFile ?: return DownloadedLyricsBundle(null, null, null)
        val lyricDirectories = buildList {
            add(File(parent, LYRIC_SUBDIRECTORY))
            add(parent)
            if (isPathInside(sourcePath, LEGACY_DOWNLOAD_ROOT_PATH)) {
                add(File(LEGACY_DOWNLOAD_ROOT_PATH, LYRIC_SUBDIRECTORY))
            }
        }.distinctBy(File::getAbsolutePath)
        val candidateBaseNames = buildManagedLyricBaseNames(
            song = song,
            audioName = audioFile.name
        )
        val entries = readLyricsFromNamedFiles(
            context = context,
            directories = lyricDirectories,
            candidateBaseNames = candidateBaseNames,
            alreadyRead = emptySet()
        )
        return DownloadedLyricsBundle(
            lyric = entries[LyricKind.ORIGINAL]?.first,
            translatedLyric = entries[LyricKind.TRANSLATED]?.first,
            romanizedLyric = entries[LyricKind.ROMANIZED]?.first,
            hasOriginalSidecar = entries[LyricKind.ORIGINAL]?.second == true,
            hasTranslatedSidecar = entries[LyricKind.TRANSLATED]?.second == true,
            hasRomanizedSidecar = entries[LyricKind.ROMANIZED]?.second == true
        )
    }

    private fun readLyricsBundleFromManagedRootFast(
        context: Context,
        song: SongItem,
        metadataOverride: DownloadedAudioMetadata?
    ): DownloadedLyricsBundle {
        val startedAtNs = System.nanoTime()
        val configuredRoot = try {
            resolveRootBlocking(context)
        } catch (error: SecurityException) {
            throw error
        } catch (error: IllegalStateException) {
            // 配置了 SAF 根目录时不能静默回退到其他根目录
            if (error is moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException) {
                throw error
            }
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "configured lyric root unavailable: ${error.message}"
            )
            null
        }
        val sourceTreeRoot = resolveSourceTreeRootFast(context, song)
        val root = when {
            sourceTreeRoot != null && (
                configuredRoot !is RootHandle.TreeRoot ||
                    configuredRoot.tree.uri != sourceTreeRoot.tree.uri
                ) -> {
                NPLogger.d(
                    "ManagedDownloadLyricsPerf",
                    "首屏歌词改用歌曲自身 SAF 根: source=${sourceTreeRoot.tree.uri}, " +
                        "configured=${configuredRoot?.javaClass?.simpleName}"
                )
                sourceTreeRoot
            }
            configuredRoot != null -> configuredRoot
            else -> sourceTreeRoot
        }
            ?: return DownloadedLyricsBundle(null, null, null)
        val audioName = resolveManagedAudioDisplayName(context, song)
        val candidateBaseNames = buildManagedLyricBaseNames(
            song = song,
            audioName = audioName
        )
        val metadata = metadataOverride ?: readDownloadedMetadataFast(
            context = context,
            root = root,
            song = song,
            audioName = audioName
        )
        val referenced = resolveLyricsBundleFromReferences(
            metadata = metadata,
            originalReference = metadata?.lyricPath,
            translatedReference = metadata?.translatedLyricPath,
            romanizedReference = metadata?.romanizedLyricPath,
            readText = { reference ->
                try {
                    readTextInternal(context, reference)
                } catch (error: SecurityException) {
                    throw error
                } catch (error: Exception) {
                    NPLogger.d(
                        TAG,
                        "首屏歌词引用读取失败: reference=$reference, " +
                            "error=${error.message}"
                    )
                    null
                }
            }
        )
        val values = buildMap<LyricKind, Pair<String?, Boolean>> {
            if (referenced.hasOriginalSidecar) {
                put(LyricKind.ORIGINAL, referenced.lyric to true)
            }
            if (referenced.hasTranslatedSidecar) {
                put(LyricKind.TRANSLATED, referenced.translatedLyric to true)
            }
            if (referenced.hasRomanizedSidecar) {
                put(LyricKind.ROMANIZED, referenced.romanizedLyric to true)
            }
        }.toMutableMap()

        val directFiles = when (root) {
            is RootHandle.FileRoot -> readLyricsFromFileRootFast(
                context = context,
                root = root,
                song = song,
                candidateBaseNames = candidateBaseNames,
                alreadyRead = emptySet()
            )
            is RootHandle.TreeRoot -> readLyricsFromCachedTreeFast(
                context = context,
                root = root,
                song = song,
                candidateBaseNames = candidateBaseNames,
                alreadyRead = emptySet()
            )
        }
        // Lyrics 文件优先, 即使 npmeta.json 中仍是旧引用或嵌入歌词
        directFiles.forEach { (kind, value) -> values[kind] = value }

        val result = DownloadedLyricsBundle(
            lyric = values[LyricKind.ORIGINAL]?.first ?: referenced.lyric,
            translatedLyric = values[LyricKind.TRANSLATED]?.first
                ?: referenced.translatedLyric,
            romanizedLyric = values[LyricKind.ROMANIZED]?.first
                ?: referenced.romanizedLyric,
            hasOriginalSidecar = values[LyricKind.ORIGINAL]?.second == true,
            hasTranslatedSidecar = values[LyricKind.TRANSLATED]?.second == true,
            hasRomanizedSidecar = values[LyricKind.ROMANIZED]?.second == true
        )
        val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
        if (elapsedMs >= FAST_LYRICS_SLOW_LOG_MS) {
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "fast lyric read elapsed=${elapsedMs}ms, root=${root::class.simpleName}, " +
                    "metadata=${metadata != null}, original=${result.hasOriginalSidecar}, " +
                    "translated=${result.hasTranslatedSidecar}, " +
                    "romanized=${result.hasRomanizedSidecar}, song=${song.name}"
            )
        }
        if (
            root is RootHandle.TreeRoot &&
                metadata == null &&
                !result.hasOriginalSidecar &&
                !result.hasTranslatedSidecar &&
                !result.hasRomanizedSidecar
        ) {
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "fast lyric miss root=${root.tree.uri}, audio=$audioName, " +
                    "candidates=${candidateBaseNames.size}, source=${song.mediaUri}"
            )
            // 冷启动没有元信息引用时只显示已有字段, 完整目录索引放到后台
            scheduleSnapshotWarmup(context)
        }
        return result
    }

    /**
     * catalog 恢复早于 SAF 设置恢复时, 从歌曲自身的 tree/document URI 找到真实根目录
     */
    private fun resolveSourceTreeRootFast(
        context: Context,
        song: SongItem
    ): RootHandle.TreeRoot? {
        val references = listOfNotNull(song.mediaUri, song.localFilePath)
        val directTreeUri = references.asSequence()
            .mapNotNull(::managedDownloadTreeUri)
            .firstOrNull()
        val treeUri = directTreeUri ?: inferLegacyDownloadTreeUri(context, references)
            ?: return null
        val tree = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        return tree.takeIf { it.isDirectory }?.let(RootHandle::TreeRoot)
    }

    private fun inferLegacyDownloadTreeUri(
        context: Context,
        references: Collection<String>
    ): Uri? {
        val hasLegacyPath = references.any(::isLegacyDownloadReference)
        val hasLegacyMediaStoreReference = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreReferences = references.asSequence()
                .filter { it.startsWith("content://", ignoreCase = true) }
                .mapNotNull { runCatching { it.toUri() }.getOrNull() }
                .filter { it.authority.equals("media", ignoreCase = true) }
                .toList()
            mediaStoreReferences.isNotEmpty() && isMediaStoreSongWithinManagedRoot(
                context = context,
                songReferences = mediaStoreReferences,
                treeDocumentId = null
            )
        } else {
            false
        }
        if (!hasLegacyPath && !hasLegacyMediaStoreReference) {
            return null
        }
        return context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { permission ->
                permission.isReadPermission &&
                    DocumentsContract.isTreeUri(permission.uri) &&
                    permission.uri.authority == "com.android.externalstorage.documents"
            }
            .map { permission -> permission.uri }
            .firstOrNull { uri ->
                runCatching { DocumentsContract.getTreeDocumentId(uri) }
                    .getOrNull() == "primary:neriplayer-download"
            }
    }

    private fun isLegacyDownloadReference(reference: String): Boolean {
        val raw = reference.trim()
        val path = when {
            raw.startsWith("/") -> raw
            raw.startsWith("file:", ignoreCase = true) -> {
                runCatching { raw.toUri().path }.getOrNull()
            }
            else -> null
        } ?: return false
        return isPathInside(path, LEGACY_DOWNLOAD_ROOT_PATH)
    }

    internal fun managedDownloadTreeUri(rawReference: String?): Uri? {
        return managedDownloadTreeReference(rawReference)
            ?.let { treeReference -> runCatching { treeReference.toUri() }.getOrNull() }
    }

    internal fun managedDownloadTreeReference(rawReference: String?): String? {
        val rawUri = rawReference?.trim()?.takeIf(String::isNotBlank) ?: return null
        val schemeEnd = rawUri.indexOf("://")
        if (schemeEnd <= 0 || !rawUri.regionMatches(0, "content", 0, schemeEnd, true)) {
            return null
        }
        val authorityStart = schemeEnd + 3
        val authorityEnd = rawUri.indexOf('/', authorityStart)
            .takeIf { it >= 0 }
            ?: rawUri.length
        if (authorityEnd <= authorityStart) {
            return null
        }
        val treeMarkerStart = rawUri.indexOf("/tree/", authorityStart, ignoreCase = true)
        if (treeMarkerStart >= 0) {
            val documentMarkerStart = rawUri.indexOf(
                "/document/",
                treeMarkerStart,
                ignoreCase = true
            )
            val treeEnd = if (documentMarkerStart >= 0) {
                documentMarkerStart
            } else {
                rawUri.length
            }
            val treeReference = rawUri.substring(0, treeEnd).trimEnd('/')
            val treeDocumentId = treeReference
                .substring(treeMarkerStart + "/tree/".length)
                .takeIf(String::isNotBlank)
                ?: return null
            val authority = rawUri.substring(authorityStart, authorityEnd)
                .takeIf(String::isNotBlank)
                ?: return null
            return "content://$authority/tree/$treeDocumentId"
        }

        val sourceUri = runCatching { rawUri.toUri() }.getOrNull() ?: return null
        val treeSegmentIndex = sourceUri.pathSegments.indexOfFirst { segment ->
            segment.equals("tree", ignoreCase = true)
        }
        val treeDocumentId = sourceUri.pathSegments
            .getOrNull(treeSegmentIndex.takeIf { it >= 0 }?.plus(1) ?: -1)
            ?.takeIf(String::isNotBlank)
            ?: runCatching { DocumentsContract.getTreeDocumentId(sourceUri) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
            ?: return null
        return "${sourceUri.scheme}://${sourceUri.authority}/tree/$treeDocumentId"
    }

    internal fun resolveLyricsBundleFromReferences(
        metadata: DownloadedAudioMetadata?,
        originalReference: String?,
        translatedReference: String?,
        romanizedReference: String?,
        readText: (String) -> String?
    ): DownloadedLyricsBundle {
        fun read(reference: String?): Pair<String?, Boolean> {
            val normalized = reference?.takeIf(String::isNotBlank)
                ?: return null to false
            val content = try {
                readText(normalized)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            return content to (content != null)
        }

        val original = read(originalReference)
        val translated = read(translatedReference)
        val romanized = read(romanizedReference)
        return DownloadedLyricsBundle(
            lyric = original.first ?: metadata?.matchedLyric ?: metadata?.originalLyric,
            translatedLyric = translated.first
                ?: metadata?.matchedTranslatedLyric
                ?: metadata?.originalTranslatedLyric,
            romanizedLyric = romanized.first
                ?: metadata?.matchedRomanizedLyric
                ?: metadata?.originalRomanizedLyric,
            hasOriginalSidecar = original.second,
            hasTranslatedSidecar = translated.second,
            hasRomanizedSidecar = romanized.second
        )
    }

    private fun readDownloadedMetadataFast(
        context: Context,
        root: RootHandle,
        song: SongItem,
        audioName: String? = null
    ): DownloadedAudioMetadata? {
        val resolvedAudioName = audioName
            ?: resolveManagedAudioDisplayName(context, song)
            ?: return null
        val metadataName = "$resolvedAudioName$METADATA_SUFFIX"
        val entry = when (root) {
            is RootHandle.FileRoot -> {
                val candidates = buildList {
                    add(File(root.dir, metadataName))
                    root.dir.listFiles()
                        ?.filter { file ->
                            ManagedDownloadTreeNaming.metadataNameOrdinal(
                                actualName = file.name,
                                audioName = resolvedAudioName
                            ) != null
                        }
                        ?.sortedWith(
                            compareBy<File>(
                                { ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, resolvedAudioName) ?: Int.MAX_VALUE },
                                { it.name }
                            )
                        )
                        ?.forEach(::add)
                    song.localFilePath
                        ?.takeIf { it.startsWith("/") }
                        ?.let(::File)
                    ?.parentFile
                        ?.let { parent ->
                            add(File(parent, metadataName))
                            parent.listFiles()
                                ?.filter { file ->
                                    ManagedDownloadTreeNaming.metadataNameOrdinal(file.name, resolvedAudioName) != null
                                }
                                ?.sortedWith(
                                    compareBy<File>(
                                        { ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, resolvedAudioName) ?: Int.MAX_VALUE },
                                        { it.name }
                                    )
                                )
                                ?.forEach(::add)
                        }
                }
                candidates.firstOrNull { it.isFile }
                    ?.let { file -> file.toStoredEntry() }
            }
            is RootHandle.TreeRoot -> {
                findTreeSiblingByNameFast(
                    context = context,
                    root = root,
                    song = song,
                    childName = metadataName,
                    allowRefresh = true,
                    nameMatches = { actualName ->
                        ManagedDownloadTreeNaming.metadataNameOrdinal(actualName, resolvedAudioName) != null
                    }
                )
                    ?: treeChildRegistry.peekTreeChild(root.tree, metadataName)
                        ?.toStoredEntry()
            }
        } ?: return null
        return readTextInternal(context, entry.reference)
            ?.let(::parseDownloadedAudioMetadataJson)
    }

    private fun findTreeSiblingByNameFast(
        context: Context,
        root: RootHandle.TreeRoot,
        song: SongItem,
        childName: String,
        allowRefresh: Boolean,
        nameMatches: (String) -> Boolean = { actualName ->
            actualName.equals(childName, ignoreCase = true)
        }
    ): StoredEntry? {
        val parent = findTreeParentDocumentFast(context, root, song) ?: return null
        val children = try {
            if (allowRefresh) {
                treeChildRegistry.cachedTreeChildren(
                    context = context,
                    parent = parent,
                    maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                )
            } else {
                treeChildRegistry.peekTreeChildren(parent).orEmpty()
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "fast lyric sibling query failed parent=${parent.uri}, " +
                    "error=${error.message}"
            )
            emptyList()
        }
        return children
            .firstOrNull { child -> nameMatches(child.name) }
            ?.toStoredEntry()
    }

    private fun findTreeParentDocumentFast(
        context: Context,
        root: RootHandle.TreeRoot,
        song: SongItem
    ): DocumentFile? {
        val sourceUri = listOfNotNull(song.mediaUri, song.localFilePath)
            .firstOrNull { it.startsWith("content://", ignoreCase = true) }
            ?.let { runCatching { it.toUri() }.getOrNull() }
            ?: return null
        val path = try {
            DocumentsContract.findDocumentPath(context.contentResolver, sourceUri)?.path
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "fast lyric parent path failed source=$sourceUri, error=${error.message}"
            )
            null
        } ?: return null
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(root.tree.uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (treeDocumentId == null || path.firstOrNull() != treeDocumentId) {
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "fast lyric parent path mismatch root=$treeDocumentId, path=$path, " +
                    "source=$sourceUri"
            )
            return null
        }
        val parentDocumentId = path.dropLast(1).lastOrNull()?.takeIf(String::isNotBlank)
            ?: return null
        val parentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(root.tree.uri, parentDocumentId)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        return (
            // 保留 provider 返回的父文档 URI, 避免 fromTreeUri 将不规范 provider 的子目录折叠到根
            DocumentFile.fromSingleUri(context, parentUri)
                ?: DocumentFile.fromTreeUri(context, parentUri)
            ).also { parent ->
                if (parent == null) {
                    NPLogger.d(
                        "ManagedDownloadLyricsPerf",
                        "fast lyric parent document unresolved uri=$parentUri, source=$sourceUri"
                    )
                }
            }
    }

    private fun readLyricsFromFileRootFast(
        context: Context,
        root: RootHandle.FileRoot,
        song: SongItem,
        candidateBaseNames: List<String>,
        alreadyRead: Set<LyricKind>
    ): Map<LyricKind, Pair<String, Boolean>> {
        val directories = buildList {
            add(File(root.dir, LYRIC_SUBDIRECTORY))
            add(root.dir)
            song.localFilePath
                ?.takeIf { it.startsWith("/") }
                ?.let { path ->
                    val parent = File(path).parentFile
                    if (parent != null) {
                        add(File(parent, LYRIC_SUBDIRECTORY))
                        add(parent)
                    }
                    val legacyRoot = File(LEGACY_DOWNLOAD_ROOT_PATH)
                    if (isPathInside(path, legacyRoot.absolutePath)) {
                        add(File(legacyRoot, LYRIC_SUBDIRECTORY))
                    }
                }
        }.distinctBy(File::getAbsolutePath)

        return readLyricsFromNamedFiles(
            context = context,
            directories = directories,
            candidateBaseNames = candidateBaseNames,
            alreadyRead = alreadyRead
        )
    }

    private fun readLyricsFromCachedTreeFast(
        context: Context,
        root: RootHandle.TreeRoot,
        song: SongItem,
        candidateBaseNames: List<String>,
        alreadyRead: Set<LyricKind>
    ): Map<LyricKind, Pair<String, Boolean>> {
        if (alreadyRead.size >= LyricKind.entries.size) {
            return emptyMap()
        }

        val lyricParents = listOf(root.tree, findTreeParentDocumentFast(context, root, song))
            .filterNotNull()
            .distinctBy { parent -> parent.uri.toString() }

        data class LyricDirectoriesResult(
            val directories: List<DocumentFile>,
            val cacheIncomplete: Boolean
        )

        fun findLyricDirectories(forceRefresh: Boolean): LyricDirectoriesResult {
            var cacheIncomplete = false
            val directories = lyricParents.asSequence()
                .mapNotNull { parent ->
                    val children = try {
                        if (forceRefresh) {
                            treeChildRegistry.refreshTreeChildren(context, parent)
                        } else {
                            treeChildRegistry.cachedTreeChildren(
                                context = context,
                                parent = parent,
                                maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                            )
                        }
                    } catch (error: SecurityException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (!forceRefresh && treeChildRegistry.peekTreeChildren(parent) == null) {
                        // 提供方返回不完整时不能把结果当作歌词不存在来缓存
                        cacheIncomplete = true
                    }
                    children.firstOrNull { child ->
                        child.isDirectory && child.name.equals(LYRIC_SUBDIRECTORY, ignoreCase = true)
                    }?.let { directory ->
                        treeChildRegistry.toDocumentFile(context, parent, directory)
                            // 部分 provider 不声明标准 DocumentProvider, 但仍支持树子项查询
                            ?: DocumentFile.fromSingleUri(context, directory.documentUri)
                    }
                }
                .distinctBy { directory -> directory.uri.toString() }
                .toList()
            return LyricDirectoriesResult(
                directories = directories,
                cacheIncomplete = cacheIncomplete
            )
        }

        fun mergeSidecarBundles(
            primary: DownloadedLyricsBundle,
            fallback: DownloadedLyricsBundle
        ): DownloadedLyricsBundle {
            return DownloadedLyricsBundle(
                lyric = if (primary.hasOriginalSidecar) primary.lyric else fallback.lyric,
                translatedLyric = if (primary.hasTranslatedSidecar) {
                    primary.translatedLyric
                } else {
                    fallback.translatedLyric
                },
                romanizedLyric = if (primary.hasRomanizedSidecar) {
                    primary.romanizedLyric
                } else {
                    fallback.romanizedLyric
                },
                hasOriginalSidecar = primary.hasOriginalSidecar || fallback.hasOriginalSidecar,
                hasTranslatedSidecar = primary.hasTranslatedSidecar || fallback.hasTranslatedSidecar,
                hasRomanizedSidecar = primary.hasRomanizedSidecar || fallback.hasRomanizedSidecar
            )
        }

        fun readBundle(
            lyricDirectories: List<DocumentFile>,
            forceRefresh: Boolean
        ): DownloadedLyricsBundle {
            return lyricDirectories.fold(DownloadedLyricsBundle(null, null, null)) { bundle, directory ->
                val entries = try {
                    val children = if (forceRefresh) {
                        treeChildRegistry.refreshTreeChildren(context, directory)
                    } else {
                        treeChildRegistry.cachedTreeChildren(
                            context = context,
                            parent = directory,
                            maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                        )
                    }
                    children.map { child -> child.toStoredEntry() }
                } catch (error: SecurityException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                mergeSidecarBundles(
                    primary = bundle,
                    fallback = resolveLyricsBundleFromEntries(
                        song = song,
                        candidateBaseNames = candidateBaseNames,
                        lyricEntries = entries,
                        readText = { reference -> readTextInternal(context, reference) }
                    )
                )
            }
        }

        fun hasAnySidecar(bundle: DownloadedLyricsBundle): Boolean {
            return bundle.hasOriginalSidecar ||
                bundle.hasTranslatedSidecar ||
                bundle.hasRomanizedSidecar
        }

        fun toResult(bundle: DownloadedLyricsBundle): Map<LyricKind, Pair<String, Boolean>> {
            return buildMap {
                if (LyricKind.ORIGINAL !in alreadyRead && bundle.hasOriginalSidecar) {
                    put(LyricKind.ORIGINAL, bundle.lyric.orEmpty() to true)
                }
                if (LyricKind.TRANSLATED !in alreadyRead && bundle.hasTranslatedSidecar) {
                    put(LyricKind.TRANSLATED, bundle.translatedLyric.orEmpty() to true)
                }
                if (LyricKind.ROMANIZED !in alreadyRead && bundle.hasRomanizedSidecar) {
                    put(LyricKind.ROMANIZED, bundle.romanizedLyric.orEmpty() to true)
                }
            }
        }

        val cachedDirectories = findLyricDirectories(forceRefresh = false)
        val cachedBundle = readBundle(cachedDirectories.directories, forceRefresh = false)
        if (hasAnySidecar(cachedBundle) && !cachedDirectories.cacheIncomplete) {
            return toResult(cachedBundle)
        }

        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "fast lyric negative cache refresh root=${root.tree.uri}, " +
                "directories=${cachedDirectories.directories.size}, " +
                "cacheIncomplete=${cachedDirectories.cacheIncomplete}, " +
                "source=${song.mediaUri}"
        )
        val refreshedDirectories = findLyricDirectories(forceRefresh = true)
        val refreshedBundle = readBundle(refreshedDirectories.directories, forceRefresh = true)
        if (!hasAnySidecar(refreshedBundle)) {
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "fast lyric refresh miss root=${root.tree.uri}, " +
                    "directories=${refreshedDirectories.directories.size}, " +
                    "source=${song.mediaUri}"
            )
        }
        return toResult(refreshedBundle)
    }

    private fun readLyricsFromNamedFiles(
        context: Context,
        directories: Collection<File>,
        candidateBaseNames: List<String>,
        alreadyRead: Set<LyricKind>
    ): Map<LyricKind, Pair<String, Boolean>> {
        fun read(kind: LyricKind): Pair<String, Boolean>? {
            if (kind in alreadyRead) return null
            val names = ManagedDownloadStorageNaming.buildLyricCandidateNames(
                songId = null,
                candidateBaseNames = candidateBaseNames,
                kind = when (kind) {
                    LyricKind.ORIGINAL -> ManagedDownloadStorageNaming.LyricKind.ORIGINAL
                    LyricKind.TRANSLATED -> ManagedDownloadStorageNaming.LyricKind.TRANSLATED
                    LyricKind.ROMANIZED -> ManagedDownloadStorageNaming.LyricKind.ROMANIZED
                }
            )
            names.firstNotNullOfOrNull { name ->
                directories.asSequence()
                    .map { directory -> File(directory, name) }
                    .firstOrNull(File::isFile)
                    ?.let { file ->
                        readTextInternal(context, file.absolutePath)
                            ?.let { content -> content to true }
                    }
            }?.let { return it }
            return null
        }

        return buildMap {
            read(LyricKind.ORIGINAL)?.let { put(LyricKind.ORIGINAL, it) }
            read(LyricKind.TRANSLATED)?.let { put(LyricKind.TRANSLATED, it) }
            read(LyricKind.ROMANIZED)?.let { put(LyricKind.ROMANIZED, it) }
        }
    }

    internal fun resolveLyricsBundleFromEntries(
        song: SongItem,
        candidateBaseNames: List<String> = buildManagedLyricBaseNames(song),
        lyricEntries: Collection<StoredEntry>,
        readText: (String) -> String?
    ): DownloadedLyricsBundle {
        val entriesByName = lyricEntries
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .associateBy { it.name.lowercase() }

        fun read(kind: LyricKind): Pair<String?, Boolean> {
            val names = ManagedDownloadStorageNaming.buildLyricCandidateNames(
                songId = song.id.takeIf { it > 0L },
                candidateBaseNames = candidateBaseNames,
                kind = when (kind) {
                    LyricKind.ORIGINAL -> ManagedDownloadStorageNaming.LyricKind.ORIGINAL
                    LyricKind.TRANSLATED -> ManagedDownloadStorageNaming.LyricKind.TRANSLATED
                    LyricKind.ROMANIZED -> ManagedDownloadStorageNaming.LyricKind.ROMANIZED
                }
            )
            names.firstNotNullOfOrNull { name ->
                val entry = entriesByName[name.lowercase()] ?: return@firstNotNullOfOrNull null
                readText(entry.reference)?.let { content -> content to true }
            }?.let { return it }
            return null to false
        }

        val original = read(LyricKind.ORIGINAL)
        val translated = read(LyricKind.TRANSLATED)
        val romanized = read(LyricKind.ROMANIZED)
        return DownloadedLyricsBundle(
            lyric = original.first,
            translatedLyric = translated.first,
            romanizedLyric = romanized.first,
            hasOriginalSidecar = original.second,
            hasTranslatedSidecar = translated.second,
            hasRomanizedSidecar = romanized.second
        )
    }

    private fun buildManagedLyricBaseNames(
        song: SongItem,
        audioName: String? = null
    ): List<String> {
        val fileName = audioName
            ?: normalizeManagedAudioFileName(song.localFileName)
            ?: normalizeManagedAudioFileName(song.localFilePath)
            ?: normalizeManagedAudioFileName(song.mediaUri)
        val fileBaseName = fileName
            ?.substringBeforeLast('.', fileName)
            ?.takeIf(String::isNotBlank)
        return buildList {
            addAll(candidateManagedDownloadBaseNames(song, settings.fileNameTemplate))
            fileBaseName?.let { addAll(candidateManagedDownloadBaseNames(it)) }
        }.distinct()
    }

    private fun isPathInside(path: String, root: String): Boolean {
        val normalizedPath = path.trimEnd('/')
        val normalizedRoot = root.trimEnd('/')
        return normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")
    }

    private fun scheduleSnapshotWarmup(
        context: Context,
        refreshSidecars: Boolean = false
    ) {
        val appContext = context.applicationContext
        val cacheKey = snapshotCacheStore.currentKey(appContext)
        synchronized(snapshotWarmupLock) {
            if (snapshotWarmupKey == cacheKey && snapshotWarmupJob?.isActive == true) {
                snapshotWarmupRefreshSidecars =
                    snapshotWarmupRefreshSidecars || refreshSidecars
                return
            }
            snapshotWarmupKey = cacheKey
            snapshotWarmupRefreshSidecars = refreshSidecars
            snapshotWarmupJob = snapshotScope.launch {
                runCatching {
                    // 即使已有持久化索引, 也先验证 SAF 授权, 防止权限失效后继续展示旧目录
                    resolveRootBlocking(appContext)
                    val cachedBeforeBuild = snapshotCacheStore.cachedSnapshot(
                        context = appContext,
                        restorePersisted = false
                    )
                    val snapshot = if (cachedBeforeBuild == null) {
                        restoreFastIndexPreviewBlocking(appContext)
                        buildDownloadLibrarySnapshotBlocking(
                            context = appContext,
                            forceRefresh = true
                        )
                    } else {
                        cachedBeforeBuild
                    }
                    val shouldRefreshSidecars = synchronized(snapshotWarmupLock) {
                        snapshotWarmupRefreshSidecars
                    }
                    if (shouldRefreshSidecars) {
                        refreshDownloadSidecarSnapshotBlocking(
                            context = appContext,
                            snapshot = snapshot,
                            respectThrottle = true,
                            refreshCovers = false
                        )
                    } else if (cachedBeforeBuild == null) {
                        notifyLyricsRefresh()
                    }
                }.onFailure { error ->
                    NPLogger.w(TAG, "后台预热下载歌词索引失败: ${error.message}")
                }
                synchronized(snapshotWarmupLock) {
                    if (snapshotWarmupKey == cacheKey) {
                        snapshotWarmupJob = null
                        snapshotWarmupRefreshSidecars = false
                    }
                }
            }
        }
    }

    internal fun resolveDownloadedLyricsBundle(
        context: Context,
        song: SongItem,
        snapshot: DownloadLibrarySnapshot,
        readText: (String) -> String?,
        exists: (Context, String?) -> Boolean
    ): DownloadedLyricsBundle {
        val resolvedAudio = findAudioEntry(snapshot, song)
        val resolvedMetadata = resolvedAudio?.let { snapshot.metadataByAudioName[it.name] }

        fun readLyric(translated: Boolean): Pair<String?, Boolean> {
            val reference = ManagedDownloadLyricStore.resolveManagedLyricReference(
                context = context,
                snapshot = snapshot,
                song = song,
                resolvedAudio = resolvedAudio,
                resolvedMetadata = resolvedMetadata,
                translated = translated,
                fileNameTemplate = settings.fileNameTemplate,
                exists = exists
            )
            if (reference != null) {
                readText(reference)?.let { return it to true }
            }
            return (
                ManagedDownloadLyricStore.selectedEmbeddedLyric(resolvedMetadata, translated)
                    ?: ManagedDownloadLyricStore.fallbackEmbeddedLyric(resolvedMetadata, translated)
                ) to false
        }

        fun readRomanizedLyric(): Pair<String?, Boolean> {
            val reference = ManagedDownloadLyricStore.resolveManagedRomanizedLyricReference(
                context = context,
                snapshot = snapshot,
                song = song,
                resolvedAudio = resolvedAudio,
                resolvedMetadata = resolvedMetadata,
                fileNameTemplate = settings.fileNameTemplate,
                exists = exists
            )
            if (reference != null) {
                readText(reference)?.let { return it to true }
            }
            return (
                ManagedDownloadLyricStore.selectedEmbeddedRomanizedLyric(resolvedMetadata)
                    ?: ManagedDownloadLyricStore.fallbackEmbeddedRomanizedLyric(resolvedMetadata)
                ) to false
        }

        val original = readLyric(translated = false)
        val translated = readLyric(translated = true)
        val romanized = readRomanizedLyric()

        return DownloadedLyricsBundle(
            lyric = original.first,
            translatedLyric = translated.first,
            romanizedLyric = romanized.first,
            hasOriginalSidecar = original.second,
            hasTranslatedSidecar = translated.second,
            hasRomanizedSidecar = romanized.second
        )
    }

    internal fun findRomanizedLyricLocation(
        context: Context,
        songId: Long,
        candidateBaseNames: List<String>
    ): String? {
        val snapshot = resolveSnapshotForIndexedLookup(context)
            ?: buildDownloadLibrarySnapshotBlocking(context)
        return ManagedDownloadLyricStore.findRomanizedLyricLocation(
            snapshot = snapshot,
            songId = songId,
            candidateBaseNames = candidateBaseNames
        )
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

    private suspend fun resolveRoot(context: Context, directoryUriString: String?): RootHandle? = withContext(Dispatchers.IO) {
        resolveRootBlocking(context, directoryUriString)
    }

    private fun resolveRootBlocking(context: Context): RootHandle {
        return rootResolver.resolveConfiguredRoot(
            context = context,
            configuredDirectoryUri = settings.configuredDirectoryUri,
            onUnavailableTreeRoot = { configuredUri ->
                NPLogger.w(TAG, "自定义下载目录不可用，停止读写并等待重新授权: $configuredUri")
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
        val refresh = treeDirectories.refreshManagedMigrationEntries(context, root)
        requireCompleteMigrationDirectoryScan(
            root = root,
            isComplete = refresh.isComplete
        )
        val rootEntries = refresh.rootEntries.filterNot(StoredEntry::isDirectory)
        val metadataEntries = rootEntries.filter { ManagedDownloadTreeNaming.isMetadataName(it.name) }
        val coverEntries = refresh.coverEntries
        val remoteCoverEntries = refresh.remoteCoverEntries
        val lyricEntries = refresh.lyricEntries
        val metadataEntriesByAudioName = metadataEntries
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                    audioName to entry
                }
            }
            .groupBy { it.first }
            .mapValues { (audioName, entries) ->
                entries.minWithOrNull(
                    compareBy<Pair<String, StoredEntry>>(
                        { ManagedDownloadTreeNaming.metadataNameOrdinal(it.second.name, audioName) ?: Int.MAX_VALUE },
                        { it.second.name }
                    )
                )!!.second
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
            allowMetadataLessAudio = allowMetadataLessAudio,
            remoteCoverEntries = remoteCoverEntries
        )
    }

    private fun requireCompleteMigrationDirectoryScan(
        root: RootHandle,
        isComplete: Boolean
    ) {
        if (isComplete) {
            return
        }
        throw ManagedDownloadMigrationException.transient(
            "迁移目录枚举不完整: root=${root.javaClass.simpleName}"
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

    internal fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        kind: LyricKind
    ): List<String> {
        return ManagedDownloadStorageNaming.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            kind = when (kind) {
                LyricKind.ORIGINAL -> ManagedDownloadStorageNaming.LyricKind.ORIGINAL
                LyricKind.TRANSLATED -> ManagedDownloadStorageNaming.LyricKind.TRANSLATED
                LyricKind.ROMANIZED -> ManagedDownloadStorageNaming.LyricKind.ROMANIZED
            }
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

    internal fun applySidecarRefreshToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        coverEntries: List<StoredEntry>,
        lyricEntries: List<StoredEntry>
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.applySidecarRefresh(
            snapshot = snapshot,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries
        )
    }

    internal fun applyReferenceDeletesToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        references: Set<String>
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.applyReferenceDeletes(snapshot, references)
    }

    private fun invalidateSnapshotCache(context: Context? = null) {
        snapshotCacheStore.invalidate(context)
        notifyLyricsRefresh()
    }

    private fun notifyLyricsRefresh() {
        _lyricsRefreshVersion.value = _lyricsRefreshVersion.value + 1L
    }

    private fun cleanupPendingAudioWrites(context: Context): StartupRecoveryResult {
        return try {
            ManagedDownloadPendingAudioWriteCleaner.cleanup(
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
        } catch (error: SecurityException) {
            throw error
        } catch (error: ManagedDownloadRootUnavailableException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "下载目录不可用，跳过待提交音频清理: ${error.message}")
            StartupRecoveryResult()
        }
    }

    internal fun cleanupUnfinalizedDownloadArtifacts(context: Context): StartupRecoveryResult {
        return try {
            val root = resolveRootBlocking(context)
            val rootEntries = listChildren(context, root).filterNot(StoredEntry::isDirectory)
            val parsedMetadataEntries = rootEntries
                .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
                .mapNotNull { entry ->
                    val metadata = parseDownloadedAudioMetadata(context, entry) ?: return@mapNotNull null
                    ManagedDownloadParsedMetadataEntry(entry, metadata)
                }
            val managedSidecarReferences = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
                .plus(listSubdirectoryEntries(context, root, REMOTE_COVER_SUBDIRECTORY))
                .plus(listSubdirectoryEntries(context, root, LYRIC_SUBDIRECTORY))
                .mapTo(linkedSetOf(), StoredEntry::reference)
            val referencesToDelete = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
                rootEntries = rootEntries,
                parsedMetadataEntries = parsedMetadataEntries,
                managedSidecarReferences = managedSidecarReferences
            )
            if (referencesToDelete.isEmpty()) {
                StartupRecoveryResult()
            } else {
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
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: ManagedDownloadRootUnavailableException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "清理未完成下载半成品失败: ${error.message}")
            StartupRecoveryResult()
        }
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

    internal fun shouldRejectTransferSize(
        expectedSizeBytes: Long?,
        actualSizeBytes: Long,
        transferSizeVerified: Boolean
    ): Boolean {
        return !transferSizeVerified &&
            expectedSizeBytes != null &&
            !ManagedDownloadSizePolicy.isTransferSizeComplete(
                expectedSizeBytes = expectedSizeBytes,
                actualSizeBytes = actualSizeBytes
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
        val refresh = treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
        requireCompleteMigrationDirectoryScan(
            root = targetRoot,
            isComplete = refresh.isComplete
        )
        return ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = refresh.rootEntries,
            coverEntries = refresh.coverEntries,
            lyricEntries = refresh.lyricEntries,
            remoteCoverEntries = refresh.remoteCoverEntries,
            readText = { entry -> readTextInternal(context, entry.reference) },
            parseMetadata = ::parseDownloadedAudioMetadataJson
        )
    }

    private fun buildMigrationNamePlan(
        entries: List<ManagedMigrationEntry>,
        targetIndex: ManagedMigrationTargetIndex,
        sourceMetadataByAudioName: Map<String, DownloadedAudioMetadata>
    ) = ManagedDownloadMigrationNamePlanner.buildNamePlan(
        entries = entries.map(ManagedMigrationEntry::toRef),
        targetIndex = targetIndex,
        sourceMetadataByAudioName = sourceMetadataByAudioName
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
            COVER_SUBDIRECTORY, REMOTE_COVER_SUBDIRECTORY -> SnapshotEntryBucket.COVER
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

    private suspend fun writeTextThroughBackend(
        context: Context,
        root: RootHandle,
        displayName: String,
        content: String
    ): StoredEntry? {
        val backend: StorageBackend
        val target: StorageTarget
        when (root) {
            is RootHandle.FileRoot -> {
                backend = FileStorageBackend(root.dir)
                target = StorageTarget.FileTarget(displayName)
            }
            is RootHandle.TreeRoot -> {
                backend = SafStorageBackend(context)
                target = StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(root.tree.uri),
                    displayName = displayName,
                    mimeType = "application/octet-stream"
                )
            }
        }
        return when (
            val result = backend.writeRecoverable(target) { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            }
        ) {
            is StorageWriteResult.Written -> result.stat.toStoredEntryForBackend(
                fileRoot = (root as? RootHandle.FileRoot)?.dir
            )
            else -> null
        }
    }

    private fun StorageStat.toStoredEntryForBackend(fileRoot: File?): StoredEntry {
        val reference = when (val value = reference) {
            is StorageReference.FileRef -> fileRoot?.let { File(it, value.logicalPath).absolutePath }
                ?: value.logicalPath
            is StorageReference.SafRef -> value.uri.toString()
        }
        val localPath = (this.reference as? StorageReference.FileRef)
            ?.let { fileRoot?.let { root -> File(root, it.logicalPath).absolutePath } }
        return StoredEntry(
            name = displayName,
            reference = reference,
            mediaUri = reference,
            localFilePath = localPath,
            sizeBytes = sizeBytes ?: 0L,
            lastModifiedMs = lastModifiedMs ?: 0L,
            isDirectory = isDirectory
        )
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

    private fun restoreStoredEntryLastModified(
        context: Context,
        entry: StoredEntry,
        lastModifiedMs: Long
    ) {
        if (lastModifiedMs <= 0L) {
            return
        }
        entry.localFilePath
            ?.let(::File)
            ?.takeIf(File::exists)
            ?.setLastModified(lastModifiedMs)
            ?.let { return }
        // saf providers own the physical timestamp; preserve source time in metadata
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

    private fun resolveSnapshotCacheKey(context: Context): String {
        val appContext = context.applicationContext
        val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
        if (configuredUri != null) {
            val identity = ManagedDownloadDirectoryIdentity.directoryIdentity(configuredUri)
                ?: configuredUri
            return "tree:$identity"
        }
        val resolvedRoot = rootResolver.resolveRoot(appContext, settings.configuredDirectoryUri)
            ?: RootHandle.FileRoot(ManagedDownloadRootResolver.defaultRootDirectory(appContext))
        return when (resolvedRoot) {
            is RootHandle.TreeRoot -> {
                val treeIdentity = ManagedDownloadDirectoryIdentity.directoryIdentity(
                    resolvedRoot.tree.uri.toString()
                ) ?: resolvedRoot.tree.uri.toString()
                "tree:$treeIdentity"
            }
            is RootHandle.FileRoot -> "file:${resolvedRoot.dir.absolutePath}"
        }
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
            .also {
                if (bucket == SnapshotEntryBucket.LYRIC) {
                    notifyLyricsRefresh()
                }
            }
    }

    private fun updateSnapshotCacheAfterDelete(
        context: Context,
        deletedReferences: Set<String>
    ): Boolean {
        return snapshotCacheStore.updateAfterDelete(context, deletedReferences)
            .also { updated ->
                if (updated && deletedReferences.isNotEmpty()) {
                    notifyLyricsRefresh()
                }
            }
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
