package moe.ouom.neriplayer.data

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import androidx.core.net.toUri

object LocalSongSupport {
    private val localUriSchemes = setOf("content", "file", "android.resource")
    const val LOCAL_ALBUM_IDENTITY = "__local_files__"

    fun isLocalSong(song: SongItem, context: Context? = null): Boolean {
        return !song.localFilePath.isNullOrBlank() ||
            isLocalMediaUri(song.mediaUri) ||
            isLikelyLegacyLocalSong(song, context)
    }

    fun isLocalSong(
        album: String?,
        mediaUri: String?,
        albumId: Long? = null,
        context: Context? = null
    ): Boolean {
        return isLocalMediaUri(mediaUri) ||
            (
                mediaUri.isNullOrBlank() &&
                    albumId == 0L &&
                    isLocalAlbumPlaceholder(album, context)
            )
    }

    fun isLocalMediaUri(mediaUri: String?): Boolean {
        if (mediaUri.isNullOrBlank()) return false
        if (mediaUri.startsWith("/")) return true

        val scheme = runCatching { mediaUri.toUri().scheme.orEmpty().lowercase() }
            .getOrDefault("")
        return scheme in localUriSchemes
    }

    fun sanitizeMediaUriForSync(mediaUri: String?): String? {
        return mediaUri?.takeUnless { isLocalMediaUri(it) }
    }

    private fun isLikelyLegacyLocalSong(song: SongItem, context: Context?): Boolean {
        return song.mediaUri.isNullOrBlank() &&
            song.albumId == 0L &&
            isLocalAlbumPlaceholder(song.album, context)
    }

    private fun isLocalAlbumPlaceholder(album: String?, context: Context?): Boolean {
        if (album.isNullOrBlank()) return false
        return album == LOCAL_ALBUM_IDENTITY || LocalFilesPlaylist.matches(album, context)
    }

    internal fun identityAlbumKey(song: SongItem): String {
        return if (isLocalSong(song, null)) LOCAL_ALBUM_IDENTITY else song.album
    }
}
