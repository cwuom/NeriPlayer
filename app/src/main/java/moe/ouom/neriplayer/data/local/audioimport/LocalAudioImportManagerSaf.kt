package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager.LyricSidecarKind
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ParsedManagedDownloadFileName
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalKnownSidecarReferences
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal fun LocalAudioImportManager.resolveParsedArtistFallback(
    currentArtist: String?,
    fallbackArtist: String,
    parsed: ParsedManagedDownloadFileName?
): String? {
    val parsedArtist = parsed?.artist?.takeIf { it.isNotBlank() } ?: return null
    val normalizedCurrentArtist = normalizeParsedMetadataValue(currentArtist)
    if (normalizedCurrentArtist.isBlank()) {
        return parsedArtist
    }
    if (normalizedCurrentArtist == normalizeParsedMetadataValue(parsed.source)) {
        return parsedArtist
    }
    return parsedArtist.takeIf {
        normalizedCurrentArtist == normalizeParsedMetadataValue(fallbackArtist)
    }
}

internal fun LocalAudioImportManager.resolveParsedAlbumFallback(
    currentAlbum: String?,
    fallbackAlbum: String,
    parsed: ParsedManagedDownloadFileName?
): String? {
    val parsedAlbum = parsed?.album?.takeIf { it.isNotBlank() } ?: return null
    val normalizedCurrentAlbum = normalizeParsedMetadataValue(currentAlbum)
    if (normalizedCurrentAlbum.isBlank()) {
        return parsedAlbum
    }
    return parsedAlbum.takeIf {
        normalizedCurrentAlbum == normalizeParsedMetadataValue(fallbackAlbum) ||
            normalizedCurrentAlbum == normalizeParsedMetadataValue(LocalSongSupport.LOCAL_ALBUM_IDENTITY)
    }
}

internal fun LocalAudioImportManager.normalizeParsedMetadataValue(value: String?): String {
    return value
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
}

internal fun LocalAudioImportManager.computeStableSongId(source: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
    var stableId = 0L
    for (index in 0 until Long.SIZE_BYTES) {
        stableId = (stableId shl 8) or (digest[index].toLong() and 0xffL)
    }
    return stableId
}

internal suspend fun LocalAudioImportManager.collectFolderCandidatesWithDocumentsContract(
    context: Context,
    folderUri: Uri,
    rootDisplayName: String?,
    progress: LocalAudioScanProgressEmitter,
    managedDownloadGate: ManagedDownloadCandidatePublicationGate
): FolderTraversalResult {
    val candidates = mutableListOf<FolderScanCandidate>()
    var failed = 0
    var visitedDirectoryCount = 0
    var withheldManagedCandidates = 0
    val treeDocumentId = configuredManagedDownloadTreeDocumentId()
    data class PendingDirectory(
        val uri: Uri,
        val isInsideManagedRoot: Boolean
    )
    val pendingDirectories = ArrayDeque<PendingDirectory>().apply {
        add(
            PendingDirectory(
                uri = folderUri,
                isInsideManagedRoot = isManagedDownloadDocumentDirectory(
                    context = context,
                    documentUri = folderUri,
                    treeDocumentId = treeDocumentId,
                    displayName = rootDisplayName
                )
            )
        )
    }
    var rootCoverIndex: Map<String, String> = emptyMap()
    var rootLyricsIndex: Map<String, String> = emptyMap()

    while (pendingDirectories.isNotEmpty()) {
        coroutineContext.ensureActive()
        val directory = pendingDirectories.removeFirst()
        val directoryUri = directory.uri
        visitedDirectoryCount++
        progress.emit(
            phase = LocalAudioScanPhase.TRAVERSING,
            processed = visitedDirectoryCount,
            total = 0,
            discoveredSongs = candidates.size,
            visitedDirectories = visitedDirectoryCount
        )
        val children = queryFolderChildren(context, directoryUri)
        if (children == null) {
            failed++
            error("Unable to query children for $directoryUri")
        }
        val coversChildren = children
            .firstOrNull { it.isDirectory && it.displayName.equals("Covers", ignoreCase = true) }
            ?.let { queryFolderChildren(context, it.documentUri) }
            .orEmpty()
        val lyricsChildren = children
            .firstOrNull { it.isDirectory && it.displayName.equals("Lyrics", ignoreCase = true) }
            ?.let { queryFolderChildren(context, it.documentUri) }
            .orEmpty()
        val directCoverIndex = buildDocumentCoverIndex(children)
        val nestedCoverIndex = buildDocumentCoverIndex(coversChildren)
        val directSidecarIndex = buildDocumentNameIndex(children)
        val directMetadataIndex = buildDocumentMetadataIndex(children)
        val nestedSidecarIndex = buildDocumentNameIndex(lyricsChildren)
        if (directoryUri == folderUri) {
            rootCoverIndex = nestedCoverIndex
            rootLyricsIndex = nestedSidecarIndex
        }
        for (child in children) {
            coroutineContext.ensureActive()
            when {
                child.isDirectory -> pendingDirectories.add(
                    PendingDirectory(
                        uri = child.documentUri,
                        isInsideManagedRoot = directory.isInsideManagedRoot ||
                            isManagedDownloadDocumentDirectory(
                                context = context,
                                documentUri = child.documentUri,
                                treeDocumentId = treeDocumentId,
                                displayName = child.displayName
                            )
                    )
                )
                child.isSupportedAudioDocument() -> {
                    if (!managedDownloadGate.evaluate(
                            isInsideManagedRoot = directory.isInsideManagedRoot,
                            displayName = child.displayName,
                            candidateReferences = listOf(child.documentUri.toString())
                        ).shouldPublish
                    ) {
                        withheldManagedCandidates++
                        continue
                    }
                    val baseName = child.displayName.substringBeforeLast('.', child.displayName)
                    candidates += FolderScanCandidate(
                        uri = child.documentUri,
                        displayName = child.displayName,
                        nearbyCoverUri = findNearbyDocumentCoverReference(
                            directCoverIndex = directCoverIndex,
                            nestedCoverIndex = nestedCoverIndex,
                            rootCoverIndex = rootCoverIndex,
                            baseName = baseName
                        ),
                        sourceAddedAt = child.lastModifiedMs,
                        sourceAddedAtSource = child.lastModifiedMs
                            ?.takeIf { it > 0L }
                            ?.let { "SAF_LAST_MODIFIED" },
                        sourceAddedAtConfidence = child.lastModifiedMs
                            ?.takeIf { it > 0L }
                            ?.let { "INFERRED" },
                        durationMs = child.durationMs,
                        knownSidecarReferences = resolveKnownSidecarReferences(
                            directIndex = directSidecarIndex,
                            nestedIndex = nestedSidecarIndex,
                            rootLyricsIndex = rootLyricsIndex,
                            displayName = child.displayName,
                            baseName = baseName,
                            metadataIndex = directMetadataIndex
                        )
                    )
                }
            }
        }
    }

    return FolderTraversalResult(
        candidates = candidates,
        visitedDirectoryCount = visitedDirectoryCount,
        failedCount = failed,
        mode = "documents_contract"
    ).also {
        if (withheldManagedCandidates > 0) {
            NPLogger.d(
                TAG,
                "scanFolderSongs withheld unfinalized managed candidates: " +
                    "$withheldManagedCandidates"
            )
        }
    }
}

