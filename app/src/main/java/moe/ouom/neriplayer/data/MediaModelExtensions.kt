package moe.ouom.neriplayer.data

import android.content.Context
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

fun SongItem.displayCoverUrl(): String? = customCoverUrl ?: coverUrl

fun SongItem.displayCoverUrl(context: Context): String? {
    val current = displayCoverUrl()?.takeIf { it.isNotBlank() }
    if (!current.isNullOrBlank() && !current.isRemoteCoverSource()) {
        return current
    }

    AudioDownloadManager.getLocalCoverUri(context, this)?.let { return it }
    LocalMediaSupport.inspect(context, this)?.coverUri?.takeIf { it.isNotBlank() }?.let { return it }
    return current
}

fun SongItem.displayName(): String = customName ?: name
fun SongItem.displayArtist(): String = customArtist ?: artist

fun LocalPlaylist.displayCoverUrl(): String? = customCoverUrl ?: songs.lastOrNull()?.displayCoverUrl()

fun LocalPlaylist.displayCoverUrl(context: Context): String? {
    return customCoverUrl ?: songs.lastOrNull()?.displayCoverUrl(context)
}

private fun String.isRemoteCoverSource(): Boolean {
    return startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true)
}
