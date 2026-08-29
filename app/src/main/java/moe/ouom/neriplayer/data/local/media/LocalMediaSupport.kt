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
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.system.Os
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey as songStableKey
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.readBytesLimited
import moe.ouom.neriplayer.util.media.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.NERI_ROMANIZED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.mergeLyricsForExternalPlayers
import moe.ouom.neriplayer.util.media.standardLyricsMetadataKeys
import moe.ouom.neriplayer.util.media.translatedLyricsMetadataKeys
import moe.ouom.neriplayer.util.network.isFileInsideDirectory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.Normalizer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.net.URLConnection
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import androidx.core.net.toUri
import okhttp3.Request

private const val LOCAL_MEDIA_SHARE_TAG = "LocalMediaSupport"
private const val MEDIA_STORE_AUTHORITY = "media"
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
private const val LOCAL_LYRICS_CACHE_TTL_MS = 750L
private const val DOCUMENT_CHILDREN_CACHE_LIMIT = 512
private const val DOCUMENT_CHILDREN_CACHE_TTL_MS = 750L
private const val EMPTY_DOCUMENT_REFRESH_CONFIRMATION_COUNT = 2
private const val SAF_CHILDREN_QUERY_RETRY_COUNT = 3
private const val SAF_WRITE_READBACK_RETRY_COUNT = 3
private const val DOCUMENT_NAVIGATION_CACHE_LIMIT = 512
private const val LOCAL_LYRICS_PERF_LOG_LIMIT = 96
private const val MAX_MEDIASTORE_DURATION_QUERY_IDS = 400
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
private const val EDITABLE_METADATA_WRITE_BUDGET_MS = 3_000L
private val SAF_WRITE_READBACK_DELAYS_MS = longArrayOf(0L, 8L, 24L)
private const val LOCAL_METADATA_SUFFIX = ".npmeta.json"
private const val LEGACY_DOWNLOAD_ROOT = "/storage/emulated/0/neriplayer-download"
private val LOCAL_METADATA_PLACEHOLDERS = setOf(
    "<unknown>",
    "<unknown artist>",
    "<unknown album>",
    "unknown",
    "unknown artist",
    "unknown album",
    "未知",
    "未知歌手",
    "未知艺术家",
    "未知专辑"
)
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

internal data class LocalKnownSidecarReferences(
    val lyrics: NearbyLyricReferences,
    val metadata: String? = null,
    val cover: String? = null
)

internal data class LocalLyricsScanMetadata(
    val lyric: String?,
    val translatedLyric: String?,
    val romanizedLyric: String?,
    val hasOriginalSidecar: Boolean = false,
    val hasTranslatedSidecar: Boolean = false,
    val hasRomanizedSidecar: Boolean = false,
    val embeddedLyric: String? = null,
    val embeddedTranslatedLyric: String? = null,
    val embeddedRomanizedLyric: String? = null,
    val sourceResolved: Boolean = false
)

internal data class EmbeddedLyricsReadOptions(
    val includeEmbeddedAssets: Boolean,
    val includeEmbeddedLyrics: Boolean,
    val includeAudioProperties: Boolean
)

internal val embeddedLyricsReadOptions = EmbeddedLyricsReadOptions(
    includeEmbeddedAssets = false,
    includeEmbeddedLyrics = true,
    includeAudioProperties = false
)

internal fun isLocalLyricsSourceResolved(
    scannedSource: Boolean,
    embeddedSource: Boolean
): Boolean = scannedSource || embeddedSource

private data class DirectLocalLyricsInspection(
    val original: String?,
    val translated: String?,
    val romanized: String?,
    val metadataOriginal: String?,
    val metadataTranslated: String?,
    val metadataRomanized: String?,
    val hasOriginalSidecar: Boolean,
    val hasTranslatedSidecar: Boolean,
    val hasRomanizedSidecar: Boolean
)

