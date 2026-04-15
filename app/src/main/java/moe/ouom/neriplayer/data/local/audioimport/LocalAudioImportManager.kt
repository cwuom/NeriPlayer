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
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
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
    val nearbyCoverUri: String? = null
)

private data class QuickImportedAudioInfo(
    val displayName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null
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
    private val lyricExtensions = listOf("lrc", "txt")
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
    private val coverNames = listOf("cover", "folder", "front")

    suspend fun importExternalSongs(context: Context, uris: List<Uri>): LocalAudioImportResult = withContext(Dispatchers.IO) {
        val songs = mutableListOf<SongItem>()
        var failedCount = 0

        uris.distinctBy { it.toString() }.forEach { uri ->
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

    /**
     * 全盘扫描设备上的本地音频（常见音乐格式）
     */
    suspend fun scanDeviceSongs(context: Context): LocalAudioImportResult = withContext(Dispatchers.IO) {
        val songs = mutableListOf<SongItem>()
        var failed = 0
        var completed = false

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
                    val id = cursor.getLong(idxId)
                    val duration = cursor.getLong(idxDuration)
                    val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val resolvedPath = resolveScannedFilePath(
                        rawPath = idxData.takeIf { it >= 0 }?.let(cursor::getString),
                        relativePath = idxRelativePath.takeIf { it >= 0 }?.let(cursor::getString),
                        displayName = idxDisplayName.takeIf { it >= 0 }?.let(cursor::getString)
                    ) ?: resolveSourceFile(context, contentUri)?.absolutePath
                    val resolvedFile = resolvedPath?.let(::File)?.takeIf(File::exists)
                    if (resolvedFile == null) {
                        failed++
                        NPLogger.w(TAG, "scanDeviceSongs skipped unreadable media: $contentUri")
                        continue
                    }

                    val fileName = resolvedFile.name
                    val fileTitle = resolvedFile.nameWithoutExtension.ifBlank {
                        fileName.substringBeforeLast('.', fileName)
                    }
                    val fallbackArtist = cursor.getString(idxArtist)
                        .orEmpty()
                        .ifBlank { context.getString(R.string.music_unknown_artist) }
                    val fallbackAlbum = cursor.getString(idxAlbum)
                        .orEmpty()
                        .ifBlank { LocalSongSupport.LOCAL_ALBUM_IDENTITY }
                    val fallbackTitle = cursor.getString(idxTitle)
                        .orEmpty()
                        .takeIf(::isReadableScannedTitle)
                        ?: fileTitle
                    val source = resolvedFile.absolutePath
                    val playbackRef = preferredLocalMediaReference(
                        localFilePath = source,
                        mediaUri = contentUri.toString()
                    ) ?: source
                    val fallbackSong = SongItem(
                        id = computeStableSongId(source),
                        name = fallbackTitle,
                        artist = fallbackArtist,
                        album = fallbackAlbum,
                        albumId = 0L,
                        durationMs = duration,
                        coverUrl = null,
                        mediaUri = playbackRef,
                        originalName = fallbackTitle,
                        originalArtist = fallbackArtist,
                        originalCoverUrl = null,
                        localFileName = fileName,
                        localFilePath = source,
                        channelId = "local",
                        audioId = computeStableSongId(source).toString()
                    )

                    songs += runCatching {
                        normalizeScannedSong(
                            baseSong = LocalMediaSupport.toSongItem(
                                LocalMediaSupport.inspect(context, contentUri)
                            ),
                            resolvedFile = resolvedFile,
                            playbackRef = playbackRef,
                            fallbackTitle = fallbackTitle,
                            fallbackArtist = fallbackArtist,
                            fallbackAlbum = fallbackAlbum,
                            fallbackDurationMs = duration
                        )
                    }.getOrElse {
                        NPLogger.w(TAG, "scanDeviceSongs metadata fallback for $contentUri: ${it.message}")
                        normalizeScannedSong(
                            baseSong = fallbackSong,
                            resolvedFile = resolvedFile,
                            playbackRef = playbackRef,
                            fallbackTitle = fallbackTitle,
                            fallbackArtist = fallbackArtist,
                            fallbackAlbum = fallbackAlbum,
                            fallbackDurationMs = duration
                        )
                    }
                }
                completed = true
            }
        }.onFailure {
            NPLogger.e(TAG, "scanDeviceSongs failed: ${it.message}", it)
            failed++
        }

        LocalAudioImportResult(
            songs = songs.distinctBy { it.identity() },
            failedCount = failed,
            completed = completed
        )
    }

    private fun normalizeScannedSong(
        baseSong: SongItem,
        resolvedFile: File,
        playbackRef: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDurationMs: Long
    ): SongItem {
        val source = resolvedFile.absolutePath
        val fileName = resolvedFile.name
        val fileTitle = resolvedFile.nameWithoutExtension.ifBlank {
            fileName.substringBeforeLast('.', fileName)
        }
        val parsedFileName = parseFileNameMetadata(fileName)
        val resolvedParsedTitle = resolveParsedTitleFallback(
            currentTitle = baseSong.name,
            fallbackTitle = fallbackTitle,
            fileTitle = fileTitle,
            parsed = parsedFileName
        )
        val safeTitle = resolvedParsedTitle ?: baseSong.name
            .takeIf(::isReadableScannedTitle)
            ?: fallbackTitle.takeIf(::isReadableScannedTitle)
            ?: fileTitle
        val safeArtist = resolveParsedArtistFallback(
            currentArtist = baseSong.artist,
            fallbackArtist = fallbackArtist,
            parsed = parsedFileName
        ) ?: baseSong.artist.takeIf { it.isNotBlank() } ?: fallbackArtist
        val safeAlbum = resolveParsedAlbumFallback(
            currentAlbum = baseSong.album,
            fallbackAlbum = fallbackAlbum,
            parsed = parsedFileName
        ) ?: baseSong.album.takeIf { it.isNotBlank() } ?: fallbackAlbum

        return baseSong.copy(
            id = computeStableSongId(source),
            name = safeTitle,
            artist = safeArtist,
            album = safeAlbum,
            durationMs = baseSong.durationMs.takeIf { it > 0L } ?: fallbackDurationMs,
            mediaUri = playbackRef,
            originalName = baseSong.originalName?.takeIf { it.isNotBlank() } ?: safeTitle,
            originalArtist = baseSong.originalArtist?.takeIf { it.isNotBlank() } ?: safeArtist,
            localFileName = fileName,
            localFilePath = source,
            channelId = "local",
            audioId = computeStableSongId(source).toString()
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
            audioId = stableId.toString()
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

        return quickSong.copy(
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
                ?: quickSong.originalTranslatedLyric
        )
    }

    private fun isReadableScannedTitle(title: String?): Boolean {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isBlank()) return false
        if (trimmed.all(Char::isDigit)) return false
        if (trimmed.startsWith("content://", ignoreCase = true)) return false
        if (trimmed.startsWith("file://", ignoreCase = true)) return false
        return true
    }

    private fun isReadableQuickImportedTitle(title: String?): Boolean {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isBlank()) return false
        if (trimmed.all(Char::isDigit)) return false
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

    private fun buildQuickImportedSong(context: Context, uri: Uri): SongItem {
        val resolvedFile = resolveSourceFile(context, uri)
        val queryInfo = queryQuickImportedAudioInfo(context, uri)
        val displayName = resolvedFile?.name
            ?: queryInfo.displayName
            ?: uri.lastPathSegment
            ?: uri.toString()
        val nearbyCoverUri = LocalMediaSupport.findNearbyCover(resolvedFile)?.toURI()?.toString()

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
        val displayName = runCatching {
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

        if (!targetFile.exists()) {
            resolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open external audio stream")
        }

        resolveSourceFile(context, uri)?.let { sourceFile ->
            copyNearbySidecars(sourceFile, targetFile)
        }

        return Uri.fromFile(targetFile)
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
}
