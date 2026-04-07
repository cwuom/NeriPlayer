package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object ManagedDownloadStorage {
    private const val TAG = "ManagedDownloadStorage"
    private const val ROOT_DIR_NAME = "NeriPlayer"
    private const val COVER_SUBDIRECTORY = "Covers"
    private const val NO_MEDIA_FILE_NAME = ".nomedia"
    private const val SNAPSHOT_CACHE_FILE_NAME = "managed_download_snapshot_v1.json"
    private const val SNAPSHOT_CACHE_PERSIST_DEBOUNCE_MS = 1_200L
    @Suppress("SpellCheckingInspection")
    private const val METADATA_SUFFIX = ".npmeta.json"
    private const val MIGRATION_COPY_PARALLELISM = 4
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

    @Volatile
    private var snapshotPersistJob: Job? = null

    @Volatile
    private var snapshotRestoreScheduled = false

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        createDefaultRoot(appContext)
        invalidateSnapshotCache()
        scheduleSnapshotCacheRestore(appContext)
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

    private data class ManagedMigrationEntry(
        val subdirectory: String?,
        val entry: StoredEntry
    )

    private data class CopiedMigrationEntry(
        val original: ManagedMigrationEntry,
        val copiedEntry: StoredEntry
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

    private data class SnapshotCache(
        val key: String,
        val snapshot: DownloadLibrarySnapshot
    )

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
        invalidateSnapshotCache()
    }

    fun updateCustomDirectoryUri(uri: String?) {
        customDirectoryUri = uri?.takeIf { it.isNotBlank() }
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
        collectManagedMigrationEntries(root).isNotEmpty()
    }

    suspend fun migrateManagedDownloads(
        context: Context,
        fromDirectoryUri: String?,
        toDirectoryUri: String?
    ): MigrationResult = withContext(Dispatchers.IO) {
        if (areEquivalentDirectoryUris(fromDirectoryUri, toDirectoryUri)) {
            return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
        }

        val sourceRoot = resolveRoot(context, fromDirectoryUri) ?: return@withContext MigrationResult(
            movedFiles = 0,
            skippedFiles = 0
        )
        val targetRoot = resolveRoot(context, toDirectoryUri)
            ?: throw IOException("目标下载目录不可用")

        val entries = collectManagedMigrationEntries(sourceRoot)
        if (entries.isEmpty()) {
            return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
        }

        val copyLimiter = Semaphore(MIGRATION_COPY_PARALLELISM)
        val copyResults = coroutineScope {
            entries.map { migrationEntry ->
                async(Dispatchers.IO) {
                    copyLimiter.withPermit {
                        copyManagedMigrationEntry(
                            context = context,
                            targetRoot = targetRoot,
                            migrationEntry = migrationEntry
                        )
                    }
                }
            }.awaitAll()
        }
        val copiedEntries = copyResults.mapNotNull { it.copiedEntry }
        val skippedFiles = copyResults.count { it.copiedEntry == null }

        if (skippedFiles > 0) {
            return@withContext MigrationResult(
                movedFiles = copiedEntries.size,
                skippedFiles = skippedFiles
            )
        }

        val rewriteFailedFiles = rewriteMigratedMetadataReferences(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries
        )
        if (rewriteFailedFiles > 0) {
            return@withContext MigrationResult(
                movedFiles = copiedEntries.size,
                skippedFiles = rewriteFailedFiles
            )
        }

        var cleanupFailedFiles = 0
        copiedEntries.forEach { migrationEntry ->
            val deleted = runCatching {
                deleteInternal(context, migrationEntry.original.entry.reference)
            }.onFailure {
                NPLogger.w(TAG, "迁移后删除旧下载文件失败: ${migrationEntry.original.entry.reference}, ${it.message}")
            }.getOrDefault(false)

            if (!deleted) {
                cleanupFailedFiles++
            }
        }

        MigrationResult(
            movedFiles = copiedEntries.size,
            skippedFiles = 0,
            cleanupFailedFiles = cleanupFailedFiles
        )
    }

    private data class ManagedMigrationCopyResult(
        val copiedEntry: CopiedMigrationEntry?
    )

    private fun copyManagedMigrationEntry(
        context: Context,
        targetRoot: RootHandle,
        migrationEntry: ManagedMigrationEntry
    ): ManagedMigrationCopyResult {
        val copiedEntry = runCatching {
            openStoredEntryInputStream(context, migrationEntry.entry)?.use { input ->
                if (migrationEntry.subdirectory == null) {
                    writeRootStream(
                        context = context,
                        root = targetRoot,
                        displayName = migrationEntry.entry.name,
                        mimeType = migrationMimeTypeFor(migrationEntry),
                        input = input
                    )
                } else {
                    writeSubdirectoryStream(
                        context = context,
                        root = targetRoot,
                        subdirectory = migrationEntry.subdirectory,
                        displayName = migrationEntry.entry.name,
                        mimeType = migrationMimeTypeFor(migrationEntry),
                        input = input
                    )
                }
            } ?: throw IOException("无法读取源下载文件: ${migrationEntry.entry.name}")
        }.onFailure {
            NPLogger.w(TAG, "迁移下载文件失败: ${migrationEntry.entry.reference}, ${it.message}")
        }.getOrNull()
            ?: return ManagedMigrationCopyResult(copiedEntry = null)

        return ManagedMigrationCopyResult(
            copiedEntry = CopiedMigrationEntry(
                original = migrationEntry,
                copiedEntry = copiedEntry
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

    fun hasDownloadedAudio(context: Context, song: SongItem): Boolean {
        return findDownloadedAudioBlocking(context, song) != null
    }

    fun buildDisplayBaseName(song: SongItem): String {
        return renderManagedDownloadBaseName(song, downloadFileNameTemplate)
    }

    fun createWorkingFile(context: Context, fileName: String): File {
        val stagingDir = File(context.cacheDir, "download_staging").apply { mkdirs() }
        return File(stagingDir, fileName)
    }

    fun findAudio(context: Context, song: SongItem): StoredEntry? {
        return findDownloadedAudioBlocking(context, song)
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

    suspend fun findDownloadedAudio(context: Context, song: SongItem): StoredEntry? = withContext(Dispatchers.IO) {
        findDownloadedAudioBlocking(context, song)
    }

    suspend fun queryStoredEntry(context: Context, reference: String?): StoredEntry? = withContext(Dispatchers.IO) {
        val target = reference?.takeIf { it.isNotBlank() } ?: return@withContext null
        buildDownloadLibrarySnapshotBlocking(context).audioEntriesByLookupKey[target]
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

    private fun findDownloadedAudioBlocking(context: Context, song: SongItem): StoredEntry? {
        val snapshot = buildDownloadLibrarySnapshotBlocking(context)
        return findAudioEntry(snapshot, song)
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
        val rootEntries = listChildren(root).filterNot(StoredEntry::isDirectory)
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
        val coverEntries = listSubdirectoryEntries(root, COVER_SUBDIRECTORY)
        val lyricEntries = listSubdirectoryEntries(root, "Lyrics")
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

    private fun rewriteMigratedMetadataReferences(
        context: Context,
        targetRoot: RootHandle,
        copiedEntries: List<CopiedMigrationEntry>
    ): Int {
        if (copiedEntries.isEmpty()) return 0
        val referenceMap = copiedEntries.associate { copied ->
            copied.original.entry.reference to copied.copiedEntry.reference
        }
        var failedFiles = 0
        copiedEntries
            .filter { it.original.entry.name.endsWith(METADATA_SUFFIX) }
            .forEach { copied ->
                val raw = readTextInternal(context, copied.copiedEntry.reference)
                val rewritten = runCatching {
                    val metadataText = raw
                        ?: throw IOException("无法读取已迁移 metadata: ${copied.copiedEntry.name}")
                    rewriteManagedMetadataReferences(metadataText, referenceMap)
                }.onFailure {
                    NPLogger.w(TAG, "迁移后重写 metadata 引用失败: ${copied.copiedEntry.reference}, ${it.message}")
                }.getOrNull()
                if (rewritten == null) {
                    failedFiles++
                    return@forEach
                }
                if (rewritten != raw) {
                    runCatching {
                        writeRootText(
                            context = context,
                            root = targetRoot,
                            displayName = copied.copiedEntry.name,
                            content = rewritten
                        )
                    }.onFailure {
                        NPLogger.w(TAG, "回写迁移后的 metadata 失败: ${copied.copiedEntry.reference}, ${it.message}")
                    }.getOrElse {
                        failedFiles++
                        Unit
                    }
                }
            }
        return failedFiles
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
        return normalizeDirectoryUri(customDirectoryUri) == null
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
        buildDownloadLibrarySnapshotBlocking(context).metadataEntriesByAudioName[audio.name]
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

    suspend fun readText(context: Context, reference: String): String? = withContext(Dispatchers.IO) {
        readTextInternal(context, reference)
    }

    suspend fun exists(context: Context, reference: String?): Boolean = withContext(Dispatchers.IO) {
        existsInternal(context, reference)
    }

    suspend fun deleteReference(context: Context, reference: String?): Boolean = withContext(Dispatchers.IO) {
        deleteInternal(context, reference)
    }

    suspend fun saveAudioFromTemp(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?
    ): StoredEntry = withContext(Dispatchers.IO) {
        saveAudioFromTempBlocking(
            context = context,
            tempFile = tempFile,
            fileName = fileName,
            mimeType = mimeType
        )
    }

    private fun saveAudioFromTempBlocking(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?
    ): StoredEntry {
        val storedEntry = when (val root = resolveRootBlocking(context)) {
            is RootHandle.FileRoot -> {
                val target = File(root.dir, createUniqueName(existingNames(root.dir), fileName))
                tempFile.copyTo(target, overwrite = false)
                tempFile.delete()
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val target = createRootFile(
                    parent = root.tree,
                    desiredName = fileName,
                    mimeType = mimeTypeFromName(fileName, mimeType),
                    replace = false
                )
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("无法打开下载目录输出流")
                tempFile.delete()
                target.toStoredEntry()
                    ?: throw IOException("无法读取已写入的下载文件")
            }
        }
        invalidateSnapshotCache(context)
        return storedEntry
    }

    fun commitCoverFile(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?
    ): StoredEntry? {
        val bytes = tempFile.takeIf(File::exists)?.readBytes() ?: return null
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
            subdirectory = "Lyrics",
            displayName = displayName,
            bytes = content.toByteArray(Charsets.UTF_8),
            mimeType = mimeTypeFromName(displayName, null)
        )?.reference
    }

    fun overwriteLyric(context: Context, fileName: String, content: String): String? {
        return saveLyricTextBlocking(context, fileName, content)
    }

    fun findLyricLocation(
        context: Context,
        songId: Long,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): String? {
        val snapshot = buildDownloadLibrarySnapshotBlocking(context)
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
    ) {
        val fileNameByName = if (translated) "${baseName}_trans.lrc" else "$baseName.lrc"
        NPLogger.d(TAG, "写入歌词文件: fileName=$fileNameByName, translated=$translated, songId=$songId")
        overwriteLyric(context, fileNameByName, content)
    }

    fun readLyrics(context: Context, song: SongItem, translated: Boolean): String? {
        val reference = findLyricLocation(
            context = context,
            songId = song.id,
            candidateBaseNames = candidateManagedDownloadBaseNames(song, downloadFileNameTemplate),
            translated = translated
        ) ?: return null
        return readTextInternal(context, reference)
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
        val snapshot = buildDownloadLibrarySnapshotBlocking(context)
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
            ensureManagedMediaScanIsolation(it)
        }?.let { return it }
        if (configuredUri != null) {
            NPLogger.w(TAG, "自定义下载目录不可用，回退默认目录: $configuredUri")
        }
        return createDefaultRoot(context).also {
            ensureManagedMediaScanIsolation(it)
        }
    }

    private fun resolveRootBlocking(context: Context, directoryUriString: String?): RootHandle? {
        val normalizedUri = normalizeDirectoryUri(directoryUriString)
        val root = if (normalizedUri == null) {
            createDefaultRoot(context)
        } else {
            resolveTreeRootBlocking(context, normalizedUri)
        }
        root?.let { ensureManagedMediaScanIsolation(it) }
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
                    NPLogger.d(TAG, "命中已下载音频(stableKey): song=${song.displayName()}, file=${entry.name}")
                }
            }

        remoteTrackKey?.let { key ->
            snapshot.audioEntriesByRemoteTrackKey[key]
                ?.let { matches ->
                    return pickBestAudioEntry(matches, song)?.also { entry ->
                        NPLogger.d(TAG, "命中已下载音频(remoteTrackKey): song=${song.displayName()}, file=${entry.name}")
                    }
                }
        }

        identity.mediaUri?.let { mediaUri ->
            snapshot.audioEntriesByMediaUri[mediaUri]
                ?.let { matches ->
                    return pickBestAudioEntry(matches, song)?.also { entry ->
                        NPLogger.d(TAG, "命中已下载音频(mediaUri): song=${song.displayName()}, file=${entry.name}")
                    }
                }
        }

        identity.id.takeIf { it > 0L }?.let { songId ->
            snapshot.audioEntriesBySongId[songId]
                ?.let { matches ->
                    return pickBestAudioEntry(matches, song)?.also { entry ->
                        NPLogger.d(TAG, "命中已下载音频(songId): song=${song.displayName()}, file=${entry.name}")
                    }
                }
        }

        return findAudioEntry(
            audioEntries = snapshot.audioEntriesWithoutMetadata,
            baseNames = candidateManagedDownloadBaseNames(song, downloadFileNameTemplate)
        )?.also { entry ->
            NPLogger.d(TAG, "命中已下载音频(legacyNameFallback): song=${song.displayName()}, file=${entry.name}")
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

    private fun listChildren(root: RootHandle): List<StoredEntry> {
        return when (root) {
            is RootHandle.FileRoot -> {
                root.dir.listFiles()
                    ?.map { file -> file.toStoredEntry() }
                    .orEmpty()
            }

            is RootHandle.TreeRoot -> {
                root.tree.listFiles()
                    .mapNotNull { file -> file.toStoredEntry() }
            }
        }
    }

    private fun collectManagedMigrationEntries(root: RootHandle): List<ManagedMigrationEntry> {
        val rootEntries = listChildren(root)
            .filterNot(StoredEntry::isDirectory)
            .filter { entry ->
                entry.extension in audioExtensions || entry.name.endsWith(METADATA_SUFFIX)
            }
            .map { entry -> ManagedMigrationEntry(subdirectory = null, entry = entry) }

        return (rootEntries +
            collectManagedMigrationEntries(root, "Lyrics") +
            collectManagedMigrationEntries(root, COVER_SUBDIRECTORY))
            .sortedWith(compareBy({ it.subdirectory ?: "" }, { it.entry.name }))
    }

    private fun collectManagedMigrationEntries(
        root: RootHandle,
        subdirectory: String
    ): List<ManagedMigrationEntry> {
        val directory = findSubdirectory(root, subdirectory) ?: return emptyList()
        return listChildren(directory)
            .filterNot(StoredEntry::isDirectory)
            .map { entry -> ManagedMigrationEntry(subdirectory = subdirectory, entry = entry) }
    }

    private fun listSubdirectoryEntries(root: RootHandle, subdirectory: String): List<StoredEntry> {
        val directory = findSubdirectory(root, subdirectory) ?: return emptyList()
        return listChildren(directory).filterNot(StoredEntry::isDirectory)
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
        return runCatching {
            val root = JSONObject(raw)
            DownloadedAudioMetadata(
                stableKey = root.optString("stableKey").takeIf(String::isNotBlank),
                songId = root.optLong("songId").takeIf { it > 0L },
                identityAlbum = root.optString("identityAlbum").takeIf(String::isNotBlank),
                name = root.optString("name").takeIf(String::isNotBlank),
                artist = root.optString("artist").takeIf(String::isNotBlank),
                coverUrl = root.optString("coverUrl").takeIf(String::isNotBlank),
                matchedLyricSource = root.optString("matchedLyricSource").takeIf(String::isNotBlank),
                matchedSongId = root.optString("matchedSongId").takeIf(String::isNotBlank),
                userLyricOffsetMs = root.optLong("userLyricOffsetMs"),
                customCoverUrl = root.optString("customCoverUrl").takeIf(String::isNotBlank),
                customName = root.optString("customName").takeIf(String::isNotBlank),
                customArtist = root.optString("customArtist").takeIf(String::isNotBlank),
                originalName = root.optString("originalName").takeIf(String::isNotBlank),
                originalArtist = root.optString("originalArtist").takeIf(String::isNotBlank),
                originalCoverUrl = root.optString("originalCoverUrl").takeIf(String::isNotBlank),
                mediaUri = root.optString("mediaUri").takeIf(String::isNotBlank),
                channelId = root.optString("channelId").takeIf(String::isNotBlank),
                audioId = root.optString("audioId").takeIf(String::isNotBlank),
                subAudioId = root.optString("subAudioId").takeIf(String::isNotBlank),
                coverPath = root.optString("coverPath").takeIf(String::isNotBlank),
                lyricPath = root.optString("lyricPath").takeIf(String::isNotBlank),
                translatedLyricPath = root.optString("translatedLyricPath").takeIf(String::isNotBlank),
                durationMs = root.optLong("durationMs")
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "解析下载 metadata 失败: ${entry.name} - ${error.message}")
        }.getOrNull()
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

    private fun scheduleSnapshotCacheRestore(context: Context) {
        if (snapshotRestoreScheduled) {
            return
        }
        synchronized(snapshotPersistenceLock) {
            if (snapshotRestoreScheduled) {
                return
            }
            snapshotRestoreScheduled = true
        }
        val appContext = context.applicationContext
        snapshotScope.launch {
            try {
                restoreSnapshotCacheFromDisk(appContext)
            } finally {
                snapshotRestoreScheduled = false
            }
        }
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

    private fun existingNames(dir: File): Set<String> {
        return dir.listFiles()
            ?.mapNotNull(File::getName)
            ?.toSet()
            .orEmpty()
    }

    private fun createRootFile(
        parent: DocumentFile,
        desiredName: String,
        mimeType: String,
        replace: Boolean
    ): DocumentFile {
        val existing = parent.findFile(desiredName)
        if (replace && existing != null && existing.isFile) {
            return existing
        }
        if (replace && existing != null) {
            existing.delete()
        }
        val finalName = if (replace) desiredName else createUniqueName(parent.listFiles().mapNotNull(DocumentFile::getName).toSet(), desiredName)
        return parent.createFile(documentCreateMimeType(finalName, mimeType), finalName)
            ?: throw IOException("无法在下载目录创建文件: $finalName")
    }

    internal fun documentCreateMimeType(desiredName: String, mimeType: String): String {
        val normalizedMimeType = mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val extension = desiredName.substringAfterLast('.', "").lowercase()
        if (normalizedMimeType.equals("text/plain", ignoreCase = true) && extension.isNotBlank() && extension != "txt") {
            return "application/octet-stream"
        }
        return normalizedMimeType
    }

    private fun writeRootStream(
        context: Context,
        root: RootHandle,
        displayName: String,
        mimeType: String,
        input: InputStream
    ): StoredEntry {
        val storedEntry = when (root) {
            is RootHandle.FileRoot -> {
                val target = File(root.dir, displayName)
                target.outputStream().use { output -> input.copyTo(output) }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val target = createRootFile(root.tree, displayName, mimeType, replace = true)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("无法写入根目录文件: $displayName")
                target.toStoredEntry()
                    ?: throw IOException("无法读取已写入的目录文件: $displayName")
            }
        }
        invalidateSnapshotCache(context)
        return storedEntry
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
                val directory = findOrCreateDirectory(root.tree, subdirectory) ?: return null
                ensureManagedMediaScanIsolation(subdirectory, directory)
                val target = createRootFile(directory, displayName, mimeType, replace = true)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("无法写入目录文件: $displayName")
                target.toStoredEntry()
            }
        }
        storedEntry?.let { invalidateSnapshotCache(context) }
        return storedEntry
    }

    private fun writeSubdirectoryStream(
        context: Context,
        root: RootHandle,
        subdirectory: String,
        displayName: String,
        mimeType: String,
        input: InputStream
    ): StoredEntry {
        val storedEntry = when (root) {
            is RootHandle.FileRoot -> {
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                ensureManagedMediaScanIsolation(subdirectory, dir)
                val target = File(dir, displayName)
                target.outputStream().use { output -> input.copyTo(output) }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(root.tree, subdirectory)
                    ?: throw IOException("无法创建目录: $subdirectory")
                ensureManagedMediaScanIsolation(subdirectory, directory)
                val target = createRootFile(directory, displayName, mimeType, replace = true)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("无法写入目录文件: $displayName")
                target.toStoredEntry()
                    ?: throw IOException("无法读取已写入的目录文件: $displayName")
            }
        }
        invalidateSnapshotCache(context)
        return storedEntry
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
                val target = createRootFile(root.tree, displayName, "application/json", replace = true)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("无法写入元数据文件: $displayName")
                target.toStoredEntry()
                    ?: throw IOException("无法读取已写入的元数据文件: $displayName")
            }
        }
        if (invalidateSnapshot) {
            invalidateSnapshotCache(context)
        }
        return storedEntry
    }

    private fun findSubdirectory(root: RootHandle, name: String): RootHandle? {
        return when (root) {
            is RootHandle.FileRoot -> {
                val dir = File(root.dir, name)
                if (dir.exists() && dir.isDirectory) RootHandle.FileRoot(dir) else null
            }

            is RootHandle.TreeRoot -> root.tree.findFile(name)
                ?.takeIf(DocumentFile::isDirectory)
                ?.let { RootHandle.TreeRoot(it) }
        }
    }

    private fun findOrCreateDirectory(parent: DocumentFile, displayName: String): DocumentFile? {
        parent.findFile(displayName)?.takeIf(DocumentFile::isDirectory)?.let { return it }
        return parent.createDirectory(displayName)
    }

    private fun ensureManagedMediaScanIsolation(root: RootHandle) {
        runCatching {
            when (root) {
                is RootHandle.FileRoot -> {
                    val directory = File(root.dir, COVER_SUBDIRECTORY)
                    if (directory.isDirectory) {
                        ensureNoMediaMarker(directory)
                    }
                }

                is RootHandle.TreeRoot -> {
                    root.tree.findFile(COVER_SUBDIRECTORY)
                        ?.takeIf(DocumentFile::isDirectory)
                        ?.let { ensureNoMediaMarker(it) }
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
        val marker = File(directory, NO_MEDIA_FILE_NAME)
        if (marker.exists()) return
        if (!marker.createNewFile()) {
            throw IOException("无法创建 $NO_MEDIA_FILE_NAME")
        }
    }

    private fun ensureNoMediaMarker(directory: DocumentFile) {
        if (directory.findFile(NO_MEDIA_FILE_NAME) != null) return
        directory.createFile("application/octet-stream", NO_MEDIA_FILE_NAME)
            ?: throw IOException("无法创建 $NO_MEDIA_FILE_NAME")
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

    private fun resolveTreeRootBlocking(context: Context, directoryUriString: String?): RootHandle.TreeRoot? {
        val uriString = normalizeConfiguredDirectoryUri(directoryUriString) ?: return null
        val treeUri = runCatching { uriString.toUri() }.getOrNull() ?: return null
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return tree.takeIf { it.exists() && it.isDirectory }?.let(RootHandle::TreeRoot)
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
            else -> context.contentResolver.openInputStream(reference.toUri())
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }
    }

    private fun existsInternal(context: Context, reference: String?): Boolean {
        if (reference.isNullOrBlank()) return false
        return when {
            reference.startsWith("/") -> File(reference).exists()
            else -> {
                val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
                DocumentFile.fromSingleUri(context, uri)?.exists()
                    ?: runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
            }
        }
    }

    private fun deleteInternal(context: Context, reference: String?): Boolean {
        if (reference.isNullOrBlank()) return false
        val deleted = when {
            reference.startsWith("/") -> {
                val file = File(reference)
                !file.exists() || file.delete()
            }

            else -> {
                val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
                DocumentFile.fromSingleUri(context, uri)?.delete()
                    ?: false
            }
        }
        if (deleted) {
            invalidateSnapshotCache(context)
        }
        return deleted
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
            matchedLyric = optString("matchedLyric").takeIf(String::isNotBlank),
            matchedTranslatedLyric = optString("matchedTranslatedLyric").takeIf(String::isNotBlank),
            matchedLyricSource = optString("matchedLyricSource").takeIf(String::isNotBlank),
            matchedSongId = optString("matchedSongId").takeIf(String::isNotBlank),
            userLyricOffsetMs = optLong("userLyricOffsetMs"),
            customCoverUrl = optString("customCoverUrl").takeIf(String::isNotBlank),
            customName = optString("customName").takeIf(String::isNotBlank),
            customArtist = optString("customArtist").takeIf(String::isNotBlank),
            originalName = optString("originalName").takeIf(String::isNotBlank),
            originalArtist = optString("originalArtist").takeIf(String::isNotBlank),
            originalCoverUrl = optString("originalCoverUrl").takeIf(String::isNotBlank),
            originalLyric = optString("originalLyric").takeIf(String::isNotBlank),
            originalTranslatedLyric = optString("originalTranslatedLyric").takeIf(String::isNotBlank),
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

    private fun parseDownloadedAudioMetadataJson(rawJson: String): DownloadedAudioMetadata? {
        return runCatching {
            JSONObject(rawJson).toDownloadedAudioMetadata()
        }.onFailure {
            NPLogger.w(TAG, "解析写回元数据失败: ${it.message}")
        }.getOrNull()
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

    private fun DocumentFile.toStoredEntry(): StoredEntry? {
        val displayName = name ?: return null
        return StoredEntry(
            name = displayName,
            reference = uri.toString(),
            mediaUri = uri.toString(),
            localFilePath = null,
            sizeBytes = length(),
            lastModifiedMs = lastModified(),
            isDirectory = isDirectory
        )
    }
}