internal suspend fun LocalAudioImportManager.collectFolderCandidatesWithDocumentFile(
    context: Context,
    root: DocumentFile,
    progress: LocalAudioScanProgressEmitter,
    managedDownloadGate: ManagedDownloadCandidatePublicationGate
): FolderTraversalResult {
    val candidates = mutableListOf<FolderScanCandidate>()
    var failed = 0
    var visitedDirectoryCount = 0
    var withheldManagedCandidates = 0
    val treeDocumentId = configuredManagedDownloadTreeDocumentId()
    data class PendingDirectory(
        val document: DocumentFile,
        val isInsideManagedRoot: Boolean
    )
    val rootIsInsideManagedRoot = isManagedDownloadDocumentDirectory(
        context = context,
        documentUri = root.uri,
        treeDocumentId = treeDocumentId,
        displayName = root.name
    )
    val pendingDirectories = ArrayDeque<PendingDirectory>().apply {
        add(
            PendingDirectory(
                document = root,
                isInsideManagedRoot = rootIsInsideManagedRoot
            )
        )
    }
    var rootCoverIndex: Map<String, String> = emptyMap()
    var rootLyricsIndex: Map<String, String> = emptyMap()

    while (pendingDirectories.isNotEmpty()) {
        coroutineContext.ensureActive()
        val pendingDirectory = pendingDirectories.removeFirst()
        val directory = pendingDirectory.document
        visitedDirectoryCount++
        progress.emit(
            phase = LocalAudioScanPhase.TRAVERSING,
            processed = visitedDirectoryCount,
            total = 0,
            discoveredSongs = candidates.size,
            visitedDirectories = visitedDirectoryCount
        )
        val children = try {
            directory.listFiles()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed++
            NPLogger.w(TAG, "scanFolderSongs failed to list ${directory.uri}: ${error.message}")
            null
        } ?: continue

        val coversChildren = children
            .firstOrNull { it.isDirectory && it.name.equals("Covers", ignoreCase = true) }
            ?.listFiles()
            ?.filter { it.isFile }
            .orEmpty()
        val lyricsChildren = children
            .firstOrNull { it.isDirectory && it.name.equals("Lyrics", ignoreCase = true) }
            ?.listFiles()
            ?.filter { it.isFile }
            .orEmpty()
        val directCoverIndex = buildSafCoverIndex(children.asList())
        val nestedCoverIndex = buildSafCoverIndex(coversChildren)
        val directSidecarIndex = buildSafDocumentNameIndex(children.asList())
        val directMetadataIndex = buildSafDocumentMetadataIndex(children.asList())
        val nestedSidecarIndex = buildSafDocumentNameIndex(lyricsChildren)
        if (directory.uri == root.uri) {
            rootCoverIndex = nestedCoverIndex
            rootLyricsIndex = nestedSidecarIndex
        }

        for (child in children) {
            coroutineContext.ensureActive()
            when {
                child.isDirectory -> pendingDirectories.add(
                    PendingDirectory(
                        document = child,
                        isInsideManagedRoot = pendingDirectory.isInsideManagedRoot ||
                            isManagedDownloadDocumentDirectory(
                                context = context,
                                documentUri = child.uri,
                                treeDocumentId = treeDocumentId,
                                displayName = child.name
                            )
                    )
                )
                child.isFile && child.isSupportedAudioDocument() -> {
                    val displayName = child.name
                    if (!managedDownloadGate.evaluate(
                            isInsideManagedRoot = pendingDirectory.isInsideManagedRoot,
                            displayName = displayName.orEmpty(),
                            candidateReferences = listOf(child.uri.toString())
                        ).shouldPublish
                    ) {
                        withheldManagedCandidates++
                        continue
                    }
                    val baseName = displayName
                        ?.substringBeforeLast('.', displayName)
                        .orEmpty()
                    val childLastModifiedMs = runCatching {
                        child.lastModified()
                    }.getOrNull()
                    candidates += FolderScanCandidate(
                        uri = child.uri,
                        displayName = displayName,
                        nearbyCoverUri = findNearbySafCoverReference(
                            directCoverIndex = directCoverIndex,
                            nestedCoverIndex = nestedCoverIndex,
                            rootCoverIndex = rootCoverIndex,
                            baseName = baseName
                        ),
                        sourceAddedAt = childLastModifiedMs,
                        sourceAddedAtSource = childLastModifiedMs
                            ?.takeIf { it > 0L }
                            ?.let { "SAF_LAST_MODIFIED" },
                        sourceAddedAtConfidence = childLastModifiedMs
                            ?.takeIf { it > 0L }
                            ?.let { "INFERRED" },
                        durationMs = null,
                        knownSidecarReferences = resolveKnownSidecarReferences(
                            directIndex = directSidecarIndex,
                            nestedIndex = nestedSidecarIndex,
                            rootLyricsIndex = rootLyricsIndex,
                            displayName = displayName.orEmpty(),
                            baseName = baseName,
                            metadataIndex = directMetadataIndex
                        )
                    )
                }
            }
        }
    }

    return FolderTraversalResult(
        candidates = candidates,
        visitedDirectoryCount = visitedDirectoryCount,
        failedCount = failed,
        mode = "document_file"
    ).also {
        if (withheldManagedCandidates > 0) {
            NPLogger.d(
                TAG,
                "scanFolderSongs withheld unfinalized managed candidates: " +
                    "$withheldManagedCandidates"
            )
        }
    }
}

