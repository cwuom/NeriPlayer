package moe.ouom.neriplayer.data.local.audioimport

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.local.audioimport/LocalAudioImportManager
 * Updated: 2026/3/23
 */


import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadSizePolicy
import moe.ouom.neriplayer.core.download.ParsedManagedDownloadFileName
import moe.ouom.neriplayer.core.download.candidateManagedDownloadFileNameTemplates
import moe.ouom.neriplayer.core.download.parseManagedDownloadBaseName
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class LocalAudioScanPhase {
    TRAVERSING,
    BUILDING_ENTRIES,
    HYDRATING_METADATA,
    COMPLETED
}

data class LocalAudioScanProgress(
    val phase: LocalAudioScanPhase = LocalAudioScanPhase.COMPLETED,
    val processed: Int = 0,
    val total: Int = 0,
    val discoveredSongs: Int = 0,
    val visitedDirectories: Int = 0,
    val elapsedMs: Long = 0L
) {
    val fraction: Float?
        get() = total.takeIf { it > 0 }?.let {
            (processed.toFloat() / it).coerceIn(0f, 1f)
        }
}

private class LocalAudioScanProgressEmitter(
    private val startedAt: Long,
    private val onProgress: (LocalAudioScanProgress) -> Unit
) {
    private var lastReportedAt = 0L

    fun emit(
        phase: LocalAudioScanPhase,
        processed: Int,
        total: Int,
        discoveredSongs: Int,
        visitedDirectories: Int,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReportedAt < PROGRESS_REPORT_INTERVAL_MS) {
            return
        }
        lastReportedAt = now
        try {
            onProgress(
                LocalAudioScanProgress(
                    phase = phase,
                    processed = processed,
                    total = total,
                    discoveredSongs = discoveredSongs,
                    visitedDirectories = visitedDirectories,
                    elapsedMs = now - startedAt
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "scan progress callback failed: ${error.message}")
        }
    }

    private companion object {
        const val PROGRESS_REPORT_INTERVAL_MS = 50L
        const val TAG = "LocalAudioScanProgress"
    }
}

data class LocalAudioImportResult(
    val songs: List<SongItem>,
    val failedCount: Int,
    val completed: Boolean = true,
    val metadataDeferred: Boolean = false
)

internal fun <T> Result<T>.getOrRethrowCancellation(
    onFailure: (Throwable) -> Unit
): T? {
    return fold(
        onSuccess = { it },
        onFailure = { error ->
            if (error is CancellationException) throw error
            onFailure(error)
            null
        }
    )
}

internal fun shouldUseMediaStoreScanResult(result: LocalAudioImportResult?): Boolean {
    return result?.songs?.isNotEmpty() == true
}

internal fun shouldFallbackToDocumentFileAfterTraversalFailure(error: Throwable): Boolean {
    return error !is CancellationException
}

internal fun resolveMediaStoreSourceAddedAt(
    dateAddedSeconds: Long?,
    dateModifiedSeconds: Long?
): Long {
    return dateAddedSeconds.toEpochMillisOrNull()
        ?: dateModifiedSeconds.toEpochMillisOrNull()
        ?: 0L
}

internal fun resolveScannedSourceAddedAt(
    preferredTimestampMs: Long?,
    fallbackTimestampMs: Long?
): Long {
    return preferredTimestampMs?.takeIf { it > 0L }
        ?: fallbackTimestampMs?.takeIf { it > 0L }
        ?: 0L
}

private fun Long?.toEpochMillisOrNull(): Long? {
    val seconds = this?.takeIf { it > 0L } ?: return null
    return if (seconds > Long.MAX_VALUE / 1_000L) {
        Long.MAX_VALUE
    } else {
        seconds * 1_000L
    }
}

internal data class SidecarCopyPlan(
    val source: File,
    val target: File
)

internal data class QuickImportedSongSeed(
    val sourceRef: String,
    val displayName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val sourceAddedAt: Long? = null,
    val localFile: File? = null,
    val nearbyCoverUri: String? = null,
    val mediaStoreCoverUri: String? = null,
    val sourceStableKey: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val matchedRomanizedLyric: String? = null,
    val originalRomanizedLyric: String? = null
)

private data class QuickImportedAudioInfo(
    val displayName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val sourceAddedAt: Long? = null,
    val mediaStoreCoverUri: String? = null
)

private data class ExternalAudioCopyInfo(
    val displayName: String?,
    val sizeBytes: Long?
)

private data class FolderScanCandidate(
    val uri: Uri,
    val displayName: String? = null,
    val nearbyCoverUri: String? = null,
    val sourceAddedAt: Long? = null
)

private data class FolderTraversalResult(
    val candidates: List<FolderScanCandidate>,
    val visitedDirectoryCount: Int,
    val failedCount: Int,
    val mode: String
)

internal data class ExternalStorageFolderMediaStoreScope(
    val volumeName: String,
    val relativePath: String
)

private data class QueriedFolderChild(
    val documentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val lastModifiedMs: Long? = null
)

internal fun buildNearbySidecarCopyPlans(
    sourceFile: File,
    targetFile: File,
    lyricExtensions: List<String>,
    imageExtensions: List<String>,
    coverNames: List<String>
): List<SidecarCopyPlan> {
    val sourceDir = sourceFile.parentFile ?: return emptyList()
    val targetDir = targetFile.parentFile ?: return emptyList()
    val sourceBase = sourceFile.nameWithoutExtension
    val targetBase = targetFile.nameWithoutExtension
    val targetCoverDir = File(targetDir, "Covers")

    return buildList {
        fun addIfExists(source: File, target: File) {
            if (source.exists()) {
                add(SidecarCopyPlan(source = source, target = target))
            }
        }

        val nearbyLyricFiles = LocalMediaSupport.findNearbyLyricFiles(
            file = sourceFile,
            extensions = lyricExtensions
        )

        fun addSelectedLyricSidecar(source: File?) {
            source ?: return
            val suffix = source.name
                .removePrefix(sourceBase)
                .takeIf { it.startsWith('.') || it.startsWith('_') }
                ?: return
            addIfExists(source, File(targetDir, "$targetBase$suffix"))
        }

        addSelectedLyricSidecar(nearbyLyricFiles.original)
        addSelectedLyricSidecar(nearbyLyricFiles.translated)
        addSelectedLyricSidecar(nearbyLyricFiles.romanized)

        imageExtensions.forEach { extension ->
            addIfExists(File(sourceDir, "$sourceBase.$extension"), File(targetDir, "$targetBase.$extension"))
        }

        coverNames.forEach { name ->
            imageExtensions.forEach { extension ->
                addIfExists(
                    File(sourceDir, "$name.$extension"),
                    File(targetCoverDir, "$targetBase.$extension")
                )
            }
        }

        val sourceCoverDir = File(sourceDir, "Covers")
        imageExtensions.forEach { extension ->
            addIfExists(File(sourceCoverDir, "$sourceBase.$extension"), File(targetDir, "$targetBase.$extension"))
        }
    }
}

