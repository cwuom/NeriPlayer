package moe.ouom.neriplayer.data.local.media

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
 * File: moe.ouom.neriplayer.data.local.media/LocalMediaSupport
 * Updated: 2026/3/23
 */


import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.system.Os
import androidx.core.content.FileProvider
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey as songStableKey
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.readBytesLimited
import moe.ouom.neriplayer.util.media.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.mergeLyricsForExternalPlayers
import moe.ouom.neriplayer.util.media.standardLyricsMetadataKeys
import moe.ouom.neriplayer.util.media.translatedLyricsMetadataKeys
import moe.ouom.neriplayer.util.network.isFileInsideDirectory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.net.URLConnection
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max
import androidx.core.net.toUri
import okhttp3.Request

private const val LOCAL_MEDIA_SHARE_TAG = "LocalMediaSupport"
private const val MAX_CONTAINER_METADATA_BYTES = 4L * 1024L * 1024L
private const val MAX_LOCAL_LYRIC_BYTES = 512L * 1024L
private const val NUL_CHAR = '\u0000'
private const val BOM_CHAR = '\uFEFF'
private const val REPLACEMENT_CHAR = '\uFFFD'
private const val SHARED_LOCAL_MEDIA_DIR = "shared_media_exports"
private const val LOCAL_COVER_LOOKUP_CACHE_LIMIT = 768
private const val NEARBY_COVER_LOOKUP_CACHE_LIMIT = 2048
private const val DIRECTORY_COVER_LOOKUP_CACHE_LIMIT = 256
private const val LOCAL_LYRICS_LOOKUP_CACHE_LIMIT = 768
private const val MAX_EDITABLE_COVER_BYTES = 8L * 1024L * 1024L
private const val MAX_EMBEDDED_COVER_CACHE_BYTES = 1024 * 1024
private const val MAX_EMBEDDED_COVER_CACHE_DIMENSION_PX = 512
private const val FRONT_COVER_PICTURE_TYPE = "Front Cover"
private val ROLELESS_COVER_PICTURE_EXTENSIONS = setOf(
    "3g2", "m4a", "m4b", "m4p", "m4r", "m4v", "mp4"
)
private val MP4_SUPPORTED_COVER_MIME_TYPES = setOf(
    "image/jpeg", "image/png"
)
private val EDITABLE_COVER_JPEG_QUALITIES = intArrayOf(95, 90, 85, 80, 75, 70, 65, 60)
private const val STAGED_METADATA_WRITE_DIRECTORY = "staged_metadata_writes"
private const val LOCAL_METADATA_SUFFIX = ".npmeta.json"
private val STAGED_CONTENT_REWRITE_EXTENSIONS = setOf(
    "aac", "aif", "aiff", "ape", "flac", "m4a", "m4b", "mp3", "mp4",
    "ogg", "opus", "tta", "wav", "wv"
)

data class LocalMediaDetails(
    val sourceUri: Uri,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val usesFallbackAlbum: Boolean,
    val albumArtist: String?,
    val composer: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val fileExtension: String?,
    val mimeType: String?,
    val audioMimeType: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val bitsPerSample: Int?,
    val sizeBytes: Long?,
    val lastModifiedMs: Long?,
    val filePath: String?,
    val coverUri: String?,
    val coverSource: String?,
    val lyricContent: String?,
    val lyricPath: String?,
    val lyricSource: String?,
    val originalTitle: String?,
    val originalArtist: String?,
    val embeddedCover: Boolean,
    val sourceStableKey: String? = null,
    val translatedLyricContent: String? = null,
    val romanizedLyricContent: String? = null
)

internal data class NearbyLyricFiles(
    val original: File?,
    val translated: File?,
    val romanized: File? = null
)

internal data class NearbyLyricReferences(
    val original: String?,
    val translated: String?,
    val romanized: String?
)

internal data class LocalLyricsScanMetadata(
    val lyric: String?,
    val translatedLyric: String?,
    val romanizedLyric: String?
)

internal data class LocalMetadataSidecar(
    val reference: String,
    val hasLyricOverride: Boolean,
    val hasTranslatedLyricOverride: Boolean,
    val hasRomanizedLyricOverride: Boolean,
    val matchedLyric: String?,
    val matchedTranslatedLyric: String?,
    val originalLyric: String?,
    val originalTranslatedLyric: String?,
    val matchedRomanizedLyric: String?,
    val originalRomanizedLyric: String?
) {
    val lyric: String?
        get() = matchedLyric ?: originalLyric

    val translatedLyric: String?
        get() = matchedTranslatedLyric ?: originalTranslatedLyric

    val romanizedLyric: String?
        get() = matchedRomanizedLyric ?: originalRomanizedLyric
}

private data class LocalDocumentNavigation(
    val baseUri: Uri,
    val treeUri: Uri?,
    val parentDocumentId: String?
)

internal enum class EditableCoverMutation {
    UNCHANGED,
    CLEAR,
    REPLACE
}

internal enum class LocalMediaMetadataWriteOutcome {
    SUCCESS,
    NOT_WRITABLE,
    UNSUPPORTED_OR_UNREADABLE,
    FAILED
}

fun SongItem.isLocalSong(): Boolean = LocalSongSupport.isLocalSong(this)

private fun Uri.isSupportedLocalMediaUri(): Boolean {
    return when {
        scheme.equals("file", ignoreCase = true) -> true
        scheme.equals("content", ignoreCase = true) -> true
        scheme.isNullOrBlank() && path?.startsWith("/") == true -> true
        else -> false
    }
}

internal fun preferredLocalMediaReference(
    localFilePath: String?,
    mediaUri: String?
): String? {
    val normalizedLocalPath = localFilePath?.takeIf { it.isNotBlank() }
    val normalizedMediaUri = mediaUri?.takeIf { it.isNotBlank() }
    return when {
        normalizedMediaUri.isContentLocalMediaReference() -> normalizedMediaUri
        normalizedLocalPath.isContentLocalMediaReference() -> normalizedLocalPath
        normalizedLocalPath != null -> normalizedLocalPath
        else -> normalizedMediaUri
    }
}

fun SongItem.localMediaUri(): Uri? {
    return localMediaUriCandidates().firstOrNull()
}

private fun SongItem.localMediaUriCandidates(): List<Uri> {
    val preferredSource = preferredLocalMediaReference(
        localFilePath = localFilePath,
        mediaUri = mediaUri
    )
    return listOfNotNull(preferredSource, localFilePath, mediaUri)
        .mapNotNull { source ->
            val localUri = if (source.startsWith("/")) {
                Uri.fromFile(File(source))
            } else {
                runCatching { source.toUri() }.getOrNull()
            }
            localUri?.takeIf { it.isSupportedLocalMediaUri() }
        }
        .distinctBy { it.toString() }
}

internal fun resolveContentShareFallbackUri(localUri: Uri?, mediaUri: String?): Uri? {
    return resolveContentShareFallbackReference(localUri?.toString(), mediaUri)
        ?.toUri()
        ?.takeIf { it.isSupportedLocalMediaUri() }
}

internal fun resolveContentShareFallbackReference(
    localUri: String?,
    mediaUri: String?
): String? {
    if (mediaUri.isContentLocalMediaReference()) {
        return mediaUri
    }
    if (localUri.isContentLocalMediaReference()) {
        return localUri
    }
    return null
}

private fun String?.isContentLocalMediaReference(): Boolean {
    if (this.isNullOrBlank()) {
        return false
    }
    return startsWith("content://", ignoreCase = true)
}

private fun SongItem.resolveShareableLocalUri(context: Context): Uri? {
    val localUri = localMediaUri() ?: return null
    val contentFallbackUri = resolveContentShareFallbackUri(localUri, mediaUri)
    val resolvedFile = runCatching {
        LocalMediaSupport.resolveLocalFile(context, localUri)
    }.getOrNull()
    if (resolvedFile != null) {
        return buildShareableFileUri(context, resolvedFile)
            ?: contentFallbackUri?.takeUnless {
                localUri.scheme.equals("content", ignoreCase = true)
            }
    }

    if (localUri.scheme.equals("content", ignoreCase = true)) {
        val stagedFile = LocalMediaSupport.prepareShareableContentFile(
            context = context,
            sourceUri = localUri,
            suggestedName = localFileName ?: name
        ) ?: return null
        return buildShareableFileUri(context, stagedFile)
    }

    val path = when {
        localUri.scheme.equals("file", ignoreCase = true) -> localUri.path
        localUri.scheme.isNullOrBlank() -> mediaUri
        else -> null
    } ?: return null

    val file = File(path)
    if (!file.exists()) return contentFallbackUri
    return buildShareableFileUri(context, file) ?: contentFallbackUri
}

suspend fun SongItem.toShareableLocalUri(context: Context): Uri? = withContext(Dispatchers.IO) {
    resolveShareableLocalUri(context)
}

private fun buildShareableFileUri(context: Context, sourceFile: File): Uri? {
    val authority = "${context.packageName}.fileprovider"
    runCatching {
        FileProvider.getUriForFile(context, authority, sourceFile)
    }.getOrNull()?.let { return it }

    val stagedFile = runCatching {
        LocalMediaSupport.prepareShareableFile(context, sourceFile)
    }.getOrElse {
        NPLogger.w(
            LOCAL_MEDIA_SHARE_TAG,
            "Failed to stage share file for ${sourceFile.absolutePath}: ${it.message}"
        )
        return null
    }
    return runCatching {
        FileProvider.getUriForFile(context, authority, stagedFile)
    }.getOrElse {
        NPLogger.w(
            LOCAL_MEDIA_SHARE_TAG,
            "FileProvider failed for staged share file ${stagedFile.absolutePath}: ${it.message}"
        )
        null
    }
}

