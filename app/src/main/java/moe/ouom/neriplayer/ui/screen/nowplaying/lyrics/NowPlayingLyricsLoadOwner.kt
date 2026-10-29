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
    val snapshotCache: NowPlayingLyricsSnapshotCache? get() = null
    val cacheGeneration: Long get() = 0L

    fun readInitial(request: NowPlayingLyricsLoadRequest): NowPlayingInitialLyricsResult =
        NowPlayingInitialLyricsResult(request.cachedPreferredLyrics,
            buildNowPlayingCachedInitialLyricsState(request.song, request.cachedPreferredLyrics))

    suspend fun readFast(request: NowPlayingLyricsLoadRequest): NowPlayingFastLyricsResult
    suspend fun readBackground(
        request: NowPlayingLyricsLoadRequest,
        fast: NowPlayingFastLyricsResult
    ): LoadedLyricsState
}

private class NowPlayingLyricsRefreshState {
    private var lastPreferenceRevision: Long? = null
    private var lastSongKey: String? = null
    private var pendingReplacement = false

    fun beginRequest(songKey: String?, preferenceRevision: Long?): Boolean {
        if (songKey != lastSongKey) pendingReplacement = false
        if (preferenceRevision != null &&
            lastPreferenceRevision != null &&
            lastPreferenceRevision != preferenceRevision
        ) {
            // 偏好变更后必须等新结果发布, 请求被取消不能恢复旧歌词
            pendingReplacement = true
        }
        lastSongKey = songKey
        lastPreferenceRevision = preferenceRevision ?: lastPreferenceRevision
        return pendingReplacement
    }

    fun published() {
        pendingReplacement = false
    }
}