object LocalAudioImportManager {
    private const val TAG = "LocalAudioImport"
    private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
        "com.android.externalstorage.documents"
    private const val EXTERNAL_STORAGE_PRIMARY_VOLUME_ID = "primary"
    private const val SCAN_NEARBY_COVER_LIMIT = 120
    private const val SCAN_PROGRESS_LOG_INTERVAL = 200
    private const val SLOW_SCAN_ITEM_THRESHOLD_MS = 120L
    private const val MAX_EXTERNAL_IMPORT_COUNT = 500
    private const val MAX_EXTERNAL_IMPORT_BYTES = 2L * 1024L * 1024L * 1024L
    private val audioExtensions = setOf(
        "aac",
        "aif",
        "aiff",
        "alac",
        "amr",
        "ape",
        "flac",
        "m4a",
        "m4b",
        "m4p",
        "mid",
        "midi",
        "mka",
        "mp3",
        "oga",
        "ogg",
        "opus",
        "wav",
        "wma"
    )
    private val lyricExtensions = listOf("lrc", "txt")
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
    private val coverNames = listOf("cover", "folder", "front")

    suspend fun importExternalSongs(context: Context, uris: List<Uri>): LocalAudioImportResult = withContext(Dispatchers.IO) {
        val songs = mutableListOf<SongItem>()
        var failedCount = 0

        val distinctUris = uris.distinctBy { it.toString() }
        if (distinctUris.size > MAX_EXTERNAL_IMPORT_COUNT) {
            NPLogger.w(
                TAG,
                "external import clipped: count=${distinctUris.size}, limit=$MAX_EXTERNAL_IMPORT_COUNT"
            )
        }

        distinctUris.take(MAX_EXTERNAL_IMPORT_COUNT).forEach { uri ->
            val stableUri = runCatching {
                stabilizeExternalUri(context, uri)
            }.getOrRethrowCancellation {
                NPLogger.e(TAG, "Failed to stabilize external audio: $uri", it)
            }

            if (stableUri == null) {
                failedCount++
                return@forEach
            }

            val song = runCatching {
                buildQuickImportedSong(context, stableUri)
            }.getOrRethrowCancellation {
                NPLogger.e(TAG, "Failed to import stabilized external audio: $stableUri", it)
            }

            if (song != null) {
                songs += song
            } else {
                failedCount++
            }
        }

        LocalAudioImportResult(
            songs = songs.distinctBy { it.identity() },
            failedCount = failedCount,
            completed = true,
            metadataDeferred = songs.isNotEmpty()
        )
    }

    suspend fun scanFolderSongs(
        context: Context,
        folderUri: Uri,
        onProgress: (LocalAudioScanProgress) -> Unit = {}
    ): LocalAudioImportResult = withContext(Dispatchers.IO) {
        scanFolderSongsInternal(
            context = context,
            folderUri = folderUri,
            onProgress = onProgress,
            mediaStoreScan = { progress ->
                scanExternalStorageFolderWithMediaStore(context, folderUri, progress)
            }
        )
    }

    internal suspend fun scanFolderSongsWithMediaStoreResultForTest(
        context: Context,
        folderUri: Uri,
        mediaStoreResult: LocalAudioImportResult?,
        onProgress: (LocalAudioScanProgress) -> Unit = {}
    ): LocalAudioImportResult = withContext(Dispatchers.IO) {
        scanFolderSongsInternal(
            context = context,
            folderUri = folderUri,
            onProgress = onProgress,
            mediaStoreScan = { mediaStoreResult }
        )
    }

    private suspend fun scanFolderSongsInternal(
        context: Context,
        folderUri: Uri,
        onProgress: (LocalAudioScanProgress) -> Unit,
        mediaStoreScan: suspend (LocalAudioScanProgressEmitter) -> LocalAudioImportResult?
    ): LocalAudioImportResult {
        val scanStartedAt = SystemClock.elapsedRealtime()
        val progress = LocalAudioScanProgressEmitter(scanStartedAt, onProgress)
        NPLogger.d(TAG, "scanFolderSongs start: uri=$folderUri")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreResult = mediaStoreScan(progress)
            if (shouldUseMediaStoreScanResult(mediaStoreResult)) {
                return requireNotNull(mediaStoreResult)
            }
        }

        val root = DocumentFile.fromTreeUri(context, folderUri)
        if (root == null) {
            NPLogger.w(TAG, "scanFolderSongs skipped unreadable folder: $folderUri")
            return LocalAudioImportResult(
                songs = emptyList(),
                failedCount = 1,
                completed = false
            )
        }

        val traversalStartedAt = SystemClock.elapsedRealtime()
        val traversalResult = try {
            collectFolderCandidatesWithDocumentsContract(context, folderUri, progress)
        } catch (error: Exception) {
            if (!shouldFallbackToDocumentFileAfterTraversalFailure(error)) {
                throw error
            }
            NPLogger.w(
                TAG,
                "scanFolderSongs fast traversal unavailable, fallback DocumentFile: ${error.message}"
            )
            collectFolderCandidatesWithDocumentFile(root, progress)
        }
        val traversalElapsedMs = SystemClock.elapsedRealtime() - traversalStartedAt
        NPLogger.d(
            TAG,
            "scanFolderSongs traversal finished: mode=${traversalResult.mode}, directories=${traversalResult.visitedDirectoryCount}, audioCandidates=${traversalResult.candidates.size}, failed=${traversalResult.failedCount}, elapsed=${traversalElapsedMs}ms"
        )

        val candidateCount = traversalResult.candidates.size
        progress.emit(
            phase = LocalAudioScanPhase.BUILDING_ENTRIES,
            processed = 0,
            total = candidateCount,
            discoveredSongs = candidateCount,
            visitedDirectories = traversalResult.visitedDirectoryCount,
            force = true
        )
        NPLogger.d(
            TAG,
            "scanFolderSongs builds filename index: candidates=$candidateCount"
        )
        val unknownArtistLabel = context.getString(R.string.music_unknown_artist)
        val songs = buildList {
            traversalResult.candidates.forEachIndexed { index, candidate ->
                coroutineContext.ensureActive()
                runCatching {
                    buildQuickFolderScannedSong(
                        candidate = candidate,
                        unknownArtistLabel = unknownArtistLabel
                    )
                }.getOrRethrowCancellation {
                    NPLogger.w(TAG, "scanFolderSongs skipped ${candidate.uri}: ${it.message}")
                }?.let(::add)
                val processed = index + 1
                progress.emit(
                    phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                    processed = processed,
                    total = candidateCount,
                    discoveredSongs = size,
                    visitedDirectories = traversalResult.visitedDirectoryCount,
                    force = processed == candidateCount
                )
            }
        }
        val failed = traversalResult.failedCount + (candidateCount - songs.size)
        val totalElapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
        NPLogger.d(
            TAG,
            "scanFolderSongs finished: mode=${traversalResult.mode}, songs=${songs.size}, failed=$failed, metadataDeferred=${songs.isNotEmpty()}, totalElapsed=${totalElapsedMs}ms"
        )

