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
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
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
    private const val PENDING_AUDIO_WRITE_MARKER = ".npdl_pending"
    private const val NO_MEDIA_FILE_NAME = ".nomedia"
    private const val SNAPSHOT_CACHE_FILE_NAME = "managed_download_snapshot_v1.json"
    private const val SNAPSHOT_CACHE_PERSIST_DEBOUNCE_MS = 1_200L
    private const val TREE_ROOT_CACHE_VALIDATE_INTERVAL_MS = 1_500L
    private const val TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS = 2_000L
    private const val MIGRATION_PROGRESS_EMIT_INTERVAL_MS = 80L
    @Suppress("SpellCheckingInspection")
    private const val METADATA_SUFFIX = ".npmeta.json"
    private const val MIGRATION_COPY_PARALLELISM = 8
    private const val MIGRATION_TREE_COPY_PARALLELISM = 2
    private const val MIGRATION_REWRITE_PARALLELISM = 4
    private const val MIGRATION_TREE_REWRITE_PARALLELISM = 2
    private const val MIGRATION_DELETE_PARALLELISM = 8
    private const val MIGRATION_IO_MAX_ATTEMPTS = 3
    private const val MIGRATION_IO_RETRY_DELAY_MS = 150L
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
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val treeDirectoryLocks = ConcurrentHashMap<String, Any>()
    private val treeSubdirectoryCache = ConcurrentHashMap<String, DocumentFile>()
    private val treeChildrenNameCache = ConcurrentHashMap<String, CachedTreeChildren>()
    private val ensuredNoMediaMarkers = ConcurrentHashMap<String, Boolean>()
    private val pendingAudioWriteIdGenerator = AtomicLong(0L)

    @Volatile
    private var snapshotPersistJob: Job? = null

    @Volatile
    private var startupRecoveryResult = StartupRecoveryResult()

    @Volatile
    private var cachedTreeRoot: CachedTreeRoot? = null

    private val _migrationProgressFlow = MutableStateFlow<MigrationProgress?>(null)
    val migrationProgressFlow: StateFlow<MigrationProgress?> = _migrationProgressFlow

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        createDefaultRoot(appContext)
        val stagingRecovery = cleanupStagingFiles(appContext)
        val pendingAudioRecovery = resolveStartupPendingAudioRecovery(appContext)
        startupRecoveryResult = StartupRecoveryResult(
            cleanedCount = stagingRecovery.cleanedCount + pendingAudioRecovery.cleanedCount,
            failedCount = stagingRecovery.failedCount + pendingAudioRecovery.failedCount
        )
        invalidateSnapshotCache()
    }

    private fun resolveStartupPendingAudioRecovery(context: Context): StartupRecoveryResult {
        val configuredUri = normalizeDirectoryUri(customDirectoryUri)
        return if (resolveTreeRootBlocking(context, configuredUri) != null) {
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

    internal data class StartupRecoveryResult(
        val cleanedCount: Int = 0,
        val failedCount: Int = 0
    ) {
        val hasRecoveredEntries: Boolean
            get() = cleanedCount > 0 || failedCount > 0
    }

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

    private data class CachedTreeChildren(
        val names: Set<String>,
        val refreshedAtMs: Long
    )

    private data class QueriedTreeChild(
        val name: String,
        val documentUri: Uri,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val isDirectory: Boolean
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

    private class MigrationTargetConflictException(message: String) : IOException(message)

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
        val durationMs: Long = 0L
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

            val copyLimiter = Semaphore(migrationCopyParallelism(sourceRoot, targetRoot))
            val copyResults = coroutineScope {
                entries.map { migrationEntry ->
                    async(Dispatchers.IO) {
                        copyLimiter.withPermit {
                            copyManagedMigrationEntry(
                                context = context,
                                targetRoot = targetRoot,
                                migrationEntry = migrationEntry,
                                progressTracker = progressTracker
                            )
                        }
                    }
                }.awaitAll()
            }
            val copiedEntries = copyResults.mapNotNull { it.copiedEntry }
            val skippedFiles = copyResults.count { it.copiedEntry == null }

            if (skippedFiles > 0) {
                rollbackMigratedEntries(context, copiedEntries)
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
                rollbackMigratedEntries(context, copiedEntries)
                return@withContext MigrationResult(
                    movedFiles = 0,
                    skippedFiles = rewriteFailedFiles
                )
            }

            val cleanupFailedFiles = cleanupMigratedEntries(
                context = context,
                copiedEntries = copiedEntries,
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
                            displayName = migrationEntry.entry.name,
                            mimeType = migrationMimeTypeFor(migrationEntry),
                            input = input,
                            onProgress = { copiedBytes ->
                                progressTracker?.onCopyProgress(migrationEntry, copiedBytes)
                            }
                        )
                    } else {
                        writeMigrationSubdirectoryStream(
                            context = context,
                            root = targetRoot,
                            subdirectory = migrationEntry.subdirectory,
                            displayName = migrationEntry.entry.name,
                            mimeType = migrationMimeTypeFor(migrationEntry),
                            input = input,
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

    fun createWorkingFile(context: Context, fileName: String): File {
        val stagingDir = File(context.cacheDir, DOWNLOAD_STAGING_DIR_NAME).apply { mkdirs() }
        val normalizedPrefix = fileName.substringBeforeLast('.', fileName)
            .ifBlank { "download" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(48)
            .ifBlank { "download" }
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
        val suffix = extension?.let { ".$it.download" } ?: ".download"
        return File.createTempFile("${normalizedPrefix}_", suffix, stagingDir)
    }

    internal fun consumeStartupRecoveryResult(): StartupRecoveryResult {
        val result = startupRecoveryResult
        startupRecoveryResult = StartupRecoveryResult()
        return result
    }

    fun cleanupStagingFiles(context: Context): StartupRecoveryResult {
        val stagingDir = File(context.cacheDir, DOWNLOAD_STAGING_DIR_NAME)
        val stagingEntries = stagingDir.listFiles().orEmpty()
        if (stagingEntries.isEmpty()) {
            return StartupRecoveryResult()
        }

        var cleanedCount = 0
        var failedCount = 0
        stagingEntries.forEach { entry ->
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
        if (cleanedCount > 0 || failedCount > 0) {
            NPLogger.d(TAG, "清理下载临时区完成: cleaned=$cleanedCount, failed=$failedCount")
        }
        return StartupRecoveryResult(
            cleanedCount = cleanedCount,
            failedCount = failedCount
        )
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
        progressTracker: MigrationProgressTracker? = null
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val cleanupLimiter = Semaphore(MIGRATION_DELETE_PARALLELISM)
        copiedEntries.map { migrationEntry ->
            async(Dispatchers.IO) {
                cleanupLimiter.withPermit {
                    progressTracker?.startCleanup(copiedEntries.size, migrationEntry.original.entry.name)
                    val deleted = runCatching {
                        deleteInternal(
                            context = context,
                            reference = migrationEntry.original.entry.reference,
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
        copiedEntries: List<CopiedMigrationEntry>
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val cleanupLimiter = Semaphore(MIGRATION_DELETE_PARALLELISM)
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
    }

    suspend fun saveMetadata(context: Context, audio: StoredEntry, json: String) = withContext(Dispatchers.IO) {
        val root = resolveRootBlocking(context)
        val metadataEntry = writeRootText(
            context = context,
            root = root,
            displayName = "${audio.name}$METADATA_SUFFIX",
            content = json,
            invalidateSnapshot = false
        )
        val metadata = parseDownloadedAudioMetadataJson(json)
        if (metadataEntry == null || metadata == null) {
            invalidateSnapshotCache(context)
            return@withContext
        }
        if (!updateSnapshotCacheAfterMetadataWrite(context, metadataEntry, metadata)) {
            invalidateSnapshotCache(context)
        }
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
        deleteReferencesInternal(
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
        expectedSizeBytes: Long? = null
    ): StoredEntry = withContext(Dispatchers.IO) {
        saveAudioFromTempBlocking(
            context = context,
            tempFile = tempFile,
            fileName = fileName,
            mimeType = mimeType,
            expectedSizeBytes = expectedSizeBytes
        )
    }

    private fun saveAudioFromTempBlocking(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?,
        expectedSizeBytes: Long?
    ): StoredEntry {
        val actualSizeBytes = tempFile.length().coerceAtLeast(0L)
        if (expectedSizeBytes != null && expectedSizeBytes > 0L && actualSizeBytes < expectedSizeBytes) {
            throw IOException("下载文件写入不完整: $actualSizeBytes/$expectedSizeBytes")
        }
        val storedEntry = try {
            when (val root = resolveRootBlocking(context)) {
                is RootHandle.FileRoot -> {
                    val finalName = createUniqueName(existingNames(root.dir), fileName)
                    val pendingTarget = File(root.dir, buildPendingAudioWriteName(finalName))
                    tempFile.copyTo(pendingTarget, overwrite = false)
                    val target = File(root.dir, finalName)
                    if (!pendingTarget.renameTo(target)) {
                        pendingTarget.delete()
                        throw IOException("无法提交下载文件: $finalName")
                    }
                    target.toStoredEntry()
                }

                is RootHandle.TreeRoot -> {
                    val finalName = createUniqueName(
                        cachedTreeChildrenNames(context, root.tree),
                        fileName
                    )
                    val committedAtMs = System.currentTimeMillis()
                    val pendingName = buildPendingAudioWriteName(finalName)
                    val pendingTarget = createRootFile(
                        context = context,
                        parent = root.tree,
                        desiredName = pendingName,
                        mimeType = mimeTypeFromName(finalName, mimeType),
                        replace = false
                    )
                    context.contentResolver.openOutputStream(pendingTarget.uri, "w")?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                        }
                    } ?: throw IOException("无法打开下载目录输出流")
                    if (!pendingTarget.renameTo(finalName)) {
                        forgetTreeChildName(root.tree, pendingName)
                        runCatching { pendingTarget.delete() }
                        throw IOException("无法提交下载文件: $finalName")
                    }
                    forgetTreeChildName(root.tree, pendingName)
                    rememberTreeChildName(root.tree, finalName)
                    pendingTarget.toStoredEntry(
                        knownName = finalName,
                        knownSizeBytes = actualSizeBytes,
                        knownLastModifiedMs = committedAtMs,
                        knownIsDirectory = false
                    )
                        ?: throw IOException("无法读取已写入的下载文件")
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
        if (!updateSnapshotCacheAfterStoredEntryWrite(context, storedEntry, SnapshotEntryBucket.AUDIO)) {
            invalidateSnapshotCache(context)
        }
        return storedEntry
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
        findIndexedEntryByNames(
            names = buildSidecarCandidateNames(candidateManagedDownloadBaseNames(audio.nameWithoutExtension)),
            entriesByName = snapshot.coverEntriesByName
        )?.reference
    }

    private suspend fun resolveRoot(context: Context, directoryUriString: String?): RootHandle? = withContext(Dispatchers.IO) {
        resolveRootBlocking(context, directoryUriString)
    }

    private fun resolveRootBlocking(context: Context): RootHandle {
        val configuredUri = normalizeDirectoryUri(customDirectoryUri)
        resolveTreeRootBlocking(context, configuredUri)?.also {
            ensureManagedMediaScanIsolation(context, it)
        }?.let { return it }
        if (configuredUri != null) {
            NPLogger.w(TAG, "自定义下载目录不可用，回退默认目录: $configuredUri")
        }
        return createDefaultRoot(context).also {
            ensureManagedMediaScanIsolation(context, it)
        }
    }

    private fun resolveRootBlocking(context: Context, directoryUriString: String?): RootHandle? {
        val normalizedUri = normalizeDirectoryUri(directoryUriString)
        val root = if (normalizedUri == null) {
            createDefaultRoot(context)
        } else {
            resolveTreeRootBlocking(context, normalizedUri)
        }
        root?.let { ensureManagedMediaScanIsolation(context, it) }
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
                queryTreeChildren(context, root.tree)
                    .map { child ->
                        StoredEntry(
                            name = child.name,
                            reference = child.documentUri.toString(),
                            mediaUri = child.documentUri.toString(),
                            localFilePath = null,
                            sizeBytes = child.sizeBytes,
                            lastModifiedMs = child.lastModifiedMs,
                            isDirectory = child.isDirectory
                        )
                    }
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

    private fun existingNames(dir: File): Set<String> {
        return dir.listFiles()
            ?.mapNotNull(File::getName)
            ?.toSet()
            .orEmpty()
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

    private fun cachedTreeChildrenNames(context: Context, parent: DocumentFile): Set<String> {
        val cacheKey = parent.uri.toString()
        val now = System.currentTimeMillis()
        treeChildrenNameCache[cacheKey]
            ?.takeIf { now - it.refreshedAtMs <= TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS }
            ?.let { return it.names }
        val refreshedNames = queryTreeChildren(context, parent)
            .map(QueriedTreeChild::name)
            .toSet()
        treeChildrenNameCache[cacheKey] = CachedTreeChildren(
            names = refreshedNames,
            refreshedAtMs = now
        )
        return refreshedNames
    }

    private fun rememberTreeChildName(parent: DocumentFile, childName: String) {
        val cacheKey = parent.uri.toString()
        val existing = treeChildrenNameCache[cacheKey]?.names.orEmpty()
        treeChildrenNameCache[cacheKey] = CachedTreeChildren(
            names = existing + childName,
            refreshedAtMs = System.currentTimeMillis()
        )
    }

    private fun forgetTreeChildName(parent: DocumentFile, childName: String) {
        val cacheKey = parent.uri.toString()
        val existing = treeChildrenNameCache[cacheKey]?.names ?: return
        treeChildrenNameCache[cacheKey] = CachedTreeChildren(
            names = existing - childName,
            refreshedAtMs = System.currentTimeMillis()
        )
    }

    private fun createRootFile(
        context: Context,
        parent: DocumentFile,
        desiredName: String,
        mimeType: String,
        replace: Boolean
    ): DocumentFile {
        val childNames = cachedTreeChildrenNames(context, parent)
        val existing = desiredName
            .takeIf { it in childNames }
            ?.let(parent::findFile)
        if (replace && existing != null && existing.isFile) {
            return existing
        }
        if (replace && existing != null) {
            existing.delete()
            forgetTreeChildName(parent, desiredName)
        }
        val finalName = if (replace) desiredName else createUniqueName(childNames, desiredName)
        return (
            parent.createFile(documentCreateMimeType(finalName, mimeType), finalName)
                ?: throw IOException("无法在下载目录创建文件: $finalName")
            ).also { rememberTreeChildName(parent, finalName) }
    }

    internal fun documentCreateMimeType(desiredName: String, mimeType: String): String {
        val normalizedMimeType = mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val extension = desiredName.substringAfterLast('.', "").lowercase()
        if (normalizedMimeType.equals("text/plain", ignoreCase = true) && extension.isNotBlank() && extension != "txt") {
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
                target.outputStream().use { output ->
                    input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val target = createRootFile(context, root.tree, displayName, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                } ?: throw IOException("无法写入根目录文件: $displayName")
                target.toStoredEntry(
                    knownName = displayName,
                    knownSizeBytes = copiedBytes.coerceAtLeast(0L),
                    knownLastModifiedMs = writtenAtMs,
                    knownIsDirectory = false
                ) ?: throw IOException("无法读取已写入的目录文件: $displayName")
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
        onProgress: ((Long) -> Unit)? = null
    ): StoredWriteResult {
        return when (root) {
            is RootHandle.FileRoot -> {
                val target = File(root.dir, displayName)
                if (target.exists()) {
                    throw MigrationTargetConflictException("目标下载目录已存在同名文件: $displayName")
                }
                var copiedBytes = 0L
                target.outputStream().use { output ->
                    copiedBytes = copyStreamWithProgress(input, output, onProgress)
                }
                StoredWriteResult(
                    entry = target.toStoredEntry().copy(sizeBytes = copiedBytes.coerceAtLeast(0L)),
                    createdNew = true
                )
            }

            is RootHandle.TreeRoot -> {
                ensureMigrationTargetAbsent(context, root.tree, displayName)
                val target = createRootFile(context, root.tree, displayName, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = copyStreamWithProgress(input, output, onProgress)
                } ?: throw IOException("无法写入根目录文件: $displayName")
                StoredWriteResult(
                    entry = target.toStoredEntry(
                        knownName = displayName,
                        knownSizeBytes = copiedBytes.coerceAtLeast(0L),
                        knownLastModifiedMs = writtenAtMs,
                        knownIsDirectory = false
                    ) ?: throw IOException("无法读取已写入的目录文件: $displayName"),
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
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(context, root.tree, subdirectory) ?: return null
                ensureManagedMediaScanIsolation(subdirectory, directory)
                val target = createRootFile(context, directory, displayName, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("无法写入目录文件: $displayName")
                target.toStoredEntry(
                    knownName = displayName,
                    knownSizeBytes = bytes.size.toLong(),
                    knownLastModifiedMs = writtenAtMs,
                    knownIsDirectory = false
                )
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
                target.outputStream().use { output ->
                    input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(context, root.tree, subdirectory)
                    ?: throw IOException("无法创建目录: $subdirectory")
                ensureManagedMediaScanIsolation(subdirectory, directory)
                val target = createRootFile(context, directory, displayName, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                } ?: throw IOException("无法写入目录文件: $displayName")
                target.toStoredEntry(
                    knownName = displayName,
                    knownSizeBytes = copiedBytes.coerceAtLeast(0L),
                    knownLastModifiedMs = writtenAtMs,
                    knownIsDirectory = false
                ) ?: throw IOException("无法读取已写入的目录文件: $displayName")
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
        onProgress: ((Long) -> Unit)? = null
    ): StoredWriteResult {
        return when (root) {
            is RootHandle.FileRoot -> {
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                ensureManagedMediaScanIsolation(subdirectory, dir)
                val target = File(dir, displayName)
                if (target.exists()) {
                    throw MigrationTargetConflictException("目标下载目录已存在同名文件: $subdirectory/$displayName")
                }
                var copiedBytes = 0L
                target.outputStream().use { output ->
                    copiedBytes = copyStreamWithProgress(input, output, onProgress)
                }
                StoredWriteResult(
                    entry = target.toStoredEntry().copy(sizeBytes = copiedBytes.coerceAtLeast(0L)),
                    createdNew = true
                )
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(context, root.tree, subdirectory)
                    ?: throw IOException("无法创建目录: $subdirectory")
                ensureManagedMediaScanIsolation(subdirectory, directory)
                ensureMigrationTargetAbsent(context, directory, displayName)
                val target = createRootFile(context, directory, displayName, mimeType, replace = true)
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = copyStreamWithProgress(input, output, onProgress)
                } ?: throw IOException("无法写入目录文件: $displayName")
                StoredWriteResult(
                    entry = target.toStoredEntry(
                        knownName = displayName,
                        knownSizeBytes = copiedBytes.coerceAtLeast(0L),
                        knownLastModifiedMs = writtenAtMs,
                        knownIsDirectory = false
                    ) ?: throw IOException("无法读取已写入的目录文件: $displayName"),
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
                target.writeText(content, Charsets.UTF_8)
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val target = createRootFile(context, root.tree, displayName, "application/json", replace = true)
                val writtenAtMs = System.currentTimeMillis()
                val encoded = content.toByteArray(Charsets.UTF_8)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(encoded)
                } ?: throw IOException("无法写入元数据文件: $displayName")
                target.toStoredEntry(
                    knownName = displayName,
                    knownSizeBytes = encoded.size.toLong(),
                    knownLastModifiedMs = writtenAtMs,
                    knownIsDirectory = false
                ) ?: throw IOException("无法读取已写入的元数据文件: $displayName")
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
            queryTreeChildren(context, parent)
                .filter(QueriedTreeChild::isDirectory)
                .mapNotNull { child ->
                    DocumentFile.fromTreeUri(context, child.documentUri)
                        ?.let { directory -> child.name to directory }
                }
                .filter { (name, _) -> matchesManagedSubdirectoryName(name, displayName) }
                .sortedWith(
                    compareBy<Pair<String, DocumentFile>>(
                        { if (it.first == displayName) 0 else 1 },
                        { managedSubdirectoryOrdinal(it.first, displayName) },
                        { it.first }
                    )
                )
                .firstOrNull()
                ?.second
                ?.also { treeSubdirectoryCache[cacheKey] = it }
                ?.let { return@synchronized it }
            parent.listFiles()
                .filter(DocumentFile::isDirectory)
                .mapNotNull { file -> file.name?.let { name -> name to file } }
                .filter { (name, _) -> matchesManagedSubdirectoryName(name, displayName) }
                .sortedWith(
                    compareBy<Pair<String, DocumentFile>>(
                        { if (it.first == displayName) 0 else 1 },
                        { managedSubdirectoryOrdinal(it.first, displayName) },
                        { it.first }
                    )
                )
                .firstOrNull()
                ?.second
                ?.also { treeSubdirectoryCache[cacheKey] = it }
                ?.let { return@synchronized it }
            val createdDirectory = parent.createDirectory(displayName)
                ?: parent.listFiles()
                    .firstOrNull { file ->
                        file.isDirectory && file.name?.let { name ->
                            matchesManagedSubdirectoryName(name, displayName)
                        } == true
                    }
            createdDirectory?.also {
                treeSubdirectoryCache[cacheKey] = it
                it.name?.let { createdName -> rememberTreeChildName(parent, createdName) }
            }
        }
    }

    private fun clearTreeDirectoryCache() {
        treeSubdirectoryCache.clear()
        treeChildrenNameCache.clear()
        ensuredNoMediaMarkers.clear()
        cachedTreeRoot = null
    }

    private fun ensureManagedMediaScanIsolation(context: Context, root: RootHandle) {
        runCatching {
            when (root) {
                is RootHandle.FileRoot -> {
                    findSubdirectories(context, root, COVER_SUBDIRECTORY).forEach { directory ->
                        if (directory is RootHandle.FileRoot) {
                            ensureNoMediaMarker(directory.dir)
                        }
                    }
                }

                is RootHandle.TreeRoot -> {
                    findSubdirectories(context, root, COVER_SUBDIRECTORY).forEach { directory ->
                        if (directory is RootHandle.TreeRoot) {
                            ensureNoMediaMarker(directory.tree)
                        }
                    }
                }
            }
        }.onFailure {
            NPLogger.w(TAG, "补写封面目录 .nomedia 失败: ${it.message}")
        }
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
                val queriedDirectories = queryTreeChildren(context, root.tree)
                    .filter(QueriedTreeChild::isDirectory)
                    .mapNotNull { child ->
                        DocumentFile.fromTreeUri(context, child.documentUri)?.let { file ->
                            NamedDirectoryRoot(name = child.name, root = RootHandle.TreeRoot(file))
                        }
                    }
                if (queriedDirectories.isNotEmpty()) {
                    queriedDirectories
                } else {
                    root.tree.listFiles()
                        .filter(DocumentFile::isDirectory)
                        .mapNotNull { file ->
                            file.name?.let { name ->
                                NamedDirectoryRoot(name = name, root = RootHandle.TreeRoot(file))
                            }
                        }
                }
            }
        }
    }

    private fun ensureMigrationTargetAbsent(context: Context, parent: DocumentFile, displayName: String) {
        if (displayName !in cachedTreeChildrenNames(context, parent)) {
            return
        }
        parent.findFile(displayName)?.let { existing ->
            val targetType = if (existing.isDirectory) "目录" else "文件"
            throw MigrationTargetConflictException("目标下载目录已存在同名$targetType: $displayName")
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

    private fun ensureManagedMediaScanIsolation(subdirectory: String, directory: DocumentFile) {
        if (!shouldCreateNoMediaMarker(subdirectory)) return
        runCatching {
            ensureNoMediaMarker(directory)
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

    private fun ensureNoMediaMarker(directory: DocumentFile) {
        val cacheKey = directory.uri.toString()
        if (ensuredNoMediaMarkers[cacheKey] == true) return
        if (directory.findFile(NO_MEDIA_FILE_NAME) != null) {
            ensuredNoMediaMarkers[cacheKey] = true
            return
        }
        directory.createFile("application/octet-stream", NO_MEDIA_FILE_NAME)
            ?: throw IOException("无法创建 $NO_MEDIA_FILE_NAME")
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

    private suspend fun <T> retryManagedMigrationWrite(
        reference: String,
        block: () -> T
    ): T? {
        repeat(MIGRATION_IO_MAX_ATTEMPTS) { attempt ->
            var shouldRetry = true
            val result = runCatching(block).onFailure { error ->
                if (error is MigrationTargetConflictException) {
                    shouldRetry = false
                }
                NPLogger.w(
                    TAG,
                    "迁移下载文件失败: $reference, attempt=${attempt + 1}/$MIGRATION_IO_MAX_ATTEMPTS, ${error.message}"
                )
            }.getOrNull()
            if (result != null) {
                return result
            }
            if (!shouldRetry) {
                return null
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

    private fun createUniqueName(existingNames: Set<String>, desiredName: String): String {
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

    private fun deleteInternal(
        context: Context,
        reference: String?,
        invalidateSnapshot: Boolean = true
    ): Boolean {
        return deleteReferencesInternal(
            context = context,
            references = listOf(reference),
            invalidateSnapshot = invalidateSnapshot
        ).isNotEmpty()
    }

    private fun deleteReferencesInternal(
        context: Context,
        references: Collection<String?>,
        invalidateSnapshot: Boolean
    ): Set<String> {
        val normalizedReferences = references
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinct()
        if (normalizedReferences.isEmpty()) {
            return emptySet()
        }
        val deletedReferences = linkedSetOf<String>()
        normalizedReferences.forEach { reference ->
            val deleted = when {
                reference.startsWith("/") -> {
                    val file = File(reference)
                    !file.exists() || file.delete()
                }

                else -> {
                    val uri = runCatching { reference.toUri() }.getOrNull() ?: return@forEach
                    deleteContentReference(
                        context = context,
                        reference = reference,
                        uri = uri
                    )
                }
            }
            if (deleted) {
                deletedReferences += reference
            }
        }
        if (deletedReferences.isNotEmpty() && invalidateSnapshot) {
            clearTreeDirectoryCache()
            if (!updateSnapshotCacheAfterDelete(context, deletedReferences)) {
                invalidateSnapshotCache(context)
            }
        }
        return deletedReferences
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
        if (!existsInternal(context, reference)) {
            return true
        }

        val deletedByContract = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrElse { error ->
            if (isMissingManagedDocumentFailure(error)) {
                return true
            }
            false
        }
        if (deletedByContract || !existsInternal(context, reference)) {
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
        return deletedByDocumentFile || !existsInternal(context, reference)
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
            durationMs = optLong("durationMs")
        )
    }

    internal fun parseDownloadedAudioMetadataJson(rawJson: String): DownloadedAudioMetadata? {
        return runCatching {
            JSONObject(rawJson).toDownloadedAudioMetadata()
        }.onFailure {
            NPLogger.w(TAG, "解析写回元数据失败: ${it.message}")
        }.getOrNull()
    }

    private fun JSONObject.optPresentString(fieldName: String): String? {
        if (!has(fieldName) || isNull(fieldName)) {
            return null
        }
        return optString(fieldName)
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
