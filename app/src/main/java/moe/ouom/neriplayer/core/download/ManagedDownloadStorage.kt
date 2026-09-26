package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.manager.batch.*
import moe.ouom.neriplayer.core.download.manager.catalog.*
import moe.ouom.neriplayer.core.download.manager.commit.*
import moe.ouom.neriplayer.core.download.manager.facade.*
import moe.ouom.neriplayer.core.download.model.*
import moe.ouom.neriplayer.core.download.storage.facade.*
import moe.ouom.neriplayer.core.download.storage.operation.*
import moe.ouom.neriplayer.core.download.storage.operation.content.*
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.*
import moe.ouom.neriplayer.core.download.storage.recovery.*
import android.content.Context
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadDeleteReferenceIndex
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndex
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationCoordinator
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationLocks
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutator
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexRebuildToken
import moe.ouom.neriplayer.core.download.index.ManagedLibraryIndexEntry
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadStorageCommitWriter
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadTreeFileCommitter
import moe.ouom.neriplayer.core.download.storage.commit.sameManagedMigrationStoredEntryIdentity
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadReferenceDeleteExecutor
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadCoverLookup
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadMetadataCodec
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.download.storage.migration.copy.ManagedDownloadMigrationCopyWorker
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationFinalizer
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationNamePlanner
import moe.ouom.neriplayer.core.download.storage.migration.progress.ManagedDownloadMigrationProgressSession
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCopyReceipt
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.copy.ManagedMigrationEntryReader
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupFinalizationPreparation
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupTarget
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProbeResult
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotCacheStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle

internal object ManagedDownloadStorage {
    internal const val TAG = "ManagedDownloadStorage"
    internal const val LEGACY_DOWNLOAD_ROOT_PATH = "/storage/emulated/0/neriplayer-download"
    internal const val LEGACY_DOWNLOAD_ROOT_RELATIVE_PATH = "neriplayer-download"
    internal const val LOG_HOT_AUDIO_HITS = false
    internal const val SIDECAR_REFRESH_THROTTLE_MS = 400L
    internal const val FAST_LYRICS_SLOW_LOG_MS = 120L
    internal const val FAST_INDEX_MANIFEST_LOCK_SHARD = "__manifest__"
    internal const val METADATA_SCAN_PARALLELISM = 4
    internal val KNOWN_TRANSIENT_PENDING_ARTIFACT_STATES = setOf(
        "PENDING_QUEUE",
        "QUEUED",
        "DOWNLOADING",
        "VERIFYING",
        "RETRYABLE",
        "FAILED_RETRYABLE",
        "CANCELLED"
    )
    internal const val TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS = 3
    internal val snapshotBuildLock = Any()
    internal val sidecarRefreshLock = Any()
    internal val snapshotWarmupLock = Any()
    internal val metadataScanDispatcher =
        Dispatchers.IO.limitedParallelism(METADATA_SCAN_PARALLELISM)
    internal val batchReferenceDeleteMutex = Mutex()
    internal val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var snapshotWarmupJob: Job? = null
    internal var snapshotWarmupKey: String? = null
    internal var snapshotWarmupRefreshSidecars: Boolean = false
    internal val settings = ManagedDownloadStorageSettings(
        defaultRootPathProvider = { context ->
            ManagedDownloadRootResolver.defaultRootDirectory(context).absolutePath
        }
    )
    internal val snapshotCacheStore = ManagedDownloadSnapshotCacheStore(
        scope = snapshotScope,
        cacheKeyProvider = ::resolveSnapshotCacheKey
    )
    internal val treeChildRegistry = ManagedDownloadTreeChildRegistry(
        writeCacheValidateIntervalMs = FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS,
        treeCacheValidateIntervalMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS,
        treeWriteCacheValidateIntervalMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS,
        onTreeQueryFailed = {
            NPLogger.w(
                TAG,
                "目录查询未完成，保留现有文件与恢复凭据: " +
                    "${it.javaClass.simpleName}: ${it.message}",
                it
            )
        }
    )
    internal val treeDirectoryLocks = ConcurrentHashMap<String, Any>()
    internal val rootResolver = ManagedDownloadRootResolver(treeDirectoryLocks)
    internal val treeDirectories = ManagedDownloadTreeDirectories(
        treeChildRegistry = treeChildRegistry,
        tag = TAG,
        deleteTrustedReference = ::deleteTrustedReference
    )
    internal val treeFileCommitter = ManagedDownloadTreeFileCommitter(
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
    internal val commitWriter = ManagedDownloadStorageCommitWriter(
        treeChildRegistry = treeChildRegistry,
        treeDirectories = treeDirectories,
        tag = TAG
    )
    internal val fastIndexManifestLocks = ManagedLibraryFastIndexMutationLocks()
    internal val fastIndexMutationCoordinator = ManagedLibraryFastIndexMutationCoordinator()
    internal val fastIndexMutator = ManagedLibraryFastIndexMutator()
    internal val migrationEntryReader = object : ManagedMigrationEntryReader {
        override suspend fun <T> read(
            context: Context,
            entry: StoredEntry,
            block: suspend (InputStream) -> T
        ): StorageLookupResult<Result<T>> {
            return readStoredEntryForMigration(context, entry, block)
        }
    }
    internal val migrationCopyWorker = ManagedDownloadMigrationCopyWorker(
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
    internal val referenceDeleteExecutor = ManagedDownloadReferenceDeleteExecutor(
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
        },
        contentReferenceBatchDeleteOperation = { context, references ->
            val batchResult = ManagedDownloadReferenceIo.deleteContentReferencesBatch(
                context = context,
                uris = references.map { reference ->
                    (reference.reference as StorageReference.SafRef).uri
                }
            )
            batchResult.results.takeIf { batchResult.supported }?.map { result ->
                when (result) {
                    ManagedDownloadReferenceIo.DeleteResult.Deleted ->
                        StorageMutationResult.Deleted
                    ManagedDownloadReferenceIo.DeleteResult.Missing ->
                        StorageMutationResult.Missing
                    ManagedDownloadReferenceIo.DeleteResult.PermissionLost ->
                        StorageMutationResult.PermissionLost
                    is ManagedDownloadReferenceIo.DeleteResult.ProviderFailure ->
                        StorageMutationResult.ProviderFailure(result.error)
                }
            }
        }
    )
    internal val migrationFinalizer = ManagedDownloadMigrationFinalizer(
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
        writeRootTextWithKnownEntry = { context, root, displayName, content, knownEntry ->
            writeRootText(
                context = context,
                root = root,
                displayName = displayName,
                content = content,
                knownTargetEntry = knownEntry
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
        rewriteMetadataReferencesPrepared = { rawJson, referenceMap, sortedReplacements ->
            ManagedDownloadMetadataCodec.rewriteManagedMetadataReferences(
                rawJson = rawJson,
                referenceMap = referenceMap,
                sortedReplacements = sortedReplacements
            )
        },
        restoreReplacement = { context, root, copied ->
            commitWriter.restoreMigrationReplacement(
                context = context,
                root = root,
                copied = copied
            )
        }
    )
    internal val pendingAudioWriteNames = ManagedDownloadPendingAudioWriteNames()
    internal val migrationCleanupTrustLock = Mutex()
    @Volatile
    internal var startupRecoveryResult = StartupRecoveryResult()
    @Volatile
    internal var lastSidecarRefreshKey: String? = null
    @Volatile
    internal var lastSidecarRefreshAtMs: Long = 0L
    internal val _startupRecoveryResults = MutableSharedFlow<StartupRecoveryResult>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    internal val startupRecoveryResults: SharedFlow<StartupRecoveryResult> = _startupRecoveryResults
    internal val migrationProgressSession = ManagedDownloadMigrationProgressSession()
    val migrationProgressFlow: StateFlow<MigrationProgress?> = migrationProgressSession.flow
    /** 在 Worker 开始收集前绑定进程级进度流 */
    internal fun beginMigrationProgressSession(
        ownerWorkId: String,
        persistedProgress: MigrationProgress?
    ): Boolean {
        return migrationProgressSession.tryClaim(ownerWorkId, persistedProgress)
    }
    internal fun endMigrationProgressSession(ownerWorkId: String) {
        migrationProgressSession.finish(ownerWorkId)
    }
    // sidecar 目录刷新完成后通知播放页重新读取当前歌曲, 不让首帧等待 SAF
    internal val _lyricsRefreshVersion = MutableStateFlow(0L)
    internal val lyricsRefreshVersion: StateFlow<Long> = _lyricsRefreshVersion
    fun initialize(context: Context) {
        return this.initializeImpl(context)
    }
    /**
     * 检查迁移凭据时不触碰任一存储根。读取失败也必须保守延后清理,
     * 避免进程恢复窗口中把半成品误当成可删除文件
     */
    internal fun scheduleLyricsRefresh(context: Context) {
        scheduleSnapshotWarmup(context, refreshSidecars = true)
    }
    internal data class StartupRecoveryResult(
        val cleanedCount: Int = 0,
        val failedCount: Int = 0,
        /** 权限或作用域恢复前立即重试没有意义，但持久记录仍必须保留 */
        val externalSignalRequiredCount: Int = 0,
        /** 已跨过核心提交边界的 pending，不属于清空失败或待重试项 */
        val protectedCount: Int = 0,
        /** 本轮已确认属于持久核心的引用，供清空快照复用，避免再次读取 SAF 元数据 */
        val protectedReferences: Set<String> = emptySet(),
        /** 仅返回实际删除失败所对应的歌曲，不能用所有输入 operation 代替 */
        val failedStableKeys: Set<String> = emptySet()
    ) {
        val hasRecoveredEntries: Boolean
            get() = cleanedCount > 0 || failedCount > 0
        val immediatelyRetryableFailedCount: Int
            get() = (failedCount - externalSignalRequiredCount).coerceAtLeast(0)
    }
    /** 只把实际删除失败的引用归属到歌曲，避免把整批 operation 误报成残留 */
    internal fun resolveFailedStableKeys(
        referencesByStableKey: Map<String, Set<String>>,
        failedReferences: Set<String>
    ): Set<String> {
        return this.resolveFailedStableKeysImpl(referencesByStableKey, failedReferences)
    }
    /**
     * metadata 读取失败时不能把同名 pending 音频当作无主临时文件删除
     * 让清空流程把这类引用保留为阻塞项，等下一轮恢复或人工处理
     */
    internal fun resolveUnreadablePendingArtifactReferences(
        pendingEntries: Collection<StoredEntry>,
        metadataEntries: Collection<StoredEntry>,
        unreadableMetadataReferences: Set<String>
    ): Set<String> {
        return this.resolveUnreadablePendingArtifactReferencesImpl(pendingEntries, metadataEntries, unreadableMetadataReferences)
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
        /** Provider 没有返回可信大小时为 false */
        val sizeKnown: Boolean = true,
        val isDirectory: Boolean = false
    ) {
        val isPendingAudioWrite: Boolean
            get() = ManagedDownloadPendingAudioWriteNames.isArtifactName(name)
        val logicalName: String
            get() = pendingAudioWriteNames.logicalAudioName(name)
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
    internal data class TemporaryDirectoryEntries(
        val entries: List<StoredEntry>,
        val isComplete: Boolean,
        val exists: Boolean
    )

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
        val rootEntriesComplete: Boolean = true,
        /** Covers/Lyrics 子项查询是否完整，false 时沿用旧侧载索引 */
        val sidecarEntriesComplete: Boolean = true,
        /** 已完成核心写入但尚未提升为正式文件名的音频 */
        val pendingAudioEntries: List<StoredEntry> = emptyList(),
        /** pending metadata 与正式 metadata 同名时, 为 pending 音频保留独立凭据 */
        val pendingMetadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
        /** 本轮根目录已完整返回空，仅等待防瞬时空结果的第二次确认 */
        val rootEmptyConfirmationPending: Boolean = false
    ) {
        internal val referenceIdentityIndex by lazy {
            ManagedDownloadDeleteReferenceIndex(buildSet {
                addAll(knownReferences)
                (coverEntriesByName.values + lyricEntriesByName.values).forEach { entry ->
                    add(entry.reference)
                    add(entry.mediaUri)
                }
            })
        }
        internal val metadataEntriesByCanonicalAudioName: Map<String, StoredEntry> by lazy {
            buildMap {
                metadataEntriesByAudioName.forEach { (audioName, entry) ->
                    val key = ManagedDownloadTreeNaming.canonicalLookupName(audioName)
                    if (key !in this) put(key, entry)
                }
            }
        }
        internal val metadataByCanonicalAudioName: Map<String, DownloadedAudioMetadata> by lazy {
            buildMap {
                metadataByAudioName.forEach { (audioName, metadata) ->
                    val key = ManagedDownloadTreeNaming.canonicalLookupName(audioName)
                    if (key !in this) put(key, metadata)
                }
            }
        }
        internal val pendingMetadataByCanonicalAudioName: Map<String, DownloadedAudioMetadata> by lazy {
            buildMap {
                pendingMetadataByAudioName.forEach { (audioName, metadata) ->
                    val key = ManagedDownloadTreeNaming.canonicalLookupName(audioName)
                    if (key !in this) put(key, metadata)
                }
            }
        }
        internal val metadataByDeclaredAudioName: Map<String, DownloadedAudioMetadata> by lazy {
            buildMap {
                metadataByAudioName.values.forEach { metadata ->
                    val key = metadata.audioFileName
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(ManagedDownloadTreeNaming::canonicalLookupName)
                        ?: return@forEach
                    if (key !in this) put(key, metadata)
                }
            }
        }
        internal val metadataByStoredReference: Map<String, DownloadedAudioMetadata> by lazy {
            buildMap {
                metadataByAudioName.values.forEach { metadata ->
                    val reference = metadata.mediaUri
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: return@forEach
                    if (reference !in this) put(reference, metadata)
                }
            }
        }
        internal val artifactOwnerAudioNamesByReference: Map<String, Set<String>> by lazy {
            buildMap<String, MutableSet<String>> {
                metadataByAudioName.forEach { (audioName, metadata) ->
                    listOfNotNull(
                        metadata.coverPath,
                        metadata.lyricPath,
                        metadata.translatedLyricPath,
                        metadata.romanizedLyricPath
                    ).forEach { reference ->
                        getOrPut(reference) { linkedSetOf() }.add(audioName)
                    }
                }
            }
        }
    }
    internal fun metadataForAudioEntry(
        snapshot: DownloadLibrarySnapshot?,
        audio: StoredEntry
    ): DownloadedAudioMetadata? {
        return this.metadataForAudioEntryImpl(snapshot, audio)
    }
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
        val verifiedAudioDurationMs: Long? = null,
        val downloadTimeMs: Long? = null,
        val downloadFinalized: Boolean? = null,
        val audioPublicationPending: Boolean = false,
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
        val restorableMetadata: ManagedDownloadRestorableMetadata? = null,
        val createdAtConfidence: String? = null,
        val audioPublicationOwnerId: String? = null
    )
    fun primeSettings(directoryUri: String?, directoryLabel: String?, fileNameTemplate: String? = null) {
        return this.primeSettingsImpl(directoryUri, directoryLabel, fileNameTemplate)
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
    ): Boolean = this.hasMigratableDownloadsImpl(context, directoryUri)
    suspend fun hasActualDirectoryEntries(
        context: Context,
        directoryUri: String?
    ): Boolean = this.hasActualDirectoryEntriesImpl(context, directoryUri)
    /** 迁移持有目录租约时直接移除应用自己的临时目录 */
    internal suspend fun discardMigrationTemporaryDirectory(
        context: Context,
        directoryUri: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val root = resolveRoot(context, directoryUri)
            ?: return@withContext false
        discardMigrationTemporaryDirectory(context, root)
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
        onReplacementJournalUpdated: suspend (ManagedMigrationReplacementJournal) -> Unit = {},
        persistedProgress: MigrationProgress? = null,
        progressOwnerWorkId: String? = null,
        persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt> = emptyMap(),
        onCopyReceipt: suspend (ManagedMigrationCopyReceipt) -> Unit = {},
        onCopyReceiptInvalidated: suspend (String) -> Unit = {},
        onCopyReceiptsFlush: suspend () -> Unit = {},
        pendingArtifactsPreflightVerified: Boolean = false
    ): MigrationResult = this.migrateManagedDownloadsImpl(context, fromDirectoryUri, toDirectoryUri, minimumSourceEntryCount, targetPreviouslyCommitted, persistedTargetNames, onSourceAudioCountResolved, onTargetNamePlanResolved, onTargetVerified, persistedReplacementJournal, replacementJournalWorkId, onReplacementJournalUpdated, persistedProgress, progressOwnerWorkId, persistedCopyReceipts, onCopyReceipt, onCopyReceiptInvalidated, onCopyReceiptsFlush, pendingArtifactsPreflightVerified)

    internal data class OrphanMigrationReplacementRecoveryResult(
        val resolvedReferences: Set<String>,
        val unresolvedReferences: Set<String>
    )
    /**
     * 恢复替换凭据尚未落盘时被终止的目标文件
     *
     * 目标目录列举不完整时不做任何推断，避免把 Provider 暂时不可见误当成用户删除
     */
    internal suspend fun verifyMigrationCleanupReceipts(
        context: Context,
        targetRoot: RootHandle,
        journal: ManagedMigrationReplacementJournal
    ) = this.verifyMigrationCleanupReceiptsImpl(context, targetRoot, journal)
    /** 识别恢复日志落盘前已经改回目标名称的替换备份 */
    internal fun isRestoredMigrationReplacementTarget(
        expectedTarget: StoredEntry,
        actualTarget: StoredEntry,
        replacementBackup: StoredEntry,
        targetDigest: String? = null,
        expectedTargetDigest: String? = null
    ): Boolean {
        return this.isRestoredMigrationReplacementTargetImpl(expectedTarget, actualTarget, replacementBackup, targetDigest, expectedTargetDigest)
    }
    /** 用本轮复制凭据覆盖旧记录，让重新复制的结果继续作为恢复依据 */
    internal fun mergeMigrationCopyReceiptsForRecovery(
        persisted: Map<String, ManagedMigrationCopyReceipt>,
        current: Iterable<ManagedMigrationCopyReceipt>
    ): Map<String, ManagedMigrationCopyReceipt> {
        return this.mergeMigrationCopyReceiptsForRecoveryImpl(persisted, current)
    }
    internal fun List<String>.migrationDocumentIdFromSafPath(): String? {
        return when {
            size >= 4 && this[0] == "tree" && this[2] == "document" -> this[3]
            size >= 2 && this[0] == "document" -> this[1]
            else -> null
        }
    }
    internal data class MigrationReplacementBackupCandidate(
        val backup: StoredEntry,
        val subdirectory: String?
    )
    internal data class MigrationRecoveryTargetResolution(
        val entry: StoredEntry?,
        val alreadyRestored: Boolean
    )
    internal data class ValidatedMigrationReplacementBackup(
        val actual: StoredEntry
    )
    fun releasePersistedDirectoryPermission(context: Context, uriString: String?) {
        return this.releasePersistedDirectoryPermissionImpl(context, uriString)
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
        return this.createWorkingFileImpl(context, songKey, fileName, operationId)
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
    ): Boolean {
        return ManagedDownloadRecoveryFiles.saveWorkingResumeMetadata(workingFile, song, operationId)
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
    ): Boolean {
        return ManagedDownloadRecoveryFiles.updateWorkingResumeFingerprint(workingFile, fingerprint)
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
        return this.upsertPendingDownloadQueueImpl(context, songs, userInitiated, requiresWifiNetwork, downloadAudioQuality)
    }
    internal fun listPendingQueuedDownloads(context: Context): List<PendingDownloadQueueEntry> {
        return ManagedDownloadRecoveryFiles.listPendingQueuedDownloads(context)
    }
    internal fun countPendingQueuedDownloads(context: Context): Int {
        return ManagedDownloadRecoveryFiles.countPendingQueuedDownloads(context)
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
    internal fun removePendingDownloadQueueOperationIds(
        context: Context,
        operationIds: Collection<String>
    ) {
        return this.removePendingDownloadQueueOperationIdsImpl(context, operationIds)
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
        return this.isLikelyManagedDownloadSongImpl(context, song)
    }
    /**
     * 首屏只使用内存和 URI 线索判断下载来源, 不查询 MediaStore 或 SAF provider
     */
    internal fun isLikelyManagedDownloadSongFast(
        context: Context,
        song: SongItem
    ): Boolean {
        return this.isLikelyManagedDownloadSongFastImpl(context, song)
    }
    /**
     * 下载歌曲在目录索引恢复前仍保留远端来源标识, 可据此走下载侧载快路径
     */
    internal fun hasManagedDownloadIdentityHint(song: SongItem): Boolean {
        return this.hasManagedDownloadIdentityHintImpl(song)
    }
    internal fun isKnownManagedDownloadDocumentId(
        documentId: String,
        treeDocumentId: String?
    ): Boolean {
        return this.isKnownManagedDownloadDocumentIdImpl(documentId, treeDocumentId)
    }
    internal fun isManagedDownloadRelativePath(
        relativePath: String?,
        treeDocumentId: String?
    ): Boolean {
        return this.isManagedDownloadRelativePathImpl(relativePath, treeDocumentId)
    }
    /**
     * 将 SAF URI 或 document ID 归一为可用于侧载文件匹配的音频文件名
     */
    internal fun normalizeManagedAudioFileName(raw: String?): String? {
        return this.normalizeManagedAudioFileNameImpl(raw)
    }
    /** 优先读取 Provider 返回的真实显示名，避免把不透明文档 ID 当成文件名 */
    internal fun resolveManagedAudioDisplayName(
        context: Context,
        song: SongItem
    ): String? {
        return this.resolveManagedAudioDisplayNameImpl(context, song)
    }
    fun peekCoverReference(audio: StoredEntry): String? {
        val snapshot = snapshotCacheStore.peekSnapshot() ?: return null
        return ManagedDownloadCoverLookup.findCoverReference(snapshot, audio)
    }
    internal fun shouldUseSidecarRefreshForCoverLookup(
        forceRefresh: Boolean,
        preferSidecarRefresh: Boolean,
        hasCachedSnapshot: Boolean
    ): Boolean = forceRefresh && preferSidecarRefresh && hasCachedSnapshot
    /**
     * 通过持久化逻辑文件名恢复 SAF 重授权后变化的 provider URI
     */
    internal suspend fun findCoverReferenceByFileName(
        context: Context,
        fileName: String?,
        forceRefresh: Boolean = true,
        preferSidecarRefresh: Boolean = false
    ): String? = this.findCoverReferenceByFileNameImpl(context, fileName, forceRefresh, preferSidecarRefresh)
    internal suspend fun isManagedCoverReference(
        context: Context,
        reference: String?
    ): Boolean = this.isManagedCoverReferenceImpl(context, reference)
    /**
     * 通过内容寻址封面哈希恢复跨 SAF 重授权后变化的 provider URI
     */
    internal suspend fun findCoverReferenceByAssetHash(
        context: Context,
        assetHash: String?
    ): String? = this.findCoverReferenceByAssetHashImpl(context, assetHash)
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
    internal data class PendingAudioWriteScanResult(
        val entries: List<StoredEntry>,
        val isComplete: Boolean
    )
    internal suspend fun listPendingAudioWrites(
        context: Context,
        forceRefresh: Boolean = false,
        directoryUri: String? = null,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): List<StoredEntry> = scanPendingAudioWrites(
        context = context,
        forceRefresh = forceRefresh,
        directoryUri = directoryUri,
        useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing
    ).entries
    internal suspend fun scanPendingAudioWrites(
        context: Context,
        forceRefresh: Boolean = false,
        directoryUri: String? = null,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): PendingAudioWriteScanResult = this.scanPendingAudioWritesImpl(context, forceRefresh, directoryUri, useDefaultRootWhenDirectoryUriMissing)
    internal data class PendingArtifactScanResult(
        val count: Int,
        val isComplete: Boolean,
        /** 已确认属于核心音频的 pending 项，不应阻塞清空收敛 */
        val protectedCount: Int = 0,
        /** 迁移时会阻断复制的 pending 音频及其 metadata 配对 */
        val migrationBlockingArtifactCount: Int = count,
        /** 没有同名 pending 音频的 metadata 凭据，迁移会保留但不反复重试 */
        val migrationMetadataOnlyArtifactCount: Int = 0
    ) {
        val blockingCount: Int
            get() = (count - protectedCount.coerceAtLeast(0)).coerceAtLeast(0)
    }
    /** 清空前使用一次完整快照确认没有遗留 pending，避免无凭据时误放行栅栏 */
    internal suspend fun scanPendingDownloadArtifacts(
        context: Context,
        protectedReferences: Set<String> = emptySet(),
        directoryUri: String? = null,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): PendingArtifactScanResult = this.scanPendingDownloadArtifactsImpl(context, protectedReferences, directoryUri, useDefaultRootWhenDirectoryUriMissing)
    /** 清空时只有明确属于临时状态的条目才允许删除 */
    internal fun isKnownTransientPendingMetadata(
        metadata: DownloadedAudioMetadata
    ): Boolean {
        return this.isKnownTransientPendingMetadataImpl(metadata)
    }
    internal suspend fun findDownloadedAudioByCandidateBaseNames(
        context: Context,
        candidateBaseNames: List<String>
    ): StoredEntry? = this.findDownloadedAudioByCandidateBaseNamesImpl(context, candidateBaseNames)
    fun findDownloadedAudio(snapshot: DownloadLibrarySnapshot, song: SongItem): StoredEntry? {
        return findAudioEntry(snapshot, song)
    }
    /** 批量收敛还要看没有 metadata 的正式音频, 但只接受当前歌曲的严格文件名候选 */
    internal fun findDownloadedAudioIncludingMetadataLess(
        snapshot: DownloadLibrarySnapshot,
        song: SongItem
    ): StoredEntry? {
        return this.findDownloadedAudioIncludingMetadataLessImpl(snapshot, song)
    }
    internal fun findPendingDownloadedAudio(
        snapshot: DownloadLibrarySnapshot,
        song: SongItem
    ): StoredEntry? {
        return this.findPendingDownloadedAudioImpl(snapshot, song)
    }
    internal fun peekPendingDownloadedAudio(song: SongItem): StoredEntry? {
        return snapshotCacheStore.peekSnapshot()?.let { snapshot ->
            findPendingDownloadedAudio(snapshot, song)
        }
    }
    suspend fun queryStoredEntry(context: Context, reference: String?): StoredEntry? = this.queryStoredEntryImpl(context, reference)
    suspend fun buildDownloadLibrarySnapshot(
        context: Context,
        forceRefresh: Boolean = false,
        includeMetadataLessAudioForLegacyUpgrade: Boolean = false
    ): DownloadLibrarySnapshot = this.buildDownloadLibrarySnapshotImpl(context, forceRefresh, includeMetadataLessAudioForLegacyUpgrade)
    internal suspend fun buildLegacyUpgradeSnapshot(
        context: Context
    ): DownloadLibrarySnapshot = this.buildLegacyUpgradeSnapshotImpl(context)
    internal suspend fun upsertCompleteFastIndexEntry(
        context: Context,
        entry: ManagedLibraryIndexEntry
    ): ManagedLibraryFastIndexMutationResult = this.upsertCompleteFastIndexEntryImpl(context, entry)
    internal suspend fun upsertCompleteFastIndexEntry(
        context: Context,
        song: SongItem,
        audio: StoredEntry,
        state: String,
        coverPath: String?
    ): ManagedLibraryFastIndexMutationResult = this.upsertCompleteFastIndexEntryImpl(context, song, audio, state, coverPath)
    internal suspend fun updateExistingFastIndexEntry(
        context: Context,
        stableKey: String,
        transform: (ManagedLibraryIndexEntry) -> ManagedLibraryIndexEntry
    ): ManagedLibraryFastIndexMutationResult = this.updateExistingFastIndexEntryImpl(context, stableKey, transform)
    /** 按分片一次性移除多首歌曲，避免全库删除退化为逐条 SAF 读写 */
    internal suspend fun removeFastIndexEntries(
        context: Context,
        stableKeys: Collection<String>
    ): List<ManagedLibraryFastIndexMutationResult> = this.removeFastIndexEntriesImpl(context, stableKeys)
    /** 全库物理删除确认后重建空索引，顺便清除不在 catalog 中的陈旧分片 */
    internal suspend fun clearFastIndexForConfirmedEmptyLibrary(
        context: Context
    ): Boolean = this.clearFastIndexForConfirmedEmptyLibraryImpl(context)
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
    ) = this.persistFastIndexImpl(context, snapshot, rebuildToken)
    internal fun shouldPersistFastIndex(snapshot: DownloadLibrarySnapshot): Boolean {
        // 元数据不完整不能说明对应分片为空
        return snapshot.rootEntriesComplete &&
            snapshot.sidecarEntriesComplete &&
            snapshot.audioEntriesWithoutMetadata.isEmpty()
    }
    internal suspend fun restoreFastIndexPreview(
        context: Context
    ): DownloadLibrarySnapshot? = this.restoreFastIndexPreviewImpl(context)
    internal data class FastIndexReadResult(
        val entries: List<ManagedLibraryIndexEntry>,
        val rootEntries: List<ManagedLibraryFastIndex.RootEntry>
    )
    internal suspend fun refreshDownloadSidecarSnapshot(
        context: Context,
        snapshot: DownloadLibrarySnapshot,
        forceRefresh: Boolean = false
    ): DownloadLibrarySnapshot = this.refreshDownloadSidecarSnapshotImpl(context, snapshot, forceRefresh)
    internal fun buildDownloadLibrarySnapshotBlocking(
        context: Context,
        forceRefresh: Boolean = false,
        includeMetadataLessAudioForLegacyUpgrade: Boolean = false
    ): DownloadLibrarySnapshot = this.buildDownloadLibrarySnapshotBlockingImpl(context, forceRefresh, includeMetadataLessAudioForLegacyUpgrade)
    internal fun canReuseCachedDownloadedMetadata(
        cachedEntry: StoredEntry?,
        currentEntry: StoredEntry,
        cachedMetadata: DownloadedAudioMetadata?
    ): Boolean {
        return this.canReuseCachedDownloadedMetadataImpl(cachedEntry, currentEntry, cachedMetadata)
    }
    internal fun selectReusableCachedDownloadedMetadata(
        currentEntries: Map<String, StoredEntry>,
        cachedSnapshot: DownloadLibrarySnapshot?
    ): Map<String, DownloadedAudioMetadata> {
        return this.selectReusableCachedDownloadedMetadataImpl(currentEntries, cachedSnapshot)
    }
    internal fun shouldSkipRedundantForcedSidecarRefresh(
        requestedSnapshot: DownloadLibrarySnapshot,
        activeSnapshot: DownloadLibrarySnapshot?,
        respectThrottle: Boolean
    ): Boolean {
        return this.shouldSkipRedundantForcedSidecarRefreshImpl(requestedSnapshot, activeSnapshot, respectThrottle)
    }
    internal fun emptyDownloadLibrarySnapshot(): DownloadLibrarySnapshot {
        return this.emptyDownloadLibrarySnapshotImpl()
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
        return this.shouldTreatAudioAsManagedImpl(audioName, metadataAudioNames, coverEntryNames, lyricEntryNames, allowMetadataLessAudio)
    }
    suspend fun findMetadataForAudio(context: Context, audio: StoredEntry): StoredEntry? = this.findMetadataForAudioImpl(context, audio)
    /** 迁移前从源 root 读取 metadata, 不让当前配置目录遮蔽旧目录凭据 */
    internal suspend fun readDownloadedMetadataFromRoot(
        context: Context,
        audio: StoredEntry,
        directoryUri: String?,
        preferPendingMetadata: Boolean = false,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): DownloadedAudioMetadata? = this.readDownloadedMetadataFromRootImpl(context, audio, directoryUri, preferPendingMetadata, useDefaultRootWhenDirectoryUriMissing)
    internal fun metadataReferenceForAudio(audio: StoredEntry): String? {
        val reference = audio.reference.takeIf(String::isNotBlank) ?: return null
        if (audio.isPendingAudioWrite) return null
        return "$reference$METADATA_SUFFIX"
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
    ): Boolean = this.saveMetadataForLegacyUpgradeImpl(context, audio, json, expectedAbsent, knownMetadataEntry)
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
    ): Boolean = this.writePendingAudioMetadataImpl(context, audioName, json, operationId)
    internal suspend fun promoteFinalizedPendingAudio(
        context: Context,
        audio: StoredEntry
    ): FinalizedPendingAudioPromotion? = this.promoteFinalizedPendingAudioImpl(context, audio)
    /** core 提交后先把可播放音频提升出 .tmp，资产增强在目录变更结束后继续执行 */
    internal suspend fun promoteCoreCommittedPendingAudio(
        context: Context,
        audio: StoredEntry,
        directoryUri: String? = null,
        promotePendingMetadata: Boolean = false,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): StoredEntry? = this.promoteCoreCommittedPendingAudioImpl(context, audio, directoryUri, promotePendingMetadata, useDefaultRootWhenDirectoryUriMissing)
    internal fun resolveStagedPendingPromotionFinalName(
        requestedName: String,
        stagedMetadata: DownloadedAudioMetadata,
        expectedStableKey: String?,
        expectedOperationId: String?
    ): String? {
        return this.resolveStagedPendingPromotionFinalNameImpl(requestedName, stagedMetadata, expectedStableKey, expectedOperationId)
    }
    internal fun resolvePendingAudioPromotionFinalName(
        enumerationComplete: Boolean,
        existingNames: Collection<String>,
        requestedName: String
    ): String? {
        return this.resolvePendingAudioPromotionFinalNameImpl(enumerationComplete, existingNames, requestedName)
    }
    internal fun rewritePendingMetadataAudioFileName(
        rawMetadata: String,
        finalAudioName: String
    ): String? {
        return this.rewritePendingMetadataAudioFileNameImpl(rawMetadata, finalAudioName)
    }
    internal data class ExactRootEntryLookup(
        val entry: StoredEntry?,
        val complete: Boolean
    )
    /**
     * 将旧流程过早公开的普通音频退回 pending 名称，避免未完成标签写入的文件进入目录索引
     */
    internal suspend fun demotePublishedAudioForFinalization(
        context: Context,
        audio: StoredEntry,
        expectedMetadataFinalized: Boolean?
    ): StoredEntry? = this.demotePublishedAudioForFinalizationImpl(context, audio, expectedMetadataFinalized)
    internal suspend fun deletePendingAudioMetadata(
        context: Context,
        audioName: String
    ): Boolean = this.deletePendingAudioMetadataImpl(context, audioName)
    /** 只删除已确认属于某个已取消 operation 的提交前待处理文件对
     * directoryUri 用于迁移期间清理仍保留凭据的旧源目录
     */
    internal suspend fun cleanupCancelledPendingDownloadArtifacts(
        context: Context,
        stableKey: String,
        operationId: String,
        directoryUri: String? = null,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): StartupRecoveryResult = cleanupCancelledPendingDownloadArtifacts(
        context = context,
        operations = listOf(CancelledPendingDownloadOperation(stableKey, operationId)),
        directoryUri = directoryUri,
        useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing
    )
    /** 清空全部任务时基于一次完整根目录快照解析所有 operation 的待处理文件对
     * 避免按歌曲重复扫描 SAF, directoryUri 为空时沿用当前配置根目录
     */
    internal suspend fun cleanupCancelledPendingDownloadArtifacts(
        context: Context,
        operations: Collection<CancelledPendingDownloadOperation>,
        onProgress: (completedItems: Int, totalItems: Int) -> Unit = { _, _ -> },
        directoryUri: String? = null,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): StartupRecoveryResult = this.cleanupCancelledPendingDownloadArtifactsImpl(context, operations, onProgress, directoryUri, useDefaultRootWhenDirectoryUriMissing)
    /**
     * 清空任务时收敛没有 operation 凭据的临时文件
     *
     * 新版本的下载 staging 全部位于 .tmp，该目录只承载可恢复的中间产物
     * 在用户明确执行清空后，未跨过 core commit 的 .tmp 项可以安全删除
     * 已跨过 core commit 的项仍按 metadata 和调用方快照保护，根目录旧版本
     * 产物只有在 metadata 可解析且明确不是 durable core 时才删除，避免误伤
     * 用户已有的正式音频
     */
    internal suspend fun cleanupUnownedPendingDownloadArtifactsForClear(
        context: Context,
        protectedReferences: Set<String> = emptySet(),
        onProgress: (completedItems: Int, totalItems: Int) -> Unit = { _, _ -> }
    ): StartupRecoveryResult = this.cleanupUnownedPendingDownloadArtifactsForClearImpl(context, protectedReferences, onProgress)
    /** 按入队时记录的根目录回放所有持久终态清理 */
    internal suspend fun cleanupPersistedTerminalTemporaryWriteArtifacts(
        context: Context
    ): StartupRecoveryResult = this.cleanupPersistedTerminalTemporaryWriteArtifactsImpl(context)
    /** 清理一条终态记录，只消费后端操作后重新校验过的目录代次
     * 收尾回调可能在列举父目录时再次加入同一目标，此时保留新代次并交给下一轮恢复
     */
    internal fun matchesTerminalTemporaryWriteFinalizationIdentity(
        metadata: DownloadedAudioMetadata,
        preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
    ): Boolean {
        return this.matchesTerminalTemporaryWriteFinalizationIdentityImpl(metadata, preparation)
    }
    internal fun terminalTemporaryWriteCleanupTargets(
        entries: Collection<StoredEntry>,
        temporaryWriteIdentityByMetadataReference: Map<String, String?> = emptyMap()
    ): List<TerminalTemporaryWriteCleanupTarget> {
        return this.terminalTemporaryWriteCleanupTargetsImpl(entries, temporaryWriteIdentityByMetadataReference)
    }
    internal fun temporaryWriteOwnerNameForOperation(
        displayName: String,
        operationId: String?
    ): String? = temporaryWriteOwnerNameForIdentity(
        displayName = displayName,
        identity = operationId
    )
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
        return this.terminalTemporaryWriteCleanupFailureCountImpl(result, targetCount)
    }
    internal fun terminalTemporaryWriteCleanupExternalSignalRequiredCount(
        result: ManagedTemporaryWriteCleanupResult,
        targetCount: Int
    ): Int {
        return this.terminalTemporaryWriteCleanupExternalSignalRequiredCountImpl(result, targetCount)
    }
    internal fun pendingMetadataEntryNames(
        audioName: String,
        candidateNames: Collection<String>
    ): List<String> {
        return this.pendingMetadataEntryNamesImpl(audioName, candidateNames)
    }
    suspend fun usesDocumentTree(context: Context): Boolean = withContext(Dispatchers.IO) {
        // SAF 配置目录失去权限时必须停止下载, 不能隐式切换到私有目录
        resolveUsesDocumentTreeSafely(settings.configuredDirectoryUri) {
            resolveRootBlocking(context) is RootHandle.TreeRoot
        }
    }
    /**
     * provider 短暂异常时仍按 SAF 路径处理, 避免侧载流程误切到私有目录
     */
    internal fun resolveUsesDocumentTreeSafely(
        configuredDirectoryUri: String?,
        resolveRoot: () -> Boolean
    ): Boolean {
        return this.resolveUsesDocumentTreeSafelyImpl(configuredDirectoryUri, resolveRoot)
    }
    /**
     * 存储 root 是否仍可解析: 用于区分"确实没有下载"与"SAF 列举瞬时失败"
     * 未配置自定义 SAF 目录时使用应用私有目录, 始终可解析; 配置了 SAF 树目录时
     * 只有该树仍可解析才算可用 (树不可解析时不允许回退到其他目录)
     */
    internal suspend fun probeStorageRoot(
        context: Context
    ): ManagedDownloadRootProbeResult = this.probeStorageRootImpl(context)
    suspend fun isStorageRootResolvable(context: Context): Boolean {
        return probeStorageRoot(context) is ManagedDownloadRootProbeResult.Accessible
    }
    /** 返回当前配置目录的稳定根标识
     * 用来区分换目录后的真实空结果和同目录的瞬时列举失败
     * 等价 URI 会归一到同一身份，因此重新选择同一目录仍视为同一根
     */
    suspend fun currentSnapshotRootKey(context: Context): String = withContext(Dispatchers.IO) {
        resolveSnapshotCacheKey(context)
    }
    /** 返回指定操作源目录的稳定根标识, 避免迁移后当前配置遮蔽旧源目录 */
    internal suspend fun snapshotRootKeyForOperation(
        context: Context,
        directoryUri: String? = null,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): String? = this.snapshotRootKeyForOperationImpl(context, directoryUri, useDefaultRootWhenDirectoryUriMissing)
    suspend fun readText(context: Context, reference: String): String? = withContext(Dispatchers.IO) {
        readTextInternal(context, reference)
    }
    suspend fun hasReadableContent(
        context: Context,
        entry: StoredEntry
    ): Boolean = this.hasReadableContentImpl(context, entry)
    /**
     * 兼容旧业务调用，真正的删除只在策略验证后进入 typed 执行器
     */
    suspend fun deleteReferences(
        context: Context,
        references: Collection<String?>,
        onDeleteAttemptFinished: (String, Boolean) -> Unit = { _, _ -> }
    ): Set<String> = this.deleteReferencesImpl(context, references, onDeleteAttemptFinished)
    internal suspend fun deleteFullLibraryReferences(
        context: Context,
        references: Collection<String?>,
        onDeleteAttemptFinished: (String, Boolean) -> Unit = { _, _ -> }
    ): Set<String> = this.deleteFullLibraryReferencesImpl(
        context,
        references,
        onDeleteAttemptFinished
    )
    suspend fun saveAudioFromTemp(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?,
        expectedSizeBytes: Long? = null,
        transferSizeVerified: Boolean = false,
        seedMetadataJson: String? = null,
        pendingMetadataJson: String? = null
    ): StoredEntry = this.saveAudioFromTempImpl(context, tempFile, fileName, mimeType, expectedSizeBytes, transferSizeVerified, seedMetadataJson, pendingMetadataJson)
    internal suspend fun promotePendingFileAudio(
        root: File,
        pendingName: String,
        finalName: String,
        pendingRoot: File = root
    ): File? {
        return this.promotePendingFileAudioImpl(root, pendingName, finalName, pendingRoot)
    }
    internal suspend fun demotePublishedFileAudio(
        root: File,
        publishedName: String,
        pendingName: String,
        pendingRoot: File = root
    ): File? {
        return this.demotePublishedFileAudioImpl(root, publishedName, pendingName, pendingRoot)
    }
    internal fun resolvePendingTreeAudioPromotionExpectedSize(
        reportedSizeBytes: Long?,
        countedSizeBytes: Long?
    ): Long? {
        return countedSizeBytes?.takeIf { size -> size > 0L }
            ?: reportedSizeBytes?.takeIf { size -> size > 0L }
    }