internal suspend fun LocalAudioImportManager.queryFolderChildren(
    context: Context,
    parentUri: Uri
): List<QueriedFolderChild>? {
    val documentId = resolveDocumentId(parentUri) ?: return null
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, documentId)
    val scanContext = coroutineContext
    fun query(includeDuration: Boolean): List<QueriedFolderChild>? {
        val projection = buildList {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (includeDuration) {
                add(MediaStore.Audio.Media.DURATION)
            }
        }.toTypedArray()
        return try {
            context.contentResolver.query(
                childrenUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val lastModifiedIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val durationIndex = if (includeDuration) {
                cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            } else {
                -1
            }
            if (idIndex < 0 || nameIndex < 0 || mimeTypeIndex < 0) {
                return@use emptyList()
            }

            buildList {
                while (cursor.moveToNext()) {
                    scanContext.ensureActive()
                    val childDocumentId = cursor.getString(idIndex) ?: continue
                    val childDisplayName = cursor.getString(nameIndex) ?: continue
                    val childMimeType = cursor.getString(mimeTypeIndex).orEmpty()
                    val isDirectory = childMimeType ==
                        DocumentsContract.Document.MIME_TYPE_DIR
                    val extension = childDisplayName.substringAfterLast('.', "")
                        .lowercase(Locale.ROOT)
                    val isAudio = childMimeType.startsWith("audio/", ignoreCase = true) ||
                        extension in audioExtensions
                    val isSidecar = extension in lyricExtensions ||
                        extension in imageExtensions ||
                        extension == "json"
                    if (!isDirectory && !isAudio && !isSidecar) continue
                    add(
                        QueriedFolderChild(
                            documentUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, childDocumentId),
                            displayName = childDisplayName,
                            mimeType = childMimeType,
                            isDirectory = isDirectory,
                            lastModifiedMs = lastModifiedIndex
                                .takeIf { it >= 0 && !cursor.isNull(it) }
                                ?.let(cursor::getLong),
                            durationMs = durationIndex
                                .takeIf { it >= 0 && !cursor.isNull(it) }
                                ?.let(cursor::getLong)
                        )
                    )
                }
            }
            }.also { result ->
                if (result == null) {
                    NPLogger.d(
                        TAG,
                        "queryFolderChildren returned no cursor: uri=$parentUri, " +
                            "includeDuration=$includeDuration"
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(
                TAG,
                "queryFolderChildren projection failed: uri=$parentUri, " +
                    "includeDuration=$includeDuration, error=${error.message}"
            )
            null
        }
    }
    return query(includeDuration = true) ?: query(includeDuration = false)
}

internal fun LocalAudioImportManager.resolveDocumentId(uri: Uri): String? {
    return runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
}

internal fun LocalAudioImportManager.isManagedDownloadDocumentDirectory(
    context: Context,
    documentUri: Uri,
    treeDocumentId: String?,
    displayName: String?
): Boolean {
    if (ManagedDownloadStorage.isManagedDownloadRelativePath(displayName, treeDocumentId)) {
        return true
    }
    val configuredTreeDocumentId = (
        treeDocumentId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return false
        )
    val documentId = resolveDocumentId(documentUri)
    if (documentId != null && ManagedDownloadStorage.isKnownManagedDownloadDocumentId(
            documentId = documentId,
            treeDocumentId = configuredTreeDocumentId
        )
    ) {
        return true
    }
    return runCatching {
        DocumentsContract.findDocumentPath(context.contentResolver, documentUri)
            ?.path
            ?.any { pathDocumentId -> pathDocumentId == configuredTreeDocumentId } == true
    }.getOrRethrowCancellation { error ->
        NPLogger.d(TAG, "managed document directory check unavailable: ${error.message}")
    } ?: false
}

internal fun LocalAudioImportManager.buildQuickFolderScannedSong(
    candidate: FolderScanCandidate,
    unknownArtistLabel: String
): SongItem {
    return buildQuickImportedSong(
        seed = QuickImportedSongSeed(
            sourceRef = candidate.uri.toString(),
            displayName = candidate.displayName
                ?.takeIf(String::isNotBlank)
                ?: candidate.uri.lastPathSegment
                ?: candidate.uri.toString(),
            title = null,
            artist = null,
            album = null,
            durationMs = candidate.durationMs,
            sourceAddedAt = candidate.sourceAddedAt,
            sourceAddedAtSource = candidate.sourceAddedAtSource,
            sourceAddedAtConfidence = candidate.sourceAddedAtConfidence,
            nearbyCoverUri = candidate.nearbyCoverUri
        ),
        unknownArtistLabel = unknownArtistLabel
    )
}

internal fun LocalAudioImportManager.buildDocumentCoverIndex(
    children: Collection<QueriedFolderChild>
): Map<String, String> {
    val index = HashMap<String, String>(children.size.coerceAtMost(4_096))
    children.asSequence()
        .filter { child -> !child.isDirectory }
        .filter { child -> isLocalSidecarIndexCandidate(child.displayName) }
        .take(LOCAL_SIDECAR_INDEX_MAX_ENTRIES)
        .forEach { child ->
            val name = child.displayName
                .takeIf(String::isNotBlank)
                ?.lowercase(Locale.ROOT)
                ?: return@forEach
            if (imageExtensions.none { extension ->
                name.substringAfterLast('.', "").equals(extension, ignoreCase = true)
                }
            ) {
                return@forEach
            }
            index.putIfAbsent(name, child.documentUri.toString())
        }
    return index
}

internal fun LocalAudioImportManager.buildSafCoverIndex(
    children: Collection<DocumentFile>
): Map<String, String> {
    val index = HashMap<String, String>(children.size.coerceAtMost(4_096))
    children.asSequence()
        .filter(DocumentFile::isFile)
        .filter { child -> child.name?.let(::isLocalSidecarIndexCandidate) == true }
        .take(LOCAL_SIDECAR_INDEX_MAX_ENTRIES)
        .forEach { child ->
            val name = child.name
                ?.takeIf(String::isNotBlank)
                ?.lowercase(Locale.ROOT)
                ?: return@forEach
            if (imageExtensions.none { extension ->
                name.substringAfterLast('.', "").equals(extension, ignoreCase = true)
                }
            ) {
                return@forEach
            }
            index.putIfAbsent(name, child.uri.toString())
        }
    return index
}

internal fun LocalAudioImportManager.buildDocumentNameIndex(
    children: Collection<QueriedFolderChild>
): Map<String, String> {
    return children.asSequence()
        .filterNot(QueriedFolderChild::isDirectory)
        .filter { child -> isLocalSidecarIndexCandidate(child.displayName) }
        .take(LOCAL_SIDECAR_INDEX_MAX_ENTRIES)
        .map { child -> child.displayName.lowercase() to child.documentUri.toString() }
        .toMap()
}

internal fun LocalAudioImportManager.buildDocumentMetadataIndex(
    children: Collection<QueriedFolderChild>
): Map<String, String> {
    val best = HashMap<String, Pair<Int, String>>()
    children.asSequence()
        .filterNot(QueriedFolderChild::isDirectory)
        .filter { child -> isLocalSidecarIndexCandidate(child.displayName) }
        .take(LOCAL_SIDECAR_INDEX_MAX_ENTRIES)
        .forEach { child ->
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(child.displayName)
                ?: return@forEach
            val key = audioName.lowercase(Locale.ROOT)
            val ordinal = ManagedDownloadTreeNaming.metadataNameOrdinal(
                actualName = child.displayName,
                audioName = audioName
            ) ?: Int.MAX_VALUE
            val previous = best[key]
            if (previous == null || ordinal < previous.first ||
                (ordinal == previous.first && child.documentUri.toString() < previous.second)
            ) {
                best[key] = ordinal to child.documentUri.toString()
            }
        }
    return best.mapValues { (_, value) -> value.second }
}

internal fun LocalAudioImportManager.buildSafDocumentNameIndex(
    children: Collection<DocumentFile>
): Map<String, String> {
    return children.asSequence()
        .filter(DocumentFile::isFile)
        .filter { child -> child.name?.let(::isLocalSidecarIndexCandidate) == true }
        .take(LOCAL_SIDECAR_INDEX_MAX_ENTRIES)
        .mapNotNull { child ->
            child.name
                ?.takeIf(String::isNotBlank)
                ?.lowercase()
                ?.let { name -> name to child.uri.toString() }
        }
        .toMap()
}

internal fun LocalAudioImportManager.buildSafDocumentMetadataIndex(
    children: Collection<DocumentFile>
): Map<String, String> {
    val best = HashMap<String, Pair<Int, String>>()
    children.asSequence()
        .filter(DocumentFile::isFile)
        .filter { child -> child.name?.let(::isLocalSidecarIndexCandidate) == true }
        .take(LOCAL_SIDECAR_INDEX_MAX_ENTRIES)
        .forEach { child ->
            val displayName = child.name ?: return@forEach
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(displayName)
                ?: return@forEach
            val key = audioName.lowercase(Locale.ROOT)
            val ordinal = ManagedDownloadTreeNaming.metadataNameOrdinal(
                actualName = displayName,
                audioName = audioName
            ) ?: Int.MAX_VALUE
            val previous = best[key]
            if (previous == null || ordinal < previous.first ||
                (ordinal == previous.first && child.uri.toString() < previous.second)
            ) {
                best[key] = ordinal to child.uri.toString()
            }
        }
    return best.mapValues { (_, value) -> value.second }
}

internal fun LocalAudioImportManager.resolveKnownSidecarReferences(
    directIndex: Map<String, String>,
    nestedIndex: Map<String, String>,
    rootLyricsIndex: Map<String, String> = emptyMap(),
    displayName: String,
    baseName: String,
    metadataIndex: Map<String, String>? = null
): LocalKnownSidecarReferences {
    fun find(names: List<String>): String? {
        return names.firstNotNullOfOrNull { name ->
            rootLyricsIndex[name.lowercase()]
                ?: nestedIndex[name.lowercase()]
                ?: directIndex[name.lowercase()]
        }
    }

    val original = find(lyricSidecarNames(baseName, LyricSidecarKind.ORIGINAL))
    val translated = find(lyricSidecarNames(baseName, LyricSidecarKind.TRANSLATED))
    val romanized = find(lyricSidecarNames(baseName, LyricSidecarKind.ROMANIZED))
    val metadata = if (metadataIndex != null) {
        metadataIndex[displayName.lowercase(Locale.ROOT)]
    } else {
        selectMetadataSidecarReference(directIndex, displayName)
    }
    return LocalKnownSidecarReferences(
        lyrics = NearbyLyricReferences(
            original = original,
            translated = translated,
            romanized = romanized
        ),
        metadata = metadata
    )
}

internal fun LocalAudioImportManager.lyricSidecarNames(baseName: String, kind: LyricSidecarKind): List<String> {
    val prefixes = when (kind) {
        LyricSidecarKind.ORIGINAL -> listOf(baseName)
        LyricSidecarKind.TRANSLATED -> listOf("${baseName}_trans")
        LyricSidecarKind.ROMANIZED -> listOf(
            "${baseName}_roma",
            "${baseName}_romalrc",
            "${baseName}_romanized"
        )
    }
    return buildList {
        prefixes.forEach { prefix ->
            lyricExtensions.forEach { extension -> add("$prefix.$extension") }
            add("$prefix.lrc.txt")
        }
    }
}

internal fun LocalAudioImportManager.findNearbyDocumentCoverReference(
    directCoverIndex: Map<String, String>,
    nestedCoverIndex: Map<String, String>,
    rootCoverIndex: Map<String, String>,
    baseName: String
): String? {
    fun findSpecific(index: Map<String, String>): String? {
        return imageExtensions.firstNotNullOfOrNull { extension ->
            index["$baseName.$extension".lowercase()]
        }
    }

    findSpecific(directCoverIndex)?.let { return it }
    findSpecific(nestedCoverIndex)?.let { return it }
    findSpecific(rootCoverIndex)?.let { return it }
    return coverNames.firstNotNullOfOrNull { coverName ->
        imageExtensions.firstNotNullOfOrNull { extension ->
            directCoverIndex["$coverName.$extension".lowercase()]
                ?: nestedCoverIndex["$coverName.$extension".lowercase()]
                ?: rootCoverIndex["$coverName.$extension".lowercase()]
        }
    }
}

internal fun LocalAudioImportManager.findNearbySafCoverReference(
    directCoverIndex: Map<String, String>,
    nestedCoverIndex: Map<String, String>,
    rootCoverIndex: Map<String, String>,
    baseName: String
): String? {
    fun findSpecific(index: Map<String, String>): String? {
        return imageExtensions.firstNotNullOfOrNull { extension ->
            index["$baseName.$extension".lowercase()]
        }
    }

    findSpecific(directCoverIndex)?.let { return it }
    findSpecific(nestedCoverIndex)?.let { return it }
    findSpecific(rootCoverIndex)?.let { return it }
    return coverNames.firstNotNullOfOrNull { coverName ->
        imageExtensions.firstNotNullOfOrNull { extension ->
            directCoverIndex["$coverName.$extension".lowercase()]
                ?: nestedCoverIndex["$coverName.$extension".lowercase()]
                ?: rootCoverIndex["$coverName.$extension".lowercase()]
        }
    }
}

internal fun LocalAudioImportManager.buildQuickImportedSong(
    context: Context,
    uri: Uri,
    resolveNearbyCover: Boolean = true
): SongItem {
    val resolvedFile = resolveSourceFile(context, uri)
    val queryInfo = queryQuickImportedAudioInfo(context, uri)
    val filesystemCreation = resolvedFile
        ?.let(::resolveFilesystemCreationObservation)
    val durationMs = queryInfo.durationMs?.takeIf { it > 0L }
        ?: runCatching {
            LocalMediaSupport.inspectQuick(
                context = context,
                uri = uri,
                includeAudioTrackInfo = true
            ).durationMs.takeIf { it > 0L }
        }.onFailure {
            NPLogger.d(TAG, "quick duration fallback unavailable for $uri: ${it.message}")
        }.getOrNull()
    val displayName = resolvedFile?.name
        ?: queryInfo.displayName
        ?: uri.lastPathSegment
        ?: uri.toString()
    val nearbyCoverUri = if (resolveNearbyCover) {
        LocalMediaSupport.findNearbyCover(resolvedFile)?.toURI()?.toString()
    } else {
        null
    }
    val lyrics = runCatching {
        LocalMediaSupport.inspectLyricsForScan(context, uri)
    }.getOrRethrowCancellation {
        NPLogger.w(TAG, "quick local lyrics inspection failed for $uri: ${it.message}")
    }

    return buildQuickImportedSong(
        seed = QuickImportedSongSeed(
            sourceRef = uri.toString(),
            displayName = displayName,
            title = queryInfo.title,
            artist = queryInfo.artist,
            album = queryInfo.album,
            durationMs = durationMs,
            sourceAddedAt = filesystemCreation?.timestampMs ?: queryInfo.sourceAddedAt,
            sourceModifiedAtMs = queryInfo.sourceModifiedAtMs,
            sourceAddedAtSource = filesystemCreation?.let { "FILESYSTEM_BIRTH" }
                ?: queryInfo.sourceAddedAtSource,
            sourceAddedAtConfidence = filesystemCreation?.confidence
                ?: queryInfo.sourceAddedAtConfidence,
            localFile = resolvedFile,
            nearbyCoverUri = nearbyCoverUri,
            mediaStoreCoverUri = queryInfo.mediaStoreCoverUri,
            matchedLyric = lyrics?.lyric,
            matchedTranslatedLyric = lyrics?.translatedLyric,
            matchedRomanizedLyric = lyrics?.romanizedLyric,
            originalLyric = lyrics?.lyric,
            originalTranslatedLyric = lyrics?.translatedLyric,
            originalRomanizedLyric = lyrics?.romanizedLyric
        ),
        unknownArtistLabel = context.getString(R.string.music_unknown_artist)
    )
}

internal fun LocalAudioImportManager.queryQuickImportedAudioInfo(context: Context, uri: Uri): QuickImportedAudioInfo {
    if (!uri.scheme.equals("content", ignoreCase = true)) {
        return QuickImportedAudioInfo()
    }

    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use QuickImportedAudioInfo()
            }
            val dateAddedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val dateModifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val dateAddedSeconds = dateAddedIndex
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
            val dateModifiedSeconds = dateModifiedIndex
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
            val sourceAddedAtSource = when {
                dateAddedSeconds?.let { it > 0L } == true -> "MEDIASTORE_DATE_ADDED"
                dateModifiedSeconds?.let { it > 0L } == true -> "MEDIASTORE_DATE_MODIFIED"
                else -> "UNKNOWN"
            }
            QuickImportedAudioInfo(
                sourceModifiedAtMs = dateModifiedSeconds.toEpochMillisOrNull(),
                title = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString),
                artist = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString),
                album = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString),
                durationMs = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong),
                sourceAddedAt = resolveMediaStoreSourceAddedAt(
                    dateAddedSeconds = dateAddedSeconds,
                    dateModifiedSeconds = dateModifiedSeconds
                ),
                sourceAddedAtSource = sourceAddedAtSource,
                sourceAddedAtConfidence = when (sourceAddedAtSource) {
                    "MEDIASTORE_DATE_ADDED" -> "PROVIDER_REPORTED"
                    "MEDIASTORE_DATE_MODIFIED" -> "INFERRED"
                    else -> "UNKNOWN"
                },
                mediaStoreCoverUri = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                    ?.takeIf { it > 0L }
                    ?.let(LocalMediaSupport::mediaStoreAlbumArtUri),
                displayName = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
            )
        } ?: QuickImportedAudioInfo()
    }.getOrElse {
        NPLogger.w(TAG, "Quick metadata query failed for $uri: ${it.message}")
        QuickImportedAudioInfo()
    }
}

