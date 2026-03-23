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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.content.FileProvider
import com.kyant.taglib.TagLib
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.max
import androidx.core.net.toUri

private const val LOCAL_MEDIA_SHARE_TAG = "LocalMediaSupport"
private const val MAX_CONTAINER_METADATA_BYTES = 4L * 1024L * 1024L
private const val NUL_CHAR = '\u0000'
private const val BOM_CHAR = '\uFEFF'
private const val REPLACEMENT_CHAR = '\uFFFD'

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
    val embeddedCover: Boolean
)

fun SongItem.isLocalSong(): Boolean = LocalSongSupport.isLocalSong(this)

private fun Uri.isSupportedLocalMediaUri(): Boolean {
    return when {
        scheme.equals("file", ignoreCase = true) -> true
        scheme.equals("content", ignoreCase = true) -> true
        scheme.isNullOrBlank() && path?.startsWith("/") == true -> true
        else -> false
    }
}

fun SongItem.localMediaUri(): Uri? {
    val source = localFilePath
        ?.takeIf { it.isNotBlank() }
        ?: mediaUri?.takeIf { it.isNotBlank() }
        ?: return null
    val localUri = if (source.startsWith("/")) {
        Uri.fromFile(File(source))
    } else {
        source.toUri()
    }
    return localUri.takeIf { it.isSupportedLocalMediaUri() }
}

fun SongItem.toShareableLocalUri(context: Context): Uri? {
    val localUri = localMediaUri() ?: return null
    val resolvedFile = runCatching {
        LocalMediaSupport.resolveLocalFile(context, localUri)
    }.getOrNull()
    if (resolvedFile != null) {
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", resolvedFile)
        }.getOrElse {
            NPLogger.w(
                LOCAL_MEDIA_SHARE_TAG,
                "FileProvider fallback to content uri for ${resolvedFile.absolutePath}: ${it.message}"
            )
            if (localUri.scheme.equals("content", ignoreCase = true)) localUri else null
        }
    }

    if (localUri.scheme.equals("content", ignoreCase = true)) {
        return localUri
    }

    val path = when {
        localUri.scheme.equals("file", ignoreCase = true) -> localUri.path
        localUri.scheme.isNullOrBlank() -> mediaUri
        else -> null
    } ?: return null

    val file = File(path)
    if (!file.exists()) return null
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

object LocalMediaSupport {
    private const val TAG = "LocalMediaSupport"
    private val lyricExtensions = listOf("lrc", "txt")
    private val coverFileNames = listOf("cover", "folder", "front")
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")