    internal fun canCreateTreePromotionTargetWithoutReplacing(
        enumerationComplete: Boolean,
        existingNames: Collection<String>,
        targetName: String
    ): Boolean {
        return this.canCreateTreePromotionTargetWithoutReplacingImpl(enumerationComplete, existingNames, targetName)
    }

    fun commitCoverBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String?
    ): StoredEntry? {
        return this.commitCoverBytesImpl(context, bytes, fileName, mimeType)
    }
    /** 封面 source 已落到临时流后直接提交，避免在 storage 层再次复制整块内容 */
    internal suspend fun persistRemoteCoverStream(
        context: Context,
        input: InputStream,
        fileName: String,
        mimeType: String?,
        expectedSizeBytes: Long? = null
    ): String? = this.persistRemoteCoverStreamImpl(context, input, fileName, mimeType, expectedSizeBytes)
    fun overwriteLyric(context: Context, fileName: String, content: String): String? {
        return saveLyricTextBlocking(context, fileName, content)
    }
    fun findLyricLocation(
        context: Context,
        songId: Long,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): String? {
        return this.findLyricLocationImpl(context, songId, candidateBaseNames, translated)
    }
    fun writeLyrics(
        context: Context,
        songId: Long,
        baseName: String,
        content: String,
        translated: Boolean
    ): String? {
        return this.writeLyricsImpl(context, songId, baseName, content, translated)
    }
    internal fun writeRomanizedLyrics(
        context: Context,
        songId: Long,
        baseName: String,
        content: String
    ): String? {
        return this.writeRomanizedLyricsImpl(context, songId, baseName, content)
    }
    fun readLyrics(context: Context, song: SongItem, translated: Boolean): String? {
        val lyrics = readLyricsBundle(context, song)
        return if (translated) lyrics.translatedLyric else lyrics.lyric
    }
    fun readRomanizedLyrics(context: Context, song: SongItem): String? {
        return readLyricsBundle(context, song).romanizedLyric
    }
    fun readLyricsBundle(context: Context, song: SongItem): DownloadedLyricsBundle {
        return this.readLyricsBundleImpl(context, song)
    }
    /**
     * 读取下载歌词的首屏快路径, 优先恢复持久化索引以避免 SAF 全目录枚举
     */
    internal fun readLyricsBundleFast(
        context: Context,
        song: SongItem,
        allowColdSafProbe: Boolean = true
    ): DownloadedLyricsBundle {
        return this.readLyricsBundleFastImpl(context, song, allowColdSafProbe)
    }
    internal fun managedDownloadTreeUri(rawReference: String?): Uri? {
        return managedDownloadTreeReference(rawReference)
            ?.let { treeReference -> runCatching { treeReference.toUri() }.getOrNull() }
    }
    internal fun managedDownloadTreeReference(rawReference: String?): String? {
        return this.managedDownloadTreeReferenceImpl(rawReference)
    }
    internal fun resolveLyricsBundleFromReferences(
        metadata: DownloadedAudioMetadata?,
        originalReference: String?,
        translatedReference: String?,
        romanizedReference: String?,
        readText: (String) -> String?
    ): DownloadedLyricsBundle {
        return this.resolveLyricsBundleFromReferencesImpl(metadata, originalReference, translatedReference, romanizedReference, readText)
    }
    internal fun resolveLyricsBundleFromEntries(
        song: SongItem,
        candidateBaseNames: List<String> = buildManagedLyricBaseNames(song),
        lyricEntries: Collection<StoredEntry>,
        readText: (String) -> String?
    ): DownloadedLyricsBundle {
        return this.resolveLyricsBundleFromEntriesImpl(song, candidateBaseNames, lyricEntries, readText)
    }
    internal fun resolveDownloadedLyricsBundle(
        context: Context,
        song: SongItem,
        snapshot: DownloadLibrarySnapshot,
        readText: (String) -> String?,
        exists: (Context, String?) -> Boolean
    ): DownloadedLyricsBundle {
        return this.resolveDownloadedLyricsBundleImpl(context, song, snapshot, readText, exists)
    }
    internal fun findRomanizedLyricLocation(
        context: Context,
        songId: Long,
        candidateBaseNames: List<String>
    ): String? {
        return this.findRomanizedLyricLocationImpl(context, songId, candidateBaseNames)
    }
    fun toPlayableUri(reference: String?): String? {
        if (reference.isNullOrBlank()) return null
        return if (reference.startsWith("/")) {
            Uri.fromFile(File(reference)).toString()
        } else {
            reference
        }
    }
    /**
     * 为旧版快照恢复一个可播放的本地引用
     *
     * 旧 JSON/Room 行可能没有 mediaUri，或把已经失效的远端地址写进了
     * mediaUri。reference 仍是存储后端的权威地址，但只有本地路径和
     * content/file URI 可以进入播放链，避免把网络地址误当成下载文件
     */
    fun resolveStoredEntryPlaybackUri(
        entry: StoredEntry,
        allowPending: Boolean = false
    ): String? {
        return this.resolveStoredEntryPlaybackUriImpl(entry, allowPending)
    }
    suspend fun findCoverReference(context: Context, audio: StoredEntry): String? = withContext(Dispatchers.IO) {
        val snapshot = resolveSnapshotForIndexedLookup(context)
            ?: buildDownloadLibrarySnapshotBlocking(context)
        ManagedDownloadCoverLookup.findCoverReference(snapshot, audio)
    }
    /**
     * 进程重启后的活动迁移优先复用已持久化的源清单，避免再次枚举整个 SAF
     * 树。每个条目仍会在复制阶段通过后端读取，源删除和权限变化不会被静默吞掉
     */
    internal data class RestoredMigrationManifest(
        val entries: List<ManagedMigrationEntry>
    )
    internal data class ValidatedMigrationCopyReceipts(
        val receipts: Map<String, ManagedMigrationCopyReceipt>,
        val sourceEntriesByReference: Map<String, StoredEntry>
    )
    internal fun ManagedDownloadTreeDirectories.ManagedMigrationEntriesRefresh.entryFor(
        subdirectory: String?,
        expected: StoredEntry
    ): StoredEntry? {
        val candidates = when (subdirectory) {
            null -> rootEntries
            COVER_SUBDIRECTORY -> coverEntries
            LYRIC_SUBDIRECTORY -> lyricEntries
            else -> emptyList()
        }
        return candidates.firstOrNull { candidate ->
            sameManagedMigrationStoredEntryIdentity(expected, candidate) ||
                expected.reference == candidate.reference ||
                expected.mediaUri == candidate.mediaUri
        }
    }
    internal suspend fun restoreManagedMigrationEntriesFromJournal(
        root: RootHandle,
        journal: ManagedMigrationReplacementJournal,
        persistedTargetNames: Map<String, String>
    ): RestoredMigrationManifest? = this.restoreManagedMigrationEntriesFromJournalImpl(root, journal, persistedTargetNames)
    /** 进程终止后根据持久复制凭据恢复目标索引
     * 直接探测完整时无需遍历目录，凭据不确定时退回有界快照
     */
    internal suspend fun buildMigrationTargetIndexFromReceipts(
        context: Context,
        targetRoot: RootHandle,
        entries: List<ManagedMigrationEntry>,
        persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt>,
        persistedTargetNames: Map<String, String>
    ): ManagedMigrationTargetIndex? = this.buildMigrationTargetIndexFromReceiptsImpl(context, targetRoot, entries, persistedCopyReceipts, persistedTargetNames)
    internal suspend fun validateMigrationSourceCopyReceipts(
        context: Context,
        sourceRoot: RootHandle,
        entries: List<ManagedMigrationEntry>,
        persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt>,
        preferDirectStats: Boolean
    ): ValidatedMigrationCopyReceipts = this.validateMigrationSourceCopyReceiptsImpl(context, sourceRoot, entries, persistedCopyReceipts, preferDirectStats)
    internal fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): List<String> {
        return this.buildLyricCandidateNamesImpl(songId, candidateBaseNames, translated)
    }
    internal fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        kind: LyricKind
    ): List<String> {
        return this.buildLyricCandidateNamesImpl(songId, candidateBaseNames, kind)
    }

    /**
     * 并行读取互不相关的 metadata，单个 sidecar 暂时不可读时保留其余音频结果
     */
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
        return this.applySidecarRefreshToSnapshotImpl(snapshot, coverEntries, lyricEntries)
    }
    internal fun applyReferenceDeletesToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        references: Set<String>
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.applyReferenceDeletes(snapshot, references)
    }
    /**
     * 迁移释放目录栅栏后收敛旧版本和新版本的待提交凭据
     *
     * 该入口只在迁移 Worker 完成后调用，避免把迁移期间的临时音频误删
     */
    internal suspend fun reconcilePendingArtifactsAfterStorageMutation(
        context: Context
    ): StartupRecoveryResult = this.reconcilePendingArtifactsAfterStorageMutationImpl(context)
    internal fun cleanupUnfinalizedDownloadArtifacts(context: Context): StartupRecoveryResult {
        return this.cleanupUnfinalizedDownloadArtifactsImpl(context)
    }
    internal fun mergeTreeChildNamesAfterRefresh(
        refreshedNames: Collection<String>,
        cachedNames: Collection<String>?,
        cachedNamesComplete: Boolean?,
        refreshedComplete: Boolean
    ): TreeChildNameRefresh {
        return this.mergeTreeChildNamesAfterRefreshImpl(refreshedNames, cachedNames, cachedNamesComplete, refreshedComplete)
    }
    internal fun resolveTreeStoredName(actualName: String?, expectedName: String): String {
        return ManagedDownloadTreeNaming.resolveTreeStoredName(actualName, expectedName)
    }
    internal fun documentCreateMimeType(desiredName: String, mimeType: String): String {
        return ManagedDownloadTreeNaming.documentCreateMimeType(desiredName, mimeType)
    }
    internal fun verifiedCommittedByteCount(
        expectedSizeBytes: Long,
        reportedSizeBytes: Long?,
        countedSizeBytes: Long?,
        toleranceBytes: Long = 0L
    ): Long? {
        return this.verifiedCommittedByteCountImpl(expectedSizeBytes, reportedSizeBytes, countedSizeBytes, toleranceBytes)
    }
    internal fun shouldRejectTransferSize(
        expectedSizeBytes: Long?,
        actualSizeBytes: Long,
        transferSizeVerified: Boolean
    ): Boolean {
        return this.shouldRejectTransferSizeImpl(expectedSizeBytes, actualSizeBytes, transferSizeVerified)
    }
    internal fun buildMigrationNamePlan(
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
    internal fun StorageStat.toStoredEntryForBackend(fileRoot: File?): StoredEntry {
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
            sizeKnown = sizeBytes != null,
            isDirectory = isDirectory
        )
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
        return this.readStoredEntryForMigrationImpl(context, entry, block)
    }
    internal fun createUniqueName(existingNames: Set<String>, desiredName: String): String {
        return ManagedDownloadStorageNaming.createUniqueName(existingNames, desiredName)
    }
    internal data class BackendReference(
        val backend: StorageBackend,
        val reference: StorageReference
    )
    internal suspend fun deleteEnumeratedMigrationReferences(
        context: Context,
        references: Collection<TrustedManagedRef>,
        root: RootHandle,
        trustedReferencesSnapshot: Set<TrustedManagedRef>? = null,
        onDeleteStarted: (TrustedManagedRef) -> Unit = {},
        onDeleteFinished: (TrustedManagedRef) -> Unit = {}
    ): Map<TrustedManagedRef, StorageMutationResult> = this.deleteEnumeratedMigrationReferencesImpl(context, references, root, trustedReferencesSnapshot, onDeleteStarted, onDeleteFinished)
    internal fun Throwable.toMigrationDeletionResult(): StorageMutationResult {
        return if (this is SecurityException) {
            StorageMutationResult.PermissionLost
        } else {
            StorageMutationResult.ProviderFailure(this)
        }
    }
    internal fun isReferenceAllowedForManagedDelete(
        reference: String,
        trustedReferences: Set<String>,
        managedFileRoots: Collection<String>,
        managedTreeRoots: Collection<String>
    ): Boolean {
        return this.isReferenceAllowedForManagedDeleteImpl(reference, trustedReferences, managedFileRoots, managedTreeRoots)
    }
    internal fun StorageMutationResult.isConfirmedStorageMutation(): Boolean {
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
    internal fun File.toStoredEntry(): StoredEntry {
        return ManagedDownloadStoredEntryMapper.fromFile(this)
    }
    internal fun QueriedTreeChild.toStoredEntry(): StoredEntry {
        return ManagedDownloadStoredEntryMapper.fromTreeChild(this)
    }
    internal fun storedEntryFromTreeChild(
        name: String,
        documentReference: String,
        sizeBytes: Long,
        lastModifiedMs: Long,
        isDirectory: Boolean
    ): StoredEntry {
        return this.storedEntryFromTreeChildImpl(name, documentReference, sizeBytes, lastModifiedMs, isDirectory)
    }
    internal fun DocumentFile.toStoredEntry(
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