object LocalMediaSupport {
    private const val TAG = "LocalMediaSupport"
    private val lyricExtensions = listOf("lrc", "txt")
    private val coverFileNames = listOf("cover", "folder", "front")
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
    private data class LocalCoverCacheHit(val coverUri: String?)
    private data class FilePathCacheHit(val path: String?)
    private val localLyricsLookupCache = object : LinkedHashMap<String, LocalLyricsScanMetadata>(
        LOCAL_LYRICS_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LocalLyricsScanMetadata>
        ): Boolean {
            return size > LOCAL_LYRICS_LOOKUP_CACHE_LIMIT
        }
    }
    private val localCoverLookupCache = object : LinkedHashMap<String, String?>(
        LOCAL_COVER_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > LOCAL_COVER_LOOKUP_CACHE_LIMIT
        }
    }
    private val nearbyCoverLookupCache = object : LinkedHashMap<String, String?>(
        NEARBY_COVER_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > NEARBY_COVER_LOOKUP_CACHE_LIMIT
        }
    }
    private val directoryCoverLookupCache = object : LinkedHashMap<String, String?>(
        DIRECTORY_COVER_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > DIRECTORY_COVER_LOOKUP_CACHE_LIMIT
        }
    }

    private data class AudioTrackTechInfo(
        val audioMimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channelCount: Int?
    )

    private data class RetrieverTextMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val durationMs: Long? = null,
        val mimeType: String? = null,
        val bitrateKbps: Int? = null,
        val sampleRateHz: Int? = null
    )

    private data class ResolvedInspectableLocalMedia(
        val queried: QueriedContentInfo,
        val resolvedPath: String?,
        val file: File?,
        val playableUri: Uri,
        val displayName: String,
        val fallbackTitle: String,
        val fileExtension: String?
    )

    internal data class ContainerMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null
    )

    private data class TagLibMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val durationMs: Long? = null,
        val bitrateKbps: Int? = null,
        val sampleRateHz: Int? = null,
        val channelCount: Int? = null,
        val lyrics: String? = null,
        val translatedLyrics: String? = null,
        val coverBytes: ByteArray? = null,
        val sourceStableKey: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TagLibMetadata) return false

            return title == other.title &&
                artist == other.artist &&
                album == other.album &&
                albumArtist == other.albumArtist &&
                composer == other.composer &&
                genre == other.genre &&
                year == other.year &&
                trackNumber == other.trackNumber &&
                discNumber == other.discNumber &&
                durationMs == other.durationMs &&
                bitrateKbps == other.bitrateKbps &&
                sampleRateHz == other.sampleRateHz &&
                channelCount == other.channelCount &&
                lyrics == other.lyrics &&
                translatedLyrics == other.translatedLyrics &&
                sourceStableKey == other.sourceStableKey &&
                (coverBytes?.contentEquals(other.coverBytes) ?: (other.coverBytes == null))
        }

        override fun hashCode(): Int {
            var result = title?.hashCode() ?: 0
            result = 31 * result + (artist?.hashCode() ?: 0)
            result = 31 * result + (album?.hashCode() ?: 0)
            result = 31 * result + (albumArtist?.hashCode() ?: 0)
            result = 31 * result + (composer?.hashCode() ?: 0)
            result = 31 * result + (genre?.hashCode() ?: 0)
            result = 31 * result + (year ?: 0)
            result = 31 * result + (trackNumber ?: 0)
            result = 31 * result + (discNumber ?: 0)
            result = 31 * result + (durationMs?.hashCode() ?: 0)
            result = 31 * result + (bitrateKbps ?: 0)
            result = 31 * result + (sampleRateHz ?: 0)
            result = 31 * result + (channelCount ?: 0)
            result = 31 * result + (lyrics?.hashCode() ?: 0)
            result = 31 * result + (translatedLyrics?.hashCode() ?: 0)
            result = 31 * result + (sourceStableKey?.hashCode() ?: 0)
            result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    internal data class QuickLocalMetadataSelection(
        val title: String,
        val artist: String,
        val album: String,
        val usesFallbackAlbum: Boolean,
        val durationMs: Long
    )

    internal fun selectQuickLocalMetadata(
        title: String,
        queriedArtist: String?,
        queriedAlbum: String?,
        queriedDurationMs: Long?,
        unknownArtistLabel: String,
        defaultAlbumLabel: String
    ): QuickLocalMetadataSelection {
        val artist = queriedArtist
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: unknownArtistLabel
        val album = queriedAlbum
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val resolvedAlbum = album ?: defaultAlbumLabel
        return QuickLocalMetadataSelection(
            title = title,
            artist = artist,
            album = resolvedAlbum,
            usesFallbackAlbum = album == null,
            durationMs = queriedDurationMs?.coerceAtLeast(0L) ?: 0L
        )
    }

    fun inspect(context: Context, song: SongItem): LocalMediaDetails? {
        for (uri in song.localMediaUriCandidates()) {
            if (!uri.isSupportedLocalMediaUri()) {
                continue
            }
            runCatching { inspect(context, uri) }
                .onSuccess { return it }
                .onFailure {
                    NPLogger.w(TAG, "inspect candidate failed for $uri: ${it.message}")
                }
        }
        return null
    }

    fun inspectMetadataOnly(
        context: Context,
        song: SongItem,
        resolveCoverFallback: Boolean = true
    ): LocalMediaDetails? {
        for (uri in song.localMediaUriCandidates()) {
            if (!uri.isSupportedLocalMediaUri()) {
                continue
            }
            runCatching {
                inspectMetadataOnly(
                    context = context,
                    uri = uri,
                    resolveCoverFallback = resolveCoverFallback
                )
            }
                .onSuccess { return it }
                .onFailure {
                    NPLogger.w(TAG, "inspect metadata-only candidate failed for $uri: ${it.message}")
                }
        }
        return null
    }

    internal suspend fun writeEditableMetadata(
        context: Context,
        song: SongItem,
        coverReference: String? = song.customCoverUrl,
        writeCover: Boolean = coverReference != null,
        writeLyrics: Boolean = false
    ): LocalMediaMetadataWriteOutcome = withContext(Dispatchers.IO) {
        val candidates = song.localMediaUriCandidates()
        if (candidates.isEmpty()) {
            return@withContext LocalMediaMetadataWriteOutcome.NOT_WRITABLE
        }

        var fallbackOutcome = LocalMediaMetadataWriteOutcome.NOT_WRITABLE
        candidates.forEach { sourceUri ->
            val directOutcome = writeEditableMetadataDirect(
                context = context,
                song = song,
                sourceUri = sourceUri,
                coverReference = coverReference,
                writeCover = writeCover,
                writeLyrics = writeLyrics
            )
            val outcome = if (shouldAttemptStagedContentMetadataWrite(sourceUri, song, directOutcome)) {
                writeEditableMetadataThroughStagedContentCopy(
                    context = context,
                    song = song,
                    sourceUri = sourceUri,
                    coverReference = coverReference,
                    writeCover = writeCover,
                    writeLyrics = writeLyrics,
                    directOutcome = directOutcome
                )
            } else {
                directOutcome
            }
            val sidecarWritten = if (writeLyrics) {
                writeLocalLyricsMetadata(
                    context = context,
                    sourceUri = sourceUri,
                    file = runCatching { resolveLocalFile(context, sourceUri) }.getOrNull(),
                    displayName = song.localFileName
                        ?.takeIf(String::isNotBlank)
                        ?: sourceUri.lastPathSegment.orEmpty(),
                    song = song
                )
            } else {
                true
            }
            val finalOutcome = when {
                !writeLyrics -> outcome
                !sidecarWritten -> LocalMediaMetadataWriteOutcome.FAILED
                outcome == LocalMediaMetadataWriteOutcome.SUCCESS -> {
                    LocalMediaMetadataWriteOutcome.SUCCESS
                }
                !writeCover -> LocalMediaMetadataWriteOutcome.SUCCESS
                else -> outcome
            }
            if (!sidecarWritten) {
                NPLogger.w(TAG, "write local lyrics metadata sidecar failed for $sourceUri")
            }
            if (finalOutcome == LocalMediaMetadataWriteOutcome.SUCCESS) {
                return@withContext finalOutcome
            }
            fallbackOutcome = selectEditableMetadataWriteFallback(
                current = fallbackOutcome,
                candidate = finalOutcome
            )
        }
        fallbackOutcome
    }

    /**
     * 只读取歌词相关字段，避免歌词首屏触发 TagLib、封面和音频轨道解析
     */
    internal fun inspectLyricsFast(
        song: SongItem
    ): LocalLyricsScanMetadata {
        val stored = LocalLyricsScanMetadata(
            lyric = song.matchedLyric ?: song.originalLyric,
            translatedLyric = song.matchedTranslatedLyric ?: song.originalTranslatedLyric,
            romanizedLyric = null
        )

        val source = song.localMediaUri()
        val cacheKey = buildLocalLyricsCacheKey(song, source)
        synchronized(localLyricsLookupCache) {
            localLyricsLookupCache[cacheKey]?.let { return it }
        }

        val directFile = song.localFilePath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?: source
                ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
                ?.path
                ?.let(::File)
                ?.takeIf(File::isFile)
        val scanned = if (directFile != null) {
            runCatching {
                inspectLyricsFromDirectFile(
                    file = directFile
                )
            }.getOrElse {
                NPLogger.w(TAG, "fast local lyrics inspection failed for $source: ${it.message}")
                LocalLyricsScanMetadata(null, null, null)
            }
        } else {
            // SAF 歌词引用在导入阶段已写入 SongItem, 首屏不再同步查询文档树
            LocalLyricsScanMetadata(null, null, null)
        }
        val result = LocalLyricsScanMetadata(
            lyric = stored.lyric ?: scanned.lyric,
            translatedLyric = stored.translatedLyric ?: scanned.translatedLyric,
            romanizedLyric = scanned.romanizedLyric
        )
        synchronized(localLyricsLookupCache) {
            localLyricsLookupCache[cacheKey] = result
        }
        return result
    }

    private fun inspectLyricsFromDirectFile(
        file: File
    ): LocalLyricsScanMetadata {
        val metadataFile = File(
            file.parentFile ?: return LocalLyricsScanMetadata(null, null, null),
            file.name + LOCAL_METADATA_SUFFIX
        )
        val localMetadata = if (metadataFile.isFile) {
            readTextFile(metadataFile)?.let {
                parseLocalMetadataSidecar(metadataFile.absolutePath, it)
            }
        } else {
            null
        }
        val nearbyFiles = findNearbyLyricFiles(file)
        fun read(reference: File?): String? {
            return reference?.let(::readTextFile)
        }
        val nearbyLyric = read(nearbyFiles.original)
        val nearbyTranslatedLyric = read(nearbyFiles.translated)
        val nearbyRomanizedLyric = read(nearbyFiles.romanized)
        return LocalLyricsScanMetadata(
            lyric = if (localMetadata?.hasLyricOverride == true) {
                localMetadata.lyric
            } else {
                nearbyLyric
            },
            translatedLyric = if (localMetadata?.hasTranslatedLyricOverride == true) {
                localMetadata.translatedLyric
            } else {
                nearbyTranslatedLyric
            },
            romanizedLyric = if (localMetadata?.hasRomanizedLyricOverride == true) {
                localMetadata.romanizedLyric
            } else {
                nearbyRomanizedLyric
            }
        )
    }

    internal fun clearLyricsLookupCache() {
        synchronized(localLyricsLookupCache) {
            localLyricsLookupCache.clear()
        }
    }

    private fun buildLocalLyricsCacheKey(song: SongItem, source: Uri?): String {
        val localFile = song.localFilePath?.let(::File)
        val localFileState = localFile?.let {
            "${it.length()}:${it.lastModified()}:${it.parentFile?.lastModified()}"
        }.orEmpty()
        return listOf(
            song.sourceStableKey,
            song.localFilePath,
            song.localFileName,
            source?.toString(),
            localFileState
        ).joinToString("|")
    }

    private fun selectEditableMetadataWriteFallback(
        current: LocalMediaMetadataWriteOutcome,
        candidate: LocalMediaMetadataWriteOutcome
    ): LocalMediaMetadataWriteOutcome {
        return when {
            current == LocalMediaMetadataWriteOutcome.FAILED ||
                candidate == LocalMediaMetadataWriteOutcome.FAILED -> LocalMediaMetadataWriteOutcome.FAILED
            current == LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE ||
                candidate == LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE -> {
                LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE
            }
            else -> LocalMediaMetadataWriteOutcome.NOT_WRITABLE
        }
    }

    internal fun shouldAttemptStagedContentMetadataWrite(
        sourceUri: Uri,
        song: SongItem,
        directOutcome: LocalMediaMetadataWriteOutcome
    ): Boolean = shouldAttemptStagedContentMetadataWrite(
        sourceScheme = sourceUri.scheme,
        sourcePathSegment = sourceUri.lastPathSegment,
        song = song,
        directOutcome = directOutcome
    )

    internal fun shouldAttemptStagedContentMetadataWrite(
        sourceScheme: String?,
        sourcePathSegment: String?,
        song: SongItem,
        directOutcome: LocalMediaMetadataWriteOutcome
    ): Boolean {
        if (directOutcome == LocalMediaMetadataWriteOutcome.SUCCESS) {
            return false
        }
        if (!sourceScheme.equals("content", ignoreCase = true)) {
            return false
        }
        return resolveEditableMediaExtension(song, sourcePathSegment) in
            STAGED_CONTENT_REWRITE_EXTENSIONS
    }

    internal fun resolveEditableMediaExtension(song: SongItem, sourceUri: Uri): String =
        resolveEditableMediaExtension(song, sourceUri.lastPathSegment)

    private fun resolveEditableMediaExtension(song: SongItem, sourcePathSegment: String?): String {
        return listOf(
            song.localFileName,
            song.localFilePath,
            sourcePathSegment,
            song.mediaUri
        ).firstNotNullOfOrNull { reference ->
            reference
                ?.substringBefore('?')
                ?.substringBefore('#')
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf(String::isNotBlank)
        }
            ?: "bin"
    }

    private fun writeEditableMetadataDirect(
        context: Context,
        song: SongItem,
        sourceUri: Uri,
        coverReference: String?,
        writeCover: Boolean,
        writeLyrics: Boolean
    ): LocalMediaMetadataWriteOutcome {
        val resolved = runCatching {
            resolveInspectableLocalMedia(
                context = context,
                uri = sourceUri,
                allowDescriptorFallback = true
            )
        }.getOrElse { error ->
            NPLogger.w(TAG, "resolve writable local metadata failed for $sourceUri: ${error.message}")
            return LocalMediaMetadataWriteOutcome.FAILED
        }
        val metadataSnapshot = openTagLibDescriptor(
            context = context,
            uri = sourceUri,
            file = resolved.file
        )?.use { target ->
            val existing = loadTagLibPropertyMap(target)
                ?: return@use null
            val lyrics = if (writeLyrics) {
                song.matchedLyric ?: song.originalLyric
            } else {
                null
            }
            val translatedLyrics = if (writeLyrics) {
                song.matchedTranslatedLyric ?: song.originalTranslatedLyric
            } else {
                null
            }
            val updated = applyEditableMetadata(
                propertyMap = existing,
                title = song.displayName(),
                artist = song.displayArtist(),
                lyrics = lyrics,
                translatedLyrics = translatedLyrics,
                audioExtension = resolved.fileExtension,
                writeLyrics = writeLyrics,
                sourceStableKey = editableMetadataSourceStableKey(song)
            )
            val picturePlan = buildEditableCoverWritePlan(
                context = context,
                descriptor = target,
                coverReference = coverReference,
                writeCover = writeCover,
                audioExtension = resolved.fileExtension
            )
            EditableMetadataSnapshot(
                existingProperties = existing,
                updatedProperties = updated,
                picturePlan = picturePlan,
                expectedStandardLyrics = mergeLyricsForExternalPlayers(lyrics, translatedLyrics),
                sourceStableKey = editableMetadataSourceStableKey(song),
                writesLyrics = writeLyrics,
                clearsMissingLyrics = writeLyrics
            )
        } ?: return LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE

        if (metadataSnapshot.picturePlan == EditableCoverWritePlan.Unreadable) {
            return LocalMediaMetadataWriteOutcome.FAILED
        }

        val propertyMapChanged = !propertyMapsEquivalent(
            metadataSnapshot.existingProperties,
            metadataSnapshot.updatedProperties
        )
        val restorePropertiesAfterCover = shouldRestoreEditablePropertiesAfterCoverWrite(
            audioExtension = resolved.fileExtension,
            writesCover = metadataSnapshot.picturePlan is EditableCoverWritePlan.Update
        )
        fun saveProperties(): Boolean {
            return openWritableTagLibDescriptor(
                context = context,
                uri = sourceUri,
                file = resolved.file
            )?.use { target ->
                runCatching {
                    TagLib.savePropertyMap(target.dup().detachFd(), metadataSnapshot.updatedProperties)
                }.getOrElse { error ->
                    NPLogger.w(TAG, "write local metadata failed for $sourceUri: ${error.message}")
                    false
                }
            } ?: false
        }
        if (!restorePropertiesAfterCover && propertyMapChanged && !saveProperties()) {
            return LocalMediaMetadataWriteOutcome.FAILED
        }

        val coverSaved = when (val picturePlan = metadataSnapshot.picturePlan) {
            EditableCoverWritePlan.Unchanged -> true
            EditableCoverWritePlan.Unreadable -> false
            is EditableCoverWritePlan.Update -> {
                openWritableTagLibDescriptor(
                    context = context,
                    uri = sourceUri,
                    file = resolved.file
                )?.use { target ->
                    runCatching {
                        TagLib.savePictures(target.dup().detachFd(), picturePlan.pictures)
                    }.getOrElse { error ->
                        NPLogger.w(TAG, "write local cover failed for $sourceUri: ${error.message}")
                        false
                    }
                } ?: false
            }
        }
        if (!coverSaved) {
            return LocalMediaMetadataWriteOutcome.FAILED
        }
        if (restorePropertiesAfterCover && !saveProperties()) {
            return LocalMediaMetadataWriteOutcome.FAILED
        }

        val verified = openTagLibDescriptor(
            context = context,
            uri = sourceUri,
            file = resolved.file
        )?.use { target ->
            val propertyMap = loadTagLibPropertyMap(target) ?: return@use false
            val propertiesMatch = hasExpectedEditableMetadata(
                propertyMap = propertyMap,
                title = song.displayName(),
                artist = song.displayArtist(),
                lyrics = if (metadataSnapshot.writesLyrics) {
                    song.matchedLyric ?: song.originalLyric
                } else {
                    null
                },
                translatedLyrics = if (metadataSnapshot.writesLyrics) {
                    song.matchedTranslatedLyric ?: song.originalTranslatedLyric
                } else {
                    null
                },
                audioExtension = resolved.fileExtension,
                expectedStandardLyrics = metadataSnapshot.expectedStandardLyrics,
                verifyStandardLyrics = metadataSnapshot.writesLyrics,
                verifyMissingLyrics = metadataSnapshot.clearsMissingLyrics,
                sourceStableKey = metadataSnapshot.sourceStableKey
            )
            val coverMatch = when (val picturePlan = metadataSnapshot.picturePlan) {
                EditableCoverWritePlan.Unchanged -> true
                EditableCoverWritePlan.Unreadable -> false
                is EditableCoverWritePlan.Update -> {
                    val pictures = runCatching {
                        TagLib.getPictures(target.dup().detachFd())
                    }.getOrElse { error ->
                        NPLogger.w(TAG, "verify local cover failed for $sourceUri: ${error.message}")
                        return@use false
                    }
                    hasExpectedEditableCover(
                        actualPictures = pictures,
                        expectedPictures = picturePlan.pictures,
                        audioExtension = resolved.fileExtension
                    )
                }
            }
            propertiesMatch && coverMatch
        } == true
        if (!verified) {
            return LocalMediaMetadataWriteOutcome.FAILED
        }

        if (metadataSnapshot.picturePlan !is EditableCoverWritePlan.Unchanged) {
            invalidateLocalCoverLookupCache(context, sourceUri, resolved)
        }
        return LocalMediaMetadataWriteOutcome.SUCCESS
    }

    private fun writeEditableMetadataThroughStagedContentCopy(
        context: Context,
        song: SongItem,
        sourceUri: Uri,
        coverReference: String?,
        writeCover: Boolean,
        writeLyrics: Boolean,
        directOutcome: LocalMediaMetadataWriteOutcome
    ): LocalMediaMetadataWriteOutcome {
        val stagingDirectory = File(context.cacheDir, STAGED_METADATA_WRITE_DIRECTORY)
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            NPLogger.w(TAG, "create staged metadata directory failed")
            return directOutcome
        }
        val extension = resolveEditableMediaExtension(song, sourceUri)
        val backup = runCatching {
            File.createTempFile("metadata-source-", ".${extension}", stagingDirectory)
        }.getOrNull() ?: return directOutcome
        val updated = runCatching {
            File.createTempFile("metadata-updated-", ".${extension}", stagingDirectory)
        }.getOrNull()
        if (updated == null) {
            backup.delete()
            return directOutcome
        }
        try {
            val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                backup.outputStream().use { output ->
                    input.copyTo(output)
                }
                backup.length() > 0L
            } ?: false
            if (!copied) {
                return directOutcome
            }
            backup.copyTo(updated, overwrite = true)
            val stagedSong = song.copy(
                mediaUri = Uri.fromFile(updated).toString(),
                localFilePath = updated.absolutePath,
                localFileName = updated.name
            )
            val stagedOutcome = writeEditableMetadataDirect(
                context = context,
                song = stagedSong,
                sourceUri = Uri.fromFile(updated),
                coverReference = coverReference,
                writeCover = writeCover,
                writeLyrics = writeLyrics
            )
            if (stagedOutcome != LocalMediaMetadataWriteOutcome.SUCCESS) {
                return directOutcome
            }
            if (!replaceContentUriWithFile(context, sourceUri, updated)) {
                restoreContentUriFromFile(context, sourceUri, backup)
                return directOutcome
            }
            if (contentUriByteCount(context, sourceUri) != updated.length()) {
                NPLogger.w(TAG, "staged metadata write verification failed for $sourceUri")
                restoreContentUriFromFile(context, sourceUri, backup)
                return LocalMediaMetadataWriteOutcome.FAILED
            }
            if (
                !verifyEditableMetadataAtSource(
                    context = context,
                    song = song,
                    sourceUri = sourceUri,
                    coverReference = coverReference,
                    writeCover = writeCover,
                    writeLyrics = writeLyrics
                )
            ) {
                NPLogger.w(TAG, "staged metadata tag verification failed for $sourceUri")
                restoreContentUriFromFile(context, sourceUri, backup)
                return LocalMediaMetadataWriteOutcome.FAILED
            }
            if (writeCover) {
                val resolvedSource = runCatching {
                    resolveInspectableLocalMedia(
                        context = context,
                        uri = sourceUri,
                        allowDescriptorFallback = true
                    )
                }.getOrNull()
                invalidateLocalCoverLookupCache(
                    context = context,
                    uri = sourceUri,
                    resolved = resolvedSource
                )
            }
            NPLogger.d(TAG, "staged metadata write completed for $sourceUri")
            return LocalMediaMetadataWriteOutcome.SUCCESS
        } catch (error: Exception) {
            NPLogger.w(TAG, "staged metadata write failed for $sourceUri: ${error.message}")
            return directOutcome
        } finally {
            if (backup.exists() && !backup.delete()) {
                NPLogger.w(TAG, "delete staged metadata backup failed: ${backup.name}")
            }
            if (updated.exists() && !updated.delete()) {
                NPLogger.w(TAG, "delete staged metadata update failed: ${updated.name}")
            }
        }
    }

    private fun replaceContentUriWithFile(context: Context, uri: Uri, source: File): Boolean {
        val output = runCatching {
            context.contentResolver.openOutputStream(uri, "rwt")
        }.getOrNull() ?: runCatching {
            context.contentResolver.openOutputStream(uri, "wt")
        }.getOrNull() ?: return false
        return runCatching {
            output.use { target ->
                source.inputStream().use { input ->
                    input.copyTo(target)
                }
                target.flush()
            }
            true
        }.getOrElse { error ->
            NPLogger.w(TAG, "replace content metadata source failed for $uri: ${error.message}")
            false
        }
    }

    private fun restoreContentUriFromFile(context: Context, uri: Uri, backup: File) {
        if (!backup.isFile || !replaceContentUriWithFile(context, uri, backup)) {
            NPLogger.e(TAG, "restore content metadata source failed for $uri")
        }
    }

    private fun contentUriByteCount(context: Context, uri: Uri): Long {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
            }
            total
        } ?: -1L
    }

    private fun verifyEditableMetadataAtSource(
        context: Context,
        song: SongItem,
        sourceUri: Uri,
        coverReference: String?,
        writeCover: Boolean,
        writeLyrics: Boolean
    ): Boolean {
        return openTagLibDescriptor(context, sourceUri, file = null)?.use { descriptor ->
            val propertyMap = loadTagLibPropertyMap(descriptor) ?: return@use false
            val lyrics = if (writeLyrics) {
                song.matchedLyric ?: song.originalLyric
            } else {
                null
            }
            val translatedLyrics = if (writeLyrics) {
                song.matchedTranslatedLyric ?: song.originalTranslatedLyric
            } else {
                null
            }
            val propertiesMatch = hasExpectedEditableMetadata(
                propertyMap = propertyMap,
                title = song.displayName(),
                artist = song.displayArtist(),
                lyrics = lyrics,
                translatedLyrics = translatedLyrics,
                audioExtension = resolveEditableMediaExtension(song, sourceUri),
                expectedStandardLyrics = mergeLyricsForExternalPlayers(lyrics, translatedLyrics),
                verifyStandardLyrics = writeLyrics,
                verifyMissingLyrics = writeLyrics,
                sourceStableKey = editableMetadataSourceStableKey(song)
            )
            propertiesMatch && hasExpectedEditableCoverAtSource(
                context = context,
                descriptor = descriptor,
                coverReference = coverReference,
                writeCover = writeCover,
                audioExtension = resolveEditableMediaExtension(song, sourceUri)
            )
        } == true
    }

    private fun hasExpectedEditableCoverAtSource(
        context: Context,
        descriptor: ParcelFileDescriptor,
        coverReference: String?,
        writeCover: Boolean,
        audioExtension: String?
    ): Boolean {
        if (!writeCover) {
            return true
        }
        val pictures = runCatching {
            TagLib.getPictures(descriptor.dup().detachFd())
        }.getOrElse { error ->
            NPLogger.w(TAG, "verify staged local cover failed: ${error.message}")
            return false
        }
        val rolelessPictureContainer = usesRolelessEditableCoverPictures(audioExtension)
        val actualCover = if (rolelessPictureContainer) {
            pictures.singleOrNull()
        } else {
            pictures.firstOrNull(::isFrontCoverPicture)
        }
        val reference = coverReference?.trim()?.takeIf(String::isNotBlank)
        return when (resolveEditableCoverMutation(writeCover, reference)) {
            EditableCoverMutation.UNCHANGED -> true
            EditableCoverMutation.CLEAR -> {
                if (rolelessPictureContainer) pictures.isEmpty() else actualCover == null
            }
            EditableCoverMutation.REPLACE -> {
                val replacementReference = reference ?: return false
                if (replacementReference.isRemoteCoverReference()) {
                    if (rolelessPictureContainer) {
                        pictures.size == 1 && actualCover?.data?.isNotEmpty() == true
                    } else {
                        actualCover?.data?.isNotEmpty() == true
                    }
                } else {
                    val expectedCover = createEditableCoverPicture(
                        context = context,
                        reference = replacementReference,
                        audioExtension = audioExtension
                    ) ?: return false
                    if (rolelessPictureContainer) {
                        pictures.size == 1 &&
                            actualCover?.data?.contentEquals(expectedCover.data) == true
                    } else {
                        actualCover?.data?.contentEquals(expectedCover.data) == true
                    }
                }
            }
        }
    }

    fun resolveLocalFile(context: Context, uri: Uri): File? {
        if (!uri.isSupportedLocalMediaUri()) return null
        val resolvedPath = directFilePath(uri)
            ?: queryContentInfo(context, uri).filePath
            ?: resolvePathFromDescriptor(context, uri)
        return resolvedPath?.let(::File)?.takeIf(File::exists)
    }

    fun inspectQuick(
        context: Context,
        uri: Uri,
        includeAudioTrackInfo: Boolean = false
    ): LocalMediaDetails {
        val resolved = resolveInspectableLocalMedia(
            context = context,
            uri = uri,
            allowDescriptorFallback = true
        )
        val audioTrackTechInfo = if (includeAudioTrackInfo) {
            inspectAudioTrackInfo(context, resolved.playableUri)
        } else {
            null
        }
        return buildQuickLocalMediaDetails(
            context = context,
            sourceUri = uri,
            resolved = resolved,
            audioTrackTechInfo = audioTrackTechInfo
        )
    }

    fun inspectForScan(context: Context, uri: Uri): LocalMediaDetails {
        val resolved = resolveInspectableLocalMedia(
            context = context,
            uri = uri,
            allowDescriptorFallback = true
        )
        val queried = resolved.queried
        val file = resolved.file
        val containerMetadata = file?.let(::parseContainerMetadata)
        val tagLibMetadata = inspectTagLibMetadata(
            context = context,
            uri = resolved.playableUri,
            file = file,
            includeEmbeddedAssets = false,
            includeAudioProperties = false
        )
        val title = pickReadableLocalTitle(
            sourceUri = uri,
            fallbackTitle = resolved.fallbackTitle,
            tagLibMetadata?.title,
            containerMetadata?.title,
            queried.title
        ) ?: resolved.fallbackTitle
        val artist = tagLibMetadata?.artist
            ?: containerMetadata?.artist?.takeIf { it.isNotBlank() }
            ?: queried.artist?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.music_unknown_artist)
        val album = tagLibMetadata?.album
            ?: containerMetadata?.album?.takeIf { it.isNotBlank() }
            ?: queried.album?.takeIf { it.isNotBlank() }
        val usesFallbackAlbum = album == null
        val nearbyCover = findNearbyCover(file)
        val localMetadata = readLocalMetadataSidecar(
            context = context,
            sourceUri = uri,
            file = file,
            displayName = resolved.displayName
        )
        val nearbyLyricFiles = findNearbyLyricFiles(file)
        val nearbyLyricReferences = findNearbyLyricReferences(
            context = context,
            uri = uri,
            file = file,
            displayName = resolved.displayName
        )
        val nearbyLyricContent = readNearbyLyricContent(
            context = context,
            reference = nearbyLyricReferences.original
                ?: nearbyLyricFiles.original?.absolutePath,
            label = "scan lyric"
        )
        val nearbyTranslatedLyricContent = readNearbyLyricContent(
            context = context,
            reference = nearbyLyricReferences.translated
                ?: nearbyLyricFiles.translated?.absolutePath,
            label = "scan translated lyric"
        )
        val nearbyRomanizedLyricContent = readNearbyLyricContent(
            context = context,
            reference = nearbyLyricReferences.romanized
                ?: nearbyLyricFiles.romanized?.absolutePath,
            label = "scan romanized lyric"
        )
        val effectiveLyricContent = if (localMetadata?.hasLyricOverride == true) {
            localMetadata.lyric
        } else {
            resolveEffectiveLocalLyricContent(
                sidecarContent = nearbyLyricContent,
                embeddedContent = tagLibMetadata?.lyrics
            )
        }
        val effectiveTranslatedLyricContent = if (
            localMetadata?.hasTranslatedLyricOverride == true
        ) {
            localMetadata.translatedLyric
        } else {
            resolveEffectiveLocalLyricContent(
                sidecarContent = nearbyTranslatedLyricContent,
                embeddedContent = tagLibMetadata?.translatedLyrics
            )
        }
        val effectiveRomanizedLyricContent = if (
            localMetadata?.hasRomanizedLyricOverride == true
        ) {
            localMetadata.romanizedLyric
        } else {
            nearbyRomanizedLyricContent?.takeIf(String::isNotBlank)
        }
        val lyricReference = localMetadata?.reference
            ?.takeIf { localMetadata.hasLyricOverride }
            ?: nearbyLyricReferences.original
            ?: nearbyLyricFiles.original?.absolutePath

        return LocalMediaDetails(
            sourceUri = uri,
            displayName = resolved.displayName,
            title = title,
            artist = artist,
            album = album ?: context.getString(R.string.local_files),
            usesFallbackAlbum = usesFallbackAlbum,
            albumArtist = tagLibMetadata?.albumArtist ?: containerMetadata?.albumArtist,
            composer = tagLibMetadata?.composer ?: containerMetadata?.composer,
            genre = tagLibMetadata?.genre ?: containerMetadata?.genre,
            year = tagLibMetadata?.year ?: containerMetadata?.year,
            trackNumber = tagLibMetadata?.trackNumber ?: containerMetadata?.trackNumber,
            discNumber = tagLibMetadata?.discNumber ?: containerMetadata?.discNumber,
            durationMs = tagLibMetadata?.durationMs ?: queried.durationMs ?: 0L,
            fileExtension = resolved.fileExtension,
            mimeType = queried.mimeType,
            audioMimeType = null,
            bitrateKbps = tagLibMetadata?.bitrateKbps,
            sampleRateHz = tagLibMetadata?.sampleRateHz,
            channelCount = tagLibMetadata?.channelCount,
            bitsPerSample = null,
            sizeBytes = queried.sizeBytes ?: file?.length(),
            lastModifiedMs = queried.lastModifiedMs ?: file?.lastModified(),
            filePath = file?.absolutePath ?: queried.filePath,
            coverUri = nearbyCover?.toURI()?.toString(),
            coverSource = nearbyCover?.let {
                context.getString(R.string.local_song_cover_external)
            },
            lyricContent = effectiveLyricContent,
            lyricPath = resolveEffectiveLocalLyricPath(
                reference = lyricReference,
                content = effectiveLyricContent
            ),
            lyricSource = when {
                localMetadata?.hasLyricOverride != true &&
                    !nearbyLyricContent.isNullOrBlank() -> {
                    context.getString(R.string.local_song_lyric_external)
                }
                !effectiveLyricContent.isNullOrBlank() -> {
                    context.getString(R.string.local_song_lyric_embedded)
                }
                else -> null
            },
            originalTitle = title,
            originalArtist = tagLibMetadata?.artist
                ?: containerMetadata?.artist?.takeIf { it.isNotBlank() }
                ?: queried.artist?.takeIf { it.isNotBlank() }
                ?: artist,
            embeddedCover = false,
            sourceStableKey = tagLibMetadata?.sourceStableKey,
            translatedLyricContent = effectiveTranslatedLyricContent,
            romanizedLyricContent = effectiveRomanizedLyricContent
        )
    }

    internal fun inspectLyricsForScan(
        context: Context,
        uri: Uri
    ): LocalLyricsScanMetadata {
        val resolved = resolveInspectableLocalMedia(
            context = context,
            uri = uri,
            allowDescriptorFallback = true
        )
        val localMetadata = readLocalMetadataSidecar(
            context = context,
            sourceUri = uri,
            file = resolved.file,
            displayName = resolved.displayName
        )
        val nearbyFiles = findNearbyLyricFiles(resolved.file)
        val nearbyReferences = findNearbyLyricReferences(
            context = context,
            uri = uri,
            file = resolved.file,
            displayName = resolved.displayName
        )
        fun read(reference: String?, fallback: File?, label: String): String? {
            return readNearbyLyricContent(
                context = context,
                reference = reference ?: fallback?.absolutePath,
                label = label
            )
        }
        val nearbyLyric = read(
            nearbyReferences.original,
            nearbyFiles.original,
            "quick scan lyric"
        )
        val nearbyTranslatedLyric = read(
            nearbyReferences.translated,
            nearbyFiles.translated,
            "quick scan translated lyric"
        )
        val nearbyRomanizedLyric = read(
            nearbyReferences.romanized,
            nearbyFiles.romanized,
            "quick scan romanized lyric"
        )
        return LocalLyricsScanMetadata(
            lyric = if (localMetadata?.hasLyricOverride == true) {
                localMetadata.lyric
            } else {
                nearbyLyric
            },
            translatedLyric = if (localMetadata?.hasTranslatedLyricOverride == true) {
                localMetadata.translatedLyric
            } else {
                nearbyTranslatedLyric
            },
            romanizedLyric = if (localMetadata?.hasRomanizedLyricOverride == true) {
                localMetadata.romanizedLyric
            } else {
                nearbyRomanizedLyric
            }
        )
    }

    fun inspectMetadataOnly(
        context: Context,
        uri: Uri,
        resolveCoverFallback: Boolean = true
    ): LocalMediaDetails {
        val resolved = resolveInspectableLocalMedia(
            context = context,
            uri = uri,
            allowDescriptorFallback = true
        )
        val queried = resolved.queried
        val file = resolved.file
        val containerMetadata = file?.let(::parseContainerMetadata)
        val tagLibMetadata = inspectTagLibMetadata(
            context = context,
            uri = resolved.playableUri,
            file = file,
            includeEmbeddedAssets = false,
            includeAudioProperties = false
        )
        val retrieverMetadata = readRetrieverTextMetadata(context, resolved.playableUri)
        val title = pickReadableLocalTitle(
            sourceUri = uri,
            fallbackTitle = resolved.fallbackTitle,
            tagLibMetadata?.title,
            retrieverMetadata.title,
            containerMetadata?.title,
            queried.title
        ) ?: resolved.fallbackTitle
        val artist = tagLibMetadata?.artist
            ?: retrieverMetadata.artist
            ?: retrieverMetadata.albumArtist
            ?: containerMetadata?.artist?.takeIf { it.isNotBlank() }
            ?: queried.artist?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.music_unknown_artist)
        val album = tagLibMetadata?.album
            ?: retrieverMetadata.album
            ?: containerMetadata?.album?.takeIf { it.isNotBlank() }
            ?: queried.album?.takeIf { it.isNotBlank() }
        val usesFallbackAlbum = album == null
        val resolvedAlbum = album ?: context.getString(R.string.local_files)
        val localMetadata = readLocalMetadataSidecar(
            context = context,
            sourceUri = uri,
            file = file,
            displayName = resolved.displayName
        )
        val coverUri = if (resolveCoverFallback) {
            runCatching {
                resolveCoverUri(context, uri)
            }.onFailure {
                NPLogger.w(TAG, "resolve metadata-only cover failed for $uri: ${it.message}")
            }.getOrNull()
        } else {
            null
        }

        return LocalMediaDetails(
            sourceUri = uri,
            displayName = resolved.displayName,
            title = title,
            artist = artist,
            album = resolvedAlbum,
            usesFallbackAlbum = usesFallbackAlbum,
            albumArtist = tagLibMetadata?.albumArtist
                ?: retrieverMetadata.albumArtist
                ?: containerMetadata?.albumArtist,
            composer = tagLibMetadata?.composer
                ?: retrieverMetadata.composer
                ?: containerMetadata?.composer,
            genre = tagLibMetadata?.genre
                ?: retrieverMetadata.genre
                ?: containerMetadata?.genre,
            year = tagLibMetadata?.year ?: retrieverMetadata.year ?: containerMetadata?.year,
            trackNumber = tagLibMetadata?.trackNumber
                ?: retrieverMetadata.trackNumber
                ?: containerMetadata?.trackNumber,
            discNumber = tagLibMetadata?.discNumber
                ?: retrieverMetadata.discNumber
                ?: containerMetadata?.discNumber,
            durationMs = tagLibMetadata?.durationMs
                ?: retrieverMetadata.durationMs
                ?: queried.durationMs
                ?: 0L,
            fileExtension = resolved.fileExtension,
            mimeType = queried.mimeType ?: retrieverMetadata.mimeType,
            audioMimeType = null,
            bitrateKbps = tagLibMetadata?.bitrateKbps ?: retrieverMetadata.bitrateKbps,
            sampleRateHz = tagLibMetadata?.sampleRateHz ?: retrieverMetadata.sampleRateHz,
            channelCount = tagLibMetadata?.channelCount,
            bitsPerSample = null,
            sizeBytes = queried.sizeBytes ?: file?.length(),
            lastModifiedMs = queried.lastModifiedMs ?: file?.lastModified(),
            filePath = file?.absolutePath ?: queried.filePath,
            coverUri = coverUri,
            coverSource = null,
            lyricContent = localMetadata?.lyric,
            lyricPath = null,
            lyricSource = null,
            originalTitle = title,
            originalArtist = tagLibMetadata?.artist
                ?: retrieverMetadata.artist
                ?: containerMetadata?.artist?.takeIf { it.isNotBlank() }
                ?: queried.artist?.takeIf { it.isNotBlank() }
                ?: artist,
            embeddedCover = false,
            sourceStableKey = tagLibMetadata?.sourceStableKey,
            translatedLyricContent = localMetadata?.translatedLyric,
            romanizedLyricContent = localMetadata?.romanizedLyric
        )
    }

    fun resolveCoverUri(context: Context, song: SongItem): String? {
        val uri = song.localMediaUri() ?: return null
        return resolveCoverUri(context, uri)
    }

    /**
     * 列表恢复时只检查已经生成的缩略图, 避免重新打开音频并解析内嵌图片
     */
    fun peekCachedEmbeddedCoverUri(context: Context, song: SongItem): String? {
        return embeddedCoverCacheLookupKeys(song)
            .asSequence()
            .flatMap { key -> sequenceOf(key, "$key#taglib") }
            .mapNotNull { key -> findCachedEmbeddedCover(context, key) }
            .firstOrNull()
    }

    internal fun embeddedCoverCacheLookupKeys(song: SongItem): List<String> {
        val localUri = song.localMediaUri()
        return listOfNotNull(
            song.localFilePath,
            localUri
                ?.takeIf { uri -> uri.scheme.equals("file", ignoreCase = true) }
                ?.path,
            song.mediaUri,
            localUri?.toString()
        )
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }

    fun resolveCoverUri(context: Context, uri: Uri): String? {
        val resolved = runCatching {
            resolveInspectableLocalMedia(
                context = context,
                uri = uri,
                allowDescriptorFallback = true
            )
        }.getOrElse {
            NPLogger.w(TAG, "resolve cover source failed for $uri: ${it.message}")
            return null
        }
        val cacheKey = localCoverLookupKey(uri, resolved)
        cachedLocalCoverLookup(cacheKey)?.let { cached ->
            cached.coverUri?.let { return it }

            // Do not retain an empty nearby-cover result. A user can add artwork
            // beside an unchanged audio file without restarting the app.
            findNearbyCover(resolved.file)?.toURI()?.toString()?.let { nearbyCover ->
                rememberLocalCoverLookup(cacheKey, nearbyCover)
                return nearbyCover
            }
            return null
        }

        val resolvedCover = findNearbyCover(resolved.file)?.toURI()?.toString()
            ?: findCachedEmbeddedCover(context, resolved.resolvedPath ?: uri.toString())
            ?: findCachedEmbeddedCover(context, "${resolved.resolvedPath ?: uri}#taglib")
            ?: extractEmbeddedCoverWithRetriever(context, uri, resolved)
            ?: extractEmbeddedCoverWithTagLib(context, uri, resolved)
        rememberLocalCoverLookup(cacheKey, resolvedCover)
        return resolvedCover
    }

    fun inspect(context: Context, uri: Uri): LocalMediaDetails {
        val resolved = resolveInspectableLocalMedia(context, uri)
        val queried = resolved.queried
        val resolvedPath = resolved.resolvedPath
        val file = resolved.file
        val playableUri = resolved.playableUri
        val displayName = resolved.displayName
        val fallbackTitle = resolved.fallbackTitle
        val fileExtension = resolved.fileExtension
        val containerMetadata = file?.let(::parseContainerMetadata)
        val tagLibMetadata = inspectTagLibMetadata(
            context = context,
            uri = playableUri,
            file = file
        )
        val nearbyCover = findNearbyCover(file)
        val nearbyLyricFiles = findNearbyLyricFiles(file)
        val nearbyLyricReferences = findNearbyLyricReferences(
            context = context,
            uri = uri,
            file = file,
            displayName = displayName
        )
        val nearbyLyricContent = readNearbyLyricContent(
            context = context,
            reference = nearbyLyricReferences.original
                ?: nearbyLyricFiles.original?.absolutePath,
            label = "lyric"
        )
        val nearbyTranslatedLyricContent = readNearbyLyricContent(
            context = context,
            reference = nearbyLyricReferences.translated
                ?: nearbyLyricFiles.translated?.absolutePath,
            label = "translated lyric"
        )
        val nearbyRomanizedLyricContent = readNearbyLyricContent(
            context = context,
            reference = nearbyLyricReferences.romanized
                ?: nearbyLyricFiles.romanized?.absolutePath,
            label = "romanized lyric"
        )
        val localMetadata = readLocalMetadataSidecar(
            context = context,
            sourceUri = uri,
            file = file,
            displayName = displayName
        )
        val hasEffectiveExternalLyric =
            localMetadata?.hasLyricOverride != true && !nearbyLyricContent.isNullOrBlank()
        val effectiveLyricContent = if (localMetadata?.hasLyricOverride == true) {
            localMetadata.lyric
        } else {
            resolveEffectiveLocalLyricContent(
                sidecarContent = nearbyLyricContent,
                embeddedContent = tagLibMetadata?.lyrics
            )
        }
        val effectiveTranslatedLyricContent = if (
            localMetadata?.hasTranslatedLyricOverride == true
        ) {
            localMetadata.translatedLyric
        } else {
            resolveEffectiveLocalLyricContent(
                sidecarContent = nearbyTranslatedLyricContent,
                embeddedContent = tagLibMetadata?.translatedLyrics
            )
        }
        val effectiveRomanizedLyricContent = if (
            localMetadata?.hasRomanizedLyricOverride == true
        ) {
            localMetadata.romanizedLyric
        } else {
            nearbyRomanizedLyricContent?.takeIf(String::isNotBlank)
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, playableUri)
            val audioTrackTechInfo = inspectAudioTrackInfo(context, playableUri)
            val retrieverTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val rawTitle = pickReadableLocalTitle(
                sourceUri = uri,
                fallbackTitle = fallbackTitle,
                tagLibMetadata?.title,
                retrieverTitle,
                containerMetadata?.title,
                queried.title
            )
            val title = rawTitle ?: fallbackTitle
            val artist = tagLibMetadata?.artist
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.takeIf { it.isNotBlank() }
                ?: containerMetadata?.artist?.takeIf { it.isNotBlank() }
                ?: queried.artist?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.music_unknown_artist)
            val album = tagLibMetadata?.album
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() }
                ?: containerMetadata?.album?.takeIf { it.isNotBlank() }
                ?: queried.album?.takeIf { it.isNotBlank() }
            val usesFallbackAlbum = album == null
            val resolvedAlbum = album ?: context.getString(R.string.local_files)
            val albumArtist = tagLibMetadata?.albumArtist
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: containerMetadata?.albumArtist?.takeIf { it.isNotBlank() }
            val composer = tagLibMetadata?.composer
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                ?.takeIf { it.isNotBlank() }
                ?: containerMetadata?.composer?.takeIf { it.isNotBlank() }
            val genre = tagLibMetadata?.genre
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.takeIf { it.isNotBlank() }
                ?: containerMetadata?.genre?.takeIf { it.isNotBlank() }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: tagLibMetadata?.durationMs
                ?: queried.durationMs
                ?: 0L
            val mimeType = queried.mimeType
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.takeIf { it.isNotBlank() }
            val bitrateKbps = audioTrackTechInfo?.bitrateKbps
                ?: tagLibMetadata?.bitrateKbps
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull()
                    ?.let { max(0, (it + 500) / 1000) }
            val sampleRateHz = audioTrackTechInfo?.sampleRateHz
                ?: tagLibMetadata?.sampleRateHz
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toIntOrNull()
                } else {
                    null
                }
            val bitsPerSample = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                    ?.toIntOrNull()
            } else {
                null
            }
            val year = tagLibMetadata?.year
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.toIntOrNull()
                ?: containerMetadata?.year
            val trackNumber = tagLibMetadata?.trackNumber ?: parseIndexedMetadata(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            ) ?: containerMetadata?.trackNumber
            val discNumber = tagLibMetadata?.discNumber ?: (
                parseIndexedMetadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                )
            ) ?: containerMetadata?.discNumber

            val embeddedPicture = retriever.embeddedPicture
            val embeddedCover = embeddedPicture != null && embeddedPicture.isNotEmpty()
            val embeddedCoverUri = if (embeddedCover) {
                saveEmbeddedCover(context, resolvedPath ?: uri.toString(), embeddedPicture)
            } else {
                null
            }
            val tagLibCoverUri = if (embeddedCoverUri == null) {
                tagLibMetadata?.coverBytes
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { saveEmbeddedCover(context, "${resolvedPath ?: uri}#taglib", it) }
            } else {
                null
            }
            val effectiveNearbyCover = if (embeddedCoverUri == null && tagLibCoverUri == null) nearbyCover else null

            LocalMediaDetails(
                sourceUri = uri,
                displayName = displayName,
                title = title,
                artist = artist,
                album = resolvedAlbum,
                usesFallbackAlbum = usesFallbackAlbum,
                albumArtist = albumArtist,
                composer = composer,
                genre = genre,
                year = year,
                trackNumber = trackNumber,
                discNumber = discNumber,
                durationMs = durationMs,
                fileExtension = fileExtension,
                mimeType = mimeType,
                audioMimeType = audioTrackTechInfo?.audioMimeType,
                bitrateKbps = bitrateKbps,
                sampleRateHz = sampleRateHz,
                channelCount = audioTrackTechInfo?.channelCount,
                bitsPerSample = bitsPerSample,
                sizeBytes = queried.sizeBytes ?: file?.length() ?: resolveSizeFromAssetDescriptor(context, uri),
                lastModifiedMs = queried.lastModifiedMs ?: file?.lastModified(),
                filePath = file?.absolutePath ?: queried.filePath,
                coverUri = embeddedCoverUri ?: tagLibCoverUri ?: effectiveNearbyCover?.toURI()?.toString(),
                coverSource = when {
                    embeddedCoverUri != null -> context.getString(R.string.local_song_cover_embedded)
                    tagLibCoverUri != null -> context.getString(R.string.local_song_cover_embedded)
                    effectiveNearbyCover != null -> context.getString(R.string.local_song_cover_external)
                    else -> null
                },
                lyricContent = effectiveLyricContent,
                lyricPath = resolveEffectiveLocalLyricPath(
                    reference = localMetadata?.reference
                        ?.takeIf { localMetadata.hasLyricOverride }
                        ?: nearbyLyricReferences.original
                        ?: nearbyLyricFiles.original?.absolutePath,
                    content = effectiveLyricContent
                ),
                lyricSource = when {
                    hasEffectiveExternalLyric -> context.getString(R.string.local_song_lyric_external)
                    !effectiveLyricContent.isNullOrBlank() -> context.getString(R.string.local_song_lyric_embedded)
                    else -> null
                },
                translatedLyricContent = effectiveTranslatedLyricContent,
                romanizedLyricContent = effectiveRomanizedLyricContent,
                originalTitle = title,
                originalArtist = tagLibMetadata?.artist ?: containerMetadata?.artist ?: queried.artist ?: artist,
                embeddedCover = embeddedCover || tagLibCoverUri != null,
                sourceStableKey = tagLibMetadata?.sourceStableKey
            )
        } catch (error: Exception) {
            NPLogger.w(TAG, "inspect metadata fallback for $uri: ${error.message}")
            val rawTitle = pickReadableLocalTitle(
                sourceUri = uri,
                fallbackTitle = fallbackTitle,
                tagLibMetadata?.title,
                containerMetadata?.title,
                queried.title
            )
            val title = rawTitle ?: fallbackTitle
            val artist = tagLibMetadata?.artist
                ?: containerMetadata?.artist?.takeIf { it.isNotBlank() }
                ?: queried.artist?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.music_unknown_artist)
            val album = tagLibMetadata?.album
                ?: containerMetadata?.album?.takeIf { it.isNotBlank() }
                ?: queried.album?.takeIf { it.isNotBlank() }
            val usesFallbackAlbum = album == null
            val resolvedAlbum = album ?: context.getString(R.string.local_files)
            val tagLibCoverUri = tagLibMetadata?.coverBytes
                ?.takeIf { it.isNotEmpty() }
                ?.let { saveEmbeddedCover(context, "${resolvedPath ?: uri}#taglib", it) }

            LocalMediaDetails(
                sourceUri = uri,
                displayName = displayName,
                title = title,
                artist = artist,
                album = resolvedAlbum,
                usesFallbackAlbum = usesFallbackAlbum,
                albumArtist = tagLibMetadata?.albumArtist ?: containerMetadata?.albumArtist,
                composer = tagLibMetadata?.composer ?: containerMetadata?.composer,
                genre = tagLibMetadata?.genre ?: containerMetadata?.genre,
                year = tagLibMetadata?.year ?: containerMetadata?.year,
                trackNumber = tagLibMetadata?.trackNumber ?: containerMetadata?.trackNumber,
                discNumber = tagLibMetadata?.discNumber ?: containerMetadata?.discNumber,
                durationMs = tagLibMetadata?.durationMs ?: queried.durationMs ?: 0L,
                fileExtension = fileExtension,
                mimeType = queried.mimeType,
                audioMimeType = null,
                bitrateKbps = tagLibMetadata?.bitrateKbps,
                sampleRateHz = tagLibMetadata?.sampleRateHz,
                channelCount = tagLibMetadata?.channelCount,
                bitsPerSample = null,
                sizeBytes = queried.sizeBytes ?: file?.length() ?: resolveSizeFromAssetDescriptor(context, uri),
                lastModifiedMs = queried.lastModifiedMs ?: file?.lastModified(),
                filePath = file?.absolutePath ?: queried.filePath,
                coverUri = tagLibCoverUri ?: nearbyCover?.toURI()?.toString(),
                coverSource = when {
                    tagLibCoverUri != null -> context.getString(R.string.local_song_cover_embedded)
                    nearbyCover != null -> context.getString(R.string.local_song_cover_external)
                    else -> null
                },
                lyricContent = effectiveLyricContent,
                lyricPath = resolveEffectiveLocalLyricPath(
                    reference = localMetadata?.reference
                        ?.takeIf { localMetadata.hasLyricOverride }
                        ?: nearbyLyricReferences.original
                        ?: nearbyLyricFiles.original?.absolutePath,
                    content = effectiveLyricContent
                ),
                lyricSource = when {
                    hasEffectiveExternalLyric -> context.getString(R.string.local_song_lyric_external)
                    !effectiveLyricContent.isNullOrBlank() -> context.getString(R.string.local_song_lyric_embedded)
                    else -> null
                },
                translatedLyricContent = effectiveTranslatedLyricContent,
                romanizedLyricContent = effectiveRomanizedLyricContent,
                originalTitle = title,
                originalArtist = tagLibMetadata?.artist
                    ?: containerMetadata?.artist?.takeIf { it.isNotBlank() }
                    ?: queried.artist?.takeIf { it.isNotBlank() }
                    ?: artist,
                embeddedCover = tagLibCoverUri != null,
                sourceStableKey = tagLibMetadata?.sourceStableKey
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveInspectableLocalMedia(
        context: Context,
        uri: Uri,
        allowDescriptorFallback: Boolean = true
    ): ResolvedInspectableLocalMedia {
        require(uri.isSupportedLocalMediaUri()) { "Unsupported local media uri: $uri" }
        val queried = queryContentInfo(context, uri)
        val resolvedPath = directFilePath(uri)
            ?: queried.filePath
            ?: if (allowDescriptorFallback) resolvePathFromDescriptor(context, uri) else null
        val file = resolvedPath?.let(::File)?.takeIf(File::exists)
        val playableUri = when {
            uri.scheme.equals("content", ignoreCase = true) -> uri
            uri.scheme.equals("android.resource", ignoreCase = true) -> uri
            else -> file?.let(Uri::fromFile) ?: uri
        }
        val displayName = file?.name
            ?: queried.displayName
            ?: resolvedPath?.substringAfterLast(File.separatorChar)
            ?: playableUri.lastPathSegment
            ?: uri.toString()
        val fallbackTitle = displayName.substringBeforeLast('.').ifBlank {
            context.getString(R.string.local_files)
        }
        val fileExtension = file?.extension?.takeIf { it.isNotBlank() }
            ?: displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        return ResolvedInspectableLocalMedia(
            queried = queried,
            resolvedPath = resolvedPath,
            file = file,
            playableUri = playableUri,
            displayName = displayName,
            fallbackTitle = fallbackTitle,
            fileExtension = fileExtension
        )
    }

    private fun buildQuickLocalMediaDetails(
        context: Context,
        sourceUri: Uri,
        resolved: ResolvedInspectableLocalMedia,
        audioTrackTechInfo: AudioTrackTechInfo?
    ): LocalMediaDetails {
        val selectedMetadata = selectQuickLocalMetadata(
            title = pickReadableLocalTitle(
                sourceUri = sourceUri,
                fallbackTitle = resolved.fallbackTitle,
                resolved.queried.title
            ) ?: resolved.fallbackTitle,
            queriedArtist = resolved.queried.artist,
            queriedAlbum = resolved.queried.album,
            queriedDurationMs = resolved.queried.durationMs,
            unknownArtistLabel = context.getString(R.string.music_unknown_artist),
            defaultAlbumLabel = context.getString(R.string.local_files)
        )
        return LocalMediaDetails(
            sourceUri = sourceUri,
            displayName = resolved.displayName,
            title = selectedMetadata.title,
            artist = selectedMetadata.artist,
            album = selectedMetadata.album,
            usesFallbackAlbum = selectedMetadata.usesFallbackAlbum,
            albumArtist = null,
            composer = null,
            genre = null,
            year = null,
            trackNumber = null,
            discNumber = null,
            durationMs = selectedMetadata.durationMs,
            fileExtension = resolved.fileExtension,
            mimeType = resolved.queried.mimeType,
            audioMimeType = audioTrackTechInfo?.audioMimeType,
            bitrateKbps = audioTrackTechInfo?.bitrateKbps,
            sampleRateHz = audioTrackTechInfo?.sampleRateHz,
            channelCount = audioTrackTechInfo?.channelCount,
            bitsPerSample = null,
            sizeBytes = resolved.queried.sizeBytes ?: resolved.file?.length(),
            lastModifiedMs = resolved.queried.lastModifiedMs ?: resolved.file?.lastModified(),
            filePath = resolved.file?.absolutePath,
            coverUri = null,
            coverSource = null,
            lyricContent = null,
            lyricPath = null,
            lyricSource = null,
            originalTitle = selectedMetadata.title,
            originalArtist = selectedMetadata.artist,
            embeddedCover = false,
            romanizedLyricContent = null
        )
    }

    fun toSongItem(details: LocalMediaDetails): SongItem {
        val stableSource = details.filePath?.takeIf { it.isNotBlank() } ?: details.sourceUri.toString()
        val playbackSource = preferredLocalMediaReference(
            localFilePath = details.filePath,
            mediaUri = details.sourceUri.toString()
        ) ?: stableSource
        val stableId = computeStableSongId(stableSource)
        return SongItem(
            id = stableId,
            name = details.title,
            artist = details.artist,
            album = normalizeLocalAlbumIdentity(details.album, details.usesFallbackAlbum),
            albumId = 0L,
            durationMs = details.durationMs,
            coverUrl = details.coverUri,
            mediaUri = playbackSource,
            matchedLyric = details.lyricContent,
            matchedTranslatedLyric = details.translatedLyricContent,
            originalName = details.originalTitle ?: details.title,
            originalArtist = details.originalArtist ?: details.artist,
            originalCoverUrl = details.coverUri,
            localFileName = details.displayName,
            localFilePath = details.filePath,
            channelId = "local",
            audioId = stableId.toString(),
            sourceStableKey = details.sourceStableKey
        )
    }

    suspend fun shareSongFile(context: Context, song: SongItem): Boolean {
        val uri = song.toShareableLocalUri(context) ?: return false
        val shareLabel = song.localFileName
            ?.takeIf { it.isNotBlank() }
            ?: song.localFilePath?.let(::File)?.name
            ?: song.name
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = when {
                song.localMediaUri()?.scheme.equals("content", ignoreCase = true) -> {
                    context.contentResolver.getType(uri) ?: "audio/*"
                }
                else -> "audio/*"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, shareLabel)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(context.contentResolver, shareLabel, uri)
        }
        return withContext(Dispatchers.Main.immediate) {
            context.startActivity(
                Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }
    }

    fun downloadDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(baseDir, "NeriPlayer")
    }

    // 优先直接分享受控目录中的文件，无法直出时再复制到缓存 staging 后分享
    fun prepareShareableFile(context: Context, sourceFile: File): File {
        return prepareShareableFileInDirectory(
            sourceFile = sourceFile,
            shareDir = File(context.cacheDir, SHARED_LOCAL_MEDIA_DIR)
        )
    }

    internal fun prepareShareableContentFile(
        context: Context,
        sourceUri: Uri,
        suggestedName: String
    ): File? {
        val shareDir = File(context.cacheDir, SHARED_LOCAL_MEDIA_DIR).apply { mkdirs() }
        val extension = suggestedName.substringAfterLast('.', "")
            .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
            ?.let { ".${it.lowercase()}" }
            .orEmpty()
        val target = File(
            shareDir,
            "content-${stableKey(sourceUri.toString())}$extension"
        )
        val partial = File(shareDir, ".${target.name}.partial")
        partial.delete()
        return runCatching {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Unable to open content URI for sharing: $sourceUri")
            input.use { source ->
                partial.outputStream().use { output ->
                    source.copyTo(output)
                }
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace staged share file: ${target.name}")
            }
            if (!partial.renameTo(target)) {
                throw IOException("Unable to commit staged share file: ${target.name}")
            }
            target
        }.onFailure { error ->
            partial.delete()
            NPLogger.w(
                LOCAL_MEDIA_SHARE_TAG,
                "Failed to stage content URI for sharing: $sourceUri: ${error.message}"
            )
        }.getOrNull()
    }

    internal fun prepareShareableFileInDirectory(sourceFile: File, shareDir: File): File {
        require(sourceFile.exists()) { "Source file does not exist: ${sourceFile.absolutePath}" }
        require(sourceFile.isFile) { "Source file is not a regular file: ${sourceFile.absolutePath}" }
        shareDir.mkdirs()
        if (isFileInsideDirectory(sourceFile, shareDir)) {
            return sourceFile
        }
        val stagedFile = File(shareDir, shareableStageFileName(sourceFile))
        if (shouldRestageShareCopy(stagedFile, sourceFile)) {
            sourceFile.inputStream().use { input ->
                stagedFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            stagedFile.setLastModified(sourceFile.lastModified())
        }
        return stagedFile
    }

    internal fun shareableStageFileName(sourceFile: File): String {
        val extension = sourceFile.extension
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        return "${stableKey("${sourceFile.absolutePath}|${sourceFile.length()}|${sourceFile.lastModified()}")}$extension"
    }

    internal fun shouldRestageShareCopy(stagedFile: File, sourceFile: File): Boolean {
        return !stagedFile.exists() ||
            stagedFile.length() != sourceFile.length() ||
            stagedFile.lastModified() < sourceFile.lastModified()
    }

    fun readTextContent(context: Context, reference: String): String? {
        val bytes = when {
            reference.startsWith("/") -> runCatching { readLimitedTextFile(File(reference)) }
                .onFailure { NPLogger.w(TAG, "read bytes failed for $reference: ${it.message}") }
                .getOrNull()
            else -> runCatching {
                context.contentResolver.openInputStream(reference.toUri())?.use(::readLimitedTextStream)
            }.onFailure {
                NPLogger.w(TAG, "read stream failed for $reference: ${it.message}")
            }.getOrNull()
        } ?: return null

        return decodeTextBytes(bytes)
    }

    fun readTextFile(file: File): String? {
        val bytes = runCatching { readLimitedTextFile(file) }
            .onFailure { NPLogger.w(TAG, "read bytes failed for ${file.absolutePath}: ${it.message}") }
            .getOrNull()
            ?: return null

        return decodeTextBytes(bytes)
    }

    private fun readLocalMetadataSidecar(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String
    ): LocalMetadataSidecar? {
        val reference = resolveLocalMetadataReference(
            context = context,
            sourceUri = sourceUri,
            file = file,
            displayName = displayName
        ) ?: return null
        val raw = readTextContent(context, reference) ?: return null
        return parseLocalMetadataSidecar(reference, raw)
    }

    internal fun parseLocalMetadataSidecar(
        reference: String,
        raw: String
    ): LocalMetadataSidecar? {
        return runCatching {
            val root = JSONObject(raw)
            LocalMetadataSidecar(
                reference = reference,
                hasLyricOverride = root.has("matchedLyric") || root.has("originalLyric"),
                hasTranslatedLyricOverride = root.has("matchedTranslatedLyric") ||
                    root.has("originalTranslatedLyric"),
                hasRomanizedLyricOverride = root.has("matchedRomanizedLyric") ||
                    root.has("originalRomanizedLyric"),
                matchedLyric = root.optPresentLocalMetadataString("matchedLyric"),
                matchedTranslatedLyric = root.optPresentLocalMetadataString(
                    "matchedTranslatedLyric"
                ),
                originalLyric = root.optPresentLocalMetadataString("originalLyric"),
                originalTranslatedLyric = root.optPresentLocalMetadataString(
                    "originalTranslatedLyric"
                ),
                matchedRomanizedLyric = root.optPresentLocalMetadataString(
                    "matchedRomanizedLyric"
                ),
                originalRomanizedLyric = root.optPresentLocalMetadataString(
                    "originalRomanizedLyric"
                )
            )
        }.onFailure {
            NPLogger.w(TAG, "parse local metadata sidecar failed for $reference: ${it.message}")
        }.getOrNull()
    }

    internal fun buildLocalLyricsMetadataJson(
        existingRaw: String?,
        song: SongItem
    ): String {
        val root = existingRaw
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        song.matchedLyric?.let { root.put("matchedLyric", it) }
        song.originalLyric?.let { root.put("originalLyric", it) }
        song.matchedTranslatedLyric?.let { root.put("matchedTranslatedLyric", it) }
        song.originalTranslatedLyric?.let { root.put("originalTranslatedLyric", it) }
        return root.toString()
    }

    private fun writeLocalLyricsMetadata(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String,
        song: SongItem
    ): Boolean {
        val metadataReference = resolveLocalMetadataReference(
            context = context,
            sourceUri = sourceUri,
            file = file,
            displayName = displayName
        )
        val targetReference = metadataReference ?: createLocalMetadataReference(
            context = context,
            sourceUri = sourceUri,
            file = file,
            displayName = displayName
        ) ?: return false
        val existingRaw = readTextContent(context, targetReference)
        val existingParsed = existingRaw?.let {
            parseLocalMetadataSidecar(targetReference, it)
        }
        val updatedRaw = buildLocalLyricsMetadataJson(existingRaw, song)
        if (!writeLocalMetadataReference(context, targetReference, file, updatedRaw)) {
            return false
        }
        val stored = readTextContent(context, targetReference) ?: return false
        val parsed = parseLocalMetadataSidecar(targetReference, stored) ?: return false
        val expectedLyric = song.matchedLyric ?: song.originalLyric ?: existingParsed?.lyric
        val expectedTranslatedLyric = song.matchedTranslatedLyric
            ?: song.originalTranslatedLyric
            ?: existingParsed?.translatedLyric
        val shouldHaveLyricOverride = song.matchedLyric != null ||
            song.originalLyric != null || existingParsed?.hasLyricOverride == true
        val shouldHaveTranslatedLyricOverride = song.matchedTranslatedLyric != null ||
            song.originalTranslatedLyric != null || existingParsed?.hasTranslatedLyricOverride == true
        return (!shouldHaveLyricOverride || parsed.hasLyricOverride) &&
            parsed.lyric == expectedLyric &&
            (!shouldHaveTranslatedLyricOverride || parsed.hasTranslatedLyricOverride) &&
            parsed.translatedLyric == expectedTranslatedLyric
    }

    private fun resolveLocalMetadataReference(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String
    ): String? {
        file?.let { localFile ->
            val target = File(localFile.parentFile ?: return@let, localFile.name + LOCAL_METADATA_SUFFIX)
            if (target.isFile) return target.absolutePath
        }
        val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return null
        val parentChildren = queryDocumentChildren(
            context = context,
            baseUri = navigation.treeUri ?: navigation.baseUri,
            parentDocumentId = navigation.parentDocumentId
        )
        val metadataName = displayName + LOCAL_METADATA_SUFFIX
        return parentChildren.firstOrNull { child ->
            !child.isDirectory && child.displayName == metadataName
        }?.uri
            ?: file?.let { localFile ->
                localFile.parentFile
                    ?.let { parent -> File(parent, localFile.name + LOCAL_METADATA_SUFFIX) }
                    ?.takeIf(File::isFile)
                    ?.absolutePath
            }
    }

    private fun createLocalMetadataReference(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String
    ): String? {
        if (file != null && !sourceUri.authority.equals("com.android.providers.media.documents", true)) {
            return File(
                file.parentFile ?: return null,
                file.name + LOCAL_METADATA_SUFFIX
            ).absolutePath
        }
        val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return null
        val parentId = navigation.parentDocumentId ?: return null
        val parentUri = buildDocumentReferenceUri(
            navigation.treeUri ?: navigation.baseUri,
            parentId
        )
        return runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                "application/json",
                displayName + LOCAL_METADATA_SUFFIX
            )?.toString()
        }.onFailure {
            NPLogger.w(TAG, "create local metadata sidecar failed for $sourceUri: ${it.message}")
        }.getOrNull() ?: file?.let { localFile ->
            localFile.parentFile
                ?.let { parent -> File(parent, localFile.name + LOCAL_METADATA_SUFFIX) }
                ?.absolutePath
        }
    }

    private fun writeLocalMetadataReference(
        context: Context,
        reference: String,
        file: File?,
        content: String
    ): Boolean {
        if (file != null && reference.startsWith("/")) {
            val target = File(reference)
            val parent = target.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs()) return false
            val temp = runCatching {
                File.createTempFile(".${target.name}.", ".tmp", parent)
            }.getOrNull() ?: return false
            return runCatching {
                temp.writeText(content, Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                true
            }.onFailure {
                temp.delete()
                NPLogger.w(TAG, "write local metadata sidecar failed for $reference: ${it.message}")
            }.getOrDefault(false)
        }
        return runCatching {
            context.contentResolver.openOutputStream(reference.toUri(), "wt")
                ?.use { output -> output.write(content.toByteArray(Charsets.UTF_8)) }
                ?: return@runCatching false
            true
        }.onFailure {
            NPLogger.w(TAG, "write local metadata sidecar failed for $reference: ${it.message}")
        }.getOrDefault(false)
    }

    private fun resolveLocalDocumentNavigation(
        context: Context,
        uri: Uri
    ): LocalDocumentNavigation? {
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val treeUri = runCatching {
            val authority = uri.authority ?: return@runCatching null
            treeDocumentId?.let { DocumentsContract.buildTreeDocumentUri(authority, it) }
        }.getOrNull()
        val documentUri = if (treeUri != null && documentId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        } else {
            uri
        }
        val providerParentId = findDocumentParentId(context, documentUri)
        val slashDelimitedParentId = documentId
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() && it != documentId }
        return LocalDocumentNavigation(
            baseUri = uri,
            treeUri = treeUri,
            parentDocumentId = providerParentId ?: slashDelimitedParentId ?: treeDocumentId
        )
    }

    private fun JSONObject.optPresentLocalMetadataString(fieldName: String): String? {
        if (!has(fieldName) || isNull(fieldName)) return null
        return optString(fieldName)
    }

    private fun readLimitedTextFile(file: File): ByteArray {
        val length = file.length()
        require(length <= MAX_LOCAL_LYRIC_BYTES) { "text file is too large: $length bytes" }
        return file.inputStream().use(::readLimitedTextStream)
    }

    private fun readLimitedTextStream(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            require(total <= MAX_LOCAL_LYRIC_BYTES) { "text stream is too large: $total bytes" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeTextBytes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""

        detectBomCharset(bytes)?.let { (charset, offset) ->
            return bytes.copyOfRange(offset, bytes.size).toString(charset).normalizeDecodedText()
        }

        val utf8Text = bytes.toString(StandardCharsets.UTF_8).normalizeDecodedText()
        if (!utf8Text.contains('\uFFFD')) {
            return utf8Text
        }

        val candidates = buildList {
            add(StandardCharsets.UTF_8)
            add(StandardCharsets.UTF_16LE)
            add(StandardCharsets.UTF_16BE)
            runCatching { Charset.forName("GB18030") }.getOrNull()?.let(::add)
            runCatching { Charset.forName("GBK") }.getOrNull()?.let(::add)
        }.distinct()

        return candidates
            .map { charset -> charset to scoreDecodedText(bytes.toString(charset).normalizeDecodedText()) }
            .maxByOrNull { it.second }
            ?.first
            ?.let { bytes.toString(it).normalizeDecodedText() }
    }

    private data class QueriedContentInfo(
        val displayName: String?,
        val sizeBytes: Long?,
        val mimeType: String?,
        val lastModifiedMs: Long?,
        val filePath: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?
    )

    private fun queryContentInfo(context: Context, uri: Uri): QueriedContentInfo {
        val resolver = context.contentResolver
        directFilePath(uri)?.let { filePath ->
            val file = File(filePath)
            return QueriedContentInfo(
                displayName = file.name,
                sizeBytes = file.takeIf(File::exists)?.length(),
                mimeType = resolver.getType(Uri.fromFile(file)),
                lastModifiedMs = file.takeIf(File::exists)?.lastModified(),
                filePath = file.takeIf(File::exists)?.absolutePath,
                title = null,
                artist = null,
                album = null,
                durationMs = null
            )
        }
        val includeRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(OpenableColumns.DISPLAY_NAME)
            add(OpenableColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (includeRelativePath) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add("_data")
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
        }.toTypedArray()

        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                QueriedContentInfo(
                    displayName = cursor.getOptionalString(OpenableColumns.DISPLAY_NAME),
                    sizeBytes = cursor.getOptionalLong(OpenableColumns.SIZE),
                    mimeType = cursor.getOptionalString(MediaStore.MediaColumns.MIME_TYPE),
                    lastModifiedMs = cursor.getOptionalLong(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000),
                    filePath = resolveQueryFilePath(
                        rawPath = cursor.getOptionalString("_data"),
                        relativePath = if (includeRelativePath) {
                            cursor.getOptionalString(MediaStore.MediaColumns.RELATIVE_PATH)
                        } else {
                            null
                        },
                        displayName = cursor.getOptionalString(OpenableColumns.DISPLAY_NAME)
                    ),
                    title = cursor.getOptionalString(MediaStore.Audio.Media.TITLE),
                    artist = cursor.getOptionalString(MediaStore.Audio.Media.ARTIST),
                    album = cursor.getOptionalString(MediaStore.Audio.Media.ALBUM),
                    durationMs = cursor.getOptionalLong(MediaStore.Audio.Media.DURATION)
                )
            }
        }.getOrElse {
            NPLogger.w(TAG, "queryContentInfo failed for $uri: ${it.message}")
            null
        } ?: QueriedContentInfo(
            displayName = null,
            sizeBytes = null,
            mimeType = resolver.getType(uri),
            lastModifiedMs = null,
            filePath = null,
            title = null,
            artist = null,
            album = null,
            durationMs = null
        )
    }

    private fun resolvePathFromDescriptor(context: Context, uri: Uri): String? {
        if (!uri.isSupportedLocalMediaUri()) {
            return null
        }
        directFilePath(uri)?.let { return it }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                Os.readlink("/proc/self/fd/${descriptor.fd}")
                    .substringBefore(" (deleted)")
                    .takeIf { it.startsWith("/") && File(it).exists() }
            }
        }.getOrElse {
            NPLogger.w(TAG, "resolvePathFromDescriptor failed for $uri: ${it.message}")
            null
        }
    }

    private fun resolveQueryFilePath(
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

    private fun resolveSizeFromAssetDescriptor(context: Context, uri: Uri): Long? {
        if (!uri.isSupportedLocalMediaUri()) {
            return null
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrElse {
            NPLogger.w(TAG, "resolveSizeFromAssetDescriptor failed for $uri: ${it.message}")
            null
        }
    }

    private fun inspectAudioTrackInfo(context: Context, uri: Uri): AudioTrackTechInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, emptyMap())
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val trackMimeType = format.getOptionalString(MediaFormat.KEY_MIME)
                if (trackMimeType?.startsWith("audio/") != true) continue

                val bitrateKbps = format.getOptionalInt(MediaFormat.KEY_BIT_RATE)
                    ?.let { max(0, (it + 500) / 1000) }
                val sampleRateHz = format.getOptionalInt(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getOptionalInt(MediaFormat.KEY_CHANNEL_COUNT)
                return AudioTrackTechInfo(
                    audioMimeType = trackMimeType,
                    bitrateKbps = bitrateKbps,
                    sampleRateHz = sampleRateHz,
                    channelCount = channelCount
                )
            }
            null
        } catch (error: Exception) {
            NPLogger.w(TAG, "inspectAudioTrackInfo failed for $uri: ${error.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun readRetrieverTextMetadata(context: Context, uri: Uri): RetrieverTextMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            RetrieverTextMetadata(
                title = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                composer = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                genre = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?.extractYear(),
                trackNumber = parseIndexedMetadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ),
                discNumber = parseIndexedMetadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
                mimeType = retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull()
                    ?.let { max(0, (it + 500) / 1000) },
                sampleRateHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toIntOrNull()
                } else {
                    null
                }
            )
        } catch (error: Exception) {
            NPLogger.w(TAG, "read retriever metadata failed for $uri: ${error.message}")
            RetrieverTextMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun inspectTagLibMetadata(
        context: Context,
        uri: Uri,
        file: File?,
        includeEmbeddedAssets: Boolean = true,
        includeAudioProperties: Boolean = true
    ): TagLibMetadata? {
        return openTagLibDescriptor(context, uri, file)?.use { descriptor ->
            val metadata = runCatching {
                TagLib.getMetadata(descriptor.dup().detachFd(), includeEmbeddedAssets)
            }.getOrElse {
                NPLogger.w(TAG, "TagLib metadata failed for $uri: ${it.message}")
                null
            }
            val audioProperties = if (includeAudioProperties) {
                runCatching {
                    TagLib.getAudioProperties(descriptor.dup().detachFd())
                }.getOrElse {
                    NPLogger.w(TAG, "TagLib audio properties failed for $uri: ${it.message}")
                    null
                }
            } else {
                null
            }

            if (metadata == null && audioProperties == null) {
                return@use null
            }

            val propertyMap = metadata?.propertyMap
            val coverBytes = if (includeEmbeddedAssets) {
                metadata?.pictures
                    ?.firstOrNull { it.pictureType.equals("Front Cover", ignoreCase = true) }
                    ?.data
                    ?: metadata?.pictures?.firstOrNull()?.data
            } else {
                null
            }

            TagLibMetadata(
                title = propertyMap.readFirstValue("TITLE", "TRACKTITLE", "SUBTITLE"),
                artist = propertyMap.readFirstValue("ARTIST", "ARTISTS", "PERFORMER", "AUTHOR"),
                album = propertyMap.readFirstValue("ALBUM", "ALBUMTITLE"),
                albumArtist = propertyMap.readFirstValue("ALBUMARTIST", "ALBUM ARTIST", "ENSEMBLE"),
                composer = propertyMap.readFirstValue("COMPOSER", "WRITER"),
                genre = propertyMap.readFirstValue("GENRE"),
                year = propertyMap.readFirstValue("DATE", "YEAR", "ORIGINALDATE")?.extractYear(),
                trackNumber = parseIndexedMetadata(propertyMap.readFirstValue("TRACKNUMBER", "TRACK", "TRACKNUM")),
                discNumber = parseIndexedMetadata(propertyMap.readFirstValue("DISCNUMBER", "DISC", "DISCNUM")),
                durationMs = audioProperties?.length?.toLong()?.takeIf { it > 0L },
                bitrateKbps = audioProperties?.bitrate?.takeIf { it > 0 },
                sampleRateHz = audioProperties?.sampleRate?.takeIf { it > 0 },
                channelCount = audioProperties?.channels?.takeIf { it > 0 },
                lyrics = if (includeEmbeddedAssets) {
                    propertyMap.readFirstValue(
                        NERI_ORIGINAL_LYRICS_METADATA_KEY,
                        "LYRICS",
                        "UNSYNCEDLYRICS",
                        "DESCRIPTION"
                    )
                } else {
                    null
                },
                translatedLyrics = if (includeEmbeddedAssets) {
                    propertyMap.readFirstValue(*translatedLyricsMetadataKeys.toTypedArray())
                } else {
                    null
                },
                coverBytes = coverBytes?.takeIf { it.isNotEmpty() },
                sourceStableKey = propertyMap.readNeriSourceStableKey()
            )
        }
    }

    private fun openTagLibDescriptor(
        context: Context,
        uri: Uri,
        file: File?
    ): ParcelFileDescriptor? {
        if (!uri.isSupportedLocalMediaUri()) {
            return null
        }
        return runCatching {
            file?.let {
                ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
            } ?: context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrElse {
            NPLogger.w(TAG, "openTagLibDescriptor failed for $uri: ${it.message}")
            null
        }
    }

    private fun openWritableTagLibDescriptor(
        context: Context,
        uri: Uri,
        file: File?
    ): ParcelFileDescriptor? {
        val fileDescriptor = file?.let { localFile ->
            runCatching {
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_WRITE)
            }.getOrNull()
        }
        if (fileDescriptor != null) {
            return fileDescriptor
        }

        val contentDescriptor = if (uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "rw")
            }.getOrNull()
        } else {
            null
        }
        if (contentDescriptor != null) {
            return contentDescriptor
        }

        val fallbackDescriptor = if (!uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "rw")
            }.getOrNull()
        } else {
            null
        }
        if (fallbackDescriptor == null) {
            NPLogger.w(TAG, "open writable metadata descriptor failed for $uri")
        }
        return fallbackDescriptor
    }

    private fun loadTagLibPropertyMap(descriptor: ParcelFileDescriptor): PropertyMap? {
        return runCatching {
            TagLib.getMetadata(descriptor.dup().detachFd(), false)?.propertyMap
        }.getOrNull()
    }

    internal fun applyEditableMetadata(
        propertyMap: PropertyMap,
        title: String,
        artist: String,
        lyrics: String?,
        translatedLyrics: String?,
        audioExtension: String?,
        writeLyrics: Boolean = false,
        sourceStableKey: String? = null
    ): PropertyMap {
        val updated: PropertyMap = hashMapOf()
        propertyMap.forEach { (key, values) ->
            updated[key] = values.copyOf()
        }
        putTagValue(updated, "TITLE", title)
        putTagValue(updated, "ARTIST", artist)
        sourceStableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { key -> putTagValue(updated, "NERI_STABLE_KEY", key) }
        if (writeLyrics) {
            val externalLyrics = mergeLyricsForExternalPlayers(lyrics, translatedLyrics)
            standardLyricsMetadataKeys(audioExtension).forEach { key ->
                putTagValue(updated, key, externalLyrics.orEmpty())
            }
            putTagValue(updated, NERI_ORIGINAL_LYRICS_METADATA_KEY, lyrics)
            translatedLyricsMetadataKeys.forEach { key ->
                putTagValue(updated, key, translatedLyrics)
            }
        }
        return updated
    }

    internal fun hasExpectedEditableMetadata(
        propertyMap: PropertyMap,
        title: String,
        artist: String,
        lyrics: String?,
        translatedLyrics: String?,
        audioExtension: String?,
        expectedStandardLyrics: String? = mergeLyricsForExternalPlayers(lyrics, translatedLyrics),
        verifyStandardLyrics: Boolean = lyrics != null || translatedLyrics != null,
        verifyMissingLyrics: Boolean = false,
        sourceStableKey: String? = null
    ): Boolean {
        return hasExpectedTagValue(propertyMap, "TITLE", title) &&
            hasExpectedTagValue(propertyMap, "ARTIST", artist) &&
            (!verifyStandardLyrics || hasExpectedStandardLyrics(
                propertyMap = propertyMap,
                audioExtension = audioExtension,
                expectedLyrics = expectedStandardLyrics
            )) &&
            hasExpectedOneOfTagValues(
                propertyMap = propertyMap,
                keys = listOf(NERI_ORIGINAL_LYRICS_METADATA_KEY),
                expectedValue = lyrics,
                verifyMissing = verifyMissingLyrics
            ) &&
            hasExpectedOneOfTagValues(
                propertyMap = propertyMap,
                keys = translatedLyricsMetadataKeys,
                expectedValue = translatedLyrics,
                verifyMissing = verifyMissingLyrics
            ) &&
            (
                sourceStableKey.isNullOrBlank() ||
                    hasExpectedOneOfTagValues(
                        propertyMap = propertyMap,
                        keys = listOf("NERI_STABLE_KEY", "NERI STABLE KEY"),
                        expectedValue = sourceStableKey
                    )
                )
    }

    private fun putTagValue(propertyMap: PropertyMap, key: String, value: String?) {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            propertyMap.remove(key)
        } else {
            propertyMap[key] = arrayOf(normalized)
        }
    }

    private fun hasExpectedTagValue(
        propertyMap: PropertyMap,
        key: String,
        expectedValue: String
    ): Boolean {
        val normalized = expectedValue.trim()
        if (normalized.isBlank()) {
            return key !in propertyMap || propertyMap[key].isNullOrEmpty()
        }
        return propertyMap[key]?.any { value -> value.trim() == normalized } == true
    }

    private fun hasExpectedOneOfTagValues(
        propertyMap: PropertyMap,
        keys: List<String>,
        expectedValue: String?,
        verifyMissing: Boolean = false
    ): Boolean {
        if (expectedValue == null) {
            return !verifyMissing || keys.all { key ->
                key !in propertyMap || propertyMap[key].isNullOrEmpty()
            }
        }
        val normalized = expectedValue.trim()
        if (normalized.isBlank()) {
            return keys.all { key ->
                key !in propertyMap || propertyMap[key].isNullOrEmpty()
            }
        }
        return keys.any { key -> hasExpectedTagValue(propertyMap, key, normalized) }
    }

    private fun hasExpectedStandardLyrics(
        propertyMap: PropertyMap,
        audioExtension: String?,
        expectedLyrics: String?
    ): Boolean {
        val keys = standardLyricsMetadataKeys(audioExtension)
        if (expectedLyrics.isNullOrBlank()) {
            return keys.all { key ->
                key !in propertyMap || propertyMap[key].isNullOrEmpty()
            }
        }
        return hasExpectedOneOfTagValues(propertyMap, keys, expectedLyrics)
    }

    private sealed class EditableCoverWritePlan {
        data object Unchanged : EditableCoverWritePlan()
        data object Unreadable : EditableCoverWritePlan()
        data class Update(val pictures: Array<Picture>) : EditableCoverWritePlan()
    }

    private data class EditableMetadataSnapshot(
        val existingProperties: PropertyMap,
        val updatedProperties: PropertyMap,
        val picturePlan: EditableCoverWritePlan,
        val expectedStandardLyrics: String?,
        val sourceStableKey: String,
        val writesLyrics: Boolean,
        val clearsMissingLyrics: Boolean
    )

    private fun editableMetadataSourceStableKey(song: SongItem): String {
        return song.sourceStableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: song.songStableKey()
    }

    internal fun hasExpectedEditableCover(
        actualPictures: Array<Picture>,
        expectedPictures: Array<Picture>,
        audioExtension: String? = null
    ): Boolean {
        if (usesRolelessEditableCoverPictures(audioExtension)) {
            return editableCoverPictureListsEquivalent(
                left = actualPictures,
                right = expectedPictures,
                audioExtension = audioExtension
            )
        }
        val actualFrontCover = actualPictures.firstOrNull(::isFrontCoverPicture)
        val expectedFrontCover = expectedPictures.firstOrNull(::isFrontCoverPicture)
        return when {
            expectedFrontCover == null -> actualFrontCover == null
            actualFrontCover == null -> false
            else -> actualFrontCover.data.contentEquals(expectedFrontCover.data)
        }
    }

    private fun buildEditableCoverWritePlan(
        context: Context,
        descriptor: ParcelFileDescriptor,
        coverReference: String?,
        writeCover: Boolean,
        audioExtension: String?
    ): EditableCoverWritePlan {
        val reference = coverReference?.trim()?.takeIf(String::isNotBlank)
        val mutation = resolveEditableCoverMutation(writeCover, reference)
        if (mutation == EditableCoverMutation.UNCHANGED) return EditableCoverWritePlan.Unchanged
        val existingPictures = runCatching {
            TagLib.getPictures(descriptor.dup().detachFd())
        }.getOrElse { error ->
            NPLogger.w(TAG, "read local cover failed: ${error.message}")
            return EditableCoverWritePlan.Unreadable
        }
        if (mutation == EditableCoverMutation.CLEAR) {
            val updatedPictures = replaceEditableCoverPictures(
                existingPictures = existingPictures,
                replacementPicture = null,
                audioExtension = audioExtension
            )
            return if (
                editableCoverPictureListsEquivalent(
                    left = existingPictures,
                    right = updatedPictures,
                    audioExtension = audioExtension
                )
            ) {
                EditableCoverWritePlan.Unchanged
            } else {
                EditableCoverWritePlan.Update(updatedPictures)
            }
        }
        require(mutation == EditableCoverMutation.REPLACE)
        val replacementReference = requireNotNull(reference)
        val replacementPicture = createEditableCoverPicture(
            context = context,
            reference = replacementReference,
            audioExtension = audioExtension
        )
            ?: return EditableCoverWritePlan.Unreadable
        val updatedPictures = replaceEditableCoverPictures(
            existingPictures = existingPictures,
            replacementPicture = replacementPicture,
            audioExtension = audioExtension
        )
        if (
            editableCoverPictureListsEquivalent(
                left = existingPictures,
                right = updatedPictures,
                audioExtension = audioExtension
            )
        ) {
            return EditableCoverWritePlan.Unchanged
        }
        return EditableCoverWritePlan.Update(updatedPictures)
    }

    internal fun usesRolelessEditableCoverPictures(audioExtension: String?): Boolean {
        return audioExtension
            ?.trim()
            ?.lowercase(Locale.ROOT) in ROLELESS_COVER_PICTURE_EXTENSIONS
    }

    internal fun shouldRestoreEditablePropertiesAfterCoverWrite(
        audioExtension: String?,
        writesCover: Boolean
    ): Boolean {
        return writesCover &&
            usesRolelessEditableCoverPictures(audioExtension)
    }

    internal fun replaceEditableCoverPictures(
        existingPictures: Array<Picture>,
        replacementPicture: Picture?,
        audioExtension: String?
    ): Array<Picture> {
        if (usesRolelessEditableCoverPictures(audioExtension)) {
            return replacementPicture?.let { arrayOf<Picture>(it) } ?: emptyArray<Picture>()
        }
        val retainedPictures = existingPictures.filterNot(::isFrontCoverPicture)
        return if (replacementPicture == null) {
            retainedPictures.toTypedArray()
        } else {
            (retainedPictures + replacementPicture).toTypedArray()
        }
    }

    private fun isFrontCoverPicture(picture: Picture): Boolean {
        return picture.pictureType.equals(FRONT_COVER_PICTURE_TYPE, ignoreCase = true)
    }

    private fun editableCoverPictureListsEquivalent(
        left: Array<Picture>,
        right: Array<Picture>,
        audioExtension: String?
    ): Boolean {
        if (left.size != right.size) return false
        val rolelessPictureContainer = usesRolelessEditableCoverPictures(audioExtension)
        return left.indices.all { index ->
            val actual = left[index]
            val expected = right[index]
            actual.data.contentEquals(expected.data) && (
                rolelessPictureContainer ||
                    actual.description == expected.description &&
                    actual.pictureType.equals(expected.pictureType, ignoreCase = true) &&
                    actual.mimeType.equals(expected.mimeType, ignoreCase = true)
                )
        }
    }

    internal fun resolveEditableCoverMutation(
        writeCover: Boolean,
        coverReference: String?
    ): EditableCoverMutation {
        if (!writeCover) return EditableCoverMutation.UNCHANGED
        return if (coverReference.isNullOrBlank()) {
            EditableCoverMutation.CLEAR
        } else {
            EditableCoverMutation.REPLACE
        }
    }

    private fun String.isRemoteCoverReference(): Boolean {
        return startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true)
    }

    internal fun readEditableCoverBytes(context: Context, reference: String): ByteArray? {
        val uri = runCatching { reference.toUri() }.getOrNull()
        if (reference.isRemoteCoverReference()) {
            return readRemoteEditableCoverBytes(reference)
        }
        val localFile = when {
            reference.startsWith("/") -> File(reference)
            else -> uri
                ?.takeIf { coverUri -> coverUri.scheme.equals("file", ignoreCase = true) }
                ?.path
                ?.let(::File)
        }
        if (localFile?.isFile == true) {
            return runCatching {
                localFile.inputStream().use { input ->
                    input.readBytesLimited(MAX_EDITABLE_COVER_BYTES)
                }
            }.getOrNull()
        }
        return uri?.let { coverUri ->
            runCatching {
                context.contentResolver.openInputStream(coverUri)?.use { input ->
                    input.readBytesLimited(MAX_EDITABLE_COVER_BYTES)
                }
            }.getOrNull()
        }
    }

    private fun readRemoteEditableCoverBytes(reference: String): ByteArray? {
        return runCatching {
            val request = Request.Builder()
                .url(reference)
                .header("Accept", "image/*")
                .build()
            AppContainer.sharedOkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    NPLogger.w(TAG, "download editable cover failed: HTTP ${response.code}")
                    return@use null
                }
                val body = response.body
                if (body.contentLength() > MAX_EDITABLE_COVER_BYTES) {
                    NPLogger.w(TAG, "download editable cover exceeds size limit")
                    return@use null
                }
                body.byteStream().use { input ->
                    input.readBytesLimited(MAX_EDITABLE_COVER_BYTES)
                }.takeIf(ByteArray::isNotEmpty)
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "download editable cover failed: ${error.message}")
        }.getOrNull()
    }

    private fun createEditableCoverPicture(
        context: Context,
        reference: String,
        audioExtension: String?
    ): Picture? {
        val sourceBytes = readEditableCoverBytes(context, reference) ?: return null
        val sourceMimeType = resolveEditableCoverMimeType(context, reference, sourceBytes)
        val encodedCover = normalizeEmbeddedCoverForContainer(
            sourceBytes = sourceBytes,
            sourceMimeType = sourceMimeType,
            audioExtension = audioExtension
        )
        val finalCover = encodedCover ?: return null
        return Picture(
            data = finalCover.first,
            description = "",
            pictureType = FRONT_COVER_PICTURE_TYPE,
            mimeType = finalCover.second
        )
    }

    internal fun normalizeEmbeddedCoverForContainer(
        sourceBytes: ByteArray,
        sourceMimeType: String?,
        audioExtension: String?
    ): Pair<ByteArray, String>? {
        val normalizedMimeType = sourceMimeType?.let(::normalizeEditableCoverMimeType)
        if (
            !usesRolelessEditableCoverPictures(audioExtension) ||
                normalizedMimeType in MP4_SUPPORTED_COVER_MIME_TYPES
        ) {
            return sourceBytes to (normalizedMimeType ?: "image/jpeg")
        }
        return encodeEditableCoverAsJpeg(sourceBytes)?.let { bytes ->
            bytes to "image/jpeg"
        }
    }

    private fun encodeEditableCoverAsJpeg(sourceBytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                EDITABLE_COVER_JPEG_QUALITIES.forEach { quality ->
                    output.reset()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        val encoded = output.toByteArray()
                        if (encoded.isNotEmpty() && encoded.size <= MAX_EDITABLE_COVER_BYTES) {
                            return@use encoded
                        }
                    }
                }
                null
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun resolveEditableCoverMimeType(
        context: Context,
        reference: String,
        bytes: ByteArray
    ): String {
        val uri = runCatching { reference.toUri() }.getOrNull()
        val declaredMimeType = uri?.let { coverUri ->
            runCatching { context.contentResolver.getType(coverUri) }.getOrNull()
        }?.substringBefore(';')?.trim()?.takeIf { it.startsWith("image/", ignoreCase = true) }
        val guessedMimeType = URLConnection.guessContentTypeFromName(
            uri?.lastPathSegment ?: reference
        )?.takeIf { it.startsWith("image/", ignoreCase = true) }
        return normalizeEditableCoverMimeType(
            detectEditableCoverMimeType(bytes) ?: declaredMimeType ?: guessedMimeType ?: "image/jpeg"
        )
    }

    private fun normalizeEditableCoverMimeType(mimeType: String): String {
        return when (mimeType.lowercase(Locale.ROOT)) {
            "image/jpg", "image/pjpeg" -> "image/jpeg"
            "image/x-ms-bmp" -> "image/bmp"
            else -> mimeType.lowercase(Locale.ROOT)
        }
    }

    private fun detectEditableCoverMimeType(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
        ) {
            return "image/gif"
        }
        if (bytes.size >= 2 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) {
            return "image/bmp"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() &&
            bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() &&
            bytes[11] == 0x50.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    private fun propertyMapsEquivalent(left: PropertyMap, right: PropertyMap): Boolean {
        if (left.size != right.size) {
            return false
        }
        return left.all { (key, leftValues) ->
            right[key]?.contentEquals(leftValues) == true
        }
    }

    private fun parseContainerMetadata(file: File): ContainerMetadata? {
        if (!file.exists() || !file.isFile) return null
        return when (file.extension.lowercase()) {
            "wav", "wave" -> parseWaveMetadata(file)
            "mp1", "mp2", "mp3", "aac" -> parseId3FileMetadata(file)
            else -> parseId3FileMetadata(file)
        }
    }

    internal fun parseId3FileMetadata(file: File): ContainerMetadata? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                mergeContainerMetadata(
                    primary = readId3v2FileMetadata(raf),
                    fallback = readId3v1FileMetadata(raf)
                )
            }
        }.getOrElse {
            NPLogger.w(TAG, "parseId3FileMetadata failed for ${file.absolutePath}: ${it.message}")
            null
        }
    }

    private fun readId3v2FileMetadata(raf: RandomAccessFile): ContainerMetadata? {
        if (raf.length() < 10L) return null
        raf.seek(0)
        val header = ByteArray(10)
        raf.readFully(header)
        if (header.readAscii(0, 3) != "ID3") return null

        val tagSize = header.readSynchsafeInt(6)
        if (tagSize <= 0) return null
        val readableSize = minOf(
            raf.length(),
            10L + tagSize.toLong(),
            MAX_CONTAINER_METADATA_BYTES
        ).toInt()
        if (readableSize <= 10) return null

        raf.seek(0)
        val tagBytes = ByteArray(readableSize)
        raf.readFully(tagBytes)
        return parseId3Metadata(tagBytes)
    }

    private fun readId3v1FileMetadata(raf: RandomAccessFile): ContainerMetadata? {
        if (raf.length() < 128L) return null
        raf.seek(raf.length() - 128L)
        val tag = ByteArray(128)
        raf.readFully(tag)
        if (tag.readAscii(0, 3) != "TAG") return null

        val trackNumber = tag[125]
            .takeIf { it == 0.toByte() }
            ?.let { tag[126].toInt() and 0xFF }
            ?.takeIf { it > 0 }
        return ContainerMetadata(
            title = tag.copyOfRange(3, 33).decodeContainerText(),
            artist = tag.copyOfRange(33, 63).decodeContainerText(),
            album = tag.copyOfRange(63, 93).decodeContainerText(),
            year = tag.copyOfRange(93, 97).decodeContainerText()?.extractYear(),
            trackNumber = trackNumber
        ).takeIf { it.hasAnyValue() }
    }

    internal fun parseWaveMetadata(file: File): ContainerMetadata? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 12L) return@use null
                val riffId = raf.readFourCc() ?: return@use null
                val riffSize = raf.readLittleEndianUInt32()
                val waveId = raf.readFourCc() ?: return@use null
                if (riffId != "RIFF" || waveId != "WAVE") return@use null

                val fileLimit = minOf(raf.length(), riffSize + 8L)
                var infoMetadata: ContainerMetadata? = null
                var id3Metadata: ContainerMetadata? = null

                while (raf.filePointer + 8L <= fileLimit) {
                    val chunkId = raf.readFourCc() ?: break
                    val chunkSize = raf.readLittleEndianUInt32()
                    val chunkDataStart = raf.filePointer
                    when {
                        chunkId == "LIST" && chunkSize >= 4L -> {
                            val listType = raf.readFourCc()
                            if (listType == "INFO") {
                                val infoBytes = raf.readChunkBytes(chunkSize - 4L, fileLimit)
                                infoMetadata = mergeContainerMetadata(
                                    primary = infoMetadata,
                                    fallback = infoBytes?.let(::parseWaveInfoMetadata)
                                )
                            }
                        }

                        chunkId.trimEnd(' ') == "ID3" -> {
                            val id3Bytes = raf.readChunkBytes(chunkSize, fileLimit)
                            id3Metadata = mergeContainerMetadata(
                                primary = id3Metadata,
                                fallback = id3Bytes?.let(::parseId3Metadata)
                            )
                        }
                    }

                    val nextChunkPosition = chunkDataStart + chunkSize + (chunkSize and 1L)
                    if (nextChunkPosition <= raf.filePointer) break
                    raf.seek(minOf(nextChunkPosition, fileLimit))
                }

                mergeContainerMetadata(id3Metadata, infoMetadata)
            }
        }.getOrElse {
            NPLogger.w(TAG, "parseWaveMetadata failed for ${file.absolutePath}: ${it.message}")
            null
        }
    }

    private fun parseWaveInfoMetadata(bytes: ByteArray): ContainerMetadata? {
        var offset = 0
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var composer: String? = null
        var genre: String? = null
        var year: Int? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null

        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.readFourCc(offset) ?: break
            val chunkSize = bytes.readLittleEndianUInt32(offset + 4).coerceAtMost((bytes.size - offset - 8).toLong())
            val valueStart = offset + 8
            val valueEnd = valueStart + chunkSize.toInt()
            val value = bytes.copyOfRange(valueStart, valueEnd).decodeContainerText()

            when (chunkId) {
                "INAM" -> title = title ?: value
                "IART" -> artist = artist ?: value
                "IPRD" -> album = album ?: value
                "IAAR" -> albumArtist = albumArtist ?: value
                "IENG" -> composer = composer ?: value
                "IGNR" -> genre = genre ?: value
                "ICRD" -> year = year ?: value?.extractYear()
                "ITRK" -> trackNumber = trackNumber ?: parseIndexedMetadata(value)
                "IPRT" -> discNumber = discNumber ?: parseIndexedMetadata(value)
            }

            offset = valueEnd + (chunkSize.toInt() and 1)
        }

        return ContainerMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            composer = composer,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber
        ).takeIf { it.hasAnyValue() }
    }

    private fun parseId3Metadata(bytes: ByteArray): ContainerMetadata? {
        if (bytes.size < 10 || bytes.readAscii(0, 3) != "ID3") return null
        val majorVersion = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = bytes.readSynchsafeInt(6)
        val limit = minOf(bytes.size, 10 + tagSize)
        var offset = 10

        if (majorVersion > 2 && (flags and 0x40) != 0 && offset + 4 <= limit) {
            val extendedSize = if (majorVersion >= 4) {
                bytes.readSynchsafeInt(offset)
            } else {
                bytes.readBigEndianInt(offset)
            }
            offset += extendedSize.coerceAtLeast(0)
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var composer: String? = null
        var genre: String? = null
        var year: Int? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null

        val frameHeaderSize = if (majorVersion == 2) 6 else 10
        while (offset + frameHeaderSize <= limit) {
            val frameId = when (majorVersion) {
                2 -> bytes.readAscii(offset, 3)
                else -> bytes.readFourCc(offset)?.trimEnd(NUL_CHAR, ' ')
            }.orEmpty()
            if (frameId.isBlank()) break
            val frameSize = if (majorVersion >= 4) {
                bytes.readSynchsafeInt(offset + 4)
            } else if (majorVersion == 2) {
                bytes.readBigEndianInt24(offset + 3)
            } else {
                bytes.readBigEndianInt(offset + 4)
            }
            if (frameSize <= 0) break

            val frameDataStart = offset + frameHeaderSize
            val frameDataEnd = frameDataStart + frameSize
            if (frameDataEnd > limit) break

            val frameData = bytes.copyOfRange(frameDataStart, frameDataEnd)
            val value = decodeId3TextFrame(frameData)

            when (frameId) {
                "TIT2", "TT2" -> title = title ?: value
                "TPE1", "TP1" -> artist = artist ?: value
                "TALB", "TAL" -> album = album ?: value
                "TPE2", "TP2" -> albumArtist = albumArtist ?: value
                "TCOM", "TCM" -> composer = composer ?: value
                "TCON", "TCO" -> genre = genre ?: value
                "TDRC", "TYER", "TYE" -> year = year ?: value?.extractYear()
                "TRCK", "TRK" -> trackNumber = trackNumber ?: parseIndexedMetadata(value)
                "TPOS", "TPA" -> discNumber = discNumber ?: parseIndexedMetadata(value)
            }

            offset = frameDataEnd
        }

        return ContainerMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            composer = composer,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber
        ).takeIf { it.hasAnyValue() }
    }

    private fun mergeContainerMetadata(
        primary: ContainerMetadata?,
        fallback: ContainerMetadata?
    ): ContainerMetadata? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return ContainerMetadata(
            title = primary.title ?: fallback.title,
            artist = primary.artist ?: fallback.artist,
            album = primary.album ?: fallback.album,
            albumArtist = primary.albumArtist ?: fallback.albumArtist,
            composer = primary.composer ?: fallback.composer,
            genre = primary.genre ?: fallback.genre,
            year = primary.year ?: fallback.year,
            trackNumber = primary.trackNumber ?: fallback.trackNumber,
            discNumber = primary.discNumber ?: fallback.discNumber
        )
    }

    private fun ContainerMetadata.hasAnyValue(): Boolean {
        return !title.isNullOrBlank() ||
            !artist.isNullOrBlank() ||
            !album.isNullOrBlank() ||
            !albumArtist.isNullOrBlank() ||
            !composer.isNullOrBlank() ||
            !genre.isNullOrBlank() ||
            year != null ||
            trackNumber != null ||
            discNumber != null
    }

    private fun localCoverLookupKey(uri: Uri, resolved: ResolvedInspectableLocalMedia): String {
        val file = resolved.file
        return buildString {
            append(file?.absolutePath ?: uri.toString())
            append('|')
            append(file?.length() ?: resolved.queried.sizeBytes ?: -1L)
            append('|')
            append(file?.lastModified() ?: resolved.queried.lastModifiedMs ?: -1L)
        }
    }

    private fun cachedLocalCoverLookup(cacheKey: String): LocalCoverCacheHit? {
        synchronized(localCoverLookupCache) {
            if (!localCoverLookupCache.containsKey(cacheKey)) return null
            val coverUri = localCoverLookupCache[cacheKey]
            if (coverUri != null && !isUsableCachedCoverUri(coverUri)) {
                localCoverLookupCache.remove(cacheKey)
                return null
            }
            return LocalCoverCacheHit(coverUri)
        }
    }

    private fun isUsableCachedCoverUri(coverUri: String): Boolean {
        val uri = runCatching { coverUri.toUri() }.getOrNull() ?: return false
        if (!uri.scheme.equals("file", ignoreCase = true)) {
            return true
        }
        val path = uri.path ?: return false
        return File(path).isFile && File(path).length() > 0L
    }

    private fun rememberLocalCoverLookup(cacheKey: String, coverUri: String?) {
        val normalizedCoverUri = coverUri
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        synchronized(localCoverLookupCache) {
            localCoverLookupCache[cacheKey] = normalizedCoverUri
        }
    }

    private fun invalidateLocalCoverLookupCache(
        context: Context,
        uri: Uri,
        resolved: ResolvedInspectableLocalMedia?
    ) {
        val prefixes = buildList {
            resolved?.file?.absolutePath?.let { add("$it|") }
            add("${uri}|")
        }
        synchronized(localCoverLookupCache) {
            val iterator = localCoverLookupCache.keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (prefixes.any(key::startsWith)) {
                    iterator.remove()
                }
            }
        }
        embeddedCoverCacheKeys(uri.toString(), resolved?.resolvedPath).forEach { cacheKey ->
            val cacheFile = embeddedCoverFile(context, cacheKey)
            if (cacheFile.isFile && !cacheFile.delete()) {
                NPLogger.w(TAG, "clear stale embedded cover cache failed: ${cacheFile.name}")
            }
        }
    }

    internal fun embeddedCoverCacheKeys(
        uri: String,
        resolvedPath: String?
    ): List<String> {
        val baseKey = resolvedPath ?: uri
        return listOf(baseKey, "$baseKey#taglib")
    }

    private fun embeddedCoverCacheKeys(
        uri: Uri,
        resolved: ResolvedInspectableLocalMedia
    ): List<String> = embeddedCoverCacheKeys(uri.toString(), resolved.resolvedPath)

    private fun extractEmbeddedCoverWithRetriever(
        context: Context,
        uri: Uri,
        resolved: ResolvedInspectableLocalMedia
    ): String? {
        val uriKey = resolved.resolvedPath ?: uri.toString()
        findCachedEmbeddedCover(context, uriKey)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, resolved.playableUri)
            saveEmbeddedCover(context, uriKey, retriever.embeddedPicture)
        } catch (error: Exception) {
            NPLogger.w(TAG, "resolve embedded cover failed for $uri: ${error.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractEmbeddedCoverWithTagLib(
        context: Context,
        uri: Uri,
        resolved: ResolvedInspectableLocalMedia
    ): String? {
        val uriKey = "${resolved.resolvedPath ?: uri}#taglib"
        findCachedEmbeddedCover(context, uriKey)?.let { return it }
        val coverBytes = openTagLibDescriptor(context, resolved.playableUri, resolved.file)?.use { descriptor ->
            runCatching {
                val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), true)
                metadata?.pictures
                    ?.firstOrNull { it.pictureType.equals("Front Cover", ignoreCase = true) }
                    ?.data
                    ?: metadata?.pictures?.firstOrNull()?.data
            }.getOrElse {
                NPLogger.w(TAG, "TagLib cover failed for $uri: ${it.message}")
                null
            }
        }
        return saveEmbeddedCover(context, uriKey, coverBytes)
    }

    private fun findCachedEmbeddedCover(context: Context, uriKey: String): String? {
        val file = embeddedCoverFile(context, uriKey)
        return file
            .takeIf { it.isFile && it.length() > 0L }
            ?.toURI()
            ?.toString()
    }

    private fun embeddedCoverFile(context: Context, uriKey: String): File {
        val coverDir = File(context.filesDir, "local_audio_covers")
        return File(coverDir, "${stableKey(uriKey)}.jpg")
    }

    private fun saveEmbeddedCover(context: Context, uriKey: String, embeddedPicture: ByteArray?): String? {
        if (embeddedPicture == null || embeddedPicture.isEmpty()) return null
        val file = embeddedCoverFile(context, uriKey)
        if (file.isFile && file.length() > 0L) {
            return file.toURI().toString()
        }
        val parent = file.parentFile ?: return null
        if (!parent.isDirectory && !parent.mkdirs()) {
            NPLogger.w(TAG, "create embedded cover cache directory failed: ${parent.path}")
            return null
        }
        val cacheBytes = compactEmbeddedCoverForCache(embeddedPicture) ?: return null
        val tempFile = File(file.parentFile ?: context.filesDir, ".${file.name}.tmp")
        tempFile.writeBytes(cacheBytes)
        if (!tempFile.renameTo(file)) {
            file.writeBytes(cacheBytes)
            tempFile.delete()
        }
        return file.toURI().toString()
    }

    private fun compactEmbeddedCoverForCache(sourceBytes: ByteArray): ByteArray? {
        if (sourceBytes.size <= MAX_EMBEDDED_COVER_CACHE_BYTES) return sourceBytes

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var targetDimension = MAX_EMBEDDED_COVER_CACHE_DIMENSION_PX
        repeat(3) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = embeddedCoverCacheSampleSize(
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    targetDimension = targetDimension
                )
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, options)
                ?: return null
            try {
                encodeEmbeddedCoverForCache(bitmap)?.let { return it }
            } finally {
                bitmap.recycle()
            }
            targetDimension = (targetDimension / 2).coerceAtLeast(1)
        }
        return null
    }

    internal fun embeddedCoverCacheSampleSize(
        width: Int,
        height: Int,
        targetDimension: Int = MAX_EMBEDDED_COVER_CACHE_DIMENSION_PX
    ): Int {
        if (width <= 0 || height <= 0 || targetDimension <= 0) return 1

        var sampleSize = 1
        while (
            width.toLong() / sampleSize > targetDimension ||
                height.toLong() / sampleSize > targetDimension
        ) {
            sampleSize = sampleSize shl 1
        }
        return sampleSize
    }

    private fun encodeEmbeddedCoverForCache(bitmap: Bitmap): ByteArray? {
        return ByteArrayOutputStream().use { output ->
            EDITABLE_COVER_JPEG_QUALITIES.forEach { quality ->
                output.reset()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    return@forEach
                }
                val encoded = output.toByteArray()
                if (encoded.isNotEmpty() && encoded.size <= MAX_EMBEDDED_COVER_CACHE_BYTES) {
                    return@use encoded
                }
            }
            null
        }
    }

    internal fun findNearbyLyricFiles(
        file: File?,
        extensions: List<String> = lyricExtensions
    ): NearbyLyricFiles {
        val actualFile = file ?: return NearbyLyricFiles(null, null, null)
        val parent = actualFile.parentFile ?: return NearbyLyricFiles(null, null, null)
        val baseName = actualFile.nameWithoutExtension
        val searchDirectories = listOf(parent, File(parent, "Lyrics"))
            .filter(File::isDirectory)

        return NearbyLyricFiles(
            original = findFirstLyricSidecar(
                searchDirectories = searchDirectories,
                fileNames = lyricSidecarNames(
                    baseName = baseName,
                    kind = LyricKind.ORIGINAL,
                    extensions = extensions
                )
            ),
            translated = findFirstLyricSidecar(
                searchDirectories = searchDirectories,
                fileNames = lyricSidecarNames(
                    baseName = baseName,
                    kind = LyricKind.TRANSLATED,
                    extensions = extensions
                )
            ),
            romanized = findFirstLyricSidecar(
                searchDirectories = searchDirectories,
                fileNames = lyricSidecarNames(
                    baseName = baseName,
                    kind = LyricKind.ROMANIZED,
                    extensions = extensions
                )
            )
        )
    }

    internal fun copyNearbyLyricSidecars(
        context: Context,
        sourceUri: Uri,
        sourceDisplayName: String,
        targetFile: File
    ) {
        if (!sourceUri.scheme.equals("content", ignoreCase = true)) {
            return
        }
        val references = findNearbyLyricReferences(
            context = context,
            uri = sourceUri,
            file = null,
            displayName = sourceDisplayName
        )
        val targetLyricFiles = findNearbyLyricFiles(targetFile)
        val metadataReference = resolveLocalMetadataReference(
            context = context,
            sourceUri = sourceUri,
            file = null,
            displayName = sourceDisplayName
        )
        if (metadataReference != null) {
            copyLyricReference(
                context = context,
                reference = metadataReference,
                target = File(targetFile.parentFile ?: return, targetFile.name + LOCAL_METADATA_SUFFIX)
            )
        }
        listOf(
            Triple(references.original, targetLyricFiles.original, ""),
            Triple(references.translated, targetLyricFiles.translated, "_trans"),
            Triple(references.romanized, targetLyricFiles.romanized, "_roma")
        ).forEach { (reference, existingTarget, suffix) ->
            if (reference == null || existingTarget != null) {
                return@forEach
            }
            copyLyricReference(
                context = context,
                reference = reference,
                target = File(
                    targetFile.parentFile ?: return@forEach,
                    "${targetFile.nameWithoutExtension}$suffix.lrc"
                )
            )
        }
    }

    private fun copyLyricReference(
        context: Context,
        reference: String,
        target: File
    ) {
        if (target.exists()) return
        runCatching {
            context.contentResolver.openInputStream(reference.toUri())?.use { input ->
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: error("unable to open lyric sidecar: $reference")
        }.onFailure {
            NPLogger.w(TAG, "copy lyric sidecar failed for $reference: ${it.message}")
            target.delete()
        }
    }

    private fun readNearbyLyricContent(
        context: Context,
        reference: String?,
        label: String
    ): String? {
        return reference?.let {
            readTextContent(context, it)
                ?: run {
                    NPLogger.w(TAG, "read $label failed for $it")
                    null
                }
        }
    }

    private fun findNearbyLyricReferences(
        context: Context,
        uri: Uri,
        file: File?,
        displayName: String
    ): NearbyLyricReferences {
        val localFiles = findNearbyLyricFiles(file)
        if (!uri.scheme.equals("content", ignoreCase = true)) {
            return NearbyLyricReferences(
                original = localFiles.original?.absolutePath,
                translated = localFiles.translated?.absolutePath,
                romanized = localFiles.romanized?.absolutePath
            )
        }

        val treeDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull()
        val documentId = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull()
        val treeUri = runCatching {
            val authority = uri.authority ?: return@runCatching null
            treeDocumentId?.let { documentId ->
                DocumentsContract.buildTreeDocumentUri(authority, documentId)
            }
        }.getOrNull()
        val documentUri = if (treeUri != null && documentId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        } else {
            uri
        }
        val providerParentId = findDocumentParentId(context, documentUri)
        val slashDelimitedParentId = documentId
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() && it != documentId }
        val parentDocumentId = providerParentId
            ?: slashDelimitedParentId
            ?: treeDocumentId
        val parentChildren = queryDocumentChildren(
            context = context,
            baseUri = treeUri ?: uri,
            parentDocumentId = parentDocumentId
        )
        val audioBaseName = displayName.substringBeforeLast('.', displayName)
        val directReferences = resolveDocumentLyricReferences(
            children = parentChildren,
            baseName = audioBaseName
        )
        val lyricsDirectory = parentChildren.firstOrNull {
            it.isDirectory && it.displayName.equals("Lyrics", ignoreCase = true)
        }
        val nestedReferences = resolveDocumentLyricReferences(
            children = lyricsDirectory?.let {
                queryDocumentChildren(
                    context = context,
                    baseUri = treeUri ?: uri,
                    parentDocumentId = it.documentId
                )
            }.orEmpty(),
            baseName = audioBaseName
        )
        return NearbyLyricReferences(
            original = directReferences.original ?: nestedReferences.original
                ?: localFiles.original?.absolutePath,
            translated = directReferences.translated ?: nestedReferences.translated
                ?: localFiles.translated?.absolutePath,
            romanized = directReferences.romanized ?: nestedReferences.romanized
                ?: localFiles.romanized?.absolutePath
        )
    }

    private fun findDocumentParentId(context: Context, documentUri: Uri): String? {
        return runCatching {
            DocumentsContract.findDocumentPath(context.contentResolver, documentUri)
                ?.path
                ?.dropLast(1)
                ?.lastOrNull()
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun resolveDocumentLyricReferences(
        children: Collection<DocumentChild>,
        baseName: String
    ): NearbyLyricReferences {
        fun find(kind: LyricKind): String? {
            val names = lyricSidecarNames(baseName, kind, lyricExtensions)
            return names.firstNotNullOfOrNull { expectedName ->
                children.firstOrNull { child ->
                    !child.isDirectory && child.displayName == expectedName
                }?.uri
            }
        }
        return NearbyLyricReferences(
            original = find(LyricKind.ORIGINAL),
            translated = find(LyricKind.TRANSLATED),
            romanized = find(LyricKind.ROMANIZED)
        )
    }

    private data class DocumentChild(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
        val uri: String
    )

    private fun queryDocumentChildren(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String?
    ): List<DocumentChild> {
        val resolvedParentId = parentDocumentId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val childrenUri = runCatching {
            if (DocumentsContract.isTreeUri(baseUri)) {
                DocumentsContract.buildChildDocumentsUriUsingTree(baseUri, resolvedParentId)
            } else {
                DocumentsContract.buildChildDocumentsUri(
                    baseUri.authority ?: return@runCatching null,
                    resolvedParentId
                )
            }
        }.getOrNull() ?: return emptyList()
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
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                    return@use emptyList()
                }
                buildList {
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idIndex)?.takeIf { it.isNotBlank() }
                            ?: continue
                        val childName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                            ?: continue
                        val mimeType = cursor.getString(mimeIndex).orEmpty()
                        add(
                            DocumentChild(
                                documentId = childId,
                                displayName = childName,
                                isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                                uri = buildDocumentReferenceUri(baseUri, childId).toString()
                            )
                        )
                    }
                }
            }.orEmpty()
        }.onFailure {
            NPLogger.w(TAG, "query document lyric children failed for $baseUri: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun buildDocumentReferenceUri(baseUri: Uri, documentId: String): Uri {
        return if (DocumentsContract.isTreeUri(baseUri)) {
            DocumentsContract.buildDocumentUriUsingTree(baseUri, documentId)
        } else {
            DocumentsContract.buildDocumentUri(
                baseUri.authority ?: error("Document URI has no authority: $baseUri"),
                documentId
            )
        }
    }

    internal fun resolveEffectiveLocalLyricContent(
        sidecarContent: String?,
        embeddedContent: String?
    ): String? {
        return sidecarContent?.takeIf(String::isNotBlank)
            ?: embeddedContent?.takeIf(String::isNotBlank)
    }

    internal fun resolveEffectiveLocalLyricPath(
        reference: String?,
        content: String?
    ): String? {
        return reference?.takeIf { !content.isNullOrBlank() }
    }

    private fun findFirstLyricSidecar(
        searchDirectories: List<File>,
        fileNames: List<String>
    ): File? {
        return searchDirectories.asSequence()
            .flatMap { directory -> fileNames.asSequence().map { File(directory, it) } }
            .firstOrNull(File::isFile)
    }

    private fun lyricSidecarNames(
        baseName: String,
        kind: LyricKind,
        extensions: List<String>
    ): List<String> {
        val prefixes = when (kind) {
            LyricKind.ORIGINAL -> listOf(baseName)
            LyricKind.TRANSLATED -> listOf("${baseName}_trans")
            LyricKind.ROMANIZED -> listOf(
                "${baseName}_roma",
                "${baseName}_romalrc",
                "${baseName}_romanized"
            )
        }
        return buildList {
            prefixes.forEach { prefix ->
                extensions.forEach { extension ->
                    add("$prefix.$extension")
                }
                if ("lrc" in extensions) {
                    add("$prefix.lrc.txt")
                }
            }
        }
    }

    private enum class LyricKind {
        ORIGINAL,
        TRANSLATED,
        ROMANIZED
    }

    internal fun findNearbyCover(file: File?): File? {
        val actualFile = file ?: return null
        val parent = actualFile.parentFile ?: return null
        val baseName = actualFile.nameWithoutExtension
        val cacheKey = nearbyCoverLookupKey(actualFile, parent, baseName)
        cachedNearbyCover(cacheKey)?.let { hit ->
            return hit.path?.let(::File)?.takeIf { it.exists() }
        }

        val cover = findNearbyCoverUncached(parent, baseName)
        rememberNearbyCover(cacheKey, cover)
        return cover
    }

    private fun findNearbyCoverUncached(parent: File, baseName: String): File? {
        imageExtensions.forEach { ext ->
            val sameName = File(parent, "$baseName.$ext")
            if (sameName.exists()) return sameName
        }

        findDirectoryCover(parent)?.let { return it }

        val coverDir = File(parent, "Covers")
        if (coverDir.exists()) {
            imageExtensions.forEach { ext ->
                val nested = File(coverDir, "$baseName.$ext")
                if (nested.exists()) return nested
            }
        }

        return null
    }

    private fun findDirectoryCover(parent: File): File? {
        val cacheKey = directoryCoverLookupKey(parent)
        cachedDirectoryCover(cacheKey)?.let { hit ->
            return hit.path?.let(::File)?.takeIf { it.exists() }
        }

        val cover = coverFileNames.firstNotNullOfOrNull { candidate ->
            imageExtensions.firstNotNullOfOrNull { ext ->
                File(parent, "$candidate.$ext").takeIf { it.exists() }
            }
        }
        rememberDirectoryCover(cacheKey, cover)
        return cover
    }

    private fun nearbyCoverLookupKey(file: File, parent: File, baseName: String): String {
        return "${parent.absolutePath}|${parent.lastModified()}|${file.length()}|$baseName"
    }

    private fun directoryCoverLookupKey(parent: File): String {
        return "${parent.absolutePath}|${parent.lastModified()}"
    }

    private fun cachedNearbyCover(cacheKey: String): FilePathCacheHit? {
        synchronized(nearbyCoverLookupCache) {
            return nearbyCoverLookupCache[cacheKey]?.let(::FilePathCacheHit)
        }
    }

    private fun rememberNearbyCover(cacheKey: String, cover: File?) {
        val coverPath = cover?.absolutePath ?: return
        synchronized(nearbyCoverLookupCache) {
            nearbyCoverLookupCache[cacheKey] = coverPath
        }
    }

    private fun cachedDirectoryCover(cacheKey: String): FilePathCacheHit? {
        synchronized(directoryCoverLookupCache) {
            return directoryCoverLookupCache[cacheKey]?.let(::FilePathCacheHit)
        }
    }

    private fun rememberDirectoryCover(cacheKey: String, cover: File?) {
        val coverPath = cover?.absolutePath ?: return
        synchronized(directoryCoverLookupCache) {
            directoryCoverLookupCache[cacheKey] = coverPath
        }
    }

    private fun parseIndexedMetadata(value: String?): Int? {
        val raw = value?.substringBefore('/')?.trim().orEmpty()
        return raw.toIntOrNull()
    }

    private fun pickReadableLocalTitle(
        sourceUri: Uri,
        fallbackTitle: String,
        vararg candidates: String?
    ): String? {
        return candidates.firstNotNullOfOrNull { candidate ->
            candidate
                ?.trim()
                ?.takeIf { it.isNotBlank() && isReadableLocalTitleCandidate(it, sourceUri, fallbackTitle) }
        }
    }

    private fun isReadableLocalTitleCandidate(
        candidate: String,
        sourceUri: Uri,
        fallbackTitle: String
    ): Boolean {
        val normalized = candidate.trim()
        if (normalized.isBlank()) return false
        if (normalized.startsWith("content://", ignoreCase = true)) return false
        if (normalized.startsWith("file://", ignoreCase = true)) return false
        return normalized != sourceUri.lastPathSegment || normalized == fallbackTitle
    }

    private fun computeStableSongId(source: String): Long {
        return stableKey(source).take(16).toULong(16).toLong()
    }

    private fun stableKey(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun directFilePath(uri: Uri): String? {
        val path = when {
            uri.scheme.equals("file", ignoreCase = true) -> uri.path
            uri.scheme.isNullOrBlank() && !uri.path.isNullOrBlank() && uri.path!!.startsWith("/") -> uri.path
            else -> null
        } ?: return null
        return path.takeIf { File(it).exists() }
    }

    private fun detectBomCharset(bytes: ByteArray): Pair<Charset, Int>? {
        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8 to 3

            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE to 2

            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE to 2

            else -> null
        }
    }

    private fun ByteArray.decodeContainerText(): String? {
        if (isEmpty()) return null
        val trimmed = dropLastWhile { it == 0.toByte() || it == 32.toByte() }.toByteArray()
        if (trimmed.isEmpty()) return null

        detectBomCharset(trimmed)?.let { (charset, offset) ->
            return trimmed.copyOfRange(offset, trimmed.size)
                .toString(charset)
                .normalizeDecodedText()
                .trim(NUL_CHAR, ' ')
                .takeIf { it.isNotBlank() }
        }

        val candidates = buildList {
            add(StandardCharsets.UTF_8)
            add(StandardCharsets.UTF_16LE)
            add(StandardCharsets.UTF_16BE)
            runCatching { Charset.forName("GB18030") }.getOrNull()?.let(::add)
            runCatching { Charset.forName("GBK") }.getOrNull()?.let(::add)
            runCatching { Charset.forName("windows-1252") }.getOrNull()?.let(::add)
            add(StandardCharsets.ISO_8859_1)
        }.distinct()

        return candidates
            .map { charset ->
                charset to scoreDecodedText(trimmed.toString(charset).normalizeDecodedText().trim(NUL_CHAR, ' '))
            }
            .maxByOrNull { it.second }
            ?.first
            ?.let { trimmed.toString(it).normalizeDecodedText().trim(NUL_CHAR, ' ') }
            ?.takeIf { it.isNotBlank() }
    }

    private fun decodeId3TextFrame(frameData: ByteArray): String? {
        if (frameData.isEmpty()) return null
        val content = frameData.copyOfRange(1, frameData.size)
        val charset = when (frameData[0].toInt() and 0xFF) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        return content.toString(charset)
            .normalizeDecodedText()
            .trim(NUL_CHAR, ' ')
            .takeIf { it.isNotBlank() }
    }

    private fun String.extractYear(): Int? {
        val match = Regex("(19|20)\\d{2}").find(this) ?: return null
        return match.value.toIntOrNull()
    }

    private fun scoreDecodedText(text: String): Int {
        val replacementPenalty = text.count { it == REPLACEMENT_CHAR } * 200
        val nulPenalty = text.count { it == NUL_CHAR } * 200
        val controlPenalty = text.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' } * 40
        val blankPenalty = if (text.isBlank()) 200 else 0
        val lyricBonus = if (text.contains('[') && text.contains(']')) 20 else 0
        val latinLetterDigitBonus = text.count(Char::isAsciiLetterOrDigit) * 2
        val cjkBonus = text.count(Char::isCjkUnifiedIdeograph) * 4
        return 1000 - replacementPenalty - nulPenalty - controlPenalty - blankPenalty +
            lyricBonus + latinLetterDigitBonus + cjkBonus
    }

    private fun String.normalizeDecodedText(): String = replace(BOM_CHAR.toString(), "")
}

