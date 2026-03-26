package moe.ouom.neriplayer.data

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
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.security.MessageDigest
import java.io.File

data class LocalAudioImportResult(
    val songs: List<SongItem>,
    val failedCount: Int,
    val completed: Boolean = true
)

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
                LocalMediaSupport.toSongItem(LocalMediaSupport.inspect(context, stableUri))
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
                    val fallbackSong = SongItem(
                        id = computeStableSongId(source),
                        name = fallbackTitle,
                        artist = fallbackArtist,
                        album = fallbackAlbum,
                        albumId = 0L,
                        durationMs = duration,
                        coverUrl = null,
                        mediaUri = source,
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
        val safeTitle = baseSong.name
            .takeIf(::isReadableScannedTitle)
            ?: fallbackTitle.takeIf(::isReadableScannedTitle)
            ?: fileTitle
        val safeArtist = baseSong.artist.takeIf { it.isNotBlank() } ?: fallbackArtist
        val safeAlbum = baseSong.album.takeIf { it.isNotBlank() } ?: fallbackAlbum

        return baseSong.copy(
            id = computeStableSongId(source),
            name = safeTitle,
            artist = safeArtist,
            album = safeAlbum,
            durationMs = baseSong.durationMs.takeIf { it > 0L } ?: fallbackDurationMs,
            mediaUri = source,
            originalName = baseSong.originalName?.takeIf { it.isNotBlank() } ?: safeTitle,
            originalArtist = baseSong.originalArtist?.takeIf { it.isNotBlank() } ?: safeArtist,
            localFileName = fileName,
            localFilePath = source,
            channelId = "local",
            audioId = computeStableSongId(source).toString()
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

    private fun computeStableSongId(source: String): Long {
        return stableKey(source).take(16).toULong(16).toLong()
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

    private fun copyNearbySidecars(sourceFile: File, targetFile: File) {
        val sourceDir = sourceFile.parentFile ?: return
        val targetDir = targetFile.parentFile ?: return
        val sourceBase = sourceFile.nameWithoutExtension
        val targetBase = targetFile.nameWithoutExtension

        lyricExtensions.forEach { extension ->
            copyIfExists(File(sourceDir, "$sourceBase.$extension"), File(targetDir, "$targetBase.$extension"))
            copyIfExists(
                File(File(sourceDir, "Lyrics"), "$sourceBase.$extension"),
                File(targetDir, "$targetBase.$extension")
            )
        }

        imageExtensions.forEach { extension ->
            copyIfExists(File(sourceDir, "$sourceBase.$extension"), File(targetDir, "$targetBase.$extension"))
        }

        coverNames.forEach { name ->
            imageExtensions.forEach { extension ->
                copyIfExists(File(sourceDir, "$name.$extension"), File(targetDir, "$name.$extension"))
            }
        }

        val sourceCoverDir = File(sourceDir, "Covers")
        imageExtensions.forEach { extension ->
            copyIfExists(File(sourceCoverDir, "$sourceBase.$extension"), File(targetDir, "$targetBase.$extension"))
        }
    }

    private fun copyIfExists(source: File, target: File) {
        if (!source.exists() || target.exists()) {
            return
        }
        runCatching {
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
