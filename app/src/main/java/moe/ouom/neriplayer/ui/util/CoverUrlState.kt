package moe.ouom.neriplayer.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import java.util.LinkedHashMap

@OptIn(ExperimentalCoroutinesApi::class)
private val coverResolutionDispatcher = Dispatchers.IO.limitedParallelism(1)
private const val UI_COVER_MEMORY_CACHE_LIMIT = 2048
private const val PLAYLIST_COVER_FALLBACK_IDLE_DELAY_MS = 96L
private val resolvedCoverMemoryCache = object : LinkedHashMap<String, String>(
    UI_COVER_MEMORY_CACHE_LIMIT,
    0.75f,
    true
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
        return size > UI_COVER_MEMORY_CACHE_LIMIT
    }
}

internal fun shouldResolveSlowLocalCoverFallback(
    resolveLocalFallback: Boolean,
    playbackIntentActive: Boolean
): Boolean = resolveLocalFallback && !playbackIntentActive

internal fun shouldResolveEmbeddedCoverFallback(
    resolveLocalFallback: Boolean,
    playbackIntentActive: Boolean,
    allowEmbeddedCoverFallback: Boolean
): Boolean = shouldResolveSlowLocalCoverFallback(
    resolveLocalFallback = resolveLocalFallback,
    playbackIntentActive = playbackIntentActive
) && allowEmbeddedCoverFallback

internal fun shouldResolvePlaylistCoverFallback(
    resolveLocalFallback: Boolean,
    playbackIntentActive: Boolean,
    hasImmediateCover: Boolean
): Boolean = resolveLocalFallback && !playbackIntentActive && !hasImmediateCover

@Composable
fun rememberSongDisplayCoverUrl(
    song: SongItem?,
    resolveLocalFallback: Boolean = true
): String? {
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val playbackIntentActive by PlayerManager.playbackControlPlayingFlow.collectAsStateWithLifecycle()
    return rememberSongDisplayCoverUrl(
        song = song,
        resolveLocalFallback = resolveLocalFallback,
        downloadPresenceVersion = downloadPresenceVersion,
        playbackIntentActive = playbackIntentActive
    )
}

@Composable
internal fun rememberSongDisplayCoverUrl(
    song: SongItem?,
    resolveLocalFallback: Boolean,
    downloadPresenceVersion: Int,
    playbackIntentActive: Boolean,
    allowEmbeddedCoverFallback: Boolean = true
): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val songDisplayKey = remember(song) {
        song?.coverDisplayCacheKey()
    }
    val songKey = remember(song) {
        song?.coverResolutionKey()
    }
    var coverUrl by remember(songDisplayKey) {
        mutableStateOf(
            cachedResolvedCover(songDisplayKey)
                ?: song?.displayCoverUrl()
        )
    }

    LaunchedEffect(
        songDisplayKey,
        songKey,
        appContext,
        downloadPresenceVersion,
        resolveLocalFallback,
        playbackIntentActive,
        allowEmbeddedCoverFallback
    ) {
        if (song == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        // 这里只读取歌曲对象中的已知封面, 避免重组时触发存储探测
        val immediateCover = song.displayCoverUrl()
        if (!immediateCover.isNullOrBlank()) {
            rememberResolvedCover(songDisplayKey, immediateCover)
            coverUrl = immediateCover
        } else {
            cachedResolvedCover(songDisplayKey)?.let { cachedCover ->
                coverUrl = cachedCover
            }
            val cachedCover = withContext(coverResolutionDispatcher) {
                resolveCachedSongDisplayCoverUrl(appContext, song)
            }
            if (!cachedCover.isNullOrBlank()) {
                rememberResolvedCover(songDisplayKey, cachedCover)
                coverUrl = cachedCover
            }
        }
        if (
            !shouldResolveEmbeddedCoverFallback(
                resolveLocalFallback = resolveLocalFallback,
                playbackIntentActive = playbackIntentActive,
                allowEmbeddedCoverFallback = allowEmbeddedCoverFallback
            )
        ) {
            return@LaunchedEffect
        }

        val resolvedCover = withContext(coverResolutionDispatcher) {
            song.displayCoverUrl(appContext, resolveLocalFallback)
        }
        if (!resolvedCover.isNullOrBlank()) {
            rememberResolvedCover(songDisplayKey, resolvedCover)
            coverUrl = resolvedCover
        }
    }

    return coverUrl
}