private fun android.database.Cursor.getOptionalString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    if (index == -1 || isNull(index)) return null
    return getString(index)
}

private fun android.database.Cursor.getOptionalLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    if (index == -1 || isNull(index)) return null
    return getLong(index)
}

private fun MediaMetadataRetriever.extractNonBlankMetadata(keyCode: Int): String? {
    return extractMetadata(keyCode)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun Map<String, Array<String>>?.readFirstValue(vararg keys: String): String? {
    val propertyMap = this ?: return null
    return keys.firstNotNullOfOrNull { key ->
        propertyMap.entries.firstOrNull { (entryKey, _) -> entryKey.equals(key, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.replace(BOM_CHAR.toString(), "")
            ?.trim(NUL_CHAR, ' ')
            ?.takeIf { it.isNotBlank() }
    }
}

private fun Map<String, Array<String>>?.readNeriSourceStableKey(): String? {
    readFirstValue("NERI_STABLE_KEY", "NERI STABLE KEY")
        ?.let { return it }

    return readFirstValue("COMMENT")?.let { comment ->
        runCatching {
            JSONObject(comment).optString("stableKey")
                .trim()
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

private fun RandomAccessFile.readFourCc(): String? {
    val bytes = ByteArray(4)
    val read = read(bytes)
    if (read != 4) return null
    return bytes.toString(StandardCharsets.US_ASCII)
}

private fun RandomAccessFile.readLittleEndianUInt32(): Long {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b3 == -1) return -1L
    return (b0.toLong() and 0xFF) or
        ((b1.toLong() and 0xFF) shl 8) or
        ((b2.toLong() and 0xFF) shl 16) or
        ((b3.toLong() and 0xFF) shl 24)
}

private fun RandomAccessFile.readChunkBytes(chunkSize: Long, fileLimit: Long): ByteArray? {
    if (chunkSize <= 0L) return ByteArray(0)
    val readableSize = minOf(chunkSize, fileLimit - filePointer, MAX_CONTAINER_METADATA_BYTES)
    if (readableSize <= 0L) return null
    val data = ByteArray(readableSize.toInt())
    val read = read(data)
    return if (read <= 0) null else data.copyOf(read)
}

private fun ByteArray.readAscii(offset: Int, length: Int): String? {
    if (offset < 0 || length <= 0 || offset + length > size) return null
    return copyOfRange(offset, offset + length).toString(StandardCharsets.US_ASCII)
}

private fun ByteArray.readFourCc(offset: Int): String? {
    if (offset < 0 || offset + 4 > size) return null
    return copyOfRange(offset, offset + 4).toString(StandardCharsets.US_ASCII)
}

private fun ByteArray.readLittleEndianUInt32(offset: Int): Long {
    if (offset < 0 || offset + 4 > size) return 0L
    return (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
}

private fun ByteArray.readBigEndianInt(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return 0
    return ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)
}

private fun ByteArray.readBigEndianInt24(offset: Int): Int {
    if (offset < 0 || offset + 3 > size) return 0
    return ((this[offset].toInt() and 0xFF) shl 16) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        (this[offset + 2].toInt() and 0xFF)
}

private fun ByteArray.readSynchsafeInt(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return 0
    return ((this[offset].toInt() and 0x7F) shl 21) or
        ((this[offset + 1].toInt() and 0x7F) shl 14) or
        ((this[offset + 2].toInt() and 0x7F) shl 7) or
        (this[offset + 3].toInt() and 0x7F)
}

private fun Char.isAsciiLetterOrDigit(): Boolean {
    return this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
}

private fun Char.isCjkUnifiedIdeograph(): Boolean {
    val code = code
    return code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xF900..0xFAFF
}

private fun MediaFormat.getOptionalInt(key: String): Int? {
    if (!containsKey(key)) return null
    return runCatching { getInteger(key) }.getOrNull()
}

private fun MediaFormat.getOptionalString(key: String): String? {
    if (!containsKey(key)) return null
    return runCatching { getString(key) }.getOrNull()
}
