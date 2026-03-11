package moe.ouom.neriplayer.data

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

object LocalSongSupport {
    private val localUriSchemes = setOf("content", "file", "android.resource")

    fun isLocalSong(song: SongItem, context: Context? = null): Boolean {
        return !song.localFilePath.isNullOrBlank() ||
            isLocalMediaUri(song.mediaUri) ||
            isLikelyLegacyLocalSong(song, context)
    }

    fun isLocalSong(album: String?, mediaUri: String?, context: Context? = null): Boolean {
        return isLocalMediaUri(mediaUri) ||
            (
                mediaUri.isNullOrBlank() &&
                    isLocalAlbumPlaceholder(album, context)
            )
    }

    fun isLocalMediaUri(mediaUri: String?): Boolean {
        if (mediaUri.isNullOrBlank()) return false
        if (mediaUri.startsWith("/")) return true

        val scheme = runCatching { Uri.parse(mediaUri).scheme.orEmpty().lowercase() }
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
        return LocalFilesPlaylist.matches(album, context)
    }
}
