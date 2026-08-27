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
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadParsedMetadataEntry
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadPendingArtifactCleanupPlanner
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadUnfinalizedCleanupPlanner
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_MANIFEST_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_INDEX_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
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
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntryReader
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetIndexBuilder
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationCleanupResult
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationMetadataRewriteResult
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationVerificationResult
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationTargetNames
import moe.ouom.neriplayer.core.download.storage.migration.requireSuccessfulMigrationCopies
import moe.ouom.neriplayer.core.download.storage.migration.resolveMinimumMigrationAudioCount
import moe.ouom.neriplayer.core.download.storage.migration.persistedMigrationJournalTargetNames
import moe.ouom.neriplayer.core.download.storage.migration.shouldRetryActiveMigrationJournal
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.core.download.storage.recovery.PersistentTerminalTemporaryWriteCleanupJournal
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupFinalizationPreparation
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupJournalEntry
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupJournalSnapshot
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupPreparationSnapshot
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupRoot
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupRootType
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupTarget
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProbeResult
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.startup.AppStartupWorkGate
import moe.ouom.neriplayer.core.download.storage.sidecar.ManagedDownloadLyricStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotCacheStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageMutationLocks
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.backend.readPreservingBlockFailure
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.backend.cleanupTerminalTemporaryWrites
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndex
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexEntryFactory
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationCoordinator
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationLocks
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutator
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexRebuildResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexRebuildToken
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardReadResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardStorage
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardWriteResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryIndexEntry
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
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
    private const val FAST_INDEX_MANIFEST_LOCK_SHARD = "__manifest__"

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
        tag = TAG,
        deleteTrustedReference = ::deleteTrustedReference
    )
    private val treeFileCommitter = ManagedDownloadTreeFileCommitter(
        treeChildRegistry = treeChildRegistry,
        tag = TAG,
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
        tag = TAG
    )
    private val fastIndexManifestLocks = ManagedLibraryFastIndexMutationLocks()
    private val fastIndexMutationCoordinator = ManagedLibraryFastIndexMutationCoordinator()
    private val fastIndexMutator = ManagedLibraryFastIndexMutator()
    private val migrationEntryReader = object : ManagedMigrationEntryReader {
        override suspend fun <T> read(
            context: Context,
            entry: StoredEntry,
            block: suspend (InputStream) -> T
        ): StorageLookupResult<Result<T>> {
            return readStoredEntryForMigration(context, entry, block)
        }
    }
    private val migrationCopyWorker = ManagedDownloadMigrationCopyWorker(
        tag = TAG,
        entryReader = migrationEntryReader,
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
        },
        writeReplacementRootStream = { context, root, displayName, mimeType, input, sourceEntry, targetNames, targetEntry, replacementPlan, onProgress ->
            writeMigrationRootStream(
                context = context,
                root = root,
                displayName = displayName,
                mimeType = mimeType,
                input = input,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = targetEntry,
                onProgress = onProgress,
                replacementPlan = replacementPlan
            )
        },
        writeReplacementSubdirectoryStream = { context, root, subdirectory, displayName, mimeType, input, sourceEntry, targetNames, targetEntry, replacementPlan, onProgress ->
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
                onProgress = onProgress,
                replacementPlan = replacementPlan
            )
        }
    )
    private val referenceDeleteExecutor = ManagedDownloadReferenceDeleteExecutor(
        tag = TAG,
        isReferenceAllowed = { reference, trustedReferences, managedFileRoots, managedTreeRoots ->
            isReferenceAllowedForManagedDelete(
                reference = reference,
                trustedReferences = trustedReferences.mapTo(
                    linkedSetOf(),
                    TrustedManagedRef::externalReference
                ),
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
        entryReader = migrationEntryReader,
        writeRootText = { context, root, displayName, content ->
            writeRootText(
                context = context,
                root = root,
                displayName = displayName,
                content = content
            )
        },
        restoreLastModified = { _, entry, lastModifiedMs ->
            restoreStoredEntryLastModified(entry, lastModifiedMs)
        },
        deleteReference = { context, reference, root ->
            deleteEnumeratedMigrationReference(
                context = context,
                reference = reference,
                root = root
            )
        },
        deleteReferences = {
                context,
                references,
                root,
                onDeleteStarted,
                onDeleteFinished ->
            deleteEnumeratedMigrationReferences(
                context = context,
                references = references,
                root = root,
                onDeleteStarted = onDeleteStarted,
                onDeleteFinished = onDeleteFinished
            )
        },
        rewriteMetadataReferences = ::rewriteManagedMetadataReferences,
        restoreReplacement = { context, root, copied ->
            commitWriter.restoreMigrationReplacement(
                context = context,
                root = root,
                copied = copied
            )
        }
    )
    private val pendingAudioWriteNames = ManagedDownloadPendingAudioWriteNames()
    private val migrationCleanupTrustLock = Any()

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
            AppStartupWorkGate.awaitInteractiveContentOrTimeout()
            val result = runCatching {
                if (settings.configuredDirectoryUri.isNullOrBlank()) {
                    createDefaultRoot(appContext)
                }
                val stagingRecovery = cleanupStagingFiles(appContext)
                val pendingAudioRecovery = resolveStartupPendingAudioRecovery(appContext)
                val metadataRecovery = resolveStartupMetadataRecovery(appContext)
                val terminalTemporaryWriteRecovery =
                    cleanupPersistedTerminalTemporaryWriteArtifacts(appContext)
                StartupRecoveryResult(
                    cleanedCount = stagingRecovery.cleanedCount +
                        pendingAudioRecovery.cleanedCount +
                        metadataRecovery.cleanedCount +
                        terminalTemporaryWriteRecovery.cleanedCount,
                    failedCount = stagingRecovery.failedCount +
                        pendingAudioRecovery.failedCount +
                        metadataRecovery.failedCount +
                        terminalTemporaryWriteRecovery.failedCount
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

    internal data class CancelledPendingDownloadOperation(
        val stableKey: String,
        val operationId: String
    )

    internal data class WorkingResumeFingerprint(
        val sourceUrl: String? = null,
        val etag: String? = null,
        val lastModified: String? = null,
        val expectedContentLength: Long? = null
    ) {
        val validator: String?
            get() = etag?.trim()?.takeIf { value ->
                value.length >= 2 &&
                    !value.startsWith("W/", ignoreCase = true) &&
                    value.startsWith('"') &&
                    value.endsWith('"')
            }
    }

    internal data class PendingDownloadQueueEntry(
        val stableKey: String,
        val song: SongItem,
        val order: Int,
        val queuedAtMs: Long,
        val operationId: String? = null,
        val requiresWifiNetwork: Boolean = true
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
        val isPendingAudioWrite: Boolean
            get() = name.contains(PENDING_AUDIO_WRITE_MARKER)

        val logicalName: String
            get() = name.substringBefore(PENDING_AUDIO_WRITE_MARKER, name)

        val extension: String
            get() = if (isPendingAudioWrite) {
                ""
            } else {
                name.substringAfterLast('.', "").lowercase()
            }

        val nameWithoutExtension: String
            get() = logicalName.substringBeforeLast('.', logicalName)

        val playbackUri: String
            get() = mediaUri.takeUnless { isPendingAudioWrite }.orEmpty()

        val displayName: String
            get() = logicalName
    }

    data class FinalizedPendingAudioPromotion(
        val audio: StoredEntry,
        val terminalTemporaryWriteCleanupRecorded: Boolean
    )

    data class MigrationResult(
        val movedFiles: Int,
        val skippedFiles: Int,
        val cleanupFailedFiles: Int = 0,
        val cleanupRetryableFailedFiles: Int = 0
    ) {
        val canSwitchDirectory: Boolean
            get() = skippedFiles == 0

        val canReleasePreviousPermission: Boolean
            get() = canSwitchDirectory && cleanupFailedFiles == 0

        val hasOnlyRetryableCleanupFailures: Boolean
            get() = cleanupFailedFiles > 0 &&
                cleanupRetryableFailedFiles == cleanupFailedFiles
    }

    enum class MigrationStage {
        PREPARING,
        COPYING,
        REWRITING_METADATA,
        VERIFYING,
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
        val currentFileName: String? = null,
        val verificationFilesProcessed: Int = 0,
        val verificationFilesTotal: Int = 0,
        val verifiedBytes: Long = 0L,
        val verificationBytesTotal: Long = 0L
    ) {
        val stageProcessed: Int
            get() = when (stage) {
                MigrationStage.PREPARING -> 0
                MigrationStage.COPYING -> copiedFiles
                MigrationStage.REWRITING_METADATA -> metadataFilesProcessed
                MigrationStage.VERIFYING -> verificationFilesProcessed
                MigrationStage.CLEANING_UP -> cleanupFilesProcessed
                MigrationStage.FINALIZING -> totalFiles
            }

        val stageTotal: Int
            get() = when (stage) {
                MigrationStage.PREPARING -> totalFiles
                MigrationStage.COPYING -> totalFiles
                MigrationStage.REWRITING_METADATA -> metadataFilesTotal
                MigrationStage.VERIFYING -> verificationFilesTotal
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
                val verificationProgress = when {
                    verificationBytesTotal > 0L -> {
                        (verifiedBytes.toDouble() / verificationBytesTotal.toDouble())
                            .toFloat()
                            .coerceIn(0f, 1f)
                    }
                    verificationFilesTotal <= 0 -> 1f
                    else -> {
                        (verificationFilesProcessed.toFloat() / verificationFilesTotal.toFloat())
                            .coerceIn(0f, 1f)
                    }
                }
                val cleanupProgress = when {
                    cleanupFilesTotal <= 0 -> 1f
                    else -> (cleanupFilesProcessed.toFloat() / cleanupFilesTotal.toFloat()).coerceIn(0f, 1f)
                }
                return when (stage) {
                    MigrationStage.PREPARING -> 0.02f
                    MigrationStage.COPYING -> 0.02f + copyProgress * 0.83f
                    MigrationStage.REWRITING_METADATA -> 0.85f + rewriteProgress * 0.07f
                    MigrationStage.VERIFYING -> 0.92f + verificationProgress * 0.05f
                    MigrationStage.CLEANING_UP -> 0.97f + cleanupProgress * 0.02f
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
        val metadataEmbeddingState: DownloadedAudioEmbeddingState? = null,
        val createdAtMs: Long? = null,
        val createdAtSource: String? = null,
        val artifactId: String? = null,
        val operationId: String? = null,
        val terminalTemporaryWriteCleanupToken: String? = null,
        val artifactState: String? = null,
        val audioFileName: String? = null,
        val libraryId: String? = null,
        val libraryAddedAtMs: Long? = null,
        val sourceCreatedAtMs: Long? = null,
        val sourceModifiedAtMs: Long? = null,
        val restorableMetadata: ManagedDownloadRestorableMetadata? = null
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

    suspend fun hasMigratableDownloads(
        context: Context,
        directoryUri: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val startedAtNanos = System.nanoTime()
        try {
            val root = resolveRoot(context, directoryUri)
                ?: throw ManagedDownloadRootUnavailableException(directoryUri.orEmpty())
            val allowMetadataLessAudio = shouldIndexMetadataLessAudio(directoryUri)
            var sidecarEnumerationRequired = false
            val refresh = treeDirectories.refreshManagedMigrationEntries(
                context = context,
                root = root,
                requiresSidecarEntries = { rootEntries ->
                    ManagedDownloadMigrationEntryCollector.requiresSidecarEvidence(
                        rootEntries = rootEntries,
                        allowMetadataLessAudio = allowMetadataLessAudio
                    ).also { required ->
                        sidecarEnumerationRequired = required
                    }
                }
            )
            requireCompleteMigrationDirectoryScan(
                root = root,
                isComplete = refresh.isComplete
            )
            val hasManagedEntries = ManagedDownloadMigrationEntryCollector.hasAnyManagedEntry(
                rootEntries = refresh.rootEntries,
                coverEntries = refresh.coverEntries,
                lyricEntries = refresh.lyricEntries,
                allowMetadataLessAudio = allowMetadataLessAudio
            )
            NPLogger.d(
                TAG,
                "migration_preflight stage=presence_scan status=complete " +
                    "rootType=${if (root is RootHandle.TreeRoot) "tree" else "file"} " +
                    "rootEntries=${refresh.rootEntries.size} " +
                    "coverEntries=${refresh.coverEntries.size} " +
                    "lyricEntries=${refresh.lyricEntries.size} " +
                    "sidecarEnumerationRequired=$sidecarEnumerationRequired " +
                    "managedEntriesPresent=$hasManagedEntries " +
                    "elapsedMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}"
            )
            hasManagedEntries
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "migration_preflight stage=presence_scan status=failed " +
                    "errorType=${error::class.java.simpleName} " +
                    "elapsedMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}"
            )
            throw error
        }
    }

    suspend fun hasActualDirectoryEntries(
        context: Context,
        directoryUri: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val startedAtNanos = System.nanoTime()
        try {
            val root = resolveRoot(context, directoryUri)
                ?: throw ManagedDownloadRootUnavailableException(directoryUri.orEmpty())
            val refresh = treeDirectories.refreshRootEntries(context, root)
            requireCompleteMigrationDirectoryScan(
                root = root,
                isComplete = refresh.isComplete
            )
            val rootDocumentId = (root as? RootHandle.TreeRoot)
                ?.tree
                ?.uri
                ?.let(::treeDocumentIdOrNull)
            var ignoredSelfRows = 0
            val hasActualEntries = refresh.entries.any { entry ->
                val isVirtualSelfRow = rootDocumentId != null &&
                    runCatching { treeDocumentIdOrNull(entry.mediaUri.toUri()) }
                        .getOrNull() == rootDocumentId
                if (isVirtualSelfRow) {
                    ignoredSelfRows++
                }
                !isVirtualSelfRow
            }
            NPLogger.d(
                TAG,
                "migration_preflight stage=target_non_empty status=complete " +
                    "rootType=${if (root is RootHandle.TreeRoot) "tree" else "file"} " +
                    "rootEntries=${refresh.entries.size} " +
                    "ignoredSelfRows=$ignoredSelfRows " +
                    "nonEmpty=$hasActualEntries " +
                    "elapsedMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}"
            )
            hasActualEntries
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "migration_preflight stage=target_non_empty status=failed " +
                    "errorType=${error::class.java.simpleName} " +
                    "elapsedMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}"
            )
            throw error
        }
    }

    suspend fun migrateManagedDownloads(
        context: Context,
        fromDirectoryUri: String?,
        toDirectoryUri: String?,
        minimumSourceEntryCount: Int = 0,
        targetPreviouslyCommitted: Boolean = false,
        persistedTargetNames: Map<String, String> = emptyMap(),
        onSourceAudioCountResolved: suspend (Int) -> Unit = {},
        onTargetNamePlanResolved: suspend (Map<String, String>) -> Unit = {},
        onTargetVerified: suspend () -> Unit = {},
        persistedReplacementJournal: ManagedMigrationReplacementJournal? = null,
        replacementJournalWorkId: String = "",
        onReplacementJournalUpdated: suspend (ManagedMigrationReplacementJournal) -> Unit = {}
    ): MigrationResult = withContext(Dispatchers.IO) {
        try {
            _migrationProgressFlow.value = null
            if (areEquivalentDirectoryUris(fromDirectoryUri, toDirectoryUri)) {
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }

            val targetRoot = resolveRoot(context, toDirectoryUri)
                ?: throw ManagedDownloadMigrationException.permanent("目标下载目录不可用")
            val sourceRoot = resolveRoot(context, fromDirectoryUri)
            val persistedPhase = persistedReplacementJournal?.phase
            if (persistedPhase == ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED) {
                verifyCommittedMigrationReplacementJournal(
                    context = context,
                    targetRoot = targetRoot,
                    journal = requireNotNull(persistedReplacementJournal)
                )
                verifyPreviouslyCommittedMigrationTarget(
                    context = context,
                    targetRoot = targetRoot,
                    toDirectoryUri = toDirectoryUri,
                    minimumAudioCount = minimumSourceEntryCount
                )
                onTargetVerified()
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }
            if (shouldRetryActiveMigrationJournal(
                    phase = persistedPhase,
                    sourceRootAvailable = sourceRoot != null,
                    sourceEntriesEmpty = false
                )
            ) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移源目录暂时不可用，保留事务等待恢复"
                )
            }
            if (sourceRoot == null) {
                if (!targetPreviouslyCommitted) {
                    throw if (persistedPhase == null) {
                        ManagedDownloadMigrationException.permanent("源下载目录不可用")
                    } else {
                        ManagedDownloadMigrationException.transient(
                            "迁移源目录暂时不可用，保留事务等待恢复"
                        )
                    }
                }
                if (persistedPhase != null) {
                    throw ManagedDownloadMigrationException.transient(
                        "迁移源目录暂时不可用，保留事务等待恢复"
                    )
                }
                verifyPreviouslyCommittedMigrationTarget(
                    context = context,
                    targetRoot = targetRoot,
                    toDirectoryUri = toDirectoryUri,
                    minimumAudioCount = minimumSourceEntryCount
                )
                onTargetVerified()
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }

            val entries = collectManagedMigrationEntries(
                context = context,
                root = sourceRoot,
                allowMetadataLessAudio = shouldIndexMetadataLessAudio(fromDirectoryUri)
            )
            val minimumAudioCount = resolveMinimumMigrationAudioCount(
                requestedMinimum = minimumSourceEntryCount,
                discoveredSourceAudioCount = entries.count { migrationEntry ->
                    migrationEntry.subdirectory == null &&
                        migrationEntry.entry.extension in audioExtensions
                }
            )
            onSourceAudioCountResolved(minimumAudioCount)
            fun verifyCommittedTargetBeforeReturningFailure() {
                if (!targetPreviouslyCommitted) return
                verifyPreviouslyCommittedMigrationTarget(
                    context = context,
                    targetRoot = targetRoot,
                    toDirectoryUri = toDirectoryUri,
                    minimumAudioCount = minimumAudioCount
                )
            }
            if (shouldRetryActiveMigrationJournal(
                    phase = persistedPhase,
                    sourceRootAvailable = true,
                    sourceEntriesEmpty = entries.isEmpty()
                )
            ) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移源目录未返回完整文件列表，保留事务等待恢复"
                )
            }
            if (entries.isEmpty()) {
                if (minimumAudioCount > 0) {
                    if (!targetPreviouslyCommitted) {
                        throw ManagedDownloadMigrationException.permanent(
                            "源下载目录未返回已缓存的下载文件"
                        )
                    }
                    verifyPreviouslyCommittedMigrationTarget(
                        context = context,
                        targetRoot = targetRoot,
                        toDirectoryUri = toDirectoryUri,
                        minimumAudioCount = minimumAudioCount
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
            val generatedNamePlan = buildMigrationNamePlan(
                entries = entries,
                targetIndex = targetIndex,
                sourceMetadataByAudioName = sourceMetadataByAudioName,
                replacementBackupNamespace = persistedReplacementJournal?.backupNamespace
                    ?: replacementJournalWorkId.takeIf(String::isNotBlank)
                    ?: "migration"
            )
            val persistedNames = mergePersistedMigrationTargetNames(
                buildList {
                    add(persistedTargetNames)
                    persistedReplacementJournal?.let { journal ->
                        add(persistedMigrationJournalTargetNames(journal))
                    }
                }
            )
            var namePlan = ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
                entries = entries.map(ManagedMigrationEntry::toRef),
                targetIndex = targetIndex,
                generatedPlan = generatedNamePlan,
                persistedTargetNames = persistedNames
            ) ?: generatedNamePlan
            namePlan = mergePersistedReplacementPlan(
                fromDirectoryUri = fromDirectoryUri,
                toDirectoryUri = toDirectoryUri,
                generatedPlan = namePlan,
                persistedJournal = persistedReplacementJournal
            )
            onTargetNamePlanResolved(namePlan.targetNamesByReference)
            var replacementJournal = namePlan.replacementPlansByReference
                .takeIf { it.isNotEmpty() }
                ?.let { replacements ->
                    ManagedMigrationReplacementJournal(
                        workId = replacementJournalWorkId.ifBlank {
                            persistedReplacementJournal?.workId.orEmpty()
                        },
                        fromDirectoryUri = fromDirectoryUri,
                        toDirectoryUri = toDirectoryUri,
                        backupNamespace = persistedReplacementJournal?.backupNamespace
                            ?: replacementJournalWorkId.ifBlank { "migration" },
                        phase = ManagedMigrationReplacementJournalPhase.PLANNED,
                        replacements = replacements.values.toList(),
                        targetNamesByReference = namePlan.targetNamesByReference
                    )
                }
            replacementJournal?.let { onReplacementJournalUpdated(it) }

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
            var copiedEntries = try {
                requireSuccessfulMigrationCopies(copyResults) { completedEntries ->
                    rollbackMigratedEntries(context, completedEntries, targetRoot)
                }
            } catch (error: ManagedDownloadMigrationException) {
                verifyCommittedTargetBeforeReturningFailure()
                throw error
            }

            val rewriteResult = rewriteMigratedMetadataReferences(
                context = context,
                targetRoot = targetRoot,
                copiedEntries = copiedEntries,
                progressTracker = progressTracker
            )
            copiedEntries = rewriteResult.copiedEntries
            if (rewriteResult.failedFiles > 0) {
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                verifyCommittedTargetBeforeReturningFailure()
                rewriteResult.error?.let { error -> throw error }
                return@withContext MigrationResult(
                    movedFiles = 0,
                    skippedFiles = rewriteResult.failedFiles
                )
            }

            val verificationResult = verifyMigratedEntries(
                context = context,
                targetRoot = targetRoot,
                copiedEntries = copiedEntries,
                progressTracker = progressTracker
            )
            if (verificationResult.failedFiles > 0) {
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                verifyCommittedTargetBeforeReturningFailure()
                verificationResult.error?.let { error -> throw error }
                return@withContext MigrationResult(
                    movedFiles = 0,
                    skippedFiles = verificationResult.failedFiles
                )
            }

            replacementJournal = replacementJournal?.copy(
                phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED
            )
            replacementJournal?.let { onReplacementJournalUpdated(it) }

            if (targetPreviouslyCommitted) {
                verifyPreviouslyCommittedMigrationTarget(
                    context = context,
                    targetRoot = targetRoot,
                    toDirectoryUri = toDirectoryUri,
                    minimumAudioCount = minimumAudioCount
                )
            }

            try {
                onTargetVerified()
            } catch (error: Throwable) {
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                throw error
            }

            val replacementBackupCleanup = cleanupMigrationReplacementBackups(
                context = context,
                copiedEntries = copiedEntries
            )
            if (replacementBackupCleanup.failedFiles > 0) {
                return@withContext MigrationResult(
                    movedFiles = 0,
                    skippedFiles = replacementBackupCleanup.failedFiles,
                    cleanupFailedFiles = replacementBackupCleanup.failedFiles,
                    cleanupRetryableFailedFiles =
                        replacementBackupCleanup.retryableFailedFiles
                )
            }

            val cleanupResult = cleanupMigratedEntriesDetailed(
                context = context,
                copiedEntries = copiedEntries,
                sourceRoot = sourceRoot,
                targetsAlreadyVerified = true,
                progressTracker = progressTracker
            )
            if (cleanupResult.failedFiles == 0) {
                replacementJournal = replacementJournal?.copy(
                    phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED
                )
                replacementJournal?.let { onReplacementJournalUpdated(it) }
            }
            progressTracker.finishAll()

            invalidateSnapshotCache(context)

            MigrationResult(
                movedFiles = copiedEntries.size,
                skippedFiles = 0,
                cleanupFailedFiles = cleanupResult.failedFiles,
                cleanupRetryableFailedFiles = cleanupResult.retryableFailedFiles
            )
        } catch (error: ManagedDownloadRootProviderException) {
            throw ManagedDownloadMigrationException.transient(
                "DocumentsProvider 暂时无法访问迁移目录",
                error
            )
        } finally {
            _migrationProgressFlow.value = null
        }
    }

    private fun verifyCommittedMigrationReplacementJournal(
        context: Context,
        targetRoot: RootHandle,
        journal: ManagedMigrationReplacementJournal
    ) {
        persistedMigrationJournalTargetNames(journal)
        if (journal.replacements.isEmpty()) {
            throw ManagedDownloadMigrationException.transient(
                "迁移替换事务没有可验证的目标计划"
            )
        }
        val targetIndex = buildMigrationTargetIndex(context, targetRoot)
        journal.replacements.forEach { replacement ->
            val target = targetIndex.entryFor(
                replacement.subdirectory,
                replacement.targetName
            )
            if (target == null || target.isDirectory) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移替换事务目标文件暂时不可用: ${replacement.targetName}"
                )
            }
            if (targetIndex.entryFor(replacement.subdirectory, replacement.backupName) != null) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移替换事务备份尚未清理: ${replacement.backupName}"
                )
            }
        }
    }

    private fun verifyPreviouslyCommittedMigrationTarget(
        context: Context,
        targetRoot: RootHandle,
        toDirectoryUri: String?,
        minimumAudioCount: Int
    ) {
        if (minimumAudioCount <= 0) return
        val targetEntries = collectManagedMigrationEntries(
            context = context,
            root = targetRoot,
            allowMetadataLessAudio = shouldIndexMetadataLessAudio(toDirectoryUri)
        )
        val targetAudioCount = targetEntries.count { migrationEntry ->
            migrationEntry.subdirectory == null &&
                migrationEntry.entry.extension in audioExtensions
        }
        if (targetAudioCount < minimumAudioCount) {
            throw ManagedDownloadMigrationException.transient(
                "已提交的目标下载目录文件不足: " +
                    "expected=$minimumAudioCount, actual=$targetAudioCount"
            )
        }
    }

    private fun cleanupMigrationReplacementBackups(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>
    ): ManagedMigrationCleanupResult {
        val backups = copiedEntries.mapNotNull { copied -> copied.replacementBackup }
            .distinctBy { entry -> entry.reference }
        if (backups.isEmpty()) {
            return ManagedMigrationCleanupResult(
                failedFiles = 0,
                retryableFailedFiles = 0
            )
        }
        var failed = 0
        var retryable = 0
        backups.forEach { backup ->
            val normalized = backup.reference.trim()
            val result = if (normalized.startsWith("/")) {
                val file = File(normalized)
                when {
                    !file.exists() -> StorageMutationResult.Missing
                    file.delete() && !file.exists() -> StorageMutationResult.Deleted
                    else -> StorageMutationResult.ProviderFailure(
                        IOException("replacement backup delete was not confirmed")
                    )
                }
            } else {
                trustedManagedRefOrNull(normalized)?.let { reference ->
                    deleteTrustedReference(context, reference)
                } ?: StorageMutationResult.OutOfScope
            }
            if (!result.isConfirmedStorageMutation()) {
                failed++
                if (
                    result is StorageMutationResult.ProviderFailure ||
                    result is StorageMutationResult.PermissionLost
                ) {
                    retryable++
                }
                NPLogger.w(TAG, "迁移替换备份清理未确认: ${backup.reference}")
            }
        }
        return ManagedMigrationCleanupResult(
            failedFiles = failed,
            retryableFailedFiles = retryable
        )
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
        songs: List<SongItem>,
        userInitiated: Boolean = false,
        requiresWifiNetwork: Boolean = true,
        downloadAudioQuality: DownloadAudioQualitySelection? = null
    ): List<String> {
        return ManagedDownloadRecoveryFiles.upsertPendingDownloadQueue(
            context = context,
            songs = songs,
            userInitiated = userInitiated,
            requiresWifiNetwork = requiresWifiNetwork,
            downloadAudioQuality = downloadAudioQuality
        )
    }

    internal fun listPendingQueuedDownloads(context: Context): List<PendingDownloadQueueEntry> {
        return ManagedDownloadRecoveryFiles.listPendingQueuedDownloads(context)
    }

    internal fun findQueuedOperationIdForSong(context: Context, songKey: String): String? {
        return ManagedDownloadRecoveryFiles.findQueuedOperationIdForSong(context, songKey)
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
        val normalizedDocumentId = documentId.trim().takeIf(String::isNotBlank)
            ?: return false
        val normalizedTreeDocumentId = treeDocumentId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return false
        // provider document IDs are opaque values. A slash or colon is data,
        // not evidence that one document is below another document
        return normalizedDocumentId == normalizedTreeDocumentId
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
                ?.let { it in audioExtensions }
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

    /**
     * 通过持久化逻辑文件名恢复 SAF 重授权后变化的 provider URI
     */
    internal suspend fun findCoverReferenceByFileName(
        context: Context,
        fileName: String?
    ): String? = withContext(Dispatchers.IO) {
        val normalizedName = fileName
            ?.trim()
            ?.takeIf { name ->
                name.isNotBlank() &&
                    name != "." &&
                    name != ".." &&
                    '/' !in name &&
                    '\\' !in name
            }
            ?: return@withContext null
        val snapshot = buildDownloadLibrarySnapshotBlocking(
            context = context,
            forceRefresh = true
        )
        snapshot.coverEntriesByName[normalizedName]?.reference
            ?: snapshot.coverEntriesByName.values.firstOrNull { entry ->
                entry.name.equals(normalizedName, ignoreCase = true)
            }?.reference
    }

    internal suspend fun isManagedCoverReference(
        context: Context,
        reference: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedReference = normalizeCoverReference(reference) ?: return@withContext false
        val snapshot = buildDownloadLibrarySnapshotBlocking(
            context = context,
            forceRefresh = false
        )
        snapshot.coverEntriesByName.values.any { entry ->
            listOf(entry.reference, entry.mediaUri, entry.localFilePath)
                .any { candidate ->
                    normalizeCoverReference(candidate) == normalizedReference
                }
        }
    }

    /**
     * 通过内容寻址封面哈希恢复跨 SAF 重授权后变化的 provider URI
     */
    internal suspend fun findCoverReferenceByAssetHash(
        context: Context,
        assetHash: String?
    ): String? = withContext(Dispatchers.IO) {
        val normalizedHash = assetHash
            ?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: return@withContext null
        val snapshot = buildDownloadLibrarySnapshotBlocking(
            context = context,
            forceRefresh = true
        )
        snapshot.coverEntriesByName.values.firstOrNull { entry ->
            entry.name.substringBeforeLast('.', entry.name)
                .equals(normalizedHash, ignoreCase = true)
        }?.reference
    }

    private fun normalizeCoverReference(reference: String?): String? {
        val raw = reference?.trim()?.takeIf(String::isNotBlank) ?: return null
        val uri = runCatching { raw.toUri() }.getOrNull()
        if (uri?.scheme?.equals("file", ignoreCase = true) != true) {
            return raw
        }
        val path = uri.path?.takeIf(String::isNotBlank) ?: return raw
        return runCatching { File(path).canonicalPath }.getOrElse { path }
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

    internal suspend fun listPendingAudioWrites(
        context: Context,
        forceRefresh: Boolean = false
    ): List<StoredEntry> = withContext(Dispatchers.IO) {
        val root = resolveRootBlocking(context)
        val refresh = treeDirectories.refreshRootEntries(context, root)
        if (!refresh.isComplete && forceRefresh) {
            NPLogger.w(TAG, "pending 音频枚举不完整，跳过恢复: root=${root.javaClass.simpleName}")
            return@withContext emptyList()
        }
        refresh.entries
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .filter(StoredEntry::isPendingAudioWrite)
            .toList()
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
        if (
            inspectStorageReference(context, cachedEntry.playbackUri) ==
                ManagedDownloadReferenceIo.AccessResult.Accessible
        ) {
            return@withContext cachedEntry
        }
        buildDownloadLibrarySnapshotBlocking(context, forceRefresh = true).audioEntriesByLookupKey[target]
            ?.takeIf { refreshedEntry ->
                inspectStorageReference(context, refreshedEntry.playbackUri) ==
                    ManagedDownloadReferenceIo.AccessResult.Accessible
            }
    }

    suspend fun buildDownloadLibrarySnapshot(
        context: Context,
        forceRefresh: Boolean = false,
        includeMetadataLessAudioForLegacyUpgrade: Boolean = false
    ): DownloadLibrarySnapshot = withContext(Dispatchers.IO) {
        buildDownloadLibrarySnapshotBlocking(
            context = context,
            forceRefresh = forceRefresh,
            includeMetadataLessAudioForLegacyUpgrade =
                includeMetadataLessAudioForLegacyUpgrade
        )
    }

    internal suspend fun buildLegacyUpgradeSnapshot(
        context: Context
    ): DownloadLibrarySnapshot = withContext(Dispatchers.IO) {
        synchronized(snapshotBuildLock) {
            val root = resolveRootBlocking(context)
            val cachedSnapshot = snapshotCacheStore.cachedSnapshot(
                context = context,
                restorePersisted = true
            )
            val rootRefresh = treeDirectories.refreshRootEntries(context, root)
            val rootEntries = rootRefresh.entries.filterNot(StoredEntry::isDirectory)
            val audioEntries = rootEntries.filter { entry ->
                entry.extension in audioExtensions
            }
            val metadataEntriesByAudioName = rootEntries.asSequence()
                .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
                .mapNotNull { entry ->
                    ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                        audioName to entry
                    }
                }
                .groupBy { (audioName, _) -> audioName }
                .mapValues { (audioName, entries) ->
                    entries.minWithOrNull(
                        compareBy<Pair<String, StoredEntry>>(
                            {
                                ManagedDownloadTreeNaming.metadataNameOrdinal(
                                    it.second.name,
                                    audioName
                                ) ?: Int.MAX_VALUE
                            },
                            { it.second.name }
                        )
                    )!!.second
                }
            val reusableCachedMetadata = selectReusableCachedDownloadedMetadata(
                currentEntries = metadataEntriesByAudioName,
                cachedSnapshot = cachedSnapshot
            )
            val coverEntries = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
            composeSnapshot(
                audioEntries = audioEntries,
                metadataEntries = metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = reusableCachedMetadata,
                coverEntries = coverEntries,
                lyricEntries = emptyList(),
                rootEntriesComplete = rootRefresh.isComplete
            )
        }
    }

    internal suspend fun upsertCompleteFastIndexEntry(
        context: Context,
        entry: ManagedLibraryIndexEntry
    ): ManagedLibraryFastIndexMutationResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = resolveRootBlocking(appContext)
        val rootIdentity = fastIndexRootIdentity(root)
        val libraryId = ensureManagedLibraryManifestForRoot(
            context = appContext,
            root = root,
            rootIdentity = rootIdentity
        )
        fastIndexMutationCoordinator.mutate(rootIdentity) {
            fastIndexMutator.upsertCompleteEntry(
                rootIdentity = rootIdentity,
                libraryId = libraryId,
                entry = entry,
                storage = fastIndexShardStorage(appContext, root, rootIdentity)
            )
        }
    }

    internal suspend fun upsertCompleteFastIndexEntry(
        context: Context,
        song: SongItem,
        audio: StoredEntry,
        state: String,
        coverPath: String?
    ): ManagedLibraryFastIndexMutationResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = resolveRootBlocking(appContext)
        val rootIdentity = fastIndexRootIdentity(root)
        val libraryId = ensureManagedLibraryManifestForRoot(
            context = appContext,
            root = root,
            rootIdentity = rootIdentity
        )
        fastIndexMutationCoordinator.mutate(rootIdentity) {
            fastIndexMutator.upsertCompleteEntry(
                rootIdentity = rootIdentity,
                libraryId = libraryId,
                entry = ManagedLibraryFastIndexEntryFactory.fromCompletedDownload(
                    libraryId = libraryId,
                    song = song,
                    audio = audio,
                    state = state,
                    coverPath = coverPath
                ),
                storage = fastIndexShardStorage(appContext, root, rootIdentity)
            )
        }
    }

    internal suspend fun updateExistingFastIndexEntry(
        context: Context,
        stableKey: String,
        transform: (ManagedLibraryIndexEntry) -> ManagedLibraryIndexEntry
    ): ManagedLibraryFastIndexMutationResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = resolveRootBlocking(appContext)
        val rootIdentity = fastIndexRootIdentity(root)
        val libraryId = readManagedLibraryIdForRoot(appContext, root, rootIdentity)
            ?: return@withContext missingFastIndexManifestResult(stableKey)
        fastIndexMutationCoordinator.mutate(rootIdentity) {
            fastIndexMutator.updateExistingEntry(
                rootIdentity = rootIdentity,
                libraryId = libraryId,
                stableKey = stableKey,
                storage = fastIndexShardStorage(appContext, root, rootIdentity),
                transform = transform
            )
        }
    }

    internal suspend fun removeFastIndexEntry(
        context: Context,
        stableKey: String
    ): ManagedLibraryFastIndexMutationResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = resolveRootBlocking(appContext)
        val rootIdentity = fastIndexRootIdentity(root)
        val libraryId = readManagedLibraryIdForRoot(appContext, root, rootIdentity)
            ?: return@withContext missingFastIndexManifestResult(stableKey)
        fastIndexMutationCoordinator.mutate(rootIdentity) {
            fastIndexMutator.remove(
                rootIdentity = rootIdentity,
                libraryId = libraryId,
                stableKey = stableKey,
                storage = fastIndexShardStorage(appContext, root, rootIdentity)
            )
        }
    }

    private fun missingFastIndexManifestResult(
        stableKey: String
    ): ManagedLibraryFastIndexMutationResult.Failed {
        val shard = stableKey.takeIf(String::isNotBlank)
            ?.let(ManagedLibraryFastIndex::shardFor)
            .orEmpty()
        return ManagedLibraryFastIndexMutationResult.Failed(
            shard = shard,
            error = IOException("managed library manifest is unavailable")
        )
    }

    private fun fastIndexRootIdentity(root: RootHandle): String {
        return when (root) {
            is RootHandle.FileRoot -> {
                val path = runCatching { root.dir.canonicalPath }
                    .getOrElse { root.dir.absolutePath }
                "file:$path"
            }
            is RootHandle.TreeRoot -> {
                val rawUri = root.tree.uri.toString()
                val identity = ManagedDownloadDirectoryIdentity.directoryIdentity(rawUri)
                    ?: rawUri
                "tree:$identity"
            }
        }
    }

    private suspend fun ensureManagedLibraryManifestForRoot(
        context: Context,
        root: RootHandle,
        rootIdentity: String = fastIndexRootIdentity(root)
    ): String {
        return fastIndexManifestLocks.withLock(
            rootIdentity = rootIdentity,
            shard = FAST_INDEX_MANIFEST_LOCK_SHARD
        ) {
            ensureManagedLibraryManifestBlocking(context, root)
        }
    }

    private suspend fun readManagedLibraryIdForRoot(
        context: Context,
        root: RootHandle,
        rootIdentity: String = fastIndexRootIdentity(root)
    ): String? {
        return fastIndexManifestLocks.withLock(
            rootIdentity = rootIdentity,
            shard = FAST_INDEX_MANIFEST_LOCK_SHARD
        ) {
            readManagedLibraryIdBlocking(context, root)
        }
    }

    private fun fastIndexShardStorage(
        context: Context,
        root: RootHandle,
        expectedRootIdentity: String
    ): ManagedLibraryFastIndexShardStorage {
        return object : ManagedLibraryFastIndexShardStorage {
            override suspend fun readShard(
                rootIdentity: String,
                shard: String
            ): ManagedLibraryFastIndexShardReadResult {
                if (
                    rootIdentity != expectedRootIdentity ||
                        fastIndexRootIdentity(root) != expectedRootIdentity
                ) {
                    return ManagedLibraryFastIndexShardReadResult.Unavailable(
                        IllegalStateException("fast index root changed during read")
                    )
                }
                return readFastIndexShardBlocking(context, root, shard)
            }

            override suspend fun writeShard(
                rootIdentity: String,
                shard: String,
                payload: String
            ): ManagedLibraryFastIndexShardWriteResult {
                if (
                    rootIdentity != expectedRootIdentity ||
                        fastIndexRootIdentity(root) != expectedRootIdentity
                ) {
                    return ManagedLibraryFastIndexShardWriteResult.Unavailable(
                        IllegalStateException("fast index root changed during write")
                    )
                }
                return writeFastIndexShardBlocking(
                    context = context,
                    root = root,
                    shard = shard,
                    payload = payload
                )
            }
        }
    }

    private fun readFastIndexShardBlocking(
        context: Context,
        root: RootHandle,
        shard: String
    ): ManagedLibraryFastIndexShardReadResult {
        val displayName = "shard-$shard.json"
        return try {
            val entries = when (root) {
                is RootHandle.FileRoot -> {
                    val indexDirectory = File(root.dir, MANAGED_LIBRARY_INDEX_DIR_NAME)
                    if (!indexDirectory.exists()) {
                        return ManagedLibraryFastIndexShardReadResult.Missing
                    }
                    if (!indexDirectory.isDirectory) {
                        return ManagedLibraryFastIndexShardReadResult.Unavailable(
                            IOException("fast index path is not a directory")
                        )
                    }
                    val target = File(indexDirectory, displayName)
                    if (!target.exists()) {
                        return ManagedLibraryFastIndexShardReadResult.Missing
                    }
                    listOf(ManagedDownloadStoredEntryMapper.fromFile(target))
                }
                is RootHandle.TreeRoot -> {
                    val refresh = treeDirectories.refreshSubdirectoryEntries(
                        context = context,
                        root = root,
                        subdirectory = MANAGED_LIBRARY_INDEX_DIR_NAME
                    )
                    if (!refresh.isComplete) {
                        return ManagedLibraryFastIndexShardReadResult.Unavailable(
                            IOException("fast index SAF enumeration is incomplete")
                        )
                    }
                    refresh.entries.filter { entry -> entry.name == displayName }
                }
            }
            if (entries.isEmpty()) {
                return ManagedLibraryFastIndexShardReadResult.Missing
            }
            if (entries.size != 1 || entries.single().isDirectory) {
                return ManagedLibraryFastIndexShardReadResult.Unavailable(
                    IOException("fast index target shard is ambiguous: $displayName")
                )
            }
            val payload = when (root) {
                is RootHandle.FileRoot -> File(entries.single().reference).readText(Charsets.UTF_8)
                is RootHandle.TreeRoot -> readTextInternal(context, entries.single().reference)
                    ?: return ManagedLibraryFastIndexShardReadResult.Unavailable(
                        IOException("fast index target shard cannot be read: $displayName")
                    )
            }
            ManagedLibraryFastIndexShardReadResult.Found(payload)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ManagedLibraryFastIndexShardReadResult.Unavailable(error)
        }
    }

    private fun writeFastIndexShardBlocking(
        context: Context,
        root: RootHandle,
        shard: String,
        payload: String
    ): ManagedLibraryFastIndexShardWriteResult {
        val displayName = "shard-$shard.json"
        return try {
            val verifiedPayload = when (root) {
                is RootHandle.FileRoot -> {
                    val indexDirectory = File(root.dir, MANAGED_LIBRARY_INDEX_DIR_NAME)
                    if (indexDirectory.exists() && !indexDirectory.isDirectory) {
                        return ManagedLibraryFastIndexShardWriteResult.Unavailable(
                            IOException("fast index path is not a directory")
                        )
                    }
                    treeDirectories.ensureManagedMediaScanIsolation(
                        MANAGED_LIBRARY_INDEX_DIR_NAME,
                        indexDirectory
                    )
                    val target = File(indexDirectory, displayName)
                    ManagedDownloadAtomicFile.writeTextAtomically(target, payload)
                    target.readText(Charsets.UTF_8)
                }
                is RootHandle.TreeRoot -> {
                    val entry = commitWriter.writeSubdirectoryBytes(
                        context = context,
                        root = root,
                        subdirectory = MANAGED_LIBRARY_INDEX_DIR_NAME,
                        displayName = displayName,
                        bytes = payload.toByteArray(Charsets.UTF_8),
                        mimeType = "application/json"
                    ) ?: return ManagedLibraryFastIndexShardWriteResult.Unavailable(
                        IOException("fast index target shard was not written: $displayName")
                    )
                    readTextInternal(context, entry.reference)
                }
            }
            if (verifiedPayload != payload) {
                return ManagedLibraryFastIndexShardWriteResult.Unavailable(
                    IOException("fast index target shard verification failed: $displayName")
                )
            }
            ManagedLibraryFastIndexShardWriteResult.Written
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ManagedLibraryFastIndexShardWriteResult.Unavailable(error)
        }
    }

    internal suspend fun captureFastIndexRebuildToken(
        context: Context
    ): ManagedLibraryFastIndexRebuildToken = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = resolveRootBlocking(appContext)
        fastIndexMutationCoordinator.capture(fastIndexRootIdentity(root))
    }

    internal suspend fun persistFastIndex(
        context: Context,
        snapshot: DownloadLibrarySnapshot,
        rebuildToken: ManagedLibraryFastIndexRebuildToken
    ) = withContext(Dispatchers.IO) {
        if (!shouldPersistFastIndex(snapshot)) return@withContext false
        val appContext = context.applicationContext
        val root = resolveRootBlocking(appContext)
        val rootIdentity = fastIndexRootIdentity(root)
        val libraryId = ensureManagedLibraryManifestForRoot(
            context = appContext,
            root = root,
            rootIdentity = rootIdentity
        )
        val entries = snapshot.audioEntries.mapNotNull { audio ->
            val metadata = snapshot.metadataByAudioName[audio.name]
            if (
                !isFinalizedDownloadedAudioEntry(
                    rootEntriesComplete = snapshot.rootEntriesComplete,
                    isPendingAudioWrite = audio.isPendingAudioWrite,
                    metadata = metadata
                )
            ) {
                return@mapNotNull null
            }
            val stableKey = metadata?.stableKey?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ManagedLibraryIndexEntry(
                stableKey = stableKey,
                artifactId = metadata.artifactId ?: "managed:$libraryId:$stableKey",
                audioName = audio.name,
                audioReference = audio.reference,
                metadataName = snapshot.metadataEntriesByAudioName[audio.name]?.name,
                state = metadata.artifactState ?: "FINALIZED",
                metadataEmbeddingState = metadata.metadataEmbeddingState,
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
                coverPath = ManagedDownloadCoverLookup.findCoverReference(snapshot, audio)
            )
        }
        val entriesByShard = entries.groupBy { entry -> ManagedLibraryFastIndex.shardFor(entry.stableKey) }
        val rebuildResult = fastIndexMutationCoordinator.rebuild(
            token = rebuildToken,
            currentRootIdentity = rootIdentity
        ) {
            val existingShards = listSubdirectoryEntries(
                context = appContext,
                root = root,
                subdirectory = MANAGED_LIBRARY_INDEX_DIR_NAME
            ).asSequence()
                .filter { entry ->
                    entry.name.startsWith("shard-") && entry.name.endsWith(".json")
                }
                .mapNotNull { entry ->
                    readTextInternal(appContext, entry.reference)
                        ?.let(ManagedLibraryFastIndex::decode)
                }
                .filter { shard -> shard.libraryId == libraryId }
                .associate { shard -> shard.shard to shard.entries }
            val allShards = (existingShards.keys + entriesByShard.keys).associateWith { shard ->
                entriesByShard[shard].orEmpty()
            }
            val changedShards = ManagedLibraryFastIndex.changedShards(existingShards, allShards)
            val nowMs = System.currentTimeMillis()
            changedShards.forEach { shard ->
                val payload = ManagedLibraryFastIndex.encode(
                    libraryId = libraryId,
                    shard = shard,
                    entries = allShards[shard].orEmpty(),
                    generatedAtMs = nowMs
                )
                when (
                    val writeResult = writeFastIndexShardBlocking(
                        context = appContext,
                        root = root,
                        shard = shard,
                        payload = payload
                    )
                ) {
                    ManagedLibraryFastIndexShardWriteResult.Written -> Unit
                    is ManagedLibraryFastIndexShardWriteResult.Unavailable -> {
                        throw IOException(
                            "fast index shard write failed: $shard",
                            writeResult.error
                        )
                    }
                }
            }
        }
        when (rebuildResult) {
            is ManagedLibraryFastIndexRebuildResult.Applied -> true
            ManagedLibraryFastIndexRebuildResult.Stale -> false
        }
    }

    internal fun shouldPersistFastIndex(snapshot: DownloadLibrarySnapshot): Boolean {
        // incomplete metadata is not evidence that the corresponding shard is empty
        return snapshot.rootEntriesComplete && snapshot.audioEntriesWithoutMetadata.isEmpty()
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

    private data class FastIndexReadResult(
        val entries: List<ManagedLibraryIndexEntry>,
        val rootEntries: List<ManagedLibraryFastIndex.RootEntry>
    )

    private fun readFastIndexWithRootEntriesBlocking(
        context: Context
    ): FastIndexReadResult? {
        val root = resolveRootBlocking(context)
        val rootEntries = listChildren(context, root)
            .filterNot(StoredEntry::isDirectory)
            .map { entry ->
                ManagedLibraryFastIndex.RootEntry(
                    name = entry.name,
                    reference = entry.reference
                )
            }
        val libraryId = rootEntries
            .firstOrNull { entry -> entry.name == MANAGED_LIBRARY_MANIFEST_FILE_NAME }
            ?.let { entry -> readTextInternal(context, entry.reference) }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.optString("libraryId")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val entries = listSubdirectoryEntries(context, root, MANAGED_LIBRARY_INDEX_DIR_NAME)
            .asSequence()
            .filter { entry -> entry.name.startsWith("shard-") && entry.name.endsWith(".json") }
            .mapNotNull { entry ->
                readTextInternal(context, entry.reference)
                    ?.let(ManagedLibraryFastIndex::decode)
            }
            .filter { shard -> shard.libraryId == libraryId }
            .flatMap { shard -> shard.entries.asSequence() }
            .toList()
        return FastIndexReadResult(entries = entries, rootEntries = rootEntries)
    }

    private fun restoreFastIndexPreviewBlocking(context: Context): Boolean {
        val index = runCatching { readFastIndexWithRootEntriesBlocking(context) }
            .getOrElse { error ->
                NPLogger.w(TAG, "读取 Managed SAF fast index 失败，回退完整重建: ${error.message}")
                return false
            }
            ?: return false
        val entries = index.entries
        if (entries.isEmpty()) return false
        val currentReferences = ManagedLibraryFastIndex.joinAudioReferences(
            entries,
            index.rootEntries
        )
        val audioEntries = entries.mapNotNull { entry ->
            val currentReference = currentReferences[entry.audioName]
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
                metadataEmbeddingState = entry.metadataEmbeddingState,
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
                standardCovers
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

    private fun findDownloadedAudioBlocking(
        context: Context,
        song: SongItem,
        forceRefresh: Boolean = false
    ): StoredEntry? {
        val snapshot = buildDownloadLibrarySnapshotBlocking(context, forceRefresh = forceRefresh)
        val entry = findAudioEntry(snapshot, song) ?: return null
        if (
            inspectStorageReference(context, entry.playbackUri) ==
                ManagedDownloadReferenceIo.AccessResult.Accessible
        ) {
            return entry
        }
        if (forceRefresh) {
            return null
        }
        return findDownloadedAudioBlocking(context, song, forceRefresh = true)
    }

    private fun buildDownloadLibrarySnapshotBlocking(
        context: Context,
        forceRefresh: Boolean = false,
        includeMetadataLessAudioForLegacyUpgrade: Boolean = false
    ): DownloadLibrarySnapshot = synchronized(snapshotBuildLock) {
        // 先确认配置目录仍可写, 避免权限失效时恢复旧索引并误认为目录正常
        val root = resolveRootBlocking(context)
        val cacheKey = snapshotCacheStore.currentKey(context)
        val cachedSnapshot = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = true
        )
        if (!forceRefresh && !includeMetadataLessAudioForLegacyUpgrade) {
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
        val lyricEntries = listSubdirectoryEntries(context, root, LYRIC_SUBDIRECTORY)
        val coverEntriesByName = coverEntries.associateBy(StoredEntry::name)
        val lyricEntriesByName = lyricEntries.associateBy(StoredEntry::name)
        val allowMetadataLessAudio = includeMetadataLessAudioForLegacyUpgrade ||
            shouldIndexMetadataLessAudio()
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
        val snapshot = composeSnapshot(
            audioEntries = canonicalAudioEntries,
            metadataEntries = metadataEntriesByAudioName.values.toList(),
            metadataByAudioName = metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries,
            rootEntriesComplete = rootRefresh.isComplete
        )
        if (!includeMetadataLessAudioForLegacyUpgrade) {
            snapshotCacheStore.putSnapshot(context, cacheKey, snapshot)
        }
        return@synchronized snapshot
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

    internal fun selectReusableCachedDownloadedMetadata(
        currentEntries: Map<String, StoredEntry>,
        cachedSnapshot: DownloadLibrarySnapshot?
    ): Map<String, DownloadedAudioMetadata> {
        if (cachedSnapshot == null) return emptyMap()
        return currentEntries.mapNotNull { (audioName, currentEntry) ->
            val cachedEntry = cachedSnapshot.metadataEntriesByAudioName[audioName]
            val cachedMetadata = cachedSnapshot.metadataByAudioName[audioName]
            if (
                canReuseCachedDownloadedMetadata(
                    cachedEntry = cachedEntry,
                    currentEntry = currentEntry,
                    cachedMetadata = cachedMetadata
                )
            ) {
                audioName to checkNotNull(cachedMetadata)
            } else {
                null
            }
        }.toMap()
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
    ): ManagedMigrationMetadataRewriteResult {
        return migrationFinalizer.rewriteMigratedMetadataReferences(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries,
            progressTracker = progressTracker
        )
    }

    private suspend fun cleanupMigratedEntriesDetailed(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        sourceRoot: RootHandle,
        targetsAlreadyVerified: Boolean = false,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): ManagedMigrationCleanupResult {
        return migrationFinalizer.cleanupMigratedEntriesDetailed(
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
        copiedEntries: List<CopiedMigrationEntry>,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): ManagedMigrationVerificationResult {
        return migrationFinalizer.verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries,
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
        snapshot.metadataEntriesByAudioName[audio.logicalName]
            ?: findMetadataByDirectLookup(context, audio)
    }

    private fun findMetadataForAudioBlocking(context: Context, audio: StoredEntry): StoredEntry? {
        val snapshot = resolveSnapshotForIndexedLookup(context)
        return snapshot?.metadataEntriesByAudioName?.get(audio.logicalName)
            ?: findMetadataByDirectLookup(context, audio)
    }

    internal fun metadataReferenceForAudio(audio: StoredEntry): String? {
        val reference = audio.reference.takeIf(String::isNotBlank) ?: return null
        if (audio.isPendingAudioWrite) return null
        return "$reference$METADATA_SUFFIX"
    }

    private fun findMetadataByDirectLookup(context: Context, audio: StoredEntry): StoredEntry? {
        val logicalAudioName = audio.logicalName
        val metadataName = "$logicalAudioName$METADATA_SUFFIX"
        val pendingMetadataName = "$logicalAudioName$PENDING_METADATA_SUFFIX"
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

    internal suspend fun prepareLegacyMetadataUpgrade(context: Context) = withContext(Dispatchers.IO) {
        invalidateSnapshotCache(context.applicationContext)
    }

    internal suspend fun saveMetadataForLegacyUpgrade(
        context: Context,
        audio: StoredEntry,
        json: String,
        expectedAbsent: Boolean,
        knownMetadataEntry: StoredEntry? = null
    ): Boolean = withContext(Dispatchers.IO) {
        saveMetadataBlocking(
            context = context,
            audio = audio,
            json = json,
            updateSnapshotCache = false,
            expectedAbsent = expectedAbsent,
            knownMetadataEntry = knownMetadataEntry
        )
    }

    internal suspend fun ensureManagedLibraryManifest(context: Context): String =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val root = resolveRootBlocking(appContext)
            ensureManagedLibraryManifestForRoot(appContext, root)
        }

    internal suspend fun writePendingAudioMetadata(
        context: Context,
        audioName: String,
        json: String,
        operationId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = resolveRootBlocking(appContext)
        ensureManagedLibraryManifestForRoot(appContext, root)
        val backendResult = writeTextThroughBackend(
            context = appContext,
            root = root,
            displayName = "$audioName$PENDING_METADATA_SUFFIX",
            content = json,
            temporaryWriteOwnerName = temporaryWriteOwnerNameForOperation(
                displayName = "$audioName$PENDING_METADATA_SUFFIX",
                operationId = operationId
            )
        )
        val written = when (backendResult) {
            is StorageWriteResult.Written -> backendResult.stat.toStoredEntryForBackend(
                fileRoot = (root as? RootHandle.FileRoot)?.dir
            )
            StorageWriteResult.Missing -> {
                NPLogger.w(TAG, "pending metadata target is missing; refusing raw writer fallback")
                null
            }
            else -> {
                NPLogger.w(TAG, "pending metadata typed write failed: $backendResult")
                null
            }
        }
        written != null
    }

    internal suspend fun promoteFinalizedPendingAudio(
        context: Context,
        audio: StoredEntry
    ): FinalizedPendingAudioPromotion? = withContext(Dispatchers.IO) {
        val metadataEntry = findMetadataForAudioBlocking(context, audio) ?: return@withContext null
        val rawMetadata = readTextInternal(context, metadataEntry.reference)
            ?: return@withContext null
        val metadata = rawMetadata
            .let(::parseDownloadedAudioMetadataJson)
            ?: return@withContext null
        if (!isFinalizedDownloadedMetadata(metadata)) {
            NPLogger.w(
                TAG,
                "拒绝提升未完成元信息收尾的 pending 音频: ${audio.logicalName}"
            )
            return@withContext null
        }
        val root = resolveRootBlocking(context)
        if (!audio.isPendingAudioWrite) {
            val terminalTemporaryWriteTargets =
                terminalTemporaryWriteCleanupTargetsForFinalization(
                    pendingAudio = audio,
                    metadata = metadata
                )
            val recordedTerminalCleanup = recordTerminalTemporaryWriteCleanup(
                context = context,
                root = root,
                targets = terminalTemporaryWriteTargets
            )
            if (!recordedTerminalCleanup) {
                NPLogger.w(
                    TAG,
                    "已发布音频未能持久化临时写入清理记录，拒绝重复最终发布: " +
                        "audio=${audio.logicalName}"
                )
                return@withContext null
            }
            return@withContext FinalizedPendingAudioPromotion(
                audio = audio,
                terminalTemporaryWriteCleanupRecorded = true
            )
        }
        val preparedMetadata = ensureTerminalTemporaryWriteFinalizationIdentity(
            context = context,
            root = root,
            metadataEntry = metadataEntry,
            metadata = metadata,
            rawMetadata = rawMetadata
        ) ?: run {
            NPLogger.w(
                TAG,
                "下载最终发布前未能持久化可验证身份，保留 pending 证据: " +
                    "audio=${audio.logicalName}"
            )
            return@withContext null
        }
        val terminalTemporaryWriteTargets =
            terminalTemporaryWriteCleanupTargetsForFinalization(
                pendingAudio = audio,
                metadata = preparedMetadata
            )
        val preparation = prepareTerminalTemporaryWriteFinalization(
            context = context,
            root = root,
            pendingAudio = audio,
            metadata = preparedMetadata,
            targets = terminalTemporaryWriteTargets
        ) ?: run {
            NPLogger.w(
                TAG,
                "下载最终发布前未能持久化临时写入准备记录，保留 pending 证据: " +
                    "audio=${audio.logicalName}"
            )
            return@withContext null
        }
        val promoted = promotePendingAudio(
            context = context,
            root = root,
            audio = audio
        )
        if (promoted != null && !updateSnapshotCacheAfterStoredEntryWrite(
                context,
                promoted,
                SnapshotEntryBucket.AUDIO
            )
        ) {
            invalidateSnapshotCache(context)
        }
        promoted?.let { finalizedAudio ->
            val recordedTerminalCleanup = completeTerminalTemporaryWriteFinalization(
                context = context,
                preparation = preparation
            )
            if (!recordedTerminalCleanup) {
                NPLogger.w(
                    TAG,
                    "下载音频已发布但临时写入清理仍处于准备态，等待恢复重试: " +
                        "audio=${audio.logicalName}"
                )
            }
            FinalizedPendingAudioPromotion(
                audio = finalizedAudio,
                terminalTemporaryWriteCleanupRecorded = recordedTerminalCleanup
            )
        }
    }

    /**
     * 将旧流程过早公开的普通音频退回 pending 名称，避免未完成标签写入的文件进入目录索引
     */
    internal suspend fun demotePublishedAudioForFinalization(
        context: Context,
        audio: StoredEntry,
        expectedMetadataFinalized: Boolean?
    ): StoredEntry? = withContext(Dispatchers.IO) {
        if (audio.isPendingAudioWrite) {
            return@withContext audio
        }
        val metadataEntry = findMetadataForAudioBlocking(context, audio) ?: return@withContext null
        val metadata = readTextInternal(context, metadataEntry.reference)
            ?.let(::parseDownloadedAudioMetadataJson)
            ?: return@withContext null
        if (metadata.downloadFinalized != expectedMetadataFinalized) {
            NPLogger.d(
                TAG,
                "跳过已变更状态的已发布音频回退: " +
                    "file=${audio.name}, expected=$expectedMetadataFinalized, " +
                    "actual=${metadata.downloadFinalized}"
            )
            return@withContext null
        }
        val pendingName = buildPendingAudioWriteName(audio.logicalName)
        val demoted = when (val root = resolveRootBlocking(context)) {
            is RootHandle.FileRoot -> {
                demotePublishedFileAudio(
                    root = root.dir,
                    publishedName = audio.name,
                    pendingName = pendingName
                )?.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                demotePublishedTreeAudio(
                    context = context,
                    root = root,
                    audio = audio,
                    pendingName = pendingName
                )
            }
        }
        if (demoted != null) {
            // pending 项不能增量写入可见目录快照，强制下次读取重新枚举
            invalidateSnapshotCache(context)
        }
        demoted
    }

    internal suspend fun deletePendingAudioMetadata(
        context: Context,
        audioName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedName = audioName.trim().takeIf(String::isNotBlank)
            ?: return@withContext false
        val root = resolveRootBlocking(context)
        deletePendingAudioMetadataBlocking(context, root, normalizedName)
    }

    /**
     * removes only a pre-commit pending pair that is proven to belong to one cancelled operation
     */
    internal suspend fun cleanupCancelledPendingDownloadArtifacts(
        context: Context,
        stableKey: String,
        operationId: String
    ): StartupRecoveryResult = cleanupCancelledPendingDownloadArtifacts(
        context = context,
        operations = listOf(CancelledPendingDownloadOperation(stableKey, operationId))
    )

    /**
     * resolves all operation-owned pending pairs from one complete root snapshot so clear-all
     * cleanup does not enumerate the same SAF directory once per song
     */
    internal suspend fun cleanupCancelledPendingDownloadArtifacts(
        context: Context,
        operations: Collection<CancelledPendingDownloadOperation>
    ): StartupRecoveryResult = withContext(Dispatchers.IO) {
        val normalizedOperations = operations.mapNotNull { operation ->
            val stableKey = operation.stableKey.trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val operationId = operation.operationId.trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            CancelledPendingDownloadOperation(stableKey, operationId)
        }.distinct()
        if (normalizedOperations.isEmpty()) {
            return@withContext StartupRecoveryResult()
        }
        try {
            val root = resolveRootBlocking(context)
            val refresh = treeDirectories.refreshRootEntries(context, root)
            if (!refresh.isComplete) {
                NPLogger.w(
                    TAG,
                    "取消清理跳过不完整下载目录枚举: operations=${normalizedOperations.size}"
                )
                return@withContext StartupRecoveryResult(
                    failedCount = normalizedOperations.size
                )
            }
            val rootEntries = refresh.entries.filterNot(StoredEntry::isDirectory)
            val parsedPendingMetadataEntries = rootEntries
                .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
                .mapNotNull { entry ->
                    val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                        ?: return@mapNotNull null
                    if (!ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, audioName)) {
                        return@mapNotNull null
                    }
                    parseDownloadedAudioMetadata(context, entry)
                        ?.let { metadata -> ManagedDownloadParsedMetadataEntry(entry, metadata) }
                }
            val referencesToDelete = normalizedOperations.flatMapTo(linkedSetOf<String>()) { operation ->
                ManagedDownloadPendingArtifactCleanupPlanner.planCancelledOperationReferences(
                    rootEntries = rootEntries,
                    parsedMetadataEntries = parsedPendingMetadataEntries,
                    stableKey = operation.stableKey,
                    operationId = operation.operationId
                )
            }
            if (referencesToDelete.isEmpty()) {
                return@withContext StartupRecoveryResult()
            }
            val entriesToDelete = rootEntries.filter { entry ->
                entry.reference in referencesToDelete
            }
            val terminalTemporaryWriteTargets =
                terminalTemporaryWriteCleanupTargets(
                    entries = entriesToDelete,
                    temporaryWriteIdentityByMetadataReference =
                        parsedPendingMetadataEntries.associate { parsed ->
                            parsed.entry.reference to terminalTemporaryWriteIdentity(
                                parsed.metadata
                            )
                        }
                )
            val recordedTerminalCleanup = terminalTemporaryWriteTargets.isEmpty() ||
                recordTerminalTemporaryWriteCleanup(
                    context = context,
                    root = root,
                    targets = terminalTemporaryWriteTargets
                )
            if (!recordedTerminalCleanup) {
                NPLogger.w(
                    TAG,
                    "取消下载未能持久化临时写入清理记录，保留 pending 证据: " +
                        "targets=${terminalTemporaryWriteTargets.size}"
                )
                return@withContext StartupRecoveryResult(
                    failedCount = terminalTemporaryWriteTargets.size
                )
            }
            val deletedReferences = deleteReferencesInternal(
                context = context,
                references = referencesToDelete,
                allowedRoot = root,
                trustedReferences = referencesToDelete,
                invalidateSnapshot = true
            )
            val temporaryCleanup = when {
                terminalTemporaryWriteTargets.isEmpty() -> StartupRecoveryResult()
                else -> cleanupPersistedTerminalTemporaryWriteArtifacts(context)
            }
            val failedCount =
                referencesToDelete.size - deletedReferences.size + temporaryCleanup.failedCount
            NPLogger.d(
                TAG,
                "取消下载 pending 半成品清理完成: operations=${normalizedOperations.size}, " +
                    "cleaned=${deletedReferences.size + temporaryCleanup.cleanedCount}, " +
                    "failed=$failedCount"
            )
            StartupRecoveryResult(
                cleanedCount = deletedReferences.size + temporaryCleanup.cleanedCount,
                failedCount = failedCount
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: SecurityException) {
            NPLogger.w(
                TAG,
                "取消下载 pending 半成品清理缺少权限，保留等待恢复: " +
                    "operations=${normalizedOperations.size}, error=${error.message}"
            )
            StartupRecoveryResult(failedCount = normalizedOperations.size)
        } catch (error: ManagedDownloadRootUnavailableException) {
            NPLogger.w(
                TAG,
                "取消下载 pending 半成品清理缺少目录，保留等待恢复: " +
                    "operations=${normalizedOperations.size}, error=${error.message}"
            )
            StartupRecoveryResult(failedCount = normalizedOperations.size)
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "取消下载 pending 半成品清理失败，保留等待恢复: " +
                    "operations=${normalizedOperations.size}, error=${error.message}",
                error
            )
            StartupRecoveryResult(failedCount = normalizedOperations.size)
        }
    }

    /**
     * replays every persisted terminal cleanup against the root captured at enqueue time
     */
    internal suspend fun cleanupPersistedTerminalTemporaryWriteArtifacts(
        context: Context
    ): StartupRecoveryResult = withContext(Dispatchers.IO) {
        val preparationRecovery = recoverPreparedTerminalTemporaryWriteFinalizations(context)
        val terminalRecovery = when (
            val snapshot = PersistentTerminalTemporaryWriteCleanupJournal.snapshot(context)
        ) {
            is TerminalTemporaryWriteCleanupJournalSnapshot.Unavailable -> {
                NPLogger.w(
                    TAG,
                    "终态临时写入清理记录不可读取，保留等待恢复: ${snapshot.reason}"
                )
                StartupRecoveryResult(failedCount = 1)
            }

            is TerminalTemporaryWriteCleanupJournalSnapshot.Available -> {
                var cleanedCount = 0
                var failedCount = 0
                snapshot.entries.forEach { entry ->
                    val root = resolveTerminalTemporaryWriteCleanupRoot(context, entry)
                    if (root == null) {
                        failedCount += entry.targetNames.size
                        NPLogger.w(
                            TAG,
                            "终态临时写入清理目录不可恢复，保留等待恢复: " +
                                "root=${entry.root.identity}, targets=${entry.targetNames.size}"
                        )
                        return@forEach
                    }
                    val recovery = try {
                        cleanupTerminalTemporaryWriteArtifactsBlocking(
                            context = context,
                            root = root,
                            targets = entry.targets
                        )
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: SecurityException) {
                        NPLogger.w(
                            TAG,
                            "终态临时写入清理缺少权限，保留等待恢复: ${error.message}"
                        )
                        StartupRecoveryResult(failedCount = entry.targetNames.size)
                    } catch (error: Exception) {
                        NPLogger.w(
                            TAG,
                            "终态临时写入清理失败，保留等待恢复: ${error.message}",
                            error
                        )
                        StartupRecoveryResult(failedCount = entry.targetNames.size)
                    }
                    cleanedCount += recovery.cleanedCount
                    failedCount += recovery.failedCount
                    if (recovery.failedCount == 0 &&
                        !PersistentTerminalTemporaryWriteCleanupJournal.consume(context, entry)
                    ) {
                        failedCount += entry.targetNames.size
                        NPLogger.w(
                            TAG,
                            "终态临时写入清理已完成但记录未确认消费，保留等待恢复: " +
                                "targets=${entry.targetNames.size}"
                        )
                    }
                }
                StartupRecoveryResult(
                    cleanedCount = cleanedCount,
                    failedCount = failedCount
                )
            }
        }
        StartupRecoveryResult(
            cleanedCount = preparationRecovery.cleanedCount + terminalRecovery.cleanedCount,
            failedCount = preparationRecovery.failedCount + terminalRecovery.failedCount
        )
    }

    private fun recordTerminalTemporaryWriteCleanup(
        context: Context,
        root: RootHandle,
        targets: Collection<TerminalTemporaryWriteCleanupTarget>
    ): Boolean {
        return PersistentTerminalTemporaryWriteCleanupJournal.enqueueTargets(
            context = context,
            root = terminalTemporaryWriteCleanupJournalRoot(root),
            targets = targets
        )
    }

    private fun prepareTerminalTemporaryWriteFinalization(
        context: Context,
        root: RootHandle,
        pendingAudio: StoredEntry,
        metadata: DownloadedAudioMetadata,
        targets: Collection<TerminalTemporaryWriteCleanupTarget>
    ): TerminalTemporaryWriteCleanupFinalizationPreparation? {
        return PersistentTerminalTemporaryWriteCleanupJournal.prepareFinalizationTargets(
            context = context,
            root = terminalTemporaryWriteCleanupJournalRoot(root),
            pendingAudioName = pendingAudio.name,
            finalAudioName = pendingAudio.logicalName,
            expectedOperationId = metadata.operationId,
            targets = targets,
            expectedFinalizationToken = metadata.terminalTemporaryWriteCleanupToken
        )
    }

    private suspend fun ensureTerminalTemporaryWriteFinalizationIdentity(
        context: Context,
        root: RootHandle,
        metadataEntry: StoredEntry,
        metadata: DownloadedAudioMetadata,
        rawMetadata: String
    ): DownloadedAudioMetadata? {
        if (
            metadata.operationId?.trim()?.isNotEmpty() == true ||
                metadata.terminalTemporaryWriteCleanupToken?.trim()?.isNotEmpty() == true
        ) {
            return metadata
        }
        val token = UUID.randomUUID().toString()
        val json = runCatching {
            JSONObject(rawMetadata)
                .put("terminalTemporaryWriteCleanupToken", token)
                .toString()
        }.getOrNull() ?: return null
        val updatedMetadata = parseDownloadedAudioMetadataJson(json) ?: return null
        val backendResult = writeTextThroughBackend(
            context = context,
            root = root,
            displayName = metadataEntry.name,
            content = json,
            temporaryWriteOwnerName = temporaryWriteOwnerNameForIdentity(
                displayName = metadataEntry.name,
                identity = updatedMetadata.terminalTemporaryWriteCleanupToken
            )
        )
        val writtenMetadata = when (backendResult) {
            is StorageWriteResult.Written -> backendResult.stat.toStoredEntryForBackend(
                fileRoot = (root as? RootHandle.FileRoot)?.dir
            )

            else -> null
        }
        if (writtenMetadata == null) {
            invalidateSnapshotCache(context)
            return null
        }
        val storedMetadata = readTextInternal(context, writtenMetadata.reference)
            ?.let(::parseDownloadedAudioMetadataJson)
        if (!isMetadataWriteVerified(expected = updatedMetadata, actual = storedMetadata)) {
            invalidateSnapshotCache(context)
            NPLogger.w(
                TAG,
                "最终发布身份写入读回校验失败，保留 pending 证据: ${metadataEntry.name}"
            )
            return null
        }
        invalidateSnapshotCache(context)
        return updatedMetadata
    }

    private fun completeTerminalTemporaryWriteFinalization(
        context: Context,
        preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
    ): Boolean {
        return PersistentTerminalTemporaryWriteCleanupJournal.completeFinalization(
            context = context,
            preparation = preparation
        )
    }

    private fun recoverPreparedTerminalTemporaryWriteFinalizations(
        context: Context
    ): StartupRecoveryResult {
        return try {
            when (
                val snapshot = PersistentTerminalTemporaryWriteCleanupJournal.preparationSnapshot(
                    context
                )
            ) {
                is TerminalTemporaryWriteCleanupPreparationSnapshot.Unavailable -> {
                    NPLogger.w(
                        TAG,
                        "最终发布准备记录不可读取，保留等待恢复: ${snapshot.reason}"
                    )
                    StartupRecoveryResult(failedCount = 1)
                }

                is TerminalTemporaryWriteCleanupPreparationSnapshot.Available -> {
                    var failedCount = 0
                    snapshot.entries.forEach { preparation ->
                        val root = resolveTerminalTemporaryWriteCleanupRoot(context, preparation)
                        if (root == null) {
                            failedCount += preparation.targetNames.size
                            NPLogger.w(
                                TAG,
                                "最终发布准备目录不可恢复，保留等待恢复: " +
                                    "root=${preparation.root.identity}, " +
                                    "targets=${preparation.targetNames.size}"
                            )
                            return@forEach
                        }
                        val refresh = treeDirectories.refreshRootEntries(context, root)
                        if (!refresh.isComplete) {
                            failedCount += preparation.targetNames.size
                            NPLogger.w(
                                TAG,
                                "最终发布准备恢复跳过不完整目录枚举: " +
                                    "targets=${preparation.targetNames.size}"
                            )
                            return@forEach
                        }
                        val rootEntries = refresh.entries.filterNot(StoredEntry::isDirectory)
                        if (!isPreparedTerminalTemporaryWriteFinalizationReady(
                                context = context,
                                preparation = preparation,
                                rootEntries = rootEntries
                            )
                        ) {
                            return@forEach
                        }
                        if (!completeTerminalTemporaryWriteFinalization(context, preparation)) {
                            failedCount += preparation.targetNames.size
                            NPLogger.w(
                                TAG,
                                "最终发布准备未能转换为终态清理记录，保留等待恢复: " +
                                    "audio=${preparation.finalAudioName}"
                            )
                        }
                    }
                    StartupRecoveryResult(failedCount = failedCount)
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: SecurityException) {
            NPLogger.w(TAG, "最终发布准备恢复缺少权限，保留等待恢复: ${error.message}")
            StartupRecoveryResult(failedCount = 1)
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "最终发布准备恢复失败，保留等待恢复: ${error.message}",
                error
            )
            StartupRecoveryResult(failedCount = 1)
        }
    }

    private fun isPreparedTerminalTemporaryWriteFinalizationReady(
        context: Context,
        preparation: TerminalTemporaryWriteCleanupFinalizationPreparation,
        rootEntries: List<StoredEntry>
    ): Boolean {
        val finalAudioExists = rootEntries.any { entry ->
            !entry.isPendingAudioWrite && entry.name == preparation.finalAudioName
        }
        if (!finalAudioExists) {
            return false
        }
        if (rootEntries.any { entry ->
                entry.isPendingAudioWrite && entry.logicalName == preparation.finalAudioName
            }
        ) {
            return false
        }
        return rootEntries.asSequence()
            .filter { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name) ==
                    preparation.finalAudioName
            }
            .mapNotNull { entry -> parseDownloadedAudioMetadata(context, entry) }
            .any { metadata ->
                isFinalizedDownloadedMetadata(metadata) &&
                    matchesTerminalTemporaryWriteFinalizationIdentity(
                        metadata = metadata,
                        preparation = preparation
                    ) &&
                    metadata.audioFileName == preparation.finalAudioName
            }
    }

    internal fun matchesTerminalTemporaryWriteFinalizationIdentity(
        metadata: DownloadedAudioMetadata,
        preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
    ): Boolean {
        val expectedOperationId = preparation.expectedOperationId
        val expectedFinalizationToken = preparation.expectedFinalizationToken
        return (
            expectedOperationId != null && metadata.operationId == expectedOperationId
        ) || (
            expectedFinalizationToken != null &&
                metadata.terminalTemporaryWriteCleanupToken == expectedFinalizationToken
        )
    }

    private fun terminalTemporaryWriteCleanupJournalRoot(
        root: RootHandle
    ): TerminalTemporaryWriteCleanupRoot {
        return when (root) {
            is RootHandle.FileRoot -> TerminalTemporaryWriteCleanupRoot(
                type = TerminalTemporaryWriteCleanupRootType.FILE,
                identity = root.dir.absolutePath
            )

            is RootHandle.TreeRoot -> TerminalTemporaryWriteCleanupRoot(
                type = TerminalTemporaryWriteCleanupRootType.TREE,
                identity = root.tree.uri.toString()
            )
        }
    }

    private fun resolveTerminalTemporaryWriteCleanupRoot(
        context: Context,
        entry: TerminalTemporaryWriteCleanupJournalEntry
    ): RootHandle? {
        return when (entry.root.type) {
            TerminalTemporaryWriteCleanupRootType.FILE -> {
                File(entry.root.identity)
                    .takeIf(File::isAbsolute)
                    ?.let(RootHandle::FileRoot)
            }

            TerminalTemporaryWriteCleanupRootType.TREE -> {
                val treeUri = entry.root.identity.toUri()
                if (treeUri.scheme != "content") {
                    return null
                }
                DocumentFile.fromTreeUri(context, treeUri)?.let(RootHandle::TreeRoot)
            }
        }
    }

    private fun resolveTerminalTemporaryWriteCleanupRoot(
        context: Context,
        preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
    ): RootHandle? {
        return resolveTerminalTemporaryWriteCleanupRoot(
            context = context,
            root = preparation.root
        )
    }

    private fun resolveTerminalTemporaryWriteCleanupRoot(
        context: Context,
        root: TerminalTemporaryWriteCleanupRoot
    ): RootHandle? {
        return when (root.type) {
            TerminalTemporaryWriteCleanupRootType.FILE -> {
                File(root.identity)
                    .takeIf(File::isAbsolute)
                    ?.let(RootHandle::FileRoot)
            }

            TerminalTemporaryWriteCleanupRootType.TREE -> {
                val treeUri = root.identity.toUri()
                if (treeUri.scheme != "content") {
                    return null
                }
                DocumentFile.fromTreeUri(context, treeUri)?.let(RootHandle::TreeRoot)
            }
        }
    }

    private suspend fun cleanupTerminalTemporaryWriteArtifactsBlocking(
        context: Context,
        root: RootHandle,
        targets: Collection<TerminalTemporaryWriteCleanupTarget>
    ): StartupRecoveryResult {
        val normalizedTargets = targets
            .mapNotNull(::normalizeTerminalTemporaryWriteCleanupTarget)
            .distinct()
        if (normalizedTargets.isEmpty()) {
            return StartupRecoveryResult()
        }
        val backend: StorageBackend
        val targetForCleanupTarget: (TerminalTemporaryWriteCleanupTarget) -> StorageTarget
        when (root) {
            is RootHandle.FileRoot -> {
                backend = FileStorageBackend(root.dir)
                targetForCleanupTarget = { target ->
                    StorageTarget.FileTarget(
                        logicalPath = target.displayName,
                        temporaryWriteOwnerName = target.temporaryWriteOwnerName
                    )
                }
            }

            is RootHandle.TreeRoot -> {
                backend = SafStorageBackend(context)
                targetForCleanupTarget = { target ->
                    StorageTarget.SafTarget(
                        parent = StorageReference.SafRef(root.tree.uri),
                        displayName = target.displayName,
                        mimeType = "application/octet-stream",
                        temporaryWriteOwnerName = target.temporaryWriteOwnerName
                    )
                }
            }
        }
        var cleanedCount = 0
        var failedCount = 0
        when (
            val result = backend.cleanupTerminalTemporaryWrites(
                normalizedTargets.map(targetForCleanupTarget)
            )
        ) {
            is ManagedTemporaryWriteCleanupResult.Completed -> {
                cleanedCount += result.deletedCount
                failedCount += terminalTemporaryWriteCleanupFailureCount(
                    result = result,
                    targetCount = normalizedTargets.size
                )
                if (result.retainedActiveCount > 0) {
                    NPLogger.d(
                        TAG,
                        "终态临时写入清理跳过活跃写入: targets=${normalizedTargets.size}, " +
                            "count=${result.retainedActiveCount}"
                    )
                }
                if (result.failures.isNotEmpty()) {
                    NPLogger.w(
                        TAG,
                        "终态临时写入未完全清理: targets=${normalizedTargets.size}, " +
                            "failed=${result.failures.size}"
                    )
                }
            }

            is ManagedTemporaryWriteCleanupResult.Skipped -> {
                failedCount += terminalTemporaryWriteCleanupFailureCount(
                    result = result,
                    targetCount = normalizedTargets.size
                )
                NPLogger.w(
                    TAG,
                    "终态临时写入清理跳过非完整目录枚举: " +
                        "targets=${normalizedTargets.size}, reason=${result.reason}"
                )
            }
        }
        if (cleanedCount > 0) {
            invalidateSnapshotCache(context)
        }
        return StartupRecoveryResult(
            cleanedCount = cleanedCount,
            failedCount = failedCount
        )
    }

    internal fun terminalTemporaryWriteCleanupTargets(
        entries: Collection<StoredEntry>,
        temporaryWriteIdentityByMetadataReference: Map<String, String?> = emptyMap()
    ): List<TerminalTemporaryWriteCleanupTarget> {
        return entries.flatMap { entry ->
            when {
                entry.isPendingAudioWrite -> {
                    listOf(
                        TerminalTemporaryWriteCleanupTarget(displayName = entry.name),
                        TerminalTemporaryWriteCleanupTarget(displayName = entry.logicalName)
                    )
                }

                else -> {
                    val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                    if (
                        audioName != null &&
                        ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, audioName)
                    ) {
                        listOfNotNull(
                            TerminalTemporaryWriteCleanupTarget(displayName = audioName),
                            TerminalTemporaryWriteCleanupTarget(displayName = entry.name),
                            temporaryWriteOwnerNameForIdentity(
                                displayName = entry.name,
                                identity = temporaryWriteIdentityByMetadataReference[entry.reference]
                            )?.let { temporaryWriteOwnerName ->
                                TerminalTemporaryWriteCleanupTarget(
                                    displayName = entry.name,
                                    temporaryWriteOwnerName = temporaryWriteOwnerName
                                )
                            }
                        )
                    } else {
                        emptyList()
                    }
                }
            }
        }.mapNotNull(::normalizeTerminalTemporaryWriteCleanupTarget)
            .distinct()
    }

    private fun terminalTemporaryWriteCleanupTargetsForFinalization(
        pendingAudio: StoredEntry,
        metadata: DownloadedAudioMetadata
    ): List<TerminalTemporaryWriteCleanupTarget> {
        val audioName = pendingAudio.logicalName
        val pendingMetadataName = "$audioName$PENDING_METADATA_SUFFIX"
        return buildList {
            if (pendingAudio.isPendingAudioWrite) {
                add(TerminalTemporaryWriteCleanupTarget(displayName = pendingAudio.name))
            }
            add(TerminalTemporaryWriteCleanupTarget(displayName = audioName))
            add(TerminalTemporaryWriteCleanupTarget(displayName = "$audioName$METADATA_SUFFIX"))
            add(TerminalTemporaryWriteCleanupTarget(displayName = pendingMetadataName))
            temporaryWriteOwnerNameForIdentity(
                displayName = pendingMetadataName,
                identity = terminalTemporaryWriteIdentity(metadata)
            )?.let { temporaryWriteOwnerName ->
                add(
                    TerminalTemporaryWriteCleanupTarget(
                        displayName = pendingMetadataName,
                        temporaryWriteOwnerName = temporaryWriteOwnerName
                    )
                )
            }
        }.mapNotNull(::normalizeTerminalTemporaryWriteCleanupTarget)
            .distinct()
    }

    internal fun temporaryWriteOwnerNameForOperation(
        displayName: String,
        operationId: String?
    ): String? = temporaryWriteOwnerNameForIdentity(
        displayName = displayName,
        identity = operationId
    )

    private fun temporaryWriteOwnerNameForIdentity(
        displayName: String,
        identity: String?
    ): String? {
        val normalizedDisplayName = normalizeTerminalTemporaryWriteTargetName(displayName)
            ?: return null
        val normalizedIdentity = identity?.trim()?.takeIf(String::isNotBlank) ?: return null
        return "$normalizedDisplayName\u0000$normalizedIdentity"
    }

    private fun terminalTemporaryWriteIdentity(metadata: DownloadedAudioMetadata): String? {
        return metadata.operationId?.trim()?.takeIf(String::isNotBlank)
            ?: metadata.terminalTemporaryWriteCleanupToken
                ?.trim()
                ?.takeIf(String::isNotBlank)
    }

    private fun normalizeTerminalTemporaryWriteCleanupTarget(
        target: TerminalTemporaryWriteCleanupTarget
    ): TerminalTemporaryWriteCleanupTarget? {
        val displayName = normalizeTerminalTemporaryWriteTargetName(target.displayName) ?: return null
        return target.copy(
            displayName = displayName,
            temporaryWriteOwnerName = target.temporaryWriteOwnerName
                ?.trim()
                ?.takeIf(String::isNotBlank)
        )
    }

    internal fun terminalTemporaryWriteTargetNames(
        entries: Collection<StoredEntry>
    ): List<String> {
        return terminalTemporaryWriteCleanupTargets(entries)
            .map(TerminalTemporaryWriteCleanupTarget::displayName)
            .distinct()
    }

    internal fun terminalTemporaryWriteCleanupFailureCount(
        result: ManagedTemporaryWriteCleanupResult,
        targetCount: Int
    ): Int {
        val normalizedTargetCount = targetCount.coerceAtLeast(1)
        return when (result) {
            is ManagedTemporaryWriteCleanupResult.Completed -> {
                result.failures.size + result.retainedActiveCount
            }

            is ManagedTemporaryWriteCleanupResult.Skipped -> normalizedTargetCount
        }
    }

    private fun normalizeTerminalTemporaryWriteTargetName(rawName: String): String? {
        val name = rawName.trim().takeIf(String::isNotBlank) ?: return null
        if (name == "." || name == ".." || '/' in name || '\\' in name) {
            return null
        }
        return name
    }

    internal fun pendingMetadataEntryNames(
        audioName: String,
        candidateNames: Collection<String>
    ): List<String> {
        return candidateNames
            .filter { name -> ManagedDownloadTreeNaming.isPendingMetadataName(name, audioName) }
            .distinct()
            .sorted()
    }

    private fun saveMetadataBlocking(
        context: Context,
        audio: StoredEntry,
        json: String,
        updateSnapshotCache: Boolean = true,
        expectedAbsent: Boolean = false,
        knownMetadataEntry: StoredEntry? = null
    ): Boolean {
        val metadata = parseDownloadedAudioMetadataJson(json)
        if (metadata == null) {
            invalidateSnapshotCache(context)
            return false
        }
        val metadataEntry = writeRootText(
            context = context,
            root = resolveRootBlocking(context),
            displayName = "${audio.logicalName}$METADATA_SUFFIX",
            content = json,
            expectedAbsent = expectedAbsent,
            knownTargetEntry = knownMetadataEntry
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
        if (
            updateSnapshotCache &&
            !updateSnapshotCacheAfterMetadataWrite(context, metadataEntry, metadata)
        ) {
            invalidateSnapshotCache(context)
        }
        return true
    }

    private fun ensureManagedLibraryManifestBlocking(
        context: Context,
        root: RootHandle
    ): String {
        readManagedLibraryIdBlocking(context, root)?.let { existing ->
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
            content = payload
        )
        if (written == null) {
            throw IOException("无法写入 Managed SAF root manifest")
        }
        return libraryId
    }

    private fun readManagedLibraryIdBlocking(
        context: Context,
        root: RootHandle
    ): String? {
        return findManagedLibraryManifestEntry(
            context = context,
            root = root
        )
            ?.let { entry -> readTextInternal(context, entry.reference) }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.optString("libraryId")
            ?.takeIf(String::isNotBlank)
    }

    private fun findManagedLibraryManifestEntry(
        context: Context,
        root: RootHandle
    ): StoredEntry? {
        return when (root) {
            is RootHandle.FileRoot -> {
                File(root.dir, MANAGED_LIBRARY_MANIFEST_FILE_NAME)
                    .takeIf { it.isFile }
                    ?.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                treeChildRegistry.cachedTreeChild(
                    context = context,
                    parent = root.tree,
                    childName = MANAGED_LIBRARY_MANIFEST_FILE_NAME,
                    maxCacheAgeMs = 0L
                )?.toStoredEntry()
            }
        }
    }

    private fun deletePendingAudioMetadataBlocking(
        context: Context,
        root: RootHandle,
        audioName: String
    ): Boolean {
        val entries = when (root) {
            is RootHandle.FileRoot -> {
                root.dir.listFiles()?.map(ManagedDownloadStoredEntryMapper::fromFile)
                    ?: return false
            }
            is RootHandle.TreeRoot -> {
                val refresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                    context = context,
                    parent = root.tree
                )
                if (!refresh.isComplete) {
                    return false
                }
                refresh.children.map(ManagedDownloadStoredEntryMapper::fromTreeChild)
            }
        }
        val pendingNames = pendingMetadataEntryNames(
            audioName = audioName,
            candidateNames = entries.filterNot(StoredEntry::isDirectory).map(StoredEntry::name)
        ).toSet()
        val pendingEntries = entries.filter { entry -> entry.name in pendingNames }
        if (pendingEntries.isEmpty()) {
            return true
        }
        val references = pendingEntries.mapTo(linkedSetOf(), StoredEntry::reference)
        val deletedReferences = deleteReferencesInternal(
            context = context,
            references = references,
            allowedRoot = root,
            trustedReferences = references.toSet(),
            invalidateSnapshot = false
        )
        if (deletedReferences.isNotEmpty()) {
            forgetDeletedReferencesFromCaches(deletedReferences)
            invalidateSnapshotCache(context)
        }
        return deletedReferences.containsAll(references)
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
    internal suspend fun probeStorageRoot(
        context: Context
    ): ManagedDownloadRootProbeResult = withContext(Dispatchers.IO) {
        try {
            val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
            if (configuredUri.isNullOrBlank()) {
                ManagedDownloadRootProbeResult.Accessible
            } else {
                rootResolver.probeTreeRoot(context, configuredUri)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ManagedDownloadRootProviderException) {
            ManagedDownloadRootProbeResult.ProviderFailure(error)
        } catch (error: Exception) {
            ManagedDownloadRootProbeResult.ProviderFailure(
                ManagedDownloadRootProviderException(
                    reference = "configured-root",
                    cause = error
                )
            )
        }
    }

    suspend fun isStorageRootResolvable(context: Context): Boolean {
        return probeStorageRoot(context) is ManagedDownloadRootProbeResult.Accessible
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

    suspend fun hasReadableContent(
        context: Context,
        entry: StoredEntry
    ): Boolean = withContext(Dispatchers.IO) {
        if (entry.isDirectory) {
            return@withContext false
        }
        if (entry.sizeBytes > 0L) {
            return@withContext inspectStorageReference(
                context,
                entry.reference
            ) == ManagedDownloadReferenceIo.AccessResult.Accessible
        }
        try {
            when (
                val result = readStoredEntryForMigration(context, entry) { input ->
                    input.read() != -1
                }
            ) {
                is StorageLookupResult.Found -> result.value.getOrThrow()
                else -> false
            }
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 兼容旧业务调用，真正的删除只在策略验证后进入 typed 执行器
     */
    suspend fun deleteReferences(context: Context, references: Collection<String?>): Set<String> =
        withContext(Dispatchers.IO) {
            batchReferenceDeleteMutex.withLock {
                val deletePolicy = buildManagedDeletePolicy(context)
                deleteReferencesInternalConcurrently(
                    context = context,
                    references = resolveTrustedManagedReferences(references, deletePolicy),
                    deletePolicy = deletePolicy,
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
        val boundedFileName = boundManagedDownloadFileName(fileName)
        val storedEntry = when (val root = resolveRootBlocking(context)) {
            is RootHandle.FileRoot -> {
                val existingAudio = findExistingAudioForSeedStableKey(context, seedMetadataJson)
                val reservedFinalName = existingAudio == null
                val finalName = existingAudio?.name
                    ?: treeChildRegistry.reserveUniqueFileChildName(root.dir, boundedFileName)
                val pendingName = buildPendingAudioWriteName(finalName)
                val pendingTarget = File(root.dir, pendingName)
                val audioEntry = try {
                    val writeResult = runBlocking(Dispatchers.IO) {
                        FileStorageBackend(root.dir).writeRecoverable(
                            target = StorageTarget.FileTarget(pendingName)
                        ) { output ->
                            tempFile.inputStream().use { input ->
                                input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                            }
                        }
                    }
                    val stored = when (writeResult) {
                        is StorageWriteResult.Written -> {
                            writeResult.stat.toStoredEntryForBackend(root.dir)
                        }
                        StorageWriteResult.Missing -> throw IOException(
                            "pending 音频写入目标不存在: $pendingName"
                        )
                        StorageWriteResult.OutOfScope -> throw IOException(
                            "pending 音频写入目标越界: $pendingName"
                        )
                        StorageWriteResult.PermissionLost -> throw SecurityException(
                            "pending 音频写入权限丢失: $pendingName"
                        )
                        is StorageWriteResult.ProviderFailure -> throw IOException(
                            "pending 音频写入失败: $pendingName",
                            writeResult.error
                        )
                        is StorageWriteResult.Unsupported -> throw IOException(
                            "pending 音频不支持写入: $pendingName (${writeResult.operation})"
                        )
                    }
                    verifyFileCommittedLength(
                        target = pendingTarget,
                        expectedSizeBytes = actualSizeBytes,
                        description = pendingTarget.name
                    )
                    stored.copy(sizeBytes = actualSizeBytes)
                } catch (error: Throwable) {
                    deletePendingFileAndConfirm(pendingTarget)?.let { cleanupError ->
                        error.addSuppressed(
                            IOException(
                                "pending 音频写入失败后清理未确认: $pendingName",
                                cleanupError
                            )
                        )
                    }
                    if (reservedFinalName) {
                        treeChildRegistry.forgetFileChildName(root.dir, finalName)
                    }
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
                val reservedFinalName = existingAudio == null
                val finalName = existingAudio?.name
                    ?: treeChildRegistry.reserveUniqueTreeChildName(context, root.tree, boundedFileName)
                val createdPendingName = buildPendingAudioWriteName(finalName)
                val audioEntry = try {
                    val entry = writeSafFileThroughBackend(
                        context = context,
                        parent = root.tree,
                        displayName = createdPendingName,
                        mimeType = mimeTypeFromName(finalName, mimeType),
                        expectedSizeBytes = actualSizeBytes,
                        sourceFile = tempFile
                    )
                    treeChildRegistry.rememberTreeChild(root.tree, entry)
                    entry
                } catch (error: Throwable) {
                    treeChildRegistry.forgetTreeChildName(
                        root.tree,
                        createdPendingName
                    )
                    if (reservedFinalName) {
                        treeChildRegistry.forgetTreeChildName(root.tree, finalName)
                    }
                    throw error
                }
                writeSeedMetadataAfterAudioCommit(
                    context = context,
                    root = root,
                    audioName = audioEntry.logicalName,
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

    private fun promoteFileTargetWithoutReplacement(
        pending: File,
        target: File,
        displayName: String
    ) {
        try {
            Files.move(
                pending.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(pending.toPath(), target.toPath())
            } catch (error: FileAlreadyExistsException) {
                throw IOException("下载目标已存在，保留 pending 文件: $displayName", error)
            } catch (error: Exception) {
                throw IOException("无法提交下载文件: $displayName", error)
            }
        } catch (error: FileAlreadyExistsException) {
            throw IOException("下载目标已存在，保留 pending 文件: $displayName", error)
        } catch (error: Exception) {
            throw IOException("无法提交下载文件: $displayName", error)
        }
    }

    private fun deletePendingFileAndConfirm(pending: File): Throwable? {
        return try {
            when {
                !pending.exists() -> null
                !pending.isFile -> IllegalStateException(
                    "pending 音频不是普通文件: ${pending.name}"
                )
                !pending.delete() && pending.exists() -> IllegalStateException(
                    "pending 音频删除未确认: ${pending.name}"
                )
                pending.exists() -> IllegalStateException(
                    "pending 音频删除后仍存在: ${pending.name}"
                )
                else -> null
            }
        } catch (error: Throwable) {
            error
        }
    }

    internal suspend fun promotePendingFileAudio(
        root: File,
        pendingName: String,
        finalName: String
    ): File? {
        val target = File(root, finalName)
        return FileStorageMutationLocks.withTargetLock(target) {
            val pending = File(root, pendingName)
                .takeIf { it.isFile }
                ?: return@withTargetLock null
            when {
                target.isFile && target.length() == pending.length() -> {
                    deletePendingFileAndConfirm(pending)?.let { cleanupError ->
                        throw IOException(
                            "下载目标已存在但重复 pending 清理未确认: $finalName",
                            cleanupError
                        )
                    }
                }
                target.isFile -> throw IOException(
                    "下载目标已存在且大小不一致，保留 pending 文件: $finalName"
                )
                target.exists() -> throw IOException(
                    "下载目标不是普通文件，保留 pending 文件: $finalName"
                )
                else -> promoteFileTargetWithoutReplacement(pending, target, finalName)
            }
            target.takeIf { it.isFile && it.length() > 0L }
        }
    }

    internal suspend fun demotePublishedFileAudio(
        root: File,
        publishedName: String,
        pendingName: String
    ): File? {
        val published = File(root, publishedName)
        return FileStorageMutationLocks.withTargetLock(published) {
            val source = published.takeIf { it.isFile && it.length() > 0L }
                ?: return@withTargetLock null
            val sourceLength = source.length()
            val pending = File(root, pendingName)
            if (pending.exists() || pending == source) {
                return@withTargetLock null
            }
            promoteFileTargetWithoutReplacement(
                pending = source,
                target = pending,
                displayName = pendingName
            )
            pending.takeIf { it.isFile && it.length() == sourceLength }
        }
    }

    private fun writeSafFileThroughBackend(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long,
        sourceFile: File
    ): StoredEntry {
        val backend = SafStorageBackend(context)
        val result = runBlocking(Dispatchers.IO) {
            backend.writeRecoverable(
                target = StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(parent.uri),
                    displayName = displayName,
                    mimeType = mimeType
                )
            ) { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                }
            }
        }
        val stat = when (result) {
            is StorageWriteResult.Written -> result.stat
            StorageWriteResult.Missing -> throw IOException("SAF 目标不存在: $displayName")
            StorageWriteResult.OutOfScope -> throw IOException("SAF 目标越界: $displayName")
            StorageWriteResult.PermissionLost -> throw SecurityException("SAF 写入权限丢失: $displayName")
            is StorageWriteResult.ProviderFailure -> throw IOException(
                "SAF 写入失败: $displayName",
                result.error
            )
            is StorageWriteResult.Unsupported -> throw IOException(
                "SAF 不支持写入: $displayName (${result.operation})"
            )
        }
        val verifiedSize = stat.sizeBytes ?: runBlocking(Dispatchers.IO) {
            when (val measured = backend.read(stat.reference) { input ->
                ManagedDownloadCommitIo.countInputStreamBytes(
                    input,
                    STREAM_COPY_BUFFER_SIZE_BYTES
                )
            }) {
                is StorageLookupResult.Found -> measured.value
                StorageLookupResult.Missing -> throw IOException(
                    "SAF 写入目标在读回时不存在: $displayName"
                )
                StorageLookupResult.PermissionLost -> throw SecurityException(
                    "SAF 写入目标读回权限丢失: $displayName"
                )
                is StorageLookupResult.ProviderFailure -> throw IOException(
                    "SAF 写入目标读回失败: $displayName",
                    measured.error
                )
                StorageLookupResult.OutOfScope,
                is StorageLookupResult.Unsupported -> throw IOException(
                    "SAF 写入目标不可读: $displayName"
                )
            }
        }
        if (verifiedSize != expectedSizeBytes) {
            throw IOException(
                "SAF 写入大小不匹配: $displayName, expected=$expectedSizeBytes, " +
                    "actual=$verifiedSize"
            )
        }
        return stat.toStoredEntryForBackend(fileRoot = null)
            .copy(sizeBytes = verifiedSize)
    }

    private suspend fun promotePendingAudio(
        context: Context,
        root: RootHandle,
        audio: StoredEntry
    ): StoredEntry? {
        if (!audio.isPendingAudioWrite) return audio
        val finalName = audio.logicalName.takeIf(String::isNotBlank) ?: return null
        return when (root) {
            is RootHandle.FileRoot -> {
                promotePendingFileAudio(
                    root = root.dir,
                    pendingName = audio.name,
                    finalName = finalName
                )?.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val pendingUri = audio.reference.toUri()
                val pendingBackend = SafStorageBackend(context)
                val initialPendingReference = StorageReference.SafRef(pendingUri)
                val pendingStat = pendingBackend.stat(initialPendingReference)
                val pending = when (pendingStat) {
                    is StorageLookupResult.Found -> pendingStat.value
                        .takeUnless(StorageStat::isDirectory)
                        ?.let {
                            resolvePendingTreeDocument(
                                context = context,
                                parent = root.tree,
                                uri = pendingUri
                            )
                        }
                    StorageLookupResult.Missing -> null
                    StorageLookupResult.PermissionLost -> throw SecurityException(
                        "SAF pending 音频权限丢失: ${audio.name}"
                    )
                    is StorageLookupResult.ProviderFailure -> throw pendingStat.error
                    StorageLookupResult.OutOfScope,
                    is StorageLookupResult.Unsupported -> null
                }
                    ?: treeChildRegistry.cachedTreeChildren(
                        context = context,
                        parent = root.tree,
                        maxCacheAgeMs = 0L
                    ).firstOrNull { child ->
                        !child.isDirectory && child.name == audio.name
                    }?.let { child ->
                        treeChildRegistry.toDocumentFile(context, root.tree, child)
                    }
                    ?: return null
                val pendingReference = StorageReference.SafRef(pending.uri)
                val pendingSizeStat = if (pending.uri == pendingUri) {
                    pendingStat
                } else {
                    pendingBackend.stat(pendingReference)
                }
                val pendingReportedSizeBytes = (pendingSizeStat as? StorageLookupResult.Found)
                    ?.value
                    ?.sizeBytes
                val committedAtMs = System.currentTimeMillis()
                val expectedSizeBytes = resolveCurrentTreePendingAudioSize(
                    backend = pendingBackend,
                    reference = pendingReference,
                    reportedSizeBytes = pendingReportedSizeBytes,
                    description = audio.name
                )
                val treePending = treeChildRegistry.toTreeDocumentFile(
                    context = context,
                    parent = root.tree,
                    child = pending
                ) ?: treeChildRegistry.cachedTreeChildForWrite(
                    context = context,
                    parent = root.tree,
                    childName = audio.name
                )?.let { cachedChild ->
                    val cachedDocument = treeChildRegistry.toDocumentFile(
                        context = context,
                        parent = root.tree,
                        child = cachedChild
                    )
                    cachedDocument?.let { document ->
                        treeChildRegistry.toTreeDocumentFile(
                            context = context,
                            parent = root.tree,
                            child = document
                        )
                    }
                }?.takeIf { found ->
                    runCatching {
                        DocumentsContract.getDocumentId(found.uri) ==
                            DocumentsContract.getDocumentId(pending.uri)
                    }.getOrDefault(false)
                }
                val renamedDocument = renameTreeDocumentWithoutReplacing(
                    context = context,
                    parent = root.tree,
                    document = treePending,
                    finalName = finalName
                )
                if (renamedDocument != null) {
                    val renamedTarget = treeChildRegistry.toTreeDocumentFileOrEnumerated(
                        context = context,
                        parent = root.tree,
                        child = renamedDocument
                    ) ?: renamedDocument
                    return verifiedTreeStoredEntry(
                        context = context,
                        target = renamedTarget,
                        expectedName = finalName,
                        expectedSizeBytes = expectedSizeBytes,
                        fallbackLastModifiedMs = committedAtMs,
                        description = finalName
                    ).also {
                        treeChildRegistry.forgetTreeChildName(root.tree, audio.name)
                        treeChildRegistry.rememberTreeChild(root.tree, it)
                    }
                }
                copyPendingTreeAudioWithoutReplacing(
                    context = context,
                    root = root,
                    pending = treePending ?: pending,
                    pendingName = audio.name,
                    finalName = finalName,
                    expectedSizeBytes = expectedSizeBytes,
                    fallbackLastModifiedMs = committedAtMs
                )
            }
        }
    }

    internal fun resolvePendingTreeAudioPromotionExpectedSize(
        reportedSizeBytes: Long?,
        countedSizeBytes: Long?
    ): Long? {
        return countedSizeBytes?.takeIf { size -> size > 0L }
            ?: reportedSizeBytes?.takeIf { size -> size > 0L }
    }

    private suspend fun resolveCurrentTreePendingAudioSize(
        backend: SafStorageBackend,
        reference: StorageReference.SafRef,
        reportedSizeBytes: Long?,
        description: String
    ): Long {
        val countedSizeBytes = when (val read = backend.read(reference) { input ->
            ManagedDownloadCommitIo.countInputStreamBytes(
                input,
                STREAM_COPY_BUFFER_SIZE_BYTES
            )
        }) {
            is StorageLookupResult.Found -> read.value
            StorageLookupResult.Missing -> throw IOException(
                "SAF pending 音频在提升前不存在: $description"
            )
            StorageLookupResult.PermissionLost -> throw SecurityException(
                "SAF pending 音频提升前权限丢失: $description"
            )
            is StorageLookupResult.ProviderFailure -> throw IOException(
                "SAF pending 音频提升前读回失败: $description",
                read.error
            )
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> throw IOException(
                "SAF pending 音频提升前不可读: $description"
            )
        }
        val expectedSizeBytes = resolvePendingTreeAudioPromotionExpectedSize(
            reportedSizeBytes = reportedSizeBytes,
            countedSizeBytes = countedSizeBytes
        ) ?: throw IOException("SAF pending 音频提升前为空: $description")
        if (reportedSizeBytes != null && reportedSizeBytes != countedSizeBytes) {
            NPLogger.w(
                TAG,
                "SAF pending 音频报告大小与读回大小不一致，使用读回值: " +
                    "$description, reported=$reportedSizeBytes, counted=$countedSizeBytes"
            )
        }
        return expectedSizeBytes
    }

    private suspend fun demotePublishedTreeAudio(
        context: Context,
        root: RootHandle.TreeRoot,
        audio: StoredEntry,
        pendingName: String
    ): StoredEntry? {
        val sourceUri = runCatching { audio.reference.toUri() }.getOrNull() ?: return null
        val backend = SafStorageBackend(context)
        val sourceStat = backend.stat(StorageReference.SafRef(sourceUri))
        val source = when (sourceStat) {
            is StorageLookupResult.Found -> sourceStat.value
                .takeUnless(StorageStat::isDirectory)
                ?.let {
                    resolvePendingTreeDocument(
                        context = context,
                        parent = root.tree,
                        uri = sourceUri
                    )
                }
            StorageLookupResult.Missing -> null
            StorageLookupResult.PermissionLost -> throw SecurityException(
                "SAF 已发布音频权限丢失: ${audio.name}"
            )
            is StorageLookupResult.ProviderFailure -> throw sourceStat.error
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> null
        } ?: return null
        val expectedSizeBytes = (sourceStat as? StorageLookupResult.Found)
            ?.value
            ?.sizeBytes
            ?.takeIf { it > 0L }
            ?: audio.sizeBytes.takeIf { it > 0L }
            ?: return null
        val renamedDocument = renameTreeDocumentWithoutReplacing(
            context = context,
            parent = root.tree,
            document = source,
            finalName = pendingName
        ) ?: return null
        val renamedTarget = treeChildRegistry.toTreeDocumentFileOrEnumerated(
            context = context,
            parent = root.tree,
            child = renamedDocument
        ) ?: renamedDocument
        return verifiedTreeStoredEntry(
            context = context,
            target = renamedTarget,
            expectedName = pendingName,
            expectedSizeBytes = expectedSizeBytes,
            fallbackLastModifiedMs = audio.lastModifiedMs,
            description = pendingName
        ).also { demoted ->
            treeChildRegistry.forgetTreeChildName(root.tree, audio.name)
            treeChildRegistry.rememberTreeChild(root.tree, demoted)
        }
    }

    private suspend fun copyPendingTreeAudioWithoutReplacing(
        context: Context,
        root: RootHandle.TreeRoot,
        pending: DocumentFile,
        pendingName: String,
        finalName: String,
        expectedSizeBytes: Long,
        fallbackLastModifiedMs: Long
    ): StoredEntry? {
        val backend = SafStorageBackend(context)
        val copied = backend.read(StorageReference.SafRef(pending.uri)) { source ->
            ManagedDownloadTreeMutationLocks.withLock(root.tree.uri) {
                val beforeCreate = treeChildRegistry.treeChildrenForWrite(context, root.tree)
                if (!canCreateTreePromotionTargetWithoutReplacing(
                        enumerationComplete = beforeCreate.isComplete,
                        existingNames = beforeCreate.children.map(QueriedTreeChild::name),
                        targetName = finalName
                    )
                ) {
                    NPLogger.w(
                        TAG,
                        "SAF 提升目标不可安全创建，保留 pending 音频: $finalName"
                    )
                    return@withLock null
                }
                val existingDocumentUris = beforeCreate.children.map(QueriedTreeChild::documentUri)
                val createdUri = try {
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        root.tree.uri,
                        ManagedDownloadTreeNaming.documentCreateMimeType(
                            finalName,
                            mimeTypeFromName(finalName, null)
                        ),
                        finalName
                    )
                } catch (error: SecurityException) {
                    throw error
                } catch (error: UnsupportedOperationException) {
                    NPLogger.w(TAG, "SAF 不支持无覆写提升创建: $finalName", error)
                    return@withLock null
                } catch (error: Throwable) {
                    throw IOException("SAF 无覆写提升创建失败: $finalName", error)
                } ?: return@withLock null
                val created = resolveNewTreePromotionDocument(
                    context = context,
                    parent = root.tree,
                    uri = createdUri
                )
                if (created == null) {
                    if (existingDocumentUris.none { uri -> sameTreeDocument(uri, createdUri) }) {
                        discardNewTreePromotionTarget(context, root.tree, finalName, createdUri)
                    }
                    return@withLock null
                }
                if (existingDocumentUris.any { uri -> sameTreeDocument(uri, created.uri) }) {
                    NPLogger.w(TAG, "SAF 提升创建返回已有文件，保留 pending 音频: $finalName")
                    return@withLock null
                }
                if (created.isDirectory) {
                    discardNewTreePromotionTarget(context, root.tree, finalName, created.uri)
                    NPLogger.w(TAG, "SAF 提升创建了目录而非音频文件: $finalName")
                    return@withLock null
                }
                if (!ManagedDownloadTreeNaming.isExactTreeStoredName(created.name, finalName)) {
                    discardNewTreePromotionTarget(context, root.tree, created.name ?: finalName, created.uri)
                    NPLogger.w(
                        TAG,
                        "SAF 提升创建返回非目标名称，保留 pending 音频: " +
                            "expected=$finalName, actual=${created.name}"
                    )
                    return@withLock null
                }
                val afterCreate = treeChildRegistry.treeChildrenForWrite(context, root.tree)
                val exactTargets = afterCreate.children.filter { child ->
                    ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
                }
                if (
                    !afterCreate.isComplete ||
                        exactTargets.size != 1 ||
                        exactTargets.none { child -> sameTreeDocument(child.documentUri, created.uri) }
                ) {
                    discardNewTreePromotionTarget(context, root.tree, finalName, created.uri)
                    NPLogger.w(TAG, "SAF 提升创建后目标不唯一，保留 pending 音频: $finalName")
                    return@withLock null
                }
                try {
                    val output = context.contentResolver.openOutputStream(created.uri, "w")
                        ?: throw IOException("SAF final 音频不可写: $finalName")
                    output.use { target ->
                        source.copyTo(target, STREAM_COPY_BUFFER_SIZE_BYTES)
                    }
                    val entry = verifiedTreeStoredEntry(
                        context = context,
                        target = created,
                        expectedName = finalName,
                        expectedSizeBytes = expectedSizeBytes,
                        fallbackLastModifiedMs = fallbackLastModifiedMs,
                        description = finalName
                    )
                    val pendingDeleted = deleteTrustedReference(
                        context,
                        TrustedManagedRef(
                            reference = StorageReference.SafRef(pending.uri),
                            externalReference = pending.uri.toString()
                        )
                    ).isConfirmedStorageMutation()
                    if (pendingDeleted) {
                        treeChildRegistry.forgetTreeChildName(root.tree, pendingName)
                    } else {
                        NPLogger.w(TAG, "音频已提升但 pending 文件清理失败: $pendingName")
                    }
                    treeChildRegistry.rememberTreeChild(root.tree, entry)
                    entry
                } catch (error: Throwable) {
                    discardNewTreePromotionTarget(context, root.tree, finalName, created.uri)
                    throw error
                }
            }
        }
        return when (copied) {
            is StorageLookupResult.Found -> copied.value
            StorageLookupResult.Missing -> throw IOException(
                "SAF pending 音频在提升时不存在: $pendingName"
            )
            StorageLookupResult.PermissionLost -> throw SecurityException(
                "SAF pending 音频提升时权限丢失: $pendingName"
            )
            is StorageLookupResult.ProviderFailure -> {
                if (copied.error is SecurityException) {
                    throw copied.error
                }
                throw IOException("SAF pending 音频提升时读取失败: $pendingName", copied.error)
            }
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> throw IOException(
                "SAF pending 音频提升时不可读: $pendingName"
            )
        }
    }

    private fun discardNewTreePromotionTarget(
        context: Context,
        parent: DocumentFile,
        childName: String,
        uri: Uri
    ) {
        val deleted = deleteTrustedReference(
            context,
            TrustedManagedRef(
                reference = StorageReference.SafRef(uri),
                externalReference = uri.toString()
            )
        ).isConfirmedStorageMutation()
        if (!deleted) {
            NPLogger.w(TAG, "SAF 提升临时目标清理失败: $childName")
        }
        treeChildRegistry.forgetTreeChildName(parent, childName)
        invalidateSnapshotCache(context)
    }

    internal fun canCreateTreePromotionTargetWithoutReplacing(
        enumerationComplete: Boolean,
        existingNames: Collection<String>,
        targetName: String
    ): Boolean {
        if (!enumerationComplete) return false
        return existingNames.none { actualName ->
            ManagedDownloadTreeNaming.isExactTreeStoredName(actualName, targetName) ||
                isTreePromotionBackupName(actualName, targetName)
        }
    }

    private fun isTreePromotionBackupName(actualName: String, targetName: String): Boolean {
        val directBackupName = ".${targetName}.backup"
        if (actualName == directBackupName) return true
        val prefix = ".${targetName}."
        if (!actualName.startsWith(prefix) || !actualName.endsWith(".backup")) {
            return false
        }
        val identifier = actualName.removePrefix(prefix).removeSuffix(".backup")
        return runCatching { UUID.fromString(identifier) }.isSuccess
    }

    private fun sameTreeDocument(first: Uri, second: Uri): Boolean {
        if (first == second) return true
        val firstId = treeDocumentIdOrNull(first)
        val secondId = treeDocumentIdOrNull(second)
        return firstId != null && firstId == secondId
    }

    private fun treeDocumentIdOrNull(uri: Uri): String? {
        return try {
            DocumentsContract.getDocumentId(uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
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
                content = content
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
            subdirectory = COVER_SUBDIRECTORY,
            displayName = fileName,
            bytes = bytes,
            mimeType = mimeTypeFromName(fileName, mimeType)
        )?.reference
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
                exists = { lookupContext, reference ->
                    inspectStorageReference(lookupContext, reference) ==
                        ManagedDownloadReferenceIo.AccessResult.Accessible
                }
            )
            val refreshedDirectLyrics = if (hasCompleteLyricsSidecars(refreshedLyrics)) {
                refreshedLyrics
            } else {
                // 索引刷新可能被 provider 节流或并发扫描保守地保留旧值，当前歌曲仍需直读
                readLyricsBundleFromManagedRootFast(
                    context = context,
                    song = song
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
            song = song
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
                    song = song
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
            exists = { lookupContext, reference ->
                    inspectStorageReference(lookupContext, reference) ==
                    ManagedDownloadReferenceIo.AccessResult.Accessible
            }
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
            song = song
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
        song: SongItem
    ): DownloadedLyricsBundle {
        val startedAtNs = System.nanoTime()
        val configuredRoot = try {
            resolveRootBlocking(context)
        } catch (error: SecurityException) {
            throw error
        } catch (error: IllegalStateException) {
            // 配置了 SAF 根目录时不能静默回退到其他根目录
            if (error is ManagedDownloadRootUnavailableException) {
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
        val metadata = readDownloadedMetadataFast(
            context = context,
            root = root,
            song = song,
            audioName = audioName
        )
        val referenced = resolveLyricsBundleFromReferences(
            metadata = metadata,
            // 当前根 sidecar 由 directFiles 精确匹配，旧 URI 不能作为 provider 访问证据
            originalReference = null,
            translatedReference = null,
            romanizedReference = null,
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
                    ?.toStoredEntry()
            }
            is RootHandle.TreeRoot -> {
                findTreeSiblingByNameFast(
                    context = context,
                    root = root,
                    song = song,
                    childName = metadataName,
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
        nameMatches: (String) -> Boolean = { actualName ->
            actualName.equals(childName, ignoreCase = true)
        }
    ): StoredEntry? {
        val parent = findTreeParentDocumentFast(context, root, song) ?: return null
        val children = try {
            treeChildRegistry.cachedTreeChildren(
                context = context,
                parent = parent,
                maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
            )
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
            allowMetadataLessAudio = allowMetadataLessAudio
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
            val root = resolveRootBlocking(context)
            val refresh = treeDirectories.refreshRootEntries(context, root)
            if (!refresh.isComplete) {
                NPLogger.w(TAG, "下载目录枚举不完整，跳过待提交音频清理")
                return StartupRecoveryResult()
            }
            val rootEntries = refresh.entries.filterNot(StoredEntry::isDirectory)
            val metadataNames = rootEntries.mapTo(linkedSetOf(), StoredEntry::name)
            val referencesToDelete = rootEntries
                .filter { entry -> pendingAudioWriteNames.isPendingAudioWriteName(entry.name) }
                .filterNot { entry ->
                    val logicalName = pendingAudioWriteNames.logicalAudioName(entry.name)
                    metadataNames.any { candidate ->
                        candidate == "$logicalName$METADATA_SUFFIX" ||
                            ManagedDownloadTreeNaming.isPendingMetadataName(
                                actualName = candidate,
                                audioName = logicalName
                            )
                    }
                }
                .mapTo(linkedSetOf(), StoredEntry::reference)
            if (referencesToDelete.isEmpty()) {
                return StartupRecoveryResult()
            }
            val deletedReferences = deleteReferencesInternal(
                context = context,
                references = referencesToDelete,
                allowedRoot = root,
                trustedReferences = referencesToDelete,
                invalidateSnapshot = true
            )
            val failedCount = referencesToDelete.size - deletedReferences.size
            NPLogger.d(
                TAG,
                "清理下载提交残留完成: cleaned=${deletedReferences.size}, failed=$failedCount"
            )
            StartupRecoveryResult(
                cleanedCount = deletedReferences.size,
                failedCount = failedCount
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
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
            val refresh = treeDirectories.refreshManagedMigrationEntries(context, root)
            if (!refresh.isComplete) {
                NPLogger.w(TAG, "下载目录枚举不完整，跳过未完成半成品清理")
                return StartupRecoveryResult()
            }
            val rootEntries = refresh.rootEntries.filterNot(StoredEntry::isDirectory)
            val parsedMetadataEntries = rootEntries
                .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
                .mapNotNull { entry ->
                    val metadata = parseDownloadedAudioMetadata(context, entry) ?: return@mapNotNull null
                    ManagedDownloadParsedMetadataEntry(entry, metadata)
                }
            val managedSidecarReferences = refresh.coverEntries
                .plus(refresh.lyricEntries)
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
                        trustedReferences = referencesToDelete.toSet(),
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

    private fun renameTreeDocumentWithoutReplacing(
        context: Context,
        parent: DocumentFile,
        document: DocumentFile?,
        finalName: String
    ): DocumentFile? {
        if (document == null) return null
        val backend = SafStorageBackend(context)
        return ManagedDownloadTreeMutationLocks.withLock(parent.uri) {
            val refresh = treeChildRegistry.treeChildrenForWrite(context, parent)
            if (!canCreateTreePromotionTargetWithoutReplacing(
                    enumerationComplete = refresh.isComplete,
                    existingNames = refresh.children.map(QueriedTreeChild::name),
                    targetName = finalName
                )
            ) {
                NPLogger.w(
                    TAG,
                    "SAF 重命名目标不可安全创建，保留源文件: $finalName"
                )
                return@withLock null
            }
            when (val result = runBlocking(Dispatchers.IO) {
                backend.rename(
                    reference = TrustedManagedRef(
                        reference = StorageReference.SafRef(document.uri),
                        externalReference = document.uri.toString()
                    ),
                    displayName = finalName
                )
            }) {
                is moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.Renamed -> {
                    val renamedUri = (result.stat.reference as? StorageReference.SafRef)?.uri
                        ?: return@withLock null
                    DocumentFile.fromSingleUri(context, renamedUri)
                }
                moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.Missing -> null
                moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.PermissionLost -> {
                    throw SecurityException("SAF 重命名权限丢失: ${document.uri}")
                }
                is moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.ProviderFailure -> {
                    throw result.error
                }
                moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.OutOfScope,
                is moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.Unsupported -> null
            }
        }
    }

    private fun resolvePendingTreeDocument(
        context: Context,
        parent: DocumentFile,
        uri: Uri
    ): DocumentFile? {
        val direct = DocumentFile.fromSingleUri(context, uri) ?: return null
        return treeChildRegistry.toTreeDocumentFile(
            context = context,
            parent = parent,
            child = direct
        ) ?: direct
    }

    private fun resolveNewTreePromotionDocument(
        context: Context,
        parent: DocumentFile,
        uri: Uri
    ): DocumentFile? {
        return DocumentFile.fromSingleUri(context, uri)
            ?.let { document ->
                treeChildRegistry.toTreeDocumentFile(
                    context = context,
                    parent = parent,
                    child = document
                ) ?: document
            }
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
        onProgress: ((Long) -> Unit)? = null,
        replacementPlan: moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementPlan? = null
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
            onProgress = onProgress,
            replacementPlan = replacementPlan
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
            readText = { entry -> readTextInternal(context, entry.reference) },
            parseMetadata = ::parseDownloadedAudioMetadataJson
        )
    }

    private fun buildMigrationNamePlan(
        entries: List<ManagedMigrationEntry>,
        targetIndex: ManagedMigrationTargetIndex,
        sourceMetadataByAudioName: Map<String, DownloadedAudioMetadata>,
        replacementBackupNamespace: String = "migration"
    ) = ManagedDownloadMigrationNamePlanner.buildNamePlan(
        entries = entries.map(ManagedMigrationEntry::toRef),
        targetIndex = targetIndex,
        sourceMetadataByAudioName = sourceMetadataByAudioName,
        replacementBackupNamespace = replacementBackupNamespace
    )

    private fun mergePersistedReplacementPlan(
        fromDirectoryUri: String?,
        toDirectoryUri: String?,
        generatedPlan: moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationNamePlan,
        persistedJournal: ManagedMigrationReplacementJournal?
    ): moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationNamePlan {
        if (persistedJournal == null) return generatedPlan
        if (
            !areEquivalentDirectoryUris(fromDirectoryUri, persistedJournal.fromDirectoryUri) ||
            !areEquivalentDirectoryUris(toDirectoryUri, persistedJournal.toDirectoryUri)
        ) {
            throw ManagedDownloadMigrationException.transient(
                "迁移替换事务目录已变化，保留事务等待恢复"
            )
        }
        return mergePersistedMigrationReplacementPlan(
            generatedPlan = generatedPlan,
            persistedJournal = persistedJournal
        )
    }

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
        onProgress: ((Long) -> Unit)? = null,
        replacementPlan: moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementPlan? = null
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
            onProgress = onProgress,
            replacementPlan = replacementPlan
        )
    }

    private fun writeRootText(
        context: Context,
        root: RootHandle,
        displayName: String,
        content: String,
        expectedAbsent: Boolean = false,
        knownTargetEntry: StoredEntry? = null
    ): StoredEntry? {
        return commitWriter.writeRootText(
            context = context,
            root = root,
            displayName = displayName,
            content = content,
            expectedAbsent = expectedAbsent,
            knownTargetEntry = knownTargetEntry
        )
    }

    private suspend fun writeTextThroughBackend(
        context: Context,
        root: RootHandle,
        displayName: String,
        content: String,
        temporaryWriteOwnerName: String? = null
    ): StorageWriteResult {
        val backend: StorageBackend
        val target: StorageTarget
        when (root) {
            is RootHandle.FileRoot -> {
                backend = FileStorageBackend(root.dir)
                target = StorageTarget.FileTarget(
                    logicalPath = displayName,
                    temporaryWriteOwnerName = temporaryWriteOwnerName
                )
            }
            is RootHandle.TreeRoot -> {
                backend = SafStorageBackend(context)
                target = StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(root.tree.uri),
                    displayName = displayName,
                    mimeType = "application/octet-stream",
                    temporaryWriteOwnerName = temporaryWriteOwnerName
                )
            }
        }
        return backend.writeRecoverable(target) { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
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

    internal suspend fun <T> readStoredEntryForMigration(
        context: Context,
        entry: StoredEntry,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<Result<T>> {
        val target = backendReference(context, entry.reference)
            ?: return StorageLookupResult.OutOfScope
        return target.backend.readPreservingBlockFailure(target.reference, block)
    }

    private fun restoreStoredEntryLastModified(entry: StoredEntry, lastModifiedMs: Long) {
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
        val target = backendReference(context, reference) ?: return null
        return runBlocking(Dispatchers.IO) {
            when (val result = target.backend.read(target.reference) { input ->
                input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }) {
                is StorageLookupResult.Found -> {
                    result.value
                }
                StorageLookupResult.Missing -> null
                StorageLookupResult.PermissionLost -> {
                    throw SecurityException("storage permission lost: $reference")
                }
                is StorageLookupResult.ProviderFailure -> {
                    throw ManagedDownloadRootProviderException(reference, result.error)
                }
                StorageLookupResult.OutOfScope,
                is StorageLookupResult.Unsupported -> null
            }
        }
    }

    private fun inspectStorageReference(
        context: Context,
        reference: String?
    ): ManagedDownloadReferenceIo.AccessResult {
        val target = backendReference(context, reference)
            ?: return ManagedDownloadReferenceIo.AccessResult.Missing
        return try {
            runBlocking(Dispatchers.IO) {
                when (val result = target.backend.stat(target.reference)) {
                    is StorageLookupResult.Found -> {
                        ManagedDownloadReferenceIo.AccessResult.Accessible
                    }
                    StorageLookupResult.Missing -> {
                        ManagedDownloadReferenceIo.AccessResult.Missing
                    }
                    StorageLookupResult.PermissionLost -> {
                        ManagedDownloadReferenceIo.AccessResult.PermissionLost
                    }
                    is StorageLookupResult.ProviderFailure -> {
                        ManagedDownloadReferenceIo.AccessResult.ProviderFailure(result.error)
                    }
                    StorageLookupResult.OutOfScope -> {
                        ManagedDownloadReferenceIo.AccessResult.ProviderFailure(
                            IllegalArgumentException("storage reference out of scope")
                        )
                    }
                    is StorageLookupResult.Unsupported -> {
                        ManagedDownloadReferenceIo.AccessResult.ProviderFailure(
                            UnsupportedOperationException(result.operation)
                        )
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: SecurityException) {
            ManagedDownloadReferenceIo.AccessResult.PermissionLost
        } catch (error: Throwable) {
            ManagedDownloadReferenceIo.AccessResult.ProviderFailure(error)
        }
    }

    private fun backendReference(
        context: Context,
        rawReference: String?
    ): BackendReference? {
        val normalized = rawReference?.trim()?.takeIf(String::isNotBlank) ?: return null
        val uri = runCatching { normalized.toUri() }.getOrNull()
        if (uri?.scheme.equals("content", ignoreCase = true) && uri != null) {
            return BackendReference(
                backend = SafStorageBackend(context),
                reference = StorageReference.SafRef(uri)
            )
        }
        val path = when {
            normalized.startsWith("/", ignoreCase = false) -> normalized
            uri?.scheme?.equals("file", ignoreCase = true) == true -> uri.path
            else -> normalized
        }?.takeIf(String::isNotBlank) ?: return null
        val file = File(path)
        val parent = file.parentFile ?: return null
        return BackendReference(
            backend = FileStorageBackend(parent),
            reference = StorageReference.FileRef(file.name)
        )
    }

    private data class BackendReference(
        val backend: StorageBackend,
        val reference: StorageReference
    )

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
                .mapTo(linkedSetOf(), ::trustedManagedRef)
        )
    }

    private fun trustedManagedRef(reference: String): TrustedManagedRef {
        val uri = runCatching { reference.toUri() }.getOrNull()
        return if (uri?.scheme.equals("content", ignoreCase = true) && uri != null) {
            TrustedManagedRef(
                reference = StorageReference.SafRef(uri),
                externalReference = reference
            )
        } else {
            val filePath = if (uri?.scheme.equals("file", ignoreCase = true) && uri != null) {
                uri.path ?: reference
            } else {
                reference
            }
            TrustedManagedRef(
                reference = StorageReference.FileRef(filePath),
                externalReference = reference
            )
        }
    }

    private fun trustedManagedRefOrNull(reference: String?): TrustedManagedRef? {
        return reference
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::trustedManagedRef)
    }

    private fun resolveTrustedManagedReferences(
        references: Collection<String?>,
        deletePolicy: ManagedDownloadDeletePolicy
    ): List<TrustedManagedRef> {
        return references.mapNotNull { rawReference ->
            val candidate = trustedManagedRefOrNull(rawReference) ?: return@mapNotNull null
            deletePolicy.trustedReferences.firstOrNull { trusted ->
                trusted.externalReference == candidate.externalReference
            } ?: candidate.takeIf { reference ->
                reference.reference is StorageReference.FileRef &&
                    isReferenceAllowedForManagedDelete(
                        reference = reference.externalReference,
                        trustedReferences = emptySet(),
                        managedFileRoots = deletePolicy.managedFileRoots,
                        managedTreeRoots = deletePolicy.managedTreeRoots
                    )
            }
        }.distinctBy(TrustedManagedRef::externalReference)
    }

    private fun deleteEnumeratedMigrationReference(
        context: Context,
        reference: TrustedManagedRef,
        root: RootHandle
    ): StorageMutationResult {
        // enumerate and delete as one short transaction; concurrent cleanup must not
        // invalidate the evidence another worker is using
        return synchronized(migrationCleanupTrustLock) {
            val trustedReferences = runCatching {
                enumerateCompleteRootReferences(context, root)
            }.getOrElse { error ->
                return@synchronized error.toMigrationDeletionResult()
            } ?: return@synchronized StorageMutationResult.ProviderFailure(
                IllegalStateException("迁移删除前的完整枚举未完成")
            )
            val enumeratedReference = trustedReferences.firstOrNull { trusted ->
                trusted.externalReference == reference.externalReference
            } ?: run {
                NPLogger.w(
                    TAG,
                    "迁移删除引用未来自当前完整枚举，保留源: ${reference.externalReference}"
                )
                return@synchronized StorageMutationResult.OutOfScope
            }
            runCatching {
                val deleted = deleteInternal(
                    context = context,
                    reference = enumeratedReference.externalReference,
                    allowedRoot = root,
                    trustedReferences = trustedReferences.mapTo(linkedSetOf()) {
                        it.externalReference
                    },
                    invalidateSnapshot = false
                )
                if (deleted) {
                    StorageMutationResult.Deleted
                } else {
                    classifyMigrationDeleteFailure(context, reference)
                }
            }.getOrElse { error -> error.toMigrationDeletionResult() }
        }
    }

    private fun deleteEnumeratedMigrationReferences(
        context: Context,
        references: Collection<TrustedManagedRef>,
        root: RootHandle,
        onDeleteStarted: (TrustedManagedRef) -> Unit = {},
        onDeleteFinished: (TrustedManagedRef) -> Unit = {}
    ): Map<TrustedManagedRef, StorageMutationResult> {
        if (references.isEmpty()) return emptyMap()
        return synchronized(migrationCleanupTrustLock) {
            val trustedReferences = runCatching {
                enumerateCompleteRootReferences(context, root)
            }.getOrElse { error ->
                val result = error.toMigrationDeletionResult()
                return@synchronized references.associateWith { result }
            } ?: run {
                val result = StorageMutationResult.ProviderFailure(
                    IllegalStateException("迁移删除前的完整枚举未完成")
                )
                return@synchronized references.associateWith { result }
            }
            val eligibleReferences = references.mapNotNull { reference ->
                if (trustedReferences.any { trusted ->
                        trusted.externalReference == reference.externalReference
                    }
                ) {
                    reference
                } else {
                    NPLogger.w(
                        TAG,
                        "迁移删除引用未来自当前完整枚举，保留源: " +
                            "reference=${reference.externalReference} " +
                            "root=${rootIdentityForLog(root)}"
                    )
                    null
                }
            }
            val eligibleReferencesByExternalReference = eligibleReferences.associateBy(
                TrustedManagedRef::externalReference
            )
            val eligibleResults = if (eligibleReferences.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    val deletedReferences = deleteReferencesInternal(
                        context = context,
                        references = eligibleReferences.map(TrustedManagedRef::externalReference),
                        allowedRoot = root,
                        trustedReferences = trustedReferences.mapTo(linkedSetOf()) {
                            it.externalReference
                        },
                        invalidateSnapshot = false,
                        onDeleteStarted = { externalReference ->
                            eligibleReferencesByExternalReference[externalReference]
                                ?.let(onDeleteStarted)
                        },
                        onDeleteAttemptFinished = { externalReference, deleted ->
                            if (deleted) {
                                eligibleReferencesByExternalReference[externalReference]
                                    ?.let(onDeleteFinished)
                            }
                        }
                    )
                    eligibleReferences.associate { reference ->
                        reference to if (reference.externalReference in deletedReferences) {
                            StorageMutationResult.Deleted
                        } else {
                            try {
                                classifyMigrationDeleteFailure(context, reference)
                            } finally {
                                onDeleteFinished(reference)
                            }
                        }
                    }
                }.getOrElse { error ->
                    val result = error.toMigrationDeletionResult()
                    eligibleReferences.associateWith { result }
                }
            }
            buildMap {
                putAll(eligibleResults)
                references.filterNot { it in eligibleReferences }.forEach { reference ->
                    put(reference, StorageMutationResult.OutOfScope)
                }
            }
        }
    }

    private fun rootIdentityForLog(root: RootHandle): String {
        return when (root) {
            is RootHandle.FileRoot -> root.dir.absolutePath
            is RootHandle.TreeRoot -> root.tree.uri.toString()
        }
    }

    private fun enumerateCompleteRootReferences(
        context: Context,
        root: RootHandle
    ): Set<TrustedManagedRef>? {
        // migration cleanup must trust one complete root-plus-sidecar enumeration;
        // a root-only listing would leave nested Covers/Lyrics files behind
        val refresh = treeDirectories.refreshManagedMigrationEntries(context, root)
        if (!refresh.isComplete) {
            return null
        }
        return buildSet {
            (refresh.rootEntries + refresh.coverEntries + refresh.lyricEntries).forEach { entry ->
                add(trustedManagedRef(entry.reference))
                contentReferenceAliasesForTrust(entry.reference).forEach { alias ->
                    add(trustedManagedRef(alias))
                }
            }
        }
    }

    private fun Throwable.toMigrationDeletionResult(): StorageMutationResult {
        return if (this is SecurityException) {
            StorageMutationResult.PermissionLost
        } else {
            StorageMutationResult.ProviderFailure(this)
        }
    }

    private fun classifyMigrationDeleteFailure(
        context: Context,
        reference: TrustedManagedRef
    ): StorageMutationResult {
        return try {
            when (val access = inspectStorageReference(
                context,
                reference.externalReference
            )) {
                ManagedDownloadReferenceIo.AccessResult.Missing -> {
                    StorageMutationResult.Missing
                }
                ManagedDownloadReferenceIo.AccessResult.PermissionLost -> {
                    StorageMutationResult.PermissionLost
                }
                is ManagedDownloadReferenceIo.AccessResult.ProviderFailure -> {
                    StorageMutationResult.ProviderFailure(access.error)
                }
                ManagedDownloadReferenceIo.AccessResult.Accessible -> {
                    StorageMutationResult.ProviderFailure(
                        IllegalStateException("迁移源文件删除未确认")
                    )
                }
            }
        } catch (_: SecurityException) {
            StorageMutationResult.PermissionLost
        } catch (error: Throwable) {
            StorageMutationResult.ProviderFailure(error)
        }
    }

    private fun contentReferenceAliasesForTrust(reference: String): Set<String> {
        val uri = runCatching { reference.toUri() }.getOrNull()
            ?: return emptySet()
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank()) {
            return emptySet()
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return emptySet()
        return buildSet {
            add(DocumentsContract.buildDocumentUri(uri.authority, documentId).toString())
            if (uri.pathSegments.any { it == "tree" }) {
                add(
                    DocumentsContract.buildDocumentUriUsingTree(uri, documentId).toString()
                )
            }
        }
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

    private fun deleteInternal(
        context: Context,
        reference: String?,
        allowedRoot: RootHandle? = null,
        trustedReferences: Set<String>? = null,
        invalidateSnapshot: Boolean = true
    ): Boolean {
        return deleteReferencesInternal(
            context = context,
            references = listOf(reference),
            allowedRoot = allowedRoot,
            trustedReferences = trustedReferences,
            invalidateSnapshot = invalidateSnapshot
        ).isNotEmpty()
    }

    private fun deleteReferencesInternal(
        context: Context,
        references: Collection<String?>,
        allowedRoot: RootHandle? = null,
        trustedReferences: Set<String>? = null,
        invalidateSnapshot: Boolean,
        onDeleteStarted: (String) -> Unit = {},
        onDeleteAttemptFinished: (String, Boolean) -> Unit = { _, _ -> }
    ): Set<String> {
        val deletePolicy = buildManagedDeletePolicy(
            context = context,
            allowedRoot = allowedRoot,
            trustedReferences = trustedReferences
        )
        val deleteResult = referenceDeleteExecutor.deleteReferences(
            context = context,
            references = resolveTrustedManagedReferences(references, deletePolicy),
            deletePolicy = deletePolicy,
            onDeleteStarted = { reference ->
                onDeleteStarted(reference.externalReference)
            },
            onDeleteAttemptFinished = { reference, deleted ->
                onDeleteAttemptFinished(reference.externalReference, deleted)
            }
        )
        applyDeleteResultToSnapshot(context, deleteResult, invalidateSnapshot)
        return deleteResult.deletedReferences
    }

    private suspend fun deleteReferencesInternalConcurrently(
        context: Context,
        references: Collection<TrustedManagedRef>,
        deletePolicy: ManagedDownloadDeletePolicy,
        invalidateSnapshot: Boolean
    ): Set<String> {
        val deleteResult = referenceDeleteExecutor.deleteReferencesConcurrently(
            context = context,
            references = references,
            deletePolicy = deletePolicy
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

    private fun deleteTrustedReference(
        context: Context,
        reference: TrustedManagedRef
    ): StorageMutationResult {
        return referenceDeleteExecutor.deleteTrustedContentReference(
            context = context,
            reference = reference
        )
    }

    private fun StorageMutationResult.isConfirmedStorageMutation(): Boolean {
        return this is StorageMutationResult.Deleted || this is StorageMutationResult.Missing
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
        if (bucket == SnapshotEntryBucket.AUDIO && storedEntry.isPendingAudioWrite) {
            return false
        }
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
