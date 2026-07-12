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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import java.util.LinkedHashMap

@OptIn(ExperimentalCoroutinesApi::class)
private val coverResolutionDispatcher = Dispatchers.IO.limitedParallelism(2)
private const val UI_COVER_MEMORY_CACHE_LIMIT = 2048
private val resolvedCoverMemoryCache = object : LinkedHashMap<String, String>(
    UI_COVER_MEMORY_CACHE_LIMIT,
    0.75f,
    true
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
        return size > UI_COVER_MEMORY_CACHE_LIMIT
    }
}

@Composable
fun rememberSongDisplayCoverUrl(
    song: SongItem?,
    resolveLocalFallback: Boolean = true
): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsState()
    val songKey = remember(song, resolveLocalFallback) {
        song?.coverResolutionKey(resolveLocalFallback)
    }
    var coverUrl by remember(songKey, downloadPresenceVersion) {
        mutableStateOf(
            cachedResolvedCover(songKey)
                ?: song?.displayCoverUrl(context, resolveLocalFallback)
        )
    }

    LaunchedEffect(songKey, appContext, downloadPresenceVersion) {
        if (song == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        cachedResolvedCover(songKey)?.let { cachedCover ->
            coverUrl = cachedCover
        }
        val immediateCover = song.displayCoverUrl(context, resolveLocalFallback)
        if (!immediateCover.isNullOrBlank()) {
            rememberResolvedCover(songKey, immediateCover)
            coverUrl = immediateCover
        } else if (!resolveLocalFallback && cachedResolvedCover(songKey).isNullOrBlank()) {
            coverUrl = null
        }
        if (!resolveLocalFallback) {
            return@LaunchedEffect
        }

        val resolvedCover = withContext(coverResolutionDispatcher) {
            song.displayCoverUrl(appContext, resolveLocalFallback)
        }
        if (!resolvedCover.isNullOrBlank()) {
            rememberResolvedCover(songKey, resolvedCover)
            coverUrl = resolvedCover
        } else if (immediateCover.isNullOrBlank() && cachedResolvedCover(songKey).isNullOrBlank()) {
            coverUrl = null
        }
    }

    return coverUrl
}

@Composable
fun rememberPlaylistDisplayCoverUrl(
    playlist: LocalPlaylist?,
    resolveLocalFallback: Boolean = true
): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsState()
    val playlistKey = remember(playlist, resolveLocalFallback) {
        playlist?.coverResolutionKey(resolveLocalFallback)
    }
    var coverUrl by remember(playlistKey, downloadPresenceVersion) {
        mutableStateOf(cachedResolvedCover(playlistKey) ?: playlist?.displayCoverUrl())
    }

    LaunchedEffect(playlistKey, appContext, downloadPresenceVersion, resolveLocalFallback) {
        if (playlist == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        val immediateCover = playlist.displayCoverUrl()
        cachedResolvedCover(playlistKey)?.let { cachedCover ->
            coverUrl = cachedCover
        }
        if (!immediateCover.isNullOrBlank()) {
            rememberResolvedCover(playlistKey, immediateCover)
            coverUrl = immediateCover
        } else if (!resolveLocalFallback && cachedResolvedCover(playlistKey).isNullOrBlank()) {
            coverUrl = null
        }
        if (!resolveLocalFallback) {
            return@LaunchedEffect
        }

        val resolvedCover = withContext(coverResolutionDispatcher) {
            playlist.displayCoverUrl(appContext, resolveLocalFallback)
        }
        if (!resolvedCover.isNullOrBlank()) {
            rememberResolvedCover(playlistKey, resolvedCover)
            coverUrl = resolvedCover
        } else if (immediateCover.isNullOrBlank() && cachedResolvedCover(playlistKey).isNullOrBlank()) {
            coverUrl = null
        }
    }

    return coverUrl
}

private fun cachedResolvedCover(key: String?): String? {
    if (key.isNullOrBlank()) return null
    return synchronized(resolvedCoverMemoryCache) {
        resolvedCoverMemoryCache[key]
    }
}

private fun rememberResolvedCover(key: String?, coverUrl: String?) {
    if (key.isNullOrBlank() || coverUrl.isNullOrBlank()) return
    synchronized(resolvedCoverMemoryCache) {
        resolvedCoverMemoryCache[key] = coverUrl
    }
}

private fun SongItem.coverResolutionKey(resolveLocalFallback: Boolean): String {
    return listOf(
        stableKey(),
        customCoverUrl.orEmpty(),
        coverUrl.orEmpty(),
        localFilePath.orEmpty(),
        mediaUri.orEmpty(),
        resolveLocalFallback.toString()
    ).joinToString("|")
}

private fun LocalPlaylist.coverResolutionKey(resolveLocalFallback: Boolean): String {
    val recentCoverSignature = songs
        .asReversed()
        .asSequence()
        .take(12)
        .joinToString("#") { song ->
            listOf(
                song.stableKey(),
                song.customCoverUrl.orEmpty(),
                song.coverUrl.orEmpty(),
                song.originalCoverUrl.orEmpty(),
                song.localFilePath.orEmpty(),
                song.mediaUri.orEmpty()
            ).joinToString(":")
        }
    return listOf(
        id.toString(),
        modifiedAt.toString(),
        customCoverUrl.orEmpty(),
        songs.size.toString(),
        recentCoverSignature,
        resolveLocalFallback.toString()
    ).joinToString("|")
}