    private data class AudioTrackTechInfo(
        val audioMimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channelCount: Int?
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
        val coverBytes: ByteArray? = null
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
            result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    fun inspect(context: Context, song: SongItem): LocalMediaDetails? {
        val uri = song.localMediaUri()?.takeIf { it.isSupportedLocalMediaUri() } ?: return null
        return inspect(context, uri)
    }

    fun resolveLocalFile(context: Context, uri: Uri): File? {
        if (!uri.isSupportedLocalMediaUri()) return null
        val resolvedPath = directFilePath(uri)
            ?: queryContentInfo(context, uri).filePath
            ?: resolvePathFromDescriptor(context, uri)
        return resolvedPath?.let(::File)?.takeIf(File::exists)
    }

    fun inspect(context: Context, uri: Uri): LocalMediaDetails {
        require(uri.isSupportedLocalMediaUri()) { "Unsupported local media uri: $uri" }
        val queried = queryContentInfo(context, uri)
        val resolvedPath = directFilePath(uri) ?: queried.filePath ?: resolvePathFromDescriptor(context, uri)
        val file = resolvedPath?.let(::File)?.takeIf(File::exists)
        val playableUri = file?.let(Uri::fromFile) ?: uri
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
        val containerMetadata = file?.let(::parseContainerMetadata)
        val tagLibMetadata = inspectTagLibMetadata(
            context = context,
            uri = playableUri,
            file = file
        )
        val nearbyCover = findNearbyCover(file)
        val nearbyLyrics = findNearbyLyrics(file)
        val lyricContent = nearbyLyrics?.let {
            readTextFile(it)
                ?: run {
                    NPLogger.w(TAG, "read lyric failed for ${it.absolutePath}")
                    null
                }
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
                lyricContent = lyricContent,
                lyricPath = nearbyLyrics?.absolutePath,
                lyricSource = nearbyLyrics?.let { context.getString(R.string.local_song_lyric_external) },
                originalTitle = title,
                originalArtist = tagLibMetadata?.artist ?: containerMetadata?.artist ?: queried.artist ?: artist,
                embeddedCover = embeddedCover || tagLibCoverUri != null
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
                lyricContent = lyricContent,
                lyricPath = nearbyLyrics?.absolutePath,
                lyricSource = nearbyLyrics?.let { context.getString(R.string.local_song_lyric_external) },
                originalTitle = title,
                originalArtist = tagLibMetadata?.artist
                    ?: containerMetadata?.artist?.takeIf { it.isNotBlank() }
                    ?: queried.artist?.takeIf { it.isNotBlank() }
                    ?: artist,
                embeddedCover = tagLibCoverUri != null
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun toSongItem(details: LocalMediaDetails): SongItem {
        val source = details.filePath?.takeIf { it.isNotBlank() } ?: details.sourceUri.toString()
        val stableId = computeStableSongId(source)
        return SongItem(
            id = stableId,
            name = details.title,
            artist = details.artist,
            album = normalizeLocalAlbumIdentity(details.album, details.usesFallbackAlbum),
            albumId = 0L,
            durationMs = details.durationMs,
            coverUrl = details.coverUri,
            mediaUri = source,
            matchedLyric = details.lyricContent,
            originalName = details.originalTitle ?: details.title,
            originalArtist = details.originalArtist ?: details.artist,
            originalCoverUrl = details.coverUri,
            localFileName = details.displayName,
            localFilePath = details.filePath
        )
    }

    fun shareSongFile(context: Context, song: SongItem): Boolean {
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
        context.startActivity(Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun downloadDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(baseDir, "NeriPlayer")
    }

    fun readTextFile(file: File): String? {
        val bytes = runCatching { file.readBytes() }
            .onFailure { NPLogger.w(TAG, "read bytes failed for ${file.absolutePath}: ${it.message}") }
            .getOrNull()
            ?: return null

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
        val projection = buildList {
            add(OpenableColumns.DISPLAY_NAME)
            add(OpenableColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
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
                        relativePath = cursor.getOptionalString(MediaStore.MediaColumns.RELATIVE_PATH),
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

    private fun inspectTagLibMetadata(
        context: Context,
        uri: Uri,
        file: File?
    ): TagLibMetadata? {
        return openTagLibDescriptor(context, uri, file)?.use { descriptor ->
            val metadata = runCatching {
                TagLib.getMetadata(descriptor.dup().detachFd(), true)
            }.getOrElse {
                NPLogger.w(TAG, "TagLib metadata failed for $uri: ${it.message}")
                null
            }
            val audioProperties = runCatching {
                TagLib.getAudioProperties(descriptor.dup().detachFd())
            }.getOrElse {
                NPLogger.w(TAG, "TagLib audio properties failed for $uri: ${it.message}")
                null
            }

            if (metadata == null && audioProperties == null) {
                return@use null
            }

            val propertyMap = metadata?.propertyMap
            val coverBytes = metadata?.pictures
                ?.firstOrNull { it.pictureType.equals("Front Cover", ignoreCase = true) }
                ?.data
                ?: metadata?.pictures?.firstOrNull()?.data

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
                coverBytes = coverBytes?.takeIf { it.isNotEmpty() }
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

    private fun parseContainerMetadata(file: File): ContainerMetadata? {
        if (!file.exists() || !file.isFile) return null
        return when (file.extension.lowercase()) {
            "wav", "wave" -> parseWaveMetadata(file)
            else -> null
        }
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

    private fun saveEmbeddedCover(context: Context, uriKey: String, embeddedPicture: ByteArray?): String? {
        if (embeddedPicture == null || embeddedPicture.isEmpty()) return null
        val coverDir = File(context.filesDir, "local_audio_covers").apply { mkdirs() }
        val file = File(coverDir, "${stableKey(uriKey)}.jpg")
        file.writeBytes(embeddedPicture)
        return file.toURI().toString()
    }

    private fun findNearbyLyrics(file: File?): File? {
        val actualFile = file ?: return null
        val parent = actualFile.parentFile ?: return null
        val baseName = actualFile.nameWithoutExtension

        lyricExtensions.forEach { ext ->
            val sibling = File(parent, "$baseName.$ext")
            if (sibling.exists()) return sibling
        }

        val lyricsDir = File(parent, "Lyrics")
        if (lyricsDir.exists()) {
            lyricExtensions.forEach { ext ->
                val nested = File(lyricsDir, "$baseName.$ext")
                if (nested.exists()) return nested
            }
        }

        return null
    }

    private fun findNearbyCover(file: File?): File? {
        val actualFile = file ?: return null
        val parent = actualFile.parentFile ?: return null
        val baseName = actualFile.nameWithoutExtension

        imageExtensions.forEach { ext ->
            val sameName = File(parent, "$baseName.$ext")
            if (sameName.exists()) return sameName
        }

        coverFileNames.forEach { candidate ->
            imageExtensions.forEach { ext ->
                val sibling = File(parent, "$candidate.$ext")
                if (sibling.exists()) return sibling
            }
        }

        val coverDir = File(parent, "Covers")
        if (coverDir.exists()) {
            imageExtensions.forEach { ext ->
                val nested = File(coverDir, "$baseName.$ext")
                if (nested.exists()) return nested
            }
        }

        return null
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
        if (normalized.all(Char::isDigit)) return false
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
