package moe.ouom.neriplayer.ui.screen

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
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.LyricSourcePreference

internal data class NowPlayingLyricsLoadRequest(
    val context: Context,
    val song: SongItem?,
    val currentMediaUrl: String?,
    val preferWordTimedLyrics: Boolean,
    val defaultLyricSource: LyricSourcePreference,
    val cachedPreferredLyrics: PreferredLyricSourceResult?
)

internal interface NowPlayingLyricsStages {
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
        val hasLyrics = loaded.hasDisplayableContent()
        if (shouldReplaceLyricsAfterRefresh(song != null, hasLyrics)) state = loaded
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
internal fun rememberNowPlayingLyricsLoadOwner(
    context: Context,
    song: SongItem?,
    currentMediaUrl: String?,
    preferWordTimedLyrics: Boolean,
    defaultLyricSource: LyricSourcePreference,
    lyricsPreferenceRevision: Long,
    downloadPresenceVersion: Int,
    downloadedLyricsRefreshVersion: Long
): NowPlayingLyricsLoadOwner {
    val sourceKey = song?.stableKey()
    val scope = rememberCoroutineScope()
    val cachedPreferred = remember(song, defaultLyricSource, preferWordTimedLyrics, lyricsPreferenceRevision) {
        song?.let { PlayerManager.getCachedPreferredLyricSourceResult(it, defaultLyricSource, preferWordTimedLyrics) }
    }
    val initial = remember(song, cachedPreferred) { buildNowPlayingInitialLyricsState(song, cachedPreferred) }
    val owner = remember(sourceKey, defaultLyricSource) {
        NowPlayingLyricsLoadOwner(initial, scope, DefaultNowPlayingLyricsStages)
    }
    DisposableEffect(owner) { onDispose(owner::dispose) }
    LaunchedEffect(
        song?.id,
        song?.matchedLyric,
        song?.matchedTranslatedLyric,
        song?.matchedRomanizedLyric,
        song?.originalLyric,
        song?.originalTranslatedLyric,
        song?.originalRomanizedLyric,
        song?.matchedSongId,
        song?.matchedLyricSource,
        song?.album,
        song?.mediaUri,
        song?.localFilePath,
        downloadPresenceVersion,
        downloadedLyricsRefreshVersion,
        resolveNowPlayingLyricsMediaReloadKey(song, currentMediaUrl),
        preferWordTimedLyrics,
        defaultLyricSource,
        lyricsPreferenceRevision
    ) {
        owner.reload(
            NowPlayingLyricsLoadRequest(
                context, song, currentMediaUrl, preferWordTimedLyrics, defaultLyricSource, cachedPreferred
            )
        )
    }
    return owner
}
