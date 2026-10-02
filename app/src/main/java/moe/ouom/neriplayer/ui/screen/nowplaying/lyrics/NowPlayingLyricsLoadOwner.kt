package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import moe.ouom.neriplayer.data.identity.stableKey

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference

internal data class NowPlayingLyricsLoadRequest(
    val context: Context,
    val song: SongItem?,
    val currentMediaUrl: String?,
    val preferWordTimedLyrics: Boolean,
    val defaultLyricSource: LyricSourcePreference,
    val cachedPreferredLyrics: PreferredLyricSourceResult?
)

internal data class NowPlayingLyricsRefreshVersions(
    val lyricsPreferenceRevision: Long,
    val downloadPresenceVersion: Int,
    val downloadedLyricsRefreshVersion: Long
)

private data class NowPlayingLyricsReloadTrigger(
    val lyrics: NowPlayingLyricsReloadKey?,
    val mediaUrl: String?,
    val preferWordTimedLyrics: Boolean,
    val source: LyricSourcePreference,
    val versions: NowPlayingLyricsRefreshVersions
)

internal data class NowPlayingLyricsReloadKey(
    val songId: Long,
    val edited: Boolean?,
    val revision: Long,
    val lyric: String?,
    val translatedLyric: String?,
    val romanizedLyric: String?,
    val originalLyric: String?,
    val originalTranslatedLyric: String?,
    val originalRomanizedLyric: String?,
    val matchedSongId: String?,
    val matchedSource: MusicPlatform?,
    val album: String,
    val mediaUri: String?,
    val localFilePath: String?
)

internal fun nowPlayingLyricsReloadKey(song: SongItem?): NowPlayingLyricsReloadKey? {
    if (song == null) return null
    return NowPlayingLyricsReloadKey(
        songId = song.id,
        edited = song.lyricSyncEdited,
        revision = song.lyricSyncRevision,
        lyric = song.matchedLyric,
        translatedLyric = song.matchedTranslatedLyric,
        romanizedLyric = song.matchedRomanizedLyric,
        originalLyric = song.originalLyric,
        originalTranslatedLyric = song.originalTranslatedLyric,
        originalRomanizedLyric = song.originalRomanizedLyric,
        matchedSongId = song.matchedSongId,
        matchedSource = song.matchedLyricSource,
        album = song.album,
        mediaUri = song.mediaUri,
        localFilePath = song.localFilePath
    )
}

internal interface NowPlayingLyricsStages {
    fun readInitial(request: NowPlayingLyricsLoadRequest): NowPlayingInitialLyricsResult =
        NowPlayingInitialLyricsResult(request.cachedPreferredLyrics,
            buildNowPlayingInitialLyricsState(request.song, request.cachedPreferredLyrics))

    suspend fun readFast(request: NowPlayingLyricsLoadRequest): NowPlayingFastLyricsResult
    suspend fun readBackground(
        request: NowPlayingLyricsLoadRequest,
        fast: NowPlayingFastLyricsResult
    ): LoadedLyricsState
}

internal class NowPlayingLyricsLoadOwner(
    initialState: LoadedLyricsState,
    private val scope: CoroutineScope,
    private val stages: NowPlayingLyricsStages
) {
    var state by mutableStateOf(initialState)
        private set
    var secondaryResolved by mutableStateOf(false)
        private set

    private var requestGeneration = 0L
    private var requestJob: Job? = null
    private var disposed = false

    fun reload(request: NowPlayingLyricsLoadRequest) {
        if (disposed) return
        requestGeneration++
        val generation = requestGeneration
        requestJob?.cancel()
        requestJob = scope.launch { load(generation, request) }
    }

    private suspend fun load(generation: Long, request: NowPlayingLyricsLoadRequest) {
        NPLogger.d("NowPlayingLyrics", "歌词加载开始: key=${request.song?.stableKey().orEmpty()}")
        val fast = stages.readFast(request)
        publish(generation, request.song, fast.state, "fast")
        val background = stages.readBackground(request, fast)
        publish(generation, request.song, background, "background")
        markSecondaryResolved(generation)
    }

    private fun markSecondaryResolved(generation: Long) {
        if (isCurrent(generation)) secondaryResolved = true
    }

    internal fun publish(
        generation: Long,
        song: SongItem?,
        loaded: LoadedLyricsState,
        stage: String
    ) {
        if (!isCurrent(generation)) return
        val resolved = overlayConfirmedUserLyrics(song, loaded)
        val hasLyrics = resolved.hasDisplayableContent()
        if (song.hasConfirmedLyricOverride() || shouldReplaceLyricsAfterRefresh(song != null, hasLyrics)) state = resolved
        logPublishedLyrics(song, stage, hasLyrics)
    }

    private fun logPublishedLyrics(song: SongItem?, stage: String, hasLyrics: Boolean) {
        NPLogger.d("NowPlayingLyrics", "歌词结果发布: stage=$stage, key=${song?.stableKey().orEmpty()}, hasLyrics=$hasLyrics")
    }

    private fun isCurrent(generation: Long): Boolean = !disposed && requestGeneration == generation

    fun dispose() {
        disposed = true
        requestGeneration++
        requestJob?.cancel()
        requestJob = null
    }
}

@Composable
internal fun rememberNowPlayingLyricsOwner(
    song: SongItem?,
    defaultLyricSource: LyricSourcePreference,
    initial: LoadedLyricsState,
    scope: CoroutineScope,
    stages: NowPlayingLyricsStages
): NowPlayingLyricsLoadOwner {
    val owner = remember(song?.stableKey(), defaultLyricSource, song?.lyricSyncRevision, song?.lyricSyncEdited, stages) {
        NowPlayingLyricsLoadOwner(initial, scope, stages)
    }
    DisposableEffect(owner) { onDispose(owner::dispose) }
    return owner
}

@Composable
internal fun rememberNowPlayingLyricsLoadOwner(
    request: NowPlayingLyricsLoadRequest,
    versions: NowPlayingLyricsRefreshVersions,
    stages: NowPlayingLyricsStages = DefaultNowPlayingLyricsStages
): NowPlayingLyricsLoadOwner {
    val scope = rememberCoroutineScope()
    val initial = rememberNowPlayingInitialLyrics(request, versions.lyricsPreferenceRevision, stages)
    val owner = rememberNowPlayingLyricsOwner(request.song, request.defaultLyricSource, initial.state, scope, stages)
    val reloadKey = NowPlayingLyricsReloadTrigger(
        nowPlayingLyricsReloadKey(request.song),
        resolveNowPlayingLyricsMediaReloadKey(request.song, request.currentMediaUrl),
        request.preferWordTimedLyrics, request.defaultLyricSource, versions
    )
    LaunchedEffect(owner, reloadKey, stages) {
        owner.reload(request.copy(cachedPreferredLyrics = initial.cachedPreferredLyrics))
    }
    return owner
}