internal fun LocalAudioImportManager.stabilizeExternalUri(
    context: Context,
    uri: Uri,
    sourceFile: File? = null,
    copyInfo: ExternalAudioCopyInfo? = null
): StabilizedExternalAudio {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        return StabilizedExternalAudio(uri)
    }
    if (uri.scheme.equals("content", ignoreCase = true) && uri.authority == MediaStore.AUTHORITY) {
        return StabilizedExternalAudio(uri)
    }

    val resolver = context.contentResolver
    val resolvedSourceFile = sourceFile ?: resolveSourceFile(context, uri)
    val resolvedCopyInfo = copyInfo
        ?: queryExternalAudioCopyInfo(context, uri, resolvedSourceFile)
    resolvedCopyInfo.sizeBytes?.takeIf { it > MAX_EXTERNAL_IMPORT_BYTES }?.let { sizeBytes ->
        error("External audio is too large: $sizeBytes bytes")
    }

    val displayName = resolvedCopyInfo.displayName ?: try {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) {
                cursor.getString(column)
            } else {
                null
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.d(TAG, "读取外部音频显示名失败: uri=$uri, error=${error.message}")
        null
    }

    val extension = displayName
        ?.substringAfterLast('.', "")
        ?.takeIf { it.isNotBlank() }
        ?: resolver.getType(uri)
            ?.substringAfterLast('/')
            ?.substringAfter('+')
            ?.takeIf { it.isNotBlank() }
        ?: "audio"

    val baseName = displayName
        ?.substringBeforeLast('.', displayName)
        ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        ?.trim()
        ?.ifBlank { null }
        ?: stableKey(uri.toString()).take(16)

    val importsDir = File(LocalMediaSupport.downloadDirectory(context), "Imports").apply { mkdirs() }
    val targetFile = File(
        importsDir,
        "${baseName.take(48)}_${stableKey(uri.toString()).take(12)}.$extension"
    )

    if (shouldCopyExternalAudio(targetFile, resolvedCopyInfo.sizeBytes)) {
        copyExternalAudioToTarget(context, uri, targetFile, resolvedCopyInfo.sizeBytes)
    }
    resolvedCopyInfo.sourceLastModifiedAt
        ?.takeIf { it > 0L }
        ?.let { sourceLastModifiedAt ->
            runCatching {
                if (!targetFile.setLastModified(sourceLastModifiedAt)) {
                    throw IOException("无法保留导入文件修改时间: ${targetFile.name}")
                }
            }
                .onFailure { error ->
                    NPLogger.d(
                        TAG,
                        "无法保留导入文件 mtime: ${targetFile.name}, " +
                            "error=${error.message}"
                    )
                }
        }

    resolvedSourceFile?.let { sourceFile ->
        copyNearbySidecars(sourceFile, targetFile)
    }
    LocalMediaSupport.copyNearbyLyricSidecars(
        context = context,
        sourceUri = uri,
        sourceDisplayName = displayName ?: targetFile.name,
        targetFile = targetFile
    )

    return StabilizedExternalAudio(
        uri = Uri.fromFile(targetFile),
        sourceAddedAt = resolvedCopyInfo.sourceAddedAt,
        sourceModifiedAtMs = resolvedCopyInfo.sourceLastModifiedAt,
        sourceAddedAtSource = resolvedCopyInfo.sourceAddedAtSource,
        sourceAddedAtConfidence = resolvedCopyInfo.sourceAddedAtConfidence
    )
}

