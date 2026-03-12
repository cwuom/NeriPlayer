package moe.ouom.neriplayer.data

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal fun normalizeLocalAlbumIdentity(
    album: String?,
    usesFallbackAlbum: Boolean
): String {
    val normalized = album?.trim().orEmpty()
    if (normalized.isBlank()) return LocalSongSupport.LOCAL_ALBUM_IDENTITY
    return if (usesFallbackAlbum) LocalSongSupport.LOCAL_ALBUM_IDENTITY else normalized
}

fun SongItem.displayAlbum(context: Context): String {
    val normalized = album.trim()
    if (normalized.isBlank()) return normalized
    return if (
        normalized == LocalSongSupport.LOCAL_ALBUM_IDENTITY ||
        LocalFilesPlaylist.matches(normalized, context)
    ) {
        context.getString(R.string.local_files)
    } else {
        normalized
    }
}
