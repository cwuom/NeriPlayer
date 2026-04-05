package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

internal object ManagedDownloadStorage {
    private const val TAG = "ManagedDownloadStorage"
    private const val ROOT_DIR_NAME = "NeriPlayer"
    @Suppress("SpellCheckingInspection")
    private const val METADATA_SUFFIX = ".npmeta.json"
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

    fun initialize(context: Context) {
        createDefaultRoot(context.applicationContext)
        invalidateSnapshotCache()
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
        val mediaUri: String? = null,
        val channelId: String? = null,
        val audioId: String? = null,
        val subAudioId: String? = null,
        val coverPath: String? = null,
        val lyricPath: String? = null,
        val translatedLyricPath: String? = null
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
        if (normalizeDirectoryUri(fromDirectoryUri) == normalizeDirectoryUri(toDirectoryUri)) {
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

        val copiedEntries = mutableListOf<ManagedMigrationEntry>()
        var skippedFiles = 0

        entries.forEach { migrationEntry ->
            val copySucceeded = runCatching {
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
            }.isSuccess

            if (copySucceeded) {
                copiedEntries += migrationEntry
            } else {
                skippedFiles++
            }
        }

        if (skippedFiles > 0) {
            return@withContext MigrationResult(
                movedFiles = copiedEntries.size,
                skippedFiles = skippedFiles
            )
        }

        var cleanupFailedFiles = 0
        copiedEntries.forEach { migrationEntry ->
            val deleted = runCatching {
                deleteInternal(context, migrationEntry.entry.reference)
            }.onFailure {
                NPLogger.w(TAG, "迁移后删除旧下载文件失败: ${migrationEntry.entry.reference}, ${it.message}")
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
    ): DownloadLibrarySnapshot {
        val cacheKey = buildSnapshotCacheKey(context)
        if (!forceRefresh) {
            snapshotCache
                ?.takeIf { it.key == cacheKey }
                ?.let { return it.snapshot }
        }

        val root = resolveRootBlocking(context)
        val rootEntries = listChildren(root).filterNot(StoredEntry::isDirectory)
        val audioEntries = rootEntries.filter { it.extension in audioExtensions }
        val metadataEntries = rootEntries.filter { it.name.endsWith(METADATA_SUFFIX) }
        val metadataByAudioName = metadataEntries.mapNotNull { entry ->
            parseDownloadedAudioMetadata(context, entry)?.let { metadata ->
                entry.name.removeSuffix(METADATA_SUFFIX) to metadata
            }
        }.toMap()
        val coverEntries = listSubdirectoryEntries(root, "Covers")
        val lyricEntries = listSubdirectoryEntries(root, "Lyrics")
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
            metadataEntriesByAudioName = metadataEntries.associateBy { entry ->
                entry.name.removeSuffix(METADATA_SUFFIX)
            },
            metadataByAudioName = metadataByAudioName,
            audioEntriesWithoutMetadata = audioEntriesWithoutMetadata,
            audioEntriesByStableKey = audioEntriesByStableKey,
            audioEntriesBySongId = audioEntriesBySongId,
            audioEntriesByMediaUri = audioEntriesByMediaUri,
            audioEntriesByRemoteTrackKey = audioEntriesByRemoteTrackKey,
            coverEntriesByName = coverEntries.associateBy(StoredEntry::name),
            lyricEntriesByName = lyricEntries.associateBy(StoredEntry::name),
            knownReferences = buildSet {
                rootEntries.forEach { add(it.reference) }
                coverEntries.forEach { add(it.reference) }
                lyricEntries.forEach { add(it.reference) }
            }
        ).also { snapshot ->
            snapshotCache = SnapshotCache(key = cacheKey, snapshot = snapshot)
        }
    }

    suspend fun findMetadataForAudio(context: Context, audio: StoredEntry): StoredEntry? = withContext(Dispatchers.IO) {
        buildDownloadLibrarySnapshotBlocking(context).metadataEntriesByAudioName[audio.name]
    }

    suspend fun saveMetadata(context: Context, audio: StoredEntry, json: String) = withContext(Dispatchers.IO) {
        val root = resolveRootBlocking(context)
        writeRootText(context, root, "${audio.name}$METADATA_SUFFIX", json)
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
        invalidateSnapshotCache()
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
            subdirectory = "Covers",
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
            mimeType = "text/plain"
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
        resolveTreeRootBlocking(context, configuredUri)?.let { return it }
        if (configuredUri != null) {
            NPLogger.w(TAG, "自定义下载目录不可用，回退默认目录: $configuredUri")
        }
        return createDefaultRoot(context)
    }

    private fun resolveRootBlocking(context: Context, directoryUriString: String?): RootHandle? {
        val normalizedUri = normalizeDirectoryUri(directoryUriString)
        return if (normalizedUri == null) {
            createDefaultRoot(context)
        } else {
            resolveTreeRootBlocking(context, normalizedUri)
        }
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
            collectManagedMigrationEntries(root, "Covers"))
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

    private fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): List<String> {
        return buildList {
            songId?.takeIf { it > 0L }?.let { resolvedSongId ->
                add(if (translated) "${resolvedSongId}_trans.lrc" else "${resolvedSongId}.lrc")
            }
            candidateBaseNames.forEach { baseName ->
                add(if (translated) "${baseName}_trans.lrc" else "$baseName.lrc")
            }
        }
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
                mediaUri = root.optString("mediaUri").takeIf(String::isNotBlank),
                channelId = root.optString("channelId").takeIf(String::isNotBlank),
                audioId = root.optString("audioId").takeIf(String::isNotBlank),
                subAudioId = root.optString("subAudioId").takeIf(String::isNotBlank),
                coverPath = root.optString("coverPath").takeIf(String::isNotBlank),
                lyricPath = root.optString("lyricPath").takeIf(String::isNotBlank),
                translatedLyricPath = root.optString("translatedLyricPath").takeIf(String::isNotBlank)
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "解析下载 metadata 失败: ${entry.name} - ${error.message}")
        }.getOrNull()
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
        val configuredUri = normalizeDirectoryUri(customDirectoryUri)
        return if (configuredUri != null) {
            "tree:$configuredUri"
        } else {
            "file:${createDefaultRoot(context).dir.absolutePath}"
        }
    }

    private fun invalidateSnapshotCache() {
        snapshotCache = null
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
        return parent.createFile(mimeType, finalName)
            ?: throw IOException("无法在下载目录创建文件: $finalName")
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
        invalidateSnapshotCache()
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
                val target = File(dir, displayName)
                target.outputStream().use { it.write(bytes) }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(root.tree, subdirectory) ?: return null
                val target = createRootFile(directory, displayName, mimeType, replace = true)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("无法写入目录文件: $displayName")
                target.toStoredEntry()
            }
        }
        storedEntry?.let { invalidateSnapshotCache() }
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
                val target = File(dir, displayName)
                target.outputStream().use { output -> input.copyTo(output) }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(root.tree, subdirectory)
                    ?: throw IOException("无法创建目录: $subdirectory")
                val target = createRootFile(directory, displayName, mimeType, replace = true)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("无法写入目录文件: $displayName")
                target.toStoredEntry()
                    ?: throw IOException("无法读取已写入的目录文件: $displayName")
            }
        }
        invalidateSnapshotCache()
        return storedEntry
    }

    private fun writeRootText(context: Context, root: RootHandle, displayName: String, content: String) {
        when (root) {
            is RootHandle.FileRoot -> {
                File(root.dir, displayName).writeText(content, Charsets.UTF_8)
            }

            is RootHandle.TreeRoot -> {
                val target = createRootFile(root.tree, displayName, "application/json", replace = true)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("无法写入元数据文件: $displayName")
            }
        }
        invalidateSnapshotCache()
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
        return uriString?.takeIf { it.isNotBlank() }
    }

    private fun resolveTreeRootBlocking(context: Context, directoryUriString: String?): RootHandle.TreeRoot? {
        val uriString = normalizeDirectoryUri(directoryUriString) ?: return null
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
            invalidateSnapshotCache()
        }
        return deleted
    }

    private fun mimeTypeFromName(name: String, fallback: String?): String {
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
            "lrc", "txt", "json" -> "text/plain"
            else -> "application/octet-stream"
        }
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