internal fun LocalAudioImportManager.queryExternalAudioCopyInfo(
    context: Context,
    uri: Uri,
    sourceFile: File? = null
): ExternalAudioCopyInfo {
    val baseInfo = queryExternalAudioBaseInfo(context, uri)
    val filesystemCreation = sourceFile?.let(::resolveFilesystemCreationObservation)
    val filesystemModified = sourceFile?.lastModified()
        ?.toValidTimestampMsOrNull()
    val providerTimestamps = when {
        isMediaStoreAuthority(uri.authority) -> {
            queryExternalMediaStoreTimestamps(context, uri)
        }
        sourceFile == null -> queryExternalDocumentTimestamp(context, uri)
        else -> null
    }
    val sourceAddedAt = filesystemCreation?.timestampMs
        ?: providerTimestamps?.dateAddedMs
        ?: providerTimestamps?.dateModifiedMs
        ?: providerTimestamps?.documentLastModifiedMs
        ?: filesystemModified
    val sourceAddedAtSource = when {
        filesystemCreation != null -> "FILESYSTEM_BIRTH"
        providerTimestamps?.dateAddedMs != null -> "MEDIASTORE_DATE_ADDED"
        providerTimestamps?.dateModifiedMs != null -> "MEDIASTORE_DATE_MODIFIED"
        providerTimestamps?.documentLastModifiedMs != null -> "SAF_LAST_MODIFIED"
        filesystemModified != null -> "MTIME_FALLBACK"
        else -> null
    }
    val sourceAddedAtConfidence = when {
        filesystemCreation != null -> filesystemCreation.confidence
        providerTimestamps?.dateAddedMs != null -> "PROVIDER_REPORTED"
        providerTimestamps?.dateModifiedMs != null ||
            providerTimestamps?.documentLastModifiedMs != null ||
            filesystemModified != null -> "INFERRED"
        else -> null
    }
    return ExternalAudioCopyInfo(
        displayName = baseInfo?.displayName,
        sizeBytes = baseInfo?.sizeBytes,
        sourceAddedAt = sourceAddedAt,
        sourceAddedAtSource = sourceAddedAtSource,
        sourceAddedAtConfidence = sourceAddedAtConfidence,
        sourceLastModifiedAt = filesystemModified
            ?: providerTimestamps?.dateModifiedMs
            ?: providerTimestamps?.documentLastModifiedMs
    )
}