internal class NowPlayingLyricsLoadOwner(
    initialState: LoadedLyricsState,
    private val scope: CoroutineScope,
    private val stages: NowPlayingLyricsStages,
    initialSecondaryResolved: Boolean = false
) {
    var state by mutableStateOf(initialState)
        private set
    var secondaryResolved by mutableStateOf(initialSecondaryResolved)
        private set

    private var requestGeneration = 0L
    private var requestJob: Job? = null
    private var disposed = false
    private var snapshotKey: NowPlayingLyricsSnapshotKey? = null
    private val refreshState = NowPlayingLyricsRefreshState()

    /**
     * 载入歌词。
     *
     * [preferenceRevision] 变化表示歌词来源偏好被修改：此时空结果也要覆盖旧歌词，
     * 否则关闭 CC 字幕之后画面会继续显示缓存内容。
     */
    fun reload(
        request: NowPlayingLyricsLoadRequest,
        key: NowPlayingLyricsSnapshotKey? = null,
        preferenceRevision: Long? = null
    ) {
        if (disposed) return
        requestGeneration++
        val generation = requestGeneration
        requestJob?.cancel()
        val forceReplace = refreshState.beginRequest(request.song?.stableKey(), preferenceRevision)
        val cacheGeneration = stages.cacheGeneration
        snapshotKey = key?.copy(cacheGeneration = cacheGeneration)
        val currentRequest = refreshInvalidatedInitialCache(request, key, cacheGeneration)
        val cached = stages.snapshotCache?.reusable(snapshotKey)
        if (cached != null) {
            state = cached.state
            secondaryResolved = cached.secondaryResolved
            refreshState.published()
            requestJob = null
            return
        }
        requestJob = scope.launch { load(generation, currentRequest, forceReplace) }
    }

    private fun refreshInvalidatedInitialCache(
        request: NowPlayingLyricsLoadRequest,
        key: NowPlayingLyricsSnapshotKey?,
        cacheGeneration: Long
    ): NowPlayingLyricsLoadRequest {
        if (key == null || key.cacheGeneration == cacheGeneration) return request
        val refreshed = stages.readInitial(request.copy(cachedPreferredLyrics = null))
        return request.copy(cachedPreferredLyrics = refreshed.cachedPreferredLyrics)
    }

    private suspend fun load(
        generation: Long,
        request: NowPlayingLyricsLoadRequest,
        forceReplace: Boolean
    ) {
        moe.ouom.neriplayer.core.player.lan.LanSharedPresentationStore.forSong(request.song)?.let { shared ->
            fun lines(values: List<moe.ouom.neriplayer.data.model.lan.LanSharedLyricLine>) = values.map { line ->
                moe.ouom.neriplayer.data.model.lyrics.LyricEntry(line.text, line.startTimeMs, line.endTimeMs,
                    line.words?.map { moe.ouom.neriplayer.data.model.lyrics.WordTiming(it.startTimeMs, it.endTimeMs, it.charCount) }, line.translation)
            }
            val original = lines(shared.lyrics)
            val translated = lines(shared.translations)
            val romanized = lines(shared.romanized)
            publish(generation, request.song, LoadedLyricsState(null, null, null, original, translated,
                romanized, original, translated, romanized), "background", true)
            markSecondaryResolved(generation)
            return
        }
        NPLogger.d("NowPlayingLyrics", "歌词加载开始: key=${request.song?.stableKey().orEmpty()}")
        val fast = stages.readFast(request)
        if (!isCurrent(generation)) return
        publish(generation, request.song, fast.state, "fast", forceReplace)
        val background = stages.readBackground(request, fast)
        publish(generation, request.song, background, "background", forceReplace)
        markSecondaryResolved(generation)
    }

    private fun markSecondaryResolved(generation: Long) {
        if (isCurrent(generation)) secondaryResolved = true
    }

    internal fun publish(
        generation: Long,
        song: SongItem?,
        loaded: LoadedLyricsState,
        stage: String,
        forceReplace: Boolean = false
    ) {
        if (!isCurrent(generation) || keepsRestoredSnapshot(stage)) return
        val resolved = overlayConfirmedUserLyrics(song, loaded)
        val hasLyrics = resolved.hasDisplayableContent()
        if (song.hasConfirmedLyricOverride() || forceReplace ||
            shouldReplaceLyricsAfterRefresh(song != null, hasLyrics)
        ) {
            state = resolved
            if (forceReplace) refreshState.published()
        }
        rememberSnapshot(if (resolved == state) state else resolved, stage)
        logPublishedLyrics(song, stage, hasLyrics)
    }

    // 后台核查完成前保留已恢复的完整分轨，快读不能让翻译或音译暂时消失
    private fun keepsRestoredSnapshot(stage: String): Boolean =
        stage == "fast" && stages.snapshotCache?.find(snapshotKey)?.secondaryResolved == true

    private fun rememberSnapshot(resolved: LoadedLyricsState, stage: String) {
        val key = snapshotKey ?: return
        stages.snapshotCache?.publish(key, resolved, secondaryResolved = stage == "background")
    }

    private fun logPublishedLyrics(song: SongItem?, stage: String, hasLyrics: Boolean) {
        NPLogger.d("NowPlayingLyrics", "歌词结果发布: stage=$stage, key=${song?.stableKey().orEmpty()}, hasLyrics=$hasLyrics")
    }

    private fun isCurrent(generation: Long): Boolean = !disposed && requestGeneration == generation &&
        (snapshotKey == null || snapshotKey?.cacheGeneration == stages.cacheGeneration)

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
    stages: NowPlayingLyricsStages,
    initialSecondaryResolved: Boolean = false
): NowPlayingLyricsLoadOwner {
    val owner = remember(song?.stableKey(), defaultLyricSource, song?.lyricSyncRevision, song?.lyricSyncEdited, stages) {
        NowPlayingLyricsLoadOwner(initial, scope, stages, initialSecondaryResolved)
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
    val cacheGeneration = stages.cacheGeneration
    val snapshotKey = nowPlayingLyricsSnapshotKey(request, versions, cacheGeneration)
    val restored = rememberNowPlayingLyricsRestorePoint(request, versions, stages, snapshotKey)
    val owner = rememberNowPlayingLyricsOwner(request.song, request.defaultLyricSource,
        restored.state, scope, stages, restored.secondaryResolved)
    val reloadKey = NowPlayingLyricsReloadTrigger(
        nowPlayingLyricsReloadKey(request.song),
        resolveNowPlayingLyricsMediaReloadKey(request.song, request.currentMediaUrl),
        request.preferWordTimedLyrics, request.defaultLyricSource, versions
    )
    LaunchedEffect(owner, reloadKey, stages, cacheGeneration) {
        owner.reload(
            request = request.copy(cachedPreferredLyrics = restored.initial.cachedPreferredLyrics),
            key = snapshotKey,
            preferenceRevision = versions.lyricsPreferenceRevision
        )
    }
    return owner
}

private data class NowPlayingLyricsRestorePoint(
    val initial: NowPlayingInitialLyricsResult,
    val state: LoadedLyricsState,
    val secondaryResolved: Boolean
)

@Composable
private fun rememberNowPlayingLyricsRestorePoint(
    request: NowPlayingLyricsLoadRequest,
    versions: NowPlayingLyricsRefreshVersions,
    stages: NowPlayingLyricsStages,
    snapshotKey: NowPlayingLyricsSnapshotKey?
): NowPlayingLyricsRestorePoint {
    val reusable = stages.snapshotCache?.reusable(snapshotKey)
    // 完整快照可直接恢复，避免在播放页首帧再次同步解析同一份歌词
    val initial = if (reusable == null) {
        rememberNowPlayingInitialLyrics(request, versions.lyricsPreferenceRevision, stages)
    } else {
        NowPlayingInitialLyricsResult(request.cachedPreferredLyrics, reusable.state)
    }
    val cached = reusable ?: stages.snapshotCache?.find(snapshotKey)
    return NowPlayingLyricsRestorePoint(
        initial,
        cached?.state ?: initial.state,
        cached?.secondaryResolved == true
    )
}
