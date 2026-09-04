package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadParsedMetadataEntry
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadPendingArtifactCleanupPlanner
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadUnfinalizedCleanupPlanner
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_TEMPORARY_DIR_NAME
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
import moe.ouom.neriplayer.core.download.storage.commit.sameManagedMigrationStoredEntryIdentity
import moe.ouom.neriplayer.core.download.storage.commit.sameMigrationReplacementBackupIdentity
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
import moe.ouom.neriplayer.core.download.metadata.resolveCreatedAtConfidence
import moe.ouom.neriplayer.core.download.storage.migration.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationCopyWorker
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationProgressSession
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationCopyResult
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationFinalizer
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationNamePlan
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationNamePlanner
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationPolicy
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationCheckpointStore
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.download.storage.migration.migrationProgressCheckpointIds
import moe.ouom.neriplayer.core.download.storage.migration.selectMigrationProgressCheckpoint
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntryReader
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetIndexBuilder
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationCleanupResult
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationMetadataRewriteResult
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationCleanupReceipt
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationCopyReceipt
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan
import moe.ouom.neriplayer.core.download.storage.migration.toCopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationSourceEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationVerificationResult
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.migration.canReuseMigrationTargetDigest
import moe.ouom.neriplayer.core.download.storage.migration.committedMigrationReceiptsMeetAudioMinimum
import moe.ouom.neriplayer.core.download.storage.migration.collectReusableMigrationCopyPairs
import moe.ouom.neriplayer.core.download.storage.migration.isCurrentMigrationSourceFingerprint
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationTargetNames
import moe.ouom.neriplayer.core.download.storage.migration.requireSuccessfulMigrationCopies
import moe.ouom.neriplayer.core.download.storage.migration.resolveMinimumMigrationAudioCount
import moe.ouom.neriplayer.core.download.storage.migration.reconcileMigrationSourceManifest
import moe.ouom.neriplayer.core.download.storage.migration.removeDeletedMigrationSources
import moe.ouom.neriplayer.core.download.storage.migration.planDeletedSourceCopyReceiptRecovery
import moe.ouom.neriplayer.core.download.storage.migration.selectOrphanedMigrationReplacementPlans
import moe.ouom.neriplayer.core.download.storage.migration.persistedMigrationJournalTargetNames
import moe.ouom.neriplayer.core.download.storage.migration.shouldRetryActiveMigrationJournal
import moe.ouom.neriplayer.core.download.storage.migration.shouldUseDirectMigrationReceiptValidation
import moe.ouom.neriplayer.core.download.storage.migration.shouldBlockStartupForMigrationRecovery
import moe.ouom.neriplayer.core.download.storage.migration.selectActiveMigrationWorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.hasCompleteMigrationCleanupReceipts
import moe.ouom.neriplayer.core.download.storage.migration.isSafeMigrationPlanName
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationCleanupReceipts
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationCopyReceipts
import moe.ouom.neriplayer.core.download.storage.migration.mergeMigrationProgressFloor
import moe.ouom.neriplayer.core.download.storage.migration.migrationSourceEntryCount
import moe.ouom.neriplayer.core.download.storage.migration.sha256MigrationContent
import moe.ouom.neriplayer.core.download.storage.migration.upgradeLegacyMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.isMigrationDocumentIdWithinTree
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
import moe.ouom.neriplayer.data.model.remoteDownloadIdentityOrNull
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
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
    private const val METADATA_SCAN_PARALLELISM = 4
    private val KNOWN_TRANSIENT_PENDING_ARTIFACT_STATES = setOf(
        "PENDING_QUEUE",
        "QUEUED",
        "DOWNLOADING",
        "VERIFYING",
        "RETRYABLE",
        "FAILED_RETRYABLE",
        "CANCELLED"
    )
    private const val TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS = 3

    private val snapshotBuildLock = Any()
    private val sidecarRefreshLock = Any()
    private val snapshotWarmupLock = Any()
    private val metadataScanDispatcher =
        Dispatchers.IO.limitedParallelism(METADATA_SCAN_PARALLELISM)
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
    private val pendingAudioWriteNames = ManagedDownloadPendingAudioWriteNames()
    private val migrationCleanupTrustLock = Mutex()

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

    private val migrationProgressSession = ManagedDownloadMigrationProgressSession()
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
    private val _lyricsRefreshVersion = MutableStateFlow(0L)
    internal val lyricsRefreshVersion: StateFlow<Long> = _lyricsRefreshVersion

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        // 先恢复最后一份持久快照，WorkManager 启动时界面就能显示真实迁移位置
        restorePersistedMigrationProgress(appContext, includeJournal = false)
        snapshotScope.launch {
            restorePersistedMigrationProgress(appContext, includeJournal = true)
            AppStartupWorkGate.awaitInteractiveContentOrTimeout()
            val migrationRecoveryPending = hasPendingStartupMigrationRecovery(appContext)
            val result = if (migrationRecoveryPending) {
                // 迁移 worker 必须独占源和目标目录。这里不创建默认目录、清理
                // 临时文件或预热快照，待迁移完成后的最终扫描再重新打开目录
                NPLogger.i(TAG, "检测到迁移恢复凭据，延后启动存储清理与快照预热")
                StartupRecoveryResult()
            } else {
                runCatching {
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
            }
            startupRecoveryResult = result
            if (result.hasRecoveredEntries) {
                _startupRecoveryResults.tryEmit(result)
            }
            if (!migrationRecoveryPending) {
                // 进程重启只清空内存索引, 保留持久化缓存供首屏预览和重建回退
                scheduleSnapshotWarmup(appContext)
            }
        }
    }

    private fun restorePersistedMigrationProgress(
        context: Context,
        includeJournal: Boolean
    ): MigrationProgress? {
        val checkpointStore = ManagedDownloadMigrationCheckpointStore(context)
        val request = runCatching { checkpointStore.readRequest() }.getOrNull()
        val journal = if (includeJournal) {
            runCatching { checkpointStore.readReplacementJournal() }.getOrNull()
        } else {
            null
        }
        if (!shouldBlockStartupForMigrationRecovery(request, journal)) {
            return null
        }
        val progress = selectMigrationProgressCheckpoint(
            checkpointIds = migrationProgressCheckpointIds(
                currentWorkId = "",
                inputCheckpointWorkId = null,
                persistedRequest = request,
                persistedJournal = journal
            ),
            readProgress = checkpointStore::readProgress
        ) ?: return null
        migrationProgressSession.restoreIfIdle(progress)
        return migrationProgressFlow.value
    }

    /**
     * 检查迁移凭据时不触碰任一存储根。读取失败也必须保守延后清理,
     * 避免进程恢复窗口中把半成品误当成可删除文件
     */
    private fun hasPendingStartupMigrationRecovery(context: Context): Boolean {
        return try {
            val checkpointStore = ManagedDownloadMigrationCheckpointStore(context)
            val request = checkpointStore.readRequest()
            val journal = checkpointStore.readReplacementJournal()
            val durableRecovery = shouldBlockStartupForMigrationRecovery(request, journal)
            if (durableRecovery) {
                return true
            }
            selectActiveMigrationWorkInfo(
                workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(ManagedDownloadMigrationWorker.WORK_NAME)
                    .get(),
                preferredWorkId = request?.workId
            ) != null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "迁移恢复凭据检查失败，延后启动存储清理: ${error.message}",
                error
            )
            true
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
        val failedCount: Int = 0,
        /** 已跨过核心提交边界的 pending，不属于清空失败或待重试项 */
        val protectedCount: Int = 0,
        /** 本轮已确认属于持久核心的引用，供清空快照复用，避免再次读取 SAF 元数据 */
        val protectedReferences: Set<String> = emptySet(),
        /** 仅返回实际删除失败所对应的歌曲，不能用所有输入 operation 代替 */
        val failedStableKeys: Set<String> = emptySet()
    ) {
        val hasRecoveredEntries: Boolean
            get() = cleanedCount > 0 || failedCount > 0
    }

    /** 只把实际删除失败的引用归属到歌曲，避免把整批 operation 误报成残留 */
    internal fun resolveFailedStableKeys(
        referencesByStableKey: Map<String, Set<String>>,
        failedReferences: Set<String>
    ): Set<String> {
        if (referencesByStableKey.isEmpty() || failedReferences.isEmpty()) {
            return emptySet()
        }
        return referencesByStableKey
            .asSequence()
            .filter { (_, references) -> references.any(failedReferences::contains) }
            .mapTo(linkedSetOf()) { (stableKey, _) -> stableKey }
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
        if (
            pendingEntries.isEmpty() ||
            metadataEntries.isEmpty() ||
            unreadableMetadataReferences.isEmpty()
        ) {
            return emptySet()
        }
        val unreadableAudioNames = metadataEntries
            .asSequence()
            .filter { it.reference in unreadableMetadataReferences }
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            }
            .toSet()
        if (unreadableAudioNames.isEmpty()) return emptySet()
        return pendingEntries
            .asSequence()
            .filter { entry ->
                val logicalName = if (entry.isPendingAudioWrite) {
                    entry.logicalName
                } else {
                    ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                }
                entry.reference in unreadableMetadataReferences ||
                    logicalName != null && logicalName in unreadableAudioNames
            }
            .mapTo(linkedSetOf(), StoredEntry::reference)
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
            get() = name.takeUnless { isPendingAudioWrite }
                ?: name.substringBefore(PENDING_AUDIO_WRITE_MARKER, name)

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

    private data class TemporaryDirectoryEntries(
        val entries: List<StoredEntry>,
        val isComplete: Boolean,
        val exists: Boolean
    )

    /** 返回应用自己的临时目录；读取路径不会因不存在目录而创建副作用 */
    private fun resolveTemporaryRoot(
        context: Context,
        root: RootHandle,
        create: Boolean
    ): RootHandle? {
        return when (root) {
            is RootHandle.FileRoot -> {
                val directory = File(root.dir, DOWNLOAD_TEMPORARY_DIR_NAME)
                if (!create && !directory.isDirectory) {
                    null
                } else {
                    if (directory.exists() && !directory.isDirectory) {
                        throw IOException("下载临时路径不是目录: ${directory.absolutePath}")
                    }
                    if (create && !directory.isDirectory &&
                        !directory.mkdirs() && !directory.isDirectory
                    ) {
                        throw IOException("无法创建下载临时目录: ${directory.absolutePath}")
                    }
                    if (directory.isDirectory) {
                        treeDirectories.ensureManagedMediaScanIsolation(
                            DOWNLOAD_TEMPORARY_DIR_NAME,
                            directory
                        )
                        RootHandle.FileRoot(directory)
                    } else {
                        null
                    }
                }
            }

            is RootHandle.TreeRoot -> {
                val directory = if (create) {
                    treeDirectories.findOrCreateDirectory(
                        context = context,
                        parent = root.tree,
                        displayName = DOWNLOAD_TEMPORARY_DIR_NAME
                    )
                } else {
                    findExistingTemporaryTreeDirectory(
                        context = context,
                        root = root,
                        forceRefresh = false
                    ).first
                }
                directory?.also {
                    treeDirectories.ensureManagedMediaScanIsolation(
                        context = context,
                        subdirectory = DOWNLOAD_TEMPORARY_DIR_NAME,
                        directory = it
                    )
                }?.let(RootHandle::TreeRoot)
            }
        }
    }

    private fun findExistingTemporaryTreeDirectory(
        context: Context,
        root: RootHandle.TreeRoot,
        forceRefresh: Boolean
    ): Pair<DocumentFile?, Boolean> {
        val refresh = if (forceRefresh) {
            treeChildRegistry.refreshTreeChildrenWithStatus(context, root.tree)
        } else {
            val cached = treeChildRegistry.cachedTreeChildrenIfFresh(
                parent = root.tree,
                maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
            )
            cached?.let {
                ManagedDownloadTreeChildRegistry.TreeChildrenRefresh(
                    children = it.toList(),
                    isComplete = true
                )
            } ?: treeChildRegistry.refreshTreeChildrenWithStatus(context, root.tree)
        }
        val child = refresh.children
            .asSequence()
            .filter(QueriedTreeChild::isDirectory)
            .filter { candidate ->
                ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                    candidate.name,
                    DOWNLOAD_TEMPORARY_DIR_NAME
                )
            }
            .sortedWith(
                compareBy<QueriedTreeChild>(
                    { if (it.name == DOWNLOAD_TEMPORARY_DIR_NAME) 0 else 1 },
                    { it.name }
                )
            )
            .firstOrNull()
        val directory = child?.let {
            treeChildRegistry.toDocumentFile(context, root.tree, it)
        }
        return directory to (refresh.isComplete && (child == null || directory != null))
    }

    private fun readTemporaryDirectoryEntries(
        context: Context,
        root: RootHandle,
        forceRefresh: Boolean,
        rootAlreadyRefreshed: Boolean = false
    ): TemporaryDirectoryEntries {
        return when (root) {
            is RootHandle.FileRoot -> {
                val directory = File(root.dir, DOWNLOAD_TEMPORARY_DIR_NAME)
                if (!directory.exists()) {
                    TemporaryDirectoryEntries(emptyList(), isComplete = true, exists = false)
                } else if (!directory.isDirectory) {
                    TemporaryDirectoryEntries(emptyList(), isComplete = false, exists = true)
                } else {
                    val children = directory.listFiles()
                    if (children == null) {
                        TemporaryDirectoryEntries(emptyList(), isComplete = false, exists = true)
                    } else {
                        TemporaryDirectoryEntries(
                            entries = children.map(ManagedDownloadStoredEntryMapper::fromFile),
                            isComplete = true,
                            exists = true
                        )
                    }
                }
            }

            is RootHandle.TreeRoot -> {
                val (directory, rootComplete) = findExistingTemporaryTreeDirectory(
                    context = context,
                    root = root,
                    // 调用方刚完成根目录列举，要求刷新时只重新读取 .tmp 子目录
                    forceRefresh = forceRefresh && !rootAlreadyRefreshed
                )
                if (directory == null) {
                    TemporaryDirectoryEntries(
                        entries = emptyList(),
                        isComplete = rootComplete,
                        exists = false
                    )
                } else {
                    val childRefresh = if (forceRefresh) {
                        treeChildRegistry.refreshTreeChildrenWithStatus(context, directory)
                    } else {
                        val cached = treeChildRegistry.cachedTreeChildrenIfFresh(
                            parent = directory,
                            maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                        )
                        cached?.let {
                            ManagedDownloadTreeChildRegistry.TreeChildrenRefresh(
                                children = it.toList(),
                                isComplete = true
                            )
                        } ?: treeChildRegistry.refreshTreeChildrenWithStatus(context, directory)
                    }
                    TemporaryDirectoryEntries(
                        entries = childRefresh.children
                            .map(ManagedDownloadStoredEntryMapper::fromTreeChild),
                        isComplete = rootComplete && childRefresh.isComplete,
                        exists = true
                    )
                }
            }
        }
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
        val rootEntriesComplete: Boolean = true,
        /** Covers/Lyrics 子项查询是否完整，false 时沿用旧侧载索引 */
        val sidecarEntriesComplete: Boolean = true,
        /** 已完成核心写入但尚未提升为正式文件名的音频 */
        val pendingAudioEntries: List<StoredEntry> = emptyList(),
        /** pending metadata 与正式 metadata 同名时, 为 pending 音频保留独立凭据 */
        val pendingMetadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap()
    )

    internal fun metadataForAudioEntry(
        snapshot: DownloadLibrarySnapshot?,
        audio: StoredEntry
    ): DownloadedAudioMetadata? {
        val metadataByAudioName = snapshot?.metadataByAudioName ?: return null
        if (audio.isPendingAudioWrite) {
            snapshot.pendingMetadataByAudioName[audio.logicalName]?.let { return it }
        }
        return metadataByAudioName[audio.name]
            ?: metadataByAudioName[audio.logicalName]
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
        val restorableMetadata: ManagedDownloadRestorableMetadata? = null,
        val createdAtConfidence: String? = null
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
    ): MigrationResult = withContext(Dispatchers.IO) {
        val normalizedProgressOwner = progressOwnerWorkId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val ownsProgressSession = if (normalizedProgressOwner != null) {
            migrationProgressSession.ensure(normalizedProgressOwner, persistedProgress)
        } else {
            migrationProgressSession.ensureLegacy(persistedProgress)
        }
        if (!ownsProgressSession) {
            throw ManagedDownloadMigrationException.transient(
                "迁移进度会话已被更新的任务接管，等待重试"
            )
        }
        try {
            if (areEquivalentDirectoryUris(fromDirectoryUri, toDirectoryUri)) {
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }

            val targetRoot = resolveRoot(context, toDirectoryUri)
                ?: throw ManagedDownloadMigrationException.permanent("目标下载目录不可用")
            val sourceRoot = resolveRoot(context, fromDirectoryUri)
            val persistedPhase = persistedReplacementJournal?.phase
            if (persistedPhase == ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED) {
                val committedJournal = requireNotNull(persistedReplacementJournal)
                val committedMinimumAudioCount = resolveMinimumMigrationAudioCount(
                    requestedMinimum = minimumSourceEntryCount,
                    discoveredSourceAudioCount = 0,
                    deletedSourceAudioCount = committedJournal.deletedSourceAudioCount
                )
                verifyCommittedMigrationReplacementJournal(
                    context = context,
                    targetRoot = targetRoot,
                    journal = committedJournal
                )
                verifyMigrationCleanupReceipts(
                    context = context,
                    targetRoot = targetRoot,
                    journal = committedJournal
                )
                if (!committedMigrationReceiptsMeetAudioMinimum(
                        committedJournal,
                        committedMinimumAudioCount
                    )
                ) {
                    verifyPreviouslyCommittedMigrationTarget(
                        context = context,
                        targetRoot = targetRoot,
                        toDirectoryUri = toDirectoryUri,
                        minimumAudioCount = committedMinimumAudioCount
                    )
                }
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
                    minimumAudioCount = resolveMinimumMigrationAudioCount(
                        requestedMinimum = minimumSourceEntryCount,
                        discoveredSourceAudioCount = 0,
                        deletedSourceAudioCount = persistedReplacementJournal
                            ?.deletedSourceAudioCount
                            ?: 0
                    )
                )
                onTargetVerified()
                return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
            }

            // 目标目录也可能留有上次中断的临时目录，迁移前一并整体移除
            discardMigrationTemporaryDirectory(context, targetRoot)
            // pending 音频和 metadata 不是可迁移的正式媒体，迁移前直接移除
            // 应用自己的 .tmp 目录，避免半成品把目录变更卡在重试状态
            if (pendingArtifactsPreflightVerified) {
                NPLogger.d(
                    TAG,
                    "迁移复用已持有目录租约内的 pending 预检结果，跳过重复枚举"
                )
            } else {
                requireMigrationSourceHasNoPendingArtifacts(
                    context = context,
                    sourceRoot = sourceRoot
                )
            }

            val journalTargetNames = mergePersistedMigrationTargetNames(
                buildList {
                    persistedReplacementJournal?.let { journal ->
                        add(persistedMigrationJournalTargetNames(journal))
                    }
                }
            )
            val restoredManifest = persistedReplacementJournal?.let { journal ->
                restoreManagedMigrationEntriesFromJournal(
                    root = sourceRoot,
                    journal = journal,
                    persistedTargetNames = mergePersistedMigrationTargetNames(
                        listOf(persistedTargetNames, journalTargetNames)
                    )
                )
            }
            val persistedManifestEntries = restoredManifest?.entries
            val usePersistedManifest = persistedManifestEntries != null
            val entries = persistedManifestEntries ?: collectManagedMigrationEntries(
                context = context,
                root = sourceRoot,
                allowMetadataLessAudio = shouldIndexMetadataLessAudio(fromDirectoryUri)
            )
            val discoveredSourceAudioCount = entries.count { migrationEntry ->
                migrationEntry.subdirectory == null &&
                    migrationEntry.entry.extension in audioExtensions
            }
            var minimumAudioCount = resolveMinimumMigrationAudioCount(
                requestedMinimum = minimumSourceEntryCount,
                discoveredSourceAudioCount = discoveredSourceAudioCount,
                deletedSourceAudioCount = persistedReplacementJournal
                    ?.deletedSourceAudioCount
                    ?: 0
            )
            onSourceAudioCountResolved(minimumAudioCount)
            val upgradedPersistedJournal = persistedReplacementJournal?.let { journal ->
                upgradeLegacyMigrationReplacementJournal(
                    journal = journal,
                    sourceEntryCount = entries.size
                )
            }
            // 只有完整的 Provider 列举才能确认源文件消失，清单快路径把判断交给
            // 复制 Worker，那里可以区分文件缺失和权限错误
            val deletedSourceRecoveryPlan = if (
                !usePersistedManifest &&
                upgradedPersistedJournal != null &&
                persistedCopyReceipts.isNotEmpty()
            ) {
                planDeletedSourceCopyReceiptRecovery(
                    journal = upgradedPersistedJournal,
                    currentSourceReferences = entries.map { it.entry.reference },
                    copyReceipts = persistedCopyReceipts
                )
            } else {
                null
            }
            var persistedJournalForAttempt = upgradedPersistedJournal?.let { journal ->
                reconcileMigrationSourceManifest(
                    journal = deletedSourceRecoveryPlan?.journal ?: journal,
                    currentEntries = entries
                )
            }
            if (deletedSourceRecoveryPlan != null && persistedJournalForAttempt != null) {
                persistedJournalForAttempt = applyDeletedSourceCopyReceiptRecoveryPlan(
                    context = context,
                    targetRoot = targetRoot,
                    journal = persistedJournalForAttempt,
                    plan = deletedSourceRecoveryPlan
                )
            }
            if (persistedJournalForAttempt != null &&
                persistedJournalForAttempt != persistedReplacementJournal
            ) {
                onReplacementJournalUpdated(persistedJournalForAttempt)
                deletedSourceRecoveryPlan?.let { plan ->
                    (plan.promoteCandidates + plan.rollbackCandidates + plan.preserveCandidates)
                        .map(ManagedMigrationCopyReceipt::sourceReference)
                        .distinct()
                        .forEach { sourceReference ->
                            onCopyReceiptInvalidated(sourceReference)
                        }
                }
                minimumAudioCount = resolveMinimumMigrationAudioCount(
                    requestedMinimum = minimumSourceEntryCount,
                    discoveredSourceAudioCount = discoveredSourceAudioCount,
                    deletedSourceAudioCount = persistedJournalForAttempt.deletedSourceAudioCount
                )
                onSourceAudioCountResolved(minimumAudioCount)
            }
            if (!usePersistedManifest && persistedJournalForAttempt != null) {
                val journalForAttempt = persistedJournalForAttempt
                val currentSourceReferences = entries.mapTo(HashSet()) { entry ->
                    entry.entry.reference.trim()
                }
                val journalReferences = buildSet {
                    journalForAttempt.sourceEntries.forEach { entry ->
                        add(entry.sourceReference.trim())
                    }
                    journalForAttempt.replacements.forEach { replacement ->
                        add(replacement.sourceReference.trim())
                    }
                }
                val missingSourceReferences = journalReferences.filterTo(linkedSetOf()) {
                    it.isNotBlank() && it !in currentSourceReferences
                }
                val orphanRecovery = recoverOrphanedMigrationReplacements(
                    context = context,
                    targetRoot = targetRoot,
                    journal = journalForAttempt,
                    missingSourceReferences = missingSourceReferences,
                    persistedCopyReceiptReferences = persistedCopyReceipts.keys
                )
                if (orphanRecovery.resolvedReferences.isNotEmpty()) {
                    persistedJournalForAttempt = removeDeletedMigrationSources(
                        journal = journalForAttempt,
                        deletedReferences = orphanRecovery.resolvedReferences
                    )
                    onReplacementJournalUpdated(checkNotNull(persistedJournalForAttempt))
                }
                if (orphanRecovery.unresolvedReferences.isNotEmpty()) {
                    throw ManagedDownloadMigrationException.transient(
                        "迁移孤儿替换尚未收敛，保留事务等待恢复: " +
                            "count=${orphanRecovery.unresolvedReferences.size}"
                    )
                }
            }
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
                    sourceEntriesEmpty = entries.isEmpty(),
                    cleanupReceiptComplete = persistedJournalForAttempt?.let {
                        hasCompleteMigrationCleanupReceipts(it)
                    } == true,
                    sourceEntryCountKnown = persistedJournalForAttempt?.sourceEntryCountKnown
                        ?: true,
                    sourceEntriesIncomplete = false
                )
            ) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移源目录未返回完整文件列表，保留事务等待恢复"
                )
            }
            if (entries.isEmpty()) {
                val cleanupReceiptsReady = persistedJournalForAttempt?.let {
                    hasCompleteMigrationCleanupReceipts(it)
                } == true
                if (cleanupReceiptsReady) {
                    val journal = requireNotNull(persistedJournalForAttempt)
                    verifyCommittedMigrationReplacementJournal(
                        context = context,
                        targetRoot = targetRoot,
                        journal = journal
                    )
                    verifyMigrationCleanupReceipts(
                        context = context,
                        targetRoot = targetRoot,
                        journal = journal
                    )
                    if (!committedMigrationReceiptsMeetAudioMinimum(journal, minimumAudioCount)) {
                        verifyPreviouslyCommittedMigrationTarget(
                            context = context,
                            targetRoot = targetRoot,
                            toDirectoryUri = toDirectoryUri,
                            minimumAudioCount = minimumAudioCount
                        )
                    }
                    onReplacementJournalUpdated(
                        journal.copy(
                            phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED,
                            cleanupComplete = true
                        )
                    )
                }
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

            val receiptValidation = validateMigrationSourceCopyReceipts(
                context = context,
                sourceRoot = checkNotNull(sourceRoot),
                entries = entries,
                persistedCopyReceipts = persistedCopyReceipts,
                preferDirectStats = shouldUseDirectMigrationReceiptValidation(
                    usePersistedManifest = usePersistedManifest,
                    persistedReceiptCount = persistedCopyReceipts.size
                )
            )
            val validatedCopyReceipts = receiptValidation.receipts
            // 只刷新一次清单指纹，让恢复进度和复制判断使用当前 Provider 事实
            val migrationEntries = entries.map { entry ->
                val current = receiptValidation.sourceEntriesByReference[entry.entry.reference]
                if (current == null) {
                    entry
                } else {
                    entry.copy(
                        entry = entry.entry.copy(
                            sizeBytes = current.sizeBytes,
                            lastModifiedMs = current.lastModifiedMs
                        )
                    )
                }
            }
            val metadataEntriesTotal = migrationEntries.count {
                ManagedDownloadTreeNaming.isMetadataName(it.entry.name)
            }
            val progressTracker = ManagedMigrationProgressReporter(
                totalFiles = migrationEntries.size,
                totalBytes = migrationEntries.sumOf { it.entry.sizeBytes.coerceAtLeast(0L) },
                metadataFilesTotal = metadataEntriesTotal,
                onProgress = { progress ->
                    migrationProgressSession.publish(normalizedProgressOwner, progress)
                },
                initialProgress = persistedProgress
            )
            progressTracker.startPreparing(migrationEntries.firstOrNull()?.entry?.name)
            val persistedNames = mergePersistedMigrationTargetNames(
                buildList {
                    add(persistedTargetNames)
                    persistedJournalForAttempt?.let { journal ->
                        add(persistedMigrationJournalTargetNames(journal))
                    }
                }
            )
            val receiptTargetIndex = if (usePersistedManifest) {
                buildMigrationTargetIndexFromReceipts(
                    context = context,
                    targetRoot = targetRoot,
                    entries = migrationEntries,
                    persistedCopyReceipts = persistedCopyReceipts,
                    persistedTargetNames = persistedNames
                )
            } else {
                null
            }
            val targetIndex = receiptTargetIndex ?: buildMigrationTargetIndex(
                context = context,
                targetRoot = targetRoot,
                skipMetadataParsing = usePersistedManifest
            )
            NPLogger.d(
                TAG,
                "migration_resume target_index=" +
                    (if (receiptTargetIndex != null) "receipts" else "provider_scan") +
                    " entries=${migrationEntries.size} receipts=${persistedCopyReceipts.size}"
            )
            val sourceMetadataByAudioName = migrationEntries
                .asSequence()
                .filter { entry -> entry.subdirectory == null && entry.metadata != null }
                .associate { entry -> entry.entry.name to requireNotNull(entry.metadata) }
            val generatedNamePlan = if (usePersistedManifest) {
                ManagedMigrationNamePlan(targetNamesByReference = emptyMap())
            } else {
                buildMigrationNamePlan(
                    entries = migrationEntries,
                    targetIndex = targetIndex,
                    sourceMetadataByAudioName = sourceMetadataByAudioName,
                    replacementBackupNamespace = persistedJournalForAttempt?.backupNamespace
                        ?: replacementJournalWorkId.takeIf(String::isNotBlank)
                        ?: "migration"
                )
            }
            var namePlan = ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
                entries = migrationEntries.map(ManagedMigrationEntry::toRef),
                targetIndex = targetIndex,
                generatedPlan = generatedNamePlan,
                persistedTargetNames = persistedNames
            ) ?: generatedNamePlan
            namePlan = mergePersistedReplacementPlan(
                fromDirectoryUri = fromDirectoryUri,
                toDirectoryUri = toDirectoryUri,
                generatedPlan = namePlan,
                persistedJournal = persistedJournalForAttempt
            )
            onTargetNamePlanResolved(namePlan.targetNamesByReference)
            val freshSourceManifestByReference = migrationEntries
                .filter { entry ->
                    entry.entry.reference in receiptValidation.sourceEntriesByReference
                }
                .associate { entry ->
                    entry.entry.reference to ManagedMigrationSourceEntry(
                        sourceReference = entry.entry.reference,
                        sourceName = entry.entry.name,
                        sourceSubdirectory = entry.subdirectory,
                        sizeBytes = entry.entry.sizeBytes.coerceAtLeast(0L),
                        lastModifiedMs = entry.entry.lastModifiedMs.coerceAtLeast(0L),
                        logicalCreatedAtMs = entry.logicalCreatedAtMs(),
                        createdAtSource = entry.logicalCreatedAtSource(),
                        createdAtConfidence = entry.logicalCreatedAtConfidence()
                    )
                }
            val sourceManifest = persistedJournalForAttempt?.sourceEntries
                ?.takeIf { it.isNotEmpty() }
                ?.map { persisted ->
                    freshSourceManifestByReference[persisted.sourceReference]
                        ?: persisted
                }
                ?: migrationEntries.map { entry ->
                    ManagedMigrationSourceEntry(
                        sourceReference = entry.entry.reference,
                        sourceName = entry.entry.name,
                        sourceSubdirectory = entry.subdirectory,
                        sizeBytes = entry.entry.sizeBytes.coerceAtLeast(0L),
                        lastModifiedMs = entry.entry.lastModifiedMs.coerceAtLeast(0L),
                        logicalCreatedAtMs = entry.logicalCreatedAtMs(),
                        createdAtSource = entry.logicalCreatedAtSource(),
                        createdAtConfidence = entry.logicalCreatedAtConfidence()
                    )
                }
            var replacementJournal: ManagedMigrationReplacementJournal? =
                ManagedMigrationReplacementJournal(
                    workId = replacementJournalWorkId.ifBlank {
                        persistedJournalForAttempt?.workId.orEmpty()
                    },
                    fromDirectoryUri = fromDirectoryUri,
                    toDirectoryUri = toDirectoryUri,
                    backupNamespace = persistedJournalForAttempt?.backupNamespace
                        ?: replacementJournalWorkId.ifBlank { "migration" },
                    phase = ManagedMigrationReplacementJournalPhase.PLANNED,
                    replacements = namePlan.replacementPlansByReference.values.toList(),
                    targetNamesByReference = namePlan.targetNamesByReference,
                    cleanupReceipts = persistedJournalForAttempt?.cleanupReceipts.orEmpty(),
                    sourceEntryCount = migrationSourceEntryCount(
                        sourceEntries = sourceManifest,
                        cleanupReceipts = persistedJournalForAttempt
                            ?.cleanupReceipts
                            .orEmpty()
                    ),
                    sourceEntries = sourceManifest,
                    deletedSourceAudioCount = persistedJournalForAttempt
                        ?.deletedSourceAudioCount
                        ?: 0,
                    sourceEntriesComplete = true
                )
            replacementJournal?.let { onReplacementJournalUpdated(it) }

            val reusableCopyPairs = collectReusableMigrationCopyPairs(
                entries = migrationEntries,
                persistedCopyReceipts = validatedCopyReceipts,
                namePlan = namePlan,
                targetIndex = targetIndex
            )
            val reusableCopyEntries = reusableCopyPairs.map { pair -> pair.sourceEntry }
            val reusableCopiesByReference = reusableCopyPairs.associate { pair ->
                pair.sourceEntry.entry.reference to pair.receipt.toCopiedMigrationEntry(
                    original = pair.sourceEntry,
                    targetEntry = pair.targetEntry,
                    reusedFromReceipt = true
                )
            }
            progressTracker.seedCompletedCopies(reusableCopyEntries)
            val entriesToCopy = migrationEntries.filterNot { entry ->
                entry.entry.reference in reusableCopiesByReference
            }

            val copyResults = coroutineScope {
                val entriesChannel = kotlinx.coroutines.channels.Channel<ManagedMigrationEntry>(
                    capacity = migrationCopyParallelism(sourceRoot, targetRoot).coerceAtLeast(1)
                )
                val workers = List(
                    migrationCopyParallelism(sourceRoot, targetRoot)
                        .coerceAtLeast(1)
                        .coerceAtMost(entriesToCopy.size.coerceAtLeast(1))
                ) {
                    async(Dispatchers.IO) {
                        buildList {
                            for (migrationEntry in entriesChannel) {
                                val result = migrationCopyWorker.copyEntry(
                                    context = context,
                                    targetRoot = targetRoot,
                                    migrationEntry = migrationEntry,
                                    targetIndex = targetIndex,
                                    namePlan = namePlan,
                                    progressTracker = progressTracker,
                                    resumeReceipt = validatedCopyReceipts[
                                        migrationEntry.entry.reference
                                    ]
                                )
                                result.copiedEntry?.toCopyReceipt()?.let { receipt ->
                                    onCopyReceipt(receipt)
                                }
                                add(result)
                            }
                        }
                    }
                }
                entriesToCopy.forEach { entry ->
                    entriesChannel.send(entry)
                }
                entriesChannel.close()
                workers.awaitAll().flatten()
            }
            // 复制结果落盘后才能改写元数据或清理源文件，让目标成为权威副本
            onCopyReceiptsFlush()
            val currentCopyReceipts = copyResults.mapNotNull { result ->
                result.copiedEntry?.toCopyReceipt()
            }
            val copyReceiptsForRecovery = mergeMigrationCopyReceiptsForRecovery(
                persisted = persistedCopyReceipts,
                current = currentCopyReceipts
            )
            val deletedSourceReferences = copyResults
                .asSequence()
                .filter(ManagedMigrationCopyResult::sourceDeleted)
                .mapNotNull { result -> result.sourceReference.trim().takeIf(String::isNotBlank) }
                .toSet()
            if (deletedSourceReferences.isNotEmpty()) {
                val deletedSourceReceiptPlan = replacementJournal?.let { journal ->
                    planDeletedSourceCopyReceiptRecovery(
                        journal = journal,
                        currentSourceReferences = migrationEntries.asSequence()
                            .map { entry -> entry.entry.reference }
                            .filterNot(deletedSourceReferences::contains)
                            .toList(),
                        copyReceipts = copyReceiptsForRecovery
                    )
                }
                replacementJournal = deletedSourceReceiptPlan?.let { plan ->
                    applyDeletedSourceCopyReceiptRecoveryPlan(
                        context = context,
                        targetRoot = targetRoot,
                        journal = plan.journal,
                        plan = plan
                    )
                } ?: replacementJournal
                val orphanRecovery = replacementJournal?.let { journal ->
                    recoverOrphanedMigrationReplacements(
                        context = context,
                        targetRoot = targetRoot,
                        journal = journal,
                        missingSourceReferences = deletedSourceReferences,
                        persistedCopyReceiptReferences = copyReceiptsForRecovery.keys
                    )
                }
                val unresolvedOrphanReferences = orphanRecovery
                    ?.unresolvedReferences
                    .orEmpty()
                replacementJournal = replacementJournal?.let { journal ->
                    removeDeletedMigrationSources(
                        journal = journal,
                        deletedReferences = deletedSourceReferences
                            .filterNot(unresolvedOrphanReferences::contains)
                    )
                }
                replacementJournal?.let { onReplacementJournalUpdated(it) }
                deletedSourceReceiptPlan?.let { plan ->
                    (plan.promoteCandidates + plan.rollbackCandidates + plan.preserveCandidates)
                        .map(ManagedMigrationCopyReceipt::sourceReference)
                        .distinct()
                        .forEach { sourceReference ->
                            onCopyReceiptInvalidated(sourceReference)
                        }
                }
                if (unresolvedOrphanReferences.isNotEmpty()) {
                    throw ManagedDownloadMigrationException.transient(
                        "迁移孤儿替换尚未收敛，保留事务等待恢复: " +
                            "count=${unresolvedOrphanReferences.size}"
                    )
                }
                minimumAudioCount = resolveMinimumMigrationAudioCount(
                    requestedMinimum = minimumSourceEntryCount,
                    discoveredSourceAudioCount = discoveredSourceAudioCount,
                    deletedSourceAudioCount = replacementJournal
                        ?.deletedSourceAudioCount
                        ?: 0
                )
                onSourceAudioCountResolved(minimumAudioCount)
            }
            val newlyCopiedEntries = try {
                requireSuccessfulMigrationCopies(copyResults) { completedEntries ->
                    rollbackMigratedEntries(context, completedEntries, targetRoot)
                }
            } catch (error: ManagedDownloadMigrationException) {
                copyResults.mapNotNull(ManagedMigrationCopyResult::copiedEntry)
                    .map { copied -> copied.original.entry.reference }
                    .distinct()
                    .forEach { sourceReference -> onCopyReceiptInvalidated(sourceReference) }
                verifyCommittedTargetBeforeReturningFailure()
                throw error
            }
            val newlyCopiedByReference = newlyCopiedEntries.associateBy {
                it.original.entry.reference
            }
            var copiedEntries = migrationEntries.mapNotNull { entry ->
                reusableCopiesByReference[entry.entry.reference]
                    ?: newlyCopiedByReference[entry.entry.reference]
            }

            suspend fun invalidateCopyReceipts(entriesToInvalidate: Iterable<CopiedMigrationEntry>) {
                entriesToInvalidate
                    .map { copied -> copied.original.entry.reference }
                    .distinct()
                    .forEach { sourceReference -> onCopyReceiptInvalidated(sourceReference) }
            }

            val rewriteResult = rewriteMigratedMetadataReferences(
                context = context,
                targetRoot = targetRoot,
                copiedEntries = copiedEntries,
                progressTracker = progressTracker
            )
            copiedEntries = rewriteResult.copiedEntries
            if (rewriteResult.failedFiles > 0) {
                invalidateCopyReceipts(copiedEntries)
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
                progressTracker = progressTracker,
                onEntryVerified = { copied -> onCopyReceipt(copied.toCopyReceipt()) }
            )
            onCopyReceiptsFlush()
            if (verificationResult.failedFiles > 0) {
                invalidateCopyReceipts(copiedEntries)
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                verifyCommittedTargetBeforeReturningFailure()
                verificationResult.error?.let { error -> throw error }
                return@withContext MigrationResult(
                    movedFiles = 0,
                    skippedFiles = verificationResult.failedFiles
                )
            }
            copiedEntries = verificationResult.verifiedEntries

            replacementJournal = replacementJournal?.let { journal ->
                try {
                    val mergedJournal = journal.copy(
                        cleanupReceipts = mergePersistedMigrationCleanupReceipts(
                            persisted = journal.cleanupReceipts,
                            current = buildMigrationCleanupReceipts(
                                context = context,
                                copiedEntries = copiedEntries
                            )
                        )
                    )
                    if (!hasCompleteMigrationCleanupReceipts(mergedJournal)) {
                        throw ManagedDownloadMigrationException.transient(
                            "迁移清理凭据未覆盖全部源条目，保留源文件等待恢复"
                        )
                    }
                    mergedJournal
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    invalidateCopyReceipts(copiedEntries)
                    rollbackMigratedEntries(context, copiedEntries, targetRoot)
                    verifyCommittedTargetBeforeReturningFailure()
                    throw error
                }
            }
            replacementJournal?.let { onReplacementJournalUpdated(it) }

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

            val replacementBackupCleanup = cleanupMigrationReplacementBackups(
                context = context,
                targetRoot = targetRoot,
                copiedEntries = copiedEntries,
                progressTracker = progressTracker
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
                replacementJournal = replacementJournal?.let { journal ->
                    if (!hasCompleteMigrationCleanupReceipts(journal)) {
                        throw ManagedDownloadMigrationException.transient(
                            "迁移清理凭据未覆盖全部源条目，保留事务等待恢复"
                        )
                    }
                    journal.copy(
                        phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED,
                        cleanupComplete = true
                    )
                }
                replacementJournal?.let { onReplacementJournalUpdated(it) }
                try {
                    onTargetVerified()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "迁移目录已提交但目录设置回写失败，保留替换日志等待重试: " +
                            error.message,
                        error
                    )
                    throw ManagedDownloadMigrationException.transient(
                        "迁移目录已提交但目录设置回写失败，保留事务等待重试",
                        error
                    )
                }
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
            if (normalizedProgressOwner == null && ownsProgressSession) {
                migrationProgressSession.finish(null)
            }
        }
    }

    /** 完整扫描源目录后应用缺失源文件计划，更新日志落盘前保留复制凭据以支持幂等恢复 */
    private suspend fun applyDeletedSourceCopyReceiptRecoveryPlan(
        context: Context,
        targetRoot: RootHandle,
        journal: ManagedMigrationReplacementJournal,
        plan: ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan
    ): ManagedMigrationReplacementJournal {
        if (
            plan.promoteCandidates.isEmpty() &&
            plan.rollbackCandidates.isEmpty() &&
            plan.preserveCandidates.isEmpty()
        ) {
            return journal
        }

        fun sourceEntryFor(receipt: ManagedMigrationCopyReceipt): ManagedMigrationEntry {
            val reference = receipt.sourceReference.trim()
            return ManagedMigrationEntry(
                subdirectory = receipt.sourceSubdirectory,
                entry = StoredEntry(
                    name = receipt.sourceName,
                    reference = reference,
                    mediaUri = reference,
                    localFilePath = reference.takeIf { it.startsWith("/") },
                    sizeBytes = receipt.sourceSizeBytes.coerceAtLeast(0L),
                    lastModifiedMs = receipt.sourceLastModifiedMs.coerceAtLeast(0L),
                    isDirectory = false
                ),
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    createdAtMs = receipt.sourceLogicalCreatedAtMs,
                    createdAtSource = receipt.sourceCreatedAtSource,
                    createdAtConfidence = receipt.sourceCreatedAtConfidence
                )
            )
        }

        fun expectedTargetDigest(receipt: ManagedMigrationCopyReceipt): String? {
            receipt.verifiedTargetDigest
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
            // 复制凭据落盘后元数据可能已经改写，改写前的源摘要不能证明目标内容
            return receipt.sourceDigest
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.takeUnless { ManagedDownloadTreeNaming.isMetadataName(receipt.sourceName) }
        }

        val targetCandidatesBySubdirectory = mutableMapOf<
            String?,
            List<StoredEntry>?
        >()

        fun targetCandidates(subdirectory: String?): List<StoredEntry>? {
            if (targetCandidatesBySubdirectory.containsKey(subdirectory)) {
                return targetCandidatesBySubdirectory[subdirectory]
            }
            val refresh = try {
                if (subdirectory == null) {
                    treeDirectories.refreshRootEntries(
                        context = context,
                        root = targetRoot
                    ).let { result -> result.entries to result.isComplete }
                } else {
                    treeDirectories.refreshSubdirectoryEntries(
                        context = context,
                        root = targetRoot,
                        subdirectory = subdirectory
                    ).let { result -> result.entries to result.isComplete }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移恢复目标枚举暂时失败",
                    error
                )
            }
            val entries = refresh.first.takeIf { refresh.second }
            targetCandidatesBySubdirectory[subdirectory] = entries
            return entries
        }

        suspend fun resolveTarget(
            receipt: ManagedMigrationCopyReceipt
        ): MigrationRecoveryTargetResolution {
            val target = statMigrationTargetEntry(
                context = context,
                targetRoot = targetRoot,
                expected = receipt.targetEntry
            )
            if (target != null) {
                val expectedDigest = expectedTargetDigest(receipt)
                val actualDigest = expectedDigest?.let { readMigrationTargetDigest(context, target) }
                if (
                    sameManagedMigrationStoredEntryIdentity(receipt.targetEntry, target) &&
                    (expectedDigest == null || actualDigest.equals(expectedDigest, true))
                ) {
                    return MigrationRecoveryTargetResolution(
                        entry = target,
                        alreadyRestored = false
                    )
                }
                val backup = receipt.replacementBackup
                if (
                    backup != null &&
                    isRestoredMigrationReplacementTarget(
                        expectedTarget = receipt.targetEntry,
                        actualTarget = target,
                        replacementBackup = backup,
                        targetDigest = actualDigest,
                        expectedTargetDigest = expectedDigest
                    )
                ) {
                    return MigrationRecoveryTargetResolution(
                        entry = target,
                        alreadyRestored = true
                    )
                }
                throw ManagedDownloadMigrationException.targetChanged(
                    if (sameManagedMigrationStoredEntryIdentity(receipt.targetEntry, target)) {
                        "迁移恢复目标内容已发生变化: ${receipt.targetEntry.name}"
                    } else {
                        "迁移恢复目标文档已发生变化: ${receipt.targetEntry.name}"
                    }
                )
            }

            val backup = receipt.replacementBackup
                ?: return MigrationRecoveryTargetResolution(
                    entry = null,
                    alreadyRestored = false
                )
            val candidates = targetCandidates(receipt.sourceSubdirectory)
                ?: return MigrationRecoveryTargetResolution(
                    entry = null,
                    alreadyRestored = false
                )
            val matchingName = candidates.filter { candidate ->
                !candidate.isDirectory && candidate.name == receipt.targetEntry.name
            }
            if (matchingName.size > 1) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移恢复目标名称存在多个候选: ${receipt.targetEntry.name}"
                )
            }
            val candidate = matchingName.singleOrNull()
                ?: return MigrationRecoveryTargetResolution(
                    entry = null,
                    alreadyRestored = false
                )
            val expectedDigest = expectedTargetDigest(receipt)
            val actualDigest = expectedDigest?.let { readMigrationTargetDigest(context, candidate) }
            if (
                isRestoredMigrationReplacementTarget(
                    expectedTarget = receipt.targetEntry,
                    actualTarget = candidate,
                    replacementBackup = backup,
                    targetDigest = actualDigest,
                    expectedTargetDigest = expectedDigest
                )
            ) {
                return MigrationRecoveryTargetResolution(
                    entry = candidate,
                    alreadyRestored = true
                )
            }
            throw ManagedDownloadMigrationException.targetChanged(
                "迁移恢复目标文档身份无法确认: ${receipt.targetEntry.name}"
            )
        }

        plan.rollbackCandidates.forEach { receipt ->
            // 目标不存在时已经完成回滚，仍存在时用身份和摘要保护用户文件
            val targetResolution = resolveTarget(receipt)
            if (targetResolution.alreadyRestored) {
                return@forEach
            }
            val target = targetResolution.entry
            val copied = receipt.toCopiedMigrationEntry(
                original = sourceEntryFor(receipt),
                targetEntry = target ?: receipt.targetEntry
            )
            val failed = migrationFinalizer.rollbackMigratedEntries(
                context = context,
                copiedEntries = listOf(copied),
                targetRoot = targetRoot
            )
            if (failed > 0) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移缺失源目标回滚暂时失败: ${receipt.targetEntry.name}"
                )
            }
        }

        val cleanupReceipts = buildList {
            (plan.promoteCandidates + plan.preserveCandidates).forEach { receipt ->
                val target = resolveTarget(receipt).entry
                    ?: throw ManagedDownloadMigrationException.transient(
                        "迁移缺失源目标暂时不可用: ${receipt.targetEntry.name}"
                    )
                val targetDigest = readMigrationTargetDigest(context, target)
                add(
                    ManagedMigrationCleanupReceipt(
                        sourceReference = receipt.sourceReference,
                        sourceName = receipt.sourceName,
                        sourceSubdirectory = receipt.sourceSubdirectory,
                        targetEntry = target,
                        targetDigest = targetDigest,
                        sourceLogicalCreatedAtMs = receipt.sourceLogicalCreatedAtMs,
                        sourceCreatedAtSource = receipt.sourceCreatedAtSource,
                        sourceCreatedAtConfidence = receipt.sourceCreatedAtConfidence
                    )
                )
            }
        }
        val mergedCleanupReceipts = mergePersistedMigrationCleanupReceipts(
            persisted = journal.cleanupReceipts,
            current = cleanupReceipts
        )
        return journal.copy(
            cleanupReceipts = mergedCleanupReceipts,
            sourceEntryCount = migrationSourceEntryCount(
                sourceEntries = journal.sourceEntries,
                cleanupReceipts = mergedCleanupReceipts
            ),
            sourceEntriesComplete = true,
            deletedSourceAudioCount = maxOf(
                journal.deletedSourceAudioCount,
                plan.deletedSourceAudioCount
            )
        )
    }

    private data class OrphanMigrationReplacementRecoveryResult(
        val resolvedReferences: Set<String>,
        val unresolvedReferences: Set<String>
    )

    /**
     * 恢复替换凭据尚未落盘时被终止的目标文件
     *
     * 目标目录列举不完整时不做任何推断，避免把 Provider 暂时不可见误当成用户删除
     */
    private suspend fun recoverOrphanedMigrationReplacements(
        context: Context,
        targetRoot: RootHandle,
        journal: ManagedMigrationReplacementJournal,
        missingSourceReferences: Iterable<String>,
        persistedCopyReceiptReferences: Iterable<String>
    ): OrphanMigrationReplacementRecoveryResult {
        val plans = selectOrphanedMigrationReplacementPlans(
            journal = journal,
            missingSourceReferences = missingSourceReferences,
            persistedCopyReceiptReferences = persistedCopyReceiptReferences
        )
        if (plans.isEmpty()) {
            return OrphanMigrationReplacementRecoveryResult(
                resolvedReferences = emptySet(),
                unresolvedReferences = emptySet()
            )
        }
        val refresh = try {
            treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ManagedDownloadMigrationException.transient(
                "迁移孤儿替换目标枚举暂时失败",
                error
            )
        }
        if (!refresh.isComplete) {
            throw ManagedDownloadMigrationException.transient(
                "迁移孤儿替换目标枚举不完整，保留替换事务等待恢复"
            )
        }

        fun entriesFor(subdirectory: String?): List<StoredEntry> = when (subdirectory) {
            null -> refresh.rootEntries
            COVER_SUBDIRECTORY -> refresh.coverEntries
            LYRIC_SUBDIRECTORY -> refresh.lyricEntries
            else -> emptyList()
        }

        fun sourceEntryFor(plan: ManagedMigrationReplacementPlan): ManagedMigrationEntry {
            val source = journal.sourceEntries.firstOrNull { entry ->
                entry.sourceReference.trim() == plan.sourceReference.trim()
            }
            val sourceReference = plan.sourceReference.trim()
            return ManagedMigrationEntry(
                subdirectory = plan.subdirectory,
                entry = StoredEntry(
                    name = source?.sourceName ?: plan.targetName,
                    reference = sourceReference,
                    mediaUri = sourceReference,
                    localFilePath = sourceReference.takeIf { it.startsWith("/") },
                    sizeBytes = source?.sizeBytes?.coerceAtLeast(0L)
                        ?: plan.targetEntry.sizeBytes.coerceAtLeast(0L),
                    lastModifiedMs = source?.lastModifiedMs?.coerceAtLeast(0L)
                        ?: plan.targetEntry.lastModifiedMs.coerceAtLeast(0L),
                    isDirectory = false
                )
            )
        }

        fun targetIsUnchanged(
            expected: StoredEntry,
            actual: StoredEntry?
        ): Boolean {
            if (actual == null || !sameManagedMigrationStoredEntryIdentity(expected, actual)) {
                return false
            }
            return canReuseMigrationTargetDigest(
                expectedSizeBytes = expected.sizeBytes,
                actualSizeBytes = actual.sizeBytes,
                expectedLastModifiedMs = expected.lastModifiedMs,
                actualLastModifiedMs = actual.lastModifiedMs
            )
        }

        val resolved = linkedSetOf<String>()
        val unresolved = linkedSetOf<String>()
        plans.forEach { plan ->
            val candidates = entriesFor(plan.subdirectory).filter { entry ->
                !entry.isDirectory && entry.name == plan.backupName
            }
            if (candidates.size > 1) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移孤儿替换备份存在多个候选: ${plan.backupName}"
                )
            }
            val backup = candidates.singleOrNull()
            val target = entriesFor(plan.subdirectory).firstOrNull { entry ->
                !entry.isDirectory && entry.name == plan.targetName
            }
            when {
                backup != null && !sameMigrationReplacementBackupIdentity(
                    plan.targetEntry,
                    backup
                ) -> {
                    unresolved += plan.sourceReference
                    NPLogger.w(
                        TAG,
                        "迁移孤儿替换备份身份变化，保留事务: ${plan.backupName}"
                    )
                }

                backup != null -> {
                    val copied = CopiedMigrationEntry(
                        original = sourceEntryFor(plan),
                        copiedEntry = plan.targetEntry,
                        createdNew = false,
                        replacementBackup = backup,
                        sourceAuthoritative = true
                    )
                    if (commitWriter.restoreMigrationReplacement(
                            context = context,
                            root = targetRoot,
                            copied = copied
                        )
                    ) {
                        resolved += plan.sourceReference
                    } else {
                        unresolved += plan.sourceReference
                        NPLogger.w(
                            TAG,
                            "迁移孤儿替换备份恢复未确认，保留事务: ${plan.backupName}"
                        )
                    }
                }

                targetIsUnchanged(plan.targetEntry, target) -> {
                    // 目标仍是迁移前的同一文件，说明替换尚未开始
                    resolved += plan.sourceReference
                }

                else -> {
                    unresolved += plan.sourceReference
                    NPLogger.w(
                        TAG,
                        "迁移孤儿替换缺少可恢复目标，保留事务: ${plan.targetName}"
                    )
                }
            }
        }
        return OrphanMigrationReplacementRecoveryResult(
            resolvedReferences = resolved,
            unresolvedReferences = unresolved
        )
    }

    private suspend fun verifyCommittedMigrationReplacementJournal(
        context: Context,
        targetRoot: RootHandle,
        journal: ManagedMigrationReplacementJournal
    ) {
        persistedMigrationJournalTargetNames(journal)
        if (journal.replacements.isEmpty()) {
            if (!hasCompleteMigrationCleanupReceipts(journal)) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移事务没有可验证的目标清单"
                )
            }
            return
        }
        if (
            journal.phase == ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED &&
            journal.cleanupComplete &&
            hasCompleteMigrationCleanupReceipts(journal)
        ) {
            // 所有替换备份都确认删除后才写入 cleanupComplete，重新打开目标无需再列举目录
            return
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

    private suspend fun buildMigrationCleanupReceipts(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>
    ): List<ManagedMigrationCleanupReceipt> {
        return copiedEntries.map { copied ->
            val targetDigest = if (
                !ManagedDownloadTreeNaming.isMetadataName(copied.original.entry.name)
            ) {
                copied.verifiedTargetDigest?.takeIf(String::isNotBlank)
                    ?: copied.sourceDigest?.takeIf(String::isNotBlank)
                    ?: readMigrationTargetDigest(context, copied.copiedEntry)
            } else {
            // 元数据引用在计算源摘要后才会改写，因此只有最终目标字节才是权威内容
                readMigrationTargetDigest(context, copied.copiedEntry)
            }
            ManagedMigrationCleanupReceipt(
                sourceReference = copied.original.entry.reference,
                sourceName = copied.original.entry.name,
                sourceSubdirectory = copied.original.subdirectory,
                targetEntry = copied.copiedEntry,
                targetDigest = targetDigest,
                sourceLogicalCreatedAtMs = copied.original.logicalCreatedAtMs(),
                sourceCreatedAtSource = copied.original.logicalCreatedAtSource(),
                sourceCreatedAtConfidence = copied.original.logicalCreatedAtConfidence()
            )
        }
    }

    private suspend fun verifyMigrationCleanupReceipts(
        context: Context,
        targetRoot: RootHandle,
        journal: ManagedMigrationReplacementJournal
    ) = coroutineScope {
        if (journal.cleanupReceipts.isEmpty()) return@coroutineScope
        val verificationLimiter = Semaphore(
            migrationRewriteParallelism(targetRoot).coerceAtLeast(1)
        )
        journal.cleanupReceipts.map { receipt ->
            async(Dispatchers.IO) {
                verificationLimiter.withPermit {
                    val target = statMigrationTargetEntry(
                        context = context,
                        targetRoot = targetRoot,
                        expected = receipt.targetEntry
                    ) ?: throw ManagedDownloadMigrationException.transient(
                        "迁移清理凭据目标文件暂时不可用: ${receipt.targetEntry.name}"
                    )
                    if (!areEquivalentMigrationTargetIdentity(receipt.targetEntry, target)) {
                        throw ManagedDownloadMigrationException.targetChanged(
                            "迁移清理凭据目标文档已变化: ${receipt.targetEntry.name}"
                        )
                    }
                    val digest = if (
                        canReuseMigrationTargetDigest(
                            expectedSizeBytes = receipt.targetEntry.sizeBytes,
                            actualSizeBytes = target.sizeBytes,
                            expectedLastModifiedMs = receipt.targetEntry.lastModifiedMs,
                            actualLastModifiedMs = target.lastModifiedMs
                        )
                    ) {
                        receipt.targetDigest
                    } else {
                        readMigrationTargetDigest(context, target)
                    }
                    if (!digest.equals(receipt.targetDigest, ignoreCase = true)) {
                        throw ManagedDownloadMigrationException.targetChanged(
                            "迁移清理凭据目标内容已变化: ${receipt.targetEntry.name}"
                        )
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun statMigrationTargetEntry(
        context: Context,
        targetRoot: RootHandle,
        expected: StoredEntry
    ): StoredEntry? {
        val reference = expected.reference.trim()
        if (!isMigrationReferenceBoundToRoot(targetRoot, reference)) return null
        val backendTarget = backendReference(context, reference) ?: return null
        return when (val result = backendTarget.backend.stat(backendTarget.reference)) {
            is StorageLookupResult.Found -> {
                result.value.toStoredEntryForBackend(
                    (targetRoot as? RootHandle.FileRoot)?.dir
                ).takeUnless { entry ->
                    entry.isDirectory || entry.name != expected.name
                }
            }
            StorageLookupResult.Missing,
            StorageLookupResult.PermissionLost,
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.ProviderFailure,
            is StorageLookupResult.Unsupported -> null
        }
    }

    private suspend fun readMigrationTargetDigest(
        context: Context,
        entry: StoredEntry
    ): String {
        return when (val result = migrationEntryReader.read(context, entry) { input ->
            sha256MigrationContent(input)
        }) {
            is StorageLookupResult.Found -> result.value.getOrElse { error ->
                throw ManagedDownloadMigrationException.transient(
                    "迁移目标校验暂时失败: ${entry.name}",
                    error
                )
            }
            StorageLookupResult.Missing,
            StorageLookupResult.PermissionLost,
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported,
            is StorageLookupResult.ProviderFailure -> {
                throw ManagedDownloadMigrationException.transient(
                    "迁移目标文件暂时不可用: ${entry.name}"
                )
            }
        }
    }

    private fun areEquivalentMigrationTargetIdentity(
        expected: StoredEntry,
        actual: StoredEntry
    ): Boolean {
        if (
            expected.reference == actual.reference ||
            expected.mediaUri == actual.mediaUri ||
            expected.localFilePath != null &&
                expected.localFilePath == actual.localFilePath
        ) {
            return true
        }
        val expectedSafIdentity = migrationSafDocumentIdentity(expected.reference)
            ?: migrationSafDocumentIdentity(expected.mediaUri)
        val actualSafIdentity = migrationSafDocumentIdentity(actual.reference)
            ?: migrationSafDocumentIdentity(actual.mediaUri)
        return expectedSafIdentity != null && expectedSafIdentity == actualSafIdentity
    }

    /** 识别恢复日志落盘前已经改回目标名称的替换备份 */
    internal fun isRestoredMigrationReplacementTarget(
        expectedTarget: StoredEntry,
        actualTarget: StoredEntry,
        replacementBackup: StoredEntry,
        targetDigest: String? = null,
        expectedTargetDigest: String? = null
    ): Boolean {
        if (actualTarget.isDirectory || actualTarget.name != expectedTarget.name) {
            return false
        }
        val sameExpectedIdentity = sameManagedMigrationStoredEntryIdentity(
            expectedTarget,
            actualTarget
        )
        val sameBackupIdentity = sameManagedMigrationStoredEntryIdentity(
            replacementBackup,
            actualTarget
        )
        if (sameBackupIdentity && !sameExpectedIdentity) {
            return true
        }
        if (!sameExpectedIdentity) {
            return false
        }
        if (
            replacementBackup.localFilePath.isNullOrBlank() ||
            actualTarget.localFilePath.isNullOrBlank() ||
            replacementBackup.sizeBytes <= 0L ||
            actualTarget.sizeBytes <= 0L ||
            replacementBackup.sizeBytes != actualTarget.sizeBytes ||
            replacementBackup.lastModifiedMs <= 0L ||
            actualTarget.lastModifiedMs <= 0L ||
            replacementBackup.lastModifiedMs != actualTarget.lastModifiedMs
        ) {
            return false
        }
        return !targetDigest.isNullOrBlank() &&
            !expectedTargetDigest.isNullOrBlank() &&
            !targetDigest.equals(expectedTargetDigest, ignoreCase = true)
    }

    /** 用本轮复制凭据覆盖旧记录，让重新复制的结果继续作为恢复依据 */
    internal fun mergeMigrationCopyReceiptsForRecovery(
        persisted: Map<String, ManagedMigrationCopyReceipt>,
        current: Iterable<ManagedMigrationCopyReceipt>
    ): Map<String, ManagedMigrationCopyReceipt> {
        val currentReceipts = current.toList()
        if (currentReceipts.isEmpty()) return persisted
        return mergePersistedMigrationCopyReceipts(
            current = currentReceipts,
            checkpoints = listOf(persisted.values)
        )
    }

    private fun migrationSafDocumentIdentity(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val uri = runCatching { normalized.toUri() }.getOrNull()
        if (
            uri != null &&
            (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank())
        ) {
            return null
        }
        val authority = uri?.authority ?: rawMigrationSafAuthority(normalized) ?: return null
        val documentId = rawMigrationSafDocumentId(normalized)
            ?: uri?.let { parsed ->
                runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
                    ?: parsed.pathSegments.migrationDocumentIdFromSafPath()
                    ?: parsed.pathSegments
                        .takeIf { segments -> segments.firstOrNull() == "tree" }
                        ?.let { segments ->
                            runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
                                ?: segments.getOrNull(1)
                        }
            }
            ?: return null
        return "${authority.lowercase(Locale.ROOT)}\u0000$documentId"
    }

    private fun rawMigrationSafAuthority(value: String): String? {
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0 || !value.regionMatches(0, "content", 0, schemeEnd, true)) {
            return null
        }
        val authorityStart = schemeEnd + 3
        val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        return value.substring(
            authorityStart,
            if (authorityEnd >= 0) authorityEnd else value.length
        ).takeIf(String::isNotBlank)
    }

    private fun rawMigrationSafDocumentId(value: String): String? {
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0) return null
        val pathStart = value.indexOf('/', schemeEnd + 3)
        if (pathStart < 0) return null
        val pathEnd = value.indexOfAny(charArrayOf('?', '#'), pathStart)
            .let { end -> if (end >= 0) end else value.length }
        val segments = value.substring(pathStart, pathEnd)
            .split('/')
            .filter(String::isNotEmpty)
        return when {
            segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
            segments.size >= 2 && segments[0] == "document" -> segments[1]
            segments.size >= 2 && segments[0] == "tree" -> segments[1]
            else -> null
        }
    }

    private fun List<String>.migrationDocumentIdFromSafPath(): String? {
        return when {
            size >= 4 && this[0] == "tree" && this[2] == "document" -> this[3]
            size >= 2 && this[0] == "document" -> this[1]
            else -> null
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

    private suspend fun cleanupMigrationReplacementBackups(
        context: Context,
        targetRoot: RootHandle,
        copiedEntries: List<CopiedMigrationEntry>,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): ManagedMigrationCleanupResult {
        val candidates = copiedEntries.mapNotNull { copied ->
            copied.replacementBackup?.let { backup ->
                MigrationReplacementBackupCandidate(
                    backup = backup,
                    subdirectory = copied.original.subdirectory
                )
            }
        }.distinctBy { candidate -> candidate.backup.reference }
        if (candidates.isEmpty()) {
            return ManagedMigrationCleanupResult(
                failedFiles = 0,
                retryableFailedFiles = 0
            )
        }
        val total = candidates.size
        val refresh = try {
            treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(TAG, "迁移替换备份清理枚举失败: ${error.message}", error)
            null
        }
        if (refresh == null || !refresh.isComplete) {
            candidates.forEach { candidate ->
                progressTracker?.startCleanup(total, candidate.backup.name)
                progressTracker?.finishCleanup(candidate.backup.name)
            }
            return ManagedMigrationCleanupResult(
                failedFiles = total,
                retryableFailedFiles = total
            )
        }
        fun entriesFor(subdirectory: String?): List<StoredEntry> = when (subdirectory) {
            null -> refresh.rootEntries
            COVER_SUBDIRECTORY -> refresh.coverEntries
            LYRIC_SUBDIRECTORY -> refresh.lyricEntries
            else -> emptyList()
        }
        val validated = mutableListOf<ValidatedMigrationReplacementBackup>()
        var failed = 0
        var retryable = 0
        candidates.forEach { candidate ->
            val backup = candidate.backup
            progressTracker?.startCleanup(total, backup.name)
            val actual = entriesFor(candidate.subdirectory).firstOrNull { entry ->
                !entry.isDirectory && entry.name == backup.name
            }
            when {
                actual == null && isMissingReplacementBackup(context, targetRoot, backup) -> {
                    progressTracker?.finishCleanup(backup.name)
                }
                actual == null -> {
                    failed++
                    NPLogger.w(TAG, "迁移替换备份不在当前目标树，保留: ${backup.reference}")
                    progressTracker?.finishCleanup(backup.name)
                }
                !sameManagedMigrationStoredEntryIdentity(backup, actual) -> {
                    failed++
                    NPLogger.w(TAG, "迁移替换备份身份已变化，保留: ${backup.name}")
                    progressTracker?.finishCleanup(backup.name)
                }
                else -> {
                    validated += ValidatedMigrationReplacementBackup(
                        actual = actual
                    )
                }
            }
        }
        if (validated.isNotEmpty()) {
            val references = validated.map { candidate ->
                trustedManagedRef(candidate.actual.reference)
            }
            val results = deleteEnumeratedMigrationReferences(
                context = context,
                references = references,
                root = targetRoot,
                trustedReferencesSnapshot = trustedReferencesFromMigrationRefresh(refresh),
                onDeleteStarted = { reference ->
                    progressTracker?.startCleanup(total, reference.externalReference)
                },
                onDeleteFinished = { reference ->
                    progressTracker?.finishCleanup(reference.externalReference)
                }
            )
            validated.forEach { candidate ->
                val reference = trustedManagedRef(candidate.actual.reference)
                val result = results[reference] ?: StorageMutationResult.ProviderFailure(
                    IOException("replacement backup delete result missing")
                )
                if (!result.isConfirmedStorageMutation()) {
                    failed++
                    if (
                        result is StorageMutationResult.ProviderFailure ||
                        result is StorageMutationResult.PermissionLost
                    ) {
                        retryable++
                    }
                    NPLogger.w(TAG, "迁移替换备份清理未确认: ${candidate.actual.reference}")
                }
            }
        }
        return ManagedMigrationCleanupResult(
            failedFiles = failed,
            retryableFailedFiles = retryable
        )
    }

    private fun isMissingReplacementBackup(
        context: Context,
        targetRoot: RootHandle,
        backup: StoredEntry
    ): Boolean {
        val normalized = backup.reference.trim()
        if (!isMigrationReferenceBoundToRoot(targetRoot, normalized)) return false
        return when (inspectStorageReference(context, normalized)) {
            ManagedDownloadReferenceIo.AccessResult.Missing -> true
            else -> false
        }
    }

    private data class MigrationReplacementBackupCandidate(
        val backup: StoredEntry,
        val subdirectory: String?
    )

    private data class MigrationRecoveryTargetResolution(
        val entry: StoredEntry?,
        val alreadyRestored: Boolean
    )

    private data class ValidatedMigrationReplacementBackup(
        val actual: StoredEntry
    )

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
        ManagedDownloadRecoveryFiles.removePendingDownloadQueueOperationIds(
            context,
            operationIds
        )
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
        // Provider 文档 ID 是不透明值，其中的斜线和冒号都是数据，不能拿来推断层级
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

    /** 优先读取 Provider 返回的真实显示名，避免把不透明文档 ID 当成文件名 */
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
        fun findReference(snapshot: DownloadLibrarySnapshot): String? {
            return snapshot.coverEntriesByName[normalizedName]?.reference
                ?: snapshot.coverEntriesByName.values.firstOrNull { entry ->
                    entry.name.equals(normalizedName, ignoreCase = true)
                }?.reference
        }

        val cachedSnapshot = snapshotCacheStore.cachedSnapshot(
            context = context,
            restorePersisted = true
        )
        if (shouldUseSidecarRefreshForCoverLookup(
                forceRefresh = forceRefresh,
                preferSidecarRefresh = preferSidecarRefresh,
                hasCachedSnapshot = cachedSnapshot != null
            )
        ) {
            // 播放侧只需刷新歌词和封面目录，不要在大 SAF 目录上重新解析每条音频元数据
            // 这样切歌时不会被整棵目录拖慢
            val snapshotForRefresh = cachedSnapshot ?: return@withContext null
            val refreshedSnapshot = refreshDownloadSidecarSnapshotBlocking(
                context = context,
                snapshot = snapshotForRefresh,
                respectThrottle = true
            )
            return@withContext findReference(refreshedSnapshot)
        }
        val snapshot = if (forceRefresh) {
            buildDownloadLibrarySnapshotBlocking(
                context = context,
                forceRefresh = true
            )
        } else {
            cachedSnapshot ?: buildDownloadLibrarySnapshotBlocking(
                context = context,
                forceRefresh = false
            )
        }
        findReference(snapshot)
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
    ): PendingAudioWriteScanResult = withContext(Dispatchers.IO) {
        val root = resolveRootForOperation(
            context = context,
            directoryUri = directoryUri,
            useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
            unavailableMessage = "pending 音频源目录不可用，暂缓恢复"
        ) ?: return@withContext PendingAudioWriteScanResult(
            entries = emptyList(),
            isComplete = false
        )
        val refresh = treeDirectories.refreshRootEntries(context, root)
        if (!refresh.isComplete && forceRefresh) {
            NPLogger.w(TAG, "pending 音频枚举不完整，跳过恢复: root=${root.javaClass.simpleName}")
            return@withContext PendingAudioWriteScanResult(
                entries = emptyList(),
                isComplete = false
            )
        }
        val temporary = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = forceRefresh,
            rootAlreadyRefreshed = true
        )
        if (!temporary.isComplete && forceRefresh) {
            NPLogger.w(TAG, "下载 .tmp 目录枚举不完整，跳过 pending 恢复")
            return@withContext PendingAudioWriteScanResult(
                entries = emptyList(),
                isComplete = false
            )
        }
        PendingAudioWriteScanResult(
            entries = (refresh.entries + temporary.entries)
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .filter(StoredEntry::isPendingAudioWrite)
            .distinctBy(StoredEntry::reference)
            .toList(),
            isComplete = refresh.isComplete && temporary.isComplete
        )
    }

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
    ): PendingArtifactScanResult = withContext(Dispatchers.IO) {
        val normalizedProtectedReferences = protectedReferences
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val root = resolveRootForOperation(
            context = context,
            directoryUri = directoryUri,
            useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
            unavailableMessage = "pending 源目录不可用，无法证明临时文件已收敛"
        ) ?: return@withContext PendingArtifactScanResult(
            count = 0,
            isComplete = false
        )
        val refresh = treeDirectories.refreshRootEntries(context, root)
        if (!refresh.isComplete) {
            return@withContext PendingArtifactScanResult(0, isComplete = false)
        }
        val temporary = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = true,
            rootAlreadyRefreshed = true
        )
        if (!temporary.isComplete) {
            return@withContext PendingArtifactScanResult(0, isComplete = false)
        }
        val pendingEntries = (refresh.entries + temporary.entries)
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .filter { entry ->
                entry.isPendingAudioWrite ||
                    entry.name.contains(PENDING_AUDIO_WRITE_MARKER) ||
                    entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true)
            }
            .toList()
        val migrationPendingArtifacts =
            ManagedDownloadMigrationEntryCollector.classifyPendingArtifacts(
                rootEntries = refresh.entries,
                temporaryEntries = temporary.entries
            )
        val count = pendingEntries.size
        val cachedDurableAudioNames = if (
            directoryUri?.trim()?.isNotBlank() == true ||
            useDefaultRootWhenDirectoryUriMissing
        ) {
            // 显式源目录可能已经不是当前配置根，不能拿目标目录缓存保护同名 pending
            emptySet()
        } else {
            snapshotCacheStore
                .cachedSnapshot(context, restorePersisted = false)
                ?.pendingMetadataByAudioName
                .orEmpty()
                .filterValues(::isDurableCoreMetadata)
                .keys
        }
        val cachedProtectedReferences = pendingEntries
            .asSequence()
            .filter { entry ->
                pendingArtifactLogicalName(entry) in cachedDurableAudioNames
            }
            .mapTo(linkedSetOf(), StoredEntry::reference)
        val allProtectedReferences = normalizedProtectedReferences + cachedProtectedReferences
        val protectedCount = pendingEntries.count { entry ->
            entry.reference in allProtectedReferences
        }
        PendingArtifactScanResult(
            count = count,
            isComplete = true,
            protectedCount = protectedCount,
            migrationBlockingArtifactCount = migrationPendingArtifacts.blockingNames.size,
            migrationMetadataOnlyArtifactCount = migrationPendingArtifacts.metadataOnlyNames.size
        )
    }

    private fun pendingArtifactLogicalName(entry: StoredEntry): String? {
        return if (entry.isPendingAudioWrite) {
            entry.logicalName
        } else {
            ManagedDownloadTreeNaming.metadataAudioName(entry.name)
        }
    }

    private fun isDurableCoreMetadata(metadata: DownloadedAudioMetadata): Boolean {
        if (metadata.downloadFinalized == true) {
            return true
        }
        return isDurableCoreArtifactState(
            metadata.artifactState
                ?.trim()
                ?.uppercase(Locale.ROOT)
        )
    }

    /** 清空时只有明确属于临时状态的条目才允许删除 */
    internal fun isKnownTransientPendingMetadata(
        metadata: DownloadedAudioMetadata
    ): Boolean {
        if (metadata.downloadFinalized == true) return false
        val state = metadata.artifactState
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?: return false
        return state in KNOWN_TRANSIENT_PENDING_ARTIFACT_STATES
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

    internal fun findPendingDownloadedAudio(
        snapshot: DownloadLibrarySnapshot,
        song: SongItem
    ): StoredEntry? {
        val pendingEntries = snapshot.pendingAudioEntries
        if (pendingEntries.isEmpty()) {
            return null
        }
        val localReferences = listOfNotNull(song.localFilePath, song.mediaUri)
            .filter { reference ->
                reference.startsWith("/") || reference.startsWith("content://", ignoreCase = true)
            }
            .distinct()
        localReferences.firstNotNullOfOrNull { reference ->
            pendingEntries.firstOrNull { entry ->
                entry.reference == reference ||
                    entry.mediaUri == reference ||
                    entry.localFilePath == reference
            }
        }?.let { return it }

        val stableKeys = setOfNotNull(
            song.stableKey().takeIf(String::isNotBlank),
            song.sourceStableKey?.trim()?.takeIf(String::isNotBlank)
        ).toMutableSet().apply {
            song.remoteDownloadIdentityOrNull()
                ?.stableKey()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
        }
        val metadataMatch = pendingEntries.mapNotNull { entry ->
            val metadata = metadataForAudioEntry(snapshot, entry) ?: return@mapNotNull null
            val matchesIdentity = metadata.stableKey in stableKeys ||
                (song.id > 0L && metadata.songId == song.id) ||
                metadata.mediaUri != null && metadata.mediaUri == song.mediaUri
            if (matchesIdentity) entry to metadata else null
        }.maxWithOrNull(
            compareBy<Pair<StoredEntry, DownloadedAudioMetadata>> {
                it.second.downloadFinalized == true
            }
                .thenBy { it.first.sizeBytes }
                .thenBy { it.first.lastModifiedMs }
        )
        if (metadataMatch != null) {
            return metadataMatch.first
        }
        return ManagedDownloadStorageLookup.findPendingAudioEntry(
            audioEntries = pendingEntries,
            // pending 音频也要遵循当前模板及历史模板, 自定义命名不能漏掉
            baseNames = candidateManagedDownloadBaseNames(song, settings.fileNameTemplate)
        )
    }

    internal fun peekPendingDownloadedAudio(song: SongItem): StoredEntry? {
        return snapshotCacheStore.peekSnapshot()?.let { snapshot ->
            findPendingDownloadedAudio(snapshot, song)
        }
    }

    suspend fun queryStoredEntry(context: Context, reference: String?): StoredEntry? = withContext(Dispatchers.IO) {
        val target = reference?.takeIf { it.isNotBlank() } ?: return@withContext null
        val cachedSnapshot = buildDownloadLibrarySnapshotBlocking(context)
        val cachedEntry = cachedSnapshot.audioEntriesByLookupKey[target]
            ?: cachedSnapshot.pendingAudioEntries.firstOrNull { entry ->
                entry.reference == target ||
                    entry.mediaUri == target ||
                    entry.localFilePath == target
            }
            ?: return@withContext null
        if (
            inspectStorageReference(context, storageReferenceForInspection(cachedEntry)) ==
                ManagedDownloadReferenceIo.AccessResult.Accessible
        ) {
            return@withContext cachedEntry
        }
        val refreshedSnapshot = buildDownloadLibrarySnapshotBlocking(
            context,
            forceRefresh = true
        )
        (refreshedSnapshot.audioEntriesByLookupKey[target]
            ?: refreshedSnapshot.pendingAudioEntries.firstOrNull { entry ->
                entry.reference == target ||
                    entry.mediaUri == target ||
                    entry.localFilePath == target
            })
            ?.takeIf { refreshedEntry ->
                inspectStorageReference(context, storageReferenceForInspection(refreshedEntry)) ==
                    ManagedDownloadReferenceIo.AccessResult.Accessible
            }
    }

    private fun storageReferenceForInspection(entry: StoredEntry): String {
        return resolveStoredEntryPlaybackUri(entry, allowPending = true)
            ?: entry.reference
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
            val pendingAudioEntries = rootEntries.filter(StoredEntry::isPendingAudioWrite)
            val audioEntries = rootEntries.filter { entry ->
                !entry.isPendingAudioWrite && entry.extension in audioExtensions
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
                rootEntriesComplete = rootRefresh.isComplete,
                pendingAudioEntries = pendingAudioEntries
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
            val metadata = metadataForAudioEntry(snapshot, audio)
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
            val logicalCreatedAtMs = metadata.createdAtMs
                ?: metadata.downloadTimeMs
                ?: audio.lastModifiedMs.takeIf { it > 0L }
            val createdAtSource = metadata.createdAtSource
                ?: when {
                    metadata.createdAtMs != null -> "MANAGED_COMMIT"
                    metadata.downloadTimeMs != null -> "DOWNLOAD_TIME"
                    audio.lastModifiedMs > 0L -> "MTIME"
                    else -> null
                }
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
                coverPath = ManagedDownloadCoverLookup.findCoverReference(snapshot, audio),
                logicalCreatedAtMs = logicalCreatedAtMs,
                createdAtSource = createdAtSource,
                createdAtConfidence = metadata.createdAtConfidence
                    ?: createdAtSource?.let(::resolveCreatedAtConfidence)
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
        // 元数据不完整不能说明对应分片为空
        return snapshot.rootEntriesComplete &&
            snapshot.sidecarEntriesComplete &&
            snapshot.audioEntriesWithoutMetadata.isEmpty()
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
                sizeKnown = false,
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
                createdAtMs = entry.logicalCreatedAtMs ?: entry.updatedAtMs,
                createdAtSource = entry.createdAtSource ?: "INDEX_PREVIEW",
                createdAtConfidence = entry.createdAtConfidence
                    ?: entry.createdAtSource?.let(::resolveCreatedAtConfidence)
                    ?: "INFERRED",
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
                rootEntriesComplete = false,
                sidecarEntriesComplete = false
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
                // buildDownloadLibrarySnapshot 已经把可用的旧侧载合并进 requested
                // snapshot。返回 activeSnapshot 会把同一轮更新的音频核心条目回退掉，
                // 进而让刚提交的歌曲暂时变白
                return snapshot
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
            resolveStoredEntryPlaybackUri(entry)?.let { playbackUri ->
                inspectStorageReference(context, playbackUri)
            } ==
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

        val libraryRefresh = treeDirectories.refreshDownloadLibraryEntries(context, root)
        val rootEntries = libraryRefresh.rootEntries.filterNot(StoredEntry::isDirectory)
        val temporaryEntries = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = forceRefresh,
            rootAlreadyRefreshed = true
        )
        val pendingAudioEntries = (rootEntries + temporaryEntries.entries)
            .filter(StoredEntry::isPendingAudioWrite)
            .distinctBy(StoredEntry::reference)
        val pendingAudioLogicalNames = pendingAudioEntries
            .mapTo(hashSetOf(), StoredEntry::logicalName)
        val audioEntries = rootEntries.filter {
            !it.isPendingAudioWrite && it.extension in audioExtensions
        }
        val metadataEntries = (rootEntries + temporaryEntries.entries.filter { entry ->
            entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true)
        }).filter { entry ->
            if (!ManagedDownloadTreeNaming.isMetadataName(entry.name)) {
                return@filter false
            }
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                ?: return@filter false
            !ManagedDownloadTreeNaming.isPendingMetadataName(
                actualName = entry.name,
                audioName = audioName
            ) || audioName in pendingAudioLogicalNames
        }
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
        val metadataEntriesToParse = mutableListOf<Pair<String, StoredEntry>>()
        val metadataByAudioName = linkedMapOf<String, DownloadedAudioMetadata>()
        metadataEntriesByAudioName.forEach { (audioName, entry) ->
            val cachedEntry = cachedSnapshot?.metadataEntriesByAudioName?.get(audioName)
            val cachedMetadata = cachedSnapshot?.metadataByAudioName?.get(audioName)
            if (
                canReuseCachedDownloadedMetadata(
                    cachedEntry = cachedEntry,
                    currentEntry = entry,
                    cachedMetadata = cachedMetadata
                )
            ) {
                reusedMetadataCount++
                metadataByAudioName[audioName] = requireNotNull(cachedMetadata)
            } else {
                metadataEntriesToParse += audioName to entry
            }
        }
        parseDownloadedAudioMetadataBatch(
            context = context,
            entries = metadataEntriesToParse
        ).forEach { (audioName, metadata) ->
            if (metadata != null) {
                metadataByAudioName[audioName] = metadata
            }
        }
        val pendingMetadataEntriesByAudioName = metadataEntries
            .mapNotNull { entry ->
                if (!entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true)) {
                    return@mapNotNull null
                }
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
        val pendingMetadataEntriesToParse = pendingMetadataEntriesByAudioName
            .mapNotNull { (audioName, entry) ->
                if (metadataEntriesByAudioName[audioName] == entry) {
                    return@mapNotNull null
                }
                audioName to entry
            }
        val pendingMetadataByAudioName = buildMap {
            pendingMetadataEntriesByAudioName.forEach { (audioName, entry) ->
                val metadata = if (metadataEntriesByAudioName[audioName] == entry) {
                    metadataByAudioName[audioName]
                } else {
                    null
                }
                if (metadata != null) {
                    put(audioName, metadata)
                }
            }
            parseDownloadedAudioMetadataBatch(
                context = context,
                entries = pendingMetadataEntriesToParse
            ).forEach { (audioName, metadata) ->
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
        val coverEntries = if (libraryRefresh.sidecarEntriesComplete) {
            libraryRefresh.coverEntries
        } else {
            cachedSnapshot?.coverEntriesByName?.values?.toList()
                ?: libraryRefresh.coverEntries
        }
        val lyricEntries = if (libraryRefresh.sidecarEntriesComplete) {
            libraryRefresh.lyricEntries
        } else {
            cachedSnapshot?.lyricEntriesByName?.values?.toList()
                ?: libraryRefresh.lyricEntries
        }
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
            rootEntriesComplete = libraryRefresh.rootEntriesComplete && temporaryEntries.isComplete,
            sidecarEntriesComplete = libraryRefresh.sidecarEntriesComplete,
            pendingAudioEntries = pendingAudioEntries,
            pendingMetadataByAudioName = pendingMetadataByAudioName
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
        return !respectThrottle &&
            activeSnapshot === requestedSnapshot &&
            requestedSnapshot.sidecarEntriesComplete
    }

    private fun composeSnapshot(
        audioEntries: List<StoredEntry>,
        metadataEntries: List<StoredEntry>,
        metadataByAudioName: Map<String, DownloadedAudioMetadata>,
        coverEntries: List<StoredEntry>,
        lyricEntries: List<StoredEntry>,
        rootEntriesComplete: Boolean = true,
        sidecarEntriesComplete: Boolean = true,
        pendingAudioEntries: List<StoredEntry> = emptyList(),
        pendingMetadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap()
    ): DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = audioEntries,
            metadataEntries = metadataEntries,
            metadataByAudioName = metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries,
            rootEntriesComplete = rootEntriesComplete,
            sidecarEntriesComplete = sidecarEntriesComplete,
            pendingAudioEntries = pendingAudioEntries,
            pendingMetadataByAudioName = pendingMetadataByAudioName
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
        progressTracker: ManagedMigrationProgressReporter? = null,
        onEntryVerified: suspend (CopiedMigrationEntry) -> Unit = {}
    ): ManagedMigrationVerificationResult {
        return migrationFinalizer.verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries,
            progressTracker = progressTracker,
            onEntryVerified = onEntryVerified
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

    /** 迁移前从源 root 读取 metadata, 不让当前配置目录遮蔽旧目录凭据 */
    internal suspend fun readDownloadedMetadataFromRoot(
        context: Context,
        audio: StoredEntry,
        directoryUri: String?,
        preferPendingMetadata: Boolean = false,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): DownloadedAudioMetadata? = withContext(Dispatchers.IO) {
        val root = resolveRootForOperation(
            context = context,
            directoryUri = directoryUri,
            useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
            unavailableMessage = "迁移前读取源 metadata 的目录不可用"
        ) ?: return@withContext null
        val metadataEntry = if (preferPendingMetadata) {
            val pendingLookup = findPendingMetadataForAudioBlocking(
                context = context,
                root = root,
                audio = audio
            )
            if (pendingLookup.entry != null || !pendingLookup.complete) {
                pendingLookup.entry
            } else {
                findMetadataForAudioBlocking(
                    context = context,
                    audio = audio,
                    rootOverride = root
                )
            }
        } else {
            findMetadataForAudioBlocking(
                context = context,
                audio = audio,
                rootOverride = root
            )
        } ?: return@withContext null
        readTextInternal(context, metadataEntry.reference)
            ?.let(::parseDownloadedAudioMetadataJson)
    }

    private fun findMetadataForAudioBlocking(
        context: Context,
        audio: StoredEntry,
        rootOverride: RootHandle? = null
    ): StoredEntry? {
        val snapshot = rootOverride?.let { null } ?: resolveSnapshotForIndexedLookup(context)
        return snapshot?.metadataEntriesByAudioName?.get(audio.logicalName)
            ?: findMetadataByDirectLookup(context, audio, rootOverride)
    }

    internal fun metadataReferenceForAudio(audio: StoredEntry): String? {
        val reference = audio.reference.takeIf(String::isNotBlank) ?: return null
        if (audio.isPendingAudioWrite) return null
        return "$reference$METADATA_SUFFIX"
    }

    private fun findMetadataByDirectLookup(
        context: Context,
        audio: StoredEntry,
        rootOverride: RootHandle? = null
    ): StoredEntry? {
        val logicalAudioName = audio.logicalName
        val metadataName = "$logicalAudioName$METADATA_SUFFIX"
        val pendingMetadataName = "$logicalAudioName$PENDING_METADATA_SUFFIX"
        val root = rootOverride ?: resolveRootBlocking(context)

        fun findInRoot(candidateRoot: RootHandle): StoredEntry? {
            return when (candidateRoot) {
                is RootHandle.FileRoot -> {
                    val metadataFile = File(candidateRoot.dir, metadataName)
                    if (metadataFile.exists() && metadataFile.isFile) {
                        metadataFile.toStoredEntry()
                    } else {
                        val pendingFile = File(candidateRoot.dir, pendingMetadataName)
                        if (pendingFile.exists() && pendingFile.isFile) {
                            pendingFile.toStoredEntry()
                        } else {
                            candidateRoot.dir.listFiles()
                                ?.asSequence()
                                ?.filter { file ->
                                    ManagedDownloadTreeNaming.metadataNameOrdinal(
                                        file.name,
                                        audio.name
                                    ) != null
                                }
                                ?.minWithOrNull(
                                    compareBy<File>(
                                        {
                                            ManagedDownloadTreeNaming.metadataNameOrdinal(
                                                it.name,
                                                audio.name
                                            ) ?: Int.MAX_VALUE
                                        },
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
                        parent = candidateRoot.tree,
                        // 写入路径优先复用已确认的目录快照，避免每次回写都重新枚举 SAF
                        maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
                    )
                    children.asSequence()
                        .filterNot(QueriedTreeChild::isDirectory)
                        .filter { child -> child.name == metadataName }
                        .firstOrNull()
                        ?.toStoredEntry()
                        ?: children.asSequence()
                            .filterNot(QueriedTreeChild::isDirectory)
                            .filter { child ->
                                ManagedDownloadTreeNaming.metadataNameOrdinal(
                                    child.name,
                                    audio.name
                                ) != null
                            }
                            .minWithOrNull(
                                compareBy<QueriedTreeChild>(
                                    {
                                        ManagedDownloadTreeNaming.metadataNameOrdinal(
                                            it.name,
                                            audio.name
                                        ) ?: Int.MAX_VALUE
                                    },
                                    { it.name }
                                )
                            )
                            ?.toStoredEntry()
                }
            }
        }

        // 正式 metadata 仍在根目录，先查根以兼容旧版本和已提交音频
        findInRoot(root)?.let { return it }
        if (!audio.isPendingAudioWrite) return null

        // 新版本 pending 音频和 pending metadata 同处 .tmp，避免根目录污染
        val temporaryRoot = resolveTemporaryRoot(context, root, create = false)
        return temporaryRoot?.let(::findInRoot)
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
        val temporaryRoot = resolveTemporaryRoot(
            context = appContext,
            root = root,
            create = true
        ) ?: throw IOException("无法准备下载 .tmp 目录")
        val backendResult = writeTextThroughBackend(
            context = appContext,
            root = temporaryRoot,
            displayName = "$audioName$PENDING_METADATA_SUFFIX",
            content = json,
            temporaryWriteOwnerName = temporaryWriteOwnerNameForOperation(
                displayName = "$audioName$PENDING_METADATA_SUFFIX",
                operationId = operationId
            )
        )
        val written = when (backendResult) {
            is StorageWriteResult.Written -> backendResult.stat.toStoredEntryForBackend(
                fileRoot = (temporaryRoot as? RootHandle.FileRoot)?.dir
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

    /** core 提交后先把可播放音频提升出 .tmp，资产增强在目录变更结束后继续执行 */
    internal suspend fun promoteCoreCommittedPendingAudio(
        context: Context,
        audio: StoredEntry,
        directoryUri: String? = null,
        promotePendingMetadata: Boolean = false,
        useDefaultRootWhenDirectoryUriMissing: Boolean = false
    ): StoredEntry? = withContext(Dispatchers.IO) {
        if (!audio.isPendingAudioWrite) return@withContext audio
        val root = resolveRootForOperation(
            context = context,
            directoryUri = directoryUri,
            useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
            unavailableMessage = "迁移前提升 pending 音频的目录不可用"
        ) ?: return@withContext null
        val metadataEntry = if (promotePendingMetadata) {
            val pendingLookup = findPendingMetadataForAudioBlocking(
                context = context,
                root = root,
                audio = audio
            )
            if (pendingLookup.entry != null || !pendingLookup.complete) {
                pendingLookup.entry
            } else {
                findMetadataForAudioBlocking(
                    context = context,
                    audio = audio,
                    rootOverride = root
                )
            }
        } else {
            findMetadataForAudioBlocking(
                context = context,
                audio = audio,
                rootOverride = root
            )
        } ?: return@withContext null
        val rawMetadata = readTextInternal(context, metadataEntry.reference)
            ?: return@withContext null
        val metadata = rawMetadata
            .let(::parseDownloadedAudioMetadataJson)
            ?: return@withContext null
        if (!isDurableCoreMetadata(metadata)) {
            return@withContext null
        }
        val finalAudioName = if (promotePendingMetadata) {
            resolvePendingCorePromotionFinalName(
                context = context,
                root = root,
                audio = audio,
                metadataEntry = metadataEntry,
                metadata = metadata
            ) ?: run {
                NPLogger.w(
                    TAG,
                    "迁移前无法安全解析 pending 音频最终名称，保留凭据: " +
                        "audio=${audio.logicalName}"
                )
                return@withContext null
            }
        } else {
            audio.logicalName
        }
        if (
            promotePendingMetadata &&
                !promotePendingCoreMetadata(
                    context = context,
                    root = root,
                    audio = audio,
                    metadataEntry = metadataEntry,
                    rawMetadata = rawMetadata,
                    metadata = metadata,
                    finalAudioName = finalAudioName
                )
        ) {
            NPLogger.w(
                TAG,
                "迁移前 core metadata 未能提升到正式名称，保留 pending 凭据: " +
                    "audio=${audio.logicalName}"
            )
            return@withContext null
        }
        val promoted = promotePendingAudio(
            context = context,
            root = root,
            audio = audio,
            finalAudioName = finalAudioName
        ) ?: return@withContext null
        if (promoted.isPendingAudioWrite) {
            return@withContext null
        }
        if (promotePendingMetadata) {
            cleanupPendingCoreMetadataAfterAudioPromotion(
                context = context,
                root = root,
                audio = audio,
                metadataEntry = metadataEntry
            )
        }
        if (!updateSnapshotCacheAfterStoredEntryWrite(
                context = context,
                promoted,
                SnapshotEntryBucket.AUDIO
            )
        ) {
            invalidateSnapshotCache(context)
        }
        promoted
    }

    private fun promotePendingCoreMetadata(
        context: Context,
        root: RootHandle,
        audio: StoredEntry,
        metadataEntry: StoredEntry,
        rawMetadata: String,
        metadata: DownloadedAudioMetadata,
        finalAudioName: String
    ): Boolean {
        val rewrittenMetadata = rewritePendingMetadataAudioFileName(
            rawMetadata = rawMetadata,
            finalAudioName = finalAudioName
        ) ?: return false
        val expectedMetadata = parseDownloadedAudioMetadataJson(rewrittenMetadata)
            ?.takeIf(::isDurableCoreMetadata)
            ?.takeIf { candidate ->
                resolveStagedPendingPromotionFinalName(
                    requestedName = audio.logicalName,
                    stagedMetadata = candidate,
                    expectedStableKey = metadata.stableKey,
                    expectedOperationId = metadata.operationId
                ) == finalAudioName
            }
            ?: return false
        val finalMetadataName = "$finalAudioName$METADATA_SUFFIX"
        val sourceIsFinalMetadata =
            !ManagedDownloadTreeNaming.isPendingMetadataName(
                metadataEntry.name,
                audio.logicalName
            ) &&
                ManagedDownloadTreeNaming.isExactTreeStoredName(
                    metadataEntry.name,
                    finalMetadataName
                )
        val existingLookup = findExactEntryInRoot(
            context = context,
            root = root,
            name = finalMetadataName
        )
        if (!existingLookup.complete) {
            NPLogger.w(
                TAG,
                "迁移前 metadata 目标枚举不完整，保留 pending 凭据: " +
                    "name=$finalMetadataName"
            )
            return false
        }
        val existing = if (sourceIsFinalMetadata) metadataEntry else existingLookup.entry
        if (existing != null) {
            val existingMetadata = readTextInternal(context, existing.reference)
                ?.let(::parseDownloadedAudioMetadataJson)
            if (
                existingMetadata == null ||
                    !matchesPendingPromotionIdentity(
                        stagedMetadata = existingMetadata,
                        expectedStableKey = metadata.stableKey,
                        expectedOperationId = metadata.operationId
                    ) ||
                    (
                        !sourceIsFinalMetadata &&
                            resolveStagedPendingPromotionFinalName(
                                requestedName = audio.logicalName,
                                stagedMetadata = existingMetadata,
                                expectedStableKey = metadata.stableKey,
                                expectedOperationId = metadata.operationId
                            ) != finalAudioName
                        )
            ) {
                NPLogger.w(
                    TAG,
                    "迁移前发现不同歌曲占用 metadata 名称，保留两份凭据: " +
                        "name=$finalMetadataName"
                )
                return false
            }
        }
        val written = writeRootText(
            context = context,
            root = root,
            displayName = finalMetadataName,
            content = rewrittenMetadata,
            expectedAbsent = existing == null,
            knownTargetEntry = existing
        ) ?: return false
        if (!ManagedDownloadTreeNaming.isExactTreeStoredName(written.name, finalMetadataName)) {
            NPLogger.w(
                TAG,
                "迁移前 metadata 写入返回非目标名称，保留 pending 凭据: " +
                    "expected=$finalMetadataName, actual=${written.name}"
            )
            invalidateSnapshotCache(context)
            return false
        }
        if (
            readTextInternal(context, written.reference)
                ?.let(::parseDownloadedAudioMetadataJson)
                ?.takeIf(::isDurableCoreMetadata)
                ?.takeIf { candidate ->
                    candidate.audioFileName == finalAudioName &&
                        resolveStagedPendingPromotionFinalName(
                            requestedName = audio.logicalName,
                            stagedMetadata = candidate,
                            expectedStableKey = metadata.stableKey,
                            expectedOperationId = metadata.operationId
                        ) == finalAudioName &&
                        isMetadataWriteVerified(expectedMetadata, candidate)
                } == null
        ) {
            return false
        }
        invalidateSnapshotCache(context)
        return true
    }

    private suspend fun cleanupPendingCoreMetadataAfterAudioPromotion(
        context: Context,
        root: RootHandle,
        audio: StoredEntry,
        metadataEntry: StoredEntry
    ) {
        if (!ManagedDownloadTreeNaming.isPendingMetadataName(metadataEntry.name, audio.logicalName)) {
            return
        }
        val sourceReleased = isPendingAudioPromotionSourceReleased(
            context = context,
            root = root,
            audio = audio
        )
        cleanupPendingCoreMetadataAfterAudioPromotion(
            context = context,
            root = root,
            audio = audio,
            metadataEntry = metadataEntry,
            sourceReleased = sourceReleased
        )
    }

    private fun cleanupPendingCoreMetadataAfterAudioPromotion(
        context: Context,
        root: RootHandle,
        audio: StoredEntry,
        metadataEntry: StoredEntry,
        sourceReleased: Boolean
    ) {
        if (!sourceReleased) {
            NPLogger.w(
                TAG,
                "迁移前音频已提升但 pending 音频清理未确认，保留配对 metadata: " +
                    "audio=${audio.name}"
            )
            return
        }
        val deletedReferences = deleteReferencesInternal(
            context = context,
            references = listOf(metadataEntry.reference),
            allowedRoot = root,
            trustedReferences = setOf(metadataEntry.reference),
            invalidateSnapshot = false
        )
        if (metadataEntry.reference !in deletedReferences) {
            NPLogger.w(
                TAG,
                "迁移前音频已提升但 pending metadata 清理未确认，保留后续恢复: " +
                    "name=${metadataEntry.name}"
            )
            return
        }
        forgetDeletedReferencesFromCaches(setOf(metadataEntry.reference))
        invalidateSnapshotCache(context)
    }

    private suspend fun isPendingAudioPromotionSourceReleased(
        context: Context,
        root: RootHandle,
        audio: StoredEntry
    ): Boolean {
        return when (root) {
            is RootHandle.FileRoot -> {
                val pending = File(audio.reference)
                !pending.exists()
            }

            is RootHandle.TreeRoot -> {
                val reference = runCatching { StorageReference.SafRef(audio.reference.toUri()) }
                    .getOrNull() ?: return false
                when (val stat = SafStorageBackend(context).stat(reference)) {
                    StorageLookupResult.Missing -> true
                    is StorageLookupResult.Found -> {
                        !stat.value.isDirectory &&
                            !ManagedDownloadPendingAudioWriteNames.isArtifactName(
                                stat.value.displayName
                            )
                    }

                    StorageLookupResult.PermissionLost,
                    is StorageLookupResult.ProviderFailure,
                    StorageLookupResult.OutOfScope,
                    is StorageLookupResult.Unsupported -> false
                }
            }
        }
    }

    private suspend fun resolvePendingCorePromotionFinalName(
        context: Context,
        root: RootHandle,
        audio: StoredEntry,
        metadataEntry: StoredEntry,
        metadata: DownloadedAudioMetadata
    ): String? {
        val rootRefresh = treeDirectories.refreshRootEntries(context, root)
        if (!rootRefresh.isComplete) {
            return null
        }
        val expectedStableKey = metadata.stableKey?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val expectedSizeBytes = pendingAudioPromotionExpectedSizeForPlanning(
            context = context,
            root = root,
            audio = audio
        )
        val directTargets = rootRefresh.entries.filter { entry ->
            !entry.isDirectory &&
                ManagedDownloadTreeNaming.isExactTreeStoredName(
                    entry.name,
                    audio.logicalName
                )
        }
        val directTarget = directTargets.singleOrNull()
        val directTargetConflicts = directTarget == null && directTargets.isNotEmpty() ||
            directTarget?.let { entry ->
                entry.extension !in audioExtensions ||
                    expectedSizeBytes == null ||
                    entry.sizeBytes <= 0L ||
                    entry.sizeBytes != expectedSizeBytes
            } == true
        val stagedNames = rootRefresh.entries
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .mapNotNull { entry ->
                val stagedAudioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                    ?: return@mapNotNull null
                if (ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, stagedAudioName)) {
                    return@mapNotNull null
                }
                val stagedMetadata = readTextInternal(context, entry.reference)
                    ?.let(::parseDownloadedAudioMetadataJson)
                    ?.takeIf(::isDurableCoreMetadata)
                    ?: return@mapNotNull null
                val finalAudioName = resolveStagedPendingPromotionFinalName(
                    requestedName = audio.logicalName,
                    stagedMetadata = stagedMetadata,
                    expectedStableKey = expectedStableKey,
                    expectedOperationId = metadata.operationId
                ) ?: return@mapNotNull null
                finalAudioName.takeIf { candidate ->
                    ManagedDownloadTreeNaming.isExactTreeStoredName(
                        stagedAudioName,
                        candidate
                    )
                }
            }
            .distinct()
            .toList()
        val renamedStagedNames = stagedNames.filterNot { candidate ->
            ManagedDownloadTreeNaming.isExactTreeStoredName(
                candidate,
                audio.logicalName
            )
        }
        if (renamedStagedNames.size > 1) {
            NPLogger.w(
                TAG,
                "迁移前发现同一 pending 凭据对应多个最终名称，保留等待恢复: " +
                    "audio=${audio.logicalName}, candidates=$renamedStagedNames"
            )
            return null
        }
        renamedStagedNames.singleOrNull()?.let { return it }
        if (!directTargetConflicts) {
            return audio.logicalName
        }
        val temporary = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = true,
            rootAlreadyRefreshed = true
        )
        if (!temporary.isComplete) {
            return null
        }
        val sourceReferences = setOf(audio.reference, metadataEntry.reference)
        return resolvePendingAudioPromotionFinalName(
            enumerationComplete = true,
            existingNames = (rootRefresh.entries + temporary.entries)
                .asSequence()
                .filterNot(StoredEntry::isDirectory)
                .filterNot { entry -> entry.reference in sourceReferences }
                .map(StoredEntry::name)
                .toList(),
            requestedName = audio.logicalName
        )
    }

    private suspend fun pendingAudioPromotionExpectedSizeForPlanning(
        context: Context,
        root: RootHandle,
        audio: StoredEntry
    ): Long? {
        return when (root) {
            is RootHandle.FileRoot -> File(audio.reference)
                .takeIf(File::isFile)
                ?.length()
                ?.takeIf { size -> size > 0L }

            is RootHandle.TreeRoot -> {
                val reference = runCatching { StorageReference.SafRef(audio.reference.toUri()) }
                    .getOrNull() ?: return null
                val backend = SafStorageBackend(context)
                when (val stat = backend.stat(reference)) {
                    is StorageLookupResult.Found -> stat.value
                        .takeUnless(StorageStat::isDirectory)
                        ?.let { current ->
                            try {
                                resolveCurrentTreePendingAudioSize(
                                    backend = backend,
                                    reference = reference,
                                    reportedSizeBytes = current.sizeBytes,
                                    description = audio.name
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                NPLogger.w(
                                    TAG,
                                    "迁移前 pending 音频大小读回失败，保留凭据: " +
                                        "audio=${audio.logicalName}, error=${error.message}",
                                    error
                                )
                                null
                            }
                        }

                    StorageLookupResult.Missing,
                    StorageLookupResult.PermissionLost,
                    is StorageLookupResult.ProviderFailure,
                    StorageLookupResult.OutOfScope,
                    is StorageLookupResult.Unsupported -> null
                }
            }
        }
    }

    internal fun resolveStagedPendingPromotionFinalName(
        requestedName: String,
        stagedMetadata: DownloadedAudioMetadata,
        expectedStableKey: String?,
        expectedOperationId: String?
    ): String? {
        if (!matchesPendingPromotionIdentity(
                stagedMetadata = stagedMetadata,
                expectedStableKey = expectedStableKey,
                expectedOperationId = expectedOperationId
            )
        ) {
            return null
        }
        return stagedMetadata.audioFileName?.takeIf { candidate ->
            isPendingAudioPromotionFinalNameCandidate(
                requestedName = requestedName,
                candidateName = candidate
            )
        }
    }

    internal fun resolvePendingAudioPromotionFinalName(
        enumerationComplete: Boolean,
        existingNames: Collection<String>,
        requestedName: String
    ): String? {
        if (
            !enumerationComplete ||
                requestedName.isBlank() ||
                requestedName != requestedName.trim() ||
                requestedName == "." ||
                requestedName == ".." ||
                '/' in requestedName ||
                '\\' in requestedName
        ) {
            return null
        }
        if (existingNames.any { actualName ->
                isTreePromotionBackupName(actualName, requestedName)
            }
        ) {
            return null
        }
        val baseName = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
        for (index in 0 until 10_000) {
            val candidate = when (index) {
                0 -> requestedName
                else -> if (extension.isBlank()) {
                    "$baseName ($index)"
                } else {
                    "$baseName ($index).$extension"
                }
            }
            if (existingNames.none { actualName ->
                    isPendingAudioPromotionNameOccupied(
                        actualName = actualName,
                        candidateName = candidate
                    )
                }
            ) {
                return candidate
            }
        }
        return null
    }

    internal fun rewritePendingMetadataAudioFileName(
        rawMetadata: String,
        finalAudioName: String
    ): String? {
        if (
            finalAudioName.isBlank() ||
                finalAudioName != finalAudioName.trim() ||
                finalAudioName == "." ||
                finalAudioName == ".." ||
                '/' in finalAudioName ||
                '\\' in finalAudioName
        ) {
            return null
        }
        return runCatching {
            JSONObject(rawMetadata)
                .put("audioFileName", finalAudioName)
                .toString()
        }.getOrNull()
    }

    private fun matchesPendingPromotionIdentity(
        stagedMetadata: DownloadedAudioMetadata,
        expectedStableKey: String?,
        expectedOperationId: String?
    ): Boolean {
        val normalizedStableKey = expectedStableKey?.trim()?.takeIf(String::isNotBlank)
            ?: return false
        if (stagedMetadata.stableKey?.trim() != normalizedStableKey) {
            return false
        }
        val normalizedOperationId = expectedOperationId?.trim()?.takeIf(String::isNotBlank)
            ?: return true
        return stagedMetadata.operationId?.trim() == normalizedOperationId
    }

    private fun isPendingAudioPromotionFinalNameCandidate(
        requestedName: String,
        candidateName: String
    ): Boolean {
        if (
            candidateName.isBlank() ||
                candidateName != candidateName.trim() ||
                candidateName == "." ||
                candidateName == ".." ||
                '/' in candidateName ||
                '\\' in candidateName
        ) {
            return false
        }
        return ManagedDownloadTreeNaming.isExactTreeStoredName(candidateName, requestedName) ||
            ManagedDownloadTreeNaming.matchesProviderNumberedName(candidateName, requestedName)
    }

    private fun isPendingAudioPromotionNameOccupied(
        actualName: String,
        candidateName: String
    ): Boolean {
        if (isTreePromotionBackupName(actualName, candidateName)) {
            return true
        }
        val pendingAudioName = actualName
            .takeIf(ManagedDownloadPendingAudioWriteNames::isArtifactName)
            ?.let(pendingAudioWriteNames::logicalAudioName)
        val metadataAudioName = ManagedDownloadTreeNaming.metadataAudioName(actualName)
        return sequenceOf(actualName, pendingAudioName, metadataAudioName)
            .filterNotNull()
            .any { name ->
                ManagedDownloadTreeNaming.isExactTreeStoredName(name, candidateName) ||
                    ManagedDownloadTreeNaming.matchesProviderNumberedName(name, candidateName)
            }
    }

    private data class ExactRootEntryLookup(
        val entry: StoredEntry?,
        val complete: Boolean
    )

    private fun findPendingMetadataForAudioBlocking(
        context: Context,
        root: RootHandle,
        audio: StoredEntry
    ): ExactRootEntryLookup {
        val pendingName = "${audio.logicalName}$PENDING_METADATA_SUFFIX"

        fun findInSingleRoot(candidateRoot: RootHandle): ExactRootEntryLookup {
            return when (candidateRoot) {
                is RootHandle.FileRoot -> {
                    val entries = candidateRoot.dir.listFiles()
                        ?: return ExactRootEntryLookup(null, false)
                    ExactRootEntryLookup(
                        entry = entries.asSequence()
                            .filter(File::isFile)
                            .filter { file ->
                                ManagedDownloadTreeNaming.isPendingMetadataName(
                                    actualName = file.name,
                                    audioName = audio.logicalName
                                )
                            }
                            .map(ManagedDownloadStoredEntryMapper::fromFile)
                            .minWithOrNull(
                                compareBy<StoredEntry>(
                                    { entry ->
                                        ManagedDownloadTreeNaming.metadataNameOrdinal(
                                            entry.name,
                                            audio.logicalName
                                        ) ?: Int.MAX_VALUE
                                    },
                                    StoredEntry::name
                                )
                            ),
                        complete = true
                    )
                }

                is RootHandle.TreeRoot -> {
                    val refresh = treeChildRegistry.treeChildrenForWrite(
                        context,
                        candidateRoot.tree
                    )
                    ExactRootEntryLookup(
                        entry = refresh.children.asSequence()
                            .filterNot(QueriedTreeChild::isDirectory)
                            .filter { child ->
                                child.name == pendingName ||
                                    ManagedDownloadTreeNaming.isPendingMetadataName(
                                        actualName = child.name,
                                        audioName = audio.logicalName
                                    )
                            }
                            .map(ManagedDownloadStoredEntryMapper::fromTreeChild)
                            .minWithOrNull(
                                compareBy<StoredEntry>(
                                    { entry ->
                                        ManagedDownloadTreeNaming.metadataNameOrdinal(
                                            entry.name,
                                            audio.logicalName
                                        ) ?: Int.MAX_VALUE
                                    },
                                    StoredEntry::name
                                )
                            ),
                        complete = refresh.isComplete
                    )
                }
            }
        }

        val rootLookup = findInSingleRoot(root)
        if (!rootLookup.complete || rootLookup.entry != null) return rootLookup
        val temporaryRoot = resolveTemporaryRoot(
            context = context,
            root = root,
            create = false
        ) ?: return ExactRootEntryLookup(null, true)
        return findInSingleRoot(temporaryRoot)
    }

    private fun findExactEntryInRoot(
        context: Context,
        root: RootHandle,
        name: String
    ): ExactRootEntryLookup {
        return when (root) {
            is RootHandle.FileRoot -> {
                ExactRootEntryLookup(
                    entry = File(root.dir, name)
                        .takeIf { file -> file.isFile }
                        ?.toStoredEntry(),
                    complete = root.dir.isDirectory
                )
            }

            is RootHandle.TreeRoot -> {
                val refresh = treeChildRegistry.treeChildrenForWrite(context, root.tree)
                ExactRootEntryLookup(
                    entry = refresh.children
                        .firstOrNull { child -> !child.isDirectory && child.name == name }
                        ?.toStoredEntry(),
                    complete = refresh.isComplete
                )
            }
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
                val temporaryRoot = resolveTemporaryRoot(
                    context = context,
                    root = root,
                    create = true
                ) as? RootHandle.FileRoot
                    ?: throw IOException("无法准备下载 .tmp 目录")
                demotePublishedFileAudio(
                    root = root.dir,
                    publishedName = audio.name,
                    pendingName = pendingName,
                    pendingRoot = temporaryRoot.dir
                )?.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val temporaryRoot = resolveTemporaryRoot(
                    context = context,
                    root = root,
                    create = true
                ) as? RootHandle.TreeRoot
                    ?: throw IOException("无法准备下载 .tmp 目录")
                demotePublishedTreeAudioToTemporary(
                    context = context,
                    root = root,
                    audio = audio,
                    pendingName = pendingName,
                    temporaryRoot = temporaryRoot
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
    ): StartupRecoveryResult = withContext(Dispatchers.IO) {
        fun reportProgress(completedItems: Int, totalItems: Int) {
            runCatching {
                onProgress(
                    completedItems.coerceAtLeast(0),
                    totalItems.coerceAtLeast(0)
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "取消清理进度回调失败，继续执行清理: ${error.message}"
                )
            }
        }
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
        val normalizedDirectoryUri = directoryUri
            ?.trim()
            ?.takeIf(String::isNotBlank)
        try {
            val root = resolveRootForOperation(
                context = context,
                directoryUri = normalizedDirectoryUri,
                useDefaultRootWhenDirectoryUriMissing =
                    useDefaultRootWhenDirectoryUriMissing,
                unavailableMessage = "取消清理源目录不可用，保留 pending 凭据"
            ) ?: return@withContext StartupRecoveryResult(
                failedCount = normalizedOperations.size
            )
            // 清空是破坏性操作，不能依赖可能遗漏刚写入 pending 的旧缓存
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
            val temporary = readTemporaryDirectoryEntries(
                context = context,
                root = root,
                forceRefresh = true,
                rootAlreadyRefreshed = true
            )
            if (!temporary.isComplete) {
                NPLogger.w(
                    TAG,
                    "取消清理跳过不完整 .tmp 目录枚举: operations=${normalizedOperations.size}"
                )
                return@withContext StartupRecoveryResult(
                    failedCount = normalizedOperations.size
                )
            }
            val rootEntries = (refresh.entries + temporary.entries)
                .filterNot(StoredEntry::isDirectory)
            // 先读取正式和待提交元数据。核心提交后的种子元数据已经在根目录
            // 而待提交音频仍在 .tmp，只读待提交元数据
            // 会把这对可恢复音频误判为普通取消残留
            val metadataEntriesToParse = metadataEntriesForPendingArtifacts(rootEntries)
            val parsedMetadataEntries = parseDownloadedAudioMetadataEntriesBatch(
                context = context,
                entries = metadataEntriesToParse
            ).mapNotNull { (entry, metadata) ->
                metadata?.let { value -> ManagedDownloadParsedMetadataEntry(entry, value) }
            }
            val cleanupPlans = normalizedOperations.map { operation ->
                operation to ManagedDownloadPendingArtifactCleanupPlanner.planCancelledOperation(
                    rootEntries = rootEntries,
                    parsedMetadataEntries = parsedMetadataEntries,
                    stableKey = operation.stableKey,
                    operationId = operation.operationId
                )
            }
            val referencesToDelete = cleanupPlans.flatMapTo(linkedSetOf<String>()) { (_, plan) ->
                plan.referencesToDelete
            }
            val protectedPendingReferences = cleanupPlans.flatMapTo(linkedSetOf<String>()) { (_, plan) ->
                plan.protectedReferences
            }
            val deleteReferencesByStableKey = cleanupPlans
                .groupBy({ (operation, _) -> operation.stableKey }, { (_, plan) -> plan })
                .mapValues { (_, plans) ->
                    plans.flatMapTo(linkedSetOf()) { plan -> plan.referencesToDelete }
                }
            val isPendingArtifact: (StoredEntry) -> Boolean = { entry ->
                entry.isPendingAudioWrite ||
                    entry.name.contains(PENDING_AUDIO_WRITE_MARKER) ||
                    entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true) ||
                    ManagedDownloadTreeNaming.isPendingMetadataName(
                        entry.name,
                        ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                            ?: ""
                    )
            }
            val protectedPendingEntryCount = rootEntries
                .asSequence()
                .filter(isPendingArtifact)
                .count { entry -> entry.reference in protectedPendingReferences }
            if (referencesToDelete.isEmpty()) {
                val unresolvedPendingEntries = rootEntries
                    .filter(isPendingArtifact)
                    .filterNot { entry -> entry.reference in protectedPendingReferences }
                if (unresolvedPendingEntries.isNotEmpty()) {
                    NPLogger.w(
                        TAG,
                        "取消清理发现无法证明归属的 pending，保留证据并等待恢复: " +
                            "operations=${normalizedOperations.size}, " +
                            "entries=${unresolvedPendingEntries.size}"
                    )
                    reportProgress(0, unresolvedPendingEntries.size)
                    return@withContext StartupRecoveryResult(
                        failedCount = unresolvedPendingEntries.size,
                        protectedCount = protectedPendingEntryCount,
                        protectedReferences = protectedPendingReferences
                    )
                }
                reportProgress(0, 0)
                return@withContext StartupRecoveryResult(
                    protectedCount = protectedPendingEntryCount,
                    protectedReferences = protectedPendingReferences
                )
            }
            reportProgress(0, referencesToDelete.size)
            val entriesToDelete = rootEntries.filter { entry ->
                entry.reference in referencesToDelete
            }
            val deletePolicy = buildManagedDeletePolicy(
                context = context,
                allowedRoot = root,
                trustedReferences = referencesToDelete
            )
            val trustedReferences = resolveTrustedManagedReferences(
                references = referencesToDelete,
                deletePolicy = deletePolicy
            )
            val completedReferences = AtomicInteger(0)
            val pendingAudioEntries = entriesToDelete.filter(StoredEntry::isPendingAudioWrite)
            val pendingAudioReferences = pendingAudioEntries
                .mapTo(linkedSetOf(), StoredEntry::reference)
            val pendingMetadataEntries = entriesToDelete.filter { entry ->
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                audioName != null && ManagedDownloadTreeNaming.isPendingMetadataName(
                    actualName = entry.name,
                    audioName = audioName
                )
            }
            val pendingMetadataReferences = pendingMetadataEntries
                .mapTo(linkedSetOf(), StoredEntry::reference)
            val pendingAudioByLogicalName = rootEntries
                .asSequence()
                .filterNot(StoredEntry::isDirectory)
                .filter { entry ->
                    entry.isPendingAudioWrite ||
                        entry.name.contains(PENDING_AUDIO_WRITE_MARKER)
                }
                .flatMap { entry ->
                    linkedSetOf<String>().apply {
                        if (entry.isPendingAudioWrite) {
                            add(entry.logicalName)
                        }
                        val markerIndex = entry.name.lastIndexOf(PENDING_AUDIO_WRITE_MARKER)
                        if (markerIndex > 0) {
                            add(entry.name.substring(0, markerIndex))
                        }
                    }.map { logicalName -> logicalName to entry }
                }
                .groupBy(
                    keySelector = { (logicalName, _) -> logicalName },
                    valueTransform = { (_, entry) -> entry }
                )
            val metadataIdentityByReference = parsedMetadataEntries
                .filter { parsed -> parsed.entry.reference in pendingMetadataReferences }
                .associate { parsed ->
                    parsed.entry.reference to terminalTemporaryWriteIdentity(parsed.metadata)
                }

            fun recordTerminalCleanupFor(entries: Collection<StoredEntry>): Boolean {
                val targets = terminalTemporaryWriteCleanupTargets(
                    entries = entries,
                    temporaryWriteIdentityByMetadataReference = metadataIdentityByReference
                )
                if (targets.isEmpty()) return true
                val recorded = recordTerminalTemporaryWriteCleanup(
                    context = context,
                    root = root,
                    targets = targets
                )
                if (!recorded) {
                    NPLogger.w(
                        TAG,
                        "取消下载未能持久化临时写入清理记录，保留 pending 证据: " +
                            "targets=${targets.size}"
                    )
                }
                return recorded
            }

            if (!recordTerminalCleanupFor(pendingAudioEntries)) {
                return@withContext StartupRecoveryResult(
                    failedCount = pendingAudioReferences.size
                )
            }
            val trustedAudioReferences = trustedReferences.filter { reference ->
                reference.externalReference in pendingAudioReferences
            }
            val deletedAudioReferences = deleteReferencesInternalConcurrently(
                context = context,
                references = trustedAudioReferences,
                deletePolicy = deletePolicy,
                invalidateSnapshot = true,
                onDeleteAttemptFinished = { _, _ ->
                    reportProgress(
                        completedItems = completedReferences.incrementAndGet(),
                        totalItems = referencesToDelete.size
                    )
                }
            )
            val unresolvedAudioReferences = pendingAudioReferences - deletedAudioReferences
            if (unresolvedAudioReferences.isNotEmpty()) {
                NPLogger.w(
                    TAG,
                    "取消清理 pending 音频未确认删除，延后 metadata: " +
                        "pending=${pendingAudioReferences.size}, " +
                        "deleted=${deletedAudioReferences.size}, " +
                        "unresolved=${unresolvedAudioReferences.size}"
                )
            }
            val metadataEntriesReadyForDeletion = pendingMetadataEntries.filter { entry ->
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                    ?: return@filter false
                pendingAudioByLogicalName[audioName].orEmpty().all { audio ->
                    audio.reference in deletedAudioReferences
                }
            }
            val metadataReferencesReadyForDeletion = metadataEntriesReadyForDeletion
                .mapTo(hashSetOf(), StoredEntry::reference)
            val deferredMetadataReferences = pendingMetadataReferences
                .asSequence()
                .filterNot { reference -> reference in metadataReferencesReadyForDeletion }
                .toSet()
            val metadataTargetsRecorded = recordTerminalCleanupFor(
                metadataEntriesReadyForDeletion
            )
            if (!metadataTargetsRecorded) {
                return@withContext StartupRecoveryResult(
                    failedCount = deferredMetadataReferences.size +
                        metadataEntriesReadyForDeletion.size
                )
            }
            val trustedMetadataReferences = trustedReferences.filter { reference ->
                reference.externalReference in metadataReferencesReadyForDeletion
            }
            val deletedMetadataReferences = deleteReferencesInternalConcurrently(
                context = context,
                references = trustedMetadataReferences,
                deletePolicy = deletePolicy,
                invalidateSnapshot = true,
                onDeleteAttemptFinished = { _, _ ->
                    reportProgress(
                        completedItems = completedReferences.incrementAndGet(),
                        totalItems = referencesToDelete.size
                    )
                }
            )
            val unresolvedMetadataReferences = pendingMetadataReferences -
                deletedMetadataReferences
            if (unresolvedMetadataReferences.isNotEmpty()) {
                NPLogger.w(
                    TAG,
                    "取消清理 pending metadata 未完全删除，保留凭据: " +
                        "pending=${pendingMetadataReferences.size}, " +
                        "deleted=${deletedMetadataReferences.size}, " +
                        "unresolved=${unresolvedMetadataReferences.size}"
                )
            }
            val deletedReferences = deletedAudioReferences + deletedMetadataReferences
            val temporaryCleanup = when {
                pendingAudioEntries.isEmpty() && metadataEntriesReadyForDeletion.isEmpty() ->
                    StartupRecoveryResult()
                else -> cleanupPersistedTerminalTemporaryWriteArtifacts(context)
            }
            reportProgress(referencesToDelete.size, referencesToDelete.size)
            val failedReferences = (referencesToDelete - deletedReferences) +
                deferredMetadataReferences
            val failedStableKeys = resolveFailedStableKeys(
                referencesByStableKey = deleteReferencesByStableKey,
                failedReferences = failedReferences
            )
            val failedCount = failedReferences.size + temporaryCleanup.failedCount
            NPLogger.d(
                TAG,
                "取消下载 pending 半成品清理完成: operations=${normalizedOperations.size}, " +
                    "cleaned=${deletedReferences.size + temporaryCleanup.cleanedCount}, " +
                    "failed=$failedCount, protected=$protectedPendingEntryCount"
            )
            StartupRecoveryResult(
                cleanedCount = deletedReferences.size + temporaryCleanup.cleanedCount,
                failedCount = failedCount,
                protectedCount = protectedPendingEntryCount,
                protectedReferences = protectedPendingReferences,
                failedStableKeys = failedStableKeys
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
    ): StartupRecoveryResult = withContext(Dispatchers.IO) {
        fun reportProgress(completedItems: Int, totalItems: Int) {
            try {
                onProgress(
                    completedItems.coerceAtLeast(0),
                    totalItems.coerceAtLeast(0)
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                NPLogger.w(TAG, "孤儿 pending 清理进度回调失败: ${error.message}")
            }
        }

        val root = try {
            resolveRootBlocking(context)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            NPLogger.w(TAG, "清空时无法解析下载根目录，保留临时文件: ${error.message}", error)
            return@withContext StartupRecoveryResult(failedCount = 1)
        }
        val refresh = try {
            treeDirectories.refreshRootEntries(context, root)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            NPLogger.w(TAG, "清空时无法枚举下载根目录，保留临时文件: ${error.message}", error)
            return@withContext StartupRecoveryResult(failedCount = 1)
        }
        if (!refresh.isComplete) {
            NPLogger.w(TAG, "清空时根目录枚举不完整，保留 pending 证据")
            return@withContext StartupRecoveryResult(failedCount = 1)
        }
        val temporary = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = true,
            rootAlreadyRefreshed = true
        )
        if (!temporary.isComplete) {
            NPLogger.w(TAG, "清空时 .tmp 枚举不完整，保留 pending 证据")
            return@withContext StartupRecoveryResult(failedCount = 1)
        }

        val normalizedProtected = protectedReferences
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val temporaryReferences = temporary.entries
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .mapTo(linkedSetOf(), StoredEntry::reference)
        val allEntries = (refresh.entries + temporary.entries)
            .filterNot(StoredEntry::isDirectory)
            .distinctBy(StoredEntry::reference)
        val isPendingArtifact: (StoredEntry) -> Boolean = { entry ->
            entry.isPendingAudioWrite ||
                entry.name.contains(PENDING_AUDIO_WRITE_MARKER) ||
                entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true) ||
                ManagedDownloadTreeNaming.isPendingMetadataName(
                    actualName = entry.name,
                    audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name) ?: ""
                )
        }
        val pendingEntries = allEntries.filter(isPendingArtifact)
        if (pendingEntries.isEmpty()) {
            return@withContext StartupRecoveryResult()
        }
        val parsedMetadataByReference = linkedMapOf<String, DownloadedAudioMetadata>()
        val unreadableMetadataReferences = linkedSetOf<String>()
        val metadataEntriesToParse = metadataEntriesForPendingArtifacts(allEntries)
        parseDownloadedAudioMetadataEntriesBatch(
            context = context,
            entries = metadataEntriesToParse
        ).forEach { (entry, metadata) ->
            if (metadata == null) {
                unreadableMetadataReferences += entry.reference
            } else {
                parsedMetadataByReference[entry.reference] = metadata
            }
        }
        val entryByReference = allEntries.associateBy(StoredEntry::reference)
        val orphanPlan = ManagedDownloadPendingArtifactCleanupPlanner
            .planUnownedForExplicitClear(
                entries = allEntries,
                temporaryReferences = temporaryReferences,
                parsedMetadataEntries = parsedMetadataByReference.mapNotNull {
                    (reference, metadata) ->
                    entryByReference[reference]?.let { entry ->
                        ManagedDownloadParsedMetadataEntry(entry, metadata)
                    }
                },
                unreadableMetadataReferences = unreadableMetadataReferences,
                protectedReferences = normalizedProtected
            )
        val protectedPendingReferences = orphanPlan.protectedReferences
        val referencesToDelete = orphanPlan.referencesToDelete

        val unresolvedCount = pendingEntries.count { entry ->
            entry.reference !in protectedPendingReferences &&
                entry.reference !in referencesToDelete
        }
        val deletePolicy = buildManagedDeletePolicy(
            context = context,
            allowedRoot = root,
            trustedReferences = referencesToDelete
        )
        val trustedReferences = resolveTrustedManagedReferences(
            references = referencesToDelete,
            deletePolicy = deletePolicy
        )
        reportProgress(0, trustedReferences.size + unresolvedCount)
        val completed = AtomicInteger(0)
        val deletedReferences = deleteReferencesInternalConcurrently(
            context = context,
            references = trustedReferences,
            deletePolicy = deletePolicy,
            invalidateSnapshot = true,
            onDeleteAttemptFinished = { _, _ ->
                reportProgress(
                    completedItems = completed.incrementAndGet(),
                    totalItems = trustedReferences.size + unresolvedCount
                )
            }
        )
        val failedCount = (trustedReferences.size - deletedReferences.size) + unresolvedCount
        reportProgress(
            completedItems = trustedReferences.size + unresolvedCount,
            totalItems = trustedReferences.size + unresolvedCount
        )
        NPLogger.d(
            TAG,
            "清空孤儿 pending 临时文件收敛完成: pending=${pendingEntries.size}, " +
                "deleted=${deletedReferences.size}, protected=${protectedPendingReferences.size}, " +
                "blocked=${pendingEntries.count { it.reference in unreadableMetadataReferences }}, " +
                "unresolved=$unresolvedCount, failed=$failedCount"
        )
        StartupRecoveryResult(
            cleanedCount = deletedReferences.size,
            failedCount = failedCount,
            protectedCount = pendingEntries.count { it.reference in protectedPendingReferences },
            protectedReferences = protectedPendingReferences
        )
    }

    /** 按入队时记录的根目录回放所有持久终态清理 */
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
                    val recovery = cleanupPersistedTerminalTemporaryWriteEntry(
                        context = context,
                        entry = entry,
                        root = root
                    )
                    cleanedCount += recovery.cleanedCount
                    failedCount += recovery.failedCount
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

    /** 清理一条终态记录，只消费后端操作后重新校验过的目录代次
     * 收尾回调可能在列举父目录时再次加入同一目标，此时保留新代次并交给下一轮恢复
     */
    private suspend fun cleanupPersistedTerminalTemporaryWriteEntry(
        context: Context,
        entry: TerminalTemporaryWriteCleanupJournalEntry,
        root: RootHandle
    ): StartupRecoveryResult {
        var cleanedCount = 0
        var failedCount = 0
        var currentEntry = entry
        var currentRoot = root

        fun aggregate(extraFailedCount: Int = 0): StartupRecoveryResult {
            return StartupRecoveryResult(
                cleanedCount = cleanedCount,
                failedCount = failedCount + extraFailedCount
            )
        }

        for (attempt in 0 until TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS) {
            val recovery = try {
                cleanupTerminalTemporaryWriteArtifactsBlocking(
                    context = context,
                    root = currentRoot,
                    targets = currentEntry.targets
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: SecurityException) {
                NPLogger.w(
                    TAG,
                    "终态临时写入清理缺少权限，保留等待恢复: " +
                        "root=${currentEntry.root.identity}, " +
                        "targets=${currentEntry.targetNames.size}, error=${error.message}"
                )
                StartupRecoveryResult(failedCount = currentEntry.targetNames.size)
            } catch (error: Exception) {
                NPLogger.w(
                    TAG,
                    "终态临时写入清理失败，保留等待恢复: " +
                        "root=${currentEntry.root.identity}, " +
                        "targets=${currentEntry.targetNames.size}, error=${error.message}",
                    error
                )
                StartupRecoveryResult(failedCount = currentEntry.targetNames.size)
            }
            cleanedCount += recovery.cleanedCount
            failedCount += recovery.failedCount
            if (recovery.failedCount > 0) {
                return aggregate()
            }

            if (PersistentTerminalTemporaryWriteCleanupJournal.consume(context, currentEntry)) {
                return aggregate()
            }

            // 当前记录缺失通常表示另一个清理 Worker 已经消费它，目标集合变化或日志不可读
            // 仍属于失败，必须留给下一轮恢复
            val rebasedEntry =
                PersistentTerminalTemporaryWriteCleanupJournal.currentEntryIfTargetsMatch(
                    context = context,
                    entry = currentEntry
                )
            if (rebasedEntry == null) {
                if (PersistentTerminalTemporaryWriteCleanupJournal.consume(context, currentEntry)) {
                    return aggregate()
                }
                NPLogger.w(
                    TAG,
                    "终态临时写入清理已完成但记录未确认消费，保留等待恢复: " +
                        "root=${currentEntry.root.identity}, " +
                        "targets=${currentEntry.targetNames.size}, " +
                        "attempt=${attempt + 1}/" +
                        TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS
                )
                return aggregate(currentEntry.targetNames.size)
            }
            if (rebasedEntry.generationId == currentEntry.generationId) {
                // 目录代次没有变化时再次列举 SAF 不能提高比较安全性，保留记录等待持久重试
                NPLogger.w(
                    TAG,
                    "终态临时写入清理记录消费写入失败，保留等待恢复: " +
                        "root=${currentEntry.root.identity}, " +
                        "targets=${currentEntry.targetNames.size}"
                )
                return aggregate(currentEntry.targetNames.size)
            }
            if (attempt + 1 >= TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS) {
                NPLogger.w(
                    TAG,
                    "终态临时写入清理代际持续变化，保留等待恢复: " +
                        "root=${currentEntry.root.identity}, " +
                        "targets=${currentEntry.targetNames.size}, " +
                        "attempts=${attempt + 1}"
                )
                return aggregate(currentEntry.targetNames.size)
            }
            val rebasedRoot = resolveTerminalTemporaryWriteCleanupRoot(context, rebasedEntry)
            if (rebasedRoot == null) {
                NPLogger.w(
                    TAG,
                    "终态临时写入清理代际已刷新但目录不可恢复，保留等待恢复: " +
                        "root=${rebasedEntry.root.identity}, " +
                        "targets=${rebasedEntry.targetNames.size}"
                )
                return aggregate(rebasedEntry.targetNames.size)
            }
            NPLogger.d(
                TAG,
                "终态临时写入清理检测到同目标代际刷新，重新验证后消费: " +
                    "root=${rebasedEntry.root.identity}, " +
                    "targets=${rebasedEntry.targetNames.size}, " +
                    "attempt=${attempt + 2}/" +
                    TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS
            )
            currentEntry = rebasedEntry
            currentRoot = rebasedRoot
        }
        return aggregate(entry.targetNames.size)
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
                    // manifest 是应用自己的稳定文件, 在写入窗口内无需重复查询根目录
                    maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
                )?.toStoredEntry()
            }
        }
    }

    private fun deletePendingAudioMetadataBlocking(
        context: Context,
        root: RootHandle,
        audioName: String
    ): Boolean {
        val rootEntries = when (root) {
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
        val temporary = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = true,
            rootAlreadyRefreshed = true
        )
        if (!temporary.isComplete) return false
        val entries = rootEntries + temporary.entries
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
        if (configuredDirectoryUri.isNullOrBlank()) {
            return false
        }
        return try {
            resolveRoot()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ManagedDownloadRootProviderException) {
            NPLogger.w(
                TAG,
                "检查 SAF 下载目录时 provider 暂时不可用，保留 SAF 写入模式: " +
                    "${error.message}"
            )
            true
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "检查配置 SAF 下载目录失败，保留 SAF 写入模式: " +
                    "${error.javaClass.simpleName}: ${error.message}"
            )
            true
        }
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
    ): String? = withContext(Dispatchers.IO) {
        val root = resolveRootForOperation(
            context = context.applicationContext,
            directoryUri = directoryUri,
            useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
            unavailableMessage = "读取操作源目录身份失败, 保留恢复凭据"
        ) ?: return@withContext null
        rootKeyForResolvedRoot(root)
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
        seedMetadataJson: String? = null,
        pendingMetadataJson: String? = null
    ): StoredEntry = withContext(Dispatchers.IO) {
        saveAudioFromTempBlocking(
            context = context,
            tempFile = tempFile,
            fileName = fileName,
            mimeType = mimeType,
            expectedSizeBytes = expectedSizeBytes,
            transferSizeVerified = transferSizeVerified,
            seedMetadataJson = seedMetadataJson,
            pendingMetadataJson = pendingMetadataJson
        )
    }

    private fun saveAudioFromTempBlocking(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?,
        expectedSizeBytes: Long?,
        transferSizeVerified: Boolean,
        seedMetadataJson: String?,
        pendingMetadataJson: String?
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
                val temporaryRoot = resolveTemporaryRoot(
                    context = context,
                    root = root,
                    create = true
                ) as? RootHandle.FileRoot
                    ?: throw IOException("无法准备下载 .tmp 目录")
                val existingAudio = findExistingAudioForSeedStableKey(context, seedMetadataJson)
                val reservedFinalName = existingAudio == null
                val finalName = existingAudio?.name
                    ?: treeChildRegistry.reserveUniqueFileChildName(root.dir, boundedFileName)
                val pendingName = buildPendingAudioWriteName(finalName)
                val pendingTarget = File(temporaryRoot.dir, pendingName)
                val audioEntry = try {
                    writeCollisionPendingMetadata(
                        context = context,
                        root = root,
                        requestedAudioName = boundedFileName,
                        actualAudioName = finalName,
                        pendingMetadataJson = pendingMetadataJson
                    )
                    val writeResult = runBlocking(Dispatchers.IO) {
                        FileStorageBackend(temporaryRoot.dir).writeRecoverable(
                            target = StorageTarget.FileTarget(pendingName)
                        ) { output ->
                            tempFile.inputStream().use { input ->
                                input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                            }
                        }
                    }
                    val stored = when (writeResult) {
                        is StorageWriteResult.Written -> {
                            writeResult.stat.toStoredEntryForBackend(temporaryRoot.dir)
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
                val temporaryRoot = resolveTemporaryRoot(
                    context = context,
                    root = root,
                    create = true
                ) as? RootHandle.TreeRoot
                    ?: throw IOException("无法准备下载 .tmp 目录")
                val existingAudio = findExistingAudioForSeedStableKey(context, seedMetadataJson)
                val reservedFinalName = existingAudio == null
                val finalName = existingAudio?.name
                    ?: treeChildRegistry.reserveUniqueTreeChildName(context, root.tree, boundedFileName)
                val createdPendingName = buildPendingAudioWriteName(finalName)
                val audioEntry = try {
                    writeCollisionPendingMetadata(
                        context = context,
                        root = root,
                        requestedAudioName = boundedFileName,
                        actualAudioName = finalName,
                        pendingMetadataJson = pendingMetadataJson
                    )
                    val entry = writeSafFileThroughBackend(
                        context = context,
                        parent = temporaryRoot.tree,
                        displayName = createdPendingName,
                        mimeType = mimeTypeFromName(finalName, mimeType),
                        expectedSizeBytes = actualSizeBytes,
                        sourceFile = tempFile
                    )
                    treeChildRegistry.rememberTreeChild(temporaryRoot.tree, entry)
                    entry
                } catch (error: Throwable) {
                    treeChildRegistry.forgetTreeChildName(
                        temporaryRoot.tree,
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
        finalName: String,
        pendingRoot: File = root
    ): File? {
        val target = File(root, finalName)
        return FileStorageMutationLocks.withTargetLock(target) {
            val pending = File(pendingRoot, pendingName)
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
        pendingName: String,
        pendingRoot: File = root
    ): File? {
        val published = File(root, publishedName)
        return FileStorageMutationLocks.withTargetLock(published) {
            val source = published.takeIf { it.isFile && it.length() > 0L }
                ?: return@withTargetLock null
            val sourceLength = source.length()
            val pending = File(pendingRoot, pendingName)
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
        audio: StoredEntry,
        finalAudioName: String = audio.logicalName
    ): StoredEntry? {
        if (!audio.isPendingAudioWrite) return audio
        val finalName = finalAudioName.takeIf(String::isNotBlank)
            ?.takeIf { candidate ->
                isPendingAudioPromotionFinalNameCandidate(
                    requestedName = audio.logicalName,
                    candidateName = candidate
                )
            }
            ?: return null
        return when (root) {
            is RootHandle.FileRoot -> {
                val pendingFile = File(audio.reference)
                val pendingRoot = pendingFile.parentFile
                    ?.takeIf { it.isDirectory }
                    ?: root.dir
                promotePendingFileAudio(
                    root = root.dir,
                    pendingName = audio.name,
                    finalName = finalName,
                    pendingRoot = pendingRoot
                )?.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val pendingUri = audio.reference.toUri()
                val pendingBackend = SafStorageBackend(context)
                val initialPendingReference = StorageReference.SafRef(pendingUri)
                val pendingStat = pendingBackend.stat(initialPendingReference)
                val rootChildren = treeChildRegistry.cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = 0L
                )
                val pendingIsDirectRootChild = rootChildren.any { child ->
                    !child.isDirectory && sameTreeDocument(child.documentUri, pendingUri)
                }
                val exactTargetCandidates = rootChildren.filter { child ->
                    !child.isDirectory &&
                        ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
                }
                val hasPromotionBackup = rootChildren.any { child ->
                    isTreePromotionBackupName(child.name, finalName)
                }
                if (exactTargetCandidates.size == 1 && !hasPromotionBackup) {
                    val expectedRecoverySizeBytes = when (pendingStat) {
                        is StorageLookupResult.Found -> {
                            pendingStat.value
                                .takeUnless(StorageStat::isDirectory)
                                ?.let {
                                    resolveCurrentTreePendingAudioSize(
                                        backend = pendingBackend,
                                        reference = initialPendingReference,
                                        reportedSizeBytes = pendingStat.value.sizeBytes,
                                        description = audio.name
                                    )
                                }
                        }

                        StorageLookupResult.Missing -> audio.sizeBytes.takeIf { it > 0L }
                        StorageLookupResult.PermissionLost,
                        is StorageLookupResult.ProviderFailure,
                        StorageLookupResult.OutOfScope,
                        is StorageLookupResult.Unsupported -> null
                    }
                    if (expectedRecoverySizeBytes != null) {
                        val recoveryPendingUri = (pendingStat as? StorageLookupResult.Found)
                            ?.value
                            ?.takeUnless(StorageStat::isDirectory)
                            ?.let { pendingUri }
                        val recoveryPendingParent = if (pendingIsDirectRootChild) {
                            root.tree
                        } else {
                            (resolveTemporaryRoot(context, root, create = false)
                                as? RootHandle.TreeRoot)?.tree
                        }
                        val recovered = ManagedDownloadTreeMutationLocks.withLock(root.tree.uri) {
                            val refreshed = treeChildRegistry.treeChildrenForWrite(
                                context,
                                root.tree
                            )
                            reconcileExistingTreePromotionTargetLocked(
                                context = context,
                                root = root,
                                refresh = refreshed,
                                targetUri = exactTargetCandidates.single().documentUri,
                                pendingUri = recoveryPendingUri,
                                pendingName = audio.name,
                                pendingParent = recoveryPendingParent,
                                finalName = finalName,
                                expectedSizeBytes = expectedRecoverySizeBytes,
                                fallbackLastModifiedMs = System.currentTimeMillis()
                            )
                        }
                        if (recovered != null) {
                            return recovered
                        }
                    }
                }
                val pending = when (pendingStat) {
                    is StorageLookupResult.Found -> pendingStat.value
                        .takeUnless(StorageStat::isDirectory)
                        ?.let {
                            if (pendingIsDirectRootChild) {
                                resolvePendingTreeDocument(
                                    context = context,
                                    parent = root.tree,
                                    uri = pendingUri
                                )
                            } else {
                                resolvePendingTemporaryTreeDocument(
                                    context = context,
                                    root = root,
                                    pendingUri = pendingUri,
                                    pendingName = audio.name
                                )
                            }
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
                val treePending = if (pendingIsDirectRootChild) {
                    treeChildRegistry.toTreeDocumentFile(
                        context = context,
                        parent = root.tree,
                        child = pending
                    )
                } else {
                    pending
                }
                val renamedDocument = if (pendingIsDirectRootChild) {
                    renameTreeDocumentWithoutReplacing(
                        context = context,
                        parent = root.tree,
                        document = treePending,
                        finalName = finalName
                    )
                } else {
                    null
                }
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
                    fallbackLastModifiedMs = committedAtMs,
                    pendingParent = if (pendingIsDirectRootChild) {
                        root.tree
                    } else {
                        (resolveTemporaryRoot(context, root, create = false)
                            as? RootHandle.TreeRoot)?.tree
                    }
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

    /** 通过受管目录缓存解析 pending 文档 */
    private fun resolvePendingTemporaryTreeDocument(
        context: Context,
        root: RootHandle.TreeRoot,
        pendingUri: android.net.Uri,
        pendingName: String
    ): DocumentFile? {
        val temporaryRoot = resolveTemporaryRoot(
            context = context,
            root = root,
            create = false
        ) as? RootHandle.TreeRoot ?: return null
        val cached = treeChildRegistry.cachedTreeChildren(
            context = context,
            parent = temporaryRoot.tree,
            maxCacheAgeMs = 0L
        )
        val child = cached.firstOrNull { candidate ->
            !candidate.isDirectory &&
                candidate.name == pendingName &&
                sameTreeDocument(candidate.documentUri, pendingUri)
        } ?: cached.firstOrNull { candidate ->
            !candidate.isDirectory && sameTreeDocument(candidate.documentUri, pendingUri)
        } ?: return null
        return treeChildRegistry.toDocumentFile(
            context = context,
            parent = temporaryRoot.tree,
            child = child
        )
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

    private suspend fun demotePublishedTreeAudioToTemporary(
        context: Context,
        root: RootHandle.TreeRoot,
        audio: StoredEntry,
        pendingName: String,
        temporaryRoot: RootHandle.TreeRoot
    ): StoredEntry? {
        val sourceUri = runCatching { audio.reference.toUri() }.getOrNull() ?: return null
        val backend = SafStorageBackend(context)
        val copied = backend.read(StorageReference.SafRef(sourceUri)) { input ->
            val result = backend.writeRecoverable(
                target = StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(temporaryRoot.tree.uri),
                    displayName = pendingName,
                    mimeType = mimeTypeFromName(pendingName, null)
                )
            ) { output ->
                input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
            }
            when (result) {
                is StorageWriteResult.Written -> result.stat.toStoredEntryForBackend(null)
                StorageWriteResult.Missing -> throw IOException(
                    "SAF 已发布音频复制源不存在: ${audio.name}"
                )
                StorageWriteResult.OutOfScope -> throw IOException(
                    "SAF 已发布音频复制目标越界: ${audio.name}"
                )
                StorageWriteResult.PermissionLost -> throw SecurityException(
                    "SAF 已发布音频复制权限丢失: ${audio.name}"
                )
                is StorageWriteResult.ProviderFailure -> throw IOException(
                    "SAF 已发布音频复制失败: ${audio.name}",
                    result.error
                )
                is StorageWriteResult.Unsupported -> throw IOException(
                    "SAF 已发布音频复制不支持: ${audio.name}"
                )
            }
        }
        val copiedEntry = when (copied) {
            is StorageLookupResult.Found -> copied.value
            StorageLookupResult.Missing -> return null
            StorageLookupResult.PermissionLost -> throw SecurityException(
                "SAF 已发布音频读权限丢失: ${audio.name}"
            )
            is StorageLookupResult.ProviderFailure -> throw IOException(
                "SAF 已发布音频读取失败: ${audio.name}",
                copied.error
            )
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> return null
        }
        val deleted = deleteTrustedReference(
            context,
            TrustedManagedRef(
                reference = StorageReference.SafRef(sourceUri),
                externalReference = sourceUri.toString()
            )
        ).isConfirmedStorageMutation()
        if (!deleted) {
            deleteTrustedReference(
                context,
                TrustedManagedRef(
                    reference = StorageReference.SafRef(copiedEntry.reference.toUri()),
                    externalReference = copiedEntry.reference
                )
            )
            throw IOException("SAF 已发布音频回退后源清理未确认: ${audio.name}")
        }
        treeChildRegistry.forgetTreeChildName(root.tree, audio.name)
        treeChildRegistry.rememberTreeChild(temporaryRoot.tree, copiedEntry)
        return copiedEntry
    }

    private fun reconcileExistingTreePromotionTargetLocked(
        context: Context,
        root: RootHandle.TreeRoot,
        refresh: ManagedDownloadTreeChildRegistry.TreeChildrenRefresh,
        targetUri: Uri,
        pendingUri: Uri?,
        pendingName: String,
        pendingParent: DocumentFile?,
        finalName: String,
        expectedSizeBytes: Long,
        fallbackLastModifiedMs: Long
    ): StoredEntry? {
        if (!refresh.isComplete) {
            NPLogger.w(
                TAG,
                "SAF 提升恢复跳过不完整目录枚举，保留 pending 音频: $finalName"
            )
            return null
        }
        val exactTargets = refresh.children.filter { child ->
            ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
        }
        if (exactTargets.size != 1) {
            return null
        }
        val exactTarget = exactTargets.single()
        if (
            exactTarget.isDirectory ||
                !sameTreeDocument(exactTarget.documentUri, targetUri) ||
                refresh.children.any { child ->
                    isTreePromotionBackupName(child.name, finalName)
                }
        ) {
            return null
        }
        if (
            exactTarget.sizeBytes != null &&
                exactTarget.sizeBytes > 0L &&
                exactTarget.sizeBytes != expectedSizeBytes
        ) {
            NPLogger.w(
                TAG,
                "SAF 提升发现未完成的同名目标，保留目标和 pending: " +
                    "name=$finalName, expected=$expectedSizeBytes, " +
                    "actual=${exactTarget.sizeBytes}"
            )
            return null
        }
        val target = resolveNewTreePromotionDocument(
            context = context,
            parent = root.tree,
            uri = exactTarget.documentUri
        ) ?: return null
        val entry = try {
            verifiedTreeStoredEntry(
                context = context,
                target = target,
                expectedName = finalName,
                expectedSizeBytes = expectedSizeBytes,
                fallbackLastModifiedMs = fallbackLastModifiedMs,
                description = finalName
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "SAF 提升恢复目标校验失败，保留目标和 pending: " +
                    "name=$finalName, error=${error.message}",
                error
            )
            return null
        }
        if (pendingUri != null && !sameTreeDocument(pendingUri, target.uri)) {
            val pendingDeleted = deleteTrustedReference(
                context,
                TrustedManagedRef(
                    reference = StorageReference.SafRef(pendingUri),
                    externalReference = pendingUri.toString()
                )
            ).isConfirmedStorageMutation()
            if (pendingDeleted) {
                treeChildRegistry.forgetTreeChildName(
                    pendingParent ?: root.tree,
                    pendingName
                )
            } else {
                NPLogger.w(
                    TAG,
                    "SAF 提升恢复目标已确认但 pending 清理未确认，保留下次重试: " +
                        "name=$pendingName"
                )
            }
        }
        treeChildRegistry.rememberTreeChild(root.tree, entry)
        NPLogger.d(
            TAG,
            "SAF 提升复用了已提交目标，跳过重复复制: name=$finalName"
        )
        return entry
    }

    private suspend fun copyPendingTreeAudioWithoutReplacing(
        context: Context,
        root: RootHandle.TreeRoot,
        pending: DocumentFile,
        pendingName: String,
        finalName: String,
        expectedSizeBytes: Long,
        fallbackLastModifiedMs: Long,
        pendingParent: DocumentFile? = null
    ): StoredEntry? {
        val backend = SafStorageBackend(context)
        val copied = backend.read(StorageReference.SafRef(pending.uri)) { source ->
            ManagedDownloadTreeMutationLocks.withLock(root.tree.uri) {
                val beforeCreate = treeChildRegistry.treeChildrenForWrite(context, root.tree)
                val recovered = beforeCreate.children
                    .filter { child ->
                        ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
                    }
                    .singleOrNull()
                    ?.let { existingTarget ->
                        reconcileExistingTreePromotionTargetLocked(
                            context = context,
                            root = root,
                            refresh = beforeCreate,
                            targetUri = existingTarget.documentUri,
                            pendingUri = pending.uri,
                            pendingName = pendingName,
                            pendingParent = pendingParent,
                            finalName = finalName,
                            expectedSizeBytes = expectedSizeBytes,
                            fallbackLastModifiedMs = fallbackLastModifiedMs
                        )
                    }
                if (recovered != null) {
                    return@withLock recovered
                }
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
                        treeChildRegistry.forgetTreeChildName(
                            pendingParent ?: root.tree,
                            pendingName
                        )
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
        if (ManagedDownloadTreeNaming.isExactTreeStoredName(actualName, directBackupName)) {
            return true
        }
        if (!actualName.startsWith('.') || !actualName.endsWith(".backup", ignoreCase = true)) {
            return false
        }
        val baseWithIdentifier = actualName
            .drop(1)
            .dropLast(".backup".length)
        val separatorIndex = baseWithIdentifier.lastIndexOf('.')
        if (separatorIndex <= 0) {
            return false
        }
        val backedUpName = baseWithIdentifier.substring(0, separatorIndex)
        if (!ManagedDownloadTreeNaming.isExactTreeStoredName(backedUpName, targetName)) {
            return false
        }
        val identifier = baseWithIdentifier.substring(separatorIndex + 1)
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

    /** 冲突编号后的音频必须先拥有同名 pending 凭据, 进程中断时才能恢复 */
    private fun writeCollisionPendingMetadata(
        context: Context,
        root: RootHandle,
        requestedAudioName: String,
        actualAudioName: String,
        pendingMetadataJson: String?
    ) {
        val content = pendingMetadataJson?.takeIf(String::isNotBlank) ?: return
        if (requestedAudioName == actualAudioName) return
        val temporaryRoot = resolveTemporaryRoot(
            context = context,
            root = root,
            create = true
        ) ?: throw IOException("无法准备下载 .tmp 目录")
        val metadataEntry = writeRootText(
            context = context,
            root = temporaryRoot,
            displayName = "$actualAudioName$PENDING_METADATA_SUFFIX",
            content = content
        )
        if (metadataEntry == null) {
            throw IOException(
                "无法为冲突后的下载音频写入 pending metadata: $actualAudioName"
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

    /** catalog 早于 SAF 设置恢复时，从歌曲自身的 tree 或 document URI 找到真实根目录 */
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
        val resolvedMetadata = resolvedAudio?.let { metadataForAudioEntry(snapshot, it) }

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
        if (entry.isPendingAudioWrite && !allowPending) {
            return null
        }
        val mediaReference = entry.mediaUri
            .trim()
            .takeIf(::isLocalStorageReference)
        val storageReference = entry.reference
            .trim()
            .takeIf(::isLocalStorageReference)
        // 调用方会在需要时把绝对路径转换为 file URI，这里保持原始引用，
        // 也让 Room/JSON 恢复逻辑不依赖 Android Uri 实现
        return mediaReference ?: storageReference
    }

    private fun isLocalStorageReference(reference: String): Boolean {
        return reference.startsWith("/") ||
            reference.startsWith("content:", ignoreCase = true) ||
            reference.startsWith("file:", ignoreCase = true)
    }

    suspend fun findCoverReference(context: Context, audio: StoredEntry): String? = withContext(Dispatchers.IO) {
        val snapshot = resolveSnapshotForIndexedLookup(context)
            ?: buildDownloadLibrarySnapshotBlocking(context)
        ManagedDownloadCoverLookup.findCoverReference(snapshot, audio)
    }

    private suspend fun resolveRoot(context: Context, directoryUriString: String?): RootHandle? = withContext(Dispatchers.IO) {
        resolveRootBlocking(context, directoryUriString)
    }

    private fun resolveRootForOperation(
        context: Context,
        directoryUri: String?,
        useDefaultRootWhenDirectoryUriMissing: Boolean,
        unavailableMessage: String
    ): RootHandle? {
        val normalizedDirectoryUri = directoryUri
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (normalizedDirectoryUri != null) {
            return resolveRootBlocking(context, normalizedDirectoryUri)
                ?: run {
                    NPLogger.w(
                        TAG,
                        "$unavailableMessage: directoryUri=$normalizedDirectoryUri"
                    )
                    null
                }
        }
        return if (useDefaultRootWhenDirectoryUriMissing) {
            rootResolver.createDefaultRoot(context)
        } else {
            resolveRootBlocking(context)
        }
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

    /**
     * 进程重启后的活动迁移优先复用已持久化的源清单，避免再次枚举整个 SAF
     * 树。每个条目仍会在复制阶段通过后端读取，源删除和权限变化不会被静默吞掉
     */
    private data class RestoredMigrationManifest(
        val entries: List<ManagedMigrationEntry>
    )

    private data class ValidatedMigrationCopyReceipts(
        val receipts: Map<String, ManagedMigrationCopyReceipt>,
        val sourceEntriesByReference: Map<String, StoredEntry>
    )

    private fun ManagedDownloadTreeDirectories.ManagedMigrationEntriesRefresh.entryFor(
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

    private suspend fun restoreManagedMigrationEntriesFromJournal(
        root: RootHandle,
        journal: ManagedMigrationReplacementJournal,
        persistedTargetNames: Map<String, String>
    ): RestoredMigrationManifest? = coroutineScope {
        if (!journal.sourceEntryCountKnown) return@coroutineScope null
        val manifest = linkedMapOf<String, ManagedMigrationSourceEntry>()
        journal.sourceEntries.forEach { rawEntry ->
            val reference = rawEntry.sourceReference.trim()
            if (reference.isBlank()) return@coroutineScope null
            val entry = rawEntry.copy(sourceReference = reference)
            val previous = manifest[reference]
            if (previous != null && previous != entry) return@coroutineScope null
            manifest[reference] = entry
        }
        journal.cleanupReceipts.forEach { receipt ->
            val reference = receipt.sourceReference.trim()
            if (reference.isBlank()) return@coroutineScope null
            val receiptEntry = ManagedMigrationSourceEntry(
                sourceReference = reference,
                sourceName = receipt.sourceName,
                sourceSubdirectory = receipt.sourceSubdirectory,
                sizeBytes = receipt.targetEntry.sizeBytes.coerceAtLeast(0L),
                lastModifiedMs = receipt.targetEntry.lastModifiedMs.coerceAtLeast(0L),
                logicalCreatedAtMs = receipt.sourceLogicalCreatedAtMs,
                createdAtSource = receipt.sourceCreatedAtSource,
                createdAtConfidence = receipt.sourceCreatedAtConfidence
            )
            val previous = manifest[reference]
            if (previous != null && (
                    previous.sourceName != receiptEntry.sourceName ||
                        previous.sourceSubdirectory != receiptEntry.sourceSubdirectory
                    )
            ) {
                return@coroutineScope null
            }
            manifest.putIfAbsent(reference, receiptEntry)
        }
        if (manifest.size != journal.sourceEntryCount) return@coroutineScope null
        if (manifest.keys.any { reference -> reference !in persistedTargetNames }) {
            return@coroutineScope null
        }
        if (manifest.values.any { entry -> !isMigrationSourceEntryBoundToRoot(root, entry) }) {
            return@coroutineScope null
        }
        val sourceEntries = manifest.values.sortedWith(
            compareBy<ManagedMigrationSourceEntry>(
                { it.sourceSubdirectory.orEmpty() },
                { it.sourceName },
                { it.sourceReference }
            )
        )
        // 持久清单只是结构恢复线索，不能证明所有源文件仍存在
        // 复制 Worker 会读取源文件并区分 Missing、PermissionLost 和 ProviderFailure
        // 重启时不再完整扫描 SAF
        val entries = sourceEntries.map { sourceEntry ->
            val reference = sourceEntry.sourceReference.trim()
            ManagedMigrationEntry(
                subdirectory = sourceEntry.sourceSubdirectory,
                entry = StoredEntry(
                    name = sourceEntry.sourceName,
                    reference = reference,
                    mediaUri = reference,
                    localFilePath = reference.takeIf { it.startsWith("/") },
                    sizeBytes = sourceEntry.sizeBytes.coerceAtLeast(0L),
                    lastModifiedMs = sourceEntry.lastModifiedMs.coerceAtLeast(0L),
                    isDirectory = false
                ),
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    createdAtMs = sourceEntry.logicalCreatedAtMs,
                    createdAtSource = sourceEntry.createdAtSource,
                    createdAtConfidence = sourceEntry.createdAtConfidence
                )
            )
        }
        RestoredMigrationManifest(
            entries = entries
        )
    }

    private fun isMigrationSourceEntryBoundToRoot(
        root: RootHandle,
        entry: ManagedMigrationSourceEntry
    ): Boolean {
        return isMigrationReferenceBoundToRoot(root, entry.sourceReference)
    }

    private fun isMigrationReferenceBoundToRoot(
        root: RootHandle,
        rawReference: String
    ): Boolean {
        val reference = rawReference.trim()
        return when (root) {
            is RootHandle.FileRoot -> {
                val rootPath = root.dir.absolutePath.trimEnd(File.separatorChar)
                reference == rootPath || reference.startsWith(rootPath + File.separator)
            }

            is RootHandle.TreeRoot -> {
                val referenceUri = runCatching { reference.toUri() }.getOrNull()
                    ?: return false
                if (
                    !referenceUri.scheme.equals("content", ignoreCase = true) ||
                    !referenceUri.authority.equals(
                        root.tree.uri.authority,
                        ignoreCase = true
                    )
                ) {
                    return false
                }
                val treeDocumentId = runCatching {
                    DocumentsContract.getTreeDocumentId(root.tree.uri)
                }.getOrNull() ?: return false
                val referenceTreeDocumentId = runCatching {
                    DocumentsContract.getTreeDocumentId(referenceUri)
                }.getOrNull()
                if (referenceTreeDocumentId != null) {
                    return referenceTreeDocumentId == treeDocumentId
                }
                val documentId = runCatching {
                    DocumentsContract.getDocumentId(referenceUri)
                }.getOrNull() ?: return false
                isMigrationDocumentIdWithinTree(
                    treeDocumentId = treeDocumentId,
                    documentId = documentId
                )
            }
        }
    }

    /** 进程终止后根据持久复制凭据恢复目标索引
     * 直接探测完整时无需遍历目录，凭据不确定时退回有界快照
     */
    private suspend fun buildMigrationTargetIndexFromReceipts(
        context: Context,
        targetRoot: RootHandle,
        entries: List<ManagedMigrationEntry>,
        persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt>,
        persistedTargetNames: Map<String, String>
    ): ManagedMigrationTargetIndex? = coroutineScope {
        if (entries.isEmpty() || persistedCopyReceipts.size < entries.size) {
            return@coroutineScope null
        }
        val receiptPairs = entries.mapNotNull { entry ->
            val receipt = persistedCopyReceipts[entry.entry.reference] ?: return@mapNotNull null
            val persistedName = persistedTargetNames[entry.entry.reference]
            if (
                receipt.sourceReference != entry.entry.reference ||
                receipt.sourceName != entry.entry.name ||
                receipt.sourceSubdirectory != entry.subdirectory ||
                !isSafeMigrationPlanName(receipt.targetEntry.name) ||
                persistedName != null && persistedName != receipt.targetEntry.name
            ) {
                return@mapNotNull null
            }
            entry to receipt
        }
        if (receiptPairs.size != entries.size) return@coroutineScope null

        fun buildIndex(
            resolvedEntries: List<Pair<String?, StoredEntry>>
        ): ManagedMigrationTargetIndex? {
            val duplicateKeys = resolvedEntries
                .map { (subdirectory, entry) -> subdirectory to entry.name }
                .let { keys -> keys.size != keys.toSet().size }
            if (duplicateKeys) return null
            return ManagedDownloadMigrationTargetIndexBuilder.build(
                rootEntries = resolvedEntries
                    .filter { (subdirectory, _) -> subdirectory == null }
                    .map { (_, entry) -> entry },
                coverEntries = resolvedEntries
                    .filter { (subdirectory, _) -> subdirectory == COVER_SUBDIRECTORY }
                    .map { (_, entry) -> entry },
                lyricEntries = resolvedEntries
                    .filter { (subdirectory, _) -> subdirectory == LYRIC_SUBDIRECTORY }
                    .map { (_, entry) -> entry }
            )
        }

        val statLimiter = Semaphore(
            migrationCopyParallelism(
                sourceRoot = targetRoot,
                targetRoot = targetRoot
            ).coerceAtLeast(1)
        )
        val actualEntries = receiptPairs.map { (entry, receipt) ->
            async(Dispatchers.IO) {
                statLimiter.withPermit {
                    statMigrationReceiptTarget(
                        context = context,
                        targetRoot = targetRoot,
                        receipt = receipt
                    )?.let { actual -> entry.subdirectory to actual }
                }
            }
        }.awaitAll()
        if (actualEntries.all { it != null }) {
            buildIndex(actualEntries.filterNotNull())?.let { return@coroutineScope it }
        }

        // 直接探测失败时无法确认受管子目录结构，保留完整回退扫描
        // 最终校验负责确认 Covers 和 Lyrics 位置并清理临时文件
        val snapshot = try {
            treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (snapshot?.isComplete != true) return@coroutineScope null
        val snapshotEntries = receiptPairs.mapNotNull { (entry, receipt) ->
            if (!isMigrationReferenceBoundToRoot(targetRoot, receipt.targetEntry.reference)) {
                return@mapNotNull null
            }
            val actual = snapshot.entryFor(entry.subdirectory, receipt.targetEntry)
                ?.takeIf { candidate ->
                    !candidate.isDirectory && candidate.name == receipt.targetEntry.name
                }
                ?: return@mapNotNull null
            entry.subdirectory to actual
        }
        if (snapshotEntries.size != receiptPairs.size) return@coroutineScope null
        buildIndex(snapshotEntries)
    }

    private suspend fun statMigrationReceiptTarget(
        context: Context,
        targetRoot: RootHandle,
        receipt: ManagedMigrationCopyReceipt
    ): StoredEntry? {
        val reference = receipt.targetEntry.reference.trim()
        if (!isMigrationReferenceBoundToRoot(targetRoot, reference)) return null
        val backendTarget = backendReference(context, reference) ?: return null
        return when (val result = backendTarget.backend.stat(backendTarget.reference)) {
            is StorageLookupResult.Found -> {
                val actual = result.value.toStoredEntryForBackend(
                    (targetRoot as? RootHandle.FileRoot)?.dir
                )
                actual.takeUnless { entry ->
                    entry.isDirectory ||
                        entry.name != receipt.targetEntry.name ||
                        !sameManagedMigrationStoredEntryIdentity(receipt.targetEntry, entry)
                }
            }
            StorageLookupResult.Missing,
            StorageLookupResult.PermissionLost,
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.ProviderFailure,
            is StorageLookupResult.Unsupported -> null
        }
    }

    private suspend fun validateMigrationSourceCopyReceipts(
        context: Context,
        sourceRoot: RootHandle,
        entries: List<ManagedMigrationEntry>,
        persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt>,
        preferDirectStats: Boolean
    ): ValidatedMigrationCopyReceipts = coroutineScope {
        if (entries.isEmpty() || persistedCopyReceipts.isEmpty()) {
            return@coroutineScope ValidatedMigrationCopyReceipts(
                receipts = emptyMap(),
                sourceEntriesByReference = emptyMap()
            )
        }

        // 新迁移可以使用完整源快照，进程终止后的恢复只需直接校验持久凭据
        // 不必再次遍历整棵 SAF 目录
        val snapshot = if (preferDirectStats) {
            null
        } else {
            try {
                treeDirectories.refreshManagedMigrationEntries(context, sourceRoot)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }
        if (snapshot?.isComplete == true) {
            val validated = entries.mapNotNull { sourceEntry ->
                val receipt = persistedCopyReceipts[sourceEntry.entry.reference]
                    ?: return@mapNotNull null
                if (!isMigrationReferenceBoundToRoot(sourceRoot, sourceEntry.entry.reference)) {
                    return@mapNotNull null
                }
                val current = snapshot.entryFor(sourceEntry.subdirectory, sourceEntry.entry)
                    ?: return@mapNotNull null
                if (
                    !isCurrentMigrationSourceFingerprint(
                        receipt = receipt,
                        sourceName = sourceEntry.entry.name,
                        entry = current
                    )
                ) {
                    return@mapNotNull null
                }
                sourceEntry.entry.reference to (receipt to current)
            }.toMap()
            return@coroutineScope ValidatedMigrationCopyReceipts(
                receipts = validated.mapValues { (_, value) -> value.first },
                sourceEntriesByReference = validated.mapValues { (_, value) -> value.second }
            )
        }

        val statLimiter = Semaphore(
            migrationCopyParallelism(
                sourceRoot = sourceRoot,
                targetRoot = sourceRoot
            ).coerceAtLeast(1)
        )
        val checks = entries.mapNotNull { sourceEntry ->
            val receipt = persistedCopyReceipts[sourceEntry.entry.reference]
                ?: return@mapNotNull null
            async(Dispatchers.IO) {
                statLimiter.withPermit {
                    // 清单中的 stat 只证明打开日志时源文件存在，复用凭据前仍要重新探测
                    val statResult = statMigrationReceiptSource(
                        context = context,
                        sourceRoot = sourceRoot,
                        sourceEntry = sourceEntry.entry
                    )
                    if (
                        isCurrentMigrationSourceFingerprint(
                            receipt = receipt,
                            sourceName = sourceEntry.entry.name,
                            statResult = statResult
                        )
                    ) {
                        val stat = (statResult as? StorageLookupResult.Found)?.value
                        stat?.let {
                            sourceEntry.entry.reference to (receipt to sourceEntry.entry.copy(
                                sizeBytes = it.sizeBytes ?: sourceEntry.entry.sizeBytes,
                                lastModifiedMs = it.lastModifiedMs
                                    ?: sourceEntry.entry.lastModifiedMs,
                                sizeKnown = it.sizeBytes != null || sourceEntry.entry.sizeKnown
                            ))
                        }
                    } else {
                        null
                    }
                }
            }
        }
        val validated = checks.awaitAll().filterNotNull().toMap()
        ValidatedMigrationCopyReceipts(
            receipts = validated.mapValues { (_, value) -> value.first },
            sourceEntriesByReference = validated.mapValues { (_, value) -> value.second }
        )
    }

    private suspend fun statMigrationReceiptSource(
        context: Context,
        sourceRoot: RootHandle,
        sourceEntry: StoredEntry
    ): StorageLookupResult<StorageStat> {
        return try {
            val reference = sourceEntry.reference.trim()
            if (!isMigrationReferenceBoundToRoot(sourceRoot, reference)) {
                return StorageLookupResult.OutOfScope
            }
            val backendSource = backendReference(context, reference)
                ?: return StorageLookupResult.OutOfScope
            backendSource.backend.stat(backendSource.reference)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StorageLookupResult.ProviderFailure(error)
        }
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
        val audioLastModifiedByName = rootEntries
            .asSequence()
            .filter { entry -> entry.extension in audioExtensions }
            .associate { entry -> entry.name to entry.lastModifiedMs }
        val parsedMetadataByAudioName = parseDownloadedAudioMetadataBatch(
            context = context,
            entries = metadataEntriesByAudioName.map { (audioName, entry) ->
                audioName to entry
            }
        ).mapNotNull { (audioName, metadata) ->
            metadata?.let {
                audioName to enrichMigrationMetadataTemporalFields(
                    metadata = it,
                    audioLastModifiedMs = audioLastModifiedByName[audioName]
                )
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

    private fun enrichMigrationMetadataTemporalFields(
        metadata: DownloadedAudioMetadata,
        audioLastModifiedMs: Long?
    ): DownloadedAudioMetadata {
        val fallbackTimestamp = audioLastModifiedMs?.takeIf { it > 0L } ?: return metadata
        if (
            metadata.createdAtMs?.let { it > 0L } == true ||
                metadata.sourceCreatedAtMs?.let { it > 0L } == true
        ) {
            return metadata
        }
        return metadata.copy(
            createdAtMs = fallbackTimestamp,
            createdAtSource = metadata.createdAtSource ?: "MTIME",
            createdAtConfidence = metadata.createdAtConfidence ?: "INFERRED",
            sourceModifiedAtMs = metadata.sourceModifiedAtMs ?: fallbackTimestamp
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

    private fun requireMigrationSourceHasNoPendingArtifacts(
        context: Context,
        sourceRoot: RootHandle
    ) {
        discardMigrationTemporaryDirectory(context, sourceRoot)
    }

    private fun discardMigrationTemporaryDirectory(
        context: Context,
        root: RootHandle
    ): Boolean {
        val deletedReferences = linkedSetOf<String>()
        when (root) {
            is RootHandle.FileRoot -> {
                val children = root.dir.listFiles()
                    ?: throw ManagedDownloadMigrationException.transient(
                        "迁移前无法检查源目录临时文件"
                    )
                children
                    .asSequence()
                    .filter(File::isDirectory)
                    .filter { directory ->
                        ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                            directory.name,
                            DOWNLOAD_TEMPORARY_DIR_NAME
                        )
                    }
                    .forEach { directory ->
                        if (!directory.deleteRecursively() && directory.exists()) {
                            throw ManagedDownloadMigrationException.transient(
                                "迁移前无法删除源目录 .tmp"
                            )
                        }
                        deletedReferences += directory.absolutePath
                    }
            }

            is RootHandle.TreeRoot -> {
                val refresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                    context = context,
                    parent = root.tree
                )
                if (!refresh.isComplete) {
                    throw ManagedDownloadMigrationException.transient(
                        "迁移前源目录枚举不完整，暂缓处理临时文件"
                    )
                }
                refresh.children
                    .asSequence()
                    .filter(QueriedTreeChild::isDirectory)
                    .filter { child ->
                        ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                            child.name,
                            DOWNLOAD_TEMPORARY_DIR_NAME
                        )
                    }
                    .forEach { child ->
                        val directory = treeChildRegistry.toDocumentFile(
                            context,
                            root.tree,
                            child
                        ) ?: throw ManagedDownloadMigrationException.transient(
                            "迁移前无法读取源目录 .tmp"
                        )
                        when (
                            deleteTrustedReference(
                                context,
                                TrustedManagedRef(
                                    reference = StorageReference.SafRef(directory.uri),
                                    externalReference = directory.uri.toString()
                                )
                            )
                        ) {
                            StorageMutationResult.Deleted,
                            StorageMutationResult.Missing -> {
                                deletedReferences += directory.uri.toString()
                            }
                            StorageMutationResult.OutOfScope,
                            StorageMutationResult.PermissionLost,
                            is StorageMutationResult.ProviderFailure,
                            is StorageMutationResult.Unsupported -> {
                                throw ManagedDownloadMigrationException.transient(
                                    "迁移前无法删除源目录 .tmp"
                                )
                            }
                        }
                    }
            }
        }
        forgetDeletedReferencesFromCaches(deletedReferences)
        if (deletedReferences.isNotEmpty()) {
            NPLogger.i(
                TAG,
                "迁移前已整体删除 .tmp 目录: count=${deletedReferences.size}"
            )
        }
        return true
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

    /** 只读取和 pending 同名的 metadata，避免大曲库清空时逐首打开无关文件 */
    private fun metadataEntriesForPendingArtifacts(
        entries: Collection<StoredEntry>
    ): List<StoredEntry> {
        val pendingAudioNames = entries.asSequence()
            .filterNot(StoredEntry::isDirectory)
            .flatMap { entry ->
                buildList {
                    if (entry.isPendingAudioWrite && entry.logicalName.isNotBlank()) {
                        add(entry.logicalName)
                    }
                    val markerIndex = entry.name.lastIndexOf(PENDING_AUDIO_WRITE_MARKER)
                    if (markerIndex > 0) {
                        add(entry.name.substring(0, markerIndex))
                    }
                    val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                    if (
                        audioName != null &&
                        ManagedDownloadTreeNaming.isPendingMetadataName(
                            actualName = entry.name,
                            audioName = audioName
                        )
                    ) {
                        add(audioName)
                    }
                }.asSequence()
            }
            .filter(String::isNotBlank)
            .toSet()
        if (pendingAudioNames.isEmpty()) return emptyList()
        return entries.filter { entry ->
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                ?: return@filter false
            audioName in pendingAudioNames
        }
    }

    /** 并行读取清理所需的 metadata，单个 provider 失败时保留其余结果 */
    private suspend fun parseDownloadedAudioMetadataEntriesBatch(
        context: Context,
        entries: Collection<StoredEntry>
    ): List<Pair<StoredEntry, DownloadedAudioMetadata?>> {
        if (entries.isEmpty()) return emptyList()
        return coroutineScope {
            entries.toList()
                .chunked(METADATA_SCAN_PARALLELISM)
                .flatMap { batch ->
                    batch.map { entry ->
                        async(metadataScanDispatcher) {
                            val metadata = try {
                                val raw = readTextInternalSuspending(context, entry.reference)
                                raw?.let(::parseDownloadedAudioMetadataJson)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                NPLogger.w(
                                    TAG,
                                    "读取清理 metadata 失败，保留 pending 证据: " +
                                        "name=${entry.name}, error=${error.message}"
                                )
                                null
                            }
                            entry to metadata
                        }
                    }.awaitAll()
                }
        }
    }

    /**
     * 并行读取互不相关的 metadata，单个 sidecar 暂时不可读时保留其余音频结果
     */
    private fun parseDownloadedAudioMetadataBatch(
        context: Context,
        entries: Collection<Pair<String, StoredEntry>>
    ): Map<String, DownloadedAudioMetadata?> {
        if (entries.isEmpty()) return emptyMap()
        return runBlocking(Dispatchers.IO) {
            coroutineScope {
                val results = linkedMapOf<String, DownloadedAudioMetadata?>()
                entries.toList()
                    .chunked(METADATA_SCAN_PARALLELISM)
                    .forEach { batch ->
                        batch.map { (audioName, entry) ->
                            async(metadataScanDispatcher) {
                                val metadata = try {
                                    val raw = readTextInternalSuspending(context, entry.reference)
                                    raw?.let(::parseDownloadedAudioMetadataJson)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    NPLogger.w(
                                        TAG,
                                        "读取下载 metadata 失败，保留音频并等待重试: " +
                                            "name=$audioName, error=${error.message}"
                                    )
                                    null
                                }
                                audioName to metadata
                            }
                        }.awaitAll().forEach { (audioName, metadata) ->
                            results[audioName] = metadata
                        }
                    }
                results
            }
        }
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
                return StartupRecoveryResult(failedCount = 1)
            }
            val temporary = readTemporaryDirectoryEntries(
                context = context,
                root = root,
                forceRefresh = true,
                rootAlreadyRefreshed = true
            )
            if (!temporary.isComplete) {
                NPLogger.w(TAG, "下载 .tmp 目录枚举不完整，跳过待提交音频清理")
                return StartupRecoveryResult(failedCount = 1)
            }
            val rootEntries = (refresh.entries + temporary.entries)
                .filterNot(StoredEntry::isDirectory)
            val metadataNames = rootEntries.mapTo(linkedSetOf(), StoredEntry::name)
            val pendingEntries = rootEntries.filter { entry ->
                entry.isPendingAudioWrite ||
                    entry.name.contains(PENDING_AUDIO_WRITE_MARKER)
            }
            val unresolvedEntries = pendingEntries.filterNot { entry ->
                val logicalName = pendingAudioWriteNames.logicalAudioName(entry.name)
                metadataNames.any { candidate ->
                    candidate == "$logicalName$METADATA_SUFFIX" ||
                        ManagedDownloadTreeNaming.isPendingMetadataName(
                            actualName = candidate,
                            audioName = logicalName
                        )
                }
            }
            if (unresolvedEntries.isNotEmpty()) {
                NPLogger.w(
                    TAG,
                    "待提交音频缺少可验证 metadata，保留 payload 等待恢复: " +
                        "count=${unresolvedEntries.size}"
                )
            }
            // 没有 owner/终态凭据时不能猜测删除。已完成的 pending 会由
            // recoverPendingAudioWritesFromRoot 先提升，取消项由 operation planner 清理
            if (pendingEntries.isEmpty()) {
                return StartupRecoveryResult()
            }
            StartupRecoveryResult(
                failedCount = unresolvedEntries.size
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: SecurityException) {
            throw error
        } catch (error: ManagedDownloadRootUnavailableException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "下载目录不可用，跳过待提交音频清理: ${error.message}")
            StartupRecoveryResult(failedCount = 1)
        }
    }

    /**
     * 迁移释放目录栅栏后收敛旧版本和新版本的待提交凭据
     *
     * 该入口只在迁移 Worker 完成后调用，避免把迁移期间的临时音频误删
     */
    internal suspend fun reconcilePendingArtifactsAfterStorageMutation(
        context: Context
    ): StartupRecoveryResult = withContext(Dispatchers.IO) {
        val pending = cleanupPendingAudioWrites(context)
        val unfinalized = cleanupUnfinalizedDownloadArtifacts(context)
        val terminal = cleanupPersistedTerminalTemporaryWriteArtifacts(context)
        StartupRecoveryResult(
            cleanedCount = pending.cleanedCount +
                unfinalized.cleanedCount +
                terminal.cleanedCount,
            failedCount = pending.failedCount +
                unfinalized.failedCount +
                terminal.failedCount,
            protectedCount = pending.protectedCount +
                unfinalized.protectedCount +
                terminal.protectedCount,
            protectedReferences = pending.protectedReferences +
                unfinalized.protectedReferences +
                terminal.protectedReferences
        )
    }

    internal fun cleanupUnfinalizedDownloadArtifacts(context: Context): StartupRecoveryResult {
        return try {
            val root = resolveRootBlocking(context)
            val refresh = treeDirectories.refreshManagedMigrationEntries(context, root)
            if (!refresh.isComplete) {
                NPLogger.w(TAG, "下载目录枚举不完整，跳过未完成半成品清理")
                return StartupRecoveryResult(failedCount = 1)
            }
            val temporary = readTemporaryDirectoryEntries(
                context = context,
                root = root,
                forceRefresh = true,
                rootAlreadyRefreshed = true
            )
            if (!temporary.isComplete) {
                NPLogger.w(TAG, "下载 .tmp 目录枚举不完整，跳过未完成半成品清理")
                return StartupRecoveryResult(failedCount = 1)
            }
            val rootEntries = (refresh.rootEntries + temporary.entries)
                .filterNot(StoredEntry::isDirectory)
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
            StartupRecoveryResult(failedCount = 1)
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
        targetRoot: RootHandle,
        skipMetadataParsing: Boolean = false
    ): ManagedMigrationTargetIndex {
        val refresh = treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
        requireCompleteMigrationDirectoryScan(
            root = targetRoot,
            isComplete = refresh.isComplete
        )
        val parsedMetadataByAudioName = if (skipMetadataParsing) {
            null
        } else {
            val metadataEntries = refresh.rootEntries
                .asSequence()
                .filterNot(StoredEntry::isDirectory)
                .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
                .mapNotNull { entry ->
                    ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                        audioName to entry
                    }
                }
                .groupBy({ (audioName, _) -> audioName }, { (_, entry) -> entry })
                .mapNotNull { (audioName, entries) ->
                    entries.minWithOrNull(
                        compareBy<StoredEntry>(
                            { candidate ->
                                ManagedDownloadTreeNaming.metadataNameOrdinal(
                                    candidate.name,
                                    audioName
                                ) ?: Int.MAX_VALUE
                            },
                            StoredEntry::name
                        )
                    )?.let { entry -> audioName to entry }
                }
            parseDownloadedAudioMetadataBatch(
                context = context,
                entries = metadataEntries
            ).mapNotNull { (audioName, metadata) ->
                metadata?.let { audioName to it }
            }.toMap()
        }
        return ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = refresh.rootEntries,
            coverEntries = refresh.coverEntries,
            lyricEntries = refresh.lyricEntries,
            parsedMetadataByAudioName = parsedMetadataByAudioName
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
            sizeKnown = sizeBytes != null,
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
        // SAF Provider 决定物理时间，来源时间保存在元数据中
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
        return rootKeyForResolvedRoot(resolvedRoot)
    }

    private fun rootKeyForResolvedRoot(root: RootHandle): String {
        return when (root) {
            is RootHandle.TreeRoot -> {
                val treeIdentity = ManagedDownloadDirectoryIdentity.directoryIdentity(
                    root.tree.uri.toString()
                ) ?: root.tree.uri.toString()
                "tree:$treeIdentity"
            }
            is RootHandle.FileRoot -> "file:${root.dir.absolutePath}"
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
        return runBlocking(Dispatchers.IO) {
            readTextInternalSuspending(context, reference)
        }
    }

    private suspend fun readTextInternalSuspending(
        context: Context,
        reference: String
    ): String? {
        val target = backendReference(context, reference) ?: return null
        return when (val result = target.backend.read(target.reference) { input ->
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
                // DocumentsProvider 可能把已经删除的 child 包装成
                // IllegalArgumentException。它不是 root 故障，不能让一次陈旧
                // sidecar 读取升级为未捕获异常或阻塞整批扫描
                if (ManagedDownloadReferenceIo.isMissingDocumentFailure(result.error)) {
                    NPLogger.d(
                        TAG,
                        "读取托管文本时确认文件已不存在，按缺失处理: reference=$reference"
                    )
                    null
                } else {
                    throw ManagedDownloadRootProviderException(reference, result.error)
                }
            }
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> null
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

    private suspend fun deleteEnumeratedMigrationReference(
        context: Context,
        reference: TrustedManagedRef,
        root: RootHandle
    ): StorageMutationResult {
        // 在一次短事务内完成列举和删除，避免并发清理让另一个 Worker 使用失效凭据
        return migrationCleanupTrustLock.withLock {
            val trustedReferences = try {
                enumerateCompleteRootReferences(context, root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return@withLock error.toMigrationDeletionResult()
            } ?: return@withLock StorageMutationResult.ProviderFailure(
                IllegalStateException("迁移删除前的完整枚举未完成")
            )
            val enumeratedReference = trustedReferences.firstOrNull { trusted ->
                trusted.externalReference == reference.externalReference
            } ?: run {
                NPLogger.w(
                    TAG,
                    "迁移删除引用未来自当前完整枚举，保留源: ${reference.externalReference}"
                )
                return@withLock StorageMutationResult.OutOfScope
            }
            try {
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.toMigrationDeletionResult()
            }
        }
    }

    private suspend fun deleteEnumeratedMigrationReferences(
        context: Context,
        references: Collection<TrustedManagedRef>,
        root: RootHandle,
        trustedReferencesSnapshot: Set<TrustedManagedRef>? = null,
        onDeleteStarted: (TrustedManagedRef) -> Unit = {},
        onDeleteFinished: (TrustedManagedRef) -> Unit = {}
    ): Map<TrustedManagedRef, StorageMutationResult> =
        migrationCleanupTrustLock.withLock {
            if (references.isEmpty()) return@withLock emptyMap()
            val trustedReferences = trustedReferencesSnapshot ?: try {
                enumerateCompleteRootReferences(context, root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val result = error.toMigrationDeletionResult()
                return@withLock references.associateWith { result }
            } ?: run {
                val result = StorageMutationResult.ProviderFailure(
                    IllegalStateException("迁移删除前的完整枚举未完成")
                )
                return@withLock references.associateWith { result }
            }
            val trustedReferenceSet = trustedReferences.mapTo(linkedSetOf()) {
                it.externalReference
            }
            // 整个批次复用不可变的完整枚举信任快照, 只允许快照中的引用进入删除
            val trustedByExternalReference = trustedReferences.associateBy(
                TrustedManagedRef::externalReference
            )
            val eligibleReferences = references.asSequence()
                .mapNotNull { reference ->
                    if (reference.externalReference in trustedByExternalReference) {
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
                .distinctBy(TrustedManagedRef::externalReference)
                .toList()
            val eligibleResults = if (eligibleReferences.isEmpty()) {
                emptyMap()
            } else {
                val deletePolicy = buildManagedDeletePolicy(
                    context = context,
                    allowedRoot = root,
                    trustedReferences = trustedReferenceSet
                )
                val deleteParallelism = migrationDeleteParallelism(root).coerceAtLeast(1)
                val startedAtMs = System.currentTimeMillis()
                val deleteResult = referenceDeleteExecutor.deleteReferencesConcurrently(
                    context = context,
                    references = eligibleReferences,
                    deletePolicy = deletePolicy,
                    parallelism = deleteParallelism,
                    onDeleteStarted = onDeleteStarted,
                    onDeleteAttemptFinished = { reference, _ ->
                        onDeleteFinished(reference)
                    }
                )
                val unresolvedReferences = eligibleReferences.filterNot { reference ->
                    reference.externalReference in deleteResult.deletedReferences
                }
                val classifyLimiter = Semaphore(deleteParallelism)
                val unresolvedResults = coroutineScope {
                    unresolvedReferences.map { reference ->
                        async(Dispatchers.IO) {
                            classifyLimiter.withPermit {
                                try {
                                    classifyMigrationDeleteFailure(context, reference)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    error.toMigrationDeletionResult()
                                }
                            }
                        }
                    }.awaitAll()
                        .mapIndexed { index, result ->
                            unresolvedReferences[index].externalReference to result
                        }
                        .toMap()
                }
                val resultsByExternalReference = eligibleReferences.associate { reference ->
                    reference.externalReference to if (
                        reference.externalReference in deleteResult.deletedReferences
                    ) {
                        StorageMutationResult.Deleted
                    } else {
                        unresolvedResults[reference.externalReference]
                            ?: StorageMutationResult.ProviderFailure(
                                IllegalStateException("迁移源文件删除结果缺失")
                            )
                    }
                }
                NPLogger.d(
                    TAG,
                    "迁移源清理完成: requested=${eligibleReferences.size}, " +
                        "costMs=${System.currentTimeMillis() - startedAtMs}, " +
                        "parallelism=$deleteParallelism"
                )
                resultsByExternalReference
            }
            buildMap {
                references.forEach { reference ->
                    put(
                        reference,
                        eligibleResults[reference.externalReference]
                            ?: StorageMutationResult.OutOfScope
                    )
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
        // 迁移清理必须依赖一次完整的根目录和侧载目录列举
        // 只列根目录会把嵌套的 Covers 和 Lyrics 文件遗留下来
        val refresh = treeDirectories.refreshManagedMigrationEntries(context, root)
        if (!refresh.isComplete) {
            return null
        }
        return trustedReferencesFromMigrationRefresh(refresh)
    }

    private fun trustedReferencesFromMigrationRefresh(
        refresh: ManagedDownloadTreeDirectories.ManagedMigrationEntriesRefresh
    ): Set<TrustedManagedRef> {
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
        } catch (error: CancellationException) {
            throw error
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
        invalidateSnapshot: Boolean,
        onDeleteAttemptFinished: (TrustedManagedRef, Boolean) -> Unit = { _, _ -> }
    ): Set<String> {
        val deleteResult = referenceDeleteExecutor.deleteReferencesConcurrently(
            context = context,
            references = references,
            deletePolicy = deletePolicy,
            onDeleteAttemptFinished = onDeleteAttemptFinished
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