internal fun LocalAudioImportManager.queryExternalAudioBaseInfo(
    context: Context,
    uri: Uri
): ExternalAudioBaseInfo? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            ExternalAudioBaseInfo(
                displayName = displayNameIndex
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString),
                sizeBytes = sizeIndex
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                    ?.takeIf { it >= 0L }
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.d(TAG, "读取外部音频基础信息失败: uri=$uri, error=${error.message}")
        null
    }
}

internal fun LocalAudioImportManager.queryExternalMediaStoreTimestamps(
    context: Context,
    uri: Uri
): ExternalProviderTimestampInfo? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val dateAdded = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
                .toEpochMillisOrNull()
            val dateModified = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
                .toEpochMillisOrNull()
            ExternalProviderTimestampInfo(
                dateAddedMs = dateAdded,
                dateModifiedMs = dateModified
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.d(TAG, "读取 MediaStore 时间失败: uri=$uri, error=${error.message}")
        null
    }
}

internal fun LocalAudioImportManager.queryExternalDocumentTimestamp(
    context: Context,
    uri: Uri
): ExternalProviderTimestampInfo? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val lastModified = cursor
                .getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
                .toValidTimestampMsOrNull()
            ExternalProviderTimestampInfo(documentLastModifiedMs = lastModified)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.d(TAG, "读取 SAF 时间失败: uri=$uri, error=${error.message}")
        null
    }
}