internal data class LocalMetadataSidecar(
    val reference: String,
    val name: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val customName: String? = null,
    val customArtist: String? = null,
    val originalName: String? = null,
    val originalArtist: String? = null,
    val stableKey: String? = null,
    val songId: Long? = null,
    val channelId: String? = null,
    val audioId: String? = null,
    val subAudioId: String? = null,
    val playlistContextId: String? = null,
    val coverPath: String? = null,
    val coverUrl: String? = null,
    val originalCoverUrl: String? = null,
    val customCoverUrl: String? = null,
    val durationMs: Long = 0L,
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

internal fun combineEditableMetadataWriteOutcome(
    directOutcome: LocalMediaMetadataWriteOutcome,
    lyricsSidecarWritten: Boolean,
    coverSidecarWritten: Boolean,
    allowSidecarAuthoritativeFallback: Boolean = false
): LocalMediaMetadataWriteOutcome {
    if (!lyricsSidecarWritten || !coverSidecarWritten) {
        return LocalMediaMetadataWriteOutcome.FAILED
    }
    if (allowSidecarAuthoritativeFallback &&
        directOutcome in setOf(
            LocalMediaMetadataWriteOutcome.FAILED,
            LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE
        )
    ) {
        // 部分 WAV 等容器不能由 TagLib 回写，但完整侧载已经通过读回校验。
        // 本地播放器以后以侧载为准，此时保存动作不应被不可用的嵌入路径否决。
        return LocalMediaMetadataWriteOutcome.SUCCESS
    }
    return directOutcome
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

internal fun isMediaStoreAuthority(authority: String?): Boolean {
    return authority.equals("media", ignoreCase = true) ||
        authority.equals("com.android.providers.media.documents", ignoreCase = true)
}

internal fun isMediaStoreUri(uri: Uri): Boolean {
    return uri.scheme?.equals("content", ignoreCase = true) == true &&
        isMediaStoreAuthority(uri.authority)
}

internal fun isMediaStoreSidecarReference(reference: String?): Boolean {
    val normalized = reference?.trim()?.lowercase(Locale.ROOT) ?: return false
    return normalized.startsWith("content://media/") ||
        normalized.startsWith("content://com.android.providers.media.documents/")
}

private fun isExternalStorageDocumentUri(uri: Uri): Boolean {
    return uri.scheme?.equals("content", ignoreCase = true) == true &&
        uri.authority?.equals("com.android.externalstorage.documents", ignoreCase = true) == true
}

internal fun shouldUseDocumentSidecarMutation(uri: Uri): Boolean {
    return uri.scheme?.equals("content", ignoreCase = true) == true &&
        (isExternalStorageDocumentUri(uri) || isMediaStoreUri(uri))
}

internal fun isMediaStoreCoverReference(reference: String): Boolean {
    val normalized = reference.trim().lowercase(Locale.ROOT)
    return normalized.startsWith("content://media/external/audio/albumart/")
}

internal enum class CoverReferenceValidation {
    USABLE,
    INVALID,
    UNAVAILABLE
}

/**
 * checks that a local cover reference still points to readable image data
 */
internal fun isUsableCoverReference(context: Context, reference: String): Boolean {
    return validateCoverReference(context, reference) == CoverReferenceValidation.USABLE
}

internal fun validateCoverReference(
    context: Context,
    reference: String
): CoverReferenceValidation {
    val uri = runCatching { reference.trim().toUri() }.getOrNull()
        ?: return CoverReferenceValidation.INVALID
    return validateCoverReference(context, uri)
}

internal fun validateCoverReference(
    context: Context,
    uri: Uri
): CoverReferenceValidation {
    val normalized = uri.toString().trim()
    if (normalized.isEmpty()) return CoverReferenceValidation.INVALID
    if (
        normalized.startsWith("http://", ignoreCase = true) ||
        normalized.startsWith("https://", ignoreCase = true)
    ) {
        return CoverReferenceValidation.USABLE
    }
    return when {
        uri.scheme.equals("file", ignoreCase = true) -> {
            if (uri.path?.let(::File)?.let(::isUsableCoverFile) == true) {
                CoverReferenceValidation.USABLE
            } else {
                CoverReferenceValidation.INVALID
            }
        }
        uri.scheme.equals("content", ignoreCase = true) -> {
            validateContentCoverReference(context, uri)
        }
        uri.scheme.isNullOrBlank() -> {
            if (
                uri.path?.takeIf { it.startsWith("/") }
                    ?.let(::File)
                    ?.let(::isUsableCoverFile) == true
            ) {
                CoverReferenceValidation.USABLE
            } else {
                CoverReferenceValidation.INVALID
            }
        }
        else -> CoverReferenceValidation.USABLE
    }
}

private fun validateContentCoverReference(
    context: Context,
    uri: Uri
): CoverReferenceValidation {
    return try {
        val stream = context.contentResolver.openInputStream(uri)
        if (stream != null) {
            stream.use { input ->
                if (hasDecodableImage(input)) {
                    CoverReferenceValidation.USABLE
                } else {
                    CoverReferenceValidation.INVALID
                }
            }
        } else {
            validateContentCoverDescriptor(context, uri)
        }
    } catch (_: SecurityException) {
        CoverReferenceValidation.UNAVAILABLE
    } catch (_: FileNotFoundException) {
        CoverReferenceValidation.INVALID
    } catch (_: Exception) {
        validateContentCoverDescriptor(context, uri)
    }
}

private fun validateContentCoverDescriptor(
    context: Context,
    uri: Uri
): CoverReferenceValidation {
    return try {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return CoverReferenceValidation.INVALID
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            if (hasDecodableImage(input)) {
                CoverReferenceValidation.USABLE
            } else {
                CoverReferenceValidation.INVALID
            }
        }
    } catch (_: SecurityException) {
        CoverReferenceValidation.UNAVAILABLE
    } catch (_: FileNotFoundException) {
        CoverReferenceValidation.INVALID
    } catch (_: Exception) {
        CoverReferenceValidation.UNAVAILABLE
    }
}

private fun isUsableCoverFile(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    return runCatching {
        file.inputStream().use(::hasDecodableImage)
    }.getOrDefault(false)
}

private fun hasDecodableImage(input: InputStream): Boolean {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeStream(input, null, options)
    return options.outWidth > 0 && options.outHeight > 0
}

internal fun isReadableLocalFile(file: File): Boolean {
    if (!file.isFile) return false
    return runCatching {
        file.inputStream().use { }
        true
    }.getOrDefault(false)
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
    private val localLyricsPerfLogCount = AtomicInteger()
    private val lyricExtensions = listOf("lrc", "txt")
    private val coverFileNames = listOf("cover", "folder", "front")
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private data class LocalCoverCacheHit(val coverUri: String?)
    private data class FilePathCacheHit(val path: String?)
    private data class LocalLyricsCacheEntry(
        val value: LocalLyricsScanMetadata,
        val cachedAtMs: Long
    )
    private val localLyricsLookupCache = object : LinkedHashMap<String, LocalLyricsCacheEntry>(
        LOCAL_LYRICS_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LocalLyricsCacheEntry>
        ): Boolean {
            return size > LOCAL_LYRICS_LOOKUP_CACHE_LIMIT
        }
    }
    private data class DocumentChildrenCacheEntry(
        val children: List<DocumentChild>,
        val cachedAtMs: Long,
        val isComplete: Boolean
    )
    private val documentChildrenCache = object : LinkedHashMap<String, DocumentChildrenCacheEntry>(
        DOCUMENT_CHILDREN_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DocumentChildrenCacheEntry>
        ): Boolean {
            return size > DOCUMENT_CHILDREN_CACHE_LIMIT
        }
    }
    private val consecutiveEmptyDocumentRefreshes = ConcurrentHashMap<String, Int>()
    private data class DocumentNavigationCacheEntry(
        val navigation: LocalDocumentNavigation?,
        val cachedAtMs: Long
    )
    private val documentNavigationCache = object : LinkedHashMap<String, DocumentNavigationCacheEntry>(
        DOCUMENT_NAVIGATION_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DocumentNavigationCacheEntry>
        ): Boolean {
            return size > DOCUMENT_NAVIGATION_CACHE_LIMIT
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
    private val mediaStoreAlbumArtCache = object : LinkedHashMap<String, String?>(
        NEARBY_COVER_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > NEARBY_COVER_LOOKUP_CACHE_LIMIT
        }
    }

    private data class AudioTrackTechInfo(
        val audioMimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channelCount: Int?,
        val durationMs: Long?
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
        val romanizedLyrics: String? = null,
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
                romanizedLyrics == other.romanizedLyrics &&
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
            result = 31 * result + (romanizedLyrics?.hashCode() ?: 0)
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
            .takeMeaningfulLocalMetadata()
            ?: unknownArtistLabel
        val album = queriedAlbum
            .takeMeaningfulLocalMetadata()
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
        val startedAtMs = SystemClock.elapsedRealtime()
        val candidates = editableLocalMediaUriCandidates(context, song)
        if (candidates.isEmpty()) {
            return@withContext LocalMediaMetadataWriteOutcome.NOT_WRITABLE
        }

        var fallbackOutcome = LocalMediaMetadataWriteOutcome.NOT_WRITABLE
        candidates.forEach { sourceUri ->
            val directTransaction = writeEditableMetadataDirectTransaction(
                context = context,
                song = song,
                sourceUri = sourceUri,
                coverReference = coverReference,
                writeCover = writeCover,
                writeLyrics = writeLyrics
            )
            val directOutcome = directTransaction.outcome
            val stagedAttempted = shouldAttemptStagedContentMetadataWrite(sourceUri, song, directOutcome)
            val outcome = if (stagedAttempted) {
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
            // 嵌入标签和侧载文件是两条独立的恢复路径。TagLib 暂不支持某种
            // 容器时仍必须保存 Lyrics 和 npmeta，不能让嵌入失败阻断侧载重建
            // MediaStore 路径可能能通过 stat 但仍会被 scoped storage 拒绝读取
            // 这类来源始终沿 SAF 引用写入
            val localFile = resolveEditableSidecarFile(context, sourceUri)
            val displayName = song.localFileName
                ?.takeIf(String::isNotBlank)
                ?: sourceUri.lastPathSegment.orEmpty()
            val knownSidecarReferences = resolveContentSidecarReferences(
                context = context,
                sourceUri = sourceUri,
                file = localFile,
                displayName = displayName
            )
            val lyricsSidecarWritten = if (writeLyrics) {
                writeLocalLyricsSidecars(
                    context = context,
                    sourceUri = sourceUri,
                    file = localFile,
                    displayName = displayName,
                    song = song,
                    knownReferences = knownSidecarReferences.lyricReferences
                )
            } else {
                true
            }
            val coverSidecarWritten = if (writeCover) {
                writeLocalCoverSidecar(
                    context = context,
                    sourceUri = sourceUri,
                    file = localFile,
                    displayName = displayName,
                    coverReference = coverReference,
                    stableIdentityKey = editableMetadataSourceStableKey(song)
                )
            } else {
                true
            }
            val metadataCoverReference = if (writeCover && !coverReference.isNullOrBlank()) {
                findNearbyCoverReference(
                    context = context,
                    uri = sourceUri,
                    file = localFile,
                    displayName = displayName
                ) ?: coverReference
            } else {
                null
            }
            val metadataSidecarWritten = writeLocalLyricsMetadata(
                context = context,
                sourceUri = sourceUri,
                file = localFile,
                displayName = displayName,
                song = song,
                knownReference = knownSidecarReferences.metadataReference,
                writeFullMetadata = true,
                writeLyricFields = writeLyrics,
                coverReference = metadataCoverReference,
                clearCoverReference = writeCover && coverReference.isNullOrBlank()
            )
            val sidecarWritten = lyricsSidecarWritten && metadataSidecarWritten
            val finalOutcome = combineEditableMetadataWriteOutcome(
                directOutcome = outcome,
                lyricsSidecarWritten = sidecarWritten,
                coverSidecarWritten = coverSidecarWritten,
                allowSidecarAuthoritativeFallback = true
            )
            if (!sidecarWritten && outcome == LocalMediaMetadataWriteOutcome.SUCCESS) {
                directTransaction.rollback?.invoke()
                NPLogger.w(TAG, "write local metadata sidecar failed for $sourceUri")
            } else if (!sidecarWritten) {
                NPLogger.w(
                    TAG,
                    "write local metadata sidecar failed after embedded write failure: $sourceUri"
                )
            }
            logEditableMetadataWriteTiming(
                sourceUri = sourceUri,
                startedAtMs = startedAtMs,
                outcome = finalOutcome,
                mode = if (stagedAttempted) "staged" else "direct"
            )
            if (finalOutcome == LocalMediaMetadataWriteOutcome.SUCCESS) {
                return@withContext finalOutcome
            }
            fallbackOutcome = selectEditableMetadataWriteFallback(
                current = fallbackOutcome,
                candidate = finalOutcome
            )
        }
        logEditableMetadataWriteTiming(
            sourceUri = candidates.lastOrNull(),
            startedAtMs = startedAtMs,
            outcome = fallbackOutcome,
            mode = "fallback"
        )
        fallbackOutcome
    }

    private fun logEditableMetadataWriteTiming(
        sourceUri: Uri?,
        startedAtMs: Long,
        outcome: LocalMediaMetadataWriteOutcome,
        mode: String
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val message = "local metadata write finished: uri=$sourceUri, " +
            "mode=$mode, outcome=$outcome, elapsedMs=$elapsedMs"
        if (elapsedMs >= EDITABLE_METADATA_WRITE_BUDGET_MS) {
            NPLogger.w(TAG, "$message, overBudget=true")
        } else {
            NPLogger.d(TAG, "$message, overBudget=false")
        }
    }

    internal suspend fun writeLocalLyricsSidecars(
        context: Context,
        song: SongItem
    ): Boolean = withContext(Dispatchers.IO) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val candidates = editableLocalMediaUriCandidates(context, song)
        if (candidates.isEmpty()) return@withContext false
        val written = candidates.any { sourceUri ->
            val file = resolveEditableSidecarFile(context, sourceUri)
            writeLocalLyricsSidecars(
                context = context,
                sourceUri = sourceUri,
                file = file,
                displayName = song.localFileName
                    ?.takeIf(String::isNotBlank)
                    ?: sourceUri.lastPathSegment.orEmpty(),
                song = song
            )
        }
        if (written) clearLyricsLookupCache()
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val message = "local lyric sidecar write finished: song=${song.name}, " +
            "written=$written, candidates=${candidates.size}, elapsedMs=$elapsedMs"
        if (elapsedMs >= EDITABLE_METADATA_WRITE_BUDGET_MS) {
            NPLogger.w(TAG, "$message, overBudget=true")
        } else {
            NPLogger.d(TAG, "$message, overBudget=false")
        }
        written
    }

    internal suspend fun writeLocalMetadataSidecar(
        context: Context,
        song: SongItem,
        writeLyrics: Boolean = false,
        coverReference: String? = null,
        clearCoverReference: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates = editableLocalMediaUriCandidates(context, song)
        if (candidates.isEmpty()) return@withContext false
        val written = candidates.any { sourceUri ->
            val file = resolveEditableSidecarFile(context, sourceUri)
            val displayName = song.localFileName
                ?.takeIf(String::isNotBlank)
                ?: sourceUri.lastPathSegment.orEmpty()
            val references = resolveContentSidecarReferences(
                context = context,
                sourceUri = sourceUri,
                file = file,
                displayName = displayName
            )
            val knownReference = references.metadataReference
            val effectiveCoverReference = if (
                !coverReference.isNullOrBlank() && !clearCoverReference
            ) {
                findNearbyCoverReference(
                    context = context,
                    uri = sourceUri,
                    file = file,
                    displayName = displayName
                ) ?: coverReference
            } else {
                coverReference
            }
            writeLocalLyricsMetadata(
                context = context,
                sourceUri = sourceUri,
                file = file,
                displayName = displayName,
                song = song,
                knownReference = knownReference,
                writeFullMetadata = true,
                writeLyricFields = writeLyrics,
                coverReference = effectiveCoverReference,
                clearCoverReference = clearCoverReference
            )
        }
        if (written) {
            clearLyricsLookupCache()
        }
        written
    }

    /**
     * 检查歌词侧载是否被外部删除，编辑器保存时需要据此重建文件
     */
    internal fun needsLyricSidecarRepair(
        context: Context,
        song: SongItem
    ): Boolean {
        val expectedOriginal = song.matchedLyric ?: song.originalLyric
        val expectedTranslated = song.matchedTranslatedLyric
            ?: song.originalTranslatedLyric
        val expectedRomanized = song.matchedRomanizedLyric
            ?: song.originalRomanizedLyric
        if (expectedOriginal == null && expectedTranslated == null && expectedRomanized == null) {
            return false
        }
        val inspected = runCatching {
            inspectLyricsFast(
                context = context,
                song = song,
                includeStoredFallback = false,
                includeEmbeddedFallback = false,
                forceRefresh = true
            )
        }.getOrElse { error ->
            NPLogger.w(TAG, "check lyric sidecar repair failed: ${error.message}")
            return true
        }
        return shouldRebuildLyricSidecars(
            expectedOriginal = expectedOriginal != null,
            expectedTranslated = expectedTranslated != null,
            expectedRomanized = expectedRomanized != null,
            hasOriginalSidecar = inspected.hasOriginalSidecar,
            hasTranslatedSidecar = inspected.hasTranslatedSidecar,
            hasRomanizedSidecar = inspected.hasRomanizedSidecar
        )
    }

    internal fun shouldRebuildLyricSidecars(
        expectedOriginal: Boolean,
        expectedTranslated: Boolean,
        expectedRomanized: Boolean,
        hasOriginalSidecar: Boolean,
        hasTranslatedSidecar: Boolean,
        hasRomanizedSidecar: Boolean
    ): Boolean {
        return (expectedOriginal && !hasOriginalSidecar) ||
            (expectedTranslated && !hasTranslatedSidecar) ||
            (expectedRomanized && !hasRomanizedSidecar)
    }

    internal suspend fun writeLocalCoverSidecar(
        context: Context,
        song: SongItem,
        coverReference: String?,
        writeCover: Boolean,
        stableIdentityKey: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates = editableLocalMediaUriCandidates(context, song)
        if (candidates.isEmpty() || !writeCover) return@withContext true
        val resolvedStableIdentityKey = stableIdentityKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: editableMetadataSourceStableKey(song)
        candidates.any { sourceUri ->
            val file = resolveEditableSidecarFile(context, sourceUri)
            writeLocalCoverSidecar(
                context = context,
                sourceUri = sourceUri,
                file = file,
                displayName = song.localFileName
                    ?.takeIf(String::isNotBlank)
                    ?: sourceUri.lastPathSegment.orEmpty(),
                coverReference = coverReference,
                stableIdentityKey = resolvedStableIdentityKey
            )
        }.also { written ->
            if (written) clearCoverLookupCache()
        }
    }

    private fun editableLocalMediaUriCandidates(
        context: Context,
        song: SongItem
    ): List<Uri> {
        val directCandidates = song.localMediaUriCandidates()
        val safCandidates = directCandidates.asSequence()
            .filter(::isMediaStoreUri)
            .mapNotNull { source -> resolveWritableLocalMediaUri(context, source) }
            .toList()
        return (safCandidates + directCandidates)
            .distinctBy(Uri::toString)
            .sortedBy { uri ->
                when {
                    uri.scheme.equals("file", ignoreCase = true) -> 0
                    isMediaStoreUri(uri) -> 2
                    else -> 1
                }
            }
    }

    private fun resolveEditableSidecarFile(context: Context, sourceUri: Uri): File? {
        if (shouldUseDocumentSidecarMutation(sourceUri)) return null
        return runCatching { resolveLocalFile(context, sourceUri) }.getOrNull()
    }

    private fun writeLocalCoverSidecar(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String,
        coverReference: String?,
        stableIdentityKey: String?
    ): Boolean {
        val mutation = resolveEditableCoverMutation(
            writeCover = true,
            coverReference = coverReference
        )
        val localFile = file.takeUnless {
            shouldUseDocumentSidecarMutation(sourceUri)
        }
        if (localFile != null) {
            return writeLocalFileCoverSidecar(
                context = context,
                file = localFile,
                coverReference = coverReference,
                mutation = mutation,
                stableIdentityKey = stableIdentityKey
            )
        }
        if (shouldSkipLocalCoverSidecar(sourceUri.toString(), localFile)) {
            val navigation = try {
                resolveLocalDocumentNavigation(context, sourceUri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (navigation?.parentDocumentId.isNullOrBlank()) {
                return true
            }
        }
        if (!sourceUri.scheme.equals("content", ignoreCase = true)) {
            return false
        }
        return writeDocumentCoverSidecar(
            context = context,
            sourceUri = sourceUri,
            displayName = displayName,
            coverReference = coverReference,
            mutation = mutation,
            stableIdentityKey = stableIdentityKey
        )
    }

    internal fun shouldSkipLocalCoverSidecar(sourceReference: String, file: File?): Boolean {
        val authority = sourceReference
            .substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
        return file == null && isMediaStoreAuthority(authority)
    }

    private fun writeLocalFileCoverSidecar(
        context: Context,
        file: File,
        coverReference: String?,
        mutation: EditableCoverMutation,
        stableIdentityKey: String?
    ): Boolean {
        val parent = file.parentFile ?: return false
        val coverDirectory = findCoversDirectory(parent) ?: File(parent, "Covers")
        val baseName = file.nameWithoutExtension
        val existingSpecificFiles = imageExtensions
            .flatMap { extension ->
                localCoverSidecarNames(baseName, extension, stableIdentityKey)
                    .map { name -> File(coverDirectory, name) }
            }
            .filter(File::isFile)
        val existingParentSpecificFiles = imageExtensions.map { extension ->
            File(parent, "$baseName.$extension")
        }.filter(File::isFile)
        if (mutation == EditableCoverMutation.CLEAR) {
            return (existingSpecificFiles + existingParentSpecificFiles).all { candidate ->
                !candidate.exists() || candidate.delete()
            }
        }
        val reference = coverReference?.trim()?.takeIf(String::isNotBlank) ?: return false
        val bytes = readEditableCoverBytes(context, reference) ?: return false
        val mimeType = resolveEditableCoverMimeType(context, reference, bytes)
        val extension = coverExtensionForMimeType(mimeType)
        val target = File(
            coverDirectory,
            localCoverSidecarName(baseName, extension, stableIdentityKey)
        )
        if (!writeBytesFileAtomically(target, bytes)) return false
        (existingSpecificFiles + existingParentSpecificFiles).filter { it != target }.forEach { old ->
            if (old.exists() && !old.delete()) return false
        }
        return target.isFile && target.length() == bytes.size.toLong()
    }

    private fun writeDocumentCoverSidecar(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        coverReference: String?,
        mutation: EditableCoverMutation,
        stableIdentityKey: String?
    ): Boolean {
        val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return false
        val parentId = navigation.parentDocumentId ?: return false
        val baseUri = navigation.treeUri ?: navigation.baseUri
        val audioBaseName = displayName.substringBeforeLast('.', displayName)
        val managedNames = managedCoverSidecarNames(
            baseName = audioBaseName,
            extensions = imageExtensions,
            stableIdentityKey = stableIdentityKey
        )
        if (mutation == EditableCoverMutation.CLEAR) {
            return withDocumentMutationLock(baseUri, parentId) {
                val parentChildren = queryDocumentChildrenForMutation(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = parentId
                ) ?: return@withDocumentMutationLock false
                if (!documentChildrenContainSource(
                        parentChildren = parentChildren,
                        sourceUri = sourceUri,
                        displayName = displayName,
                        parentDocumentId = parentId
                    )
                ) {
                    return@withDocumentMutationLock false
                }
                val coversDirectory = findManagedSidecarDirectory(parentChildren, "Covers")
                if (managedNames.isEmpty()) return@withDocumentMutationLock true
                val parentSpecific = parentChildren.filter { child ->
                    !child.isDirectory && child.displayName in managedNames
                }
                val coversChildren = coversDirectory?.let { directory ->
                    queryDocumentChildrenForMutation(
                        context = context,
                        baseUri = baseUri,
                        parentDocumentId = directory.documentId
                    ) ?: return@withDocumentMutationLock false
                }.orEmpty()
                val specific = coversChildren.filter { child ->
                    !child.isDirectory && child.displayName in managedNames
                }
                val deleted = (specific + parentSpecific).distinctBy(DocumentChild::uri).all { child ->
                    deleteDocumentReference(context, child)
                }
                if (deleted) {
                    invalidateDocumentChildrenCache(baseUri, parentId)
                    coversDirectory?.let { directory ->
                        invalidateDocumentChildrenCache(baseUri, directory.documentId)
                    }
                    clearCoverLookupCache()
                }
                deleted
            }
        }
        val reference = coverReference?.trim()?.takeIf(String::isNotBlank) ?: return false
        val bytes = readEditableCoverBytes(context, reference) ?: return false
        val mimeType = resolveEditableCoverMimeType(context, reference, bytes)
        val extension = coverExtensionForMimeType(mimeType)
        return withDocumentMutationLock(baseUri, parentId) {
            val parentChildren = queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentId
            ) ?: return@withDocumentMutationLock false
            if (!documentChildrenContainSource(
                    parentChildren = parentChildren,
                    sourceUri = sourceUri,
                    displayName = displayName,
                    parentDocumentId = parentId
                )
            ) {
                return@withDocumentMutationLock false
            }
            val coversDirectory = findManagedSidecarDirectory(parentChildren, "Covers")
            val parentSpecific = parentChildren.filter { child ->
                !child.isDirectory && child.displayName in managedNames
            }
            val resolvedCoversDirectory = coversDirectory ?: ensureDocumentSidecarDirectoryForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentId,
                directoryName = "Covers",
                parentChildren
            ) ?: return@withDocumentMutationLock false
            val coversChildren = queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = resolvedCoversDirectory.documentId
            ) ?: return@withDocumentMutationLock false
            val targetName = localCoverSidecarName(audioBaseName, extension, stableIdentityKey)
            val specific = coversChildren.filter { child ->
                !child.isDirectory && child.displayName in managedNames
            }
            val target = findExactDocumentSidecarChild(coversChildren, targetName)?.uri
                ?: createDocumentSidecarForMutation(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = resolvedCoversDirectory.documentId,
                    mimeType = mimeType,
                    displayName = targetName,
                    coversChildren
                )?.uri
                ?: return@withDocumentMutationLock false
            if (!writeBytesContent(context, target, bytes)) return@withDocumentMutationLock false
            (specific + parentSpecific).distinctBy(DocumentChild::uri)
                .filter { it.uri != target }
                .forEach { old ->
                    if (!deleteDocumentReference(context, old)) {
                        return@withDocumentMutationLock false
                    }
                }
            invalidateDocumentChildrenCache(baseUri, parentId)
            invalidateDocumentChildrenCache(baseUri, resolvedCoversDirectory.documentId)
            clearCoverLookupCache()
            readBytesContentMatchesWithRetry(context, target, bytes)
        }
    }

    private fun writeLocalLyricsSidecars(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String,
        song: SongItem,
        knownReferences: NearbyLyricReferences? = null
    ): Boolean {
        val contents = listOf(
            LyricKind.ORIGINAL to (song.matchedLyric ?: song.originalLyric),
            LyricKind.TRANSLATED to
                (song.matchedTranslatedLyric ?: song.originalTranslatedLyric),
            LyricKind.ROMANIZED to
                (song.matchedRomanizedLyric ?: song.originalRomanizedLyric)
        )
        val localFile = file.takeUnless {
            shouldUseDocumentSidecarMutation(sourceUri)
        }
        if (localFile != null) {
            val nearby = knownReferences?.let { references ->
                NearbyLyricFiles(
                    original = references.original?.let(::File),
                    translated = references.translated?.let(::File),
                    romanized = references.romanized?.let(::File)
                )
            } ?: findNearbyLyricFiles(localFile)
            val legacyRoot = File(LEGACY_DOWNLOAD_ROOT)
            val isLegacyDownload = runCatching {
                isFileInsideDirectory(localFile, legacyRoot)
            }.getOrDefault(false)
            val targetDirectory = resolveLocalLyricsTargetDirectory(
                file = localFile,
                nearby = nearby,
                legacyRoot = legacyRoot,
                isLegacyDownload = isLegacyDownload
            )
            val needsDirectory = contents.any { (_, content) -> content != null }
            if (needsDirectory && !targetDirectory.exists() && !targetDirectory.mkdirs()) {
                return false
            }
            fun isInTargetDirectory(candidate: File): Boolean {
                return runCatching {
                    candidate.canonicalFile.parentFile == targetDirectory.canonicalFile
                }.getOrDefault(false)
            }
            val plans = contents.mapNotNull { (kind, content) ->
                val existing = when (kind) {
                    LyricKind.ORIGINAL -> nearby.original
                    LyricKind.TRANSLATED -> nearby.translated
                    LyricKind.ROMANIZED -> nearby.romanized
                }?.takeIf(::isInTargetDirectory)
                if (content == null) {
                    // null means the caller did not change this lyric field
                    return@mapNotNull null
                }
                val contentValue = content
                val target = existing ?: File(
                    targetDirectory,
                    lyricSidecarNames(
                        baseName = localFile.nameWithoutExtension,
                        kind = kind,
                        extensions = listOf("lrc")
                    ).first()
                )
                val existed = target.isFile
                val previous = if (existed) readTextFile(target) else null
                if (existed && previous == null) return false
                Triple(target, contentValue, previous)
            }
            val written = plans.all { (target, content, _) ->
                writeTextFileAtomically(target, content) && readTextFile(target) == content
            }
            if (!written) {
                plans.asReversed().forEach { (target, _, previous) ->
                    if (previous == null) {
                        if (target.exists() && !target.delete()) {
                            NPLogger.w(TAG, "rollback lyric sidecar delete failed: ${target.absolutePath}")
                        }
                    } else if (!writeTextFileAtomically(target, previous)) {
                        NPLogger.w(TAG, "rollback lyric sidecar restore failed: ${target.absolutePath}")
                    }
                }
            }
            clearLyricsLookupCache()
            return written
        }

        val navigation = resolveLocalDocumentNavigation(context, sourceUri)
        val parentId = navigation?.parentDocumentId
        val writeDocumentSidecars = writeDocumentSidecars@{
            val rawReferences = knownReferences ?: findNearbyLyricReferences(
                context = context,
                uri = sourceUri,
                file = null,
                displayName = displayName
            )
            val existingReferences = rawReferences.copy(
                original = rawReferences.original?.takeUnless { reference ->
                    shouldUseDocumentSidecarMutation(sourceUri) && reference.startsWith("/")
                },
                translated = rawReferences.translated?.takeUnless { reference ->
                    shouldUseDocumentSidecarMutation(sourceUri) && reference.startsWith("/")
                },
                romanized = rawReferences.romanized?.takeUnless { reference ->
                    shouldUseDocumentSidecarMutation(sourceUri) && reference.startsWith("/")
                }
            )
            val lyricResolution = ensureDocumentLyricReferences(
                context = context,
                uri = sourceUri,
                displayName = displayName,
                requiredKinds = contents.mapNotNullTo(mutableSetOf()) { (kind, content) ->
                    val existing = when (kind) {
                        LyricKind.ORIGINAL -> existingReferences.original
                        LyricKind.TRANSLATED -> existingReferences.translated
                        LyricKind.ROMANIZED -> existingReferences.romanized
                    }
                    kind.takeIf { content != null || existing != null }
                },
                existing = existingReferences
            )
            val references = lyricResolution.references
            var invalidPlan = false
            val plans = contents.mapNotNull { (kind, content) ->
                if (content == null) {
                    // null means the caller did not change this lyric field
                    return@mapNotNull null
                }
                val contentValue = content
                val reference = when (kind) {
                    LyricKind.ORIGINAL -> references.original
                    LyricKind.TRANSLATED -> references.translated
                    LyricKind.ROMANIZED -> references.romanized
                }
                if (reference == null) {
                    invalidPlan = true
                    return@mapNotNull null
                }
                val previous = readTextContent(context, reference)
                val existedBefore = reference !in lyricResolution.createdReferences
                if (existedBefore && previous == null) {
                    invalidPlan = true
                    return@mapNotNull null
                }
                Triple(reference, contentValue, previous to existedBefore)
            }
            if (invalidPlan) return@writeDocumentSidecars false
            var written = true
            plans.forEach { (reference, content, _) ->
                if (!writeTextContent(context, reference, content)) {
                    written = false
                    NPLogger.w(TAG, "SAF lyric variant write failed: reference=$reference")
                }
            }
            if (!written) {
                plans.asReversed().forEach { (reference, _, previousState) ->
                    val (previous, existedBefore) = previousState
                    if (existedBefore) {
                        if (previous != null && !writeTextContent(context, reference, previous)) {
                            NPLogger.w(TAG, "rollback SAF lyric sidecar restore failed: $reference")
                        }
                    } else if (
                        lyricResolution.createdReferences[reference]
                            ?.let { created -> !deleteDocumentReference(context, created) }
                            == true
                    ) {
                        NPLogger.w(TAG, "rollback SAF lyric sidecar delete failed: $reference")
                    }
                }
            }
            written
        }
        val written = if (navigation != null && parentId != null) {
            withDocumentMutationLock(navigation.treeUri ?: navigation.baseUri, parentId) {
                writeDocumentSidecars()
            }
        } else {
            writeDocumentSidecars()
        }
        clearLyricsLookupCache()
        return written
    }

    internal fun resolveLocalLyricsTargetDirectory(
        file: File,
        nearby: NearbyLyricFiles,
        legacyRoot: File = File(LEGACY_DOWNLOAD_ROOT),
        isLegacyDownload: Boolean = runCatching {
            isFileInsideDirectory(file, legacyRoot)
        }.getOrDefault(false)
    ): File {
        val parent = file.parentFile ?: return file
        if (isLegacyDownload) {
            return File(legacyRoot, "Lyrics")
        }
        val lyricsDirectory = File(parent, "Lyrics")
        if (lyricsDirectory.isDirectory) {
            return lyricsDirectory
        }
        return nearby.original?.parentFile
            ?: nearby.translated?.parentFile
            ?: nearby.romanized?.parentFile
            ?: parent
    }

    private data class DocumentLyricReferenceResolution(
        val references: NearbyLyricReferences,
        val createdReferences: Map<String, DocumentChild>
    )

    private fun ensureDocumentLyricReferences(
        context: Context,
        uri: Uri,
        displayName: String,
        requiredKinds: Set<LyricKind>,
        existing: NearbyLyricReferences
    ): DocumentLyricReferenceResolution {
        if (!uri.scheme.equals("content", ignoreCase = true) || requiredKinds.isEmpty()) {
            return DocumentLyricReferenceResolution(existing, emptyMap())
        }
        val navigation = resolveLocalDocumentNavigation(context, uri)
            ?: return DocumentLyricReferenceResolution(existing, emptyMap())
        val parentId = navigation.parentDocumentId
            ?: return DocumentLyricReferenceResolution(existing, emptyMap())
        val baseUri = navigation.treeUri ?: navigation.baseUri
        val audioBaseName = displayName.substringBeforeLast('.', displayName)
        return withDocumentMutationLock(baseUri, parentId) {
            val parentChildren = queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentId
            ) ?: return@withDocumentMutationLock DocumentLyricReferenceResolution(existing, emptyMap())
            if (!documentChildrenContainSource(
                    parentChildren = parentChildren,
                    sourceUri = uri,
                    displayName = displayName,
                    parentDocumentId = parentId
                )
            ) {
                return@withDocumentMutationLock DocumentLyricReferenceResolution(existing, emptyMap())
            }
            val lyricsDirectory = findManagedSidecarDirectory(parentChildren, "Lyrics")
                ?: ensureDocumentSidecarDirectoryForMutation(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = parentId,
                    directoryName = "Lyrics",
                    parentChildren
                )
                ?: return@withDocumentMutationLock DocumentLyricReferenceResolution(existing, emptyMap())
            val targetChildren = queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = lyricsDirectory.documentId
            ) ?: return@withDocumentMutationLock DocumentLyricReferenceResolution(existing, emptyMap())
            val createdReferences = linkedMapOf<String, DocumentChild>()
            fun ensure(kind: LyricKind, existingReference: String?): String? {
                val name = lyricSidecarNames(
                    baseName = audioBaseName,
                    kind = kind,
                    extensions = listOf("lrc")
                ).first()
                val exactExisting = targetChildren.firstOrNull { child ->
                    child.uri == existingReference &&
                        !child.isDirectory &&
                        canonicalSafName(child.displayName) == canonicalSafName(name)
                }?.uri
                if (exactExisting != null || kind !in requiredKinds) {
                    return exactExisting
                }
                findExactDocumentSidecarChild(targetChildren, name)?.uri?.let { return it }
                return createDocumentSidecarForMutation(
                        context = context,
                        baseUri = baseUri,
                        parentDocumentId = lyricsDirectory.documentId,
                        mimeType = "text/plain",
                        displayName = name,
                        targetChildren
                    )?.takeIf(DocumentChild::createdByCurrentMutation)
                    ?.also { createdReferences[it.uri] = it }
                    ?.uri
            }
            DocumentLyricReferenceResolution(
                references = NearbyLyricReferences(
                    original = ensure(LyricKind.ORIGINAL, existing.original),
                    translated = ensure(LyricKind.TRANSLATED, existing.translated),
                    romanized = ensure(LyricKind.ROMANIZED, existing.romanized)
                ),
                createdReferences = createdReferences
            )
        }
    }

    private fun writeTextFileAtomically(target: File, content: String): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temporary = runCatching {
            File.createTempFile(".${target.name}.", ".tmp", parent)
        }.getOrNull() ?: return false
        return runCatching {
            temporary.writeText(content, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.isFile && readTextFile(target) == content
        }.onFailure {
            temporary.delete()
            NPLogger.w(TAG, "write lyric sidecar failed for ${target.absolutePath}: ${it.message}")
        }.getOrDefault(false)
    }

    private fun writeBytesFileAtomically(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temporary = runCatching {
            File.createTempFile(".${target.name}.", ".tmp", parent)
        }.getOrNull() ?: return false
        return runCatching {
            temporary.outputStream().use { output -> output.write(bytes) }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.isFile && target.inputStream().use { input ->
                input.readBytesLimited(MAX_EDITABLE_COVER_BYTES).contentEquals(bytes)
            }
        }.onFailure {
            temporary.delete()
            NPLogger.w(TAG, "write cover sidecar failed for ${target.absolutePath}: ${it.message}")
        }.getOrDefault(false)
    }

    private fun writeTextContent(context: Context, reference: String, content: String): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        var lastError: Throwable? = null
        for ((modeIndex, mode) in listOf("wt", "w").withIndex()) {
            val written = try {
                val output = context.contentResolver.openOutputStream(reference.toUri(), mode)
                    ?: run {
                        lastError = IllegalStateException("provider returned no output stream")
                        null
                    }
                if (output == null) {
                    false
                } else {
                    output.use {
                        it.write(bytes)
                        it.flush()
                    }
                    readTextContentMatchesWithRetry(context, reference, content)
                }
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                false
            }
            if (written) {
                return true
            }
            if (lastError == null || lastError is IllegalStateException) {
                lastError = IOException("SAF lyric sidecar readback mismatch")
            }
            if (modeIndex + 1 < 2 && SAF_WRITE_READBACK_DELAYS_MS[modeIndex + 1] > 0L) {
                SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[modeIndex + 1])
            }
        }
        if (lastError == null) return true
        NPLogger.w(TAG, "write lyric sidecar failed for $reference: ${lastError.message}")
        return false
    }

    private fun writeBytesContent(context: Context, reference: String, bytes: ByteArray): Boolean {
        var lastError: Throwable? = null
        for ((modeIndex, mode) in listOf("rwt", "wt", "w").withIndex()) {
            val written = try {
                val output = context.contentResolver.openOutputStream(reference.toUri(), mode)
                    ?: run {
                        lastError = IllegalStateException("provider returned no output stream")
                        null
                    }
                if (output == null) {
                    false
                } else {
                    output.use {
                        it.write(bytes)
                        it.flush()
                    }
                    readBytesContentMatchesWithRetry(context, reference, bytes)
                }
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                false
            }
            if (written) return true
            if (lastError == null || lastError is IllegalStateException) {
                lastError = IOException("SAF cover sidecar readback mismatch")
            }
            if (modeIndex + 1 < 3 && SAF_WRITE_READBACK_DELAYS_MS[modeIndex] > 0L) {
                SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[modeIndex])
            }
        }
        NPLogger.w(TAG, "write cover sidecar failed for $reference: ${lastError?.message}")
        return false
    }

    private fun readTextContentMatchesWithRetry(
        context: Context,
        reference: String,
        expected: String
    ): Boolean {
        repeat(SAF_WRITE_READBACK_RETRY_COUNT) { attempt ->
            if (readTextContent(context, reference) == expected) return true
            if (attempt + 1 < SAF_WRITE_READBACK_RETRY_COUNT) {
                SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[attempt + 1])
            }
        }
        return false
    }

    private fun readBytesContentMatchesWithRetry(
        context: Context,
        reference: String,
        expected: ByteArray
    ): Boolean {
        repeat(SAF_WRITE_READBACK_RETRY_COUNT) { attempt ->
            if (readBytesContent(context, reference)?.contentEquals(expected) == true) {
                return true
            }
            if (attempt + 1 < SAF_WRITE_READBACK_RETRY_COUNT) {
                SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[attempt + 1])
            }
        }
        return false
    }

    private fun readBytesContent(context: Context, reference: String): ByteArray? {
        return try {
            if (reference.startsWith("/")) {
                File(reference).inputStream().use { input ->
                    input.readBytesLimited(MAX_EDITABLE_COVER_BYTES)
                }
            } else {
                context.contentResolver.openInputStream(reference.toUri())?.use { input ->
                    input.readBytesLimited(MAX_EDITABLE_COVER_BYTES)
                }
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "read cover sidecar failed for $reference: ${error.message}")
            null
        }
    }

    private fun deleteDocumentReference(context: Context, child: DocumentChild): Boolean {
        val uri = runCatching { child.uri.toUri() }.getOrNull() ?: return false
        val actualDocumentId = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return false
        if (child.documentId.isBlank() || actualDocumentId != child.documentId) {
            NPLogger.w(TAG, "拒绝删除来源不明的 SAF sidecar: ${child.uri}")
            return false
        }
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "delete sidecar failed for ${child.uri}: ${error.message}")
            false
        }
    }

    /**
     * 只读取歌词相关字段，避免歌词首屏触发 TagLib、封面和音频轨道解析
     */
    internal fun inspectLyricsFast(
        song: SongItem,
        includeStoredFallback: Boolean = true
    ): LocalLyricsScanMetadata {
        return inspectLyricsFast(
            context = null,
            song = song,
            includeStoredFallback = includeStoredFallback,
            includeEmbeddedFallback = false,
            knownSidecarReferences = null,
            forceRefresh = false
        )
    }

    internal fun inspectLyricsFast(
        context: Context?,
        song: SongItem,
        includeStoredFallback: Boolean = true,
        includeEmbeddedFallback: Boolean = context != null,
        knownSidecarReferences: LocalKnownSidecarReferences? = null,
        forceRefresh: Boolean = false
    ): LocalLyricsScanMetadata {
        val startedAt = SystemClock.elapsedRealtime()
        if (forceRefresh) {
            clearLyricsLookupCache()
        }
        val stored = if (includeStoredFallback) {
            LocalLyricsScanMetadata(
                lyric = song.matchedLyric ?: song.originalLyric,
                translatedLyric = song.matchedTranslatedLyric
                    ?: song.originalTranslatedLyric,
                romanizedLyric = song.matchedRomanizedLyric
                    ?: song.originalRomanizedLyric
            )
        } else {
            LocalLyricsScanMetadata(null, null, null)
        }

        val source = song.localMediaUri()
        val rawContentSource = song.mediaUri?.startsWith("content://", ignoreCase = true) == true ||
            song.localFilePath?.startsWith("content://", ignoreCase = true) == true
        val contentSource = source?.scheme.equals("content", ignoreCase = true) || rawContentSource
        val directFile = song.localFilePath
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it.startsWith("content://", ignoreCase = true) }
            ?.let(::File)
            ?.takeIf(::isReadableLocalFile)
            ?: source
                ?.takeIf { !it.scheme.equals("content", ignoreCase = true) }
                ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
                ?.path
                ?.let(::File)
                ?.takeIf(::isReadableLocalFile)
        // 当媒体同时有可读绝对路径和 MediaStore content URI 时，绝对路径才是
        // Lyrics/Covers 的权威来源。跳过一次昂贵的 provider 查询，也避免权限
        // 变化让编辑入口错误地读到旧的媒体索引
        // content URIs cannot expose a reliable filesystem timestamp. Keep a
        // short cache instead of rescanning the provider twice per frame; the
        // TTL is deliberately small so external edits/deletes become visible
        // on the next playback/editor interaction
        val cacheable = knownSidecarReferences == null
        val cacheKey = buildLocalLyricsCacheKey(
            song = song,
            source = source,
            includeEmbeddedFallback = includeEmbeddedFallback,
            includeStoredFallback = includeStoredFallback
        )
        if (cacheable && !forceRefresh) {
                synchronized(localLyricsLookupCache) {
                localLyricsLookupCache[cacheKey]?.let { cached ->
                    if (System.currentTimeMillis() - cached.cachedAtMs <= LOCAL_LYRICS_CACHE_TTL_MS) {
                        logLyricsInspection(
                            song = song,
                            source = source,
                            stage = "cache",
                            startedAt = startedAt,
                            result = cached.value
                        )
                        return cached.value
                    }
                    localLyricsLookupCache.remove(cacheKey)
                }
            }
        }

        val directScanned = if (knownSidecarReferences != null && context != null) {
            try {
                inspectLyricsFromKnownReferences(
                    context = context,
                    references = knownSidecarReferences
                )
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "known local lyrics inspection failed for $source: ${error.message}")
                null
            }
        } else if (directFile != null) {
            try {
                inspectLyricsFromDirectFile(
                    file = directFile
                )
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "fast local lyrics inspection failed for $source: ${error.message}")
                null
            }
        } else {
            null
        }

        val shouldProbeContentSource = directFile == null && knownSidecarReferences == null
        val contentScanned = if (
            context != null &&
            contentSource &&
            source != null &&
            shouldProbeContentSource
        ) {
            try {
                inspectLyricsFromContentUri(
                    context = context,
                    sourceUri = source,
                    displayName = ManagedDownloadStorage.resolveManagedAudioDisplayName(
                        context = context,
                        song = song
                    ) ?: source.lastPathSegment.orEmpty()
                )
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "fast SAF lyrics inspection failed for $source: ${error.message}")
                null
            }
        } else {
            null
        }
        val resolvedScan = mergeLyricsInspections(
            primary = directScanned,
            fallback = contentScanned
        )
        val needsEmbeddedFallback = context != null && includeEmbeddedFallback && (
            resolvedScan == null ||
                !resolvedScan.hasOriginalSidecar ||
                !resolvedScan.hasTranslatedSidecar ||
                !resolvedScan.hasRomanizedSidecar
            )
        val embedded = context?.takeIf { needsEmbeddedFallback }?.let { embeddedContext ->
            try {
                inspectEmbeddedLyrics(embeddedContext, song)
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "fast embedded lyrics inspection failed for $source: ${error.message}")
                null
            }
        }
        val result = LocalLyricsScanMetadata(
            lyric = resolvedScan?.original
                ?: embedded?.lyric
                ?: resolvedScan?.metadataOriginal
                ?: stored.lyric,
            translatedLyric = resolvedScan?.translated
                ?: embedded?.translatedLyric
                ?: resolvedScan?.metadataTranslated
                ?: stored.translatedLyric,
            romanizedLyric = resolvedScan?.romanized
                ?: embedded?.romanizedLyric
                ?: resolvedScan?.metadataRomanized
                ?: stored.romanizedLyric,
            hasOriginalSidecar = resolvedScan?.hasOriginalSidecar == true,
            hasTranslatedSidecar = resolvedScan?.hasTranslatedSidecar == true,
            hasRomanizedSidecar = resolvedScan?.hasRomanizedSidecar == true,
            embeddedLyric = embedded?.lyric,
            embeddedTranslatedLyric = embedded?.translatedLyric,
            embeddedRomanizedLyric = embedded?.romanizedLyric,
            sourceResolved = isLocalLyricsSourceResolved(
                scannedSource = resolvedScan != null,
                embeddedSource = embedded != null
            )
        )
        if (cacheable && !forceRefresh) {
            synchronized(localLyricsLookupCache) {
                localLyricsLookupCache[cacheKey] = LocalLyricsCacheEntry(
                    value = result,
                    cachedAtMs = System.currentTimeMillis()
                )
            }
        }
        logLyricsInspection(
            song = song,
            source = source,
            stage = when {
                directFile != null -> "file"
                contentScanned != null -> "saf"
                embedded != null -> "embedded"
                else -> "stored"
            },
            startedAt = startedAt,
            result = result
        )
        return result
    }

    private fun inspectLyricsFromKnownReferences(
        context: Context,
        references: LocalKnownSidecarReferences
    ): DirectLocalLyricsInspection {
        val metadata = references.metadata
            ?.takeUnless(::isMediaStoreSidecarReference)
            ?.let { reference ->
                readTextContent(context, reference)?.let { raw ->
                    parseLocalMetadataSidecar(reference, raw)
                }
            }

        fun read(reference: String?): String? {
            return reference
                ?.takeUnless(::isMediaStoreSidecarReference)
                ?.let { readTextContent(context, it) }
        }

        val original = read(references.lyrics.original)
        val translated = read(references.lyrics.translated)
        val romanized = read(references.lyrics.romanized)
        return DirectLocalLyricsInspection(
            original = original,
            translated = translated,
            romanized = romanized,
            metadataOriginal = metadata?.takeIf { it.hasLyricOverride }?.lyric,
            metadataTranslated = metadata
                ?.takeIf { it.hasTranslatedLyricOverride }
                ?.translatedLyric,
            metadataRomanized = metadata
                ?.takeIf { it.hasRomanizedLyricOverride }
                ?.romanizedLyric,
            hasOriginalSidecar = references.lyrics.original != null && original != null,
            hasTranslatedSidecar = references.lyrics.translated != null && translated != null,
            hasRomanizedSidecar = references.lyrics.romanized != null && romanized != null
        )
    }

    private fun logLyricsInspection(
        song: SongItem,
        source: Uri?,
        stage: String,
        startedAt: Long,
        result: LocalLyricsScanMetadata
    ) {
        if (localLyricsPerfLogCount.getAndIncrement() >= LOCAL_LYRICS_PERF_LOG_LIMIT) {
            return
        }
        NPLogger.d(
            "LocalLyricsPerf",
            "song=${song.name}, stage=$stage, elapsed=" +
                "${SystemClock.elapsedRealtime() - startedAt}ms, " +
                "originalSidecar=${result.hasOriginalSidecar}, " +
                "translatedSidecar=${result.hasTranslatedSidecar}, " +
                "romanizedSidecar=${result.hasRomanizedSidecar}, " +
                "source=${source ?: song.localFilePath ?: song.mediaUri}"
        )
    }

    internal fun inspectEmbeddedLyrics(
        context: Context,
        song: SongItem
    ): LocalLyricsScanMetadata? {
        val options = embeddedLyricsReadOptions
        song.localMediaUriCandidates().forEach { sourceUri ->
            val resolved = try {
                resolveInspectableLocalMedia(
                    context = context,
                    uri = sourceUri,
                    allowDescriptorFallback = true
                )
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@forEach
            val metadata = try {
                inspectTagLibMetadata(
                    context = context,
                    uri = resolved.playableUri,
                    file = resolved.file,
                    includeEmbeddedAssets = options.includeEmbeddedAssets,
                    includeEmbeddedLyrics = options.includeEmbeddedLyrics,
                    includeAudioProperties = options.includeAudioProperties
                )
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@forEach
            return LocalLyricsScanMetadata(
                lyric = metadata.lyrics,
                translatedLyric = metadata.translatedLyrics,
                romanizedLyric = metadata.romanizedLyrics,
                embeddedLyric = metadata.lyrics,
                embeddedTranslatedLyric = metadata.translatedLyrics,
                embeddedRomanizedLyric = metadata.romanizedLyrics
            )
        }
        return null
    }

    private fun inspectLyricsFromDirectFile(
        file: File
    ): DirectLocalLyricsInspection {
        val metadataFile = File(
            file.parentFile ?: return DirectLocalLyricsInspection(
                original = null,
                translated = null,
                romanized = null,
                metadataOriginal = null,
                metadataTranslated = null,
                metadataRomanized = null,
                hasOriginalSidecar = false,
                hasTranslatedSidecar = false,
                hasRomanizedSidecar = false
            ),
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
        return DirectLocalLyricsInspection(
            original = nearbyLyric,
            translated = nearbyTranslatedLyric,
            romanized = nearbyRomanizedLyric,
            metadataOriginal = localMetadata?.takeIf { it.hasLyricOverride }?.lyric,
            metadataTranslated = localMetadata
                ?.takeIf { it.hasTranslatedLyricOverride }
                ?.translatedLyric,
            metadataRomanized = localMetadata
                ?.takeIf { it.hasRomanizedLyricOverride }
                ?.romanizedLyric,
            hasOriginalSidecar = nearbyFiles.original != null && nearbyLyric != null,
            hasTranslatedSidecar = nearbyFiles.translated != null && nearbyTranslatedLyric != null,
            hasRomanizedSidecar = nearbyFiles.romanized != null && nearbyRomanizedLyric != null
        )
    }

    private fun mergeLyricsInspections(
        primary: DirectLocalLyricsInspection?,
        fallback: DirectLocalLyricsInspection?
    ): DirectLocalLyricsInspection? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return DirectLocalLyricsInspection(
            original = if (primary.hasOriginalSidecar) primary.original else fallback.original,
            translated = if (primary.hasTranslatedSidecar) {
                primary.translated
            } else {
                fallback.translated
            },
            romanized = if (primary.hasRomanizedSidecar) {
                primary.romanized
            } else {
                fallback.romanized
            },
            metadataOriginal = primary.metadataOriginal ?: fallback.metadataOriginal,
            metadataTranslated = primary.metadataTranslated ?: fallback.metadataTranslated,
            metadataRomanized = primary.metadataRomanized ?: fallback.metadataRomanized,
            hasOriginalSidecar = primary.hasOriginalSidecar || fallback.hasOriginalSidecar,
            hasTranslatedSidecar = primary.hasTranslatedSidecar || fallback.hasTranslatedSidecar,
            hasRomanizedSidecar = primary.hasRomanizedSidecar || fallback.hasRomanizedSidecar
        )
    }

    private fun inspectLyricsFromContentUri(
        context: Context,
        sourceUri: Uri,
        displayName: String
    ): DirectLocalLyricsInspection {
        val references = resolveContentSidecarReferences(
            context = context,
            sourceUri = sourceUri,
            displayName = displayName
        )
        val metadata = references.metadataReference
            ?.takeUnless(::isMediaStoreSidecarReference)
            ?.let { reference ->
                readTextContent(context, reference)?.let { raw ->
                    parseLocalMetadataSidecar(reference, raw)
                }
            }
        fun read(reference: String?): String? {
            return reference
                ?.takeUnless(::isMediaStoreSidecarReference)
                ?.let { readTextContent(context, it) }
        }
        val original = read(references.lyricReferences.original)
        val translated = read(references.lyricReferences.translated)
        val romanized = read(references.lyricReferences.romanized)
        return DirectLocalLyricsInspection(
            original = original,
            translated = translated,
            romanized = romanized,
            metadataOriginal = metadata?.takeIf { it.hasLyricOverride }?.lyric,
            metadataTranslated = metadata
                ?.takeIf { it.hasTranslatedLyricOverride }
                ?.translatedLyric,
            metadataRomanized = metadata
                ?.takeIf { it.hasRomanizedLyricOverride }
                ?.romanizedLyric,
            hasOriginalSidecar = references.lyricReferences.original != null && original != null,
            hasTranslatedSidecar = references.lyricReferences.translated != null &&
                translated != null,
            hasRomanizedSidecar = references.lyricReferences.romanized != null &&
                romanized != null
        )
    }

    internal fun clearLyricsLookupCache() {
        synchronized(localLyricsLookupCache) {
            localLyricsLookupCache.clear()
        }
        invalidateSafReadCaches()
    }

    internal fun invalidateSongAssetCaches(song: SongItem) {
        val keyParts = setOf(
            song.songStableKey(),
            song.mediaUri.orEmpty(),
            song.localFilePath.orEmpty()
        ).filter(String::isNotBlank)
        if (keyParts.isEmpty()) return
        synchronized(localLyricsLookupCache) {
            localLyricsLookupCache.keys.removeAll { key -> keyParts.any(key::contains) }
        }
        synchronized(localCoverLookupCache) {
            localCoverLookupCache.keys.removeAll { key -> keyParts.any(key::contains) }
        }
        synchronized(nearbyCoverLookupCache) {
            nearbyCoverLookupCache.keys.removeAll { key -> keyParts.any(key::contains) }
        }
        synchronized(directoryCoverLookupCache) {
            directoryCoverLookupCache.keys.removeAll { key -> keyParts.any(key::contains) }
        }
    }

    internal fun clearCoverLookupCache() {
        synchronized(localCoverLookupCache) {
            localCoverLookupCache.clear()
        }
        synchronized(nearbyCoverLookupCache) {
            nearbyCoverLookupCache.clear()
        }
        synchronized(directoryCoverLookupCache) {
            directoryCoverLookupCache.clear()
        }
        synchronized(mediaStoreAlbumArtCache) {
            mediaStoreAlbumArtCache.clear()
        }
        invalidateSafReadCaches()
    }

    internal fun invalidateSafReadCaches() {
        synchronized(documentChildrenCache) {
            documentChildrenCache.clear()
        }
        consecutiveEmptyDocumentRefreshes.clear()
        synchronized(documentNavigationCache) {
            documentNavigationCache.clear()
        }
    }

    private fun buildLocalLyricsCacheKey(
        song: SongItem,
        source: Uri?,
        includeEmbeddedFallback: Boolean,
        includeStoredFallback: Boolean
    ): String {
        val localFile = song.localFilePath?.let(::File)
        val localFileState = localFile?.let {
            "${it.length()}:${it.lastModified()}:${it.parentFile?.lastModified()}"
        }.orEmpty()
        return listOf(
            song.sourceStableKey,
            song.localFilePath,
            song.localFileName,
            source?.toString(),
            includeEmbeddedFallback,
            includeStoredFallback,
            localLyricsModelState(song),
            localFileState,
            localLyricsCacheState(localFile)
        ).joinToString("|")
    }

    private fun localLyricsModelState(song: SongItem): String {
        return listOf(
            song.matchedLyric,
            song.matchedTranslatedLyric,
            song.matchedRomanizedLyric,
            song.originalLyric,
            song.originalTranslatedLyric,
            song.originalRomanizedLyric
        ).joinToString("|") { value ->
            value?.let { "${it.length}:${it.hashCode()}" }.orEmpty()
        }
    }

    private fun localLyricsCacheState(localFile: File?): String {
        val actualFile = localFile ?: return ""
        val parent = actualFile.parentFile ?: return ""
        val legacyRoot = File(LEGACY_DOWNLOAD_ROOT)
        val isLegacyDownload = runCatching {
            isFileInsideDirectory(actualFile, legacyRoot)
        }.getOrDefault(false)
        val searchDirectories = buildList {
            if (isLegacyDownload) add(File(legacyRoot, "Lyrics"))
            add(File(parent, "Lyrics"))
            add(parent)
        }.distinctBy(File::getAbsolutePath)
        val files = buildList {
            add(File(parent, actualFile.name + LOCAL_METADATA_SUFFIX))
            LyricKind.entries.forEach { kind ->
                searchDirectories.forEach { directory ->
                    addAll(
                        lyricSidecarNames(
                            baseName = actualFile.nameWithoutExtension,
                            kind = kind,
                            extensions = lyricExtensions
                        ).map { name -> File(directory, name) }
                    )
                }
            }
        }
        return files.joinToString(",") { file ->
            "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        }
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

    private data class EditableMetadataWriteTransaction(
        val outcome: LocalMediaMetadataWriteOutcome,
        val rollback: (() -> Unit)? = null
    )

    private fun writeEditableMetadataDirect(
        context: Context,
        song: SongItem,
        sourceUri: Uri,
        coverReference: String?,
        writeCover: Boolean,
        writeLyrics: Boolean
    ): LocalMediaMetadataWriteOutcome = writeEditableMetadataDirectTransaction(
        context = context,
        song = song,
        sourceUri = sourceUri,
        coverReference = coverReference,
        writeCover = writeCover,
        writeLyrics = writeLyrics
    ).outcome

    private fun writeEditableMetadataDirectTransaction(
        context: Context,
        song: SongItem,
        sourceUri: Uri,
        coverReference: String?,
        writeCover: Boolean,
        writeLyrics: Boolean
    ): EditableMetadataWriteTransaction {
        val resolved = runCatching {
            resolveInspectableLocalMedia(
                context = context,
                uri = sourceUri,
                allowDescriptorFallback = true
            )
        }.getOrElse { error ->
            NPLogger.w(TAG, "resolve writable local metadata failed for $sourceUri: ${error.message}")
            return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
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
            val romanizedLyrics = if (writeLyrics) {
                song.matchedRomanizedLyric ?: song.originalRomanizedLyric
            } else {
                null
            }
            val updated = applyEditableMetadata(
                propertyMap = existing,
                title = song.displayName(),
                artist = song.displayArtist(),
                lyrics = lyrics,
                translatedLyrics = translatedLyrics,
                romanizedLyrics = romanizedLyrics,
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
        } ?: return EditableMetadataWriteTransaction(
            LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE
        )

        if (metadataSnapshot.picturePlan == EditableCoverWritePlan.Unreadable) {
            return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
        }

        val rollbackUsed = AtomicBoolean(false)
        fun rollbackEmbeddedMetadata() {
            if (!rollbackUsed.compareAndSet(false, true)) return
            val restored = openWritableTagLibDescriptor(
                context = context,
                uri = sourceUri,
                file = resolved.file
            )?.use { target ->
                val propertiesRestored = runCatching {
                    TagLib.savePropertyMap(
                        target.dup().detachFd(),
                        metadataSnapshot.existingProperties
                    )
                    true
                }.getOrElse { error ->
                    NPLogger.w(TAG, "rollback local metadata properties failed: ${error.message}")
                    false
                }
                val picturesRestored = when (val picturePlan = metadataSnapshot.picturePlan) {
                    is EditableCoverWritePlan.Update -> runCatching {
                        TagLib.savePictures(
                            target.dup().detachFd(),
                            picturePlan.originalPictures
                        )
                        true
                    }.getOrElse { error ->
                        NPLogger.w(TAG, "rollback local cover failed: ${error.message}")
                        false
                    }
                    else -> true
                }
                propertiesRestored && picturesRestored
            } == true
            if (!restored) {
                NPLogger.e(TAG, "rollback local embedded metadata was not confirmed: $sourceUri")
            }
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
            rollbackEmbeddedMetadata()
            return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
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
            rollbackEmbeddedMetadata()
            return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
        }
        if (restorePropertiesAfterCover && !saveProperties()) {
            rollbackEmbeddedMetadata()
            return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
        }

        val verified = verifyEditableMetadataReadback(
            context = context,
            song = song,
            sourceUri = sourceUri,
            resolved = resolved,
            metadataSnapshot = metadataSnapshot
        )
        if (!verified) {
            rollbackEmbeddedMetadata()
            return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
        }

        if (metadataSnapshot.picturePlan !is EditableCoverWritePlan.Unchanged) {
            invalidateLocalCoverLookupCache(context, sourceUri, resolved)
        }
        return EditableMetadataWriteTransaction(
            outcome = LocalMediaMetadataWriteOutcome.SUCCESS,
            rollback = ::rollbackEmbeddedMetadata
        )
    }

    private fun verifyEditableMetadataReadback(
        context: Context,
        song: SongItem,
        sourceUri: Uri,
        resolved: ResolvedInspectableLocalMedia,
        metadataSnapshot: EditableMetadataSnapshot
    ): Boolean {
        return retryEditableMetadataReadback(sourceUri.scheme) {
            openTagLibDescriptor(
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
                    romanizedLyrics = if (metadataSnapshot.writesLyrics) {
                        song.matchedRomanizedLyric ?: song.originalRomanizedLyric
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
        }
    }

    internal fun retryEditableMetadataReadback(
        sourceScheme: String?,
        readBack: () -> Boolean
    ): Boolean {
        val attemptCount = if (sourceScheme.equals("content", ignoreCase = true)) {
            SAF_WRITE_READBACK_RETRY_COUNT
        } else {
            1
        }
        repeat(attemptCount) { attempt ->
            if (readBack()) return true
            if (attempt + 1 < attemptCount) {
                SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[attempt + 1])
            }
        }
        return false
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
        val startedAtMs = SystemClock.elapsedRealtime()
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
            val replaceStartedAtMs = SystemClock.elapsedRealtime()
            if (!replaceContentUriWithFile(context, sourceUri, updated)) {
                restoreContentUriFromFile(context, sourceUri, backup)
                return directOutcome
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
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            val replaceElapsedMs = SystemClock.elapsedRealtime() - replaceStartedAtMs
            val message = "staged metadata write completed for $sourceUri: " +
                "copyAndTagLibMs=${replaceStartedAtMs - startedAtMs}, " +
                "replaceMs=$replaceElapsedMs, totalMs=$elapsedMs"
            if (elapsedMs >= EDITABLE_METADATA_WRITE_BUDGET_MS) {
                NPLogger.w(TAG, "$message, overBudget=true")
            } else {
                NPLogger.d(TAG, "$message, overBudget=false")
            }
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

    /**
     * reads only the duration exposed by the provider or audio track header
     */
    fun resolveDurationFast(context: Context, uri: Uri): Long {
        val queriedDuration = runCatching {
            queryContentInfo(context, uri).durationMs
        }.getOrNull()?.takeIf { it > 0L }
        if (queriedDuration != null) {
            return queriedDuration
        }
        return runCatching {
            inspectAudioTrackInfo(context, uri)?.durationMs ?: 0L
        }.getOrDefault(0L)
    }

    fun resolveMediaStoreDurationsFast(
        context: Context,
        sources: List<Uri>
    ): Map<String, Long> {
        val sourcesByCollection = sources.asSequence()
            .filter(::isMediaStoreUri)
            .mapNotNull { source ->
                val id = source.lastPathSegment
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: return@mapNotNull null
                val collectionUri = source.mediaStoreAudioCollectionUri() ?: return@mapNotNull null
                collectionUri to (id to source.toString())
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.toMap() }
        if (sourcesByCollection.isEmpty()) return emptyMap()

        val result = HashMap<String, Long>(sourcesByCollection.values.sumOf { it.size })
        sourcesByCollection.forEach { (audioUri, sourceById) ->
            sourceById.keys.chunked(MAX_MEDIASTORE_DURATION_QUERY_IDS).forEach { ids ->
                runCatching {
                    val placeholders = ids.joinToString(",") { "?" }
                    context.contentResolver.query(
                        audioUri,
                        arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION),
                        "${MediaStore.Audio.Media._ID} IN ($placeholders)",
                        ids.map(Long::toString).toTypedArray(),
                        null
                    )?.use { cursor ->
                        val idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                        val durationIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                        if (idIndex < 0 || durationIndex < 0) return@use
                        while (cursor.moveToNext()) {
                            val durationMs = cursor.getLong(durationIndex)
                                .takeIf { it > 0L }
                                ?: continue
                            sourceById[cursor.getLong(idIndex)]?.let { source ->
                                result[source] = durationMs
                            }
                        }
                    }
                }.onFailure { error ->
                    NPLogger.d(
                        TAG,
                        "bulk MediaStore duration query unavailable for $audioUri: " +
                            error.message
                    )
                }
            }
        }
        return result
    }

    private fun Uri.mediaStoreAudioCollectionUri(): Uri? {
        val path = path ?: return null
        val collectionPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (collectionPath.isBlank()) return null
        return buildUpon().path(collectionPath).build()
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
            includeEmbeddedLyrics = true,
            includeAudioProperties = file != null
        )
        val localMetadata = readLocalMetadataSidecar(
            context = context,
            sourceUri = uri,
            file = file,
            displayName = resolved.displayName
        )
        val title = pickReadableLocalTitle(
            sourceUri = uri,
            fallbackTitle = resolved.fallbackTitle,
            tagLibMetadata?.title,
            containerMetadata?.title,
            queried.title,
            localMetadata?.customName,
            localMetadata?.name
        ) ?: resolved.fallbackTitle
        val artist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: queried.artist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: context.getString(R.string.music_unknown_artist)
        val rawAlbum = tagLibMetadata?.album.takeMeaningfulLocalMetadata()
            ?: containerMetadata?.album.takeMeaningfulLocalMetadata()
            ?: queried.album.takeMeaningfulLocalMetadata()
            ?: localMetadata?.album.takeMeaningfulLocalMetadata()
        val usesFallbackAlbum = rawAlbum == null
        val album = normalizeLocalAlbumIdentity(rawAlbum, usesFallbackAlbum)
        val nearbyCoverReference = findNearbyCoverReference(
            context = context,
            uri = uri,
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
        val effectiveLyricContent = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyLyricContent,
            embeddedContent = tagLibMetadata?.lyrics,
            metadataFallback = localMetadata?.takeIf { it.hasLyricOverride }?.lyric
        )
        val effectiveTranslatedLyricContent = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyTranslatedLyricContent,
            embeddedContent = tagLibMetadata?.translatedLyrics,
            metadataFallback = localMetadata
                ?.takeIf { it.hasTranslatedLyricOverride }
                ?.translatedLyric
        )
        val effectiveRomanizedLyricContent = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyRomanizedLyricContent,
            embeddedContent = tagLibMetadata?.romanizedLyrics,
            metadataFallback = localMetadata
                ?.takeIf { it.hasRomanizedLyricOverride }
                ?.romanizedLyric
        )
        val lyricReference = nearbyLyricReferences.original
            ?: nearbyLyricFiles.original?.absolutePath
            ?: localMetadata?.reference?.takeIf { localMetadata.hasLyricOverride }

        return LocalMediaDetails(
            sourceUri = uri,
            displayName = resolved.displayName,
            title = title,
            artist = artist,
            album = album,
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
            coverUri = nearbyCoverReference,
            coverSource = nearbyCoverReference?.let {
                context.getString(R.string.local_song_cover_external)
            },
            lyricContent = effectiveLyricContent,
            lyricPath = resolveEffectiveLocalLyricPath(
                reference = lyricReference,
                content = effectiveLyricContent
            ),
            lyricSource = when {
                nearbyLyricContent != null -> {
                    context.getString(R.string.local_song_lyric_external)
                }
                !effectiveLyricContent.isNullOrBlank() -> {
                    context.getString(R.string.local_song_lyric_embedded)
                }
                else -> null
            },
            originalTitle = title,
            originalArtist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: queried.artist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
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
        val needsEmbeddedLyrics =
            (nearbyLyric == null && localMetadata?.hasLyricOverride != true) ||
                (nearbyTranslatedLyric == null &&
                    localMetadata?.hasTranslatedLyricOverride != true) ||
                (nearbyRomanizedLyric == null &&
                    localMetadata?.hasRomanizedLyricOverride != true)
        val embedded = if (needsEmbeddedLyrics) {
            runCatching {
                inspectTagLibMetadata(
                    context = context,
                    uri = resolved.playableUri,
                    file = resolved.file,
                    includeEmbeddedAssets = false,
                    includeEmbeddedLyrics = true,
                    includeAudioProperties = false
                )
            }.onFailure {
                NPLogger.w(
                    TAG,
                    "quick scan embedded lyrics inspection failed for $uri: ${it.message}"
                )
            }.getOrNull()
        } else {
            null
        }
        return LocalLyricsScanMetadata(
            lyric = nearbyLyric
                ?: embedded?.lyrics
                ?: localMetadata?.takeIf { it.hasLyricOverride }?.lyric,
            translatedLyric = nearbyTranslatedLyric
                ?: embedded?.translatedLyrics
                ?: localMetadata?.takeIf { it.hasTranslatedLyricOverride }?.translatedLyric,
            romanizedLyric = nearbyRomanizedLyric
                ?: embedded?.romanizedLyrics
                ?: localMetadata?.takeIf { it.hasRomanizedLyricOverride }?.romanizedLyric,
            hasOriginalSidecar = nearbyFiles.original != null || nearbyReferences.original != null,
            hasTranslatedSidecar = nearbyFiles.translated != null || nearbyReferences.translated != null,
            hasRomanizedSidecar = nearbyFiles.romanized != null || nearbyReferences.romanized != null,
            embeddedLyric = embedded?.lyrics,
            embeddedTranslatedLyric = embedded?.translatedLyrics,
            embeddedRomanizedLyric = embedded?.romanizedLyrics
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
            includeEmbeddedLyrics = true,
            includeAudioProperties = false
        )
        val retrieverMetadata = if (
            shouldProbeRetrieverTextMetadata(resolved.playableUri.toString(), file)
        ) {
            readRetrieverTextMetadata(context, resolved.playableUri)
        } else {
            RetrieverTextMetadata()
        }
        val localMetadata = readLocalMetadataSidecar(
            context = context,
            sourceUri = uri,
            file = file,
            displayName = resolved.displayName
        )
        val title = pickReadableLocalTitle(
            sourceUri = uri,
            fallbackTitle = resolved.fallbackTitle,
            tagLibMetadata?.title,
            retrieverMetadata.title,
            containerMetadata?.title,
            queried.title,
            localMetadata?.customName,
            localMetadata?.name
        ) ?: resolved.fallbackTitle
        val artist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: retrieverMetadata.artist.takeMeaningfulLocalMetadata()
            ?: retrieverMetadata.albumArtist.takeMeaningfulLocalMetadata()
            ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: queried.artist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
            ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
            ?: context.getString(R.string.music_unknown_artist)
        val rawAlbum = tagLibMetadata?.album.takeMeaningfulLocalMetadata()
            ?: retrieverMetadata.album.takeMeaningfulLocalMetadata()
            ?: containerMetadata?.album.takeMeaningfulLocalMetadata()
            ?: queried.album.takeMeaningfulLocalMetadata()
            ?: localMetadata?.album.takeMeaningfulLocalMetadata()
        val usesFallbackAlbum = rawAlbum == null
        val resolvedAlbum = normalizeLocalAlbumIdentity(rawAlbum, usesFallbackAlbum)
        val nearbyLyricFiles = findNearbyLyricFiles(file)
        val nearbyLyricReferences = findNearbyLyricReferences(
            context = context,
            uri = uri,
            file = file,
            displayName = resolved.displayName
        )
        fun readLyric(reference: String?, fallback: File?, label: String): String? {
            return readNearbyLyricContent(
                context = context,
                reference = reference ?: fallback?.absolutePath,
                label = label
            )
        }
        val nearbyLyric = readLyric(
            reference = nearbyLyricReferences.original,
            fallback = nearbyLyricFiles.original,
            label = "metadata-only lyric"
        )
        val nearbyTranslatedLyric = readLyric(
            reference = nearbyLyricReferences.translated,
            fallback = nearbyLyricFiles.translated,
            label = "metadata-only translated lyric"
        )
        val nearbyRomanizedLyric = readLyric(
            reference = nearbyLyricReferences.romanized,
            fallback = nearbyLyricFiles.romanized,
            label = "metadata-only romanized lyric"
        )
        val effectiveLyric = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyLyric,
            embeddedContent = tagLibMetadata?.lyrics,
            metadataFallback = localMetadata?.takeIf { it.hasLyricOverride }?.lyric
        )
        val effectiveTranslatedLyric = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyTranslatedLyric,
            embeddedContent = tagLibMetadata?.translatedLyrics,
            metadataFallback = localMetadata
                ?.takeIf { it.hasTranslatedLyricOverride }
                ?.translatedLyric
        )
        val effectiveRomanizedLyric = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyRomanizedLyric,
            embeddedContent = tagLibMetadata?.romanizedLyrics,
            metadataFallback = localMetadata
                ?.takeIf { it.hasRomanizedLyricOverride }
                ?.romanizedLyric
        )
        val lyricReference = nearbyLyricReferences.original
            ?: nearbyLyricFiles.original?.absolutePath
            ?: localMetadata?.reference?.takeIf { localMetadata.hasLyricOverride }
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
            lyricContent = effectiveLyric,
            lyricPath = resolveEffectiveLocalLyricPath(
                reference = lyricReference,
                content = effectiveLyric
            ),
            lyricSource = when {
                nearbyLyric != null -> context.getString(R.string.local_song_lyric_external)
                !effectiveLyric.isNullOrBlank() -> context.getString(R.string.local_song_lyric_embedded)
                else -> null
            },
            originalTitle = title,
            originalArtist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: retrieverMetadata.artist.takeMeaningfulLocalMetadata()
                ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: queried.artist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: artist,
            embeddedCover = false,
            sourceStableKey = tagLibMetadata?.sourceStableKey,
            translatedLyricContent = effectiveTranslatedLyric,
            romanizedLyricContent = effectiveRomanizedLyric
        )
    }

    fun resolveCoverUri(context: Context, song: SongItem): String? {
        return song.localMediaUriCandidates()
            .asSequence()
            .mapNotNull { candidate -> resolveCoverUri(context, candidate) }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .firstOrNull { isUsableCoverReference(context, it) }
    }

    internal fun resolveCoverReferenceByPriority(
        sidecarReference: String?,
        embeddedReference: String?,
        fallbackReference: String? = null
    ): String? {
        return sequenceOf(sidecarReference, embeddedReference, fallbackReference)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
    }

    /**
     * 只查找本地 Covers 或同目录封面，不打开音频解析内嵌图片
     */
    fun resolveNearbyCoverUri(context: Context, song: SongItem): String? {
        return try {
            resolveNearbyCoverUriInternal(context, song)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            invalidateSafReadCaches()
            NPLogger.w(
                TAG,
                "SAF 封面只读探测失败，降级为空: song=${song.songStableKey()}, " +
                    "message=${error.message}"
            )
            null
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "本地封面只读探测失败，降级为空: song=${song.songStableKey()}, " +
                    "type=${error::class.simpleName}, message=${error.message}"
            )
            null
        }
    }

    private fun resolveNearbyCoverUriInternal(context: Context, song: SongItem): String? {
        // metadata sidecar keeps the authoritative SAF cover reference even when
        // MediaStore cannot expose the sibling Covers directory directly
        runCatching {
            readLocalMetadataSidecarFast(context = context, song = song)
                ?.coverPath
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.takeIf { isUsableCoverReference(context, it) }
        }.getOrNull()?.let { return it }

        val uri = song.localMediaUri()
        val directFile = song.localFilePath
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it.startsWith("content://", ignoreCase = true) }
            ?.let(::File)
            ?.takeIf(File::isFile)
        if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
            val contentUri: Uri = uri
            val resolved = runCatching {
                resolveInspectableLocalMedia(
                    context = context,
                    uri = contentUri,
                    allowDescriptorFallback = false
                )
            }.getOrNull()
            resolved?.let {
                findNearbyCoverReference(
                    context = context,
                    uri = contentUri,
                    file = it.file,
                    displayName = it.displayName
                )
            }?.let { return it }
        }
        if (directFile != null) {
            return findNearbyCoverReference(
                context = context,
                uri = Uri.fromFile(directFile),
                file = directFile,
                displayName = directFile.name
            )
        }
        if (uri == null) return null
        val resolved = runCatching {
            resolveInspectableLocalMedia(
                context = context,
                uri = uri,
                allowDescriptorFallback = false
            )
        }.getOrNull() ?: return null
        return findNearbyCoverReference(
            context = context,
            uri = uri,
            file = resolved.file,
            displayName = resolved.displayName
        )
    }

    /**
     * MediaStore can expose the already indexed album artwork without opening
     * the audio container. This is only a fast hint; sidecar and embedded
     * cover resolution remain the authoritative fallbacks.
     */
    fun peekMediaStoreAlbumArtUri(context: Context, song: SongItem): String? {
        val source = song.localMediaUri() ?: return null
        return peekMediaStoreAlbumArtUri(context, source)
    }

    fun peekMediaStoreAlbumArtUri(context: Context, source: Uri): String? {
        if (!isMediaStoreAuthority(source.authority)) {
            return null
        }
        val cacheKey = source.toString()
        synchronized(mediaStoreAlbumArtCache) {
            mediaStoreAlbumArtCache[cacheKey]?.let { return it }
        }
        val coverUri = runCatching {
            context.contentResolver.query(
                source,
                arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                if (index < 0 || cursor.isNull(index)) return@use null
                cursor.getLong(index).takeIf { it > 0L }
            }?.let { albumId ->
                mediaStoreAlbumArtUri(albumId)
            }
        }.onFailure {
            NPLogger.d(TAG, "MediaStore album art hint unavailable for $source: ${it.message}")
        }.getOrNull()?.takeIf { isUsableCoverReference(context, it) }
        if (coverUri != null) {
            synchronized(mediaStoreAlbumArtCache) {
                mediaStoreAlbumArtCache[cacheKey] = coverUri
            }
        }
        return coverUri
    }

    fun mediaStoreAlbumArtUri(albumId: Long): String {
        require(albumId > 0L) { "albumId must be positive" }
        return "content://$MEDIA_STORE_AUTHORITY/external/audio/albumart/$albumId"
    }

    /**
     * 列表恢复时只检查已经生成的缩略图, 避免重新打开音频并解析内嵌图片
     */
    fun peekCachedEmbeddedCoverUri(context: Context, song: SongItem): String? {
        return embeddedCoverCacheLookupKeys(song)
            .asSequence()
            .flatMap { key -> sequenceOf(key, "$key#taglib") }
            .firstNotNullOfOrNull { key -> findCachedEmbeddedCover(context, key) }
    }

    internal fun peekCachedEmbeddedCoverUri(context: Context, source: Uri): String? {
        return listOfNotNull(source.toString(), source.path)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .asSequence()
            .flatMap { key -> sequenceOf(key, "$key#taglib") }
            .firstNotNullOfOrNull { key -> findCachedEmbeddedCover(context, key) }
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
        return try {
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
            cachedLocalCoverLookup(context, cacheKey)?.let { cached ->
                cached.coverUri?.let { return it }

                // Do not retain an empty nearby-cover result. A user can add artwork
                // beside an unchanged audio file without restarting the app.
                findNearbyCoverReference(
                    context = context,
                    uri = uri,
                    file = resolved.file,
                    displayName = resolved.displayName
                )?.takeIf { isUsableCoverReference(context, it) }?.let { nearbyCover ->
                    rememberLocalCoverLookup(cacheKey, nearbyCover)
                    return nearbyCover
                }
                return null
            }

            val resolvedCover = sequence {
                findNearbyCoverReference(
                    context = context,
                    uri = uri,
                    file = resolved.file,
                    displayName = resolved.displayName
                )?.let { yield(it) }
                peekMediaStoreAlbumArtUri(context, uri)?.let { yield(it) }
                findCachedEmbeddedCover(context, resolved.resolvedPath ?: uri.toString())
                    ?.let { yield(it) }
                findCachedEmbeddedCover(context, "${resolved.resolvedPath ?: uri}#taglib")
                    ?.let { yield(it) }
                extractEmbeddedCoverWithRetriever(context, uri, resolved)?.let { yield(it) }
                extractEmbeddedCoverWithTagLib(context, uri, resolved)?.let { yield(it) }
            }.firstOrNull { isUsableCoverReference(context, it) }
            rememberLocalCoverLookup(cacheKey, resolvedCover)
            resolvedCover
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            invalidateSafReadCaches()
            NPLogger.w(
                TAG,
                "SAF 封面解析权限不可用，降级为空: uri=$uri, message=${error.message}"
            )
            null
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "本地封面解析失败，降级为空: uri=$uri, " +
                    "type=${error::class.simpleName}, message=${error.message}"
            )
            null
        }
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
        val nearbyCoverReference = findNearbyCoverReference(
            context = context,
            uri = uri,
            file = file,
            displayName = displayName
        )
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
        val hasEffectiveExternalLyric = nearbyLyricContent != null
        val effectiveLyricContent = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyLyricContent,
            embeddedContent = tagLibMetadata?.lyrics,
            metadataFallback = localMetadata?.takeIf { it.hasLyricOverride }?.lyric
        )
        val effectiveTranslatedLyricContent = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyTranslatedLyricContent,
            embeddedContent = tagLibMetadata?.translatedLyrics,
            metadataFallback = localMetadata
                ?.takeIf { it.hasTranslatedLyricOverride }
                ?.translatedLyric
        )
        val effectiveRomanizedLyricContent = resolveLocalLyricContentByPriority(
            sidecarContent = nearbyRomanizedLyricContent,
            embeddedContent = tagLibMetadata?.romanizedLyrics,
            metadataFallback = localMetadata
                ?.takeIf { it.hasRomanizedLyricOverride }
                ?.romanizedLyric
        )

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
                queried.title,
                localMetadata?.customName,
                localMetadata?.name
            )
            val title = rawTitle ?: fallbackTitle
            val artist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .takeMeaningfulLocalMetadata()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    .takeMeaningfulLocalMetadata()
                ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: queried.artist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: context.getString(R.string.music_unknown_artist)
            val rawAlbum = tagLibMetadata?.album.takeMeaningfulLocalMetadata()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    .takeMeaningfulLocalMetadata()
                ?: containerMetadata?.album.takeMeaningfulLocalMetadata()
                ?: queried.album.takeMeaningfulLocalMetadata()
                ?: localMetadata?.album.takeMeaningfulLocalMetadata()
            val usesFallbackAlbum = rawAlbum == null
            val resolvedAlbum = normalizeLocalAlbumIdentity(rawAlbum, usesFallbackAlbum)
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
            val effectiveNearbyCover = nearbyCoverReference

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
                coverUri = resolveCoverReferenceByPriority(
                    sidecarReference = effectiveNearbyCover,
                    embeddedReference = embeddedCoverUri ?: tagLibCoverUri
                ),
                coverSource = when {
                    effectiveNearbyCover != null -> context.getString(R.string.local_song_cover_external)
                    embeddedCoverUri != null || tagLibCoverUri != null -> {
                        context.getString(R.string.local_song_cover_embedded)
                    }
                    else -> null
                },
                lyricContent = effectiveLyricContent,
                lyricPath = resolveEffectiveLocalLyricPath(
                    reference = nearbyLyricReferences.original
                        ?: nearbyLyricFiles.original?.absolutePath
                        ?: localMetadata?.reference?.takeIf { localMetadata.hasLyricOverride },
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
                originalArtist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
                    ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
                    ?: queried.artist.takeMeaningfulLocalMetadata()
                    ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
                    ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
                    ?: artist,
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
                queried.title,
                localMetadata?.customName,
                localMetadata?.name
            )
            val title = rawTitle ?: fallbackTitle
            val artist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: queried.artist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
                ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
                ?: context.getString(R.string.music_unknown_artist)
            val rawAlbum = tagLibMetadata?.album.takeMeaningfulLocalMetadata()
                ?: containerMetadata?.album.takeMeaningfulLocalMetadata()
                ?: queried.album.takeMeaningfulLocalMetadata()
                ?: localMetadata?.album.takeMeaningfulLocalMetadata()
            val usesFallbackAlbum = rawAlbum == null
            val resolvedAlbum = normalizeLocalAlbumIdentity(rawAlbum, usesFallbackAlbum)
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
                coverUri = resolveCoverReferenceByPriority(
                    sidecarReference = nearbyCoverReference,
                    embeddedReference = tagLibCoverUri
                ),
                coverSource = when {
                    nearbyCoverReference != null -> {
                        context.getString(R.string.local_song_cover_external)
                    }
                    tagLibCoverUri != null -> context.getString(R.string.local_song_cover_embedded)
                    else -> null
                },
                lyricContent = effectiveLyricContent,
                lyricPath = resolveEffectiveLocalLyricPath(
                    reference = nearbyLyricReferences.original
                        ?: nearbyLyricFiles.original?.absolutePath
                        ?: localMetadata?.reference?.takeIf { localMetadata.hasLyricOverride },
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
                originalArtist = tagLibMetadata?.artist.takeMeaningfulLocalMetadata()
                    ?: containerMetadata?.artist.takeMeaningfulLocalMetadata()
                    ?: queried.artist.takeMeaningfulLocalMetadata()
                    ?: localMetadata?.customArtist.takeMeaningfulLocalMetadata()
                    ?: localMetadata?.artist.takeMeaningfulLocalMetadata()
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
            album = normalizeLocalAlbumIdentity(
                selectedMetadata.album,
                selectedMetadata.usesFallbackAlbum
            ),
            usesFallbackAlbum = selectedMetadata.usesFallbackAlbum,
            albumArtist = null,
            composer = null,
            genre = null,
            year = null,
            trackNumber = null,
            discNumber = null,
            durationMs = selectedMetadata.durationMs.takeIf { it > 0L }
                ?: audioTrackTechInfo?.durationMs
                ?: 0L,
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
            matchedRomanizedLyric = details.romanizedLyricContent,
            originalLyric = details.lyricContent,
            originalTranslatedLyric = details.translatedLyricContent,
            originalRomanizedLyric = details.romanizedLyricContent,
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
            reference.startsWith("/") -> try {
                readLimitedTextFile(File(reference))
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "read bytes failed for $reference: ${error.message}")
                null
            }
            else -> try {
                context.contentResolver.openInputStream(reference.toUri())
                    ?.use(::readLimitedTextStream)
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "read stream failed for $reference: ${error.message}")
                null
            }
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
        )?.takeUnless(::isMediaStoreSidecarReference) ?: return null
        readTextContent(context, reference)
            ?.let { raw -> parseLocalMetadataSidecar(reference, raw) }
            ?.let { return it }

        // SAF 文件可能同时暴露出不可直接读取的绝对路径, 失败后重新走文档树
        if (sourceUri.scheme.equals("content", ignoreCase = true) && file != null) {
            val documentReference = resolveLocalMetadataReference(
                context = context,
                sourceUri = sourceUri,
                file = null,
                displayName = displayName
            )?.takeUnless { it == reference || isMediaStoreSidecarReference(it) }
            if (documentReference != null) {
                readTextContent(context, documentReference)
                    ?.let { raw -> parseLocalMetadataSidecar(documentReference, raw) }
                    ?.let { return it }
            }
        }
        return null
    }

    internal fun readLocalMetadataSidecarFast(
        context: Context,
        song: SongItem,
        metadataReference: String? = null
    ): LocalMetadataSidecar? {
        return try {
            val explicitReference = metadataReference
                ?.trim()
                ?.takeIf(String::isNotBlank)
            if (explicitReference != null && !isMediaStoreSidecarReference(explicitReference)) {
                readTextContent(context, explicitReference)
                    ?.let { raw -> parseLocalMetadataSidecar(explicitReference, raw) }
                    ?.let { return it }
            }

            val sourceUri = song.localMediaUri()
            val file = song.localFilePath
                ?.takeIf { it.isNotBlank() && !it.startsWith("content://", ignoreCase = true) }
                ?.let(::File)
                ?.takeIf(File::isFile)
            if (file != null) {
                val metadataFile = File(
                    file.parentFile ?: return null,
                    file.name + LOCAL_METADATA_SUFFIX
                )
                val reference = metadataFile
                    .takeIf { shouldProbeAbsoluteMetadataSidecar(sourceUri, it) }
                    ?.absolutePath
                if (reference != null) {
                    readTextContent(context, reference)
                        ?.let { raw -> parseLocalMetadataSidecar(reference, raw) }
                        ?.let { return it }
                }
            }

            val resolvedSourceUri = sourceUri ?: return null
            val resolved = runCatching {
                resolveInspectableLocalMedia(
                    context = context,
                    uri = resolvedSourceUri,
                    allowDescriptorFallback = false
                )
            }.getOrNull() ?: return null
            readLocalMetadataSidecar(
                context = context,
                sourceUri = resolvedSourceUri,
                file = resolved.file,
                displayName = resolved.displayName
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            invalidateSafReadCaches()
            NPLogger.w(
                TAG,
                "SAF 本地 metadata sidecar 权限不可用，降级为空: " +
                    "song=${song.songStableKey()}, message=${error.message}"
            )
            null
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "本地 metadata sidecar 读取失败，降级为空: " +
                    "song=${song.songStableKey()}, " +
                    "type=${error::class.simpleName}, message=${error.message}"
            )
            null
        }
    }

    internal fun shouldProbeAbsoluteMetadataSidecar(
        sourceUri: Uri?,
        metadataFile: File
    ): Boolean {
        if (sourceUri?.let(::isExternalStorageDocumentUri) == true) return false
        if (!isReadableLocalFile(metadataFile)) return false
        if (sourceUri?.let(::isMediaStoreUri) != true) return true
        val legacyRoot = LEGACY_DOWNLOAD_ROOT.trimEnd(File.separatorChar)
        val path = metadataFile.absolutePath
        return path != legacyRoot && !path.startsWith(legacyRoot + File.separator)
    }

    internal fun shouldProbeRetrieverTextMetadata(
        sourceReference: String?,
        file: File?
    ): Boolean {
        val normalized = sourceReference?.trim()?.lowercase(Locale.ROOT) ?: return true
        val isMediaStoreReference = normalized.startsWith("content://media/") ||
            normalized.startsWith("content://com.android.providers.media.documents/")
        if (!isMediaStoreReference) return true
        return file?.let(::isReadableLocalFile) == true
    }

    internal fun parseLocalMetadataSidecar(
        reference: String,
        raw: String
    ): LocalMetadataSidecar? {
        return runCatching {
            val root = JSONObject(raw)
            LocalMetadataSidecar(
                reference = reference,
                name = root.optPresentLocalMetadataString("name"),
                artist = root.optPresentLocalMetadataString("artist"),
                album = root.optPresentLocalMetadataString("album")
                    ?: root.optPresentLocalMetadataString("identityAlbum"),
                customName = root.optPresentLocalMetadataString("customName"),
                customArtist = root.optPresentLocalMetadataString("customArtist"),
                originalName = root.optPresentLocalMetadataString("originalName"),
                originalArtist = root.optPresentLocalMetadataString("originalArtist"),
                stableKey = root.optPresentLocalMetadataString("stableKey"),
                songId = root.optLong("songId").takeIf { root.has("songId") && it != 0L },
                channelId = root.optPresentLocalMetadataString("channelId"),
                audioId = root.optPresentLocalMetadataString("audioId"),
                subAudioId = root.optPresentLocalMetadataString("subAudioId"),
                playlistContextId = root.optPresentLocalMetadataString("playlistContextId"),
                coverPath = root.optPresentLocalMetadataString("coverPath"),
                coverUrl = root.optPresentLocalMetadataString("coverUrl"),
                originalCoverUrl = root.optPresentLocalMetadataString("originalCoverUrl"),
                customCoverUrl = root.optPresentLocalMetadataString("customCoverUrl"),
                durationMs = root.optLong("durationMs").coerceAtLeast(0L),
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
        song: SongItem,
        clearMissingLyricFields: Boolean = false
    ): String {
        val root = existingRaw
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        updateLyricMetadataField(
            root = root,
            matchedKey = "matchedLyric",
            originalKey = "originalLyric",
            matchedValue = song.matchedLyric,
            originalValue = song.originalLyric,
            clearMissing = clearMissingLyricFields
        )
        updateLyricMetadataField(
            root = root,
            matchedKey = "matchedTranslatedLyric",
            originalKey = "originalTranslatedLyric",
            matchedValue = song.matchedTranslatedLyric,
            originalValue = song.originalTranslatedLyric,
            clearMissing = clearMissingLyricFields
        )
        updateLyricMetadataField(
            root = root,
            matchedKey = "matchedRomanizedLyric",
            originalKey = "originalRomanizedLyric",
            matchedValue = song.matchedRomanizedLyric,
            originalValue = song.originalRomanizedLyric,
            clearMissing = clearMissingLyricFields
        )
        return root.toString()
    }

    internal fun buildEditableLocalMetadataJson(
        existingRaw: String?,
        song: SongItem,
        writeLyrics: Boolean,
        coverReference: String?,
        clearCoverReference: Boolean
    ): String {
        val root = existingRaw
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()

        fun putValue(key: String, value: Any?) {
            if (value == null) {
                root.remove(key)
            } else {
                root.put(key, value)
            }
        }

        putValue("name", song.name)
        putValue("artist", song.artist)
        putValue("album", song.album)
        putValue("customName", song.customName)
        putValue("customArtist", song.customArtist)
        putValue("originalName", song.originalName)
        putValue("originalArtist", song.originalArtist)
        putValue("coverUrl", song.coverUrl)
        putValue("customCoverUrl", song.customCoverUrl)
        putValue("originalCoverUrl", song.originalCoverUrl)
        putValue("mediaUri", song.mediaUri)
        putValue("localFilePath", song.localFilePath)
        putValue("stableKey", song.songStableKey())
        putValue("songId", song.id)
        putValue("channelId", song.channelId)
        putValue("audioId", song.audioId)
        putValue("subAudioId", song.subAudioId)
        putValue("playlistContextId", song.playlistContextId)
        putValue("durationMs", song.durationMs)

        if (clearCoverReference) {
            root.remove("coverPath")
        } else if (coverReference != null) {
            putValue("coverPath", coverReference)
        }

        if (writeLyrics) {
            updateLyricMetadataField(
                root = root,
                matchedKey = "matchedLyric",
                originalKey = "originalLyric",
                matchedValue = song.matchedLyric,
                originalValue = song.originalLyric,
                clearMissing = true
            )
            updateLyricMetadataField(
                root = root,
                matchedKey = "matchedTranslatedLyric",
                originalKey = "originalTranslatedLyric",
                matchedValue = song.matchedTranslatedLyric,
                originalValue = song.originalTranslatedLyric,
                clearMissing = true
            )
            updateLyricMetadataField(
                root = root,
                matchedKey = "matchedRomanizedLyric",
                originalKey = "originalRomanizedLyric",
                matchedValue = song.matchedRomanizedLyric,
                originalValue = song.originalRomanizedLyric,
                clearMissing = true
            )
            putValue("matchedLyricSource", song.matchedLyricSource?.name)
            putValue("matchedSongId", song.matchedSongId)
            putValue("userLyricOffsetMs", song.userLyricOffsetMs)
        }
        return root.toString()
    }

    private fun updateLyricMetadataField(
        root: JSONObject,
        matchedKey: String,
        originalKey: String,
        matchedValue: String?,
        originalValue: String?,
        clearMissing: Boolean
    ) {
        matchedValue?.let { root.put(matchedKey, it) }
        originalValue?.let { root.put(originalKey, it) }
        if (clearMissing && matchedValue == null && originalValue == null) {
            root.remove(matchedKey)
            root.remove(originalKey)
        }
    }

    private fun writeLocalLyricsMetadata(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String,
        song: SongItem,
        knownReference: String? = null,
        writeFullMetadata: Boolean = false,
        writeLyricFields: Boolean = true,
        coverReference: String? = null,
        clearCoverReference: Boolean = false
    ): Boolean {
        val localFile = file.takeUnless {
            shouldUseDocumentSidecarMutation(sourceUri)
        }
        val localMetadataReference = knownReference
            ?.takeUnless(::isMediaStoreSidecarReference)
            ?.takeUnless { reference ->
                shouldUseDocumentSidecarMutation(sourceUri) && reference.startsWith("/")
            }
        val navigation = sourceUri.takeIf { uri ->
            uri.scheme.equals("content", ignoreCase = true)
        }?.let { uri -> resolveLocalDocumentNavigation(context, uri) }
        val parentId = navigation?.parentDocumentId
        if (
            navigation != null && parentId != null &&
                (localFile == null || isMediaStoreUri(sourceUri))
        ) {
            val baseUri = navigation.treeUri ?: navigation.baseUri
            val metadataName = displayName + LOCAL_METADATA_SUFFIX
            val written = withDocumentMutationLock(baseUri, parentId) {
                val parentChildren = queryDocumentChildrenForMutation(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = parentId
                ) ?: return@withDocumentMutationLock false
                if (!documentChildrenContainSource(
                        parentChildren = parentChildren,
                        sourceUri = sourceUri,
                        displayName = displayName,
                        parentDocumentId = parentId
                    )
                ) {
                    return@withDocumentMutationLock false
                }
                val targetReference = parentChildren.firstOrNull { child ->
                    child.uri == localMetadataReference &&
                        !child.isDirectory &&
                        canonicalSafName(child.displayName) == canonicalSafName(metadataName)
                }?.uri
                    ?: findDocumentSidecarChild(parentChildren, metadataName)?.uri
                    ?: createDocumentSidecarForMutation(
                        context = context,
                        baseUri = baseUri,
                        parentDocumentId = parentId,
                        mimeType = "application/json",
                        displayName = metadataName,
                        parentChildren
                    )?.uri
                    ?: return@withDocumentMutationLock false
                writeLocalLyricsMetadataReference(
                    context = context,
                    reference = targetReference,
                    file = null,
                    song = song,
                    writeFullMetadata = writeFullMetadata,
                    writeLyricFields = writeLyricFields,
                    coverReference = coverReference,
                    clearCoverReference = clearCoverReference
                )
            }
            clearLyricsLookupCache()
            return written
        }
        val metadataReference = localMetadataReference
            ?: resolveLocalMetadataReference(
                context = context,
                sourceUri = sourceUri,
                file = localFile,
                displayName = displayName
            )
        val targetReference = metadataReference ?: createLocalMetadataReference(
            context = context,
            sourceUri = sourceUri,
            file = localFile,
            displayName = displayName
        ) ?: return false
        val written = writeLocalLyricsMetadataReference(
            context = context,
            reference = targetReference,
            file = localFile,
            song = song,
            writeFullMetadata = writeFullMetadata,
            writeLyricFields = writeLyricFields,
            coverReference = coverReference,
            clearCoverReference = clearCoverReference
        )
        clearLyricsLookupCache()
        return written
    }

    private fun writeLocalLyricsMetadataReference(
        context: Context,
        reference: String,
        file: File?,
        song: SongItem,
        writeFullMetadata: Boolean = false,
        writeLyricFields: Boolean = true,
        coverReference: String? = null,
        clearCoverReference: Boolean = false
    ): Boolean {
        val existingRaw = readTextContent(context, reference)
        if (existingRaw == null && isReadableLocalReference(context, reference)) {
            NPLogger.w(TAG, "拒绝覆盖无法读取的本地 metadata sidecar: $reference")
            return false
        }
        val updatedRaw = if (writeFullMetadata) {
            buildEditableLocalMetadataJson(
                existingRaw = existingRaw,
                song = song,
                writeLyrics = writeLyricFields,
                coverReference = coverReference,
                clearCoverReference = clearCoverReference
            )
        } else {
            buildLocalLyricsMetadataJson(
                existingRaw = existingRaw,
                song = song,
                clearMissingLyricFields = false
            )
        }
        return writeLocalMetadataReference(context, reference, file, updatedRaw)
    }

    private fun isReadableLocalReference(context: Context, reference: String): Boolean {
        if (reference.startsWith("/")) {
            return File(reference).isFile
        }
        val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
        val isDocumentUri = try {
            DocumentsContract.isDocumentUri(context, uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!isDocumentUri) return false
        return when (
            val result = ManagedDownloadReferenceIo.inspect(context, uri.toString())
        ) {
            ManagedDownloadReferenceIo.AccessResult.Accessible -> true
            ManagedDownloadReferenceIo.AccessResult.Missing -> false
            ManagedDownloadReferenceIo.AccessResult.PermissionLost -> {
                throw SecurityException("local metadata permission lost: $uri")
            }
            is ManagedDownloadReferenceIo.AccessResult.ProviderFailure -> throw result.error
        }
    }

    private fun resolveLocalMetadataReference(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String
    ): String? {
        val localFile = file.takeUnless {
            shouldUseDocumentSidecarMutation(sourceUri)
        }
        localFile?.let { resolvedFile ->
            val target = File(
                resolvedFile.parentFile ?: return@let,
                resolvedFile.name + LOCAL_METADATA_SUFFIX
            )
            if (shouldProbeAbsoluteMetadataSidecar(sourceUri, target)) {
                return target.absolutePath
            }
        }
        val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return null
        val parentChildren = queryDocumentChildren(
            context = context,
            baseUri = navigation.treeUri ?: navigation.baseUri,
            parentDocumentId = navigation.parentDocumentId
        )
        val metadataName = displayName + LOCAL_METADATA_SUFFIX
        return findDocumentSidecarChild(parentChildren, metadataName)?.uri ?: localFile?.let { resolvedFile ->
            val target = resolvedFile.parentFile
                ?.let { parent -> File(parent, resolvedFile.name + LOCAL_METADATA_SUFFIX) }
                ?: return@let null
            target.absolutePath.takeIf {
                shouldProbeAbsoluteMetadataSidecar(sourceUri, target)
            }
        }
    }

    private fun createLocalMetadataReference(
        context: Context,
        sourceUri: Uri,
        file: File?,
        displayName: String
    ): String? {
        val localFile = file.takeUnless {
            shouldUseDocumentSidecarMutation(sourceUri)
        }
        if (localFile != null && !isMediaStoreUri(sourceUri)) {
            return File(
                localFile.parentFile ?: return null,
                localFile.name + LOCAL_METADATA_SUFFIX
            ).absolutePath
        }
        val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return null
        val parentId = navigation.parentDocumentId ?: return null
        val baseUri = navigation.treeUri ?: navigation.baseUri
        val metadataName = displayName + LOCAL_METADATA_SUFFIX
        val documentReference = withDocumentMutationLock(baseUri, parentId) {
            val parentChildren = queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentId
            ) ?: return@withDocumentMutationLock null
            if (!documentChildrenContainSource(
                    parentChildren = parentChildren,
                    sourceUri = sourceUri,
                    displayName = displayName,
                    parentDocumentId = parentId
                )
            ) {
                return@withDocumentMutationLock null
            }
            findDocumentSidecarChild(parentChildren, metadataName)?.uri
                ?: createDocumentSidecarForMutation(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = parentId,
                    mimeType = "application/json",
                    displayName = metadataName
                )?.uri
        }
        return documentReference ?: if (!isMediaStoreUri(sourceUri)) localFile?.let { resolvedFile ->
                resolvedFile.parentFile
                    ?.let { parent -> File(parent, resolvedFile.name + LOCAL_METADATA_SUFFIX) }
                    ?.absolutePath
            } else null
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
            return try {
                temp.writeText(content, Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.isFile && readTextFile(target) == content
            } catch (error: SecurityException) {
                temp.delete()
                throw error
            } catch (error: Exception) {
                temp.delete()
                NPLogger.w(TAG, "write local metadata sidecar failed for $reference: ${error.message}")
                false
            }
        }
        return writeTextContent(context, reference, content)
    }

    private fun resolveLocalDocumentNavigation(
        context: Context,
        uri: Uri
    ): LocalDocumentNavigation? {
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        val cacheKey = "generation=${LocalStorageRootGeneration.current()}|$uri"
        synchronized(documentNavigationCache) {
            documentNavigationCache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.cachedAtMs <= DOCUMENT_CHILDREN_CACHE_TTL_MS) {
                    return cached.navigation
                }
                documentNavigationCache.remove(cacheKey)
            }
        }
        val navigation = if (isMediaStoreUri(uri)) {
            resolveMediaStoreDocumentNavigation(context, uri)
        } else {
            val treeDocumentId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val documentId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val treeUri = try {
                val authority = uri.authority
                if (authority == null) {
                    null
                } else {
                    treeDocumentId?.let { DocumentsContract.buildTreeDocumentUri(authority, it) }
                }
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val documentUri = if (treeUri != null && documentId != null) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            } else {
                uri
            }
            val providerParentId = findDocumentParentId(context, documentUri)
            LocalDocumentNavigation(
                baseUri = uri,
                treeUri = treeUri,
                parentDocumentId = providerParentId ?: treeDocumentId
            )
        }
        synchronized(documentNavigationCache) {
            documentNavigationCache[cacheKey] = DocumentNavigationCacheEntry(
                navigation = navigation,
                cachedAtMs = System.currentTimeMillis()
            )
        }
        return navigation
    }

    /**
     * MediaStore hides sibling files from the audio URI. Re-enter the
     * persisted external-storage tree using RELATIVE_PATH so Lyrics/Covers
     * and metadata sidecars remain addressable after scoped-storage changes
     */
    private fun resolveMediaStoreDocumentNavigation(
        context: Context,
        sourceUri: Uri
    ): LocalDocumentNavigation? {
        val relativePath = queryContentInfo(context, sourceUri).relativePath
            ?.trim()
            ?.trim('/')
            ?.takeIf(String::isNotBlank)
            ?: return null
        val treeUri = resolveExternalStorageTreeUri(context, relativePath) ?: return null
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        val rootSegments = documentPathSegments(treeDocumentId)
        val relativeSegments = documentPathSegments(relativePath)
        val targetSegments = when {
            rootSegments.isNotEmpty() && relativeSegments.startsWithSegments(rootSegments) -> {
                relativeSegments.drop(rootSegments.size)
            }
            rootSegments.isEmpty() -> relativeSegments
            else -> return null
        }
        var parentDocumentId = treeDocumentId
        targetSegments.forEach { segment ->
            val child = queryDocumentChildren(
                context = context,
                baseUri = treeUri,
                parentDocumentId = parentDocumentId
            ).firstOrNull { it.isDirectory && it.displayName == segment }
                ?: return null
            parentDocumentId = child.documentId
        }
        return LocalDocumentNavigation(
            baseUri = treeUri,
            treeUri = treeUri,
            parentDocumentId = parentDocumentId
        )
    }

    internal fun resolveWritableLocalMediaUri(
        context: Context,
        sourceUri: Uri
    ): Uri? {
        if (!isMediaStoreUri(sourceUri)) return sourceUri
        val contentInfo = queryContentInfo(context, sourceUri)
        val displayName = contentInfo.displayName?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val navigation = resolveMediaStoreDocumentNavigation(context, sourceUri) ?: return null
        val parentDocumentId = navigation.parentDocumentId ?: return null
        val baseUri = navigation.treeUri ?: navigation.baseUri
        return queryDocumentChildren(context, baseUri, parentDocumentId)
            .firstOrNull { child ->
                !child.isDirectory && child.displayName.equals(displayName, ignoreCase = true)
            }
            ?.uri
            ?.toUri()
            ?.takeIf { isWritableDocumentUri(context, it) }
    }

    internal fun buildExternalStorageDocumentId(
        parentDocumentId: String,
        displayName: String
    ): String? {
        if (parentDocumentId.isBlank() || displayName.isBlank()) return null
        if (displayName.contains('/') || displayName.contains('\\')) return null
        return "${parentDocumentId.trimEnd('/')}/$displayName"
    }

    private fun isWritableDocumentUri(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } == true
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveExternalStorageTreeUri(
        context: Context,
        relativePath: String
    ): Uri? {
        val candidates = buildList {
            ManagedDownloadStorage.configuredDirectoryUri()
                ?.let { runCatching { it.toUri() }.getOrNull() }
                ?.let(::add)
            context.contentResolver.persistedUriPermissions
                .asSequence()
                .filter { it.isReadPermission || it.isWritePermission }
                .map { it.uri }
                .forEach(::add)
        }.filter { uri ->
            uri.authority == "com.android.externalstorage.documents" &&
                runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
        }.distinctBy(Uri::toString)

        val relativeSegments = documentPathSegments(relativePath)
        return candidates.firstOrNull { treeUri ->
            val treeId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val rootSegments = documentPathSegments(treeId)
            rootSegments.isEmpty() || relativeSegments.startsWithSegments(rootSegments)
        }
    }

    private fun documentPathSegments(value: String?): List<String> {
        val decoded = Uri.decode(value.orEmpty())
        val path = decoded.substringAfter(':', decoded)
        return path.split('/').filter(String::isNotBlank)
    }

    private fun List<String>.startsWithSegments(prefix: List<String>): Boolean {
        return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
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
        val relativePath: String?,
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
                relativePath = null,
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
                    relativePath = if (includeRelativePath) {
                        cursor.getOptionalString(MediaStore.MediaColumns.RELATIVE_PATH)
                    } else {
                        null
                    },
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
            relativePath = null,
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

                val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                        .div(1_000L)
                        .takeIf { it > 0L }
                } else {
                    null
                }
                val bitrateKbps = format.getOptionalInt(MediaFormat.KEY_BIT_RATE)
                    ?.let { max(0, (it + 500) / 1000) }
                val sampleRateHz = format.getOptionalInt(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getOptionalInt(MediaFormat.KEY_CHANNEL_COUNT)
                return AudioTrackTechInfo(
                    audioMimeType = trackMimeType,
                    bitrateKbps = bitrateKbps,
                    sampleRateHz = sampleRateHz,
                    channelCount = channelCount,
                    durationMs = durationMs
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
            NPLogger.d(TAG, "read retriever metadata unavailable for $uri: ${error.message}")
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
        includeEmbeddedLyrics: Boolean = includeEmbeddedAssets,
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
                lyrics = if (includeEmbeddedLyrics) {
                    propertyMap.readFirstValue(
                        NERI_ORIGINAL_LYRICS_METADATA_KEY,
                        "LYRICS",
                        "UNSYNCEDLYRICS",
                        "DESCRIPTION"
                    )
                } else {
                    null
                },
                translatedLyrics = if (includeEmbeddedLyrics) {
                    propertyMap.readFirstValue(*translatedLyricsMetadataKeys.toTypedArray())
                } else {
                    null
                },
                romanizedLyrics = if (includeEmbeddedLyrics) {
                    propertyMap.readFirstValue(NERI_ROMANIZED_LYRICS_METADATA_KEY)
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
        val isContentUri = uri.scheme.equals("content", ignoreCase = true)
        if (isContentUri) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
            }.getOrNull()?.let { return it }
        }
        file?.let { localFile ->
            runCatching {
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrNull()?.let { return it }
        }
        if (!isContentUri) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
            }.getOrNull()?.let { return it }
        }
        NPLogger.w(TAG, "openTagLibDescriptor failed for $uri")
        return null
    }

    private fun openWritableTagLibDescriptor(
        context: Context,
        uri: Uri,
        file: File?
    ): ParcelFileDescriptor? {
        val isContentUri = uri.scheme.equals("content", ignoreCase = true)
        if (isContentUri) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "rw")
            }.getOrNull()?.let { return it }
        }
        val fileDescriptor = file?.let { localFile ->
            runCatching {
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_WRITE)
            }.getOrNull()
        }
        if (fileDescriptor != null) {
            return fileDescriptor
        }

        val fallbackDescriptor = if (!isContentUri) {
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
        romanizedLyrics: String? = null,
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
            putTagValue(updated, NERI_ROMANIZED_LYRICS_METADATA_KEY, romanizedLyrics)
        }
        return updated
    }

    internal fun hasExpectedEditableMetadata(
        propertyMap: PropertyMap,
        title: String,
        artist: String,
        lyrics: String?,
        translatedLyrics: String?,
        romanizedLyrics: String? = null,
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
            hasExpectedOneOfTagValues(
                propertyMap = propertyMap,
                keys = listOf(NERI_ROMANIZED_LYRICS_METADATA_KEY),
                expectedValue = romanizedLyrics,
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
        data class Update(
            val pictures: Array<Picture>,
            val originalPictures: Array<Picture>
        ) : EditableCoverWritePlan() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Update

                if (!pictures.contentEquals(other.pictures)) return false
                if (!originalPictures.contentEquals(other.originalPictures)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = pictures.contentHashCode()
                result = 31 * result + originalPictures.contentHashCode()
                return result
            }
        }
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
                EditableCoverWritePlan.Update(
                    pictures = updatedPictures,
                    originalPictures = existingPictures
                )
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
        return EditableCoverWritePlan.Update(
            pictures = updatedPictures,
            originalPictures = existingPictures
        )
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
            return replacementPicture?.let { arrayOf(it) } ?: emptyArray<Picture>()
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

    private fun coverExtensionForMimeType(mimeType: String): String {
        return when (normalizeEditableCoverMimeType(mimeType)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            else -> "jpg"
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

    private fun cachedLocalCoverLookup(
        context: Context,
        cacheKey: String
    ): LocalCoverCacheHit? {
        val coverUri = synchronized(localCoverLookupCache) {
            if (!localCoverLookupCache.containsKey(cacheKey)) return null
            localCoverLookupCache[cacheKey]
        }
        if (coverUri == null) {
            // 没有封面时不保留负缓存，避免后续写入或恢复元信息后永远跳过重试
            synchronized(localCoverLookupCache) {
                localCoverLookupCache.remove(cacheKey)
            }
            return null
        }
        if (!isUsableCachedCoverUri(context, coverUri)) {
            synchronized(localCoverLookupCache) {
                if (localCoverLookupCache[cacheKey] == coverUri) {
                    localCoverLookupCache.remove(cacheKey)
                }
            }
            return null
        }
        return LocalCoverCacheHit(coverUri)
    }

    private fun isUsableCachedCoverUri(context: Context, coverUri: String): Boolean {
        return isUsableCoverReference(context, coverUri)
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
        if (!file.isFile || file.length() <= 0L) return null
        if (!isUsableCoverFile(file)) {
            if (!file.delete()) {
                NPLogger.w(TAG, "remove invalid embedded cover cache failed: ${file.name}")
            }
            return null
        }
        return file.toURI().toString()
    }

    private fun embeddedCoverFile(context: Context, uriKey: String): File {
        val coverDir = File(context.filesDir, "local_audio_covers")
        return File(coverDir, "${stableKey(uriKey)}.jpg")
    }

    private fun saveEmbeddedCover(context: Context, uriKey: String, embeddedPicture: ByteArray?): String? {
        if (embeddedPicture == null || embeddedPicture.isEmpty()) return null
        val file = embeddedCoverFile(context, uriKey)
        if (file.isFile && file.length() > 0L) {
            if (isUsableCoverFile(file)) {
                return file.toURI().toString()
            }
            if (!file.delete()) {
                NPLogger.w(TAG, "replace invalid embedded cover cache failed: ${file.name}")
            }
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
        if (sourceBytes.size <= MAX_EMBEDDED_COVER_CACHE_BYTES) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
            return sourceBytes.takeIf { bounds.outWidth > 0 && bounds.outHeight > 0 }
        }

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
        val legacyDownloadRoot = File(LEGACY_DOWNLOAD_ROOT)
        val isLegacyDownload = runCatching {
            isFileInsideDirectory(actualFile, legacyDownloadRoot)
        }.getOrDefault(false)
        val searchDirectories = buildList {
            if (isLegacyDownload) {
                add(File(legacyDownloadRoot, "Lyrics"))
            }
            add(File(parent, "Lyrics"))
            add(parent)
        }
            .filter(File::isDirectory)
            .distinctBy { it.absolutePath }

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
        return resolveContentSidecarReferences(
            context = context,
            sourceUri = uri,
            file = file,
            displayName = displayName
        ).lyricReferences
    }

    private data class ContentSidecarReferences(
        val metadataReference: String?,
        val lyricReferences: NearbyLyricReferences
    )

    private fun resolveContentSidecarReferences(
        context: Context,
        sourceUri: Uri,
        file: File? = null,
        displayName: String
    ): ContentSidecarReferences {
        val localFile = file.takeUnless {
            shouldUseDocumentSidecarMutation(sourceUri)
        }
        val localFiles = findNearbyLyricFiles(localFile)
        val localReferences = NearbyLyricReferences(
            original = localFiles.original?.absolutePath,
            translated = localFiles.translated?.absolutePath,
            romanized = localFiles.romanized?.absolutePath
        )
        if (!sourceUri.scheme.equals("content", ignoreCase = true)) {
            return ContentSidecarReferences(
                metadataReference = localMetadataReference(localFile),
                lyricReferences = localReferences
            )
        }

        val navigation = resolveLocalDocumentNavigation(context, sourceUri)
            ?: return ContentSidecarReferences(
                metadataReference = localMetadataReference(localFile),
                lyricReferences = localReferences
            )
        val parentDocumentId = navigation.parentDocumentId
            ?: return ContentSidecarReferences(
                metadataReference = localMetadataReference(localFile),
                lyricReferences = localReferences
            )
        val baseUri = navigation.treeUri ?: navigation.baseUri
        val parentChildren = queryDocumentChildren(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId
        )
        val audioBaseName = displayName.substringBeforeLast('.', displayName)
        val directReferences = resolveDocumentLyricReferences(
            children = parentChildren,
            baseName = audioBaseName
        )
        val lyricsDirectory = findManagedSidecarDirectory(parentChildren, "Lyrics")
        val nestedReferences = resolveDocumentLyricReferences(
            children = lyricsDirectory?.let {
                queryDocumentChildren(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = it.documentId
                )
            }.orEmpty(),
            baseName = audioBaseName
        )
        val metadataName = displayName + LOCAL_METADATA_SUFFIX
        val metadataReference = findDocumentSidecarChild(parentChildren, metadataName)?.uri
            ?: localMetadataReference(localFile)
        return ContentSidecarReferences(
            metadataReference = metadataReference,
            lyricReferences = NearbyLyricReferences(
                original = nestedReferences.original ?: directReferences.original
                    ?: localFiles.original?.absolutePath,
                translated = nestedReferences.translated ?: directReferences.translated
                    ?: localFiles.translated?.absolutePath,
                romanized = nestedReferences.romanized ?: directReferences.romanized
                    ?: localFiles.romanized?.absolutePath
            )
        )
    }

    private fun localMetadataReference(file: File?): String? {
        if (file == null) return null
        return File(
            file.parentFile ?: return null,
            file.name + LOCAL_METADATA_SUFFIX
        ).takeIf(File::isFile)?.absolutePath
    }

    private fun findNearbyCoverReference(
        context: Context,
        uri: Uri,
        file: File?,
        displayName: String
    ): String? {
        fun usable(reference: String?): String? {
            return reference?.takeIf { isUsableCoverReference(context, it) }
        }

        if (uri.scheme.equals("content", ignoreCase = true)) {
            val navigation = resolveLocalDocumentNavigation(context, uri)
            val parentId = navigation?.parentDocumentId
            if (navigation != null && parentId != null) {
                val baseUri = navigation.treeUri ?: navigation.baseUri
        val parentChildren = queryDocumentChildren(context, baseUri, parentId)
        val baseName = displayName.substringBeforeLast('.', displayName)
        fun specific(children: Collection<DocumentChild>): String? {
            return imageExtensions.asSequence()
                .flatMap { extension ->
                    children.asSequence().filter { child ->
                        !child.isDirectory &&
                            coverSidecarNameMatches(child.displayName, baseName, extension)
                    }
                }
                .sortedWith(compareBy({
                    if (it.displayName.equals("$baseName.${it.displayName.substringAfterLast('.')}", ignoreCase = true)) {
                        0
                    } else {
                        1
                    }
                }, DocumentChild::displayName))
                .firstOrNull()
                ?.uri
        }
                usable(specific(parentChildren))?.let { return it }
                val coversDirectory = findManagedSidecarDirectory(parentChildren, "Covers")
                val coversChildren = coversDirectory?.let { directory ->
                    queryDocumentChildren(context, baseUri, directory.documentId)
                }.orEmpty()
                usable(specific(coversChildren))?.let { return it }
                usable(coverFileNames.firstNotNullOfOrNull { coverName ->
                    imageExtensions.firstNotNullOfOrNull { extension ->
                        parentChildren.firstOrNull { child ->
                            !child.isDirectory && child.displayName.equals(
                                "$coverName.$extension",
                                ignoreCase = true
                            )
                        }?.uri
                    }
                })?.let { return it }
            }
        }
        val localFile = file.takeUnless {
            shouldUseDocumentSidecarMutation(uri)
        }
        return usable(findNearbyCover(localFile)?.toURI()?.toString())
    }

    private fun documentChildrenContainSource(
        parentChildren: Collection<DocumentChild>,
        sourceUri: Uri,
        displayName: String,
        parentDocumentId: String
    ): Boolean {
        val sourceDocumentId = try {
            DocumentsContract.getDocumentId(sourceUri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val containsSource = containsExactDocumentSource(
            documentIds = parentChildren
                .filterNot(DocumentChild::isDirectory)
                .map(DocumentChild::documentId),
            sourceDocumentId = sourceDocumentId
        )
        if (!containsSource) {
            NPLogger.w(
                TAG,
                "SAF 子项枚举未包含当前音频，拒绝创建侧载: " +
                    "source=$sourceUri, parent=$parentDocumentId, name=$displayName"
            )
        }
        return containsSource
    }

    internal fun containsExactDocumentSource(
        documentIds: Collection<String>,
        sourceDocumentId: String?
    ): Boolean {
        return !sourceDocumentId.isNullOrBlank() && sourceDocumentId in documentIds
    }

    internal fun matchesDocumentPathParent(
        path: List<String>,
        parentDocumentId: String,
        sourceDocumentId: String?,
        displayName: String,
        actualDisplayName: String?
    ): Boolean {
        if (
            actualDisplayName != null &&
            !actualDisplayName.equals(displayName, ignoreCase = true)
        ) {
            return false
        }
        return !sourceDocumentId.isNullOrBlank() &&
            path.dropLast(1).lastOrNull() == parentDocumentId &&
            path.lastOrNull() == sourceDocumentId
    }

    private fun findDocumentParentId(context: Context, documentUri: Uri): String? {
        if (isMediaStoreUri(documentUri)) return null
        return try {
            DocumentsContract.findDocumentPath(context.contentResolver, documentUri)
                ?.path
                ?.dropLast(1)
                ?.lastOrNull()
                ?.takeIf(String::isNotBlank)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDocumentLyricReferences(
        children: Collection<DocumentChild>,
        baseName: String
    ): NearbyLyricReferences {
        fun find(kind: LyricKind): String? {
            val names = lyricSidecarNames(baseName, kind, lyricExtensions)
            return names.firstNotNullOfOrNull { expectedName ->
                findDocumentSidecarChild(children, expectedName)?.uri
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
        val uri: String,
        val createdByCurrentMutation: Boolean = false
    )

    private data class DocumentChildrenQueryResult(
        val children: List<DocumentChild>,
        val isComplete: Boolean
    )

    internal fun isManagedSidecarDirectoryName(actualName: String, desiredName: String): Boolean {
        if (canonicalSafName(actualName) == canonicalSafName(desiredName)) return true
        val normalizedActual = Normalizer.normalize(actualName, Normalizer.Form.NFC)
        val normalizedDesired = Normalizer.normalize(desiredName, Normalizer.Form.NFC)
        val prefix = "$normalizedDesired ("
        if (!normalizedActual.startsWith(prefix, ignoreCase = true) ||
            !normalizedActual.endsWith(")")
        ) {
            return false
        }
        return normalizedActual.substring(prefix.length, normalizedActual.length - 1)
            .toIntOrNull() != null
    }

    private fun findManagedSidecarDirectory(
        children: Collection<DocumentChild>,
        desiredName: String
    ): DocumentChild? {
        return children
            .asSequence()
            .filter(DocumentChild::isDirectory)
            .filter { child -> isManagedSidecarDirectoryName(child.displayName, desiredName) }
            .minWithOrNull(
                compareBy(
                    { if (canonicalSafName(it.displayName) == canonicalSafName(desiredName)) 0 else 1 },
                    { it.displayName.substringAfter("(", "").removeSuffix(")").toIntOrNull() ?: Int.MAX_VALUE }
                )
            )
    }

    private fun findExactManagedSidecarDirectory(
        children: Collection<DocumentChild>,
        desiredName: String
    ): DocumentChild? {
        return children.asSequence()
            .filter(DocumentChild::isDirectory)
            .filter { child ->
                canonicalSafName(child.displayName) == canonicalSafName(desiredName)
            }
            .minByOrNull(DocumentChild::displayName)
    }

    internal fun localCoverSidecarName(
        baseName: String,
        extension: String,
        stableIdentityKey: String?
    ): String {
        val normalizedKey = stableIdentityKey?.trim()?.takeIf(String::isNotBlank)
            ?: return "$baseName.$extension"
        val suffix = ManagedDownloadStorageNaming.coverStableKeySuffix(normalizedKey)
        return "$baseName-$suffix.$extension"
    }

    private fun localCoverSidecarNames(
        baseName: String,
        extension: String,
        stableIdentityKey: String?
    ): List<String> {
        return listOfNotNull(
            localCoverSidecarName(baseName, extension, stableIdentityKey),
            "$baseName.$extension".takeUnless {
                stableIdentityKey.isNullOrBlank()
            }
        ).distinct()
    }

    private fun managedCoverSidecarNames(
        baseName: String,
        extensions: Collection<String>,
        stableIdentityKey: String?
    ): Set<String> {
        val normalizedKey = stableIdentityKey?.trim()?.takeIf(String::isNotBlank)
            ?: return emptySet()
        return extensions.mapTo(linkedSetOf()) { extension ->
            localCoverSidecarName(
                baseName = baseName,
                extension = extension,
                stableIdentityKey = normalizedKey
            )
        }
    }

    internal fun sidecarNameMatches(actualName: String, canonicalName: String): Boolean {
        if (
            canonicalSafName(actualName) == canonicalSafName(canonicalName) ||
            numberedSidecarNameMatches(actualName, canonicalName)
        ) {
            return true
        }
        // 部分 DocumentsProvider 会为非 txt 文本 MIME 自动补 .txt
        if (
            !canonicalName.endsWith(".txt", ignoreCase = true) &&
            actualName.endsWith(".txt", ignoreCase = true)
        ) {
            val providerNameWithoutTextExtension = actualName.dropLast(".txt".length)
            return canonicalSafName(providerNameWithoutTextExtension) == canonicalSafName(canonicalName) ||
                numberedSidecarNameMatches(providerNameWithoutTextExtension, canonicalName)
        }
        return false
    }

    private fun coverSidecarNameMatches(
        actualName: String,
        baseName: String,
        extension: String
    ): Boolean {
        val plainName = "$baseName.$extension"
        if (sidecarNameMatches(actualName, plainName)) return true
        val canonical = removeProviderNumberedSidecarSuffix(actualName)
        val suffix = ".$extension"
        if (!canonical.endsWith(suffix, ignoreCase = true)) return false
        val stem = canonical.substring(0, canonical.length - suffix.length)
        val prefix = "$baseName-"
        val hash = stem.removePrefix(prefix)
        return stem.startsWith(prefix) &&
            hash.isNotEmpty() &&
            (hash.length <= 8 || hash.length == 32) &&
            hash.all { it in "0123456789abcdefABCDEF" }
    }

    private fun numberedSidecarNameMatches(actualName: String, canonicalName: String): Boolean {
        return numberedSidecarNameOrdinalOrNull(actualName, canonicalName) != null
    }

    private fun numberedSidecarNameOrdinal(actualName: String, canonicalName: String): Int {
        return numberedSidecarNameOrdinalOrNull(actualName, canonicalName) ?: Int.MAX_VALUE
    }

    private fun numberedSidecarNameOrdinalOrNull(actualName: String, canonicalName: String): Int? {
        val normalizedActualName = Normalizer.normalize(actualName, Normalizer.Form.NFC)
        val normalizedCanonicalName = Normalizer.normalize(canonicalName, Normalizer.Form.NFC)
        parseNumberedSidecarNameOrdinal(
            actualName = normalizedActualName,
            prefix = "$normalizedCanonicalName (",
            suffix = ""
        )?.let { return it }
        val extensionIndex = normalizedCanonicalName.lastIndexOf('.')
        if (extensionIndex <= 0 || extensionIndex == normalizedCanonicalName.lastIndex) return null
        return parseNumberedSidecarNameOrdinal(
            actualName = normalizedActualName,
            prefix = normalizedCanonicalName.substring(0, extensionIndex) + " (",
            suffix = normalizedCanonicalName.substring(extensionIndex)
        )
    }

    private fun parseNumberedSidecarNameOrdinal(
        actualName: String,
        prefix: String,
        suffix: String
    ): Int? {
        if (
            !actualName.startsWith(prefix, ignoreCase = true) ||
                !actualName.endsWith(suffix, ignoreCase = true)
        ) {
            return null
        }
        val numberEnd = actualName.length - suffix.length
        if (numberEnd <= prefix.length || actualName[numberEnd - 1] != ')') return null
        return actualName.substring(prefix.length, numberEnd - 1).toIntOrNull()
    }

    private fun removeProviderNumberedSidecarSuffix(actualName: String): String {
        val extensionIndex = actualName.lastIndexOf('.')
        if (extensionIndex > 0 && extensionIndex < actualName.lastIndex) {
            val stem = actualName.substring(0, extensionIndex)
            val markerIndex = stem.lastIndexOf(" (")
            if (
                markerIndex >= 0 &&
                    stem.endsWith(")") &&
                    stem.substring(markerIndex + 2, stem.length - 1).toIntOrNull() != null
            ) {
                return actualName.substring(0, markerIndex) + actualName.substring(extensionIndex)
            }
        }
        val markerIndex = actualName.lastIndexOf(" (")
        return if (
            markerIndex >= 0 &&
                actualName.endsWith(")") &&
                actualName.substring(markerIndex + 2, actualName.length - 1).toIntOrNull() != null
        ) {
            actualName.substring(0, markerIndex)
        } else {
            actualName
        }
    }

    private fun <T> withDocumentMutationLock(
        baseUri: Uri,
        parentDocumentId: String,
        block: () -> T
    ): T {
        return ManagedDownloadTreeMutationLocks.withLock(baseUri, parentDocumentId, block)
    }

    private fun invalidateDocumentChildrenCache(baseUri: Uri, parentDocumentId: String) {
        val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
        synchronized(documentChildrenCache) {
            documentChildrenCache.remove(cacheKey)
        }
        consecutiveEmptyDocumentRefreshes.remove(cacheKey)
    }

    private fun documentParentCacheKey(baseUri: Uri, parentDocumentId: String): String {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(baseUri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }?.takeIf(String::isNotBlank)
        val scope = treeDocumentId ?: baseUri.toString()
        return "generation=${LocalStorageRootGeneration.current()}|" +
            "${baseUri.authority.orEmpty()}|$scope|$parentDocumentId"
    }

    private fun cachedDocumentChildren(
        baseUri: Uri,
        parentDocumentId: String
    ): List<DocumentChild> {
        val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
        return synchronized(documentChildrenCache) {
            documentChildrenCache[cacheKey]
                ?.takeIf { entry ->
                    System.currentTimeMillis() - entry.cachedAtMs <= DOCUMENT_CHILDREN_CACHE_TTL_MS
                }
                ?.children
                .orEmpty()
        }
    }

    private fun rememberDocumentChild(
        baseUri: Uri,
        parentDocumentId: String,
        child: DocumentChild
    ) {
        val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
        synchronized(documentChildrenCache) {
            val childrenByUri = LinkedHashMap<String, DocumentChild>()
            documentChildrenCache[cacheKey]
                ?.children
                .orEmpty()
                .forEach { cached -> childrenByUri[cached.uri] = cached }
            childrenByUri[child.uri] = child
            documentChildrenCache[cacheKey] = DocumentChildrenCacheEntry(
                children = childrenByUri.values.toList(),
                cachedAtMs = System.currentTimeMillis(),
                isComplete = documentChildrenCache[cacheKey]?.isComplete ?: false
            )
        }
    }

    private fun queryDocumentChildrenForMutation(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String?
    ): List<DocumentChild>? {
        val resolvedParentId = parentDocumentId?.takeIf(String::isNotBlank) ?: return null
        repeat(SAF_CHILDREN_QUERY_RETRY_COUNT) { attempt ->
            val result = queryDocumentChildrenUncached(
                context = context,
                baseUri = baseUri,
                parentDocumentId = resolvedParentId
            ) ?: return@repeat
            val stabilized = stabilizeDocumentChildrenRefresh(
                baseUri = baseUri,
                parentDocumentId = resolvedParentId,
                result = result
            )
            cacheDocumentChildren(
                baseUri = baseUri,
                parentDocumentId = resolvedParentId,
                children = stabilized.children,
                isComplete = stabilized.isComplete
            )
            if (stabilized.isComplete) {
                return stabilized.children
            }
            if (attempt + 1 < SAF_CHILDREN_QUERY_RETRY_COUNT) {
                SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[attempt + 1])
            }
        }
        return null
    }

    private fun ensureDocumentSidecarDirectoryForMutation(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String,
        directoryName: String,
        existingChildren: List<DocumentChild>? = null
    ): DocumentChild? {
        val children = existingChildren ?: queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId
        ) ?: return null
        val knownChildren = (children + cachedDocumentChildren(baseUri, parentDocumentId))
            .distinctBy(DocumentChild::uri)
        findExactManagedSidecarDirectory(knownChildren, directoryName)?.let { return it }
        val refreshedChildren = queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId
        ) ?: return null
        val refreshedKnownChildren = (
            refreshedChildren + cachedDocumentChildren(baseUri, parentDocumentId)
            ).distinctBy(DocumentChild::uri)
        findExactManagedSidecarDirectory(refreshedKnownChildren, directoryName)?.let { return it }
        findCanonicalExternalStorageChildForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId,
            displayName = directoryName,
            isDirectory = true
        )?.let { canonical ->
            rememberDocumentChild(baseUri, parentDocumentId, canonical)
            return canonical
        }
        val parentUri = buildDocumentReferenceUri(baseUri, parentDocumentId)
        NPLogger.d(
            TAG,
            "create local SAF sidecar directory: name=$directoryName, parent=$parentDocumentId, " +
                "known=${refreshedKnownChildren.joinToString { child -> child.displayName }}"
        )
        val createdUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                directoryName
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "create sidecar directory failed for $parentUri: ${error.message}")
            null
        } ?: return null
        // DocumentsProvider 可能在 createDocument 返回后延迟刷新 children 查询。
        // 返回 URI 已经是 provider 确认的目标，优先使用它避免误判为创建失败。
        val resolved = documentChildFromCreatedUri(
            context = context,
            uri = createdUri,
            isDirectory = true
        )
            ?: queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentDocumentId
            ).orEmpty().let { children ->
                findExactManagedSidecarDirectory(children, directoryName)
            }
            ?: return null
        rememberDocumentChild(baseUri, parentDocumentId, resolved)
        return resolved
    }

    private fun createDocumentSidecarForMutation(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
        existingChildren: List<DocumentChild>? = null
    ): DocumentChild? {
        val currentChildren = existingChildren ?: queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId
        ) ?: return null
        val knownChildren = (currentChildren + cachedDocumentChildren(baseUri, parentDocumentId))
            .distinctBy(DocumentChild::uri)
        findExactDocumentSidecarChild(knownChildren, displayName)?.let { return it }
        val refreshedChildren = queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId
        ) ?: return null
        val refreshedKnownChildren = (
            refreshedChildren + cachedDocumentChildren(baseUri, parentDocumentId)
            ).distinctBy(DocumentChild::uri)
        findExactDocumentSidecarChild(refreshedKnownChildren, displayName)?.let { return it }
        findCanonicalExternalStorageChildForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId,
            displayName = displayName,
            isDirectory = false
        )?.let { canonical ->
            rememberDocumentChild(baseUri, parentDocumentId, canonical)
            return canonical
        }
        val parentUri = buildDocumentReferenceUri(baseUri, parentDocumentId)
        NPLogger.d(
            TAG,
            "create local SAF sidecar file: name=$displayName, parent=$parentDocumentId, " +
                "known=${refreshedKnownChildren.joinToString { child -> child.displayName }}"
        )
        val createdUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                ManagedDownloadStorage.documentCreateMimeType(
                    desiredName = displayName,
                    mimeType = mimeType
                ),
                displayName
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "create sidecar document failed for $parentUri: ${error.message}")
            null
        } ?: return null
        // 同上，先消费 createDocument 的返回值，再把刷新查询作为兼容回退。
        val resolved = documentChildFromCreatedUri(
            context = context,
            uri = createdUri,
            isDirectory = false
        )
            ?.copy(createdByCurrentMutation = true)
            ?: queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentDocumentId
            ).orEmpty().let { children ->
                findExactDocumentSidecarChild(children, displayName)
            }
            ?: return null
        rememberDocumentChild(baseUri, parentDocumentId, resolved)
        return resolved
    }

    private fun documentChildFromUri(
        context: Context,
        uri: Uri,
        isDirectory: Boolean
    ): DocumentChild? {
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }?.takeIf(String::isNotBlank) ?: return null
        val document = try {
            DocumentFile.fromSingleUri(context, uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val actualName = try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: document?.name ?: return null
        return DocumentChild(
            documentId = documentId,
            displayName = actualName,
            isDirectory = document?.isDirectory ?: isDirectory,
            uri = uri.toString()
        )
    }

    private fun documentChildFromCreatedUri(
        context: Context,
        uri: Uri,
        isDirectory: Boolean
    ): DocumentChild? {
        // createDocument 返回的 URI 可能对应 provider 改写后的名字, 立即查询实际条目
        return documentChildFromUri(
            context = context,
            uri = uri,
            isDirectory = isDirectory
        )
    }

    private fun findCanonicalExternalStorageChildForMutation(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String,
        displayName: String,
        isDirectory: Boolean
    ): DocumentChild? {
        // documentId 是 Provider 的 opaque 身份, 不能从父 ID 和文件名反推
        return null
    }

    private fun findExactDocumentSidecarChild(
        children: Collection<DocumentChild>,
        canonicalName: String
    ): DocumentChild? {
        return children.asSequence()
            .filterNot(DocumentChild::isDirectory)
            .firstOrNull { child ->
                canonicalSafName(child.displayName) == canonicalSafName(canonicalName)
            }
    }

    private fun findDocumentSidecarChild(
        children: Collection<DocumentChild>,
        canonicalName: String
    ): DocumentChild? {
        return children.asSequence()
            .filterNot(DocumentChild::isDirectory)
            .filter { child -> sidecarNameMatches(child.displayName, canonicalName) }
            .minWithOrNull(
                compareBy(
                    { if (canonicalSafName(it.displayName) == canonicalSafName(canonicalName)) 0 else 1 },
                    { numberedSidecarNameOrdinal(it.displayName, canonicalName) },
                    DocumentChild::displayName
                )
            )
    }

    private fun canonicalSafName(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    private fun queryDocumentChildren(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String?
    ): List<DocumentChild> {
        val resolvedParentId = parentDocumentId?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            run read@{
                val cacheKey = documentParentCacheKey(baseUri, resolvedParentId)
                synchronized(documentChildrenCache) {
                    documentChildrenCache[cacheKey]?.let { cached ->
                        if (System.currentTimeMillis() - cached.cachedAtMs <= DOCUMENT_CHILDREN_CACHE_TTL_MS) {
                            return@read cached.children
                        }
                        documentChildrenCache.remove(cacheKey)
                    }
                }
                val children = queryDocumentChildrenUncached(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = resolvedParentId
                ) ?: return@read emptyList()
                val stabilized = stabilizeDocumentChildrenRefresh(
                    baseUri = baseUri,
                    parentDocumentId = resolvedParentId,
                    result = children
                )
                cacheDocumentChildren(
                    baseUri = baseUri,
                    parentDocumentId = resolvedParentId,
                    children = stabilized.children,
                    isComplete = stabilized.isComplete
                )
                stabilized.children
            }
        } catch (error: SecurityException) {
            invalidateSafReadCaches()
            val failure = classifySafReadFailure(
                baseUri = baseUri,
                attemptedDocumentId = resolvedParentId,
                error = error
            )
            NPLogger.w(
                TAG,
                "SAF 只读目录查询失败: kind=${failure::class.simpleName}, " +
                    "base=$baseUri, parent=$resolvedParentId, message=${error.message}"
            )
            emptyList()
        }
    }

    private fun classifySafReadFailure(
        baseUri: Uri,
        attemptedDocumentId: String,
        error: SecurityException
    ): SafAccessResult<Nothing> {
        val treeUri = if (DocumentsContract.isTreeUri(baseUri)) {
            baseUri
        } else {
            runCatching {
                DocumentsContract.buildTreeDocumentUri(
                    baseUri.authority ?: return@runCatching baseUri,
                    attemptedDocumentId
                )
            }.getOrDefault(baseUri)
        }
        val message = error.message.orEmpty()
        return if (message.contains("not a descendant", ignoreCase = true)) {
            SafAccessResult.OutOfScope(treeUri, attemptedDocumentId, error)
        } else {
            SafAccessResult.PermissionLost(treeUri, error)
        }
    }

    private fun queryDocumentChildrenUncached(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String?
    ): DocumentChildrenQueryResult? {
        val queriedChildren = queryDocumentChildrenDirect(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId
        )
        if (queriedChildren != null) return queriedChildren
        val resolvedParentId = parentDocumentId?.takeIf(String::isNotBlank) ?: return null
        return DocumentChildrenQueryResult(
            children = listDocumentChildrenWithDocumentFile(
                context = context,
                baseUri = baseUri,
                parentDocumentId = resolvedParentId
            ),
            isComplete = false
        )
    }

    private fun queryDocumentChildrenDirect(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String?
    ): DocumentChildrenQueryResult? {
        val resolvedParentId = parentDocumentId?.takeIf(String::isNotBlank) ?: return null
        val childrenUri = try {
            if (DocumentsContract.isTreeUri(baseUri)) {
                DocumentsContract.buildChildDocumentsUriUsingTree(baseUri, resolvedParentId)
            } else {
                DocumentsContract.buildChildDocumentsUri(
                    baseUri.authority ?: return null,
                    resolvedParentId
                )
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "build document children uri failed for $baseUri: ${error.message}")
            null
        } ?: return null
        val queriedChildren = try {
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
                    return@use null
                }
                val children = buildList {
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idIndex)
                        if (childId.isNullOrBlank()) continue
                        val childName = cursor.getString(nameIndex)
                        if (childName.isNullOrBlank()) continue
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
                val extras = cursor.extras
                val loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
                val providerError = extras?.getString(DocumentsContract.EXTRA_ERROR)
                DocumentChildrenQueryResult(
                    children = children,
                    isComplete = !loading && providerError.isNullOrBlank()
                )
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "query document children failed for $baseUri: ${error.message}")
            null
        }
        return queriedChildren
    }

    private fun stabilizeDocumentChildrenRefresh(
        baseUri: Uri,
        parentDocumentId: String,
        result: DocumentChildrenQueryResult
    ): DocumentChildrenQueryResult {
        val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
        val previous = synchronized(documentChildrenCache) {
            documentChildrenCache[cacheKey]?.children.orEmpty()
        }
        if (!result.isComplete) {
            consecutiveEmptyDocumentRefreshes.remove(cacheKey)
            return DocumentChildrenQueryResult(
                children = mergeDocumentChildren(previous, result.children),
                isComplete = false
            )
        }
        if (result.children.isNotEmpty() || previous.isEmpty()) {
            consecutiveEmptyDocumentRefreshes.remove(cacheKey)
            return result
        }
        val count = consecutiveEmptyDocumentRefreshes.merge(cacheKey, 1) { current, _ -> current + 1 }
            ?: 1
        if (count < EMPTY_DOCUMENT_REFRESH_CONFIRMATION_COUNT) {
            return DocumentChildrenQueryResult(
                children = previous,
                isComplete = false
            )
        }
        consecutiveEmptyDocumentRefreshes.remove(cacheKey)
        return result
    }

    private fun mergeDocumentChildren(
        previous: Collection<DocumentChild>,
        refreshed: Collection<DocumentChild>
    ): List<DocumentChild> {
        val childrenByUri = LinkedHashMap<String, DocumentChild>()
        previous.forEach { child -> childrenByUri[child.uri] = child }
        refreshed.forEach { child -> childrenByUri[child.uri] = child }
        return childrenByUri.values.toList()
    }

    private fun cacheDocumentChildren(
        baseUri: Uri,
        parentDocumentId: String?,
        children: List<DocumentChild>,
        isComplete: Boolean = true
    ) {
        val resolvedParentId = parentDocumentId?.takeIf(String::isNotBlank) ?: return
        val cacheKey = documentParentCacheKey(baseUri, resolvedParentId)
        synchronized(documentChildrenCache) {
            val oldEntry = documentChildrenCache[cacheKey]
            val mergedChildren = if (isComplete) {
                children
            } else {
                mergeDocumentChildren(oldEntry?.children.orEmpty(), children)
            }
            documentChildrenCache[cacheKey] = DocumentChildrenCacheEntry(
                children = mergedChildren,
                cachedAtMs = System.currentTimeMillis(),
                isComplete = isComplete
            )
        }
    }

    private fun listDocumentChildrenWithDocumentFile(
        context: Context,
        baseUri: Uri,
        parentDocumentId: String
    ): List<DocumentChild> {
        val parentUri = try {
            buildDocumentReferenceUri(baseUri, parentDocumentId)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val parent = try {
            // buildDocumentReferenceUri returns a document URI for a child id
            // fromTreeUri on that URI performs a wrong parent-path probe on some providers
            if (DocumentsContract.isTreeUri(parentUri)) {
                DocumentFile.fromTreeUri(context, parentUri)
            } else {
                DocumentFile.fromSingleUri(context, parentUri)
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return try {
            parent.listFiles().mapNotNull { child ->
                val name = child.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val documentId = try {
                    DocumentsContract.getDocumentId(child.uri)
                } catch (error: SecurityException) {
                    throw error
                } catch (_: Exception) {
                    null
                }?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                DocumentChild(
                    documentId = documentId,
                    displayName = name,
                    isDirectory = child.isDirectory,
                    uri = child.uri.toString()
                )
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
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
        return sidecarContent ?: embeddedContent?.takeIf(String::isNotBlank)
    }

    private fun resolveLocalLyricContentByPriority(
        sidecarContent: String?,
        embeddedContent: String?,
        metadataFallback: String?
    ): String? {
        return sidecarContent ?: embeddedContent?.takeIf(String::isNotBlank)
            ?: metadataFallback
    }

    internal fun resolveEffectiveLocalLyricPath(
        reference: String?,
        content: String?
    ): String? {
        return reference?.takeIf { content != null }
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
        findCoverSidecarInDirectory(parent, baseName)?.let { return it }

        val coverDir = findCoversDirectory(parent)
        if (coverDir != null) {
            findCoverSidecarInDirectory(coverDir, baseName)?.let { return it }
        }

        findDirectoryCover(parent)?.let { return it }

        return null
    }

    private fun findCoverSidecarInDirectory(directory: File, baseName: String): File? {
        val children = directory.listFiles()?.filter(File::isFile).orEmpty()
        imageExtensions.forEach { extension ->
            children.firstOrNull { child ->
                child.name.equals("$baseName.$extension", ignoreCase = true)
            }?.let { return it }
        }
        return children
            .filter { child ->
                imageExtensions.any { extension ->
                    coverSidecarNameMatches(child.name, baseName, extension)
                }
            }.minByOrNull { child -> child.name }
    }

    private fun findCoversDirectory(parent: File): File? {
        val canonical = File(parent, "Covers")
        if (canonical.isDirectory) return canonical
        return parent.listFiles()
            ?.firstOrNull { child ->
                child.isDirectory && child.name.equals("Covers", ignoreCase = true)
            }
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
                ?.takeIf {
                    it.isNotBlank() &&
                        it.takeMeaningfulLocalMetadata() != null &&
                        isReadableLocalTitleCandidate(it, sourceUri, fallbackTitle)
                }
        }
    }

    private fun String?.takeMeaningfulLocalMetadata(): String? {
        val value = this?.trim().orEmpty()
        if (value.isBlank()) return null
        return value.takeUnless {
            it.lowercase(Locale.ROOT) in LOCAL_METADATA_PLACEHOLDERS
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
