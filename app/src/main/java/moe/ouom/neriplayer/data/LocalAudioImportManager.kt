package moe.ouom.neriplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File
import java.security.MessageDigest

data class LocalAudioImportResult(
    val songs: List<SongItem>,
    val failedCount: Int
)

object LocalAudioImportManager {
    private const val TAG = "LocalAudioImport"

    suspend fun importSongs(context: Context, uris: List<Uri>): LocalAudioImportResult = withContext(Dispatchers.IO) {
        val songs = mutableListOf<SongItem>()
        var failedCount = 0

        uris.distinctBy { it.toString() }.forEach { uri ->
            val song = runCatching { readSong(context, uri) }
                .onFailure { NPLogger.e(TAG, "Failed to import local audio: $uri", it) }
                .getOrNull()

            if (song != null) {
                songs += song
            } else {
                failedCount++
            }
        }

        LocalAudioImportResult(
            songs = songs.distinctBy { it.identity() },
            failedCount = failedCount
        )
    }

    private fun readSong(context: Context, uri: Uri): SongItem {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val displayName = queryDisplayName(context, uri)
            val fallbackName = displayName.substringBeforeLast('.').ifBlank {
                context.getString(R.string.local_files)
            }

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.music_unknown_artist)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.local_files)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val coverUrl = saveEmbeddedCover(
                context = context,
                uriKey = uri.toString(),
                embeddedPicture = retriever.embeddedPicture
            )

            SongItem(
                id = computeStableSongId(uri),
                name = title,
                artist = artist,
                album = album,
                albumId = 0L,
                durationMs = durationMs,
                coverUrl = coverUrl,
                mediaUri = uri.toString(),
                originalName = title,
                originalArtist = artist,
                originalCoverUrl = coverUrl
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0).orEmpty()
                } else {
                    ""
                }
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun saveEmbeddedCover(context: Context, uriKey: String, embeddedPicture: ByteArray?): String? {
        if (embeddedPicture == null || embeddedPicture.isEmpty()) return null

        val coverDir = File(context.filesDir, "local_audio_covers").apply { mkdirs() }
        val file = File(coverDir, "${stableKey(uriKey)}.jpg")
        file.writeBytes(embeddedPicture)
        return file.toURI().toString()
    }

    private fun computeStableSongId(uri: Uri): Long {
        return stableKey(uri.toString()).take(16).toULong(16).toLong()
    }

    private fun stableKey(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