internal fun LocalAudioImportManager.isMediaStoreAuthority(authority: String?): Boolean {
    return authority == MediaStore.AUTHORITY ||
        authority == "com.android.providers.media.documents"
}

internal fun LocalAudioImportManager.shouldCopyExternalAudio(targetFile: File, expectedBytes: Long?): Boolean {
    if (!targetFile.exists()) return true
    if (!targetFile.isFile) return true
    if (targetFile.length() <= 0L) return true
    return expectedBytes != null && targetFile.length() != expectedBytes
}

internal fun LocalAudioImportManager.isExternalAudioCopySizeComplete(
    expectedBytes: Long?,
    copiedBytes: Long
): Boolean = copiedBytes > 0L && (expectedBytes == null || copiedBytes == expectedBytes)

internal fun LocalAudioImportManager.copyExternalAudioToTarget(
    context: Context,
    uri: Uri,
    targetFile: File,
    expectedBytes: Long?
) {
    val partialFile = File(
        targetFile.parentFile ?: error("Import target has no parent"),
        ".${targetFile.name}.${stableKey(uri.toString()).take(8)}.partial"
    )
    val backupFile = File(
        targetFile.parentFile ?: error("Import target has no parent"),
        ".${targetFile.name}.${stableKey(uri.toString()).take(8)}.backup"
    )
    partialFile.delete()
    if (!targetFile.exists() && backupFile.isFile) {
        if (backupFile.renameTo(targetFile) &&
            !shouldCopyExternalAudio(targetFile, expectedBytes)
        ) {
            return
        }
    }
    var copiedBytes = 0L
    var stagedTargetFile: File? = null
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            partialFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    copiedBytes += read
                    if (copiedBytes > MAX_EXTERNAL_IMPORT_BYTES) {
                        error("External audio exceeds import limit")
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        } ?: error("Unable to open external audio stream")
        if (!isExternalAudioCopySizeComplete(expectedBytes, copiedBytes)) {
            error("External audio copy size mismatch: expected=$expectedBytes actual=$copiedBytes")
        }
        val hadExistingTarget = targetFile.exists()
        if (hadExistingTarget) {
            val stageFile = if (!backupFile.exists()) {
                backupFile
            } else {
                File(
                    targetFile.parentFile ?: error("Import target has no parent"),
                    ".${targetFile.name}.${stableKey(uri.toString()).take(8)}." +
                        "${UUID.randomUUID()}.stale"
                )
            }
            if (!targetFile.renameTo(stageFile)) {
                error("Unable to stage stale import file: ${targetFile.name}")
            }
            stagedTargetFile = stageFile
        }
        if (!partialFile.renameTo(targetFile)) {
            error("Unable to commit imported audio file: ${targetFile.name}")
        }
        stagedTargetFile?.let { stagedFile ->
            if (stagedFile.exists() && !stagedFile.delete()) {
                NPLogger.d(TAG, "无法删除外部导入备份文件: ${stagedFile.name}")
            }
        }
    } catch (error: CancellationException) {
        partialFile.delete()
        stagedTargetFile?.let { stagedFile ->
            if (!targetFile.exists() && stagedFile.isFile) {
                stagedFile.renameTo(targetFile)
            }
        }
        if (!targetFile.exists() && backupFile.isFile) {
            backupFile.renameTo(targetFile)
        }
        throw error
    } catch (error: Throwable) {
        partialFile.delete()
        stagedTargetFile?.let { stagedFile ->
            if (!targetFile.exists() && stagedFile.isFile) {
                stagedFile.renameTo(targetFile)
            }
        }
        if (!targetFile.exists() && backupFile.isFile) {
            backupFile.renameTo(targetFile)
        }
        throw error
    }
}