        progress.emit(
            phase = LocalAudioScanPhase.COMPLETED,
            processed = candidateCount,
            total = candidateCount,
            discoveredSongs = songs.size,
            visitedDirectories = traversalResult.visitedDirectoryCount,
            force = true
        )

        return LocalAudioImportResult(
            songs = songs.distinctBy { it.identity() },
            failedCount = failed,
            completed = true,
            metadataDeferred = songs.isNotEmpty()
        )
    }

    private suspend fun scanExternalStorageFolderWithMediaStore(
        context: Context,
        folderUri: Uri,
        progress: LocalAudioScanProgressEmitter
    ): LocalAudioImportResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val scope = resolveExternalStorageFolderMediaStoreScope(context, folderUri) ?: return null
        val audioUri = MediaStore.Audio.Media.getContentUri(scope.volumeName)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            "_data"
        )
        val selection = if (scope.relativePath.isBlank()) {
            null
        } else {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        }
        val selectionArgs = scope.relativePath
            .takeIf(String::isNotBlank)
            ?.let { relativePath -> arrayOf("${escapeMediaStoreLikeValue(relativePath)}%") }

        return try {
            context.contentResolver.query(
                audioUri,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val totalCount = cursor.count
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val displayNameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val relativePathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val dateAddedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val dateModifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataPathIndex = cursor.getColumnIndex("_data")
                val unknownArtistLabel = context.getString(R.string.music_unknown_artist)
                val songs = ArrayList<SongItem>(totalCount.coerceAtLeast(0))
                var resolvedPathCount = 0
                var coverHitCount = 0
                var mediaStoreCoverHitCount = 0
                var slowRowCount = 0
                val startedAt = SystemClock.elapsedRealtime()

                progress.emit(
                    phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                    processed = 0,
                    total = totalCount,
                    discoveredSongs = 0,
                    visitedDirectories = 0,
                    force = true
                )
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val rowStartedAt = SystemClock.elapsedRealtime()
                    val contentUri = Uri.withAppendedPath(
                        audioUri,
                        cursor.getLong(idIndex).toString()
                    )
                    val displayName = displayNameIndex
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getString)
                        ?.takeIf(String::isNotBlank)
                        ?: contentUri.lastPathSegment
                        ?: contentUri.toString()
                    val resolvedPath = resolveScannedFilePath(
                        rawPath = dataPathIndex
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getString),
                        relativePath = relativePathIndex
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getString),
                        displayName = displayName
                    )
                    val resolvedFile = resolvedPath?.let(::File)?.takeIf(File::isFile)
                    if (resolvedFile != null) {
                        resolvedPathCount++
                    }
                    val nearbyCoverUri = LocalMediaSupport.findNearbyCover(resolvedFile)
                        ?.toURI()
                        ?.toString()
                        ?.also { coverHitCount++ }
                    val mediaStoreCoverUri = albumIdIndex
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                        ?.takeIf { it > 0L }
                        ?.let(LocalMediaSupport::mediaStoreAlbumArtUri)
                        ?.also { mediaStoreCoverHitCount++ }
                    val sourceAddedAt = resolveMediaStoreSourceAddedAt(
                        dateAddedSeconds = dateAddedIndex
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getLong),
                        dateModifiedSeconds = dateModifiedIndex
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getLong)
                    )
                    songs += buildQuickImportedSong(
                        seed = QuickImportedSongSeed(
                            sourceRef = contentUri.toString(),
                            displayName = displayName,
                            title = titleIndex
                                .takeIf { !cursor.isNull(it) }
                                ?.let(cursor::getString),
                            artist = artistIndex
                                .takeIf { !cursor.isNull(it) }
                                ?.let(cursor::getString),
                            album = albumIndex
                                .takeIf { !cursor.isNull(it) }
                                ?.let(cursor::getString),
                            durationMs = durationIndex
                                .takeIf { !cursor.isNull(it) }
                                ?.let(cursor::getLong),
                            sourceAddedAt = sourceAddedAt,
                            localFile = resolvedFile,
                            nearbyCoverUri = nearbyCoverUri,
                            mediaStoreCoverUri = mediaStoreCoverUri
                        ),
                        unknownArtistLabel = unknownArtistLabel
                    )
                    val rowElapsedMs = SystemClock.elapsedRealtime() - rowStartedAt
                    if (rowElapsedMs >= SLOW_SCAN_ITEM_THRESHOLD_MS) {
                        slowRowCount++
                        NPLogger.d(
                            TAG,
                            "scanFolderSongs MediaStore slow row: " +
                                "cost=${rowElapsedMs}ms, uri=$contentUri"
                        )
                    }
                    val processed = songs.size
                    progress.emit(
                        phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                        processed = processed,
                        total = totalCount,
                        discoveredSongs = processed,
                        visitedDirectories = 0,
                        force = processed == totalCount
                    )
                }
                progress.emit(
                    phase = LocalAudioScanPhase.COMPLETED,
                    processed = songs.size,
                    total = totalCount,
                    discoveredSongs = songs.size,
                    visitedDirectories = 0,
                    force = true
                )
                NPLogger.d(
                    TAG,
                    "scanFolderSongs MediaStore finished: volume=${scope.volumeName}, " +
                        "relativePath=${scope.relativePath}, songs=${songs.size}, " +
                        "resolvedPaths=$resolvedPathCount, coverHits=$coverHitCount, " +
                        "mediaStoreCoverHits=$mediaStoreCoverHitCount, " +
                        "slowRows=$slowRowCount, elapsed=" +
                        "${SystemClock.elapsedRealtime() - startedAt}ms"
                )
                LocalAudioImportResult(
                    songs = songs.distinctBy { it.identity() },
                    failedCount = 0,
                    completed = true,
                    metadataDeferred = songs.isNotEmpty()
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "scanFolderSongs MediaStore fast path unavailable for $folderUri: ${error.message}"
            )
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveExternalStorageFolderMediaStoreScope(
        context: Context,
        folderUri: Uri
    ): ExternalStorageFolderMediaStoreScope? {
        if (folderUri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) {
            return null
        }
        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(folderUri)
        }.getOrElse {
            runCatching { DocumentsContract.getDocumentId(folderUri) }.getOrNull()
        } ?: return null
        return parseExternalStorageFolderMediaStoreScope(
            documentId = documentId,
            knownVolumeNames = MediaStore.getExternalVolumeNames(context)
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun parseExternalStorageFolderMediaStoreScope(
        documentId: String,
        knownVolumeNames: Set<String>
    ): ExternalStorageFolderMediaStoreScope? {
        val separatorIndex = documentId.indexOf(':')
        if (separatorIndex <= 0) {
            return null
        }
        val volumeId = documentId.substring(0, separatorIndex)
            .trim()
            .takeIf(String::isNotBlank)
            ?: return null
        val volumeName = if (volumeId.equals(EXTERNAL_STORAGE_PRIMARY_VOLUME_ID, ignoreCase = true)) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            knownVolumeNames.firstOrNull { knownName ->
                knownName.equals(volumeId, ignoreCase = true)
            }
        } ?: return null
        val relativePath = documentId
            .substring(separatorIndex + 1)
            .trim()
            .trim('/')
            .takeIf(String::isNotBlank)
            ?.plus('/')
            .orEmpty()
        return ExternalStorageFolderMediaStoreScope(
            volumeName = volumeName,
            relativePath = relativePath
        )
    }

    private fun escapeMediaStoreLikeValue(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    /**
     * 全盘扫描设备上的本地音频 (常见音乐格式)
     */
    suspend fun scanDeviceSongs(
        context: Context,
        onProgress: (LocalAudioScanProgress) -> Unit = {}
    ): LocalAudioImportResult = withContext(Dispatchers.IO) {
        val scanStartedAt = SystemClock.elapsedRealtime()
        val progress = LocalAudioScanProgressEmitter(scanStartedAt, onProgress)
        NPLogger.d(TAG, "scanDeviceSongs start")
        val songs = mutableListOf<SongItem>()
        var failed = 0
        var completed = false
        var rawRowCount = 0
        var slowItemCount = 0
        var mediaStoreCoverHitCount = 0

        val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val includeRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (includeRelativePath) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add("_data")
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"

        runCatching {
            context.contentResolver.query(audioUri, projection, selection, null, null)?.use { cursor ->
                val resolveNearbyCover = cursor.count <= SCAN_NEARBY_COVER_LIMIT
                if (!resolveNearbyCover) {
                    NPLogger.d(
                        TAG,
                        "scanDeviceSongs skips nearby cover lookup: rows=${cursor.count}, coverLimit=$SCAN_NEARBY_COVER_LIMIT"
                    )
                }
                val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val idxTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val idxArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val idxAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val idxAlbumId = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val idxDisplayName = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val idxRelativePath = if (includeRelativePath) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val idxDateAdded = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val idxDateModified = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val idxData = cursor.getColumnIndex("_data")
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val itemStartedAt = SystemClock.elapsedRealtime()
                    rawRowCount++
                    progress.emit(
                        phase = LocalAudioScanPhase.BUILDING_ENTRIES,
                        processed = rawRowCount,
                        total = cursor.count,
                        discoveredSongs = songs.size,
                        visitedDirectories = 0
                    )
                    val id = cursor.getLong(idxId)
                    val duration = cursor.getLong(idxDuration)
                    val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val resolvedPath = resolveScannedFilePath(
                        rawPath = idxData.takeIf { it >= 0 }?.let(cursor::getString),
                        relativePath = idxRelativePath.takeIf { it >= 0 }?.let(cursor::getString),
                        displayName = idxDisplayName.takeIf { it >= 0 }?.let(cursor::getString)
                    )
                    val resolvedFile = resolvedPath?.let(::File)?.takeIf(File::exists)
                    val displayName = resolvedFile?.name
                        ?: idxDisplayName.takeIf { it >= 0 }?.let(cursor::getString)
                        ?: contentUri.lastPathSegment
                        ?: contentUri.toString()
                    val nearbyCoverUri = if (resolveNearbyCover) {
                        LocalMediaSupport.findNearbyCover(resolvedFile)?.toURI()?.toString()
                    } else {
                        null
                    }
                    val mediaStoreCoverUri = idxAlbumId
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                        ?.takeIf { it > 0L }
                        ?.let(LocalMediaSupport::mediaStoreAlbumArtUri)
                        ?.also { mediaStoreCoverHitCount++ }
                    val sourceAddedAt = resolveMediaStoreSourceAddedAt(
                        dateAddedSeconds = idxDateAdded
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getLong),
                        dateModifiedSeconds = idxDateModified
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getLong)
                    )

                    songs += buildQuickImportedSong(
                        seed = QuickImportedSongSeed(
                            sourceRef = contentUri.toString(),
                            displayName = displayName,
                            title = idxTitle.takeIf { it >= 0 }?.let(cursor::getString),
                            artist = idxArtist.takeIf { it >= 0 }?.let(cursor::getString),
                            album = idxAlbum.takeIf { it >= 0 }?.let(cursor::getString),
                            durationMs = duration,
                            sourceAddedAt = sourceAddedAt,
                            localFile = resolvedFile,
                            nearbyCoverUri = nearbyCoverUri,
                            mediaStoreCoverUri = mediaStoreCoverUri
                        ),
                        unknownArtistLabel = context.getString(R.string.music_unknown_artist)
                    )
                    val costMs = SystemClock.elapsedRealtime() - itemStartedAt
                    if (costMs >= SLOW_SCAN_ITEM_THRESHOLD_MS) {
                        slowItemCount++
                        NPLogger.d(TAG, "scanDeviceSongs slow item: cost=${costMs}ms, uri=$contentUri")
                    }
                    if (rawRowCount % SCAN_PROGRESS_LOG_INTERVAL == 0) {
                        NPLogger.d(
                            TAG,
                            "scanDeviceSongs progress: rows=$rawRowCount, songs=${songs.size}, slowItems=$slowItemCount, elapsed=${SystemClock.elapsedRealtime() - scanStartedAt}ms"
                        )
                    }
                }
                completed = true
                progress.emit(
                    phase = LocalAudioScanPhase.COMPLETED,
                    processed = rawRowCount,
                    total = rawRowCount,
                    discoveredSongs = songs.size,
                    visitedDirectories = 0,
                    force = true
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            NPLogger.e(TAG, "scanDeviceSongs failed: ${error.message}", error)
            failed++
        }
        val totalElapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
        NPLogger.d(
            TAG,
            "scanDeviceSongs finished: rows=$rawRowCount, songs=${songs.size}, " +
                "failed=$failed, slowItems=$slowItemCount, " +
                "mediaStoreCoverHits=$mediaStoreCoverHitCount, completed=$completed, " +
                "totalElapsed=${totalElapsedMs}ms"
        )

        LocalAudioImportResult(
            songs = songs.distinctBy { it.identity() },
            failedCount = failed,
            completed = completed,
            metadataDeferred = songs.isNotEmpty()
        )
    }

    internal fun buildQuickImportedSong(
        seed: QuickImportedSongSeed,
        unknownArtistLabel: String
    ): SongItem {
        val resolvedSource = seed.localFile?.absolutePath ?: seed.sourceRef
        val resolvedDisplayName = seed.localFile?.name ?: seed.displayName
        val fallbackTitle = resolvedDisplayName.substringBeforeLast('.').ifBlank {
            resolvedDisplayName.ifBlank {
                resolvedSource.substringAfterLast(File.separatorChar, resolvedSource)
            }
        }
        val parsedFileName = parseFileNameMetadata(resolvedDisplayName)
        val queriedTitle = seed.title
            ?.trim()
            ?.takeIf(::isReadableQuickImportedTitle)
        val resolvedParsedTitle = resolveParsedTitleFallback(
            currentTitle = queriedTitle,
            fallbackTitle = fallbackTitle,
            fileTitle = fallbackTitle,
            parsed = parsedFileName
        )
        val resolvedTitle = resolvedParsedTitle
            ?: queriedTitle
            ?: fallbackTitle
        val queriedArtist = seed.artist
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val resolvedArtist = resolveParsedArtistFallback(
            currentArtist = queriedArtist,
            fallbackArtist = unknownArtistLabel,
            parsed = parsedFileName
        ) ?: queriedArtist
            ?: unknownArtistLabel
        val resolvedAlbumSeed = resolveParsedAlbumFallback(
            currentAlbum = seed.album,
            fallbackAlbum = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            parsed = parsedFileName
        ) ?: seed.album
        val resolvedAlbum = normalizeLocalAlbumIdentity(
            album = resolvedAlbumSeed,
            usesFallbackAlbum = resolvedAlbumSeed.isNullOrBlank()
        )
        val stableId = computeStableSongId(resolvedSource)
        val sourceAddedAt = resolveScannedSourceAddedAt(
            preferredTimestampMs = seed.sourceAddedAt,
            fallbackTimestampMs = seed.localFile?.lastModified()
        )

        return SongItem(
            id = stableId,
            name = resolvedTitle,
            artist = resolvedArtist,
            album = resolvedAlbum,
            albumId = 0L,
            durationMs = seed.durationMs?.takeIf { it > 0L } ?: 0L,
            coverUrl = seed.nearbyCoverUri ?: seed.mediaStoreCoverUri,
            mediaUri = preferredLocalMediaReference(
                localFilePath = seed.localFile?.absolutePath,
                mediaUri = seed.sourceRef
            ) ?: resolvedSource,
            originalName = resolvedTitle,
            originalArtist = resolvedArtist,
            originalCoverUrl = seed.nearbyCoverUri ?: seed.mediaStoreCoverUri,
            matchedLyric = seed.matchedLyric,
            matchedTranslatedLyric = seed.matchedTranslatedLyric,
            matchedRomanizedLyric = seed.matchedRomanizedLyric,
            originalLyric = seed.originalLyric,
            originalTranslatedLyric = seed.originalTranslatedLyric,
            originalRomanizedLyric = seed.originalRomanizedLyric,
            localFileName = resolvedDisplayName.ifBlank { null },
            localFilePath = seed.localFile?.absolutePath,
            channelId = "local",
            audioId = stableId.toString(),
            sourceStableKey = seed.sourceStableKey,
            addedAt = sourceAddedAt
        )
    }

    internal fun mergeImportedSongMetadata(
        quickSong: SongItem,
        detailedSong: SongItem
    ): SongItem {
        val resolvedName = detailedSong.name
            .takeIf(::isReadableQuickImportedTitle)
            ?: quickSong.name
        val resolvedArtist = detailedSong.artist.takeIf { it.isNotBlank() } ?: quickSong.artist
        val resolvedAlbum = detailedSong.album.takeIf { it.isNotBlank() } ?: quickSong.album
        val resolvedCoverUrl = quickSong.coverUrl
            ?.takeIf { it.isNotBlank() }
            ?: detailedSong.coverUrl
        val quickLocalPath = quickSong.localFilePath?.takeIf { it.isNotBlank() }
        val detailedLocalPath = detailedSong.localFilePath?.takeIf { it.isNotBlank() }
        val resolvedLocalPath = quickLocalPath ?: detailedLocalPath
        val shouldAdoptDetailedIdentity = quickLocalPath == null && detailedLocalPath != null
        val resolvedId = if (shouldAdoptDetailedIdentity) detailedSong.id else quickSong.id
        val resolvedAudioId = if (shouldAdoptDetailedIdentity) {
            detailedSong.audioId ?: detailedSong.id.toString()
        } else {
            quickSong.audioId ?: detailedSong.audioId
        }
        val resolvedMediaUri = preferredLocalMediaReference(
            localFilePath = resolvedLocalPath,
            mediaUri = quickSong.mediaUri ?: detailedSong.mediaUri
        ) ?: quickSong.mediaUri ?: detailedSong.mediaUri
        val resolvedSourceStableKey = quickSong.sourceStableKey ?: detailedSong.sourceStableKey

        return quickSong.copy(
            id = resolvedId,
            name = resolvedName,
            artist = resolvedArtist,
            album = resolvedAlbum,
            durationMs = detailedSong.durationMs.takeIf { it > 0L } ?: quickSong.durationMs,
            coverUrl = resolvedCoverUrl,
            matchedLyric = detailedSong.matchedLyric,
            matchedTranslatedLyric = detailedSong.matchedTranslatedLyric,
            matchedRomanizedLyric = detailedSong.matchedRomanizedLyric,
            originalName = quickSong.originalName?.takeIf { it.isNotBlank() }
                ?: detailedSong.originalName?.takeIf { it.isNotBlank() }
                ?: resolvedName,
            originalArtist = quickSong.originalArtist?.takeIf { it.isNotBlank() }
                ?: detailedSong.originalArtist?.takeIf { it.isNotBlank() }
                ?: resolvedArtist,
            originalCoverUrl = quickSong.originalCoverUrl
                ?: detailedSong.originalCoverUrl
                ?: resolvedCoverUrl,
            originalLyric = quickSong.originalLyric ?: detailedSong.originalLyric,
            originalTranslatedLyric = quickSong.originalTranslatedLyric
                ?: detailedSong.originalTranslatedLyric,
            originalRomanizedLyric = quickSong.originalRomanizedLyric
                ?: detailedSong.originalRomanizedLyric,
            mediaUri = resolvedMediaUri,
            localFileName = quickSong.localFileName ?: detailedSong.localFileName,
            localFilePath = resolvedLocalPath,
            channelId = quickSong.channelId ?: detailedSong.channelId ?: "local",
            audioId = resolvedAudioId,
            sourceStableKey = resolvedSourceStableKey
        )
    }

    fun hydrateLocalSongMetadata(
        context: Context,
        song: SongItem,
        includeEmbeddedAssets: Boolean = true
    ): SongItem {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return song
        }
        val details = runCatching {
            if (includeEmbeddedAssets) {
                LocalMediaSupport.inspect(context, song)
            } else {
                LocalMediaSupport.inspectMetadataOnly(context, song)
            }
        }.getOrRethrowCancellation {
            NPLogger.w(TAG, "hydrate local metadata failed for ${song.name}: ${it.message}")
        } ?: return song

        return mergeImportedSongMetadata(
            quickSong = song,
            detailedSong = LocalMediaSupport.toSongItem(details)
        )
    }

    fun hydrateLocalSongTextMetadata(
        context: Context,
        song: SongItem,
        resolveCoverFallback: Boolean = true,
        includeEmbeddedFallback: Boolean = true
    ): SongItem {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return song
        }
        val lyricMetadata = runCatching {
            LocalMediaSupport.inspectLyricsFast(
                context = context,
                song = song,
                includeStoredFallback = false,
                includeEmbeddedFallback = includeEmbeddedFallback
            )
        }.getOrRethrowCancellation {
            NPLogger.w(TAG, "hydrate local text metadata failed for ${song.name}: ${it.message}")
        } ?: return song
        if (!lyricMetadata.sourceResolved) {
            return song
        }
        val nearbyCover = if (resolveCoverFallback && song.coverUrl.isNullOrBlank()) {
            runCatching {
                LocalMediaSupport.resolveNearbyCoverUri(context, song)
            }.getOrNull()
        } else {
            null
        }
        return song.copy(
            coverUrl = song.coverUrl ?: nearbyCover,
            originalCoverUrl = song.originalCoverUrl ?: nearbyCover,
            matchedLyric = lyricMetadata.lyric ?: song.matchedLyric,
            matchedTranslatedLyric = lyricMetadata.translatedLyric
                ?: song.matchedTranslatedLyric,
            matchedRomanizedLyric = lyricMetadata.romanizedLyric
                ?: song.matchedRomanizedLyric,
            originalLyric = lyricMetadata.lyric ?: song.originalLyric,
            originalTranslatedLyric = lyricMetadata.translatedLyric
                ?: song.originalTranslatedLyric,
            originalRomanizedLyric = lyricMetadata.romanizedLyric
                ?: song.originalRomanizedLyric,
            matchedLyricSource = if (lyricMetadata.lyric != null) {
                null
            } else {
                song.matchedLyricSource
            },
            matchedSongId = if (lyricMetadata.lyric != null) {
                null
            } else {
                song.matchedSongId
            }
        )
    }

    /**
     * 只补全已经可直接访问的本地封面，避免扫描后台读取并持久化整段歌词
     */
    fun hydrateLocalSongCoverMetadata(
        context: Context,
        song: SongItem
    ): SongItem {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return song
        }
        if (!song.coverUrl.isNullOrBlank() || !song.originalCoverUrl.isNullOrBlank()) {
            return song
        }
        val nearbyCover = runCatching {
            LocalMediaSupport.resolveNearbyCoverUri(context, song)
                ?: LocalMediaSupport.peekCachedEmbeddedCoverUri(context, song)
                ?: LocalMediaSupport.resolveCoverUri(context, song)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return song
        return song.copy(
            coverUrl = nearbyCover,
            originalCoverUrl = song.originalCoverUrl ?: nearbyCover
        )
    }

    private fun isReadableScannedTitle(title: String?): Boolean {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("content://", ignoreCase = true)) return false
        if (trimmed.startsWith("file://", ignoreCase = true)) return false
        return true
    }

    private fun isReadableQuickImportedTitle(title: String?): Boolean {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("content://", ignoreCase = true)) return false
        if (trimmed.startsWith("file://", ignoreCase = true)) return false
        return true
    }

    private fun parseFileNameMetadata(displayName: String): ParsedManagedDownloadFileName? {
        val baseName = displayName
            .substringBeforeLast('.', displayName)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null
        return candidateManagedDownloadFileNameTemplates(
            ManagedDownloadStorage.currentDownloadFileNameTemplate()
        ).asSequence()
            .mapNotNull { template -> parseManagedDownloadBaseName(baseName, template) }
            .firstOrNull { parsed ->
                !parsed.title.isNullOrBlank() ||
                    !parsed.artist.isNullOrBlank() ||
                    !parsed.album.isNullOrBlank()
            }
    }

    private fun resolveParsedTitleFallback(
        currentTitle: String?,
        fallbackTitle: String,
        fileTitle: String,
        parsed: ParsedManagedDownloadFileName?
    ): String? {
        val parsedTitle = parsed?.title?.takeIf(::isReadableScannedTitle) ?: return null
        val normalizedCurrentTitle = normalizeParsedMetadataValue(currentTitle)
        if (normalizedCurrentTitle.isBlank()) {
            return parsedTitle
        }

        val fallbackCandidates = linkedSetOf(fileTitle, fallbackTitle).apply {
            listOfNotNull(parsed.artist, parsed.title)
                .takeIf { it.size >= 2 }
                ?.joinToString(" - ")
                ?.let(::add)
            listOfNotNull(parsed.source, parsed.artist, parsed.title)
                .takeIf { it.size >= 2 }
                ?.joinToString(" - ")
                ?.let(::add)
            listOfNotNull(parsed.album, parsed.title)
                .takeIf { it.size >= 2 }
                ?.joinToString(" - ")
                ?.let(::add)
        }.map(::normalizeParsedMetadataValue)
            .filter(String::isNotBlank)
            .toSet()

        return parsedTitle.takeIf { normalizedCurrentTitle in fallbackCandidates }
    }

    private fun resolveParsedArtistFallback(
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

    private fun resolveParsedAlbumFallback(
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

    private fun normalizeParsedMetadataValue(value: String?): String {
        return value
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
    }

    private fun computeStableSongId(source: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        var stableId = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            stableId = (stableId shl 8) or (digest[index].toLong() and 0xffL)
        }
        return stableId
    }

    private fun buildFolderScannedSong(context: Context, uri: Uri): SongItem {
        val details = LocalMediaSupport.inspectForScan(context, uri)
        return buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = uri.toString(),
                displayName = details.displayName,
                title = details.title,
                artist = details.artist,
                album = details.album.takeUnless { details.usesFallbackAlbum },
                durationMs = details.durationMs,
                sourceAddedAt = details.lastModifiedMs,
                localFile = details.filePath?.let(::File)?.takeIf(File::exists),
                nearbyCoverUri = details.coverUri,
                sourceStableKey = details.sourceStableKey,
                matchedLyric = details.lyricContent,
                matchedTranslatedLyric = details.translatedLyricContent,
                originalLyric = details.lyricContent,
                originalTranslatedLyric = details.translatedLyricContent,
                matchedRomanizedLyric = details.romanizedLyricContent,
                originalRomanizedLyric = details.romanizedLyricContent
            ),
            unknownArtistLabel = context.getString(R.string.music_unknown_artist)
        )
    }

    private suspend fun collectFolderCandidatesWithDocumentsContract(
        context: Context,
        folderUri: Uri,
        progress: LocalAudioScanProgressEmitter
    ): FolderTraversalResult {
        val candidates = mutableListOf<FolderScanCandidate>()
        var failed = 0
        var visitedDirectoryCount = 0
        val pendingDirectories = ArrayDeque<Uri>().apply { add(folderUri) }

        while (pendingDirectories.isNotEmpty()) {
            coroutineContext.ensureActive()
            val directoryUri = pendingDirectories.removeFirst()
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
            val directCoverIndex = buildDocumentCoverIndex(children)
            val nestedCoverIndex = buildDocumentCoverIndex(coversChildren)
            for (child in children) {
                coroutineContext.ensureActive()
                when {
                    child.isDirectory -> pendingDirectories.add(child.documentUri)
                    child.isSupportedAudioDocument() -> {
                        val baseName = child.displayName.substringBeforeLast('.', child.displayName)
                        candidates += FolderScanCandidate(
                            uri = child.documentUri,
                            displayName = child.displayName,
                            nearbyCoverUri = findNearbyDocumentCoverReference(
                                directCoverIndex = directCoverIndex,
                                nestedCoverIndex = nestedCoverIndex,
                                baseName = baseName
                            ),
                            sourceAddedAt = child.lastModifiedMs
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
        )
    }

    private suspend fun collectFolderCandidatesWithDocumentFile(
        root: DocumentFile,
        progress: LocalAudioScanProgressEmitter
    ): FolderTraversalResult {
        val candidates = mutableListOf<FolderScanCandidate>()
        var failed = 0
        var visitedDirectoryCount = 0
        val pendingDirectories = ArrayDeque<DocumentFile>().apply { add(root) }

        while (pendingDirectories.isNotEmpty()) {
            coroutineContext.ensureActive()
            val directory = pendingDirectories.removeFirst()
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
            val directCoverIndex = buildSafCoverIndex(children.asList())
            val nestedCoverIndex = buildSafCoverIndex(coversChildren)

            for (child in children) {
                coroutineContext.ensureActive()
                when {
                    child.isDirectory -> pendingDirectories.add(child)
                    child.isFile && child.isSupportedAudioDocument() -> {
                        val displayName = child.name
                        val baseName = displayName
                            ?.substringBeforeLast('.', displayName)
                            .orEmpty()
                        candidates += FolderScanCandidate(
                            uri = child.uri,
                            displayName = displayName,
                            nearbyCoverUri = findNearbySafCoverReference(
                                directCoverIndex = directCoverIndex,
                                nestedCoverIndex = nestedCoverIndex,
                                baseName = baseName
                            ),
                            sourceAddedAt = runCatching { child.lastModified() }.getOrNull()
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
        )
    }

    private suspend fun queryFolderChildren(
        context: Context,
        parentUri: Uri
    ): List<QueriedFolderChild>? {
        val documentId = resolveDocumentId(parentUri) ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, documentId)
        val scanContext = coroutineContext
        return try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
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
                if (idIndex < 0 || nameIndex < 0 || mimeTypeIndex < 0) {
                    return@use emptyList()
                }

                buildList {
                    while (cursor.moveToNext()) {
                        scanContext.ensureActive()
                        val childDocumentId = cursor.getString(idIndex) ?: continue
                        val childDisplayName = cursor.getString(nameIndex) ?: continue
                        val childMimeType = cursor.getString(mimeTypeIndex).orEmpty()
                        add(
                            QueriedFolderChild(
                                documentUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, childDocumentId),
                                displayName = childDisplayName,
                                mimeType = childMimeType,
                                isDirectory = childMimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                                lastModifiedMs = lastModifiedIndex
                                    .takeIf { it >= 0 && !cursor.isNull(it) }
                                    ?.let(cursor::getLong)
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "queryFolderChildren failed for $parentUri: ${error.message}")
            null
        }
    }

    private fun resolveDocumentId(uri: Uri): String? {
        return runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    }

    private fun buildQuickFolderScannedSong(
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
                durationMs = null,
                sourceAddedAt = candidate.sourceAddedAt,
                nearbyCoverUri = candidate.nearbyCoverUri
            ),
            unknownArtistLabel = unknownArtistLabel
        )
    }

    private fun buildDocumentCoverIndex(
        children: Collection<QueriedFolderChild>
    ): Map<String, String> {
        return children.asSequence()
            .filterNot(QueriedFolderChild::isDirectory)
            .mapNotNull { child ->
                child.displayName
                    .takeIf(String::isNotBlank)
                    ?.lowercase()
                    ?.let { name -> name to child.documentUri.toString() }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, references) -> references.first() }
    }

    private fun buildSafCoverIndex(
        children: Collection<DocumentFile>
    ): Map<String, String> {
        return children.asSequence()
            .filter(DocumentFile::isFile)
            .mapNotNull { child ->
                child.name
                    ?.takeIf(String::isNotBlank)
                    ?.lowercase()
                    ?.let { name -> name to child.uri.toString() }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, references) -> references.first() }
    }

    private fun findNearbyDocumentCoverReference(
        directCoverIndex: Map<String, String>,
        nestedCoverIndex: Map<String, String>,
        baseName: String
    ): String? {
        fun findSpecific(index: Map<String, String>): String? {
            return imageExtensions.firstNotNullOfOrNull { extension ->
                index["$baseName.$extension".lowercase()]
            }
        }

        findSpecific(directCoverIndex)?.let { return it }
        findSpecific(nestedCoverIndex)?.let { return it }
        return coverNames.firstNotNullOfOrNull { coverName ->
            imageExtensions.firstNotNullOfOrNull { extension ->
                directCoverIndex["$coverName.$extension".lowercase()]
            }
        }
    }

    private fun findNearbySafCoverReference(
        directCoverIndex: Map<String, String>,
        nestedCoverIndex: Map<String, String>,
        baseName: String
    ): String? {
        fun findSpecific(index: Map<String, String>): String? {
            return imageExtensions.firstNotNullOfOrNull { extension ->
                index["$baseName.$extension".lowercase()]
            }
        }

        findSpecific(directCoverIndex)?.let { return it }
        findSpecific(nestedCoverIndex)?.let { return it }
        return coverNames.firstNotNullOfOrNull { coverName ->
            imageExtensions.firstNotNullOfOrNull { extension ->
                directCoverIndex["$coverName.$extension".lowercase()]
            }
        }
    }

    private fun buildQuickImportedSong(
        context: Context,
        uri: Uri,
        resolveNearbyCover: Boolean = true
    ): SongItem {
        val resolvedFile = resolveSourceFile(context, uri)
        val queryInfo = queryQuickImportedAudioInfo(context, uri)
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
                durationMs = queryInfo.durationMs,
                sourceAddedAt = queryInfo.sourceAddedAt,
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

    private fun queryQuickImportedAudioInfo(context: Context, uri: Uri): QuickImportedAudioInfo {
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
                QuickImportedAudioInfo(
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
                        dateAddedSeconds = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getLong),
                        dateModifiedSeconds = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getLong)
                    ),
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

    private fun stabilizeExternalUri(context: Context, uri: Uri): Uri {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri
        }
        if (uri.scheme.equals("content", ignoreCase = true) && uri.authority == MediaStore.AUTHORITY) {
            return uri
        }

        val resolver = context.contentResolver
        val copyInfo = queryExternalAudioCopyInfo(context, uri)
        copyInfo.sizeBytes?.takeIf { it > MAX_EXTERNAL_IMPORT_BYTES }?.let { sizeBytes ->
            error("External audio is too large: $sizeBytes bytes")
        }

        val displayName = copyInfo.displayName ?: runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) {
                    cursor.getString(column)
                } else {
                    null
                }
            }
        }.getOrNull()

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

        if (shouldCopyExternalAudio(targetFile, copyInfo.sizeBytes)) {
            copyExternalAudioToTarget(context, uri, targetFile, copyInfo.sizeBytes)
        }

        resolveSourceFile(context, uri)?.let { sourceFile ->
            copyNearbySidecars(sourceFile, targetFile)
        }
        LocalMediaSupport.copyNearbyLyricSidecars(
            context = context,
            sourceUri = uri,
            sourceDisplayName = displayName ?: targetFile.name,
            targetFile = targetFile
        )

        return Uri.fromFile(targetFile)
    }

    private fun queryExternalAudioCopyInfo(context: Context, uri: Uri): ExternalAudioCopyInfo {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                ExternalAudioCopyInfo(
                    displayName = displayNameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getString),
                    sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                        ?.takeIf { it >= 0L }
                )
            }
        }.getOrNull() ?: ExternalAudioCopyInfo(displayName = null, sizeBytes = null)
    }

    private fun shouldCopyExternalAudio(targetFile: File, expectedBytes: Long?): Boolean {
        if (!targetFile.exists()) return true
        if (!targetFile.isFile) return true
        if (targetFile.length() <= 0L) return true
        return expectedBytes != null &&
            !ManagedDownloadSizePolicy.isTransferSizeComplete(
                expectedSizeBytes = expectedBytes,
                actualSizeBytes = targetFile.length()
            )
    }

    private fun copyExternalAudioToTarget(
        context: Context,
        uri: Uri,
        targetFile: File,
        expectedBytes: Long?
    ) {
        val partialFile = File(
            targetFile.parentFile ?: error("Import target has no parent"),
            ".${targetFile.name}.${stableKey(uri.toString()).take(8)}.partial"
        )
        partialFile.delete()
        var copiedBytes = 0L
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
            if (!ManagedDownloadSizePolicy.isTransferSizeComplete(expectedBytes, copiedBytes)) {
                error("External audio copy size mismatch: expected=$expectedBytes actual=$copiedBytes")
            }
            if (targetFile.exists() && !targetFile.delete()) {
                error("Unable to replace stale import file: ${targetFile.name}")
            }
            if (!partialFile.renameTo(targetFile)) {
                error("Unable to commit imported audio file: ${targetFile.name}")
            }
        } catch (error: Throwable) {
            partialFile.delete()
            throw error
        }
    }

    private fun resolveSourceFile(context: Context, uri: Uri): File? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.let(::File)?.takeIf(File::exists)
        }

        val dataPath = runCatching {
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
        }.getOrNull()

        if (!dataPath.isNullOrBlank()) {
            return File(dataPath).takeIf(File::exists)
        }

        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                Os.readlink("/proc/self/fd/${descriptor.fd}")
                    .takeIf { it.startsWith("/") && File(it).exists() }
                    ?.let(::File)
            }
        }.getOrNull()
    }

    private fun resolveScannedFilePath(
        rawPath: String?,
        relativePath: String?,
        displayName: String?
    ): String? {
        val normalizedRawPath = rawPath
            ?.substringBefore(" (deleted)")
            ?.takeIf { it.startsWith("/") && File(it).exists() }
        if (normalizedRawPath != null) {
            return normalizedRawPath
        }

        val safeRelativePath = relativePath?.takeIf { it.isNotBlank() } ?: return null
        val safeDisplayName = displayName?.takeIf { it.isNotBlank() } ?: return null
        val reconstructed = File(Environment.getExternalStorageDirectory(), safeRelativePath)
            .resolve(safeDisplayName)
        return reconstructed.absolutePath.takeIf { reconstructed.exists() }
    }

    internal fun copyNearbySidecars(sourceFile: File, targetFile: File) {
        buildNearbySidecarCopyPlans(
            sourceFile = sourceFile,
            targetFile = targetFile,
            lyricExtensions = lyricExtensions,
            imageExtensions = imageExtensions,
            coverNames = coverNames
        ).forEach { plan ->
            copyIfExists(plan.source, plan.target)
        }
    }

    private fun copyIfExists(source: File, target: File) {
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

    private fun stableKey(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun DocumentFile.isSupportedAudioDocument(): Boolean {
        val mimeType = type?.lowercase()
        if (mimeType?.startsWith("audio/") == true) {
            return true
        }

        val extension = name
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return extension in audioExtensions
    }

    private fun QueriedFolderChild.isSupportedAudioDocument(): Boolean {
        if (mimeType.startsWith("audio/", ignoreCase = true)) {
            return true
        }

        val extension = displayName
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() }
            ?: return false
        return extension in audioExtensions
    }
}
