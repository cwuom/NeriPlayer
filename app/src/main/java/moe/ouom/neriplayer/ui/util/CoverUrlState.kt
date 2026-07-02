package moe.ouom.neriplayer.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

@Composable
fun rememberSongDisplayCoverUrl(song: SongItem?): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsState()
    val songKey = remember(song) { song?.coverResolutionKey() }
    var coverUrl by remember(songKey, downloadPresenceVersion) {
        mutableStateOf(song?.displayCoverUrl(context))
    }

    LaunchedEffect(songKey, appContext, downloadPresenceVersion) {
        if (song == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        val immediateCover = song.displayCoverUrl(context)
        coverUrl = immediateCover

        val resolvedCover = withContext(Dispatchers.IO) {
            song.displayCoverUrl(appContext)
        }
        if (!resolvedCover.isNullOrBlank()) {
            coverUrl = resolvedCover
        } else if (immediateCover.isNullOrBlank()) {
            coverUrl = null
        }
    }

    return coverUrl
}

private fun SongItem.coverResolutionKey(): String {
    return listOf(
        stableKey(),
        customCoverUrl.orEmpty(),
        coverUrl.orEmpty(),
        localFilePath.orEmpty(),
        mediaUri.orEmpty()
    ).joinToString("|")
}