internal fun LocalAudioImportManager.resolveSourceFile(context: Context, uri: Uri): File? {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        return uri.path?.let(::File)?.takeIf(File::isFile)
    }

    val dataPath = try {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA, "_data"),
            null,
            null,
            null
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                .takeIf { it >= 0 }
                ?: cursor.getColumnIndex("_data").takeIf { it >= 0 }
            if (dataColumn != null && cursor.moveToFirst()) {
                cursor.getString(dataColumn)
            } else {
                null
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    if (!dataPath.isNullOrBlank()) {
        return File(dataPath).takeIf(File::isFile)
    }

    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            Os.readlink("/proc/self/fd/${descriptor.fd}")
                .takeIf { it.startsWith("/") && File(it).isFile }
                ?.let(::File)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }
}

internal fun LocalAudioImportManager.resolveScannedFilePath(
    rawPath: String?,
    relativePath: String?,
    displayName: String?
): String? {
    val normalizedRawPath = rawPath
        ?.substringBefore(" (deleted)")
        ?.takeIf { it.startsWith("/") && File(it).isFile }
    if (normalizedRawPath != null) {
        return normalizedRawPath
    }

    val safeRelativePath = relativePath?.takeIf { it.isNotBlank() } ?: return null
    val safeDisplayName = displayName?.takeIf { it.isNotBlank() } ?: return null
    val reconstructed = File(Environment.getExternalStorageDirectory(), safeRelativePath)
        .resolve(safeDisplayName)
    return reconstructed.absolutePath.takeIf { reconstructed.isFile }
}

internal fun LocalAudioImportManager.probeReadableContentReference(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            // MediaStore 存在性探测不读取音频内容，避免大曲库产生逐首读放大
            descriptor.length != 0L
        } == true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        false
    }
}

internal fun LocalAudioImportManager.copyIfExists(source: File, target: File) {
    if (!source.exists() || target.exists()) {
        return
    }
    runCatching {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = false)
    }.onFailure {
        NPLogger.w(TAG, "Failed to copy sidecar ${source.absolutePath}: ${it.message}")
    }
}

internal fun LocalAudioImportManager.stableKey(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