private fun resolveCachedSongDisplayCoverUrl(
    context: android.content.Context,
    song: SongItem
): String? {
    song.customCoverUrl?.takeIf { it.isNotBlank() }?.let { return it }
    AudioDownloadManager.peekLocalCoverUri(song)?.takeIf { it.isNotBlank() }?.let { return it }
    if (song.isLocalSong()) {
        LocalMediaSupport.peekCachedEmbeddedCoverUri(context, song)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return song.displayCoverUrl()
}

@Composable
fun rememberPlaylistDisplayCoverUrl(
    playlist: LocalPlaylist?,
    resolveLocalFallback: Boolean = true,
    additionalCoverCandidates: List<SongItem> = emptyList(),
    allowEmbeddedCoverFallback: Boolean = true
): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val playbackIntentActive by PlayerManager.playbackControlPlayingFlow.collectAsStateWithLifecycle()
    val effectivePlaylist = remember(playlist, appContext) {
        playlist?.takeUnless {
            it.songs.isEmpty() && LocalFilesPlaylist.isSystemPlaylist(it, appContext)
        }
    }
    val effectiveCoverCandidates = if (effectivePlaylist == null) {
        emptyList()
    } else {
        additionalCoverCandidates
    }
    val playlistKey = remember(effectivePlaylist, effectiveCoverCandidates) {
        effectivePlaylist?.coverResolutionKey()
    }
    var coverUrl by remember(playlistKey, downloadPresenceVersion) {
        mutableStateOf(
            cachedResolvedCover(playlistKey)
                ?: effectivePlaylist?.customCoverUrl?.takeIf { it.isNotBlank() }
        )
    }

    LaunchedEffect(
        playlistKey,
        appContext,
        downloadPresenceVersion,
        resolveLocalFallback,
        additionalCoverCandidates,
        playbackIntentActive,
        allowEmbeddedCoverFallback
    ) {
        if (effectivePlaylist == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        cachedResolvedCover(playlistKey)?.let { cachedCover ->
            coverUrl = cachedCover
            return@LaunchedEffect
        }
        val immediateCover = withContext(coverResolutionDispatcher) {
            effectivePlaylist.displayCoverUrl(effectiveCoverCandidates)
        }
        if (!immediateCover.isNullOrBlank()) {
            rememberResolvedCover(playlistKey, immediateCover)
            coverUrl = immediateCover
        }
        if (!resolveLocalFallback || !allowEmbeddedCoverFallback || playbackIntentActive) {
            return@LaunchedEffect
        }
        if (
            !shouldResolvePlaylistCoverFallback(
                resolveLocalFallback = resolveLocalFallback,
                playbackIntentActive = playbackIntentActive,
                hasImmediateCover = !immediateCover.isNullOrBlank()
            )
        ) {
            return@LaunchedEffect
        }

        val resolvedCover = resolvePlaylistCoverFallbackGradually(
            context = appContext,
            playlist = effectivePlaylist,
            additionalCoverCandidates = effectiveCoverCandidates
        )
        if (!resolvedCover.isNullOrBlank()) {
            rememberResolvedCover(playlistKey, resolvedCover)
            coverUrl = resolvedCover
        } else if (cachedResolvedCover(playlistKey).isNullOrBlank()) {
            coverUrl = null
        }
    }

    return coverUrl
}

private suspend fun resolvePlaylistCoverFallbackGradually(
    context: android.content.Context,
    playlist: LocalPlaylist,
    additionalCoverCandidates: List<SongItem>
): String? {
    suspend fun resolveCandidates(candidates: Iterable<SongItem>): String? {
        for (song in candidates) {
            if (!song.isLocalSong()) continue
            if (PlayerManager.playbackControlPlayingFlow.value) return null

            val resolvedCover = withContext(coverResolutionDispatcher) {
                song.displayCoverUrl(
                    context = context,
                    resolveLocalMetadataFallback = true
                )
            }
            if (!resolvedCover.isNullOrBlank()) return resolvedCover

            // 每个候选之间留出时间，避免无封面的大歌单持续占用 CPU 和内存
            kotlinx.coroutines.delay(PLAYLIST_COVER_FALLBACK_IDLE_DELAY_MS)
        }
        return null
    }

    return resolveCandidates(playlist.songs)
        ?: resolveCandidates(additionalCoverCandidates)
}

@Composable
fun rememberLocalArtistDisplayCoverUrl(
    artist: LocalArtistSummary?,
    resolveLocalFallback: Boolean = true
): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val artistKey = remember(artist) {
        artist?.coverResolutionKey()
    }
    var coverUrl by remember(artistKey, downloadPresenceVersion) {
        mutableStateOf(cachedResolvedCover(artistKey) ?: artist?.displayCoverUrl())
    }

    LaunchedEffect(artistKey, appContext, downloadPresenceVersion, resolveLocalFallback) {
        if (artist == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        val immediateCover = artist.displayCoverUrl()
        cachedResolvedCover(artistKey)?.let { cachedCover ->
            coverUrl = cachedCover
        }
        if (!immediateCover.isNullOrBlank()) {
            rememberResolvedCover(artistKey, immediateCover)
            coverUrl = immediateCover
        } else if (!resolveLocalFallback && cachedResolvedCover(artistKey).isNullOrBlank()) {
            coverUrl = null
        }
        if (!resolveLocalFallback) {
            return@LaunchedEffect
        }

        val resolvedCover = withContext(coverResolutionDispatcher) {
            artist.displayCoverUrl(appContext, resolveLocalFallback)
        }
        if (!resolvedCover.isNullOrBlank()) {
            rememberResolvedCover(artistKey, resolvedCover)
            coverUrl = resolvedCover
        } else if (immediateCover.isNullOrBlank() && cachedResolvedCover(artistKey).isNullOrBlank()) {
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

private fun SongItem.coverResolutionKey(): String {
    return listOf(
        stableKey(),
        customCoverUrl.orEmpty(),
        coverUrl.orEmpty(),
        localFilePath.orEmpty(),
        mediaUri.orEmpty()
    ).joinToString("|")
}

internal fun SongItem.coverDisplayCacheKey(): String = "song:${stableKey()}"

private fun LocalPlaylist.coverResolutionKey(): String {
    return listOf(
        id.toString(),
        modifiedAt.toString(),
        customCoverUrl.orEmpty(),
        songs.size.toString(),
        playlistCoverResolutionSignature(songs).toString()
    ).joinToString("|")
}

private fun LocalArtistSummary.coverResolutionKey(): String {
    return listOf(
        stableKey,
        name,
        songs.size.toString(),
        playlistCoverResolutionSignature(songs).toString()
    ).joinToString("|")
}

internal fun playlistCoverResolutionSignature(songs: List<SongItem>): Long {
    var signature = 1_125_899_906_842_597L
    songs.forEach { song ->
        signature = 31L * signature + song.id
        signature = 31L * signature + song.album.hashCode()
        signature = 31L * signature + song.albumId
        signature = 31L * signature + song.customCoverUrl.orEmpty().hashCode()
        signature = 31L * signature + song.coverUrl.orEmpty().hashCode()
        signature = 31L * signature + song.originalCoverUrl.orEmpty().hashCode()
        signature = 31L * signature + song.localFilePath.orEmpty().hashCode()
        signature = 31L * signature + song.mediaUri.orEmpty().hashCode()
        signature = 31L * signature + song.channelId.orEmpty().hashCode()
        signature = 31L * signature + song.audioId.orEmpty().hashCode()
        signature = 31L * signature + song.subAudioId.orEmpty().hashCode()
        signature = 31L * signature + song.sourceStableKey.orEmpty().hashCode()
    }
    return signature
}
