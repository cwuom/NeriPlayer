package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object ManagedDownloadStorage {
    private const val TAG = "ManagedDownloadStorage"
    private const val LOG_HOT_AUDIO_HITS = false
    private const val ROOT_DIR_NAME = "NeriPlayer"
    private const val COVER_SUBDIRECTORY = "Covers"
    private const val LYRIC_SUBDIRECTORY = "Lyrics"
    private const val DOWNLOAD_STAGING_DIR_NAME = "download_staging"
    private const val DOWNLOAD_STAGING_FILE_PREFIX = "npdl_"
    private const val DOWNLOAD_STAGING_FILE_SUFFIX = ".download"
    private const val DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX = ".hls.json"
    private const val DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX = ".resume.json"
    private const val DOWNLOAD_STAGING_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val PENDING_AUDIO_WRITE_MARKER = ".npdl_pending"
    private const val NO_MEDIA_FILE_NAME = ".nomedia"
    private const val SNAPSHOT_CACHE_FILE_NAME = "managed_download_snapshot_v1.json"
    private const val PENDING_DOWNLOAD_QUEUE_FILE_NAME = "pending_download_queue_v1.json"
    private const val CANCELLED_DOWNLOAD_KEYS_FILE_NAME = "cancelled_download_keys_v1.json"
    private const val PENDING_DOWNLOAD_QUEUE_VERSION = 1
    private const val CANCELLED_DOWNLOAD_KEYS_VERSION = 1
    private const val SNAPSHOT_CACHE_PERSIST_DEBOUNCE_MS = 1_200L
    private const val TREE_ROOT_CACHE_VALIDATE_INTERVAL_MS = 1_500L
    private const val TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS = 2_000L
    private const val TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS = 60_000L
    private const val FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS = 60_000L
    private const val MIGRATION_PROGRESS_EMIT_INTERVAL_MS = 80L
    @Suppress("SpellCheckingInspection")
    private const val METADATA_SUFFIX = ".npmeta.json"
    private const val MIGRATION_COPY_PARALLELISM = 8
    private const val MIGRATION_TREE_COPY_PARALLELISM = 2
    private const val MIGRATION_REWRITE_PARALLELISM = 4
    private const val MIGRATION_TREE_REWRITE_PARALLELISM = 2
    private const val MIGRATION_DELETE_PARALLELISM = 8
    private const val MIGRATION_TREE_DELETE_PARALLELISM = 2
    private const val MIGRATION_IO_MAX_ATTEMPTS = 3
    private const val MIGRATION_IO_RETRY_DELAY_MS = 150L
    private const val SAF_DELETE_MAX_ATTEMPTS = 3
    private const val SAF_DELETE_RETRY_DELAY_MS = 80L
    private const val SAF_REFERENCE_DELETE_PARALLELISM = 6
    private const val STREAM_COPY_BUFFER_SIZE_BYTES = 1 * 1024 * 1024
    private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "webm", "eac3")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    @Volatile
    private var customDirectoryUri: String? = null

    @Volatile
    private var customDirectoryLabel: String? = null

    @Volatile
    private var downloadFileNameTemplate: String? = null

    @Volatile
    private var snapshotCache: SnapshotCache? = null

    private val snapshotBuildLock = Any()
    private val snapshotPersistenceLock = Any()
    private val pendingDownloadQueueLock = Any()
    private val cancelledDownloadKeysLock = Any()
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val treeDirectoryLocks = ConcurrentHashMap<String, Any>()
    private val treeSubdirectoryCache = ConcurrentHashMap<String, DocumentFile>()
    private val treeChildrenNameCache = ConcurrentHashMap<String, CachedChildNames>()
    private val treeChildrenEntryCache = ConcurrentHashMap<String, CachedTreeChildren>()
    private val fileChildrenNameCache = ConcurrentHashMap<String, CachedChildNames>()
    private val childNameReservationLocks = ConcurrentHashMap<String, Any>()
    private val ensuredNoMediaMarkers = ConcurrentHashMap<String, Boolean>()
    private val pendingAudioWriteIdGenerator = AtomicLong(0L)
    private val atomicFileWriteIdGenerator = AtomicLong(0L)

    @Volatile
    private var snapshotPersistJob: Job? = null

    @Volatile
    private var startupRecoveryResult = StartupRecoveryResult()

    private val _startupRecoveryResults = MutableSharedFlow<StartupRecoveryResult>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    internal val startupRecoveryResults: SharedFlow<StartupRecoveryResult> = _startupRecoveryResults

    @Volatile
    private var cachedTreeRoot: CachedTreeRoot? = null

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
        val configuredUri = normalizeDirectoryUri(customDirectoryUri)
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
        val configuredUri = normalizeDirectoryUri(customDirectoryUri)
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

    private data class ManagedMigrationEntry(
        val subdirectory: String?,
        val entry: StoredEntry
    )

    private data class CachedTreeRoot(
        val identity: String,
        val normalizedUri: String,
        val root: RootHandle.TreeRoot,
        val validatedAtMs: Long
    )

    private class CachedChildNames(
        initialNames: Collection<String>,
        initialRefreshedAtMs: Long,
        initialComplete: Boolean
    ) {
        val names: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
            addAll(initialNames)
        }

        @Volatile
        var refreshedAtMs: Long = initialRefreshedAtMs

        @Volatile
        var isComplete: Boolean = initialComplete
    }

    private class CachedTreeChildren(
        initialChildren: Collection<QueriedTreeChild>,
        initialRefreshedAtMs: Long,
        initialComplete: Boolean
    ) {
        val childrenByName: MutableMap<String, QueriedTreeChild> = ConcurrentHashMap()

        @Volatile
        var refreshedAtMs: Long = initialRefreshedAtMs

        @Volatile
        var isComplete: Boolean = initialComplete

        init {
            initialChildren.forEach { child ->
                childrenByName[child.name] = child
            }
        }
    }

    private data class QueriedTreeChild(
        val name: String,
        val documentUri: Uri,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val isDirectory: Boolean
    )

    internal data class TreeChildNameRefresh(
        val names: Set<String>,
        val isComplete: Boolean
    )

    private data class CopiedMigrationEntry(
        val original: ManagedMigrationEntry,
        val copiedEntry: StoredEntry,
        val createdNew: Boolean
    )

    private data class StoredWriteResult(
        val entry: StoredEntry,
        val createdNew: Boolean
    )

    private data class ManagedDeletePolicy(
        val managedFileRoots: List<String>,
        val managedTreeRoots: List<String>,
        val trustedReferences: Set<String>
    )

    private data class MigrationTargetIndex(
        val rootEntriesByName: Map<String, StoredEntry>,
        val coverEntriesByName: Map<String, StoredEntry>,
        val lyricEntriesByName: Map<String, StoredEntry>
    ) {
        fun namesFor(subdirectory: String?): Set<String> {
            return when (subdirectory) {
                null -> rootEntriesByName.keys
                COVER_SUBDIRECTORY -> coverEntriesByName.keys
                LYRIC_SUBDIRECTORY -> lyricEntriesByName.keys
                else -> emptySet()
            }
        }

        fun entryFor(subdirectory: String?, name: String): StoredEntry? {
            return when (subdirectory) {
                null -> rootEntriesByName[name]
                COVER_SUBDIRECTORY -> coverEntriesByName[name]
                LYRIC_SUBDIRECTORY -> lyricEntriesByName[name]
                else -> null
            }
        }
    }

    private data class MigrationNamePlan(
        val targetNamesByReference: Map<String, String>
    ) {
        fun targetNameFor(entry: ManagedMigrationEntry): String {
            return targetNamesByReference[entry.entry.reference] ?: entry.entry.name
        }
    }

    private class MigrationProgressTracker(
        private val totalFiles: Int,
        private val totalBytes: Long,
        private val metadataFilesTotal: Int
    ) {
        private val lock = Any()
        private val activeCopyBytes = mutableMapOf<String, Long>()
        private var stage: MigrationStage = MigrationStage.PREPARING
        private var currentFileName: String? = null
        private var completedCopyBytes = 0L
        private var copiedFiles = 0
        private var metadataFilesProcessed = 0
        private var cleanupFilesProcessed = 0
        private var cleanupFilesTotal = 0
        private var lastEmitAtMs = 0L

        fun startPreparing(fileName: String? = null) {
            synchronized(lock) {
                stage = MigrationStage.PREPARING
                currentFileName = fileName
                emitLocked(force = true)
            }
        }

        fun startCopy(entry: ManagedMigrationEntry) {
            synchronized(lock) {
                stage = MigrationStage.COPYING
                currentFileName = entry.entry.name
                activeCopyBytes.putIfAbsent(entry.entry.reference, 0L)
                emitLocked(force = true)
            }
        }

        fun onCopyProgress(entry: ManagedMigrationEntry, copiedBytes: Long) {
            synchronized(lock) {
                stage = MigrationStage.COPYING
                currentFileName = entry.entry.name
                activeCopyBytes[entry.entry.reference] = copiedBytes.coerceAtLeast(0L)
                emitLocked(force = false)
            }
        }

        fun completeCopy(entry: ManagedMigrationEntry) {
            synchronized(lock) {
                stage = MigrationStage.COPYING
                currentFileName = entry.entry.name
                val finishedBytes = activeCopyBytes.remove(entry.entry.reference)
                    ?: entry.entry.sizeBytes.coerceAtLeast(0L)
                completedCopyBytes += finishedBytes.coerceAtLeast(0L)
                copiedFiles++
                emitLocked(force = true)
            }
        }

        fun failCopy(entry: ManagedMigrationEntry) {
            synchronized(lock) {
                activeCopyBytes.remove(entry.entry.reference)
                currentFileName = entry.entry.name
                emitLocked(force = true)
            }
        }

        fun startRewrite(fileName: String?) {
            synchronized(lock) {
                stage = MigrationStage.REWRITING_METADATA
                currentFileName = fileName
                emitLocked(force = true)
            }
        }

        fun finishRewrite(fileName: String?) {
            synchronized(lock) {
                stage = MigrationStage.REWRITING_METADATA
                currentFileName = fileName
                metadataFilesProcessed++
                emitLocked(force = true)
            }
        }

        fun startCleanup(totalEntries: Int, fileName: String?) {
            synchronized(lock) {
                stage = MigrationStage.CLEANING_UP
                cleanupFilesTotal = totalEntries
                currentFileName = fileName
                emitLocked(force = true)
            }
        }

        fun finishCleanup(fileName: String?) {
            synchronized(lock) {
                stage = MigrationStage.CLEANING_UP
                currentFileName = fileName
                cleanupFilesProcessed++
                emitLocked(force = true)
            }
        }

        fun finishAll() {
            synchronized(lock) {
                stage = MigrationStage.FINALIZING
                currentFileName = null
                emitLocked(force = true)
            }
        }

        private fun emitLocked(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmitAtMs < MIGRATION_PROGRESS_EMIT_INTERVAL_MS) {
                return
            }
            lastEmitAtMs = now
            val inFlightBytes = activeCopyBytes.values.sum()
            _migrationProgressFlow.value = MigrationProgress(
                stage = stage,
                totalFiles = totalFiles,
                processedFiles = when (stage) {
                    MigrationStage.PREPARING -> 0
                    MigrationStage.COPYING -> copiedFiles
                    MigrationStage.REWRITING_METADATA -> copiedFiles + metadataFilesProcessed
                    MigrationStage.CLEANING_UP -> copiedFiles + metadataFilesTotal + cleanupFilesProcessed
                    MigrationStage.FINALIZING -> totalFiles
                }.coerceAtMost(totalFiles),
                copiedFiles = copiedFiles,
                copiedBytes = completedCopyBytes + inFlightBytes,
                totalBytes = totalBytes,
                metadataFilesProcessed = metadataFilesProcessed,
                metadataFilesTotal = metadataFilesTotal,
                cleanupFilesProcessed = cleanupFilesProcessed,
                cleanupFilesTotal = cleanupFilesTotal,
                currentFileName = currentFileName
            )
        }
    }

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

    private data class SnapshotCache(
        val key: String,
        val snapshot: DownloadLibrarySnapshot
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

    private sealed interface RootHandle {
        data class FileRoot(val dir: File) : RootHandle
        data class TreeRoot(val tree: DocumentFile) : RootHandle
    }

    fun primeSettings(directoryUri: String?, directoryLabel: String?, fileNameTemplate: String? = null) {
        customDirectoryUri = directoryUri?.takeIf { it.isNotBlank() }
        customDirectoryLabel = directoryLabel?.takeIf { it.isNotBlank() }
        downloadFileNameTemplate = normalizeDownloadFileNameTemplate(fileNameTemplate)
        clearTreeDirectoryCache()
        invalidateSnapshotCache()
    }

    fun updateCustomDirectoryUri(uri: String?) {
        customDirectoryUri = uri?.takeIf { it.isNotBlank() }
        clearTreeDirectoryCache()
        invalidateSnapshotCache()
    }

    fun updateConfiguredTreeUri(uri: String?) {
        updateCustomDirectoryUri(uri)
    }

    fun updateCustomDirectoryLabel(label: String?) {
        customDirectoryLabel = label?.takeIf { it.isNotBlank() }
    }

    fun updateDownloadFileNameTemplate(template: String?) {
        downloadFileNameTemplate = normalizeDownloadFileNameTemplate(template)
    }

    internal fun currentDownloadFileNameTemplate(): String? = downloadFileNameTemplate

    internal fun currentSnapshotCacheKey(context: Context): String {
        return buildSnapshotCacheKey(context.applicationContext)
    }

    internal fun ensureSnapshotCacheReady(context: Context): Boolean {
        val cacheKey = buildSnapshotCacheKey(context.applicationContext)
        val currentCache = snapshotCache
        if (currentCache?.key == cacheKey) {
            return true
        }
        return restoreSnapshotCacheFromDisk(context.applicationContext, expectedKey = cacheKey) != null
    }

    internal fun cachedDownloadLibrarySnapshot(
        context: Context,
        restoreFromDisk: Boolean = true
    ): DownloadLibrarySnapshot? {
        val appContext = context.applicationContext
        val cacheKey = buildSnapshotCacheKey(appContext)
        snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?.let { return it }
        if (!restoreFromDisk) {
            return null
        }
        return restoreSnapshotCacheFromDisk(appContext, expectedKey = cacheKey)
    }

    internal fun directoryIdentity(uriString: String?): String? {
        val normalized = normalizeConfiguredDirectoryUri(uriString) ?: return null
        extractDirectoryDocumentId(normalized, "/tree/")
            ?.let { documentId ->
                return "tree:${extractDirectoryAuthority(normalized)}:$documentId"
            }
        extractDirectoryDocumentId(normalized, "/document/")
            ?.let { documentId ->
                return "document:${extractDirectoryAuthority(normalized)}:$documentId"
            }
        return normalized
    }

    internal fun areEquivalentDirectoryUris(first: String?, second: String?): Boolean {
        val firstIdentity = directoryIdentity(first)
        val secondIdentity = directoryIdentity(second)
        return when {
            firstIdentity == null && secondIdentity == null -> true
            else -> firstIdentity != null && firstIdentity == secondIdentity
        }
    }

    internal fun canonicalizeDirectoryUri(uriString: String?): String? {
        return normalizeConfiguredDirectoryUri(uriString)
    }

    fun describeConfiguredDirectory(context: Context, uriString: String? = customDirectoryUri): String {
        val resolvedUri = uriString?.takeIf { it.isNotBlank() }
        if (resolvedUri.isNullOrBlank()) {
            return context.getString(R.string.settings_download_directory_default_label)
        }
        if (resolvedUri == customDirectoryUri && !customDirectoryLabel.isNullOrBlank()) {
            return customDirectoryLabel.orEmpty()
        }
        val treeUri = runCatching { resolvedUri.toUri() }.getOrNull()
        val tree = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        return tree?.name?.takeIf { it.isNotBlank() }
            ?: resolvedUri
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
            val progressTracker = MigrationProgressTracker(
                totalFiles = entries.size,
                totalBytes = entries.sumOf { it.entry.sizeBytes.coerceAtLeast(0L) },
                metadataFilesTotal = metadataEntriesTotal
            )
            progressTracker.startPreparing(entries.firstOrNull()?.entry?.name)
            val targetIndex = buildMigrationTargetIndex(context, targetRoot)
            val namePlan = buildMigrationNamePlan(entries, targetIndex)

            val copyLimiter = Semaphore(migrationCopyParallelism(sourceRoot, targetRoot))
            val copyResults = coroutineScope {
                entries.map { migrationEntry ->
                    async(Dispatchers.IO) {
                        copyLimiter.withPermit {
                            copyManagedMigrationEntry(
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
                parallelism = migrationRewriteParallelism(targetRoot),
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

    private data class ManagedMigrationCopyResult(
        val copiedEntry: CopiedMigrationEntry?
    )

    private suspend fun copyManagedMigrationEntry(
        context: Context,
        targetRoot: RootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: MigrationTargetIndex,
        namePlan: MigrationNamePlan,
        progressTracker: MigrationProgressTracker? = null
    ): ManagedMigrationCopyResult {
        val copiedEntry = retryManagedMigrationWrite(
            reference = migrationEntry.entry.reference
        ) {
            progressTracker?.startCopy(migrationEntry)
            try {
                openStoredEntryInputStream(context, migrationEntry.entry)?.use { input ->
                    if (migrationEntry.subdirectory == null) {
                        writeMigrationRootStream(
                            context = context,
                            root = targetRoot,
                            displayName = namePlan.targetNameFor(migrationEntry),
                            mimeType = migrationMimeTypeFor(migrationEntry),
                            input = input,
                            sourceEntry = migrationEntry.entry,
                            targetNames = targetIndex.namesFor(migrationEntry.subdirectory),
                            targetEntry = targetIndex.entryFor(
                                migrationEntry.subdirectory,
                                namePlan.targetNameFor(migrationEntry)
                            ),
                            onProgress = { copiedBytes ->
                                progressTracker?.onCopyProgress(migrationEntry, copiedBytes)
                            }
                        )
                    } else {
                        writeMigrationSubdirectoryStream(
                            context = context,
                            root = targetRoot,
                            subdirectory = migrationEntry.subdirectory,
                            displayName = namePlan.targetNameFor(migrationEntry),
                            mimeType = migrationMimeTypeFor(migrationEntry),
                            input = input,
                            sourceEntry = migrationEntry.entry,
                            targetNames = targetIndex.namesFor(migrationEntry.subdirectory),
                            targetEntry = targetIndex.entryFor(
                                migrationEntry.subdirectory,
                                namePlan.targetNameFor(migrationEntry)
                            ),
                            onProgress = { copiedBytes ->
                                progressTracker?.onCopyProgress(migrationEntry, copiedBytes)
                            }
                        )
                    }
                } ?: throw IOException("无法读取源下载文件: ${migrationEntry.entry.name}")
            } catch (error: Throwable) {
                progressTracker?.failCopy(migrationEntry)
                throw error
            }.also {
                progressTracker?.completeCopy(migrationEntry)
            }
        }
            ?: return ManagedMigrationCopyResult(copiedEntry = null)

        return ManagedMigrationCopyResult(
            copiedEntry = CopiedMigrationEntry(
                original = migrationEntry,
                copiedEntry = copiedEntry.entry,
                createdNew = copiedEntry.createdNew
            )
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

    fun hasDownloadedAudio(
        context: Context,
        song: SongItem,
        forceRefresh: Boolean = false
    ): Boolean {
        return findDownloadedAudioBlocking(context, song, forceRefresh) != null
    }

    fun buildDisplayBaseName(song: SongItem): String {
        return renderManagedDownloadBaseName(song, downloadFileNameTemplate)
    }

    internal fun buildWorkingFileName(songKey: String, fileName: String): String {
        val normalizedKey = buildWorkingSongKeyHash(songKey)
        val normalizedPrefix = fileName.substringBeforeLast('.', fileName)
            .ifBlank { "download" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(48)
            .ifBlank { "download" }
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9]"), "")
            ?.take(8)
            ?.lowercase()
        val fileBody = "${DOWNLOAD_STAGING_FILE_PREFIX}${normalizedKey}_$normalizedPrefix"
        return extension?.let { "$fileBody.$it$DOWNLOAD_STAGING_FILE_SUFFIX" }
            ?: "$fileBody$DOWNLOAD_STAGING_FILE_SUFFIX"
    }

    internal fun buildWorkingSongKeyHash(songKey: String): String {
        return java.lang.Long.toHexString(songKey.hashCode().toLong() and 0xffffffffL)
    }

    fun createWorkingFile(context: Context, songKey: String, fileName: String): File {
        val stagingDir = File(context.cacheDir, DOWNLOAD_STAGING_DIR_NAME).apply { mkdirs() }
        return File(stagingDir, buildWorkingFileName(songKey, fileName))
    }

    internal fun buildWorkingHlsCheckpointFile(workingFile: File): File {
        return File(workingFile.parentFile, workingFile.name + DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX)
    }

    internal fun buildWorkingResumeMetadataFile(workingFile: File): File {
        return File(workingFile.parentFile, workingFile.name + DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
    }

    internal fun shouldPreserveWorkingFileForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isFreshNamedNonEmptyWorkingFile(entry, nowMs)) {
            return false
        }
        return hasFreshValidWorkingResumeMetadata(entry, nowMs)
    }

    private fun isFreshNamedNonEmptyWorkingFile(
        entry: File,
        nowMs: Long
    ): Boolean {
        if (!entry.isFile) {
            return false
        }
        if (!entry.name.startsWith(DOWNLOAD_STAGING_FILE_PREFIX)) {
            return false
        }
        if (!entry.name.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX)) {
            return false
        }
        if (entry.length() <= 0L) {
            return false
        }
        val ageMs = (nowMs - entry.lastModified().coerceAtLeast(0L)).coerceAtLeast(0L)
        return ageMs <= DOWNLOAD_STAGING_MAX_AGE_MS
    }

    internal fun shouldPreserveWorkingCheckpointForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!entry.isFile) {
            return false
        }
        if (!entry.name.endsWith(DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX)) {
            return false
        }
        val workingFileName = entry.name.removeSuffix(DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX)
        if (workingFileName.isBlank()) {
            return false
        }
        val ageMs = (nowMs - entry.lastModified().coerceAtLeast(0L)).coerceAtLeast(0L)
        if (ageMs > DOWNLOAD_STAGING_MAX_AGE_MS) {
            return false
        }
        val pairedWorkingFile = File(entry.parentFile, workingFileName)
        return shouldPreserveWorkingFileForResume(pairedWorkingFile, nowMs)
    }

    internal fun shouldPreserveWorkingResumeMetadataForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!entry.isFile) {
            return false
        }
        if (!entry.name.endsWith(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)) {
            return false
        }
        val workingFileName = entry.name.removeSuffix(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
        if (workingFileName.isBlank()) {
            return false
        }
        val ageMs = (nowMs - entry.lastModified().coerceAtLeast(0L)).coerceAtLeast(0L)
        if (ageMs > DOWNLOAD_STAGING_MAX_AGE_MS) {
            return false
        }
        val pairedWorkingFile = File(entry.parentFile, workingFileName)
        if (!isFreshNamedNonEmptyWorkingFile(pairedWorkingFile, nowMs)) {
            return false
        }
        return hasValidWorkingResumeMetadataFile(entry)
    }

    private fun hasFreshValidWorkingResumeMetadata(
        workingFile: File,
        nowMs: Long
    ): Boolean {
        val metadataFile = buildWorkingResumeMetadataFile(workingFile)
        if (!metadataFile.isFile) {
            return false
        }
        val ageMs = (nowMs - metadataFile.lastModified().coerceAtLeast(0L)).coerceAtLeast(0L)
        if (ageMs > DOWNLOAD_STAGING_MAX_AGE_MS) {
            return false
        }
        return hasValidWorkingResumeMetadataFile(metadataFile)
    }

    private fun hasValidWorkingResumeMetadataFile(metadataFile: File): Boolean {
        if (!metadataFile.isFile) {
            return false
        }
        return runCatching {
            parseWorkingResumeMetadataSong(metadataFile.readText(Charsets.UTF_8)) != null
        }.getOrDefault(false)
    }

    internal fun saveWorkingResumeMetadata(
        workingFile: File,
        song: SongItem
    ) {
        val metadataFile = buildWorkingResumeMetadataFile(workingFile)
        runCatching {
            metadataFile.parentFile?.mkdirs()
            metadataFile.writeText(song.toWorkingResumeMetadataJson().toString(), Charsets.UTF_8)
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载恢复元数据失败: ${metadataFile.name}, ${error.message}")
        }
    }

    internal fun deleteWorkingResumeMetadata(workingFile: File?) {
        workingFile ?: return
        val metadataFile = buildWorkingResumeMetadataFile(workingFile)
        if (metadataFile.exists()) {
            runCatching {
                metadataFile.delete()
            }
        }
    }

    internal fun deleteWorkingDownloadArtifacts(workingFile: File?) {
        workingFile ?: return
        deleteWorkingResumeMetadata(workingFile)
        val checkpointFile = buildWorkingHlsCheckpointFile(workingFile)
        if (checkpointFile.exists()) {
            runCatching {
                checkpointFile.delete()
            }
        }
        if (workingFile.exists()) {
            runCatching {
                workingFile.delete()
            }
        }
    }

    internal fun deletePendingWorkingDownloadArtifacts(
        context: Context,
        songKeys: Collection<String>
    ): Set<String> {
        val stagingDir = File(context.cacheDir, DOWNLOAD_STAGING_DIR_NAME)
        return deletePendingWorkingDownloadArtifactsInDirectory(stagingDir, songKeys)
    }

    internal fun deletePendingWorkingDownloadArtifactsInDirectory(
        stagingDir: File,
        songKeys: Collection<String>
    ): Set<String> {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return emptySet()
        }
        val deletedKeys = listPendingResumableDownloadsInDirectory(stagingDir)
            .filter { pendingDownload -> pendingDownload.song.stableKey() in keys }
            .mapNotNullTo(linkedSetOf()) { pendingDownload ->
                val songKey = pendingDownload.song.stableKey()
                deleteWorkingDownloadArtifacts(pendingDownload.workingFile)
                songKey
            }
        val keyHashes = keys.associateBy(::buildWorkingSongKeyHash)
        stagingDir.listFiles()
            .orEmpty()
            .filter { entry -> matchingWorkingArtifactSongKey(entry.name, keyHashes) != null }
            .forEach { entry ->
                val songKey = matchingWorkingArtifactSongKey(entry.name, keyHashes) ?: return@forEach
                if (deleteWorkingArtifactEntry(entry)) {
                    deletedKeys += songKey
                }
            }
        return deletedKeys
    }

    internal fun listPendingResumableDownloads(context: Context): List<PendingResumableDownload> {
        val stagingDir = File(context.cacheDir, DOWNLOAD_STAGING_DIR_NAME)
        return listPendingResumableDownloadsInDirectory(stagingDir)
    }

    internal fun upsertPendingDownloadQueue(
        context: Context,
        songs: List<SongItem>
    ) {
        upsertPendingDownloadQueueInFile(
            queueFile = pendingDownloadQueueFile(context),
            songs = songs
        )
    }

    internal fun listPendingQueuedDownloads(context: Context): List<PendingDownloadQueueEntry> {
        return listPendingQueuedDownloadsFromFile(pendingDownloadQueueFile(context))
    }

    internal fun removePendingDownloadQueueEntries(
        context: Context,
        songKeys: Collection<String>
    ) {
        removePendingDownloadQueueEntriesFromFile(
            queueFile = pendingDownloadQueueFile(context),
            songKeys = songKeys
        )
    }

    internal fun clearPendingDownloadQueue(context: Context) {
        clearPendingDownloadQueueFile(pendingDownloadQueueFile(context))
    }

    internal fun markCancelledDownloadKeys(
        context: Context,
        songKeys: Collection<String>
    ) {
        markCancelledDownloadKeysInFile(
            keysFile = cancelledDownloadKeysFile(context),
            songKeys = songKeys
        )
    }

    internal fun listCancelledDownloadKeys(context: Context): Set<String> {
        return listCancelledDownloadKeysFromFile(cancelledDownloadKeysFile(context))
    }

    internal fun removeCancelledDownloadKeys(
        context: Context,
        songKeys: Collection<String>
    ) {
        removeCancelledDownloadKeysFromFile(
            keysFile = cancelledDownloadKeysFile(context),
            songKeys = songKeys
        )
    }

    internal fun clearCancelledDownloadKeys(context: Context) {
        clearCancelledDownloadKeysFile(cancelledDownloadKeysFile(context))
    }

    internal fun upsertPendingDownloadQueueInFile(
        queueFile: File,
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val distinctSongs = songs.distinctBy { it.stableKey() }
        if (distinctSongs.isEmpty()) {
            return
        }
        synchronized(pendingDownloadQueueLock) {
            val existingEntries = readPendingDownloadQueueFile(queueFile)
            val mergedEntries = mergePendingDownloadQueueEntries(
                existingEntries = existingEntries,
                songs = distinctSongs,
                nowMs = nowMs
            )
            writePendingDownloadQueueFile(queueFile, mergedEntries, nowMs)
        }
    }

    internal fun listPendingQueuedDownloadsFromFile(queueFile: File): List<PendingDownloadQueueEntry> {
        return synchronized(pendingDownloadQueueLock) {
            readPendingDownloadQueueFile(queueFile)
        }
    }

    internal fun removePendingDownloadQueueEntriesFromFile(
        queueFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        synchronized(pendingDownloadQueueLock) {
            val existingEntries = readPendingDownloadQueueFile(queueFile)
            if (existingEntries.isEmpty()) {
                return
            }
            val retainedEntries = existingEntries.filterNot { it.stableKey in keys }
            if (retainedEntries.size == existingEntries.size) {
                return
            }
            writePendingDownloadQueueFile(queueFile, retainedEntries, nowMs)
        }
    }

    internal fun clearPendingDownloadQueueFile(
        queueFile: File,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(pendingDownloadQueueLock) {
            writePendingDownloadQueueFile(queueFile, emptyList(), nowMs)
        }
    }

    internal fun markCancelledDownloadKeysInFile(
        keysFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        synchronized(cancelledDownloadKeysLock) {
            val mergedKeys = listCancelledDownloadKeysFromFileLocked(keysFile) + keys
            writeCancelledDownloadKeysFile(keysFile, mergedKeys, nowMs)
        }
    }

    internal fun listCancelledDownloadKeysFromFile(keysFile: File): Set<String> {
        return synchronized(cancelledDownloadKeysLock) {
            listCancelledDownloadKeysFromFileLocked(keysFile)
        }
    }

    internal fun removeCancelledDownloadKeysFromFile(
        keysFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        synchronized(cancelledDownloadKeysLock) {
            val retainedKeys = listCancelledDownloadKeysFromFileLocked(keysFile) - keys
            writeCancelledDownloadKeysFile(keysFile, retainedKeys, nowMs)
        }
    }

    internal fun clearCancelledDownloadKeysFile(
        keysFile: File,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(cancelledDownloadKeysLock) {
            writeCancelledDownloadKeysFile(keysFile, emptySet(), nowMs)
        }
    }

    internal fun listPendingResumableDownloadsInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): List<PendingResumableDownload> {
        val metadataEntries = stagingDir.listFiles { _, name ->
            name.endsWith(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
        }.orEmpty()
        if (metadataEntries.isEmpty()) {
            return emptyList()
        }
        return metadataEntries
            .asSequence()
            .filter { metadataFile ->
                shouldPreserveWorkingResumeMetadataForResume(metadataFile, nowMs)
            }
            .mapNotNull { metadataFile ->
                val workingFileName = metadataFile.name.removeSuffix(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
                val workingFile = File(stagingDir, workingFileName)
                val song = runCatching {
                    metadataFile.readText(Charsets.UTF_8)
                }.mapCatching(::parseWorkingResumeMetadataSong)
                    .getOrNull()
                    ?: return@mapNotNull null
                PendingResumableDownload(
                    song = song,
                    workingFile = workingFile
                )
            }
            .sortedBy { it.workingFile.lastModified() }
            .distinctBy { it.song.stableKey() }
            .toList()
    }

    internal fun consumeStartupRecoveryResult(): StartupRecoveryResult {
        val result = startupRecoveryResult
        startupRecoveryResult = StartupRecoveryResult()
        return result
    }

    fun cleanupStagingFiles(context: Context): StartupRecoveryResult {
        val stagingDir = File(context.cacheDir, DOWNLOAD_STAGING_DIR_NAME)
        return cleanupStagingFilesInDirectory(stagingDir)
    }

    internal fun cleanupStagingFilesInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): StartupRecoveryResult {
        val stagingEntries = stagingDir.listFiles().orEmpty()
        if (stagingEntries.isEmpty()) {
            return StartupRecoveryResult()
        }

        var cleanedCount = 0
        var failedCount = 0
        var preservedCount = 0
        stagingEntries.forEach { entry ->
            if (
                shouldPreserveWorkingFileForResume(entry, nowMs) ||
                shouldPreserveWorkingCheckpointForResume(entry, nowMs) ||
                shouldPreserveWorkingResumeMetadataForResume(entry, nowMs)
            ) {
                preservedCount++
                return@forEach
            }
            val deleted = runCatching {
                if (entry.isDirectory) {
                    entry.deleteRecursively()
                } else {
                    !entry.exists() || entry.delete()
                }
            }.getOrDefault(false)
            if (deleted) {
                cleanedCount++
            } else {
                failedCount++
            }
        }
        if (cleanedCount > 0 || failedCount > 0 || preservedCount > 0) {
            NPLogger.d(
                TAG,
                "清理下载临时区完成: cleaned=$cleanedCount, failed=$failedCount, preserved=$preservedCount"
            )
        }
        return StartupRecoveryResult(
            cleanedCount = cleanedCount,
            failedCount = failedCount
        )
    }

    private fun matchingWorkingArtifactSongKey(
        fileName: String,
        songKeyByHash: Map<String, String>
    ): String? {
        if (!fileName.startsWith(DOWNLOAD_STAGING_FILE_PREFIX)) {
            return null
        }
        val keyHash = fileName
            .removePrefix(DOWNLOAD_STAGING_FILE_PREFIX)
            .substringBefore('_', missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
            ?: return null
        return songKeyByHash[keyHash]
    }

    private fun deleteWorkingArtifactEntry(entry: File): Boolean {
        return runCatching {
            if (entry.isDirectory) {
                entry.deleteRecursively()
            } else {
                !entry.exists() || entry.delete()
            }
        }.getOrDefault(false)
    }

    fun findAudio(
        context: Context,
        song: SongItem,
        forceRefresh: Boolean = false
    ): StoredEntry? {
        return findDownloadedAudioBlocking(context, song, forceRefresh)
    }

    fun peekDownloadedAudio(song: SongItem): StoredEntry? {
        return snapshotCache?.snapshot?.let { snapshot ->
            findAudioEntry(snapshot, song)
        }
    }

    fun peekCoverReference(audio: StoredEntry): String? {
        val snapshot = snapshotCache?.snapshot ?: return null
        snapshot.metadataByAudioName[audio.name]?.let { metadata ->
            resolveMetadataCoverReference(
                snapshot = snapshot,
                audioName = audio.name,
                metadata = metadata
            )?.let { return it }
        }
        return findIndexedEntryByNames(
            names = buildSidecarCandidateNames(candidateManagedDownloadBaseNames(audio.nameWithoutExtension)),
            entriesByName = snapshot.coverEntriesByName
        )?.reference
    }

    fun buildCandidateBaseNames(song: SongItem): List<String> {
        return candidateManagedDownloadBaseNames(song, downloadFileNameTemplate)
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
        val cacheKey = buildSnapshotCacheKey(context)
        if (!forceRefresh) {
            snapshotCache
                ?.takeIf { it.key == cacheKey }
                ?.let { return@synchronized it.snapshot }
            restoreSnapshotCacheFromDisk(context, expectedKey = cacheKey)
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
            snapshotCache = SnapshotCache(key = cacheKey, snapshot = snapshot)
            scheduleSnapshotCachePersist(context, cacheKey)
        }
    }

    private fun composeSnapshot(
        audioEntries: List<StoredEntry>,
        metadataEntries: List<StoredEntry>,
        metadataByAudioName: Map<String, DownloadedAudioMetadata>,
        coverEntries: List<StoredEntry>,
        lyricEntries: List<StoredEntry>
    ): DownloadLibrarySnapshot {
        val metadataEntriesByAudioName = metadataEntries.associateBy { entry ->
            entry.name.removeSuffix(METADATA_SUFFIX)
        }
        val coverEntriesByName = coverEntries.associateBy(StoredEntry::name)
        val lyricEntriesByName = lyricEntries.associateBy(StoredEntry::name)
        val audioEntriesByStableKey = mutableMapOf<String, MutableList<StoredEntry>>()
        val audioEntriesBySongId = mutableMapOf<Long, MutableList<StoredEntry>>()
        val audioEntriesByMediaUri = mutableMapOf<String, MutableList<StoredEntry>>()
        val audioEntriesByRemoteTrackKey = mutableMapOf<String, MutableList<StoredEntry>>()
        val audioEntriesWithoutMetadata = mutableListOf<StoredEntry>()

        audioEntries.forEach { entry ->
            val metadata = metadataByAudioName[entry.name]
            if (metadata == null) {
                audioEntriesWithoutMetadata += entry
                return@forEach
            }

            metadata.stableKey?.let { key ->
                audioEntriesByStableKey.getOrPut(key) { mutableListOf() } += entry
            }
            metadata.songId?.takeIf { it > 0L }?.let { songId ->
                audioEntriesBySongId.getOrPut(songId) { mutableListOf() } += entry
            }
            metadata.mediaUri?.let { mediaUri ->
                audioEntriesByMediaUri.getOrPut(mediaUri) { mutableListOf() } += entry
            }
            buildRemoteTrackKey(
                channelId = metadata.channelId,
                audioId = metadata.audioId,
                subAudioId = metadata.subAudioId
            )?.let { remoteTrackKey ->
                audioEntriesByRemoteTrackKey.getOrPut(remoteTrackKey) { mutableListOf() } += entry
            }
        }

        return DownloadLibrarySnapshot(
            audioEntries = audioEntries,
            audioEntriesByLookupKey = buildMap {
                audioEntries.forEach { entry ->
                    put(entry.reference, entry)
                    put(entry.mediaUri, entry)
                    entry.localFilePath?.let { put(it, entry) }
                }
            },
            metadataEntriesByAudioName = metadataEntriesByAudioName,
            metadataByAudioName = metadataByAudioName,
            audioEntriesWithoutMetadata = audioEntriesWithoutMetadata,
            audioEntriesByStableKey = audioEntriesByStableKey,
            audioEntriesBySongId = audioEntriesBySongId,
            audioEntriesByMediaUri = audioEntriesByMediaUri,
            audioEntriesByRemoteTrackKey = audioEntriesByRemoteTrackKey,
            coverEntriesByName = coverEntriesByName,
            lyricEntriesByName = lyricEntriesByName,
            knownReferences = buildSet {
                audioEntries.forEach { add(it.reference) }
                metadataEntries.forEach { add(it.reference) }
                coverEntries.forEach { add(it.reference) }
                lyricEntries.forEach { add(it.reference) }
            }
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
        parallelism: Int,
        progressTracker: MigrationProgressTracker? = null
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val referenceMap = copiedEntries.associate { copied ->
            copied.original.entry.reference to copied.copiedEntry.reference
        }
        val rewriteLimiter = Semaphore(parallelism)
        copiedEntries
            .filter { it.original.entry.name.endsWith(METADATA_SUFFIX) }
            .map { copied ->
                async(Dispatchers.IO) {
                    rewriteLimiter.withPermit {
                        progressTracker?.startRewrite(copied.copiedEntry.name)
                        val raw = readTextInternal(context, copied.copiedEntry.reference)
                        val rewritten = runCatching {
                            val metadataText = raw
                                ?: throw IOException("无法读取已迁移 metadata: ${copied.copiedEntry.name}")
                            rewriteManagedMetadataReferences(metadataText, referenceMap)
                        }.onFailure {
                            NPLogger.w(TAG, "迁移后重写 metadata 引用失败: ${copied.copiedEntry.reference}, ${it.message}")
                        }.getOrNull()
                        if (rewritten == null) {
                            progressTracker?.finishRewrite(copied.copiedEntry.name)
                            return@withPermit 1
                        }
                        if (rewritten == raw) {
                            progressTracker?.finishRewrite(copied.copiedEntry.name)
                            return@withPermit 0
                        }
                        runCatching {
                            writeRootText(
                                context = context,
                                root = targetRoot,
                                displayName = copied.copiedEntry.name,
                                content = rewritten,
                                invalidateSnapshot = false
                            )
                        }.onFailure {
                            NPLogger.w(TAG, "回写迁移后的 metadata 失败: ${copied.copiedEntry.reference}, ${it.message}")
                        }.getOrElse {
                            progressTracker?.finishRewrite(copied.copiedEntry.name)
                            return@withPermit 1
                        }
                        progressTracker?.finishRewrite(copied.copiedEntry.name)
                        0
                    }
                }
            }
            .awaitAll()
            .sum()
    }

    private suspend fun cleanupMigratedEntries(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        sourceRoot: RootHandle,
        progressTracker: MigrationProgressTracker? = null
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val cleanupLimiter = Semaphore(migrationDeleteParallelism(sourceRoot))
        copiedEntries.map { migrationEntry ->
            async(Dispatchers.IO) {
                cleanupLimiter.withPermit {
                    progressTracker?.startCleanup(copiedEntries.size, migrationEntry.original.entry.name)
                    val deleted = runCatching {
                        deleteInternal(
                            context = context,
                            reference = migrationEntry.original.entry.reference,
                            allowedRoot = sourceRoot,
                            invalidateSnapshot = false
                        )
                    }.onFailure {
                        NPLogger.w(TAG, "迁移后删除旧下载文件失败: ${migrationEntry.original.entry.reference}, ${it.message}")
                    }.getOrDefault(false)
                    progressTracker?.finishCleanup(migrationEntry.original.entry.name)
                    if (deleted) 0 else 1
                }
            }
        }.awaitAll().sum()
    }

    private suspend fun rollbackMigratedEntries(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        targetRoot: RootHandle
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val cleanupLimiter = Semaphore(migrationDeleteParallelism(targetRoot))
        copiedEntries.map { migrationEntry ->
            async(Dispatchers.IO) {
                cleanupLimiter.withPermit {
                    if (!migrationEntry.createdNew) {
                        return@withPermit 0
                    }
                    val deleted = runCatching {
                        deleteInternal(
                            context = context,
                            reference = migrationEntry.copiedEntry.reference,
                            allowedRoot = targetRoot,
                            invalidateSnapshot = false
                        )
                    }.onFailure {
                        NPLogger.w(TAG, "回滚迁移目标文件失败: ${migrationEntry.copiedEntry.reference}, ${it.message}")
                    }.getOrDefault(false)
                    if (deleted) 0 else 1
                }
            }
        }.awaitAll().sum()
    }

    internal fun rewriteManagedMetadataReferences(
        rawJson: String,
        referenceMap: Map<String, String>
    ): String {
        if (referenceMap.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        rewriteMetadataReferenceField(root, "coverPath", referenceMap)
        rewriteMetadataReferenceField(root, "lyricPath", referenceMap)
        rewriteMetadataReferenceField(root, "translatedLyricPath", referenceMap)
        rewriteMetadataReferenceField(root, "coverUrl", referenceMap)
        rewriteMetadataReferenceField(root, "originalCoverUrl", referenceMap)
        rewriteMetadataReferenceField(root, "mediaUri", referenceMap)
        rewriteMetadataEmbeddedReferenceField(root, "stableKey", referenceMap)
        return root.toString()
    }

    internal fun shouldTreatAudioAsManaged(
        audioName: String,
        metadataAudioNames: Set<String>,
        coverEntryNames: Set<String>,
        lyricEntryNames: Set<String>,
        allowMetadataLessAudio: Boolean
    ): Boolean {
        if (audioName in metadataAudioNames) {
            return true
        }
        if (allowMetadataLessAudio) {
            return true
        }
        val candidateBaseNames = candidateManagedDownloadBaseNames(
            audioName.substringBeforeLast('.', audioName)
        )
        val hasManagedCover = buildSidecarCandidateNames(candidateBaseNames)
            .any(coverEntryNames::contains)
        if (hasManagedCover) {
            return true
        }
        return buildLyricCandidateNames(
            songId = null,
            candidateBaseNames = candidateBaseNames,
            translated = false
        ).any(lyricEntryNames::contains) ||
            buildLyricCandidateNames(
                songId = null,
                candidateBaseNames = candidateBaseNames,
                translated = true
            ).any(lyricEntryNames::contains)
    }

    private fun shouldIndexMetadataLessAudio(): Boolean {
        return shouldIndexMetadataLessAudio(customDirectoryUri)
    }

    private fun rewriteMetadataReferenceField(
        root: JSONObject,
        fieldName: String,
        referenceMap: Map<String, String>
    ) {
        val current = root.optString(fieldName).takeIf(String::isNotBlank) ?: return
        val updated = referenceMap[current] ?: return
        root.put(fieldName, updated)
    }

    private fun rewriteMetadataEmbeddedReferenceField(
        root: JSONObject,
        fieldName: String,
        referenceMap: Map<String, String>
    ) {
        val current = root.optString(fieldName).takeIf(String::isNotBlank) ?: return
        val updated = referenceMap.entries.fold(current) { value, (from, to) ->
            if (value.contains(from)) {
                value.replace(from, to)
            } else {
                value
            }
        }
        if (updated != current) {
            root.put(fieldName, updated)
        }
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
                cachedTreeChild(context, root.tree, metadataName)
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
                val finalName = reserveUniqueFileChildName(root.dir, fileName)
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
                    forgetFileChildName(root.dir, finalName)
                    throw error
                }
            }

            is RootHandle.TreeRoot -> {
                val finalName = reserveUniqueTreeChildName(context, root.tree, fileName)
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
                        forgetTreeChildName(root.tree, createdPendingName)
                        if (entry.name != finalName) {
                            forgetTreeChildName(root.tree, finalName)
                        }
                        rememberTreeChild(root.tree, entry)
                        entry
                    } else {
                        commitTreeAudioAfterRenameFailure(
                            context = context,
                            parent = root.tree,
                            pendingTarget = pendingTarget,
                            pendingName = createdPendingName,
                            finalName = finalName,
                            mimeType = mimeType,
                            tempFile = tempFile,
                            actualSizeBytes = actualSizeBytes,
                            committedAtMs = committedAtMs
                        )
                    }
                } catch (error: Throwable) {
                    pendingTarget?.let { target ->
                        deleteContentReference(context, target.uri.toString(), target.uri)
                    }
                    pendingName?.let { forgetTreeChildName(root.tree, it) }
                    deleteSeedMetadataAfterAudioCommitFailure(context, root, seedMetadataEntry)
                    forgetTreeChildName(root.tree, finalName)
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
        snapshotCache?.snapshot?.let { return it }
        if (ensureSnapshotCacheReady(context)) {
            snapshotCache?.snapshot?.let { return it }
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
        return findIndexedEntryByNames(
            names = buildLyricCandidateNames(
                songId = songId.takeIf { it > 0L },
                candidateBaseNames = candidateBaseNames,
                translated = translated
            ),
            entriesByName = snapshot.lyricEntriesByName
        )?.reference
    }

    fun writeLyrics(
        context: Context,
        songId: Long,
        baseName: String,
        content: String,
        translated: Boolean
    ): String? {
        val fileNameByName = if (translated) "${baseName}_trans.lrc" else "$baseName.lrc"
        NPLogger.d(TAG, "写入歌词文件: fileName=$fileNameByName, translated=$translated, songId=$songId")
        return overwriteLyric(context, fileNameByName, content)
    }

    fun readLyrics(context: Context, song: SongItem, translated: Boolean): String? {
        val snapshot = buildDownloadLibrarySnapshotBlocking(context)
        val resolvedAudio = findAudioEntry(snapshot, song)
        val resolvedMetadata = resolvedAudio?.let { snapshot.metadataByAudioName[it.name] }
        val embeddedLyric = if (translated) {
            resolvedMetadata?.matchedTranslatedLyric
        } else {
            resolvedMetadata?.matchedLyric
        }
        if (embeddedLyric != null && embeddedLyric.isBlank()) {
            return ""
        }
        val reference = resolveManagedLyricReference(
            context = context,
            snapshot = snapshot,
            song = song,
            resolvedAudio = resolvedAudio,
            resolvedMetadata = resolvedMetadata,
            translated = translated
        )
        if (reference != null) {
            return readTextInternal(context, reference)
        }
        return if (translated) {
            resolvedMetadata?.matchedTranslatedLyric ?: resolvedMetadata?.originalTranslatedLyric
        } else {
            resolvedMetadata?.matchedLyric ?: resolvedMetadata?.originalLyric
        }
    }

    private fun resolveManagedLyricReference(
        context: Context,
        snapshot: DownloadLibrarySnapshot,
        song: SongItem,
        resolvedAudio: StoredEntry?,
        resolvedMetadata: DownloadedAudioMetadata?,
        translated: Boolean
    ): String? {
        val metadataReference = if (translated) {
            resolvedMetadata?.translatedLyricPath
        } else {
            resolvedMetadata?.lyricPath
        }
        if (existsInternal(context, metadataReference)) {
            return metadataReference
        }

        resolvedAudio?.let { audio ->
            findIndexedLyricReference(
                snapshot = snapshot,
                songId = resolvedMetadata?.songId ?: song.id.takeIf { it > 0L },
                candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
                translated = translated
            )?.let { return it }
        }

        return findIndexedLyricReference(
            snapshot = snapshot,
            songId = song.id.takeIf { it > 0L },
            candidateBaseNames = candidateManagedDownloadBaseNames(song, downloadFileNameTemplate),
            translated = translated
        )
    }

    private fun findIndexedLyricReference(
        snapshot: DownloadLibrarySnapshot,
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): String? {
        return findIndexedEntryByNames(
            names = buildLyricCandidateNames(
                songId = songId,
                candidateBaseNames = candidateBaseNames,
                translated = translated
            ),
            entriesByName = snapshot.lyricEntriesByName
        )?.reference
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
        snapshot.metadataByAudioName[audio.name]?.let { metadata ->
            resolveMetadataCoverReference(
                snapshot = snapshot,
                audioName = audio.name,
                metadata = metadata
            )?.let { return@withContext it }
        }
        findIndexedEntryByNames(
            names = buildSidecarCandidateNames(candidateManagedDownloadBaseNames(audio.nameWithoutExtension)),
            entriesByName = snapshot.coverEntriesByName
        )?.reference
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
        val remoteCoverKeys = linkedSetOf<String>().apply {
            song.customCoverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            song.coverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            song.originalCoverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }
        val identityAlbum = song.identity().album.takeIf(String::isNotBlank)
        val allowAlbumFallback = remoteCoverKeys.isEmpty()

        return snapshot.metadataByAudioName.asSequence()
            .filter { (audioName, _) -> audioName != excludedAudioName }
            .mapNotNull { (audioName, metadata) ->
                val resolvedCoverReference = resolveMetadataCoverReference(
                    snapshot = snapshot,
                    audioName = audioName,
                    metadata = metadata
                ) ?: return@mapNotNull null
                val remoteMatch = remoteCoverKeys.isNotEmpty() &&
                    listOfNotNull(
                        metadata.customCoverUrl?.takeIf(String::isNotBlank),
                        metadata.coverUrl?.takeIf(String::isNotBlank),
                        metadata.originalCoverUrl?.takeIf(String::isNotBlank)
                    ).any(remoteCoverKeys::contains)
                val albumMatch = allowAlbumFallback &&
                    !identityAlbum.isNullOrBlank() &&
                    metadata.customCoverUrl.isNullOrBlank() &&
                    metadata.identityAlbum == identityAlbum
                when {
                    remoteMatch -> 2 to resolvedCoverReference
                    albumMatch -> 1 to resolvedCoverReference
                    else -> null
                }
            }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun resolveMetadataCoverReference(
        snapshot: DownloadLibrarySnapshot,
        audioName: String,
        metadata: DownloadedAudioMetadata
    ): String? {
        metadata.coverPath
            ?.takeIf(snapshot.knownReferences::contains)
            ?.let { return it }
        val baseName = audioName.substringBeforeLast('.', audioName)
        metadata.stableKey
            ?.takeIf(String::isNotBlank)
            ?.let { stableKey ->
                findIndexedEntryByNames(
                    names = buildStableCoverCandidateNames(baseName, stableKey),
                    entriesByName = snapshot.coverEntriesByName
                )?.reference
            }
            ?.let { return it }
        return findIndexedEntryByNames(
            names = buildSidecarCandidateNames(candidateManagedDownloadBaseNames(baseName)),
            entriesByName = snapshot.coverEntriesByName
        )?.reference
    }

    private fun buildStableCoverCandidateNames(baseName: String, stableKey: String): List<String> {
        val suffix = java.lang.Long.toHexString(stableKey.hashCode().toLong() and 0xffffffffL)
        return imageExtensions.map { extension -> "$baseName-$suffix.$extension" }
    }

    private suspend fun resolveRoot(context: Context, directoryUriString: String?): RootHandle? = withContext(Dispatchers.IO) {
        resolveRootBlocking(context, directoryUriString)
    }

    private fun resolveRootBlocking(context: Context): RootHandle {
        val configuredUri = normalizeDirectoryUri(customDirectoryUri)
        resolveTreeRootBlocking(context, configuredUri)?.let { return it }
        if (configuredUri != null) {
            NPLogger.w(TAG, "自定义下载目录不可用，回退默认目录: $configuredUri")
        }
        return createDefaultRoot(context)
    }

    private fun resolveRootBlocking(context: Context, directoryUriString: String?): RootHandle? {
        val normalizedUri = normalizeDirectoryUri(directoryUriString)
        val root = if (normalizedUri == null) {
            createDefaultRoot(context)
        } else {
            resolveTreeRootBlocking(context, normalizedUri)
        }
        return root
    }

    private fun findAudioEntry(
        snapshot: DownloadLibrarySnapshot,
        song: SongItem
    ): StoredEntry? {
        val identity = song.identity()
        val stableKey = identity.stableKey()
        val remoteTrackKey = buildRemoteTrackKey(song.channelId, song.audioId, song.subAudioId)

        snapshot.audioEntriesByStableKey[stableKey]
            ?.let { matches ->
                return pickBestAudioEntry(matches, song)?.also { entry ->
                    if (LOG_HOT_AUDIO_HITS) {
                        NPLogger.d(TAG, "命中已下载音频(stableKey): song=${song.displayName()}, file=${entry.name}")
                    }
                }
            }

        remoteTrackKey?.let { key ->
            snapshot.audioEntriesByRemoteTrackKey[key]
                ?.let { matches ->
                    return pickBestAudioEntry(matches, song)?.also { entry ->
                        if (LOG_HOT_AUDIO_HITS) {
                            NPLogger.d(TAG, "命中已下载音频(remoteTrackKey): song=${song.displayName()}, file=${entry.name}")
                        }
                    }
                }
        }

        identity.mediaUri?.let { mediaUri ->
            snapshot.audioEntriesByMediaUri[mediaUri]
                ?.let { matches ->
                    return pickBestAudioEntry(matches, song)?.also { entry ->
                        if (LOG_HOT_AUDIO_HITS) {
                            NPLogger.d(TAG, "命中已下载音频(mediaUri): song=${song.displayName()}, file=${entry.name}")
                        }
                    }
                }
        }

        identity.id.takeIf { it > 0L }?.let { songId ->
            snapshot.audioEntriesBySongId[songId]
                ?.let { matches ->
                    return pickBestAudioEntry(matches, song)?.also { entry ->
                        if (LOG_HOT_AUDIO_HITS) {
                            NPLogger.d(TAG, "命中已下载音频(songId): song=${song.displayName()}, file=${entry.name}")
                        }
                    }
                }
        }

        return findAudioEntry(
            audioEntries = snapshot.audioEntriesWithoutMetadata,
            baseNames = candidateManagedDownloadBaseNames(song, downloadFileNameTemplate)
        )?.also { entry ->
            if (LOG_HOT_AUDIO_HITS) {
                NPLogger.d(TAG, "命中已下载音频(legacyNameFallback): song=${song.displayName()}, file=${entry.name}")
            }
        }
    }

    private fun findAudioEntry(audioEntries: List<StoredEntry>, baseNames: List<String>): StoredEntry? {
        val exactCandidates = buildSet {
            baseNames.forEach { baseName ->
                audioExtensions.forEach { ext -> add("$baseName.$ext") }
            }
        }
        val patternCandidates = baseNames.map { baseName ->
            Regex("^${Regex.escape(baseName)}(?: \\(\\d+\\))?\\.[A-Za-z0-9]+$")
        }

        return audioEntries
            .filterNot(StoredEntry::isDirectory)
            .firstOrNull { entry ->
                entry.extension in audioExtensions && (
                    entry.name in exactCandidates ||
                        patternCandidates.any { it.matches(entry.name) }
                    )
            }
    }

    private fun pickBestAudioEntry(
        audioEntries: List<StoredEntry>,
        song: SongItem
    ): StoredEntry? {
        if (audioEntries.isEmpty()) return null
        val baseNames = candidateManagedDownloadBaseNames(song, downloadFileNameTemplate)
        return findAudioEntry(audioEntries, baseNames)
            ?: audioEntries.maxByOrNull(StoredEntry::lastModifiedMs)
    }

    private fun listChildren(context: Context, root: RootHandle): List<StoredEntry> {
        return when (root) {
            is RootHandle.FileRoot -> {
                root.dir.listFiles()
                    ?.map { file -> file.toStoredEntry() }
                    .orEmpty()
            }

            is RootHandle.TreeRoot -> {
                cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                ).map { child -> child.toStoredEntry() }
            }
        }
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
        val audioEntries = rootEntries.filter { it.extension in audioExtensions }
        val metadataEntries = rootEntries.filter { it.name.endsWith(METADATA_SUFFIX) }
        val coverEntries = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
        val lyricEntries = listSubdirectoryEntries(context, root, LYRIC_SUBDIRECTORY)
        val metadataEntriesByAudioName = metadataEntries.associateBy { entry ->
            entry.name.removeSuffix(METADATA_SUFFIX)
        }
        val coverEntryNames = coverEntries.mapTo(linkedSetOf(), StoredEntry::name)
        val lyricEntryNames = lyricEntries.mapTo(linkedSetOf(), StoredEntry::name)
        val managedAudioEntries = audioEntries.filter { entry ->
            shouldTreatAudioAsManaged(
                audioName = entry.name,
                metadataAudioNames = metadataEntriesByAudioName.keys,
                coverEntryNames = coverEntryNames,
                lyricEntryNames = lyricEntryNames,
                allowMetadataLessAudio = allowMetadataLessAudio
            )
        }
        if (managedAudioEntries.isEmpty() && metadataEntriesByAudioName.isEmpty()) {
            return emptyList()
        }

        val managedAudioNames = managedAudioEntries.mapTo(linkedSetOf(), StoredEntry::name)
        val parsedMetadataByAudioName = metadataEntriesByAudioName.mapNotNull { (audioName, entry) ->
            parseDownloadedAudioMetadata(context, entry)?.let { metadata ->
                audioName to metadata
            }
        }.toMap()
        val managedCoverNames = buildSet {
            managedAudioEntries.forEach { entry ->
                buildSidecarCandidateNames(
                    candidateManagedDownloadBaseNames(entry.nameWithoutExtension)
                ).forEach(::add)
            }
        }
        val managedLyricNames = buildSet {
            managedAudioEntries.forEach { entry ->
                val candidateBaseNames = candidateManagedDownloadBaseNames(entry.nameWithoutExtension)
                val songId = parsedMetadataByAudioName[entry.name]?.songId
                buildLyricCandidateNames(
                    songId = songId,
                    candidateBaseNames = candidateBaseNames,
                    translated = false
                ).forEach(::add)
                buildLyricCandidateNames(
                    songId = songId,
                    candidateBaseNames = candidateBaseNames,
                    translated = true
                ).forEach(::add)
            }
        }

        val migrationEntries = buildList {
            managedAudioEntries.forEach { entry ->
                add(ManagedMigrationEntry(subdirectory = null, entry = entry))
            }
            metadataEntries.forEach { entry ->
                if (entry.name.removeSuffix(METADATA_SUFFIX) in managedAudioNames) {
                    add(ManagedMigrationEntry(subdirectory = null, entry = entry))
                }
            }
            coverEntries.forEach { entry ->
                if (entry.name in managedCoverNames) {
                    add(ManagedMigrationEntry(subdirectory = COVER_SUBDIRECTORY, entry = entry))
                }
            }
            lyricEntries.forEach { entry ->
                if (entry.name in managedLyricNames) {
                    add(ManagedMigrationEntry(subdirectory = LYRIC_SUBDIRECTORY, entry = entry))
                }
            }
        }

        return migrationEntries.sortedWith(compareBy({ it.subdirectory ?: "" }, { it.entry.name }))
    }

    private fun listSubdirectoryEntries(context: Context, root: RootHandle, subdirectory: String): List<StoredEntry> {
        return findSubdirectories(context, root, subdirectory, canonicalLast = true)
            .flatMap { childRoot -> listChildren(context, childRoot) }
            .filterNot(StoredEntry::isDirectory)
    }

    private fun findIndexedEntryByNames(
        names: List<String>,
        entriesByName: Map<String, StoredEntry>
    ): StoredEntry? {
        return names.firstNotNullOfOrNull(entriesByName::get)
    }

    private fun buildSidecarCandidateNames(candidateBaseNames: List<String>): List<String> {
        return buildList {
            candidateBaseNames.forEach { baseName ->
                imageExtensions.forEach { extension ->
                    add("$baseName.$extension")
                }
            }
        }
    }

    internal fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): List<String> {
        val names = linkedSetOf<String>()
        fun addLyricNames(baseName: String) {
            if (translated) {
                names += "${baseName}_trans.lrc"
                names += "${baseName}_trans.lrc.txt"
            } else {
                names += "$baseName.lrc"
                names += "$baseName.lrc.txt"
            }
        }

        songId?.takeIf { it > 0L }?.let { resolvedSongId ->
            addLyricNames(resolvedSongId.toString())
        }
        candidateBaseNames.forEach(::addLyricNames)
        return names.toList()
    }

    private fun parseDownloadedAudioMetadata(
        context: Context,
        entry: StoredEntry
    ): DownloadedAudioMetadata? {
        val raw = readTextInternal(context, entry.reference) ?: return null
        return parseDownloadedAudioMetadataJson(raw)
    }

    private fun snapshotCacheFile(context: Context): File {
        return File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)
    }

    private fun persistSnapshotCache(
        context: Context,
        cacheKey: String,
        snapshot: DownloadLibrarySnapshot
    ) {
        runCatching {
            snapshotCacheFile(context).writeText(
                serializeSnapshotCachePayload(cacheKey, snapshot),
                Charsets.UTF_8
            )
        }.onFailure {
            NPLogger.w(TAG, "写入下载索引缓存失败: ${it.message}")
        }
    }

    private fun restoreSnapshotCacheFromDisk(
        context: Context,
        expectedKey: String? = null
    ): DownloadLibrarySnapshot? {
        val rawPayload = runCatching {
            snapshotCacheFile(context).takeIf(File::exists)?.readText(Charsets.UTF_8)
        }.onFailure {
            NPLogger.w(TAG, "读取下载索引缓存失败: ${it.message}")
        }.getOrNull() ?: return null

        val restored = runCatching {
            deserializeSnapshotCachePayload(rawPayload, expectedKey)
        }.onFailure {
            NPLogger.w(TAG, "解析下载索引缓存失败: ${it.message}")
        }.getOrNull() ?: return null

        snapshotCache = SnapshotCache(key = restored.first, snapshot = restored.second)
        return restored.second
    }

    private fun scheduleSnapshotCachePersist(
        context: Context,
        expectedKey: String
    ) {
        val appContext = context.applicationContext
        synchronized(snapshotPersistenceLock) {
            snapshotPersistJob?.cancel()
            snapshotPersistJob = snapshotScope.launch {
                delay(SNAPSHOT_CACHE_PERSIST_DEBOUNCE_MS)
                val currentCache = snapshotCache
                    ?.takeIf { it.key == expectedKey }
                    ?: return@launch
                persistSnapshotCache(appContext, currentCache.key, currentCache.snapshot)
            }
        }
    }

    internal fun serializeSnapshotCachePayload(
        cacheKey: String,
        snapshot: DownloadLibrarySnapshot
    ): String {
        return JSONObject().apply {
            put("cacheKey", cacheKey)
            put("audioEntries", snapshot.audioEntries.toJsonArray())
            put("metadataEntries", snapshot.metadataEntriesByAudioName.values.toList().toJsonArray())
            put("metadataByAudioName", JSONObject().apply {
                snapshot.metadataByAudioName.forEach { (audioName, metadata) ->
                    put(audioName, metadata.toJson())
                }
            })
            put("coverEntries", snapshot.coverEntriesByName.values.toList().toJsonArray())
            put("lyricEntries", snapshot.lyricEntriesByName.values.toList().toJsonArray())
        }.toString()
    }

    internal fun deserializeSnapshotCachePayload(
        raw: String,
        expectedKey: String? = null
    ): Pair<String, DownloadLibrarySnapshot>? {
        val root = JSONObject(raw)
        val cacheKey = root.optString("cacheKey").takeIf(String::isNotBlank) ?: return null
        if (expectedKey != null && expectedKey != cacheKey) {
            return null
        }

        val audioEntries = root.optJSONArray("audioEntries").toStoredEntries()
        val metadataEntries = root.optJSONArray("metadataEntries").toStoredEntries()
        val metadataRoot = root.optJSONObject("metadataByAudioName") ?: JSONObject()
        val metadataByAudioName = buildMap {
            metadataRoot.keys().forEach { audioName ->
                metadataRoot.optJSONObject(audioName)
                    ?.toDownloadedAudioMetadata()
                    ?.let { put(audioName, it) }
            }
        }
        val coverEntries = root.optJSONArray("coverEntries").toStoredEntries()
        val lyricEntries = root.optJSONArray("lyricEntries").toStoredEntries()
        return cacheKey to composeSnapshot(
            audioEntries = audioEntries,
            metadataEntries = metadataEntries,
            metadataByAudioName = metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries
        )
    }

    internal fun applyMetadataWriteToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        metadataEntry: StoredEntry,
        metadata: DownloadedAudioMetadata
    ): DownloadLibrarySnapshot {
        val targetAudioName = metadataEntry.name.removeSuffix(METADATA_SUFFIX)
        return composeSnapshot(
            audioEntries = snapshot.audioEntries,
            metadataEntries = snapshot.metadataEntriesByAudioName.values
                .filterNot { it.name.removeSuffix(METADATA_SUFFIX) == targetAudioName } +
                metadataEntry,
            metadataByAudioName = snapshot.metadataByAudioName.toMutableMap().apply {
                put(targetAudioName, metadata)
            },
            coverEntries = snapshot.coverEntriesByName.values.toList(),
            lyricEntries = snapshot.lyricEntriesByName.values.toList()
        )
    }

    internal fun applyStoredEntryWriteToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        storedEntry: StoredEntry,
        bucket: SnapshotEntryBucket
    ): DownloadLibrarySnapshot {
        return when (bucket) {
            SnapshotEntryBucket.AUDIO -> composeSnapshot(
                audioEntries = replaceStoredEntry(snapshot.audioEntries, storedEntry),
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = snapshot.coverEntriesByName.values.toList(),
                lyricEntries = snapshot.lyricEntriesByName.values.toList()
            )

            SnapshotEntryBucket.COVER -> composeSnapshot(
                audioEntries = snapshot.audioEntries,
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = replaceStoredEntry(snapshot.coverEntriesByName.values, storedEntry),
                lyricEntries = snapshot.lyricEntriesByName.values.toList()
            )

            SnapshotEntryBucket.LYRIC -> composeSnapshot(
                audioEntries = snapshot.audioEntries,
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = snapshot.coverEntriesByName.values.toList(),
                lyricEntries = replaceStoredEntry(snapshot.lyricEntriesByName.values, storedEntry)
            )
        }
    }

    internal fun applyReferenceDeletesToSnapshot(
        snapshot: DownloadLibrarySnapshot,
        references: Set<String>
    ): DownloadLibrarySnapshot {
        if (references.isEmpty()) {
            return snapshot
        }
        val deletedMetadataAudioNames = snapshot.metadataEntriesByAudioName.values
            .filter { entry -> entry.reference in references }
            .mapTo(linkedSetOf()) { entry -> entry.name.removeSuffix(METADATA_SUFFIX) }
        return composeSnapshot(
            audioEntries = snapshot.audioEntries.filterNot { entry -> entry.reference in references },
            metadataEntries = snapshot.metadataEntriesByAudioName.values
                .filterNot { entry -> entry.reference in references },
            metadataByAudioName = snapshot.metadataByAudioName.filterKeys { audioName ->
                audioName !in deletedMetadataAudioNames
            },
            coverEntries = snapshot.coverEntriesByName.values
                .filterNot { entry -> entry.reference in references },
            lyricEntries = snapshot.lyricEntriesByName.values
                .filterNot { entry -> entry.reference in references }
        )
    }

    private fun replaceStoredEntry(
        entries: Collection<StoredEntry>,
        storedEntry: StoredEntry
    ): List<StoredEntry> {
        return entries
            .filterNot { entry ->
                entry.reference == storedEntry.reference || entry.name == storedEntry.name
            } + storedEntry
    }

    private fun buildRemoteTrackKey(
        channelId: String?,
        audioId: String?,
        subAudioId: String?
    ): String? {
        val resolvedChannelId = channelId?.takeIf { it.isNotBlank() } ?: return null
        val resolvedAudioId = audioId?.takeIf { it.isNotBlank() }.orEmpty()
        val resolvedSubAudioId = subAudioId?.takeIf { it.isNotBlank() }.orEmpty()
        if (resolvedAudioId.isBlank() && resolvedSubAudioId.isBlank()) {
            return null
        }
        return "$resolvedChannelId|$resolvedAudioId|$resolvedSubAudioId"
    }

    private fun buildSnapshotCacheKey(context: Context): String {
        val configuredIdentity = directoryIdentity(customDirectoryUri)
        return if (configuredIdentity != null) {
            "tree:$configuredIdentity"
        } else {
            "file:${createDefaultRoot(context).dir.absolutePath}"
        }
    }

    private fun invalidateSnapshotCache(context: Context? = null) {
        snapshotCache = null
        synchronized(snapshotPersistenceLock) {
            snapshotPersistJob?.cancel()
            snapshotPersistJob = null
        }
        val appContext = context?.applicationContext ?: return
        runCatching {
            val cacheFile = snapshotCacheFile(appContext)
            if (cacheFile.exists() && !cacheFile.delete()) {
                throw IOException("无法删除旧下载索引缓存")
            }
        }.onFailure {
            NPLogger.w(TAG, "清理下载索引缓存失败: ${it.message}")
        }
    }

    private fun cleanupPendingAudioWrites(context: Context): StartupRecoveryResult {
        return runCatching {
            val pendingEntries = when (val root = resolveRootBlocking(context)) {
                is RootHandle.FileRoot -> root.dir.listFiles()
                    ?.filter(File::isFile)
                    ?.filter { file -> isPendingAudioWriteName(file.name) }
                    .orEmpty()

                is RootHandle.TreeRoot -> queryTreeChildren(context, root.tree)
                    .filterNot(QueriedTreeChild::isDirectory)
                    .filter { child -> isPendingAudioWriteName(child.name) }
            }

            var cleanedCount = 0
            var failedCount = 0
            pendingEntries.forEach { entry ->
                val deleted = when (entry) {
                    is File -> runCatching { !entry.exists() || entry.delete() }.getOrDefault(false)
                    is QueriedTreeChild -> deleteContentReference(
                        context = context,
                        reference = entry.documentUri.toString(),
                        uri = entry.documentUri
                    )
                    else -> false
                }
                if (deleted) {
                    cleanedCount++
                } else {
                    failedCount++
                }
            }
            if (cleanedCount > 0 || failedCount > 0) {
                NPLogger.d(TAG, "清理下载提交残留完成: cleaned=$cleanedCount, failed=$failedCount")
            }
            StartupRecoveryResult(
                cleanedCount = cleanedCount,
                failedCount = failedCount
            )
        }.onFailure {
            NPLogger.w(TAG, "清理下载提交残留失败: ${it.message}")
        }.getOrDefault(StartupRecoveryResult())
    }

    internal fun cleanupUnfinalizedDownloadArtifacts(context: Context): StartupRecoveryResult {
        return runCatching {
            val root = resolveRootBlocking(context)
            val rootEntries = listChildren(context, root).filterNot(StoredEntry::isDirectory)
            val audioEntriesByName = rootEntries
                .filter { entry -> entry.extension in audioExtensions }
                .associateBy(StoredEntry::name)
            val parsedMetadataEntries = rootEntries
                .filter { entry -> entry.name.endsWith(METADATA_SUFFIX) }
                .mapNotNull { entry ->
                    val metadata = parseDownloadedAudioMetadata(context, entry) ?: return@mapNotNull null
                    entry to metadata
                }
            val unfinalizedMetadataEntries = parsedMetadataEntries
                .filter { (_, metadata) -> isUnfinalizedDownloadedMetadata(metadata) }
            if (unfinalizedMetadataEntries.isEmpty()) {
                return@runCatching StartupRecoveryResult()
            }
            val managedSidecarReferences = listSubdirectoryEntries(context, root, COVER_SUBDIRECTORY)
                .plus(listSubdirectoryEntries(context, root, LYRIC_SUBDIRECTORY))
                .mapTo(linkedSetOf(), StoredEntry::reference)
            val protectedReferences = parsedMetadataEntries
                .asSequence()
                .filterNot { (_, metadata) -> isUnfinalizedDownloadedMetadata(metadata) }
                .flatMap { (_, metadata) ->
                    sequenceOf(metadata.coverPath, metadata.lyricPath, metadata.translatedLyricPath)
                }
                .filterNot(String?::isNullOrBlank)
                .map(String?::orEmpty)
                .filter(managedSidecarReferences::contains)
                .toSet()
            val referencesToDelete = linkedSetOf<String>()
            unfinalizedMetadataEntries.forEach { (entry, metadata) ->
                val audio = audioEntriesByName[entry.name.removeSuffix(METADATA_SUFFIX)]
                if (audio?.sizeBytes?.let { it > 0L } == true) {
                    return@forEach
                }
                referencesToDelete += entry.reference
                audio?.reference?.let(referencesToDelete::add)
                listOf(metadata.coverPath, metadata.lyricPath, metadata.translatedLyricPath)
                    .filterNot(String?::isNullOrBlank)
                    .map(String?::orEmpty)
                    .filter(managedSidecarReferences::contains)
                    .filterNot(protectedReferences::contains)
                    .forEach(referencesToDelete::add)
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
        return name.contains(PENDING_AUDIO_WRITE_MARKER)
    }

    private fun buildPendingAudioWriteName(fileName: String): String {
        val pendingId = pendingAudioWriteIdGenerator.incrementAndGet()
        return "$fileName$PENDING_AUDIO_WRITE_MARKER.$pendingId"
    }

    private fun queryTreeChildren(context: Context, parent: DocumentFile): List<QueriedTreeChild> {
        val parentUri = parent.uri
        val documentId = runCatching { DocumentsContract.getDocumentId(parentUri) }.getOrNull()
            ?: return parent.listFiles().mapNotNull { file ->
                file.name?.let { name ->
                    QueriedTreeChild(
                        name = name,
                        documentUri = file.uri,
                        sizeBytes = file.length(),
                        lastModifiedMs = file.lastModified(),
                        isDirectory = file.isDirectory
                    )
                }
            }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, documentId)
        return runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || nameIndex < 0 || mimeTypeIndex < 0) {
                    return@use emptyList()
                }
                buildList {
                    while (cursor.moveToNext()) {
                        val childDocumentId = cursor.getString(idIndex) ?: continue
                        val childName = cursor.getString(nameIndex) ?: continue
                        val childMimeType = cursor.getString(mimeTypeIndex).orEmpty()
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, childDocumentId)
                        add(
                            QueriedTreeChild(
                                name = childName,
                                documentUri = childUri,
                                sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                                    cursor.getLong(sizeIndex)
                                } else {
                                    0L
                                },
                                lastModifiedMs = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                                    cursor.getLong(modifiedIndex)
                                } else {
                                    0L
                                },
                                isDirectory = childMimeType == DocumentsContract.Document.MIME_TYPE_DIR
                            )
                        )
                    }
                }
            }.orEmpty()
        }.onFailure {
            NPLogger.w(TAG, "查询目录子项失败，回退 DocumentFile 枚举: ${it.message}")
        }.getOrElse {
            parent.listFiles().mapNotNull { file ->
                file.name?.let { name ->
                    QueriedTreeChild(
                        name = name,
                        documentUri = file.uri,
                        sizeBytes = file.length(),
                        lastModifiedMs = file.lastModified(),
                        isDirectory = file.isDirectory
                    )
                }
            }
        }
    }

    private fun cachedFileChildrenNames(dir: File): Set<String> {
        val cacheKey = dir.absolutePath
        val now = System.currentTimeMillis()
        fileChildrenNameCache[cacheKey]
            ?.takeIf {
                it.isComplete &&
                    now - it.refreshedAtMs <= FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
            }
            ?.let { return it.names }
        val refreshedNames = dir.listFiles()
            ?.mapNotNull(File::getName)
            .orEmpty()
        val refreshed = CachedChildNames(
            initialNames = refreshedNames,
            initialRefreshedAtMs = now,
            initialComplete = true
        )
        fileChildrenNameCache[cacheKey] = refreshed
        return refreshed.names
    }

    private fun rememberFileChildName(dir: File, childName: String) {
        val cacheKey = dir.absolutePath
        val now = System.currentTimeMillis()
        fileChildrenNameCache[cacheKey]?.let { cached ->
            cached.names += childName
            cached.refreshedAtMs = now
            return
        }
        fileChildrenNameCache[cacheKey] = CachedChildNames(
            initialNames = listOf(childName),
            initialRefreshedAtMs = now,
            initialComplete = false
        )
    }

    private fun reserveUniqueFileChildName(dir: File, desiredName: String): String {
        val cacheKey = dir.absolutePath
        val lock = childNameReservationLocks.computeIfAbsent("file:$cacheKey") { Any() }
        return synchronized(lock) {
            createUniqueName(cachedFileChildrenNames(dir), desiredName)
                .also { reservedName -> rememberFileChildName(dir, reservedName) }
        }
    }

    private fun forgetFileChildName(dir: File, childName: String) {
        val cacheKey = dir.absolutePath
        val cached = fileChildrenNameCache[cacheKey] ?: return
        cached.names -= childName
        cached.refreshedAtMs = System.currentTimeMillis()
    }

    private fun cachedTreeChildrenNames(context: Context, parent: DocumentFile): Set<String> {
        return cachedTreeChildrenNames(
            context = context,
            parent = parent,
            maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
        )
    }

    private fun cachedTreeChildrenNamesForWrite(context: Context, parent: DocumentFile): Set<String> {
        return cachedTreeChildrenNames(
            context = context,
            parent = parent,
            maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS,
            allowReservedNames = true
        )
    }

    private fun cachedTreeChildrenNames(
        context: Context,
        parent: DocumentFile,
        maxCacheAgeMs: Long,
        allowReservedNames: Boolean = false
    ): Set<String> {
        val cacheKey = parent.uri.toString()
        val now = System.currentTimeMillis()
        val cachedNames = treeChildrenNameCache[cacheKey]
        val cachedEntries = treeChildrenEntryCache[cacheKey]
        if (maxCacheAgeMs > 0L) {
            cachedNames
                ?.takeIf { cached ->
                    now - cached.refreshedAtMs <= maxCacheAgeMs &&
                        (
                            cached.isComplete ||
                                (
                                    allowReservedNames &&
                                        cachedEntries?.isComplete == true &&
                                        now - cachedEntries.refreshedAtMs <= maxCacheAgeMs
                                    )
                            )
                }
                ?.let { return it.names }
        }
        val refreshedChildren = queryTreeChildren(context, parent)
        rememberTreeChildren(parent, refreshedChildren, now, isComplete = true)
        return treeChildrenNameCache[cacheKey]?.names
            ?: refreshedChildren.mapTo(linkedSetOf(), QueriedTreeChild::name)
    }

    private fun refreshTreeChildren(
        context: Context,
        parent: DocumentFile
    ): Collection<QueriedTreeChild> {
        val refreshedAtMs = System.currentTimeMillis()
        return queryTreeChildren(context, parent).also { children ->
            rememberTreeChildren(parent, children, refreshedAtMs, isComplete = true)
        }
    }

    private fun cachedTreeChildren(
        context: Context,
        parent: DocumentFile,
        maxCacheAgeMs: Long
    ): Collection<QueriedTreeChild> {
        val cacheKey = parent.uri.toString()
        val now = System.currentTimeMillis()
        if (maxCacheAgeMs > 0L) {
            treeChildrenEntryCache[cacheKey]
                ?.takeIf { it.isComplete && now - it.refreshedAtMs <= maxCacheAgeMs }
                ?.let { return it.childrenByName.values }
        }
        return refreshTreeChildren(context, parent)
    }

    private fun cachedTreeChild(
        context: Context,
        parent: DocumentFile,
        childName: String,
        maxCacheAgeMs: Long = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
    ): QueriedTreeChild? {
        return cachedTreeChildren(context, parent, maxCacheAgeMs)
            .firstOrNull { child -> child.name == childName }
    }

    private fun rememberTreeChildren(
        parent: DocumentFile,
        children: Collection<QueriedTreeChild>,
        refreshedAtMs: Long,
        isComplete: Boolean
    ) {
        val cacheKey = parent.uri.toString()
        val childNames = children.map(QueriedTreeChild::name)
        val cachedNames = treeChildrenNameCache[cacheKey]
        val refreshedNames = mergeTreeChildNamesAfterRefresh(
            refreshedNames = childNames,
            cachedNames = cachedNames?.names,
            cachedNamesComplete = cachedNames?.isComplete,
            refreshedComplete = isComplete
        )
        treeChildrenEntryCache[cacheKey] = CachedTreeChildren(
            initialChildren = children,
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = isComplete
        )
        treeChildrenNameCache[cacheKey] = CachedChildNames(
            initialNames = refreshedNames.names,
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = refreshedNames.isComplete
        )
    }

    internal fun mergeTreeChildNamesAfterRefresh(
        refreshedNames: Collection<String>,
        cachedNames: Collection<String>?,
        cachedNamesComplete: Boolean?,
        refreshedComplete: Boolean
    ): TreeChildNameRefresh {
        if (cachedNamesComplete != false) {
            return TreeChildNameRefresh(
                names = refreshedNames.toCollection(linkedSetOf()),
                isComplete = refreshedComplete
            )
        }
        return TreeChildNameRefresh(
            names = (refreshedNames + cachedNames.orEmpty()).toCollection(linkedSetOf()),
            isComplete = false
        )
    }

    private fun rememberTreeChildName(
        parent: DocumentFile,
        childName: String,
        isReservation: Boolean = true
    ) {
        val cacheKey = parent.uri.toString()
        val now = System.currentTimeMillis()
        treeChildrenNameCache[cacheKey]?.let { cached ->
            cached.names += childName
            cached.refreshedAtMs = now
            if (isReservation) {
                cached.isComplete = false
            }
            return
        }
        treeChildrenNameCache[cacheKey] = CachedChildNames(
            initialNames = listOf(childName),
            initialRefreshedAtMs = now,
            initialComplete = false
        )
    }

    private fun rememberTreeChild(parent: DocumentFile, child: QueriedTreeChild) {
        rememberTreeChildName(parent, child.name, isReservation = false)
        val cacheKey = parent.uri.toString()
        val now = System.currentTimeMillis()
        treeChildrenEntryCache[cacheKey]?.let { cached ->
            cached.childrenByName[child.name] = child
            cached.refreshedAtMs = now
            return
        }
        treeChildrenEntryCache[cacheKey] = CachedTreeChildren(
            initialChildren = listOf(child),
            initialRefreshedAtMs = now,
            initialComplete = false
        )
    }

    private fun rememberTreeChild(parent: DocumentFile, entry: StoredEntry) {
        val childUri = runCatching { entry.reference.toUri() }.getOrNull() ?: return
        updateRememberedTreeChild(
            parent = parent,
            childName = entry.name,
            documentUri = childUri,
            sizeBytes = entry.sizeBytes,
            lastModifiedMs = entry.lastModifiedMs,
            isDirectory = entry.isDirectory
        )
    }

    private fun updateRememberedTreeChild(
        parent: DocumentFile,
        childName: String,
        documentUri: Uri,
        sizeBytes: Long,
        lastModifiedMs: Long,
        isDirectory: Boolean
    ) {
        rememberTreeChild(
            parent = parent,
            child = QueriedTreeChild(
                name = childName,
                documentUri = documentUri,
                sizeBytes = sizeBytes,
                lastModifiedMs = lastModifiedMs,
                isDirectory = isDirectory
            )
        )
    }

    private fun reserveUniqueTreeChildName(
        context: Context,
        parent: DocumentFile,
        desiredName: String
    ): String {
        val cacheKey = parent.uri.toString()
        val lock = childNameReservationLocks.computeIfAbsent("tree:$cacheKey") { Any() }
        return synchronized(lock) {
            createUniqueName(cachedTreeChildrenNamesForWrite(context, parent), desiredName)
                .also { reservedName -> rememberTreeChildName(parent, reservedName) }
        }
    }

    private fun forgetTreeChildName(parent: DocumentFile, childName: String) {
        val cacheKey = parent.uri.toString()
        forgetTreeChildName(cacheKey, childName)
    }

    private fun forgetTreeChildName(cacheKey: String, childName: String) {
        val now = System.currentTimeMillis()
        treeChildrenNameCache[cacheKey]?.let { cached ->
            cached.names -= childName
            cached.refreshedAtMs = now
        }
        treeChildrenEntryCache[cacheKey]?.let { entries ->
            entries.childrenByName -= childName
            entries.refreshedAtMs = now
        }
    }

    internal fun resolveTreeStoredName(actualName: String?, expectedName: String): String {
        return actualName?.takeIf(String::isNotBlank) ?: expectedName
    }

    private fun DocumentFile.resolvedTreeStoredName(expectedName: String): String {
        val resolvedName = resolveTreeStoredName(name, expectedName)
        if (resolvedName != expectedName) {
            NPLogger.w(
                TAG,
                "SAF 文件名与预期不一致: expected=$expectedName, actual=$resolvedName, uri=$uri"
            )
        }
        return resolvedName
    }

    private fun createRootFile(
        context: Context,
        parent: DocumentFile,
        desiredName: String,
        mimeType: String,
        replace: Boolean
    ): DocumentFile {
        val childNames = cachedTreeChildrenNamesForWrite(context, parent)
        val existingChild = desiredName
            .takeIf { it in childNames }
            ?.let { cachedTreeChild(context, parent, it) }
        val existing = existingChild?.toDocumentFile(context)
        if (replace && existingChild != null && !existingChild.isDirectory && existing != null) {
            return existing
        }
        if (replace && existingChild != null) {
            deleteContentReference(
                context = context,
                reference = existingChild.documentUri.toString(),
                uri = existingChild.documentUri
            )
            forgetTreeChildName(parent, desiredName)
        }
        val finalName = if (replace) desiredName else createUniqueName(childNames, desiredName)
        return (
            parent.createFile(documentCreateMimeType(finalName, mimeType), finalName)
                ?: throw IOException("无法在下载目录创建文件: $finalName")
            ).also { created ->
                val storedName = created.resolvedTreeStoredName(finalName)
                rememberTreeChild(
                    parent = parent,
                    child = QueriedTreeChild(
                        name = storedName,
                        documentUri = created.uri,
                        sizeBytes = 0L,
                        lastModifiedMs = System.currentTimeMillis(),
                        isDirectory = false
                    )
                )
            }
    }

    private fun commitTreeAudioAfterRenameFailure(
        context: Context,
        parent: DocumentFile,
        pendingTarget: DocumentFile,
        pendingName: String,
        finalName: String,
        mimeType: String?,
        tempFile: File,
        actualSizeBytes: Long,
        committedAtMs: Long
    ): StoredEntry {
        NPLogger.w(TAG, "SAF 重命名失败，回退为直接写入最终文件: $finalName")
        return try {
            val target = parent.createFile(
                documentCreateMimeType(finalName, mimeTypeFromName(finalName, mimeType)),
                finalName
            ) ?: throw IOException("无法在下载目录创建文件: $finalName")
            val storedName = target.resolvedTreeStoredName(finalName)

            try {
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                    }
                } ?: throw IOException("无法打开下载目录输出流")
            } catch (error: Throwable) {
                deleteContentReference(context, target.uri.toString(), target.uri)
                throw error
            }

            if (storedName != finalName) {
                forgetTreeChildName(parent, finalName)
            }
            val entry = verifiedTreeStoredEntry(
                context = context,
                target = target,
                expectedName = storedName,
                expectedSizeBytes = actualSizeBytes,
                fallbackLastModifiedMs = committedAtMs,
                description = finalName
            )
            rememberTreeChild(parent, entry)
            entry
        } finally {
            deleteContentReference(context, pendingTarget.uri.toString(), pendingTarget.uri)
            forgetTreeChildName(parent, pendingName)
        }
    }

    internal fun documentCreateMimeType(desiredName: String, mimeType: String): String {
        val normalizedMimeType = mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val extension = desiredName.substringAfterLast('.', "").lowercase()
        if (normalizedMimeType.equals("text/plain", ignoreCase = true) && extension.isNotBlank() && extension != "txt") {
            return "application/octet-stream"
        }
        if (
            extension.isNotBlank() &&
            (
                normalizedMimeType.startsWith("audio/", ignoreCase = true) ||
                    normalizedMimeType.startsWith("image/", ignoreCase = true)
                )
        ) {
            // 有些 SAF 提供方会按 MIME 再补一次后缀，二进制兜底能保住原文件名
            return "application/octet-stream"
        }
        if (
            normalizedMimeType.equals("application/json", ignoreCase = true) &&
            desiredName.endsWith(METADATA_SUFFIX, ignoreCase = true)
        ) {
            return "application/octet-stream"
        }
        return normalizedMimeType
    }

    private fun writeRootStream(
        context: Context,
        root: RootHandle,
        displayName: String,
        mimeType: String,
        input: InputStream,
        invalidateSnapshot: Boolean = true
    ): StoredEntry {
        val storedEntry = when (root) {
            is RootHandle.FileRoot -> {
                val target = File(root.dir, displayName)
                var copiedBytes = 0L
                target.outputStream().use { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                }
                val verifiedSize = verifyFileCommittedLength(
                    target = target,
                    expectedSizeBytes = copiedBytes,
                    description = displayName
                )
                target.toStoredEntry().copy(sizeBytes = verifiedSize)
            }

            is RootHandle.TreeRoot -> {
                val target = createRootFile(context, root.tree, displayName, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                } ?: throw IOException("无法写入根目录文件: $displayName")
                val entry = verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = displayName,
                    expectedSizeBytes = copiedBytes.coerceAtLeast(0L),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                )
                rememberTreeChild(root.tree, entry)
                entry
            }
        }
        if (invalidateSnapshot) {
            invalidateSnapshotCache(context)
        }
        return storedEntry
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
        return when (root) {
            is RootHandle.FileRoot -> {
                val target = resolveFileMigrationTarget(
                    parent = root.dir,
                    displayName = displayName,
                    sourceEntry = sourceEntry,
                    targetNames = targetNames,
                    targetEntry = targetEntry
                )
                if (!target.createdNew) {
                    return target
                }
                val targetFile = File(root.dir, target.entry.name)
                var copiedBytes = 0L
                targetFile.outputStream().use { output ->
                    copiedBytes = copyStreamWithProgress(input, output, onProgress)
                }
                val verifiedSize = verifyFileCommittedLength(
                    target = targetFile,
                    expectedSizeBytes = copiedBytes,
                    description = target.entry.name
                )
                StoredWriteResult(
                    entry = targetFile.toStoredEntry().copy(sizeBytes = verifiedSize),
                    createdNew = true
                )
            }

            is RootHandle.TreeRoot -> {
                val targetPlan = resolveTreeMigrationTarget(
                    context = context,
                    parent = root.tree,
                    displayName = displayName,
                    sourceEntry = sourceEntry,
                    targetNames = targetNames,
                    targetEntry = targetEntry
                )
                if (!targetPlan.createdNew) {
                    return targetPlan
                }
                val target = createRootFile(context, root.tree, targetPlan.entry.name, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = copyStreamWithProgress(input, output, onProgress)
                } ?: throw IOException("无法写入根目录文件: ${targetPlan.entry.name}")
                val entry = verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = targetPlan.entry.name,
                    expectedSizeBytes = copiedBytes.coerceAtLeast(0L),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                )
                rememberTreeChild(root.tree, entry)
                StoredWriteResult(
                    entry = entry,
                    createdNew = true
                )
            }
        }
    }

    private fun copyStreamWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        if (onProgress == null) {
            return input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
        }
        val buffer = ByteArray(STREAM_COPY_BUFFER_SIZE_BYTES)
        var copiedBytes = 0L
        while (true) {
            val readCount = input.read(buffer)
            if (readCount < 0) {
                break
            }
            if (readCount == 0) {
                continue
            }
            output.write(buffer, 0, readCount)
            copiedBytes += readCount
            onProgress(copiedBytes)
        }
        return copiedBytes
    }

    internal fun verifiedCommittedByteCount(
        expectedSizeBytes: Long,
        reportedSizeBytes: Long?,
        countedSizeBytes: Long?
    ): Long? {
        val expectedSize = expectedSizeBytes.coerceAtLeast(0L)
        val reportedSize = reportedSizeBytes?.takeIf { it >= 0L }
        if (reportedSize != null) {
            return reportedSize.takeIf { it == expectedSize }
        }
        return countedSizeBytes
            ?.takeIf { it >= 0L }
            ?.takeIf { it == expectedSize }
    }

    private fun requireVerifiedCommittedByteCount(
        expectedSizeBytes: Long,
        reportedSizeBytes: Long?,
        countedSizeBytes: Long?,
        description: String
    ): Long {
        return verifiedCommittedByteCount(
            expectedSizeBytes = expectedSizeBytes,
            reportedSizeBytes = reportedSizeBytes,
            countedSizeBytes = countedSizeBytes
        ) ?: throw IOException(
            "提交后的目标大小不匹配: $description, expected=${expectedSizeBytes.coerceAtLeast(0L)}, " +
                "reported=${reportedSizeBytes ?: "unavailable"}, counted=${countedSizeBytes ?: "unavailable"}"
        )
    }

    private fun verifyFileCommittedLength(
        target: File,
        expectedSizeBytes: Long,
        description: String
    ): Long {
        val reportedSize = target.takeIf { it.exists() && it.isFile }?.length()
        return requireVerifiedCommittedByteCount(
            expectedSizeBytes = expectedSizeBytes,
            reportedSizeBytes = reportedSize,
            countedSizeBytes = null,
            description = description
        )
    }

    private fun verifyDocumentCommittedLength(
        context: Context,
        uri: Uri,
        expectedSizeBytes: Long,
        description: String
    ): Long {
        val reportedSize = queryDocumentSizeBytes(context, uri)
        val countedSize = if (reportedSize == null) {
            countDocumentBytes(context, uri)
        } else {
            null
        }
        return requireVerifiedCommittedByteCount(
            expectedSizeBytes = expectedSizeBytes,
            reportedSizeBytes = reportedSize,
            countedSizeBytes = countedSize,
            description = description
        )
    }

    private fun queryDocumentSizeBytes(context: Context, uri: Uri): Long? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (sizeIndex < 0 || !cursor.moveToFirst() || cursor.isNull(sizeIndex)) {
                    null
                } else {
                    cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        }.onFailure {
            NPLogger.w(TAG, "查询 SAF 目标大小失败: $uri, ${it.message}")
        }.getOrNull()
    }

    private fun countDocumentBytes(context: Context, uri: Uri): Long? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                countInputStreamBytes(input)
            }
        }.onFailure {
            NPLogger.w(TAG, "回读 SAF 目标失败: $uri, ${it.message}")
        }.getOrNull()
    }

    private fun countInputStreamBytes(input: InputStream): Long {
        val buffer = ByteArray(STREAM_COPY_BUFFER_SIZE_BYTES)
        var countedBytes = 0L
        while (true) {
            val readCount = input.read(buffer)
            if (readCount < 0) {
                return countedBytes
            }
            if (readCount == 0) {
                continue
            }
            countedBytes += readCount
        }
    }

    private fun verifiedTreeStoredEntry(
        context: Context,
        target: DocumentFile,
        expectedName: String,
        expectedSizeBytes: Long,
        fallbackLastModifiedMs: Long,
        description: String
    ): StoredEntry {
        val storedName = target.resolvedTreeStoredName(expectedName)
        val verifiedSize = verifyDocumentCommittedLength(
            context = context,
            uri = target.uri,
            expectedSizeBytes = expectedSizeBytes,
            description = description
        )
        return target.toStoredEntry(
            knownName = storedName,
            knownSizeBytes = verifiedSize,
            knownLastModifiedMs = target.lastModified().takeIf { it > 0L } ?: fallbackLastModifiedMs,
            knownIsDirectory = false
        ) ?: throw IOException("无法读取已写入的目录文件: $description")
    }

    private fun buildMigrationTargetIndex(
        context: Context,
        targetRoot: RootHandle
    ): MigrationTargetIndex {
        val rootEntriesByName = listChildren(context, targetRoot)
            .associateBy(StoredEntry::name)
        val coverEntriesByName = findSubdirectories(context, targetRoot, COVER_SUBDIRECTORY, canonicalLast = true)
            .flatMap { listChildren(context, it) }
            .associateBy(StoredEntry::name)
        val lyricEntriesByName = findSubdirectories(context, targetRoot, LYRIC_SUBDIRECTORY, canonicalLast = true)
            .flatMap { listChildren(context, it) }
            .associateBy(StoredEntry::name)
        return MigrationTargetIndex(
            rootEntriesByName = rootEntriesByName,
            coverEntriesByName = coverEntriesByName,
            lyricEntriesByName = lyricEntriesByName
        )
    }

    private fun buildMigrationNamePlan(
        entries: List<ManagedMigrationEntry>,
        targetIndex: MigrationTargetIndex
    ): MigrationNamePlan {
        val plannedNames = mutableMapOf<String, String>()
        val reservedRootNames = targetIndex.rootEntriesByName.keys.toMutableSet()
        val audioEntriesByName = entries
            .filter { it.subdirectory == null && it.entry.extension in audioExtensions }
            .associateBy { it.entry.name }

        audioEntriesByName.values
            .sortedBy { it.entry.name }
            .forEach { audioEntry ->
                val targetName = resolvePlannedMigrationName(
                    desiredName = audioEntry.entry.name,
                    sourceEntry = audioEntry.entry,
                    targetEntry = targetIndex.entryFor(null, audioEntry.entry.name),
                    reservedNames = reservedRootNames
                )
                plannedNames[audioEntry.entry.reference] = targetName
                val metadataName = audioEntry.entry.name + METADATA_SUFFIX
                val metadataTargetName = targetName + METADATA_SUFFIX
                entries.firstOrNull { candidate ->
                    candidate.subdirectory == null && candidate.entry.name == metadataName
                }?.let { metadataEntry ->
                    plannedNames[metadataEntry.entry.reference] = metadataTargetName
                    reservedRootNames += metadataTargetName
                }
            }

        return MigrationNamePlan(targetNamesByReference = plannedNames)
    }

    private fun resolvePlannedMigrationName(
        desiredName: String,
        sourceEntry: StoredEntry,
        targetEntry: StoredEntry?,
        reservedNames: MutableSet<String>
    ): String {
        if (targetEntry == null && desiredName !in reservedNames) {
            reservedNames += desiredName
            return desiredName
        }
        if (targetEntry != null && isEquivalentMigrationTarget(sourceEntry, targetEntry)) {
            return desiredName
        }
        val resolvedName = createUniqueName(reservedNames, desiredName)
        reservedNames += resolvedName
        return resolvedName
    }

    private fun resolveFileMigrationTarget(
        parent: File,
        displayName: String,
        sourceEntry: StoredEntry,
        targetNames: Set<String>,
        targetEntry: StoredEntry? = null
    ): StoredWriteResult {
        val existing = File(parent, displayName)
        if (displayName in targetNames || existing.exists()) {
            if (sourceEntry.name.endsWith(METADATA_SUFFIX)) {
                (targetEntry ?: existing.takeIf(File::isFile)?.toStoredEntry())
                    ?.let { existingEntry ->
                        NPLogger.d(TAG, "迁移复用目标 metadata: ${existingEntry.name}")
                        return StoredWriteResult(entry = existingEntry, createdNew = false)
                    }
            }
            targetEntry
                ?.takeIf { existingEntry -> isEquivalentMigrationTarget(sourceEntry, existingEntry) }
                ?.let { existingEntry ->
                    NPLogger.d(TAG, "迁移复用目标文件: ${existingEntry.name}")
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            existing.takeIf(File::isFile)
                ?.toStoredEntry()
                ?.takeIf { existingEntry -> isEquivalentMigrationTarget(sourceEntry, existingEntry) }
                ?.let { existingEntry ->
                    NPLogger.d(TAG, "迁移复用目标文件: ${existingEntry.name}")
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            val reservedName = reserveUniqueFileChildName(parent, displayName)
            return StoredWriteResult(
                entry = plannedStoredEntry(reservedName),
                createdNew = true
            )
        }
        rememberFileChildName(parent, displayName)
        return StoredWriteResult(
            entry = plannedStoredEntry(displayName),
            createdNew = true
        )
    }

    private fun resolveTreeMigrationTarget(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        sourceEntry: StoredEntry,
        targetNames: Set<String>,
        targetEntry: StoredEntry? = null
    ): StoredWriteResult {
        if (displayName in targetNames) {
            val existingChildEntry = cachedTreeChild(context, parent, displayName)
                ?.takeUnless(QueriedTreeChild::isDirectory)
                ?.toStoredEntry()
            if (sourceEntry.name.endsWith(METADATA_SUFFIX)) {
                (targetEntry ?: existingChildEntry)
                    ?.let { existingEntry ->
                        NPLogger.d(TAG, "迁移复用目标 SAF metadata: ${existingEntry.name}")
                        return StoredWriteResult(entry = existingEntry, createdNew = false)
                    }
            }
            targetEntry
                ?.takeIf { existingEntry -> isEquivalentMigrationTarget(sourceEntry, existingEntry) }
                ?.let { existingEntry ->
                    NPLogger.d(TAG, "迁移复用目标 SAF 文件: ${existingEntry.name}")
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            existingChildEntry
                ?.takeIf { existingEntry -> isEquivalentMigrationTarget(sourceEntry, existingEntry) }
                ?.let { existingEntry ->
                    NPLogger.d(TAG, "迁移复用目标 SAF 文件: ${existingEntry.name}")
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            val reservedName = reserveUniqueTreeChildName(context, parent, displayName)
            return StoredWriteResult(
                entry = plannedStoredEntry(reservedName),
                createdNew = true
            )
        }
        rememberTreeChildName(parent, displayName, isReservation = true)
        return StoredWriteResult(
            entry = plannedStoredEntry(displayName),
            createdNew = true
        )
    }

    private fun isEquivalentMigrationTarget(sourceEntry: StoredEntry, targetEntry: StoredEntry): Boolean {
        return sourceEntry.reference.isNotBlank() && sourceEntry.reference == targetEntry.reference
    }

    private fun plannedStoredEntry(displayName: String): StoredEntry {
        return StoredEntry(
            name = displayName,
            reference = "",
            mediaUri = "",
            localFilePath = null,
            sizeBytes = 0L,
            lastModifiedMs = 0L,
            isDirectory = false
        )
    }

    private fun writeSubdirectoryBytesBlocking(
        context: Context,
        subdirectory: String,
        displayName: String,
        bytes: ByteArray,
        mimeType: String
    ): StoredEntry? {
        val storedEntry = when (val root = resolveRootBlocking(context)) {
            is RootHandle.FileRoot -> {
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                ensureManagedMediaScanIsolation(subdirectory, dir)
                val target = File(dir, displayName)
                target.outputStream().use { it.write(bytes) }
                val verifiedSize = verifyFileCommittedLength(
                    target = target,
                    expectedSizeBytes = bytes.size.toLong(),
                    description = displayName
                )
                target.toStoredEntry().copy(sizeBytes = verifiedSize)
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(context, root.tree, subdirectory) ?: return null
                ensureManagedMediaScanIsolation(context, subdirectory, directory)
                val target = createRootFile(context, directory, displayName, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("无法写入目录文件: $displayName")
                verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = displayName,
                    expectedSizeBytes = bytes.size.toLong(),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                ).also { rememberTreeChild(directory, it) }
            }
        }
        storedEntry?.let { entry ->
            val bucket = when (subdirectory) {
                COVER_SUBDIRECTORY -> SnapshotEntryBucket.COVER
                LYRIC_SUBDIRECTORY -> SnapshotEntryBucket.LYRIC
                else -> null
            }
            if (bucket == null || !updateSnapshotCacheAfterStoredEntryWrite(context, entry, bucket)) {
                invalidateSnapshotCache(context)
            }
        }
        return storedEntry
    }

    private fun writeSubdirectoryFileBlocking(
        context: Context,
        subdirectory: String,
        displayName: String,
        sourceFile: File,
        mimeType: String
    ): StoredEntry? {
        if (!sourceFile.exists()) {
            return null
        }
        sourceFile.inputStream().use { input ->
            return writeSubdirectoryStream(
                context = context,
                root = resolveRootBlocking(context),
                subdirectory = subdirectory,
                displayName = displayName,
                mimeType = mimeType,
                input = input,
                invalidateSnapshot = false
            ).also { entry ->
                val bucket = when (subdirectory) {
                    COVER_SUBDIRECTORY -> SnapshotEntryBucket.COVER
                    LYRIC_SUBDIRECTORY -> SnapshotEntryBucket.LYRIC
                    else -> null
                }
                if (bucket == null || !updateSnapshotCacheAfterStoredEntryWrite(context, entry, bucket)) {
                    invalidateSnapshotCache(context)
                }
            }
        }
    }

    private fun writeSubdirectoryStream(
        context: Context,
        root: RootHandle,
        subdirectory: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
        invalidateSnapshot: Boolean = true
    ): StoredEntry {
        val storedEntry = when (root) {
            is RootHandle.FileRoot -> {
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                ensureManagedMediaScanIsolation(subdirectory, dir)
                val target = File(dir, displayName)
                var copiedBytes = 0L
                target.outputStream().use { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                }
                val verifiedSize = verifyFileCommittedLength(
                    target = target,
                    expectedSizeBytes = copiedBytes,
                    description = displayName
                )
                target.toStoredEntry().copy(sizeBytes = verifiedSize)
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(context, root.tree, subdirectory)
                    ?: throw IOException("无法创建目录: $subdirectory")
                ensureManagedMediaScanIsolation(context, subdirectory, directory)
                val target = createRootFile(context, directory, displayName, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                } ?: throw IOException("无法写入目录文件: $displayName")
                val entry = verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = displayName,
                    expectedSizeBytes = copiedBytes.coerceAtLeast(0L),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                )
                rememberTreeChild(directory, entry)
                entry
            }
        }
        if (invalidateSnapshot) {
            invalidateSnapshotCache(context)
        }
        return storedEntry
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
        return when (root) {
            is RootHandle.FileRoot -> {
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                ensureManagedMediaScanIsolation(subdirectory, dir)
                val target = resolveFileMigrationTarget(
                    parent = dir,
                    displayName = displayName,
                    sourceEntry = sourceEntry,
                    targetNames = targetNames,
                    targetEntry = targetEntry
                )
                if (!target.createdNew) {
                    return target
                }
                val targetFile = File(dir, target.entry.name)
                var copiedBytes = 0L
                targetFile.outputStream().use { output ->
                    copiedBytes = copyStreamWithProgress(input, output, onProgress)
                }
                val verifiedSize = verifyFileCommittedLength(
                    target = targetFile,
                    expectedSizeBytes = copiedBytes,
                    description = target.entry.name
                )
                StoredWriteResult(
                    entry = targetFile.toStoredEntry().copy(sizeBytes = verifiedSize),
                    createdNew = true
                )
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(context, root.tree, subdirectory)
                    ?: throw IOException("无法创建目录: $subdirectory")
                ensureManagedMediaScanIsolation(context, subdirectory, directory)
                val targetPlan = resolveTreeMigrationTarget(
                    context = context,
                    parent = directory,
                    displayName = displayName,
                    sourceEntry = sourceEntry,
                    targetNames = targetNames,
                    targetEntry = targetEntry
                )
                if (!targetPlan.createdNew) {
                    return targetPlan
                }
                val target = createRootFile(context, directory, targetPlan.entry.name, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = copyStreamWithProgress(input, output, onProgress)
                } ?: throw IOException("无法写入目录文件: ${targetPlan.entry.name}")
                val entry = verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = targetPlan.entry.name,
                    expectedSizeBytes = copiedBytes.coerceAtLeast(0L),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                )
                rememberTreeChild(directory, entry)
                StoredWriteResult(
                    entry = entry,
                    createdNew = true
                )
            }
        }
    }

    private fun writeRootText(
        context: Context,
        root: RootHandle,
        displayName: String,
        content: String,
        invalidateSnapshot: Boolean = true
    ): StoredEntry? {
        val storedEntry = when (root) {
            is RootHandle.FileRoot -> {
                val target = File(root.dir, displayName)
                val encoded = content.toByteArray(Charsets.UTF_8)
                target.writeBytes(encoded)
                val verifiedSize = verifyFileCommittedLength(
                    target = target,
                    expectedSizeBytes = encoded.size.toLong(),
                    description = displayName
                )
                target.toStoredEntry().copy(sizeBytes = verifiedSize)
            }

            is RootHandle.TreeRoot -> {
                val target = createRootFile(context, root.tree, displayName, "application/json", replace = true)
                val writtenAtMs = System.currentTimeMillis()
                val encoded = content.toByteArray(Charsets.UTF_8)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(encoded)
                } ?: throw IOException("无法写入元数据文件: $displayName")
                val entry = verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = displayName,
                    expectedSizeBytes = encoded.size.toLong(),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                )
                rememberTreeChild(root.tree, entry)
                entry
            }
        }
        if (invalidateSnapshot) {
            invalidateSnapshotCache(context)
        }
        return storedEntry
    }

    private fun findOrCreateDirectory(context: Context, parent: DocumentFile, displayName: String): DocumentFile? {
        val cacheKey = "${parent.uri}|$displayName"
        treeSubdirectoryCache[cacheKey]
            ?.takeIf { it.isDirectory }
            ?.let { return it }
        val lock = treeDirectoryLocks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            treeSubdirectoryCache[cacheKey]
                ?.takeIf { it.isDirectory }
                ?.let { return@synchronized it }
            findCachedManagedSubdirectory(
                context = context,
                parent = parent,
                displayName = displayName,
                maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
            )
                ?.also { treeSubdirectoryCache[cacheKey] = it }
                ?.let { return@synchronized it }
            val createdDirectory = parent.createDirectory(displayName)
                ?: findCachedManagedSubdirectory(
                    context = context,
                    parent = parent,
                    displayName = displayName,
                    maxCacheAgeMs = 0L
                )
            createdDirectory?.also {
                treeSubdirectoryCache[cacheKey] = it
                val createdName = it.resolvedTreeStoredName(displayName)
                updateRememberedTreeChild(
                    parent = parent,
                    childName = createdName,
                    documentUri = it.uri,
                    sizeBytes = 0L,
                    lastModifiedMs = System.currentTimeMillis(),
                    isDirectory = true
                )
            }
        }
    }

    private fun clearTreeDirectoryCache() {
        treeSubdirectoryCache.clear()
        treeChildrenNameCache.clear()
        treeChildrenEntryCache.clear()
        fileChildrenNameCache.clear()
        childNameReservationLocks.clear()
        ensuredNoMediaMarkers.clear()
        cachedTreeRoot = null
    }

    private fun findCachedManagedSubdirectory(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        maxCacheAgeMs: Long
    ): DocumentFile? {
        return cachedTreeChildren(
            context = context,
            parent = parent,
            maxCacheAgeMs = maxCacheAgeMs
        )
            .filter(QueriedTreeChild::isDirectory)
            .filter { child -> matchesManagedSubdirectoryName(child.name, displayName) }
            .sortedWith(
                compareBy<QueriedTreeChild>(
                    { if (it.name == displayName) 0 else 1 },
                    { managedSubdirectoryOrdinal(it.name, displayName) },
                    QueriedTreeChild::name
                )
            )
            .firstNotNullOfOrNull { child -> child.toDocumentFile(context) }
    }

    // 只给封面目录补 .nomedia，避免把用户手选的整个下载根目录从媒体库里隐藏
    internal fun shouldCreateNoMediaMarker(subdirectory: String): Boolean {
        return subdirectory == COVER_SUBDIRECTORY
    }

    internal fun matchesManagedSubdirectoryName(actualName: String, desiredName: String): Boolean {
        if (actualName == desiredName) {
            return true
        }
        if (!actualName.startsWith("$desiredName (") || !actualName.endsWith(")")) {
            return false
        }
        val suffix = actualName.removePrefix("$desiredName (").removeSuffix(")")
        return suffix.isNotBlank() && suffix.all(Char::isDigit)
    }

    private fun managedSubdirectoryOrdinal(actualName: String, desiredName: String): Int {
        if (actualName == desiredName) {
            return 0
        }
        return actualName.removePrefix("$desiredName (")
            .removeSuffix(")")
            .toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private data class NamedDirectoryRoot(
        val name: String,
        val root: RootHandle
    )

    private fun findSubdirectories(
        context: Context,
        root: RootHandle,
        desiredName: String,
        canonicalLast: Boolean = false
    ): List<RootHandle> {
        val comparator = if (canonicalLast) {
            compareBy<NamedDirectoryRoot>(
                { if (it.name == desiredName) 1 else 0 },
                { managedSubdirectoryOrdinal(it.name, desiredName) },
                { it.name }
            )
        } else {
            compareBy<NamedDirectoryRoot>(
                { if (it.name == desiredName) 0 else 1 },
                { managedSubdirectoryOrdinal(it.name, desiredName) },
                { it.name }
            )
        }
        return listDirectoryChildren(context, root)
            .filter { matchesManagedSubdirectoryName(it.name, desiredName) }
            .sortedWith(comparator)
            .map(NamedDirectoryRoot::root)
    }

    private fun listDirectoryChildren(context: Context, root: RootHandle): List<NamedDirectoryRoot> {
        return when (root) {
            is RootHandle.FileRoot -> root.dir.listFiles()
                ?.filter(File::isDirectory)
                ?.map { file -> NamedDirectoryRoot(name = file.name, root = RootHandle.FileRoot(file)) }
                .orEmpty()

            is RootHandle.TreeRoot -> {
                cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                )
                    .filter(QueriedTreeChild::isDirectory)
                    .mapNotNull { child ->
                        child.toDocumentFile(context)?.let { file ->
                            NamedDirectoryRoot(name = child.name, root = RootHandle.TreeRoot(file))
                        }
                    }
            }
        }
    }

    private fun ensureManagedMediaScanIsolation(subdirectory: String, directory: File) {
        if (!shouldCreateNoMediaMarker(subdirectory)) return
        runCatching {
            ensureNoMediaMarker(directory)
        }.onFailure {
            NPLogger.w(TAG, "创建封面目录 .nomedia 失败: ${it.message}")
        }
    }

    private fun ensureManagedMediaScanIsolation(
        context: Context,
        subdirectory: String,
        directory: DocumentFile
    ) {
        if (!shouldCreateNoMediaMarker(subdirectory)) return
        runCatching {
            ensureNoMediaMarker(context, directory)
        }.onFailure {
            NPLogger.w(TAG, "创建封面目录 .nomedia 失败: ${it.message}")
        }
    }

    private fun ensureNoMediaMarker(directory: File) {
        val cacheKey = directory.absolutePath
        if (ensuredNoMediaMarkers[cacheKey] == true) return
        val marker = File(directory, NO_MEDIA_FILE_NAME)
        if (marker.exists()) {
            ensuredNoMediaMarkers[cacheKey] = true
            return
        }
        if (!marker.createNewFile()) {
            throw IOException("无法创建 $NO_MEDIA_FILE_NAME")
        }
        ensuredNoMediaMarkers[cacheKey] = true
    }

    private fun ensureNoMediaMarker(context: Context, directory: DocumentFile) {
        val cacheKey = directory.uri.toString()
        if (ensuredNoMediaMarkers[cacheKey] == true) return
        if (cachedTreeChild(context, directory, NO_MEDIA_FILE_NAME) != null) {
            ensuredNoMediaMarkers[cacheKey] = true
            return
        }
        val marker = directory.createFile("application/octet-stream", NO_MEDIA_FILE_NAME)
            ?: throw IOException("无法创建 $NO_MEDIA_FILE_NAME")
        val storedName = marker.resolvedTreeStoredName(NO_MEDIA_FILE_NAME)
        updateRememberedTreeChild(
            parent = directory,
            childName = storedName,
            documentUri = marker.uri,
            sizeBytes = 0L,
            lastModifiedMs = System.currentTimeMillis(),
            isDirectory = false
        )
        ensuredNoMediaMarkers[cacheKey] = true
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
        return if (entry.subdirectory == null && entry.entry.name.endsWith(METADATA_SUFFIX)) {
            "application/json"
        } else {
            mimeTypeFromName(entry.entry.name, null)
        }
    }

    private fun migrationCopyParallelism(sourceRoot: RootHandle, targetRoot: RootHandle): Int {
        return if (sourceRoot is RootHandle.TreeRoot || targetRoot is RootHandle.TreeRoot) {
            MIGRATION_TREE_COPY_PARALLELISM
        } else {
            MIGRATION_COPY_PARALLELISM
        }
    }

    private fun migrationRewriteParallelism(targetRoot: RootHandle): Int {
        return if (targetRoot is RootHandle.TreeRoot) {
            MIGRATION_TREE_REWRITE_PARALLELISM
        } else {
            MIGRATION_REWRITE_PARALLELISM
        }
    }

    private fun migrationDeleteParallelism(root: RootHandle): Int {
        return if (root is RootHandle.TreeRoot) {
            MIGRATION_TREE_DELETE_PARALLELISM
        } else {
            MIGRATION_DELETE_PARALLELISM
        }
    }

    private suspend fun <T> retryManagedMigrationWrite(
        reference: String,
        block: () -> T
    ): T? {
        repeat(MIGRATION_IO_MAX_ATTEMPTS) { attempt ->
            val result = runCatching(block).onFailure { error ->
                NPLogger.w(
                    TAG,
                    "迁移下载文件失败: $reference, attempt=${attempt + 1}/$MIGRATION_IO_MAX_ATTEMPTS, ${error.message}"
                )
            }.getOrNull()
            if (result != null) {
                return result
            }
            if (attempt < MIGRATION_IO_MAX_ATTEMPTS - 1) {
                delay(MIGRATION_IO_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return null
    }

    private fun normalizeDirectoryUri(uriString: String?): String? {
        return uriString?.trim()?.takeIf(String::isNotBlank)
    }

    private fun normalizeConfiguredDirectoryUri(uriString: String?): String? {
        val normalized = normalizeDirectoryUri(uriString)
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val authority = extractDirectoryAuthority(normalized).takeIf(String::isNotBlank) ?: return normalized
        extractEncodedDirectoryDocumentId(normalized, "/tree/")
            ?.let { encodedDocumentId ->
                return "content://$authority/tree/$encodedDocumentId"
            }
        extractEncodedDirectoryDocumentId(normalized, "/document/")
            ?.let { encodedDocumentId ->
                return "content://$authority/tree/$encodedDocumentId"
            }
        return normalized
    }

    private fun extractDirectoryDocumentId(uriString: String, marker: String): String? {
        val encodedId = extractEncodedDirectoryDocumentId(uriString, marker) ?: return null
        return runCatching {
            URLDecoder.decode(encodedId, StandardCharsets.UTF_8.name())
        }.getOrDefault(encodedId)
    }

    private fun extractEncodedDirectoryDocumentId(uriString: String, marker: String): String? {
        val markerIndex = uriString.indexOf(marker)
        if (markerIndex < 0) return null
        val startIndex = markerIndex + marker.length
        val endIndex = uriString.indexOfAny(charArrayOf('/', '?', '#'), startIndex)
            .takeIf { it >= 0 }
            ?: uriString.length
        return uriString.substring(startIndex, endIndex).takeIf { it.isNotBlank() }
    }

    private fun extractDirectoryAuthority(uriString: String): String {
        val schemeSeparatorIndex = uriString.indexOf("://")
        if (schemeSeparatorIndex < 0) return ""
        val authorityStartIndex = schemeSeparatorIndex + 3
        val authorityEndIndex = uriString.indexOfAny(charArrayOf('/', '?', '#'), authorityStartIndex)
            .takeIf { it >= 0 }
            ?: uriString.length
        return uriString.substring(authorityStartIndex, authorityEndIndex)
    }

    private fun resolveCachedTreeRoot(normalizedUri: String, identity: String): RootHandle.TreeRoot? {
        val now = System.currentTimeMillis()
        val cachedRoot = cachedTreeRoot
            ?.takeIf { it.identity == identity && it.normalizedUri == normalizedUri }
            ?: return null
        if (now - cachedRoot.validatedAtMs <= TREE_ROOT_CACHE_VALIDATE_INTERVAL_MS) {
            return cachedRoot.root
        }
        return cachedRoot.root
            .takeIf { it.tree.exists() && it.tree.isDirectory }
            ?.also {
                cachedTreeRoot = cachedRoot.copy(validatedAtMs = now)
            }
    }

    private fun rememberCachedTreeRoot(
        normalizedUri: String,
        identity: String,
        root: RootHandle.TreeRoot
    ): RootHandle.TreeRoot {
        cachedTreeRoot = CachedTreeRoot(
            identity = identity,
            normalizedUri = normalizedUri,
            root = root,
            validatedAtMs = System.currentTimeMillis()
        )
        return root
    }

    private fun resolveTreeRootBlocking(context: Context, directoryUriString: String?): RootHandle.TreeRoot? {
        val normalizedUri = normalizeConfiguredDirectoryUri(directoryUriString) ?: return null
        val identity = directoryIdentity(normalizedUri) ?: normalizedUri
        resolveCachedTreeRoot(normalizedUri, identity)?.let { return it }

        val lock = treeDirectoryLocks.computeIfAbsent("tree_root:$identity") { Any() }
        return synchronized(lock) {
            resolveCachedTreeRoot(normalizedUri, identity)?.let { return@synchronized it }
            val treeUri = runCatching { normalizedUri.toUri() }.getOrNull() ?: return@synchronized null
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@synchronized null
            tree.takeIf { it.exists() && it.isDirectory }
                ?.let { rememberCachedTreeRoot(normalizedUri, identity, RootHandle.TreeRoot(it)) }
        }
    }

    private fun createDefaultRoot(context: Context): RootHandle.FileRoot {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val dir = File(baseDir, ROOT_DIR_NAME).apply { mkdirs() }
        return RootHandle.FileRoot(dir)
    }

    internal fun createUniqueName(existingNames: Set<String>, desiredName: String): String {
        if (desiredName !in existingNames) return desiredName
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var index = 1
        while (index < 10_000) {
            val candidate = if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext"
            if (candidate !in existingNames) {
                return candidate
            }
            index++
        }
        return desiredName
    }

    private fun readTextInternal(context: Context, reference: String): String? {
        return when {
            reference.startsWith("/") -> File(reference).takeIf(File::exists)?.readText(Charsets.UTF_8)
            else -> runCatching {
                context.contentResolver.openInputStream(reference.toUri())
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrElse { error ->
                if (isMissingManagedDocumentFailure(error)) {
                    null
                } else {
                    throw error
                }
            }
        }
    }

    private fun existsInternal(context: Context, reference: String?): Boolean {
        if (reference.isNullOrBlank()) return false
        return when {
            reference.startsWith("/") -> File(reference).exists()
            else -> {
                val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
                resolveDocumentFile(context, uri)?.exists()
                    ?: runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
            }
        }
    }

    private fun buildManagedDeletePolicy(
        context: Context,
        allowedRoot: RootHandle? = null,
        trustedReferences: Set<String>? = null
    ): ManagedDeletePolicy {
        val roots = listOf(allowedRoot ?: resolveRootBlocking(context))
        val snapshotTrustedReferences = trustedReferences
            ?: if (allowedRoot == null) {
                cachedDownloadLibrarySnapshot(context)?.knownReferences.orEmpty()
            } else {
                emptySet()
            }
        return ManagedDeletePolicy(
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
        val normalizedReference = reference.takeIf(String::isNotBlank) ?: return false
        if (normalizedReference in trustedReferences) {
            return true
        }
        if (normalizedReference.startsWith("/")) {
            return managedFileRoots.any { rootPath ->
                isFileReferenceUnderManagedRoot(normalizedReference, rootPath)
            }
        }
        return managedTreeRoots.any { rootUri ->
            isDocumentReferenceUnderManagedTree(normalizedReference, rootUri)
        }
    }

    internal fun isFileReferenceUnderManagedRoot(reference: String, managedRootPath: String): Boolean {
        val root = runCatching { File(managedRootPath).canonicalFile }.getOrNull() ?: return false
        val target = runCatching { File(reference).canonicalFile }.getOrNull() ?: return false
        if (target == root) {
            return false
        }
        return generateSequence(target.parentFile) { file -> file.parentFile }
            .any { parent -> parent == root }
    }

    internal fun isDocumentReferenceUnderManagedTree(reference: String, managedTreeUri: String): Boolean {
        val referenceAuthority = extractDirectoryAuthority(reference).takeIf(String::isNotBlank) ?: return false
        val rootAuthority = extractDirectoryAuthority(managedTreeUri).takeIf(String::isNotBlank) ?: return false
        if (referenceAuthority != rootAuthority) {
            return false
        }
        val rootDocumentId = extractDirectoryDocumentId(managedTreeUri, "/tree/")
            ?: extractDirectoryDocumentId(managedTreeUri, "/document/")
            ?: return false
        val referenceDocumentId = extractDirectoryDocumentId(reference, "/document/")
            ?: return false
        return isDocumentIdInsideManagedRoot(
            documentId = referenceDocumentId,
            rootDocumentId = rootDocumentId
        )
    }

    internal fun isDocumentIdInsideManagedRoot(documentId: String, rootDocumentId: String): Boolean {
        if (documentId == rootDocumentId) {
            return false
        }
        if (rootDocumentId.endsWith(":")) {
            return documentId.startsWith(rootDocumentId)
        }
        val prefix = if (rootDocumentId.endsWith("/")) rootDocumentId else "$rootDocumentId/"
        return documentId.startsWith(prefix)
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
        val normalizedReferences = references
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinct()
        if (normalizedReferences.isEmpty()) {
            return emptySet()
        }
        val deletePolicy = buildManagedDeletePolicy(context, allowedRoot)
        val deletedReferences = linkedSetOf<String>()
        normalizedReferences.forEach { reference ->
            val deleted = deleteReferenceBlocking(context, reference, deletePolicy)
            if (deleted) {
                deletedReferences += reference
            }
        }
        if (invalidateSnapshot) {
            forgetDeletedReferencesFromCaches(deletedReferences)
            val hasUnconfirmedDeletes = deletedReferences.size != normalizedReferences.size
            if (hasUnconfirmedDeletes) {
                invalidateSnapshotCache(context)
            } else if (deletedReferences.isNotEmpty() && !updateSnapshotCacheAfterDelete(context, deletedReferences)) {
                invalidateSnapshotCache(context)
            }
        }
        return deletedReferences
    }

    private suspend fun deleteReferencesInternalConcurrently(
        context: Context,
        references: Collection<String?>,
        allowedRoot: RootHandle? = null,
        invalidateSnapshot: Boolean
    ): Set<String> {
        val normalizedReferences = references
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinct()
        if (normalizedReferences.isEmpty()) {
            return emptySet()
        }
        val startedAtMs = System.currentTimeMillis()
        val deleteLimiter = Semaphore(SAF_REFERENCE_DELETE_PARALLELISM)
        val deletePolicy = buildManagedDeletePolicy(context, allowedRoot)
        val deletedReferences = coroutineScope {
            normalizedReferences.map { reference ->
                async(Dispatchers.IO) {
                    deleteLimiter.withPermit {
                        reference.takeIf { deleteReferenceBlocking(context, reference, deletePolicy) }
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }
        if (invalidateSnapshot) {
            forgetDeletedReferencesFromCaches(deletedReferences)
            val hasUnconfirmedDeletes = deletedReferences.size != normalizedReferences.size
            if (hasUnconfirmedDeletes) {
                invalidateSnapshotCache(context)
            } else if (deletedReferences.isNotEmpty() && !updateSnapshotCacheAfterDelete(context, deletedReferences)) {
                invalidateSnapshotCache(context)
            }
        }
        NPLogger.d(
            TAG,
            "批量删除引用完成: requested=${normalizedReferences.size}, deleted=${deletedReferences.size}, costMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return deletedReferences
    }

    private fun forgetDeletedReferencesFromCaches(deletedReferences: Set<String>) {
        if (deletedReferences.isEmpty()) return
        deletedReferences
            .filter { reference -> reference.startsWith("/") }
            .forEach { reference ->
                val file = File(reference)
                file.parentFile?.let { parent -> forgetFileChildName(parent, file.name) }
            }

        val deletedContentReferences = deletedReferences
            .filterNot { reference -> reference.startsWith("/") }
            .toSet()
        if (deletedContentReferences.isEmpty()) return

        treeChildrenEntryCache.forEach { (cacheKey, cachedChildren) ->
            cachedChildren.childrenByName.values
                .filter { child -> child.documentUri.toString() in deletedContentReferences }
                .map(QueriedTreeChild::name)
                .forEach { childName -> forgetTreeChildName(cacheKey, childName) }
        }
        treeSubdirectoryCache.forEach { (cacheKey, directory) ->
            if (directory.uri.toString() in deletedContentReferences) {
                treeSubdirectoryCache.remove(cacheKey, directory)
            }
        }
        deletedContentReferences.forEach { reference ->
            ensuredNoMediaMarkers.remove(reference)
        }
    }

    private fun deleteReferenceBlocking(
        context: Context,
        reference: String,
        deletePolicy: ManagedDeletePolicy
    ): Boolean {
        if (
            !isReferenceAllowedForManagedDelete(
                reference = reference,
                trustedReferences = deletePolicy.trustedReferences,
                managedFileRoots = deletePolicy.managedFileRoots,
                managedTreeRoots = deletePolicy.managedTreeRoots
            )
        ) {
            NPLogger.w(TAG, "拒绝删除非托管下载引用: $reference")
            return false
        }
        return when {
            reference.startsWith("/") -> {
                val file = File(reference)
                !file.exists() || file.delete()
            }

            else -> {
                val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
                deleteContentReference(
                    context = context,
                    reference = reference,
                    uri = uri
                )
            }
        }
    }

    private fun resolveDocumentFile(context: Context, uri: Uri): DocumentFile? {
        return DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
    }

    private fun deleteContentReference(
        context: Context,
        reference: String,
        uri: Uri
    ): Boolean {
        repeat(SAF_DELETE_MAX_ATTEMPTS) { attempt ->
            if (deleteContentReferenceOnce(context, reference, uri)) {
                return true
            }
            if (attempt < SAF_DELETE_MAX_ATTEMPTS - 1) {
                runCatching {
                    Thread.sleep(SAF_DELETE_RETRY_DELAY_MS * (attempt + 1L))
                }
            }
        }
        return false
    }

    private fun deleteContentReferenceOnce(
        context: Context,
        reference: String,
        uri: Uri
    ): Boolean {
        val deletedByContract = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrElse { error ->
            if (isMissingManagedDocumentFailure(error)) {
                return true
            }
            false
        }
        if (deletedByContract) {
            return true
        }

        val deletedByDocumentFile = runCatching {
            resolveDocumentFile(context, uri)?.delete() ?: false
        }.getOrElse { error ->
            if (isMissingManagedDocumentFailure(error)) {
                return true
            }
            false
        }
        return deletedByDocumentFile
    }

    internal fun isMissingManagedDocumentFailure(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            when (cause) {
                is FileNotFoundException -> true
                is IllegalArgumentException -> {
                    val message = cause.message.orEmpty()
                    message.contains("Missing file", ignoreCase = true) ||
                        message.contains("Failed to determine if", ignoreCase = true)
                }

                else -> false
            }
        }
    }

    internal fun mimeTypeFromName(name: String, fallback: String?): String {
        val normalizedFallback = fallback?.takeIf { it.isNotBlank() }
        if (normalizedFallback != null) return normalizedFallback
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "eac3" -> "audio/eac3"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "lrc" -> "application/octet-stream"
            "txt", "json" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun List<StoredEntry>.toJsonArray(): JSONArray {
        return JSONArray().also { jsonArray ->
            forEach { entry -> jsonArray.put(entry.toJson()) }
        }
    }

    private fun StoredEntry.toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("reference", reference)
            put("mediaUri", mediaUri)
            put("localFilePath", localFilePath)
            put("sizeBytes", sizeBytes)
            put("lastModifiedMs", lastModifiedMs)
            put("isDirectory", isDirectory)
        }
    }

    private fun JSONArray?.toStoredEntries(): List<StoredEntry> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optJSONObject(index)?.toStoredEntry()?.let(::add)
            }
        }
    }

    private fun JSONObject.toStoredEntry(): StoredEntry? {
        val name = optString("name").takeIf(String::isNotBlank) ?: return null
        val reference = optString("reference").takeIf(String::isNotBlank) ?: return null
        val mediaUri = optString("mediaUri").takeIf(String::isNotBlank) ?: reference
        return StoredEntry(
            name = name,
            reference = reference,
            mediaUri = mediaUri,
            localFilePath = optString("localFilePath").takeIf(String::isNotBlank),
            sizeBytes = optLong("sizeBytes"),
            lastModifiedMs = optLong("lastModifiedMs"),
            isDirectory = optBoolean("isDirectory")
        )
    }

    private fun DownloadedAudioMetadata.toJson(): JSONObject {
        return JSONObject().apply {
            put("stableKey", stableKey)
            put("songId", songId)
            put("identityAlbum", identityAlbum)
            put("name", name)
            put("artist", artist)
            put("coverUrl", coverUrl)
            put("matchedLyric", matchedLyric)
            put("matchedTranslatedLyric", matchedTranslatedLyric)
            put("matchedLyricSource", matchedLyricSource)
            put("matchedSongId", matchedSongId)
            put("userLyricOffsetMs", userLyricOffsetMs)
            put("customCoverUrl", customCoverUrl)
            put("customName", customName)
            put("customArtist", customArtist)
            put("originalName", originalName)
            put("originalArtist", originalArtist)
            put("originalCoverUrl", originalCoverUrl)
            put("originalLyric", originalLyric)
            put("originalTranslatedLyric", originalTranslatedLyric)
            put("mediaUri", mediaUri)
            put("channelId", channelId)
            put("audioId", audioId)
            put("subAudioId", subAudioId)
            put("coverPath", coverPath)
            put("lyricPath", lyricPath)
            put("translatedLyricPath", translatedLyricPath)
            put("durationMs", durationMs)
            put("downloadFinalized", downloadFinalized)
        }
    }

    private fun SongItem.toWorkingResumeMetadataJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("artist", artist)
            put("album", album)
            put("albumId", albumId)
            put("durationMs", durationMs)
            put("coverUrl", coverUrl)
            put("mediaUri", mediaUri)
            put("matchedLyric", matchedLyric)
            put("matchedTranslatedLyric", matchedTranslatedLyric)
            put("matchedLyricSource", matchedLyricSource?.name)
            put("matchedSongId", matchedSongId)
            put("userLyricOffsetMs", userLyricOffsetMs)
            put("customCoverUrl", customCoverUrl)
            put("customName", customName)
            put("customArtist", customArtist)
            put("originalName", originalName)
            put("originalArtist", originalArtist)
            put("originalCoverUrl", originalCoverUrl)
            put("originalLyric", originalLyric)
            put("originalTranslatedLyric", originalTranslatedLyric)
            put("localFileName", localFileName)
            put("localFilePath", localFilePath)
            put("channelId", channelId)
            put("audioId", audioId)
            put("subAudioId", subAudioId)
            put("playlistContextId", playlistContextId)
            put("streamUrl", streamUrl)
            put(
                "neteaseArtists",
                JSONArray().also { artistsArray ->
                    neteaseArtists.orEmpty().forEach { artistSummary ->
                        artistsArray.put(
                            JSONObject().apply {
                                put("id", artistSummary.id)
                                put("name", artistSummary.name)
                            }
                        )
                    }
                }
            )
        }
    }

    private fun JSONObject.toWorkingResumeMetadataSong(): SongItem? {
        val id = optLong("id").takeIf { has("id") } ?: return null
        val name = optString("name").takeIf(String::isNotBlank) ?: return null
        val artist = optString("artist").takeIf(String::isNotBlank) ?: return null
        val album = optString("album").takeIf(String::isNotBlank) ?: return null
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = optLong("albumId"),
            durationMs = optLong("durationMs"),
            coverUrl = optString("coverUrl").takeIf { has("coverUrl") && !isNull("coverUrl") },
            mediaUri = optString("mediaUri").takeIf(String::isNotBlank),
            matchedLyric = optPresentString("matchedLyric"),
            matchedTranslatedLyric = optPresentString("matchedTranslatedLyric"),
            matchedLyricSource = optString("matchedLyricSource")
                .takeIf(String::isNotBlank)
                ?.let { value -> runCatching { MusicPlatform.valueOf(value) }.getOrNull() },
            matchedSongId = optString("matchedSongId").takeIf(String::isNotBlank),
            userLyricOffsetMs = optLong("userLyricOffsetMs"),
            customCoverUrl = optString("customCoverUrl").takeIf(String::isNotBlank),
            customName = optString("customName").takeIf(String::isNotBlank),
            customArtist = optString("customArtist").takeIf(String::isNotBlank),
            originalName = optString("originalName").takeIf(String::isNotBlank),
            originalArtist = optString("originalArtist").takeIf(String::isNotBlank),
            originalCoverUrl = optString("originalCoverUrl").takeIf(String::isNotBlank),
            originalLyric = optPresentString("originalLyric"),
            originalTranslatedLyric = optPresentString("originalTranslatedLyric"),
            localFileName = optString("localFileName").takeIf(String::isNotBlank),
            localFilePath = optString("localFilePath").takeIf(String::isNotBlank),
            channelId = optString("channelId").takeIf(String::isNotBlank),
            audioId = optString("audioId").takeIf(String::isNotBlank),
            subAudioId = optString("subAudioId").takeIf(String::isNotBlank),
            playlistContextId = optString("playlistContextId").takeIf(String::isNotBlank),
            streamUrl = optString("streamUrl").takeIf(String::isNotBlank),
            neteaseArtists = optJSONArray("neteaseArtists").toNeteaseArtistSummaries()
        )
    }

    internal fun parseWorkingResumeMetadataSong(rawJson: String): SongItem? {
        return runCatching {
            JSONObject(rawJson).toWorkingResumeMetadataSong()
        }.onFailure {
            NPLogger.w(TAG, "解析下载恢复元数据失败: ${it.message}")
        }.getOrNull()
    }

    private fun pendingDownloadQueueFile(context: Context): File {
        return File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
    }

    private fun cancelledDownloadKeysFile(context: Context): File {
        return File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
    }

    private fun mergePendingDownloadQueueEntries(
        existingEntries: List<PendingDownloadQueueEntry>,
        songs: List<SongItem>,
        nowMs: Long
    ): List<PendingDownloadQueueEntry> {
        val merged = linkedMapOf<String, PendingDownloadQueueEntry>()
        existingEntries
            .sortedBy(PendingDownloadQueueEntry::order)
            .forEach { entry ->
                merged.putIfAbsent(entry.stableKey, entry)
            }

        var nextOrder = (merged.values.maxOfOrNull(PendingDownloadQueueEntry::order) ?: -1) + 1
        songs.forEach { song ->
            val stableKey = song.stableKey()
            val existingEntry = merged[stableKey]
            merged[stableKey] = PendingDownloadQueueEntry(
                stableKey = stableKey,
                song = song,
                order = existingEntry?.order ?: nextOrder++,
                queuedAtMs = existingEntry?.queuedAtMs ?: nowMs
            )
        }

        return merged.values
            .sortedBy(PendingDownloadQueueEntry::order)
            .mapIndexed { index, entry -> entry.copy(order = index) }
    }

    private fun readPendingDownloadQueueFile(queueFile: File): List<PendingDownloadQueueEntry> {
        val rawPayload = runCatching {
            queueFile.takeIf(File::exists)
                ?.readText(Charsets.UTF_8)
                ?.takeIf(String::isNotBlank)
        }.onFailure {
            NPLogger.w(TAG, "读取未完成下载队列失败: ${it.message}")
        }.getOrNull() ?: return emptyList()

        return runCatching {
            parsePendingDownloadQueuePayload(rawPayload)
        }.onFailure {
            NPLogger.w(TAG, "解析未完成下载队列失败: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun writePendingDownloadQueueFile(
        queueFile: File,
        entries: List<PendingDownloadQueueEntry>,
        updatedAtMs: Long
    ) {
        if (entries.isEmpty()) {
            deletePendingDownloadQueueFile(queueFile)
            return
        }

        runCatching {
            writeTextAtomically(
                target = queueFile,
                content = serializePendingDownloadQueuePayload(entries, updatedAtMs)
            )
        }.onFailure {
            NPLogger.w(TAG, "写入未完成下载队列失败: ${it.message}")
        }
    }

    private fun deletePendingDownloadQueueFile(queueFile: File) {
        runCatching {
            if (queueFile.exists() && !queueFile.delete()) {
                throw IOException("delete failed: ${queueFile.name}")
            }
        }.onFailure {
            NPLogger.w(TAG, "删除未完成下载队列失败: ${it.message}")
        }
    }

    private fun listCancelledDownloadKeysFromFileLocked(keysFile: File): Set<String> {
        val rawPayload = runCatching {
            keysFile.takeIf(File::exists)
                ?.readText(Charsets.UTF_8)
                ?.takeIf(String::isNotBlank)
        }.onFailure {
            NPLogger.w(TAG, "读取已取消下载标记失败: ${it.message}")
        }.getOrNull() ?: return emptySet()

        return runCatching {
            parseCancelledDownloadKeysPayload(rawPayload)
        }.onFailure {
            NPLogger.w(TAG, "解析已取消下载标记失败: ${it.message}")
        }.getOrDefault(emptySet())
    }

    private fun writeCancelledDownloadKeysFile(
        keysFile: File,
        songKeys: Set<String>,
        updatedAtMs: Long
    ) {
        if (songKeys.isEmpty()) {
            deleteCancelledDownloadKeysFile(keysFile)
            return
        }

        runCatching {
            writeTextAtomically(
                target = keysFile,
                content = serializeCancelledDownloadKeysPayload(songKeys, updatedAtMs)
            )
        }.onFailure {
            NPLogger.w(TAG, "写入已取消下载标记失败: ${it.message}")
        }
    }

    private fun deleteCancelledDownloadKeysFile(keysFile: File) {
        runCatching {
            if (keysFile.exists() && !keysFile.delete()) {
                throw IOException("delete failed: ${keysFile.name}")
            }
        }.onFailure {
            NPLogger.w(TAG, "删除已取消下载标记失败: ${it.message}")
        }
    }

    internal fun serializePendingDownloadQueuePayload(
        entries: List<PendingDownloadQueueEntry>,
        updatedAtMs: Long
    ): String {
        return JSONObject().apply {
            put("version", PENDING_DOWNLOAD_QUEUE_VERSION)
            put("updatedAtMs", updatedAtMs)
            put(
                "entries",
                JSONArray().also { entriesArray ->
                    entries
                        .sortedBy(PendingDownloadQueueEntry::order)
                        .forEach { entry ->
                            entriesArray.put(entry.toPendingDownloadQueueJson())
                        }
                }
            )
        }.toString()
    }

    internal fun parsePendingDownloadQueuePayload(rawJson: String): List<PendingDownloadQueueEntry> {
        val root = JSONObject(rawJson)
        val entries = root.optJSONArray("entries") ?: return emptyList()
        val restoredEntries = mutableListOf<PendingDownloadQueueEntry>()
        for (index in 0 until entries.length()) {
            entries.optJSONObject(index)
                ?.toPendingDownloadQueueEntry()
                ?.let(restoredEntries::add)
        }
        return restoredEntries
            .sortedBy(PendingDownloadQueueEntry::order)
            .distinctBy(PendingDownloadQueueEntry::stableKey)
            .mapIndexed { index, entry -> entry.copy(order = index) }
    }

    internal fun serializeCancelledDownloadKeysPayload(
        songKeys: Set<String>,
        updatedAtMs: Long
    ): String {
        return JSONObject().apply {
            put("version", CANCELLED_DOWNLOAD_KEYS_VERSION)
            put("updatedAtMs", updatedAtMs)
            put(
                "keys",
                JSONArray().also { keysArray ->
                    songKeys
                        .filter(String::isNotBlank)
                        .sorted()
                        .forEach(keysArray::put)
                }
            )
        }.toString()
    }

    internal fun parseCancelledDownloadKeysPayload(rawJson: String): Set<String> {
        val root = JSONObject(rawJson)
        val keys = root.optJSONArray("keys") ?: return emptySet()
        return buildSet {
            for (index in 0 until keys.length()) {
                keys.optString(index)
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }

    private fun PendingDownloadQueueEntry.toPendingDownloadQueueJson(): JSONObject {
        return JSONObject().apply {
            put("stableKey", stableKey)
            put("order", order)
            put("queuedAtMs", queuedAtMs)
            put("song", song.toWorkingResumeMetadataJson())
        }
    }

    private fun JSONObject.toPendingDownloadQueueEntry(): PendingDownloadQueueEntry? {
        val song = optJSONObject("song")?.toWorkingResumeMetadataSong() ?: return null
        val stableKey = song.stableKey()
        return PendingDownloadQueueEntry(
            stableKey = stableKey,
            song = song,
            order = optInt("order", Int.MAX_VALUE),
            queuedAtMs = optLong("queuedAtMs").coerceAtLeast(0L)
        )
    }

    private fun writeTextAtomically(target: File, content: String) {
        val parent = target.parentFile
        parent?.mkdirs()
        val tempFile = File(
            parent ?: File("."),
            "${target.name}.tmp.${atomicFileWriteIdGenerator.incrementAndGet()}.${System.nanoTime()}"
        )
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            moveFileAtomically(tempFile, target)
        } catch (error: Exception) {
            runCatching {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            throw error
        }
    }

    private fun moveFileAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun JSONObject.toDownloadedAudioMetadata(): DownloadedAudioMetadata {
        return DownloadedAudioMetadata(
            stableKey = optString("stableKey").takeIf(String::isNotBlank),
            songId = optLong("songId").takeIf { it > 0L },
            identityAlbum = optString("identityAlbum").takeIf(String::isNotBlank),
            name = optString("name").takeIf(String::isNotBlank),
            artist = optString("artist").takeIf(String::isNotBlank),
            coverUrl = optString("coverUrl").takeIf(String::isNotBlank),
            matchedLyric = optPresentString("matchedLyric"),
            matchedTranslatedLyric = optPresentString("matchedTranslatedLyric"),
            matchedLyricSource = optString("matchedLyricSource").takeIf(String::isNotBlank),
            matchedSongId = optString("matchedSongId").takeIf(String::isNotBlank),
            userLyricOffsetMs = optLong("userLyricOffsetMs"),
            customCoverUrl = optString("customCoverUrl").takeIf(String::isNotBlank),
            customName = optString("customName").takeIf(String::isNotBlank),
            customArtist = optString("customArtist").takeIf(String::isNotBlank),
            originalName = optString("originalName").takeIf(String::isNotBlank),
            originalArtist = optString("originalArtist").takeIf(String::isNotBlank),
            originalCoverUrl = optString("originalCoverUrl").takeIf(String::isNotBlank),
            originalLyric = optPresentString("originalLyric"),
            originalTranslatedLyric = optPresentString("originalTranslatedLyric"),
            mediaUri = optString("mediaUri").takeIf(String::isNotBlank),
            channelId = optString("channelId").takeIf(String::isNotBlank),
            audioId = optString("audioId").takeIf(String::isNotBlank),
            subAudioId = optString("subAudioId").takeIf(String::isNotBlank),
            coverPath = optString("coverPath").takeIf(String::isNotBlank),
            lyricPath = optString("lyricPath").takeIf(String::isNotBlank),
            translatedLyricPath = optString("translatedLyricPath").takeIf(String::isNotBlank),
            durationMs = optLong("durationMs"),
            downloadFinalized = optOptionalBoolean("downloadFinalized")
        )
    }

    internal fun parseDownloadedAudioMetadataJson(rawJson: String): DownloadedAudioMetadata? {
        return runCatching {
            JSONObject(rawJson).toDownloadedAudioMetadata()
        }.onFailure {
            NPLogger.w(TAG, "解析写回元数据失败: ${it.message}")
        }.getOrNull()
    }

    internal fun finalizedDownloadedMetadataJson(rawJson: String): String? {
        return runCatching {
            JSONObject(rawJson).apply {
                put("downloadFinalized", true)
            }.toString()
        }.onFailure {
            NPLogger.w(TAG, "恢复 finalized 元数据失败: ${it.message}")
        }.getOrNull()
    }

    internal fun isMetadataWriteVerified(
        expected: DownloadedAudioMetadata,
        actual: DownloadedAudioMetadata?
    ): Boolean {
        return actual == expected
    }

    private fun JSONObject.optPresentString(fieldName: String): String? {
        if (!has(fieldName) || isNull(fieldName)) {
            return null
        }
        return optString(fieldName)
    }

    private fun JSONObject.optOptionalBoolean(fieldName: String): Boolean? {
        if (!has(fieldName) || isNull(fieldName)) {
            return null
        }
        return optBoolean(fieldName)
    }

    private fun JSONArray?.toNeteaseArtistSummaries(): List<NeteaseArtistSummary> {
        if (this == null) {
            return emptyList()
        }
        return buildList(length()) {
            for (index in 0 until length()) {
                val root = optJSONObject(index) ?: continue
                val artistId = root.optLong("id").takeIf { root.has("id") } ?: continue
                val artistName = root.optString("name").takeIf(String::isNotBlank) ?: continue
                add(
                    NeteaseArtistSummary(
                        id = artistId,
                        name = artistName
                    )
                )
            }
        }
    }

    private fun updateSnapshotCacheAfterMetadataWrite(
        context: Context,
        metadataEntry: StoredEntry,
        metadata: DownloadedAudioMetadata
    ): Boolean {
        val appContext = context.applicationContext
        val cacheKey = buildSnapshotCacheKey(appContext)
        val currentSnapshot = snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?: restoreSnapshotCacheFromDisk(appContext, expectedKey = cacheKey)
            ?: return true
        val updatedSnapshot = applyMetadataWriteToSnapshot(
            snapshot = currentSnapshot,
            metadataEntry = metadataEntry,
            metadata = metadata
        )
        snapshotCache = SnapshotCache(key = cacheKey, snapshot = updatedSnapshot)
        scheduleSnapshotCachePersist(appContext, cacheKey)
        return true
    }

    private fun updateSnapshotCacheAfterStoredEntryWrite(
        context: Context,
        storedEntry: StoredEntry,
        bucket: SnapshotEntryBucket
    ): Boolean {
        val appContext = context.applicationContext
        val cacheKey = buildSnapshotCacheKey(appContext)
        val currentSnapshot = snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?: restoreSnapshotCacheFromDisk(appContext, expectedKey = cacheKey)
            ?: return false
        val updatedSnapshot = applyStoredEntryWriteToSnapshot(
            snapshot = currentSnapshot,
            storedEntry = storedEntry,
            bucket = bucket
        )
        snapshotCache = SnapshotCache(key = cacheKey, snapshot = updatedSnapshot)
        scheduleSnapshotCachePersist(appContext, cacheKey)
        return true
    }

    private fun updateSnapshotCacheAfterDelete(
        context: Context,
        deletedReferences: Set<String>
    ): Boolean {
        if (deletedReferences.isEmpty()) {
            return true
        }
        val appContext = context.applicationContext
        val cacheKey = buildSnapshotCacheKey(appContext)
        val currentSnapshot = snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?: restoreSnapshotCacheFromDisk(appContext, expectedKey = cacheKey)
            ?: return true
        val updatedSnapshot = applyReferenceDeletesToSnapshot(
            snapshot = currentSnapshot,
            references = deletedReferences
        )
        snapshotCache = SnapshotCache(key = cacheKey, snapshot = updatedSnapshot)
        scheduleSnapshotCachePersist(appContext, cacheKey)
        return true
    }

    private fun File.toStoredEntry(): StoredEntry {
        return StoredEntry(
            name = name,
            reference = absolutePath,
            mediaUri = Uri.fromFile(this).toString(),
            localFilePath = absolutePath,
            sizeBytes = length(),
            lastModifiedMs = lastModified(),
            isDirectory = isDirectory
        )
    }

    private fun QueriedTreeChild.toStoredEntry(): StoredEntry {
        return storedEntryFromTreeChild(
            name = name,
            documentReference = documentUri.toString(),
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            isDirectory = isDirectory
        )
    }

    internal fun storedEntryFromTreeChild(
        name: String,
        documentReference: String,
        sizeBytes: Long,
        lastModifiedMs: Long,
        isDirectory: Boolean
    ): StoredEntry {
        return StoredEntry(
            name = name,
            reference = documentReference,
            mediaUri = documentReference,
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            isDirectory = isDirectory
        )
    }

    private fun QueriedTreeChild.toDocumentFile(context: Context): DocumentFile? {
        return runCatching {
            DocumentFile.fromTreeUri(context, documentUri)
                ?: DocumentFile.fromSingleUri(context, documentUri)
        }.getOrNull()
    }

    private fun DocumentFile.toStoredEntry(
        knownName: String? = null,
        knownSizeBytes: Long? = null,
        knownLastModifiedMs: Long? = null,
        knownIsDirectory: Boolean? = null
    ): StoredEntry? {
        val displayName = knownName ?: name ?: return null
        return StoredEntry(
            name = displayName,
            reference = uri.toString(),
            mediaUri = uri.toString(),
            localFilePath = null,
            sizeBytes = knownSizeBytes ?: length(),
            lastModifiedMs = knownLastModifiedMs ?: lastModified(),
            isDirectory = knownIsDirectory ?: isDirectory
        )
    }
}
