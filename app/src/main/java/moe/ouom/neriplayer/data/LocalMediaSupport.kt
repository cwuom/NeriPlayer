package moe.ouom.neriplayer.data

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.content.FileProvider
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.max

private const val LOCAL_MEDIA_SHARE_TAG = "LocalMediaSupport"

data class LocalMediaDetails(
    val sourceUri: Uri,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
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

fun SongItem.localMediaUri(): Uri? {
    val source = localFilePath
        ?.takeIf { it.isNotBlank() }
        ?: mediaUri?.takeIf { it.isNotBlank() }
        ?: return null
    return if (source.startsWith("/")) {
        Uri.fromFile(File(source))
    } else {
        Uri.parse(source)
    }
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

    fun inspect(context: Context, song: SongItem): LocalMediaDetails? {
        val uri = song.localMediaUri() ?: return null
        return inspect(context, uri)
    }

    fun resolveLocalFile(context: Context, song: SongItem): File? {
        val uri = song.localMediaUri() ?: return null
        return resolveLocalFile(context, uri)
    }

    fun resolveLocalFile(context: Context, uri: Uri): File? {
        val resolvedPath = directFilePath(uri)
            ?: queryContentInfo(context, uri).filePath
            ?: resolvePathFromDescriptor(context, uri)
        return resolvedPath?.let(::File)?.takeIf(File::exists)
    }

    fun inspect(context: Context, uri: Uri): LocalMediaDetails {
        val queried = queryContentInfo(context, uri)
        val resolvedPath = directFilePath(uri) ?: queried.filePath ?: resolvePathFromDescriptor(context, uri)
        val file = resolvedPath?.let(::File)?.takeIf(File::exists)
        val playableUri = file?.let(Uri::fromFile) ?: uri

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, playableUri)
            val audioTrackTechInfo = inspectAudioTrackInfo(context, playableUri)

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

            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val title = sanitizeLocalTitle(
                rawTitle = rawTitle,
                fallbackTitle = fallbackTitle,
                sourceUri = uri
            )
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.music_unknown_artist)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.local_files)
            val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?.takeIf { it.isNotBlank() }
            val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                ?.takeIf { it.isNotBlank() }
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.takeIf { it.isNotBlank() }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val mimeType = queried.mimeType
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.takeIf { it.isNotBlank() }
            val bitrateKbps = audioTrackTechInfo?.bitrateKbps
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull()
                    ?.let { max(0, (it + 500) / 1000) }
            val sampleRateHz = audioTrackTechInfo?.sampleRateHz
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
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.toIntOrNull()
            val trackNumber = parseIndexedMetadata(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            )
            val discNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                parseIndexedMetadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                )
            } else {
                null
            }

            val embeddedPicture = retriever.embeddedPicture
            val embeddedCover = embeddedPicture != null && embeddedPicture.isNotEmpty()
            val embeddedCoverUri = if (embeddedCover) {
                saveEmbeddedCover(context, resolvedPath ?: uri.toString(), embeddedPicture)
            } else {
                null
            }
            val nearbyCover = if (embeddedCoverUri == null) {
                findNearbyCover(file)
            } else {
                null
            }
            val nearbyLyrics = findNearbyLyrics(file)
            val lyricContent = nearbyLyrics?.let {
                readTextFile(it)?.also { _ -> }
                    ?: run {
                        NPLogger.w(TAG, "read lyric failed for ${it.absolutePath}")
                        null
                    }
            }

            LocalMediaDetails(
                sourceUri = uri,
                displayName = displayName,
                title = title,
                artist = artist,
                album = album,
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
                coverUri = embeddedCoverUri ?: nearbyCover?.toURI()?.toString(),
                coverSource = when {
                    embeddedCoverUri != null -> context.getString(R.string.local_song_cover_embedded)
                    nearbyCover != null -> context.getString(R.string.local_song_cover_external)
                    else -> null
                },
                lyricContent = lyricContent,
                lyricPath = nearbyLyrics?.absolutePath,
                lyricSource = nearbyLyrics?.let { context.getString(R.string.local_song_lyric_external) },
                originalTitle = title,
                originalArtist = artist,
                embeddedCover = embeddedCover
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun toSongItem(context: Context, details: LocalMediaDetails): SongItem {
        val source = details.filePath?.takeIf { it.isNotBlank() } ?: details.sourceUri.toString()
        val stableId = computeStableSongId(source)
        return SongItem(
            id = stableId,
            name = details.title,
            artist = details.artist,
            album = details.album,
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
        val filePath: String?
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
                filePath = file.takeIf(File::exists)?.absolutePath
            )
        }
        val projection = buildList {
            add(OpenableColumns.DISPLAY_NAME)
            add(OpenableColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add("_data")
        }.toTypedArray()

        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                QueriedContentInfo(
                    displayName = cursor.getOptionalString(OpenableColumns.DISPLAY_NAME),
                    sizeBytes = cursor.getOptionalLong(OpenableColumns.SIZE),
                    mimeType = cursor.getOptionalString(MediaStore.MediaColumns.MIME_TYPE),
                    lastModifiedMs = cursor.getOptionalLong(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000),
                    filePath = resolveQueryFilePath(
                        rawPath = cursor.getOptionalString("_data"),
                        relativePath = cursor.getOptionalString(MediaStore.MediaColumns.RELATIVE_PATH),
                        displayName = cursor.getOptionalString(OpenableColumns.DISPLAY_NAME)
                    )
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
            filePath = null
        )
    }

    private fun resolvePathFromDescriptor(context: Context, uri: Uri): String? {
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

    private fun sanitizeLocalTitle(
        rawTitle: String?,
        fallbackTitle: String,
        sourceUri: Uri
    ): String {
        val candidate = rawTitle?.trim().orEmpty()
        if (candidate.isBlank()) {
            return fallbackTitle
        }
        val isPureNumber = candidate.all(Char::isDigit)
        val looksLikeUriId = candidate == sourceUri.lastPathSegment
        val fallbackIsReadable = fallbackTitle.isNotBlank() && fallbackTitle != candidate
        return if ((isPureNumber || looksLikeUriId) && fallbackIsReadable) {
            fallbackTitle
        } else {
            candidate
        }
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

    private fun scoreDecodedText(text: String): Int {
        val replacementPenalty = text.count { it == '\uFFFD' } * 200
        val nulPenalty = text.count { it == '\u0000' } * 200
        val controlPenalty = text.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' } * 40
        val blankPenalty = if (text.isBlank()) 200 else 0
        val lyricBonus = if (text.contains('[') && text.contains(']')) 20 else 0
        return 1000 - replacementPenalty - nulPenalty - controlPenalty - blankPenalty + lyricBonus
    }

    private fun String.normalizeDecodedText(): String = replace("\uFEFF", "")
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

private fun MediaFormat.getOptionalInt(key: String): Int? {
    if (!containsKey(key)) return null
    return runCatching { getInteger(key) }.getOrNull()
}

private fun MediaFormat.getOptionalString(key: String): String? {
    if (!containsKey(key)) return null
    return runCatching { getString(key) }.getOrNull()
}
