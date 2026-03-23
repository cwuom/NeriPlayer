package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger

internal object ManagedDownloadStorage {
    private const val TAG = "ManagedDownloadStorage"
    private const val ROOT_DIR_NAME = "NeriPlayer"
    private const val METADATA_SUFFIX = ".npmeta.json"
    private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "webm", "eac3")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    @Volatile
    private var customDirectoryUri: String? = null

    @Volatile
    private var customDirectoryLabel: String? = null

    @Volatile
    private var snapshotCache: SnapshotCache? = null

    fun initialize(context: Context) {
        // 下载目录设置由 AppContainer 统一预热并监听更新，这里保留兼容入口。
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

        val location: String
            get() = reference

        val playbackUri: String
            get() = mediaUri

        val localFile: File?
            get() = localFilePath?.let(::File)

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

    data class ManagedDownloadContents(
        val audioEntries: List<StoredEntry>,
        val coverEntriesByBaseName: Map<String, StoredEntry>
    )

    private data class ManagedMigrationEntry(
        val subdirectory: String?,
        val entry: StoredEntry
    )

    data class DownloadLibrarySnapshot(
        val audioEntries: List<StoredEntry>,
        val audioEntriesByLookupKey: Map<String, StoredEntry>,
        val metadataEntriesByAudioName: Map<String, StoredEntry>,
        val coverEntriesByName: Map<String, StoredEntry>,
        val lyricEntriesByName: Map<String, StoredEntry>,
        val knownReferences: Set<String>
    )

    private data class SnapshotCache(
        val key: String,
        val snapshot: DownloadLibrarySnapshot
    )

    private sealed interface RootHandle {
        data class FileRoot(val dir: File) : RootHandle
        data class TreeRoot(val tree: DocumentFile) : RootHandle
    }

    fun primeSettings(directoryUri: String?, directoryLabel: String?) {
        customDirectoryUri = directoryUri?.takeIf { it.isNotBlank() }
        customDirectoryLabel = directoryLabel?.takeIf { it.isNotBlank() }
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

    fun describeConfiguredDirectory(context: Context, uriString: String? = customDirectoryUri): String {
        val resolvedUri = uriString?.takeIf { it.isNotBlank() }
        if (resolvedUri.isNullOrBlank()) {
            return context.getString(R.string.settings_download_directory_default_label)
        }
        if (resolvedUri == customDirectoryUri && !customDirectoryLabel.isNullOrBlank()) {
            return customDirectoryLabel.orEmpty()
        }
        val treeUri = runCatching { Uri.parse(resolvedUri) }.getOrNull()
        val tree = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        return tree?.name?.takeIf { it.isNotBlank() }
            ?: resolvedUri
    }

    suspend fun isCustomDirectorySelected(context: Context): Boolean = withContext(Dispatchers.IO) {
        resolveRoot(context) is RootHandle.TreeRoot
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
                            replace = true,
                            input = input
                        )
                    } else {
                        writeSubdirectoryStream(
                            context = context,
                            root = targetRoot,
                            subdirectory = migrationEntry.subdirectory,
                            displayName = migrationEntry.entry.name,
                            mimeType = migrationMimeTypeFor(migrationEntry),
                            replace = true,
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
        return runBlocking(Dispatchers.IO) { findDownloadedAudio(context, song) != null }
    }

    fun buildDisplayBaseName(song: SongItem): String {
        return sanitizeManagedDownloadFileName("${song.displayArtist()} - ${song.displayName()}")
    }

    fun createWorkingFile(context: Context, fileName: String): File {
        val stagingDir = File(context.cacheDir, "download_staging").apply { mkdirs() }
        return File(stagingDir, fileName)
    }

    fun commitDownloadedAudio(
        context: Context,
        fileName: String,
        tempFile: File,
        mimeType: String?
    ): StoredEntry {
        return runBlocking(Dispatchers.IO) {
            saveAudioFromTemp(
                context = context,
                tempFile = tempFile,
                fileName = fileName,
                mimeType = mimeType
            )
        }
    }

    fun findAudio(context: Context, song: SongItem): StoredEntry? {
        return runBlocking(Dispatchers.IO) { findDownloadedAudio(context, song) }
    }

    fun peekDownloadedAudio(song: SongItem): StoredEntry? {
        return snapshotCache?.snapshot?.let { snapshot ->
            findAudioEntry(snapshot.audioEntries, song)
        }
    }

    fun peekStoredEntry(reference: String?): StoredEntry? {
        val target = reference?.takeIf { it.isNotBlank() } ?: return null
        return snapshotCache?.snapshot?.audioEntriesByLookupKey?.get(target)
    }

    fun peekCoverReference(audio: StoredEntry): String? {
        val snapshot = snapshotCache?.snapshot ?: return null
        return findIndexedEntryByNames(
            names = buildSidecarCandidateNames(
                candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
                extensions = imageExtensions
            ),
            entriesByName = snapshot.coverEntriesByName
        )?.reference
    }

    fun buildCandidateBaseNames(song: SongItem): List<String> {
        return candidateManagedDownloadBaseNames(song)
    }

    suspend fun findDownloadedAudio(context: Context, song: SongItem): StoredEntry? = withContext(Dispatchers.IO) {
        val snapshot = buildDownloadLibrarySnapshot(context)
        findAudioEntry(snapshot.audioEntries, song)
    }

    suspend fun queryStoredEntry(context: Context, reference: String?): StoredEntry? = withContext(Dispatchers.IO) {
        val target = reference?.takeIf { it.isNotBlank() } ?: return@withContext null
        buildDownloadLibrarySnapshot(context).audioEntriesByLookupKey[target]
    }

    suspend fun listDownloadedAudio(context: Context): List<StoredEntry> = withContext(Dispatchers.IO) {
        buildDownloadLibrarySnapshot(context).audioEntries
    }

    suspend fun listManagedDownloadContents(context: Context): ManagedDownloadContents = withContext(Dispatchers.IO) {
        val root = resolveRoot(context)
        val rootEntries = listChildren(root).filterNot(StoredEntry::isDirectory)
        val coverEntriesByBaseName = buildMap {
            val coverDirectory = findSubdirectory(root, "Covers")
            if (coverDirectory != null) {
                listChildren(coverDirectory)
                    .filterNot(StoredEntry::isDirectory)
                    .forEach { coverEntry ->
                        candidateManagedDownloadBaseNames(coverEntry.nameWithoutExtension).forEach { baseName ->
                            putIfAbsent(baseName, coverEntry)
                        }
                    }
            }
        }

        ManagedDownloadContents(
            audioEntries = rootEntries.filter { it.extension in audioExtensions },
            coverEntriesByBaseName = coverEntriesByBaseName
        )
    }

    suspend fun buildDownloadLibrarySnapshot(
        context: Context,
        forceRefresh: Boolean = false
    ): DownloadLibrarySnapshot = withContext(Dispatchers.IO) {
        val cacheKey = buildSnapshotCacheKey(context)
        if (!forceRefresh) {
            snapshotCache
                ?.takeIf { it.key == cacheKey }
                ?.let { return@withContext it.snapshot }
        }

        val root = resolveRoot(context)
        val rootEntries = listChildren(root).filterNot(StoredEntry::isDirectory)
        val audioEntries = rootEntries.filter { it.extension in audioExtensions }
        val metadataEntries = rootEntries.filter { it.name.endsWith(METADATA_SUFFIX) }
        val coverEntries = listSubdirectoryEntries(root, "Covers")
        val lyricEntries = listSubdirectoryEntries(root, "Lyrics")

        val snapshot = DownloadLibrarySnapshot(
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
            coverEntriesByName = coverEntries.associateBy(StoredEntry::name),
            lyricEntriesByName = lyricEntries.associateBy(StoredEntry::name),
            knownReferences = buildSet {
                rootEntries.forEach { add(it.reference) }
                coverEntries.forEach { add(it.reference) }
                lyricEntries.forEach { add(it.reference) }
            }
        )
        snapshotCache = SnapshotCache(key = cacheKey, snapshot = snapshot)
        snapshot
    }

    suspend fun findMetadataForAudio(context: Context, audio: StoredEntry): StoredEntry? = withContext(Dispatchers.IO) {
        buildDownloadLibrarySnapshot(context).metadataEntriesByAudioName[audio.name]
    }

    suspend fun saveMetadata(context: Context, audio: StoredEntry, json: String) = withContext(Dispatchers.IO) {
        val root = resolveRoot(context)
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
        val root = resolveRoot(context)
        val storedEntry = when (root) {
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
        storedEntry
    }

    suspend fun saveCoverBytes(
        context: Context,
        displayName: String,
        bytes: ByteArray,
        mimeType: String
    ): String? = withContext(Dispatchers.IO) {
        writeSubdirectoryBytes(
            context = context,
            subdirectory = "Covers",
            displayName = displayName,
            bytes = bytes,
            mimeType = mimeType,
            replace = true
        )?.reference
    }

    fun commitCoverFile(
        context: Context,
        tempFile: File,
        fileName: String,
        mimeType: String?
    ): StoredEntry? = runBlocking(Dispatchers.IO) {
        val bytes = tempFile.takeIf(File::exists)?.readBytes() ?: return@runBlocking null
        writeSubdirectoryBytes(
            context = context,
            subdirectory = "Covers",
            displayName = fileName,
            bytes = bytes,
            mimeType = mimeTypeFromName(fileName, mimeType),
            replace = true
        )
    }

    suspend fun saveLyricText(
        context: Context,
        displayName: String,
        content: String
    ): String? = withContext(Dispatchers.IO) {
        writeSubdirectoryText(
            context = context,
            subdirectory = "Lyrics",
            displayName = displayName,
            content = content,
            replace = true
        )?.reference
    }

    fun overwriteLyric(context: Context, fileName: String, content: String): String? {
        return runBlocking(Dispatchers.IO) { saveLyricText(context, fileName, content) }
    }

    fun findAudioLocation(context: Context, candidateBaseNames: List<String>): String? = runBlocking(Dispatchers.IO) {
        findAudioEntry(buildDownloadLibrarySnapshot(context).audioEntries, candidateBaseNames)?.reference
    }

    fun findCoverLocation(context: Context, candidateBaseNames: List<String>): String? = runBlocking(Dispatchers.IO) {
        val snapshot = buildDownloadLibrarySnapshot(context)
        findIndexedEntryByNames(
            names = buildSidecarCandidateNames(candidateBaseNames, imageExtensions),
            entriesByName = snapshot.coverEntriesByName
        )?.let(::entryToPublicLocation)
    }

    fun locationExists(context: Context, reference: String?): Boolean {
        return runBlocking(Dispatchers.IO) { exists(context, reference) }
    }

    fun findLyricLocation(
        context: Context,
        songId: Long,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): String? = runBlocking(Dispatchers.IO) {
        val snapshot = buildDownloadLibrarySnapshot(context)
        findIndexedEntryByNames(
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
        val fileNameById = if (songId > 0L) {
            if (translated) "${songId}_trans.lrc" else "${songId}.lrc"
        } else {
            null
        }
        val fileNameByName = if (translated) "${baseName}_trans.lrc" else "$baseName.lrc"
        fileNameById?.let { overwriteLyric(context, it, content) }
        overwriteLyric(context, fileNameByName, content)
    }

    fun readLyrics(context: Context, song: SongItem, translated: Boolean): String? {
        val reference = findLyricLocation(
            context = context,
            songId = song.id,
            candidateBaseNames = candidateManagedDownloadBaseNames(song),
            translated = translated
        ) ?: return null
        return runBlocking(Dispatchers.IO) { readText(reference = reference, context = context) }
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
        val snapshot = buildDownloadLibrarySnapshot(context)
        findIndexedEntryByNames(
            names = buildSidecarCandidateNames(
                candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
                extensions = imageExtensions
            ),
            entriesByName = snapshot.coverEntriesByName
        )?.reference
    }

    suspend fun findLyricText(
        context: Context,
        audio: StoredEntry,
        songId: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        val snapshot = buildDownloadLibrarySnapshot(context)
        findIndexedEntryByNames(
            names = buildLyricCandidateNames(
                songId = songId,
                candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
                translated = false
            ),
            entriesByName = snapshot.lyricEntriesByName
        )?.let { readTextInternal(context, it.reference) }
    }

    suspend fun findTranslatedLyricText(
        context: Context,
        audio: StoredEntry,
        songId: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        val snapshot = buildDownloadLibrarySnapshot(context)
        findIndexedEntryByNames(
            names = buildLyricCandidateNames(
                songId = songId,
                candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
                translated = true
            ),
            entriesByName = snapshot.lyricEntriesByName
        )?.let { readTextInternal(context, it.reference) }
    }

    suspend fun findManagedLyricReferences(context: Context, song: SongItem): List<String> = withContext(Dispatchers.IO) {
        val baseNames = candidateManagedDownloadBaseNames(song)
        val snapshot = buildDownloadLibrarySnapshot(context)
        (
            buildLyricCandidateNames(song.id.takeIf { it > 0L }, baseNames, translated = false) +
                buildLyricCandidateNames(song.id.takeIf { it > 0L }, baseNames, translated = true)
            )
            .mapNotNull { candidate -> snapshot.lyricEntriesByName[candidate]?.reference }
            .distinct()
    }

    private suspend fun resolveRoot(context: Context): RootHandle = withContext(Dispatchers.IO) {
        val configuredUri = normalizeDirectoryUri(customDirectoryUri)
        resolveTreeRoot(context, configuredUri)?.let { return@withContext it }
        if (configuredUri != null) {
            NPLogger.w(TAG, "自定义下载目录不可用，回退默认目录: $configuredUri")
        }
        createDefaultRoot(context)
    }

    private suspend fun resolveRoot(context: Context, directoryUriString: String?): RootHandle? = withContext(Dispatchers.IO) {
        val normalizedUri = normalizeDirectoryUri(directoryUriString)
        if (normalizedUri == null) {
            createDefaultRoot(context)
        } else {
            resolveTreeRoot(context, normalizedUri)
        }
    }

    private fun findAudioEntry(audioEntries: List<StoredEntry>, song: SongItem): StoredEntry? {
        return findAudioEntry(audioEntries, candidateManagedDownloadBaseNames(song))
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

    private fun buildSidecarCandidateNames(
        candidateBaseNames: List<String>,
        extensions: Set<String>
    ): List<String> {
        return buildList {
            candidateBaseNames.forEach { baseName ->
                extensions.forEach { extension ->
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
        replace: Boolean,
        input: InputStream
    ): StoredEntry {
        val storedEntry = when (root) {
            is RootHandle.FileRoot -> {
                val target = File(
                    root.dir,
                    if (replace) displayName else createUniqueName(existingNames(root.dir), displayName)
                )
                target.outputStream().use { output -> input.copyTo(output) }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val target = createRootFile(root.tree, displayName, mimeType, replace)
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

    private suspend fun writeSubdirectoryText(
        context: Context,
        subdirectory: String,
        displayName: String,
        content: String,
        replace: Boolean
    ): StoredEntry? = withContext(Dispatchers.IO) {
        writeSubdirectoryBytes(
            context = context,
            subdirectory = subdirectory,
            displayName = displayName,
            bytes = content.toByteArray(Charsets.UTF_8),
            mimeType = "text/plain",
            replace = replace
        )
    }

    private suspend fun writeSubdirectoryBytes(
        context: Context,
        subdirectory: String,
        displayName: String,
        bytes: ByteArray,
        mimeType: String,
        replace: Boolean
    ): StoredEntry? = withContext(Dispatchers.IO) {
        val root = resolveRoot(context)
        val storedEntry = when (root) {
            is RootHandle.FileRoot -> {
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                val target = File(dir, if (replace) displayName else createUniqueName(existingNames(dir), displayName))
                target.outputStream().use { it.write(bytes) }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(root.tree, subdirectory) ?: return@withContext null
                val target = createRootFile(directory, displayName, mimeType, replace)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("无法写入目录文件: $displayName")
                target.toStoredEntry()
            }
        }
        storedEntry?.let { invalidateSnapshotCache() }
        storedEntry
    }

    private fun writeSubdirectoryStream(
        context: Context,
        root: RootHandle,
        subdirectory: String,
        displayName: String,
        mimeType: String,
        replace: Boolean,
        input: InputStream
    ): StoredEntry {
        val storedEntry = when (root) {
            is RootHandle.FileRoot -> {
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                val target = File(
                    dir,
                    if (replace) displayName else createUniqueName(existingNames(dir), displayName)
                )
                target.outputStream().use { output -> input.copyTo(output) }
                target.toStoredEntry()
            }

            is RootHandle.TreeRoot -> {
                val directory = findOrCreateDirectory(root.tree, subdirectory)
                    ?: throw IOException("无法创建目录: $subdirectory")
                val target = createRootFile(directory, displayName, mimeType, replace)
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

    private fun findRootChild(root: RootHandle, name: String): StoredEntry? {
        return when (root) {
            is RootHandle.FileRoot -> File(root.dir, name).takeIf(File::exists)?.toStoredEntry()
            is RootHandle.TreeRoot -> root.tree.findFile(name)?.toStoredEntry()
        }
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

    private fun findSidecarByBaseNames(
        root: RootHandle,
        subdirectory: String,
        baseNames: List<String>,
        extensions: Set<String>
    ): StoredEntry? {
        val names = baseNames.flatMap { baseName -> extensions.map { ext -> "$baseName.$ext" } }
        return findSidecarByNames(root, subdirectory, names)
    }

    private fun findSidecarByNames(
        root: RootHandle,
        subdirectory: String,
        names: List<String>
    ): StoredEntry? {
        val directory = findSubdirectory(root, subdirectory) ?: return null
        val children = listChildren(directory).filterNot(StoredEntry::isDirectory)
        return names.firstNotNullOfOrNull { name ->
            children.firstOrNull { it.name == name }
        }
    }

    private fun entryToPublicLocation(entry: StoredEntry): String {
        return entry.reference
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
        val uri = runCatching { Uri.parse(entry.reference) }.getOrNull() ?: return null
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

    private fun resolveTreeRoot(context: Context, directoryUriString: String?): RootHandle.TreeRoot? {
        val uriString = normalizeDirectoryUri(directoryUriString) ?: return null
        val treeUri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
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
            else -> context.contentResolver.openInputStream(Uri.parse(reference))
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }
    }

    private fun existsInternal(context: Context, reference: String?): Boolean {
        if (reference.isNullOrBlank()) return false
        return when {
            reference.startsWith("/") -> File(reference).exists()
            else -> {
                val uri = runCatching { Uri.parse(reference) }.getOrNull() ?: return false
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
                val uri = runCatching { Uri.parse(reference) }.getOrNull() ?: return false
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
