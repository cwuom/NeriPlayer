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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.MAX_SOURCE_COVER_BYTES
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
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
import java.io.FileInputStream
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

internal const val LOCAL_MEDIA_SHARE_TAG = "LocalMediaSupport"
internal const val MEDIA_STORE_AUTHORITY = "media"
internal const val MAX_CONTAINER_METADATA_BYTES = 4L * 1024L * 1024L
internal const val MAX_LOCAL_LYRIC_BYTES = 512L * 1024L
internal const val NUL_CHAR = '\u0000'
internal const val BOM_CHAR = '\uFEFF'
internal const val REPLACEMENT_CHAR = '\uFFFD'
internal const val SHARED_LOCAL_MEDIA_DIR = "shared_media_exports"
internal const val LOCAL_COVER_LOOKUP_CACHE_LIMIT = 768
internal const val NEARBY_COVER_LOOKUP_CACHE_LIMIT = 2048
internal const val DIRECTORY_COVER_LOOKUP_CACHE_LIMIT = 256
internal const val DIRECTORY_FILE_INDEX_CACHE_LIMIT = 256
internal const val DIRECTORY_FILE_INDEX_CACHE_TTL_MS = 1_000L
internal const val DIRECTORY_FILE_INDEX_MAX_CHILDREN = 8_192
internal const val LOCAL_LYRICS_LOOKUP_CACHE_LIMIT = 768
internal const val LOCAL_LYRICS_CACHE_TTL_MS = 750L
internal const val DOCUMENT_CHILDREN_CACHE_LIMIT = 256
internal const val DOCUMENT_CHILDREN_CACHE_TTL_MS = 750L
internal const val DOCUMENT_CHILDREN_INCOMPLETE_CACHE_TTL_MS = 5_000L
internal const val DOCUMENT_CHILDREN_CACHE_MAX_CHILDREN = 8_192
internal const val DOCUMENT_CHILDREN_CACHE_MAX_TOTAL_CHILDREN = 65_536
internal const val EMPTY_DOCUMENT_REFRESH_CONFIRMATION_COUNT = 2
internal const val SAF_CHILDREN_QUERY_RETRY_COUNT = 3
internal const val SAF_WRITE_READBACK_RETRY_COUNT = 3
internal const val DOCUMENT_NAVIGATION_CACHE_LIMIT = 512
internal const val LOCAL_LYRICS_PERF_LOG_LIMIT = 96
internal const val MAX_MEDIASTORE_DURATION_QUERY_IDS = 400
internal const val MAX_EDITABLE_COVER_BYTES = MAX_SOURCE_COVER_BYTES
internal const val MAX_EMBEDDED_COVER_CACHE_BYTES = 1024 * 1024
internal const val MAX_EMBEDDED_COVER_CACHE_DIMENSION_PX = 512
internal const val FRONT_COVER_PICTURE_TYPE = "Front Cover"
internal val ROLELESS_COVER_PICTURE_EXTENSIONS = setOf(
    "3g2", "m4a", "m4b", "m4p", "m4r", "m4v", "mp4"
)
internal val MP4_SUPPORTED_COVER_MIME_TYPES = setOf(
    "image/jpeg", "image/png"
)
internal val EDITABLE_COVER_JPEG_QUALITIES = intArrayOf(95, 90, 85, 80, 75, 70, 65, 60)
internal const val EDITABLE_METADATA_WRITE_BUDGET_MS = 3_000L
internal val SAF_WRITE_READBACK_DELAYS_MS = longArrayOf(0L, 8L, 24L)
internal const val LOCAL_METADATA_SUFFIX = ".npmeta.json"
internal const val LEGACY_DOWNLOAD_ROOT = "/storage/emulated/0/neriplayer-download"

internal fun isDocumentChildrenCacheSizeAllowed(childCount: Int): Boolean {
    return childCount in 0..DOCUMENT_CHILDREN_CACHE_MAX_CHILDREN
}

internal fun isDocumentChildrenCacheTotalWithinBudget(totalChildren: Int): Boolean {
    return totalChildren in 0..DOCUMENT_CHILDREN_CACHE_MAX_TOTAL_CHILDREN
}

internal fun logEditableMetadataFailure(
    stage: String,
    sourceUri: Uri,
    error: Throwable,
    metrics: String? = null
) {
    val causes = buildList {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            add(
                "${current.javaClass.simpleName}:" +
                    (current.message?.take(240) ?: "<no-message>")
            )
            current = current.cause
            depth++
        }
    }.joinToString(" <- ")
    val metricSuffix = metrics?.let { ", $it" }.orEmpty()
    NPLogger.w(
        LOCAL_MEDIA_SHARE_TAG,
        "本地音频元数据回写失败: stage=$stage, uri=$sourceUri, " +
            "causes=$causes$metricSuffix",
        error
    )
}

internal fun redactCoverReference(reference: String): String {
    val uri = runCatching { reference.toUri() }.getOrNull()
    val scheme = uri?.scheme?.lowercase(Locale.ROOT) ?: "path"
    val authorityHash = uri?.authority
        ?.let { Integer.toHexString(it.hashCode()) }
        ?: "-"
    val referenceHash = Integer.toHexString(reference.hashCode())
    return "$scheme/$authorityHash#$referenceHash"
}

internal fun redactCoverError(error: Throwable): String {
    val message = error.message
        ?.replace(Regex("(?:content|file|https?)://\\S+"), "<uri>")
        ?.replace(Regex("/(?:storage|sdcard|data)/\\S+"), "<path>")
        ?.take(160)
        ?: "<no-message>"
    return "${error.javaClass.simpleName}:$message"
}

internal fun logEditableCoverReadFailure(
    stage: String,
    reference: String,
    error: Throwable
) {
    NPLogger.w(
        LOCAL_MEDIA_SHARE_TAG,
        "本地封面读取失败: stage=$stage, ref=${redactCoverReference(reference)}, " +
            "error=${redactCoverError(error)}"
    )
}

internal val LOCAL_METADATA_PLACEHOLDERS = setOf(
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

internal data class DirectLocalLyricsInspection(
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
    val sourceModifiedAtMs: Long? = null,
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

internal data class LocalDocumentNavigation(
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
    SIDECAR_ONLY,
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
        return LocalMediaMetadataWriteOutcome.SIDECAR_ONLY
    }
    return directOutcome
}

fun SongItem.isLocalSong(): Boolean = LocalSongSupport.isLocalSong(this)

internal fun Uri.isSupportedLocalMediaUri(): Boolean {
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

internal fun isExternalStorageDocumentUri(uri: Uri): Boolean {
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
 * 检查本地封面引用是否仍然指向可读取的图片数据
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

internal fun validateContentCoverReference(
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

internal fun validateContentCoverDescriptor(
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

internal fun isUsableCoverFile(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    return runCatching {
        file.inputStream().use(::hasDecodableImage)
    }.getOrDefault(false)
}

internal fun hasDecodableImage(input: InputStream): Boolean {
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

internal fun SongItem.localMediaUriCandidates(): List<Uri> {
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

internal fun String?.isContentLocalMediaReference(): Boolean {
    if (this.isNullOrBlank()) {
        return false
    }
    return startsWith("content://", ignoreCase = true)
}

internal fun SongItem.resolveShareableLocalUri(context: Context): Uri? {
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

internal fun buildShareableFileUri(context: Context, sourceFile: File): Uri? {
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
