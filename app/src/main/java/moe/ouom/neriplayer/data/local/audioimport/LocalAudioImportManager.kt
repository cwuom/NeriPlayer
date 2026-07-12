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
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ParsedManagedDownloadFileName
import moe.ouom.neriplayer.core.download.candidateManagedDownloadFileNameTemplates
import moe.ouom.neriplayer.core.download.parseManagedDownloadBaseName
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

data class LocalAudioImportResult(
    val songs: List<SongItem>,
    val failedCount: Int,
    val completed: Boolean = true
)

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
    val localFile: File? = null,
    val nearbyCoverUri: String? = null,
    val sourceStableKey: String? = null
)

private data class QuickImportedAudioInfo(
    val displayName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null
)

private data class ExternalAudioCopyInfo(
    val displayName: String?,
    val sizeBytes: Long?
)

private data class FolderScanCandidate(
    val uri: Uri
)

private data class FolderTraversalResult(
    val candidates: List<FolderScanCandidate>,
    val visitedDirectoryCount: Int,
    val failedCount: Int,
    val mode: String
)

private data class QueriedFolderChild(
    val documentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val isDirectory: Boolean
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

        lyricExtensions.forEach { extension ->
            addIfExists(File(sourceDir, "$sourceBase.$extension"), File(targetDir, "$targetBase.$extension"))
            addIfExists(
                File(File(sourceDir, "Lyrics"), "$sourceBase.$extension"),
                File(targetDir, "$targetBase.$extension")
            )
        }

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
    private const val FOLDER_SCAN_METADATA_PARALLELISM = 8
    private const val FOLDER_SCAN_DEEP_METADATA_LIMIT = 120
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
            }.onFailure {
                NPLogger.e(TAG, "Failed to stabilize external audio: $uri", it)
            }.getOrNull()

            if (stableUri == null) {
                failedCount++
                return@forEach
            }

            val song = runCatching {
                buildQuickImportedSong(context, stableUri)
            }.onFailure {
                NPLogger.e(TAG, "Failed to import stabilized external audio: $stableUri", it)
            }.getOrNull()

            if (song != null) {
                songs += song
            } else {
                failedCount++
            }
        }

        LocalAudioImportResult(
            songs = songs.distinctBy { it.identity() },
            failedCount = failedCount,
            completed = true
        )
    }

    suspend fun scanFolderSongs(context: Context, folderUri: Uri): LocalAudioImportResult = withContext(Dispatchers.IO) {
        val scanStartedAt = SystemClock.elapsedRealtime()
        NPLogger.d(TAG, "scanFolderSongs start: uri=$folderUri")
        val root = DocumentFile.fromTreeUri(context, folderUri)
        if (root == null || !root.canRead()) {
            NPLogger.w(TAG, "scanFolderSongs skipped unreadable folder: $folderUri")
            return@withContext LocalAudioImportResult(
                songs = emptyList(),
                failedCount = 1,
                completed = false
            )
        }

        val traversalStartedAt = SystemClock.elapsedRealtime()
        val traversalResult = runCatching {
            collectFolderCandidatesWithDocumentsContract(context, folderUri)
        }.onFailure {
            NPLogger.w(TAG, "scanFolderSongs fast traversal unavailable, fallback DocumentFile: ${it.message}")
        }.getOrElse {
            collectFolderCandidatesWithDocumentFile(root)
        }
        val traversalElapsedMs = SystemClock.elapsedRealtime() - traversalStartedAt
        NPLogger.d(
            TAG,
            "scanFolderSongs traversal finished: mode=${traversalResult.mode}, directories=${traversalResult.visitedDirectoryCount}, audioCandidates=${traversalResult.candidates.size}, failed=${traversalResult.failedCount}, elapsed=${traversalElapsedMs}ms"
        )

        val metadataStartedAt = SystemClock.elapsedRealtime()
        val processedCount = AtomicInteger(0)
        val slowItemCount = AtomicInteger(0)
        val useDeepMetadata = traversalResult.candidates.size <= FOLDER_SCAN_DEEP_METADATA_LIMIT
        if (!useDeepMetadata) {
            NPLogger.d(
                TAG,
                "scanFolderSongs uses quick metadata: candidates=${traversalResult.candidates.size}, deepLimit=$FOLDER_SCAN_DEEP_METADATA_LIMIT"
            )
        }
        val songs = coroutineScope {
            val scanDispatcher = Dispatchers.IO.limitedParallelism(FOLDER_SCAN_METADATA_PARALLELISM)
            traversalResult.candidates.map { candidate ->
                async(scanDispatcher) {
                    val itemStartedAt = SystemClock.elapsedRealtime()
                    runCatching {
                        if (useDeepMetadata) {
                            buildFolderScannedSong(context, candidate.uri)
                        } else {
                            buildQuickImportedSong(
                                context = context,
                                uri = candidate.uri,
                                resolveNearbyCover = false
                            )
                        }
                    }.onFailure {
                        NPLogger.w(TAG, "scanFolderSongs skipped ${candidate.uri}: ${it.message}")
                    }.also {
                        val processed = processedCount.incrementAndGet()
                        val costMs = SystemClock.elapsedRealtime() - itemStartedAt
                        if (costMs >= SLOW_SCAN_ITEM_THRESHOLD_MS) {
                            slowItemCount.incrementAndGet()
                            NPLogger.d(TAG, "scanFolderSongs slow item: cost=${costMs}ms, uri=${candidate.uri}")
                        }
                        if (processed % SCAN_PROGRESS_LOG_INTERVAL == 0 || processed == traversalResult.candidates.size) {
                            NPLogger.d(
                                TAG,
                                "scanFolderSongs metadata progress: processed=$processed/${traversalResult.candidates.size}, slowItems=${slowItemCount.get()}, elapsed=${SystemClock.elapsedRealtime() - metadataStartedAt}ms"
                            )
                        }
                    }.getOrNull()
                }
            }.awaitAll()
        }
        val failed = traversalResult.failedCount + songs.count { it == null }
        val metadataElapsedMs = SystemClock.elapsedRealtime() - metadataStartedAt
        val totalElapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
        NPLogger.d(
            TAG,
            "scanFolderSongs finished: mode=${traversalResult.mode}, songs=${songs.count { it != null }}, failed=$failed, slowItems=${slowItemCount.get()}, metadataElapsed=${metadataElapsedMs}ms, totalElapsed=${totalElapsedMs}ms"
        )

        LocalAudioImportResult(
            songs = songs.filterNotNull().distinctBy { it.identity() },
            failedCount = failed,
            completed = true
        )
    }

    /**
     * 全盘扫描设备上的本地音频（常见音乐格式）
     */
    suspend fun scanDeviceSongs(context: Context): LocalAudioImportResult = withContext(Dispatchers.IO) {
        val scanStartedAt = SystemClock.elapsedRealtime()
        NPLogger.d(TAG, "scanDeviceSongs start")
        val songs = mutableListOf<SongItem>()
        var failed = 0
        var completed = false
        var rawRowCount = 0
        var slowItemCount = 0

        val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            "_data"
        )
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
                val idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val idxDisplayName = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val idxRelativePath = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val idxData = cursor.getColumnIndex("_data")

                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val itemStartedAt = SystemClock.elapsedRealtime()
                    rawRowCount++
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

                    songs += buildQuickImportedSong(
                        seed = QuickImportedSongSeed(
                            sourceRef = contentUri.toString(),
                            displayName = displayName,
                            title = idxTitle.takeIf { it >= 0 }?.let(cursor::getString),
                            artist = idxArtist.takeIf { it >= 0 }?.let(cursor::getString),
                            album = idxAlbum.takeIf { it >= 0 }?.let(cursor::getString),
                            durationMs = duration,
                            localFile = resolvedFile,
                            nearbyCoverUri = nearbyCoverUri
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
            }
        }.onFailure {
            NPLogger.e(TAG, "scanDeviceSongs failed: ${it.message}", it)
            failed++
        }
        val totalElapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
        NPLogger.d(
            TAG,
            "scanDeviceSongs finished: rows=$rawRowCount, songs=${songs.size}, failed=$failed, slowItems=$slowItemCount, completed=$completed, totalElapsed=${totalElapsedMs}ms"
        )

        LocalAudioImportResult(
            songs = songs.distinctBy { it.identity() },
            failedCount = failed,
            completed = completed
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

        return SongItem(
            id = stableId,
            name = resolvedTitle,
            artist = resolvedArtist,
            album = resolvedAlbum,
            albumId = 0L,
            durationMs = seed.durationMs?.takeIf { it > 0L } ?: 0L,
            coverUrl = seed.nearbyCoverUri,
            mediaUri = preferredLocalMediaReference(
                localFilePath = seed.localFile?.absolutePath,
                mediaUri = seed.sourceRef
            ) ?: resolvedSource,
            originalName = resolvedTitle,
            originalArtist = resolvedArtist,
            originalCoverUrl = seed.nearbyCoverUri,
            localFileName = resolvedDisplayName.ifBlank { null },
            localFilePath = seed.localFile?.absolutePath,
            channelId = "local",
            audioId = stableId.toString(),
            sourceStableKey = seed.sourceStableKey
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
        val resolvedCoverUrl = detailedSong.coverUrl ?: quickSong.coverUrl
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
            matchedLyric = detailedSong.matchedLyric ?: quickSong.matchedLyric,
            matchedTranslatedLyric = detailedSong.matchedTranslatedLyric ?: quickSong.matchedTranslatedLyric,
            originalName = detailedSong.originalName?.takeIf { it.isNotBlank() } ?: resolvedName,
            originalArtist = detailedSong.originalArtist?.takeIf { it.isNotBlank() } ?: resolvedArtist,
            originalCoverUrl = detailedSong.originalCoverUrl ?: quickSong.originalCoverUrl ?: resolvedCoverUrl,
            originalLyric = detailedSong.originalLyric ?: quickSong.originalLyric,
            originalTranslatedLyric = detailedSong.originalTranslatedLyric
                ?: quickSong.originalTranslatedLyric,
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
        }.onFailure {
            NPLogger.w(TAG, "hydrate local metadata failed for ${song.name}: ${it.message}")
        }.getOrNull() ?: return song

        return mergeImportedSongMetadata(
            quickSong = song,
            detailedSong = LocalMediaSupport.toSongItem(details)
        )
    }

    fun hydrateLocalSongTextMetadata(context: Context, song: SongItem): SongItem {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return song
        }
        val details = runCatching {
            LocalMediaSupport.inspectMetadataOnly(context, song)
        }.onFailure {
            NPLogger.w(TAG, "hydrate local text metadata failed for ${song.name}: ${it.message}")
        }.getOrNull() ?: return song

        return mergeImportedSongMetadata(
            quickSong = song,
            detailedSong = LocalMediaSupport.toSongItem(details)
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
        return stableKey(source).take(16).toULong(16).toLong()
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
                localFile = details.filePath?.let(::File)?.takeIf(File::exists),
                nearbyCoverUri = details.coverUri,
                sourceStableKey = details.sourceStableKey
            ),
            unknownArtistLabel = context.getString(R.string.music_unknown_artist)
        )
    }

    private fun collectFolderCandidatesWithDocumentsContract(
        context: Context,
        folderUri: Uri
    ): FolderTraversalResult {
        val candidates = mutableListOf<FolderScanCandidate>()
        var failed = 0
        var visitedDirectoryCount = 0
        val pendingDirectories = ArrayDeque<Uri>().apply { add(folderUri) }

        while (pendingDirectories.isNotEmpty()) {
            val directoryUri = pendingDirectories.removeFirst()
            visitedDirectoryCount++
            val children = queryFolderChildren(context, directoryUri)
            if (children == null) {
                failed++
                error("Unable to query children for $directoryUri")
            }
            for (child in children) {
                when {
                    child.isDirectory -> pendingDirectories.add(child.documentUri)
                    child.isSupportedAudioDocument() -> candidates += FolderScanCandidate(child.documentUri)
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

    private fun collectFolderCandidatesWithDocumentFile(root: DocumentFile): FolderTraversalResult {
        val candidates = mutableListOf<FolderScanCandidate>()
        var failed = 0
        var visitedDirectoryCount = 0
        val pendingDirectories = ArrayDeque<DocumentFile>().apply { add(root) }

        while (pendingDirectories.isNotEmpty()) {
            val directory = pendingDirectories.removeFirst()
            visitedDirectoryCount++
            val children = runCatching { directory.listFiles() }
                .onFailure {
                    failed++
                    NPLogger.w(TAG, "scanFolderSongs failed to list ${directory.uri}: ${it.message}")
                }
                .getOrNull()
                ?: continue

            for (child in children) {
                when {
                    child.isDirectory -> pendingDirectories.add(child)
                    child.isFile && child.isSupportedAudioDocument() -> {
                        candidates += FolderScanCandidate(child.uri)
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

    private fun queryFolderChildren(context: Context, parentUri: Uri): List<QueriedFolderChild>? {
        val documentId = resolveDocumentId(parentUri) ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, documentId)
        return runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIndex < 0 || nameIndex < 0 || mimeTypeIndex < 0) {
                    return@use emptyList()
                }

                buildList {
                    while (cursor.moveToNext()) {
                        val childDocumentId = cursor.getString(idIndex) ?: continue
                        val childDisplayName = cursor.getString(nameIndex) ?: continue
                        val childMimeType = cursor.getString(mimeTypeIndex).orEmpty()
                        add(
                            QueriedFolderChild(
                                documentUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, childDocumentId),
                                displayName = childDisplayName,
                                mimeType = childMimeType,
                                isDirectory = childMimeType == DocumentsContract.Document.MIME_TYPE_DIR
                            )
                        )
                    }
                }
            }.orEmpty()
        }.onFailure {
            NPLogger.w(TAG, "queryFolderChildren failed for $parentUri: ${it.message}")
        }.getOrNull()
    }

    private fun resolveDocumentId(uri: Uri): String? {
        return runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
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

        return buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = uri.toString(),
                displayName = displayName,
                title = queryInfo.title,
                artist = queryInfo.artist,
                album = queryInfo.album,
                durationMs = queryInfo.durationMs,
                localFile = resolvedFile,
                nearbyCoverUri = nearbyCoverUri
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
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.MediaColumns.DISPLAY_NAME
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
        return expectedBytes != null && targetFile.length() != expectedBytes
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
            if (expectedBytes != null && copiedBytes != expectedBytes) {
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
