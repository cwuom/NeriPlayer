@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package moe.ouom.neriplayer.core.player

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.player/PlayerManager
 * Updated: 2025/8/16
 */


import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.buildBiliPartSong
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.biliCookieRepo
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.debug.playWhenReadyChangeReasonName
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.model.deriveCodecLabel
import moe.ouom.neriplayer.core.player.model.estimateBitrateKbps
import moe.ouom.neriplayer.core.player.model.inferYouTubeQualityKeyFromBitrate
import moe.ouom.neriplayer.core.player.model.mergeLocalPlaybackAudioInfoWithRemoteQuality
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.core.player.model.toPersistedSongItem
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.core.player.source.toSongItem
import moe.ouom.neriplayer.core.player.state.blockingIo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.listentogether.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.buildStableTrackKey
import moe.ouom.neriplayer.listentogether.resolvedAudioId
import moe.ouom.neriplayer.listentogether.resolvedChannelId
import moe.ouom.neriplayer.listentogether.resolvedPlaylistContextId
import moe.ouom.neriplayer.listentogether.resolvedSubAudioId
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.SearchManager
import java.io.File
import kotlin.random.Random
import androidx.core.net.toUri

enum class PlaybackCommandSource {
    LOCAL,
    REMOTE_SYNC
}

data class PlaybackCommand(
    val type: String,
    val source: PlaybackCommandSource,
    val timestampMs: Long = System.currentTimeMillis(),
    val queue: List<SongItem>? = null,
    val currentIndex: Int? = null,
    val positionMs: Long? = null,
    val force: Boolean = false
)

/**
 * PlayerManager 负责统一管理播放器生命周期、播放队列和播放状态
 * - 持有 ExoPlayer 实例并协调资源解析、缓存和音质切换
 * - 维护当前歌曲、队列、进度、循环与随机状态等 StateFlow
 * - 处理本地文件、网易云、B 站和 YouTube Music 的播放资源
 * - 管理淡入淡出、音效、歌词、收藏、持久化和设备联动
 * - 向 UI 和 Listen Together 同步播放命令与关键事件
 */
internal data class PlaybackStartPlan(
    val useFadeIn: Boolean,
    val fadeDurationMs: Long,
    val initialVolume: Float
)

internal const val RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS = 1000L
private const val PLAYBACK_PROGRESS_UPDATE_INTERVAL_MS = 80L

internal fun resolvePlaybackStartPlan(
    shouldFadeIn: Boolean,
    fadeDurationMs: Long
): PlaybackStartPlan {
    val normalizedDurationMs = fadeDurationMs.coerceAtLeast(0L)
    val useFadeIn = shouldFadeIn && normalizedDurationMs > 0L
    return PlaybackStartPlan(
        useFadeIn = useFadeIn,
        fadeDurationMs = normalizedDurationMs,
        initialVolume = if (useFadeIn) 0f else 1f
    )
}

internal fun resolveManagedPlaybackStartPlan(
    playbackFadeInEnabled: Boolean,
    playbackFadeInDurationMs: Long,
    playbackCrossfadeInDurationMs: Long,
    useTrackTransitionFade: Boolean = false,
    forceStartupProtectionFade: Boolean = false
): PlaybackStartPlan {
    val targetDurationMs = when {
        useTrackTransitionFade -> playbackCrossfadeInDurationMs
        forceStartupProtectionFade && playbackFadeInEnabled ->
            maxOf(
                playbackFadeInDurationMs,
                RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS
            )
        forceStartupProtectionFade -> RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS
        else -> playbackFadeInDurationMs
    }
    return resolvePlaybackStartPlan(
        shouldFadeIn = useTrackTransitionFade ||
            playbackFadeInEnabled ||
            forceStartupProtectionFade,
        fadeDurationMs = targetDurationMs
    )
}

internal fun shouldForceStartupProtectionFadeOnManualResume(
    isPlayerPrepared: Boolean,
    resumePositionMs: Long,
    currentMediaUrlResolvedAtMs: Long
): Boolean {
    return !isPlayerPrepared &&
        resumePositionMs > 0L &&
        currentMediaUrlResolvedAtMs <= 0L
}

internal fun shouldRunPlaybackServiceInForeground(
    hasCurrentSong: Boolean,
    resumePlaybackRequested: Boolean,
    playJobActive: Boolean,
    pendingPauseJobActive: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    playerPlaybackState: Int
): Boolean {
    if (!hasCurrentSong) return false
    return resumePlaybackRequested ||
        playJobActive ||
        pendingPauseJobActive ||
        playWhenReady ||
        isPlaying ||
        playerPlaybackState == Player.STATE_BUFFERING
}

internal fun shouldBootstrapPlaybackServiceOnAppLaunch(
    hasCurrentSong: Boolean,
    playJobActive: Boolean,
    pendingPauseJobActive: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    playerPlaybackState: Int
): Boolean {
    if (!hasCurrentSong) return false
    return playJobActive ||
        pendingPauseJobActive ||
        playWhenReady ||
        isPlaying ||
        playerPlaybackState == Player.STATE_BUFFERING
}

internal fun resolvePlaybackSoundConfigForEngine(
    baseConfig: PlaybackSoundConfig,
    listenTogetherSyncPlaybackRate: Float
): PlaybackSoundConfig {
    val normalizedBaseConfig = baseConfig.copy(
        speed = normalizePlaybackSpeed(baseConfig.speed),
        pitch = normalizePlaybackPitch(baseConfig.pitch),
        loudnessGainMb = normalizePlaybackLoudnessGainMb(baseConfig.loudnessGainMb)
    )
    val resolvedSyncRate = listenTogetherSyncPlaybackRate.coerceIn(0.95f, 1.05f)
    return normalizedBaseConfig.copy(
        speed = normalizePlaybackSpeed(normalizedBaseConfig.speed * resolvedSyncRate)
    )
}

object PlayerManager {
    const val BILI_SOURCE_TAG = "Bilibili"
    const val NETEASE_SOURCE_TAG = "Netease"
    private val NETEASE_QUALITY_FALLBACK_ORDER = listOf(
        "jymaster",
        "sky",
        "jyeffect",
        "hires",
        "lossless",
        "exhigh",
        "standard"
    )

    private var initialized = false
    private lateinit var application: Application
    private lateinit var player: ExoPlayer

    private lateinit var cache: Cache
    private var conditionalHttpFactory: ConditionalHttpDataSourceFactory? = null

    // Helper function to get localized string
    private fun getLocalizedString(resId: Int, vararg formatArgs: Any): String {
        val context = moe.ouom.neriplayer.util.LanguageManager.applyLanguage(application)
        return context.getString(resId, *formatArgs)
    }

    private fun newIoScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun newMainScope() = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var ioScope = newIoScope()
    private var mainScope = newMainScope()
    private var progressJob: Job? = null
    private var volumeFadeJob: Job? = null
    private var pendingPauseJob: Job? = null
    private var bluetoothDisconnectPauseJob: Job? = null
    private var playbackSoundPersistJob: Job? = null
    private var playbackSoundApplyJob: Job? = null
    private var pendingPlaybackSoundConfig: PlaybackSoundConfig? = null
    private var neteaseQualityRefreshJob: Job? = null
    private var youtubeQualityRefreshJob: Job? = null
    private var biliQualityRefreshJob: Job? = null

    private val localRepo: LocalPlaylistRepository
        get() = LocalPlaylistRepository.getInstance(application)

    private lateinit var stateFile: File

    private var preferredQuality: String = "exhigh"
    private var youtubePreferredQuality: String = "very_high"
    private var biliPreferredQuality: String = "high"
    private var playbackFadeInEnabled = false
    private var playbackCrossfadeNextEnabled = false
    private var playbackFadeInDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackFadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackCrossfadeInDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackCrossfadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackSoundConfig = PlaybackSoundConfig()
    private var keepLastPlaybackProgressEnabled = true
    private var keepPlaybackModeStateEnabled = true
    private var stopOnBluetoothDisconnectEnabled = true
    private var allowMixedPlaybackEnabled = false

    private var currentPlaylist: List<SongItem> = emptyList()
    private var currentIndex = -1

    /** 记录随机播放历史，支持上一首和跨轮次回退 */
    private val shuffleHistory = mutableListOf<Int>()   // 已播放过的随机索引历史
    private val shuffleFuture  = mutableListOf<Int>()   // queued next items for shuffle history
    private var shuffleBag     = mutableListOf<Int>()   // remaining shuffle candidates for current cycle

    private var consecutivePlayFailures = 0
    private const val MAX_CONSECUTIVE_FAILURES = 10
    private const val MEDIA_URL_STALE_MS = 10 * 60 * 1000L
    private const val URL_REFRESH_COOLDOWN_MS = 10 * 1000L
    private const val STATE_PERSIST_INTERVAL_MS = 15 * 1000L
    private const val DEFAULT_FADE_DURATION_MS = 500L
    private const val BLUETOOTH_DISCONNECT_CONFIRM_DELAY_MS = 1200L
    private const val AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS = 2_000L
    private const val AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS = 1_500L
    private const val PENDING_SEEK_POSITION_TOLERANCE_MS = 1_500L
    private const val QUALITY_CHANGE_REFRESH_DEBOUNCE_MS = 300L
    private const val MIN_FADE_STEPS = 4
    private const val MAX_FADE_STEPS = 30
    @Volatile
    private var urlRefreshInProgress = false
    @Volatile
    private var pendingSeekPositionMs: Long = C.TIME_UNSET
    private var lastUrlRefreshKey: String? = null
    private var lastUrlRefreshAtMs: Long = 0L
    private var currentMediaUrlResolvedAtMs: Long = 0L
    private var restoredResumePositionMs: Long = 0L
    private var restoredShouldResumePlayback = false
    private var lastStatePersistAtMs: Long = 0L
    private var lastAutoTrackAdvanceAtMs: Long = 0L
    @Volatile
    private var resumePlaybackRequested = false
    @Volatile
    private var suppressAutoResumeForCurrentSession = false
    @Volatile
    private var listenTogetherSyncPlaybackRate = 1f

    private val _currentSongFlow = MutableStateFlow<SongItem?>(null)
    val currentSongFlow: StateFlow<SongItem?> = _currentSongFlow

    private val _currentQueueFlow = MutableStateFlow<List<SongItem>>(emptyList())
    val currentQueueFlow: StateFlow<List<SongItem>> = _currentQueueFlow

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    private val _playWhenReadyFlow = MutableStateFlow(false)
    val playWhenReadyFlow: StateFlow<Boolean> = _playWhenReadyFlow

    private val _playerPlaybackStateFlow = MutableStateFlow(Player.STATE_IDLE)
    val playerPlaybackStateFlow: StateFlow<Int> = _playerPlaybackStateFlow

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionFlow: StateFlow<Long> = _playbackPositionMs

    private val _shuffleModeFlow = MutableStateFlow(false)
    val shuffleModeFlow: StateFlow<Boolean> = _shuffleModeFlow

    private val _repeatModeFlow = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatModeFlow: StateFlow<Int> = _repeatModeFlow
    private var repeatModeSetting: Int = Player.REPEAT_MODE_OFF

    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private val _playerEventFlow = MutableSharedFlow<PlayerEvent>()
    val playerEventFlow: SharedFlow<PlayerEvent> = _playerEventFlow.asSharedFlow()

    private val _playbackCommandFlow = MutableSharedFlow<PlaybackCommand>(
        extraBufferCapacity = 32
    )
    val playbackCommandFlow: SharedFlow<PlaybackCommand> = _playbackCommandFlow.asSharedFlow()

    /** 当前曲目的解析后媒体地址，供恢复播放和错误恢复使用 */
    private val _currentMediaUrl = MutableStateFlow<String?>(null)
    val currentMediaUrlFlow: StateFlow<String?> = _currentMediaUrl

    private val _currentPlaybackAudioInfo = MutableStateFlow<PlaybackAudioInfo?>(null)
    val currentPlaybackAudioInfoFlow: StateFlow<PlaybackAudioInfo?> = _currentPlaybackAudioInfo

    private val playbackEffectsController = PlaybackEffectsController()
    private val _playbackSoundState = MutableStateFlow(PlaybackSoundState())
    val playbackSoundStateFlow: StateFlow<PlaybackSoundState> = _playbackSoundState

    /** 本地歌单快照，供收藏状态和歌单选择弹窗使用 */
    private val _playlistsFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlistsFlow: StateFlow<List<LocalPlaylist>> = _playlistsFlow

    private var playJob: Job? = null
    private var playbackRequestToken = 0L
    private var lastHandledTrackEndKey: String? = null
    private var lastTrackEndHandledAtMs = 0L
    val audioLevelFlow get() = AudioReactive.level
    val beatImpulseFlow get() = AudioReactive.beat

    var biliRepo = AppContainer.biliPlaybackRepository
    var biliClient = AppContainer.biliClient
    var neteaseClient = AppContainer.neteaseClient
    var youtubeMusicPlaybackRepository = AppContainer.youtubeMusicPlaybackRepository
    var youtubeMusicClient = AppContainer.youtubeMusicClient

    val cloudMusicSearchApi = AppContainer.cloudMusicSearchApi
    val qqMusicSearchApi = AppContainer.qqMusicSearchApi
    var lrcLibClient = AppContainer.lrcLibClient

    // YouTube Music 歌词缓存，避免短时间内重复请求
    private val ytMusicLyricsCache = android.util.LruCache<String, List<LyricEntry>>(20)

    // 当前缓存上限，设置变化后会据此重建缓存
    private var currentCacheSize: Long = 1024L * 1024 * 1024

    var sleepTimerManager: SleepTimerManager = createSleepTimerManager()
        private set

    private fun createSleepTimerManager(): SleepTimerManager {
        return SleepTimerManager(
            scope = mainScope,
            onTimerExpired = {
                pause()
                sleepTimerManager.cancel()
            }
        )
    }

    fun isTransportActive(): Boolean {
        ensureInitialized()
        if (!initialized || _currentSongFlow.value == null) return false
        return resumePlaybackRequested ||
            playJob?.isActive == true ||
            pendingPauseJob?.isActive == true ||
            _playWhenReadyFlow.value ||
            _isPlayingFlow.value
    }

    fun shouldRunPlaybackServiceInForeground(): Boolean {
        ensureInitialized()
        if (!initialized || _currentSongFlow.value == null) return false
        return shouldRunPlaybackServiceInForeground(
            hasCurrentSong = _currentSongFlow.value != null,
            resumePlaybackRequested = resumePlaybackRequested,
            playJobActive = playJob?.isActive == true,
            pendingPauseJobActive = pendingPauseJob?.isActive == true,
            playWhenReady = _playWhenReadyFlow.value,
            isPlaying = _isPlayingFlow.value,
            playerPlaybackState = _playerPlaybackStateFlow.value
        )
    }

    fun shouldBootstrapPlaybackServiceOnAppLaunch(): Boolean {
        ensureInitialized()
        if (!initialized || _currentSongFlow.value == null) return false
        return shouldBootstrapPlaybackServiceOnAppLaunch(
            hasCurrentSong = _currentSongFlow.value != null,
            playJobActive = playJob?.isActive == true,
            pendingPauseJobActive = pendingPauseJob?.isActive == true,
            playWhenReady = _playWhenReadyFlow.value,
            isPlaying = _isPlayingFlow.value,
            playerPlaybackState = _playerPlaybackStateFlow.value
        )
    }

    fun isTransportBuffering(): Boolean {
        ensureInitialized()
        if (!initialized || !isTransportActive()) return false
        return playJob?.isActive == true || _playerPlaybackStateFlow.value == Player.STATE_BUFFERING
    }

    fun shouldIgnoreExternalPauseCommand(): Boolean {
        ensureInitialized()
        if (!initialized || _currentSongFlow.value == null) return false
        if (!resumePlaybackRequested) return false

        val autoAdvanceAgeMs = SystemClock.elapsedRealtime() - lastAutoTrackAdvanceAtMs
        if (autoAdvanceAgeMs !in 0L..AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS) return false

        if (playJob?.isActive == true) {
            return true
        }

        val currentPositionMs = runCatching { player.currentPosition.coerceAtLeast(0L) }
            .getOrDefault(Long.MAX_VALUE)
        val playbackState = _playerPlaybackStateFlow.value
        if (playbackState == Player.STATE_ENDED) {
            return true
        }
        if (!_playWhenReadyFlow.value) {
            return false
        }
        return when (playbackState) {
            Player.STATE_BUFFERING,
            Player.STATE_READY -> currentPositionMs <= AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS
            else -> false
        }
    }

    private fun markAutoTrackAdvance() {
        lastAutoTrackAdvanceAtMs = SystemClock.elapsedRealtime()
    }

    private fun fadeStepsFor(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (durationMs / 40L).toInt().coerceIn(MIN_FADE_STEPS, MAX_FADE_STEPS)
    }

    private fun runPlayerActionOnMainThread(action: () -> Unit) {
        if (!::player.isInitialized) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        mainScope.launch {
            if (!::player.isInitialized) return@launch
            action()
        }
    }

    private fun applyAudioFocusPolicy() {
        if (!::player.isInitialized) return
        val handleFocus = !allowMixedPlaybackEnabled
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        mainScope.launch {
            player.setAudioAttributes(attributes, handleFocus)
        }
    }

    private fun isPreparedInPlayer(): Boolean =
        player.currentMediaItem != null && (
            player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING
            )

    fun setListenTogetherSyncPlaybackRate(rate: Float) {
        ensureInitialized()
        val resolvedRate = rate.coerceIn(0.95f, 1.05f)
        if (kotlin.math.abs(listenTogetherSyncPlaybackRate - resolvedRate) < 0.001f) return
        listenTogetherSyncPlaybackRate = resolvedRate
        schedulePlaybackSoundConfigApply(
            previousConfig = playbackSoundConfig,
            newConfig = playbackSoundConfig
        )
    }

    fun resetListenTogetherSyncPlaybackRate() {
        setListenTogetherSyncPlaybackRate(1f)
    }

    fun resetForListenTogetherJoin() {
        ensureInitialized()
        if (!initialized) return
        cancelPendingPauseRequest(resetVolumeToFull = true)
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        resumePlaybackRequested = false
        restoredShouldResumePlayback = false
        restoredResumePositionMs = 0L
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        _currentMediaUrl.value = null
        currentMediaUrlResolvedAtMs = 0L
        _currentSongFlow.value = null
        _currentQueueFlow.value = emptyList()
        currentPlaylist = emptyList()
        currentIndex = -1
        consecutivePlayFailures = 0
        ioScope.launch {
            persistState(positionMs = 0L, shouldResumePlayback = false)
        }
    }

    private fun pendingSeekPositionOrNull(): Long? {
        return pendingSeekPositionMs.takeIf { it != C.TIME_UNSET }
    }

    private fun rememberPendingSeekPosition(positionMs: Long) {
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
    }

    private fun clearPendingSeekPosition() {
        pendingSeekPositionMs = C.TIME_UNSET
    }

    private fun resolveDisplayedPlaybackPosition(actualPositionMs: Long): Long {
        val actual = actualPositionMs.coerceAtLeast(0L)
        val pending = pendingSeekPositionOrNull() ?: return actual
        return if (kotlin.math.abs(actual - pending) <= PENDING_SEEK_POSITION_TOLERANCE_MS) {
            clearPendingSeekPosition()
            actual
        } else {
            pending
        }
    }

    private val gson = Gson()

    private fun isLocalSong(song: SongItem): Boolean = LocalSongSupport.isLocalSong(song, application)

    private fun isDirectStreamUrl(url: String?): Boolean {
        val normalized = url?.trim().orEmpty()
        return normalized.startsWith("https://", ignoreCase = true) ||
            normalized.startsWith("http://", ignoreCase = true)
    }

    private fun activeListenTogetherRoomState() = AppContainer.listenTogetherSessionManager.roomState.value

    private fun activeListenTogetherSessionState() = AppContainer.listenTogetherSessionManager.sessionState.value

    private fun isListenTogetherActive(): Boolean {
        return !activeListenTogetherSessionState().roomId.isNullOrBlank()
    }

    private fun isCurrentUserControllerInListenTogether(): Boolean {
        val session = activeListenTogetherSessionState()
        val room = activeListenTogetherRoomState()
        val sessionUserId = session.userUuid?.trim()?.takeIf { it.isNotBlank() }
        val controllerUserId = room?.controllerUserUuid?.trim()?.takeIf { it.isNotBlank() }
            ?: room?.controllerUserId?.trim()?.takeIf { it.isNotBlank() }
        return sessionUserId != null && controllerUserId != null && sessionUserId == controllerUserId
    }

    private fun currentListenTogetherTargetStableKey(): String? {
        val room = activeListenTogetherRoomState() ?: return null
        return room.track?.stableKey ?: room.queue.getOrNull(room.currentIndex)?.stableKey
    }

    private fun currentListenTogetherTargetStreamUrl(): String? {
        val room = activeListenTogetherRoomState() ?: return null
        return room.track?.streamUrl ?: room.queue.getOrNull(room.currentIndex)?.streamUrl
    }

    private fun SongItem.listenTogetherStableKeyOrNull(): String? {
        val channel = resolvedChannelId() ?: return null
        val audioId = resolvedAudioId() ?: return null
        return buildStableTrackKey(
            channelId = channel,
            audioId = audioId,
            subAudioId = resolvedSubAudioId(),
            playlistContextId = resolvedPlaylistContextId()
        )
    }

    private fun shouldWaitForListenTogetherAuthoritativeStream(song: SongItem): Boolean {
        if (!isListenTogetherActive()) return false
        if (isCurrentUserControllerInListenTogether()) return false
        val room = activeListenTogetherRoomState() ?: return false
        if (!room.settings.shareAudioLinks || room.roomStatus != "active") return false
        if (isDirectStreamUrl(currentListenTogetherTargetStreamUrl())) return false
        val targetStableKey = currentListenTogetherTargetStableKey() ?: return false
        val songStableKey = song.listenTogetherStableKeyOrNull() ?: return false
        return songStableKey == targetStableKey
    }

    private fun stopCurrentPlaybackForListenTogetherAwaitingStream() {
        cancelPendingPauseRequest(resetVolumeToFull = true)
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        _currentMediaUrl.value = null
        currentMediaUrlResolvedAtMs = 0L
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
    }

    private fun rejectListenTogetherControl(messageResId: Int): Boolean {
        postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageResId)))
        return true
    }

    private fun shouldBlockLocalRoomControl(commandSource: PlaybackCommandSource): Boolean {
        if (commandSource != PlaybackCommandSource.LOCAL) return false
        if (!isListenTogetherActive()) return false
        val room = activeListenTogetherRoomState()
        if (room?.roomStatus == "controller_offline" && !isCurrentUserControllerInListenTogether()) {
            return rejectListenTogetherControl(R.string.listen_together_error_controller_offline)
        }
        if (room?.settings?.allowMemberControl == false && !isCurrentUserControllerInListenTogether()) {
            return rejectListenTogetherControl(R.string.listen_together_error_member_control_disabled)
        }
        return false
    }

    private fun shouldBlockLocalSongSwitch(song: SongItem, commandSource: PlaybackCommandSource): Boolean {
        if (commandSource != PlaybackCommandSource.LOCAL) return false
        if (!isListenTogetherActive()) return false
        if (!isLocalSong(song)) return false
        return rejectListenTogetherControl(R.string.listen_together_error_local_playback_blocked)
    }

    private fun isYouTubeMusicTrack(song: SongItem): Boolean {
        return song.channelId == ListenTogetherChannels.YOUTUBE_MUSIC || isYouTubeMusicSong(song)
    }

    private fun isBiliTrack(song: SongItem): Boolean {
        return song.channelId == ListenTogetherChannels.BILIBILI ||
            song.album.startsWith(BILI_SOURCE_TAG)
    }
    private fun shouldPersistEmbeddedLyrics(song: SongItem): Boolean = !isLocalSong(song)

    private fun queueIndexOf(song: SongItem, playlist: List<SongItem> = currentPlaylist): Int {
        return playlist.indexOfFirst { it.sameIdentityAs(song) }
    }

    private fun localMediaSource(song: SongItem): String? {
        return song.localFilePath?.takeIf { it.isNotBlank() }
            ?: song.mediaUri?.takeIf { it.isNotBlank() }
    }

    private fun toPlayableLocalUrl(mediaUri: String?): String? {
        val uriString = mediaUri?.takeIf { it.isNotBlank() } ?: return null
        return if (uriString.startsWith("/")) {
            Uri.fromFile(File(uriString)).toString()
        } else {
            val parsed = runCatching { uriString.toUri() }.getOrNull() ?: return null
            when (parsed.scheme?.lowercase()) {
                null, "" -> Uri.fromFile(File(uriString)).toString()
                else -> uriString
            }
        }
    }

    private fun isReadableLocalMediaUri(mediaUri: String?): Boolean {
        val uriString = mediaUri?.takeIf { it.isNotBlank() } ?: return false
        if (uriString.startsWith("/")) {
            return File(uriString).exists()
        }

        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            null, "" -> File(uriString).exists()
            "file" -> uri.path?.let(::File)?.exists() == true
            "content", "android.resource" -> runCatching {
                application.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            else -> false
        }
    }

    private fun isReadableLocalSong(song: SongItem): Boolean {
        return isReadableLocalMediaUri(localMediaSource(song))
    }

    private fun isRestorableLocalMediaUri(mediaUri: String?): Boolean {
        val uriString = mediaUri?.takeIf { it.isNotBlank() } ?: return false
        if (uriString.startsWith("/")) {
            return File(uriString).exists()
        }

        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            null, "" -> File(uriString).exists()
            "file" -> uri.path?.let(::File)?.exists() == true
            else -> false
        }
    }

    private fun isRestorableLocalSong(song: SongItem): Boolean {
        return isRestorableLocalMediaUri(localMediaSource(song))
    }

    private fun sanitizeRestoredPlaylist(playlist: List<SongItem>): List<SongItem> {
        return playlist.filter { song ->
            !isLocalSong(song) || isRestorableLocalSong(song)
        }
    }

    private fun isCurrentSong(song: SongItem): Boolean {
        return _currentSongFlow.value?.sameIdentityAs(song) == true
    }

    private fun maybeUpdateSongDuration(song: SongItem, durationMs: Long) {
        val resolvedDurationMs = durationMs.takeIf { it > 0L } ?: return
        var changed = false

        val queueIndex = queueIndexOf(song)
        if (queueIndex != -1) {
            val queuedSong = currentPlaylist[queueIndex]
            if (queuedSong.durationMs <= 0L) {
                val updatedPlaylist = currentPlaylist.toMutableList()
                updatedPlaylist[queueIndex] = queuedSong.copy(durationMs = resolvedDurationMs)
                currentPlaylist = updatedPlaylist
                _currentQueueFlow.value = currentPlaylist
                changed = true
            }
        }

        val currentSong = _currentSongFlow.value
        if (currentSong?.sameIdentityAs(song) == true && currentSong.durationMs <= 0L) {
            _currentSongFlow.value = currentSong.copy(durationMs = resolvedDurationMs)
            changed = true
        }

        if (changed) {
            ioScope.launch { persistState() }
        }
    }

    private fun maybeBackfillCurrentSongDurationFromPlayer() {
        if (!::player.isInitialized) {
            return
        }
        val currentSong = _currentSongFlow.value ?: return
        val playerDurationMs = player.duration.takeIf { it > 0L } ?: return
        maybeUpdateSongDuration(currentSong, playerDurationMs)
    }

    fun changeCurrentPlaybackQuality(optionKey: String) {
        val normalizedKey = optionKey.trim().lowercase()
        if (normalizedKey.isBlank()) return
        val currentAudioInfo = _currentPlaybackAudioInfo.value ?: return
        if (normalizedKey == currentAudioInfo.qualityKey) return

        ioScope.launch {
            when (currentAudioInfo.source) {
                PlaybackAudioSource.NETEASE -> settingsRepo.setAudioQuality(normalizedKey)
                PlaybackAudioSource.BILIBILI -> settingsRepo.setBiliAudioQuality(normalizedKey)
                PlaybackAudioSource.YOUTUBE_MUSIC -> settingsRepo.setYouTubeAudioQuality(normalizedKey)
                PlaybackAudioSource.LOCAL -> Unit
            }
        }
    }

    fun setPlaybackSpeed(speed: Float, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(speed = normalizePlaybackSpeed(speed)),
            persist = persist
        )
    }

    fun setPlaybackPitch(pitch: Float, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(pitch = normalizePlaybackPitch(pitch)),
            persist = persist
        )
    }

    fun setPlaybackLoudnessGain(levelMb: Int, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                loudnessGainMb = normalizePlaybackLoudnessGainMb(levelMb)
            ),
            persist = persist
        )
    }

    fun setPlaybackEqualizerEnabled(enabled: Boolean, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(equalizerEnabled = enabled),
            persist = persist
        )
    }

    fun selectPlaybackEqualizerPreset(presetId: String, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                equalizerEnabled = true,
                presetId = presetId
            ),
            persist = persist
        )
    }

    fun updatePlaybackEqualizerBandLevel(
        index: Int,
        levelMb: Int,
        persist: Boolean = true
    ) {
        ensureInitialized()
        val currentBands = _playbackSoundState.value.bands
        if (index !in currentBands.indices) return
        val updatedLevels = currentBands.map { it.levelMb }.toMutableList()
        updatedLevels[index] = levelMb
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                equalizerEnabled = true,
                presetId = PlaybackEqualizerPresetId.CUSTOM,
                customBandLevelsMb = updatedLevels
            ),
            persist = persist
        )
    }

    fun resetPlaybackSoundSettings(persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            PlaybackSoundConfig(
                speed = DEFAULT_PLAYBACK_SPEED,
                pitch = DEFAULT_PLAYBACK_PITCH,
                loudnessGainMb = DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB,
                equalizerEnabled = false,
                presetId = PlaybackEqualizerPresetId.FLAT,
                customBandLevelsMb = emptyList()
            ),
            persist = persist
        )
    }

    private fun applyPlaybackSoundConfig(
        newConfig: PlaybackSoundConfig,
        persist: Boolean
    ) {
        val previousConfig = playbackSoundConfig
        playbackSoundConfig = newConfig.copy(
            speed = normalizePlaybackSpeed(newConfig.speed),
            pitch = normalizePlaybackPitch(newConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(newConfig.loudnessGainMb)
        )
        schedulePlaybackSoundConfigApply(
            previousConfig = previousConfig,
            newConfig = playbackSoundConfig
        )
        if (persist) {
            persistPlaybackSoundConfig(playbackSoundConfig)
        }
    }

    private fun schedulePlaybackSoundConfigApply(
        previousConfig: PlaybackSoundConfig,
        newConfig: PlaybackSoundConfig
    ) {
        pendingPlaybackSoundConfig = resolvePlaybackSoundConfigForEngine(
            baseConfig = newConfig,
            listenTogetherSyncPlaybackRate = listenTogetherSyncPlaybackRate
        )
        playbackSoundApplyJob?.cancel()

        val debounceHeavyEffectUpdate =
            previousConfig.equalizerEnabled != newConfig.equalizerEnabled ||
                previousConfig.presetId != newConfig.presetId ||
                previousConfig.customBandLevelsMb != newConfig.customBandLevelsMb ||
                previousConfig.loudnessGainMb != newConfig.loudnessGainMb
        val applyDelayMs = if (debounceHeavyEffectUpdate) 48L else 0L

        playbackSoundApplyJob = mainScope.launch {
            if (applyDelayMs > 0L) {
                delay(applyDelayMs)
            }
            val latestConfig = pendingPlaybackSoundConfig ?: return@launch
            pendingPlaybackSoundConfig = null
            _playbackSoundState.value = playbackEffectsController.updateConfig(latestConfig)
        }
    }

    private fun applyPlaybackSoundConfigIfChanged(newConfig: PlaybackSoundConfig) {
        val normalizedConfig = newConfig.copy(
            speed = normalizePlaybackSpeed(newConfig.speed),
            pitch = normalizePlaybackPitch(newConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(newConfig.loudnessGainMb)
        )
        if (normalizedConfig == playbackSoundConfig) return
        applyPlaybackSoundConfig(normalizedConfig, persist = false)
    }

    private fun persistPlaybackSoundConfig(config: PlaybackSoundConfig) {
        playbackSoundPersistJob?.cancel()
        playbackSoundPersistJob = ioScope.launch {
            delay(150)
            settingsRepo.setPlaybackSpeed(config.speed)
            settingsRepo.setPlaybackPitch(config.pitch)
            settingsRepo.setPlaybackLoudnessGainMb(config.loudnessGainMb)
            settingsRepo.setPlaybackEqualizerEnabled(config.equalizerEnabled)
            settingsRepo.setPlaybackEqualizerPreset(config.presetId)
            settingsRepo.setPlaybackEqualizerCustomBandLevels(config.customBandLevelsMb)
        }
    }

    private fun scheduleQualityRefresh(
        source: PlaybackAudioSource,
        reason: String
    ) {
        val targetJob = when (source) {
            PlaybackAudioSource.NETEASE -> ::neteaseQualityRefreshJob
            PlaybackAudioSource.YOUTUBE_MUSIC -> ::youtubeQualityRefreshJob
            PlaybackAudioSource.BILIBILI -> ::biliQualityRefreshJob
            PlaybackAudioSource.LOCAL -> return
        }
        targetJob.get()?.cancel()
        targetJob.set(
            ioScope.launch {
                delay(QUALITY_CHANGE_REFRESH_DEBOUNCE_MS)
                refreshCurrentSongForQualityChange(source = source, reason = reason)
            }
        )
    }

    private suspend fun refreshCurrentSongForQualityChange(
        source: PlaybackAudioSource,
        reason: String
    ) {
        val currentAudioInfo = _currentPlaybackAudioInfo.value ?: return
        if (currentAudioInfo.source != source) return
        val currentSong = _currentSongFlow.value ?: return
        if (isLocalSong(currentSong)) return

        val (positionMs, shouldResumePlaybackAfterRefresh) = withContext(Dispatchers.Main) {
            player.currentPosition.coerceAtLeast(0L) to (player.playWhenReady || player.isPlaying)
        }
        refreshCurrentSongUrl(
            resumePositionMs = positionMs,
            allowFallback = true,
            reason = reason,
            fallbackSeekPositionMs = positionMs,
            resumePlaybackAfterRefresh = shouldResumePlaybackAfterRefresh
        )
    }

    private fun postPlayerEvent(event: PlayerEvent) {
        ioScope.launch { _playerEventFlow.emit(event) }
    }

    private fun emitPlaybackCommand(
        type: String,
        source: PlaybackCommandSource,
        queue: List<SongItem>? = null,
        currentIndex: Int? = null,
        positionMs: Long? = null,
        force: Boolean = false
    ) {
        if (source != PlaybackCommandSource.LOCAL) return
        _playbackCommandFlow.tryEmit(
            PlaybackCommand(
                type = type,
                source = source,
                queue = queue,
                currentIndex = currentIndex,
                positionMs = positionMs,
                force = force
            )
        )
    }

    private fun resetTrackEndDeduplicationState() {
        lastHandledTrackEndKey = null
        lastTrackEndHandledAtMs = 0L
    }

    /**
     */
    private fun syncExoRepeatMode() {
        val desired = if (repeatModeSetting == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        if (player.repeatMode != desired) {
            player.repeatMode = desired
        }
    }

    private fun shouldResumePlaybackSnapshot(): Boolean {
        return resumePlaybackRequested || playJob?.isActive == true
    }

    /**
     */
    private fun computeCacheKey(song: SongItem): String {
        return when {
            isLocalSong(song) -> "local-${song.stableKey().hashCode()}"
            isYouTubeMusicTrack(song) -> {
                val videoId = song.audioId ?: extractYouTubeMusicVideoId(song.mediaUri).orEmpty()
                "ytmusic-$videoId-$youtubePreferredQuality-m4a"
            }
            isBiliTrack(song) -> {
            val cidPart = song.subAudioId ?: song.album.split('|').getOrNull(1)
            val biliSongId = song.audioId ?: song.id.toString()
            if (cidPart != null) {
                "bili-$biliSongId-$cidPart-$biliPreferredQuality"
            } else {
                "bili-$biliSongId-$biliPreferredQuality"
            }
            }
            else -> "netease-${song.id}-$preferredQuality"
        }
    }

    private fun buildMediaItem(
        song: SongItem,
        url: String,
        cacheKey: String,
        mimeType: String? = null
    ): MediaItem {
        val isLocalFile = url.startsWith("file://")
        return MediaItem.Builder()
            .setMediaId("${song.id}|${song.album}|${song.mediaUri.orEmpty()}")
            .setUri(url.toUri())
            .apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
                // Local files do not need a custom cache key.
                if (!isLocalFile) {
                    setCustomCacheKey(cacheKey)
                }
            }
            .build()
    }

    private fun handleTrackEnded() {
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        // Check whether the sleep timer should stop at track end.
        val isLastInPlaylist = if (player.shuffleModeEnabled) {
            shuffleFuture.isEmpty() && shuffleBag.isEmpty()
        } else {
            currentIndex >= currentPlaylist.lastIndex
        }

        if (sleepTimerManager.shouldStopOnTrackEnd(isLastInPlaylist)) {
            pause()
            sleepTimerManager.cancel()
            return
        }

        when (repeatModeSetting) {
            Player.REPEAT_MODE_ONE -> {
                markAutoTrackAdvance()
                playAtIndex(currentIndex)
            }
            Player.REPEAT_MODE_ALL -> {
                markAutoTrackAdvance()
                next(force = true)
            }
            else -> {
                if (player.shuffleModeEnabled) {
                    if (shuffleFuture.isNotEmpty() || shuffleBag.isNotEmpty()) {
                        markAutoTrackAdvance()
                        next(force = false)
                    } else {
                        stopPlaybackPreservingQueue()
                    }
                } else {
                    if (currentIndex < currentPlaylist.lastIndex) {
                        markAutoTrackAdvance()
                        next(force = false)
                    } else {
                        stopPlaybackPreservingQueue()
                    }
                }
            }
        }
    }

    fun initialize(app: Application, maxCacheSize: Long = 1024L * 1024 * 1024) {
        if (initialized) return
        application = app
        currentCacheSize = maxCacheSize

        ioScope = newIoScope()
        mainScope = newMainScope()

        runCatching {
            stateFile = File(app.filesDir, "last_playlist.json")
            blockingIo {
                keepLastPlaybackProgressEnabled = settingsRepo.keepLastPlaybackProgressFlow.first()
                keepPlaybackModeStateEnabled = settingsRepo.keepPlaybackModeStateFlow.first()
                playbackFadeInEnabled = settingsRepo.playbackFadeInFlow.first()
                playbackCrossfadeNextEnabled = settingsRepo.playbackCrossfadeNextFlow.first()
                playbackFadeInDurationMs =
                    settingsRepo.playbackFadeInDurationMsFlow.first().coerceAtLeast(0L)
                playbackFadeOutDurationMs =
                    settingsRepo.playbackFadeOutDurationMsFlow.first().coerceAtLeast(0L)
                playbackCrossfadeInDurationMs =
                    settingsRepo.playbackCrossfadeInDurationMsFlow.first().coerceAtLeast(0L)
                playbackCrossfadeOutDurationMs =
                    settingsRepo.playbackCrossfadeOutDurationMsFlow.first().coerceAtLeast(0L)
                stopOnBluetoothDisconnectEnabled =
                    settingsRepo.stopOnBluetoothDisconnectFlow.first()
                allowMixedPlaybackEnabled = settingsRepo.allowMixedPlaybackFlow.first()
                playbackSoundConfig = PlaybackSoundConfig(
                    speed = settingsRepo.playbackSpeedFlow.first(),
                    pitch = settingsRepo.playbackPitchFlow.first(),
                    loudnessGainMb = settingsRepo.playbackLoudnessGainMbFlow.first(),
                    equalizerEnabled = settingsRepo.playbackEqualizerEnabledFlow.first(),
                    presetId = settingsRepo.playbackEqualizerPresetFlow.first(),
                    customBandLevelsMb = settingsRepo.playbackEqualizerCustomBandLevelsFlow.first()
                )
            }
            // Base HTTP client shared by playback data sources.
            val okHttpClient = AppContainer.sharedOkHttpClient
            val upstreamFactory: HttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
            val conditionalFactory = ConditionalHttpDataSourceFactory(
                upstreamFactory,
                biliCookieRepo,
                AppContainer.youtubeAuthRepo
            )
            conditionalHttpFactory = conditionalFactory

            val finalDataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (maxCacheSize > 0) {
                val cacheDir = File(app.cacheDir, "media_cache")
                val dbProvider = StandaloneDatabaseProvider(app)

                cache = SimpleCache(
                    cacheDir,
                    LeastRecentlyUsedCacheEvictor(maxCacheSize),
                    dbProvider
                )

                val cacheDsFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(conditionalFactory)
                    .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                    
                androidx.media3.datasource.DefaultDataSource.Factory(app, cacheDsFactory)
            } else {
                NPLogger.d("NERI-Player", "Cache disabled by user setting (size=0).")
                androidx.media3.datasource.DefaultDataSource.Factory(app, conditionalFactory)
            }

            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(finalDataSourceFactory, extractorsFactory)

            val renderersFactory = ReactiveRenderersFactory(app)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            player = ExoPlayer.Builder(app, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    setWakeMode(C.WAKE_MODE_NETWORK)
                }
            _playbackSoundState.value = playbackEffectsController.attachPlayer(player)
            applyPlaybackSoundConfig(playbackSoundConfig, persist = false)
            applyAudioFocusPolicy()
            _playWhenReadyFlow.value = player.playWhenReady
            _playerPlaybackStateFlow.value = player.playbackState

            val audioOffload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                )
                .build()

            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(audioOffload)
                .build()

            player.repeatMode = Player.REPEAT_MODE_OFF

            youtubeMusicPlaybackRepository.warmBootstrapAsync()

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    NPLogger.e("NERI-Player", "onPlayerError: ${error.errorCodeName}", error)

                    val currentUrl = _currentMediaUrl.value
                    val isOfflineCache = currentUrl?.startsWith("http://offline.cache/") == true

                    val cause = error.cause
                    if (shouldAttemptUrlRefresh(error, _currentSongFlow.value, isOfflineCache)) {
                        val shouldBypassRefreshCooldown = pendingSeekPositionOrNull() != null &&
                            YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
                                _currentSongFlow.value,
                                currentUrl
                            )
                        val resumePositionMs = pendingSeekPositionOrNull()
                            ?: player.currentPosition.coerceAtLeast(0L)
                        val resumePlaybackAfterRefresh = player.playWhenReady || player.isPlaying
                        refreshCurrentSongUrl(
                            resumePositionMs = resumePositionMs,
                            allowFallback = false,
                            reason = "playback_error_${error.errorCodeName}",
                            bypassCooldown = shouldBypassRefreshCooldown,
                            fallbackSeekPositionMs = resumePositionMs,
                            resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                        )
                        return
                    }

                    consecutivePlayFailures++

                    val msg = when {
                        isOfflineCache -> {
                            NPLogger.w(
                                "NERI-Player",
                                "Offline cached playback failed, pausing current song and waiting for recovery."
                            )
                            getLocalizedString(R.string.player_playback_failed_with_code, error.errorCodeName)
                        }
                        cause?.message?.contains("no protocol: null", ignoreCase = true) == true ->
                            getLocalizedString(R.string.player_playback_invalid_url)
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                            getLocalizedString(R.string.player_playback_network_error)
                        else ->
                            getLocalizedString(R.string.player_playback_failed_with_code, error.errorCodeName)
                    }

                    postPlayerEvent(PlayerEvent.ShowError(msg))

                    if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
                        stopPlaybackPreservingQueue(clearMediaUrl = true)
                        return
                    }

                    if (isOfflineCache) {
                        pause()
                    } else {
                        mainScope.launch { handleTrackEnded() }
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    _playerPlaybackStateFlow.value = state
                    if (state == Player.STATE_READY) {
                        maybeBackfillCurrentSongDurationFromPlayer()
                    }
                    if (state == Player.STATE_ENDED) {
                        handleTrackEndedIfNeeded(source = "playback_state_changed")
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlayingFlow.value = isPlaying
                    if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                    val positionMs = player.currentPosition.coerceAtLeast(0L)
                    val shouldResumePlayback = shouldResumePlaybackSnapshot()
                    ioScope.launch {
                        persistState(
                            positionMs = positionMs,
                            shouldResumePlayback = shouldResumePlayback
                        )
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    _playWhenReadyFlow.value = playWhenReady
                    if (!playWhenReady) {
                        val stackHint = Throwable().stackTrace.take(6).joinToString(" <- ") {
                            "${it.fileName}:${it.lineNumber}"
                        }
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "playWhenReady=false, reason=${playWhenReadyChangeReasonName(reason)}, state=${playbackStateName(player.playbackState)}, mediaId=${player.currentMediaItem?.mediaId}, stack=[$stackHint]"
                        )
                    }
                    if (
                        !playWhenReady &&
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                        player.playbackState == Player.STATE_ENDED
                    ) {
                        handleTrackEndedIfNeeded(source = "play_when_ready_end_of_item")
                    }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _shuffleModeFlow.value = shuffleModeEnabled
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    syncExoRepeatMode()
                    _repeatModeFlow.value = repeatModeSetting
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    _playbackSoundState.value =
                        playbackEffectsController.onAudioSessionIdChanged(audioSessionId)
                }
            })

        player.playWhenReady = false

        ioScope.launch {
            settingsRepo.audioQualityFlow.collect { q ->
                val previousQuality = preferredQuality
                preferredQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.NETEASE,
                        reason = "netease_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.youtubeAudioQualityFlow.collect { q ->
                val previousQuality = youtubePreferredQuality
                youtubePreferredQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.YOUTUBE_MUSIC,
                        reason = "youtube_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.biliAudioQualityFlow.collect { q ->
                val previousQuality = biliPreferredQuality
                biliPreferredQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.BILIBILI,
                        reason = "bili_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeInFlow.collect { enabled -> playbackFadeInEnabled = enabled }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeNextFlow.collect { enabled ->
                playbackCrossfadeNextEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeInDurationMsFlow.collect { duration ->
                playbackFadeInDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeOutDurationMsFlow.collect { duration ->
                playbackFadeOutDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeInDurationMsFlow.collect { duration ->
                playbackCrossfadeInDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeOutDurationMsFlow.collect { duration ->
                playbackCrossfadeOutDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackSpeedFlow.collect { speed ->
                applyPlaybackSoundConfigIfChanged(playbackSoundConfig.copy(speed = speed))
            }
        }
        ioScope.launch {
            settingsRepo.playbackPitchFlow.collect { pitch ->
                applyPlaybackSoundConfigIfChanged(playbackSoundConfig.copy(pitch = pitch))
            }
        }
        ioScope.launch {
            settingsRepo.playbackLoudnessGainMbFlow.collect { levelMb ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(loudnessGainMb = levelMb)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerEnabledFlow.collect { enabled ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(equalizerEnabled = enabled)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerPresetFlow.collect { presetId ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(presetId = presetId)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerCustomBandLevelsFlow.collect { levels ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(customBandLevelsMb = levels)
                )
            }
        }
        ioScope.launch {
            settingsRepo.keepLastPlaybackProgressFlow.collect { enabled ->
                val changed = keepLastPlaybackProgressEnabled != enabled
                keepLastPlaybackProgressEnabled = enabled
                if (changed && initialized && currentPlaylist.isNotEmpty()) {
                    persistState()
                }
            }
        }
        ioScope.launch {
            settingsRepo.keepPlaybackModeStateFlow.collect { enabled ->
                val changed = keepPlaybackModeStateEnabled != enabled
                keepPlaybackModeStateEnabled = enabled
                if (changed && initialized && currentPlaylist.isNotEmpty()) {
                    persistState()
                }
            }
        }
        ioScope.launch {
            settingsRepo.stopOnBluetoothDisconnectFlow.collect { enabled ->
                stopOnBluetoothDisconnectEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.allowMixedPlaybackFlow.collect { enabled ->
                allowMixedPlaybackEnabled = enabled
                applyAudioFocusPolicy()
            }
        }

        ioScope.launch {
            localRepo.playlists.collect { repoLists ->
                _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(repoLists)
            }
        }

        setupAudioDeviceCallback()
        restoreState()

        sleepTimerManager = createSleepTimerManager()

        initialized = true
        NPLogger.d("NERI-Player", "PlayerManager initialized with cache size: $maxCacheSize")
        }.onFailure { e ->
            NPLogger.e("NERI-Player", "PlayerManager initialize failed", e)
            runCatching { conditionalHttpFactory?.close() }
            conditionalHttpFactory = null
            runCatching { if (::player.isInitialized) player.release() }
            runCatching { _playbackSoundState.value = playbackEffectsController.release() }
            runCatching { if (::cache.isInitialized) cache.release() }
            runCatching { mainScope.cancel() }
            runCatching { ioScope.cancel() }
            initialized = false
        }
    }

    suspend fun clearCache(clearAudio: Boolean = true, clearImage: Boolean = true): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            var apiRemovedCount = 0
            var physicalDeletedCount = 0
            var totalSpaceFreed = 0L

            try {
                // Clear audio cache files and indexed spans.
                if (clearAudio) {
                    if (::cache.isInitialized) {
                        val keysSnapshot = HashSet(cache.keys)
                        keysSnapshot.forEach { key ->
                            try {
                                val resource = cache.getCachedSpans(key)
                                resource.forEach { totalSpaceFreed += it.length }
                                cache.removeResource(key)
                                apiRemovedCount++
                            } catch (_: Exception) {
                            }
                        }
                    }

                    val cacheDir = File(application.cacheDir, "media_cache")
                    if (cacheDir.exists() && cacheDir.isDirectory) {
                        val files = cacheDir.listFiles() ?: emptyArray()
                        files.forEach { file ->
                            if (file.isFile && file.name.endsWith(".exo") && file.delete()) {
                                physicalDeletedCount++
                            }
                        }
                    }
                }

                if (clearImage) {
                    val imageCacheDir = File(application.cacheDir, "image_cache")
                    if (imageCacheDir.exists() && imageCacheDir.isDirectory) {
                        val deleted = imageCacheDir.deleteRecursively()
                        if (deleted) {
                            imageCacheDir.mkdirs()
                        }
                    }
                }

                NPLogger.d("NERI-Player", "Cache Clear: API removed $apiRemovedCount keys, Physically deleted $physicalDeletedCount .exo files.")

                val msg = if (physicalDeletedCount > 0 || apiRemovedCount > 0 || clearImage) {
                    getLocalizedString(R.string.cache_clear_complete)
                } else {
                    getLocalizedString(R.string.settings_cache_empty)
                }
                Pair(true, msg)
            } catch (e: Exception) {
                NPLogger.e("NERI-Player", "Clear cache failed", e)
                Pair(false, getLocalizedString(R.string.toast_cache_clear_error, e.message ?: "Unknown"))
            }
        }
    }

    private fun ensureInitialized() {
        if (!initialized && ::application.isInitialized) {
            initialize(application)
        }
    }

    private fun setupAudioDeviceCallback() {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _currentAudioDevice.value = getCurrentAudioDevice(audioManager)
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                handleDeviceChange(audioManager)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                handleDeviceChange(audioManager)
            }
        }
        audioDeviceCallback = deviceCallback
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    fun handleAudioBecomingNoisy(): Boolean {
        ensureInitialized()
        if (!initialized) return false
        if (!_isPlayingFlow.value) return false
        val currentDevice = _currentAudioDevice.value
        if (currentDevice == null || !isHeadsetLikeOutput(currentDevice.type)) {
            return false
        }
        if (requiresDisconnectConfirmation(currentDevice.type)) {
            if (!shouldPauseForBluetoothDisconnect(currentDevice, null)) {
                return false
            }
            schedulePauseForBluetoothDisconnect(
                previousDevice = currentDevice,
                reason = "becoming_noisy"
            )
            return true
        }
        NPLogger.d("NERI-PlayerManager", "Audio becoming noisy, pausing playback immediately.")
        pause()
        return true
    }

    private fun handleDeviceChange(audioManager: AudioManager) {
        val previousDevice = _currentAudioDevice.value
        val newDevice = getCurrentAudioDevice(audioManager)
        _currentAudioDevice.value = newDevice
        if (shouldPauseForBluetoothDisconnect(previousDevice, newDevice)) {
            schedulePauseForBluetoothDisconnect(
                previousDevice = previousDevice,
                reason = "device_changed_to_${newDevice.type}"
            )
        } else if (shouldPauseForImmediateOutputDisconnect(previousDevice, newDevice)) {
            bluetoothDisconnectPauseJob?.cancel()
            bluetoothDisconnectPauseJob = null
            NPLogger.d(
                "NERI-PlayerManager",
                "Detected immediate output disconnect (${previousDevice?.type} -> ${newDevice.type}), pausing playback."
            )
            pause()
        } else if (newDevice.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            bluetoothDisconnectPauseJob?.cancel()
            bluetoothDisconnectPauseJob = null
        }
    }

    private fun shouldPauseForBluetoothDisconnect(
        previousDevice: AudioDevice?,
        newDevice: AudioDevice?
    ): Boolean {
        if (!stopOnBluetoothDisconnectEnabled) return false
        if (!_isPlayingFlow.value) return false
        if (previousDevice == null || !requiresDisconnectConfirmation(previousDevice.type)) return false
        return newDevice == null || newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private fun schedulePauseForBluetoothDisconnect(previousDevice: AudioDevice?, reason: String) {
        if (previousDevice == null || !requiresDisconnectConfirmation(previousDevice.type)) return
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = mainScope.launch {
            delay(BLUETOOTH_DISCONNECT_CONFIRM_DELAY_MS)
            if (!stopOnBluetoothDisconnectEnabled || !_isPlayingFlow.value) {
                bluetoothDisconnectPauseJob = null
                return@launch
            }

            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val confirmedDevice = getCurrentAudioDevice(audioManager)
            _currentAudioDevice.value = confirmedDevice
            if (confirmedDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Confirmed bluetooth disconnect ($reason), pausing playback."
                )
                pause()
            } else {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Ignored transient bluetooth route change ($reason): ${confirmedDevice.type}"
                )
            }
            bluetoothDisconnectPauseJob = null
        }
    }

    private fun getCurrentAudioDevice(audioManager: AudioManager): AudioDevice {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bluetoothDevice = devices.firstOrNull { isBluetoothOutputType(it.type) }
        if (bluetoothDevice != null) {
            return try {
                AudioDevice(
                    name = bluetoothDevice.productName.toString()
                        .ifBlank { getLocalizedString(R.string.device_bluetooth_headset) },
                    type = bluetoothDevice.type,
                    icon = Icons.Default.BluetoothAudio
                )
            } catch (_: SecurityException) {
                AudioDevice(
                    getLocalizedString(R.string.device_bluetooth_headset),
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    Icons.Default.BluetoothAudio
                )
            }
        }
        val wiredHeadset = devices.firstOrNull { isWiredOutputType(it.type) }
        if (wiredHeadset != null) {
            return AudioDevice(
                getLocalizedString(R.string.device_wired_headset),
                wiredHeadset.type,
                Icons.Default.Headset
            )
        }
        return AudioDevice(
            getLocalizedString(R.string.device_speaker),
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            Icons.Default.SpeakerGroup
        )
    }

    private fun isBluetoothOutputType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
    }

    private fun isWiredOutputType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun isHeadsetLikeOutput(type: Int): Boolean {
        return isBluetoothOutputType(type) || isWiredOutputType(type)
    }

    private fun requiresDisconnectConfirmation(type: Int): Boolean {
        return isBluetoothOutputType(type)
    }

    private fun shouldPauseForImmediateOutputDisconnect(
        previousDevice: AudioDevice?,
        newDevice: AudioDevice?
    ): Boolean {
        if (previousDevice == null || !isWiredOutputType(previousDevice.type)) return false
        if (!_isPlayingFlow.value) return false
        return newDevice == null || newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private fun cancelVolumeFade(resetToFull: Boolean = false) {
        volumeFadeJob?.cancel()
        volumeFadeJob = null
        if (resetToFull && ::player.isInitialized) {
            runPlayerActionOnMainThread {
                runCatching { player.volume = 1f }
            }
        }
    }

    private fun cancelPendingPauseRequest(resetVolumeToFull: Boolean = false) {
        val hadPendingPause = pendingPauseJob?.isActive == true
        pendingPauseJob?.cancel()
        pendingPauseJob = null
        if (resetVolumeToFull && hadPendingPause && ::player.isInitialized) {
            runPlayerActionOnMainThread {
                if (::player.isInitialized) {
                    player.volume = 1f
                }
            }
        }
    }

    private fun preparePlayerForManagedStart(plan: PlaybackStartPlan) {
        if (!::player.isInitialized) return
        cancelVolumeFade()
        player.playWhenReady = false
        player.volume = plan.initialVolume
    }

    private suspend fun fadeOutCurrentPlaybackIfNeeded(
        enabled: Boolean,
        fadeOutDurationMs: Long = playbackCrossfadeOutDurationMs
    ) {
        if (!enabled || !::player.isInitialized) {
            return
        }

        val shouldFade = _isPlayingFlow.value
        if (!shouldFade) {
            return
        }

        val durationMs = fadeOutDurationMs.coerceAtLeast(0L)
        if (durationMs <= 0L) {
            return
        }

        cancelVolumeFade()
        val startVolume = withContext(Dispatchers.Main) { player.volume.coerceIn(0f, 1f) }
        if (startVolume <= 0f) {
            return
        }

        val steps = fadeStepsFor(durationMs)
        if (steps <= 0) return
        val stepDelay = (durationMs / steps).coerceAtLeast(1L)
        repeat(steps) { step ->
            val fraction = (step + 1).toFloat() / steps
            withContext(Dispatchers.Main) {
                if (!::player.isInitialized) {
                    return@withContext
                }
                player.volume = (startVolume * (1f - fraction)).coerceAtLeast(0f)
            }
            delay(stepDelay)
        }

        withContext(Dispatchers.Main) {
            if (::player.isInitialized) {
                player.volume = 0f
            }
        }
    }

    private fun startPlayerPlaybackWithFade(plan: PlaybackStartPlan) {
        cancelVolumeFade()
        runPlayerActionOnMainThread {
            if (!::player.isInitialized) return@runPlayerActionOnMainThread
            player.volume = plan.initialVolume
            player.playWhenReady = true
            player.play()
        }
        if (!plan.useFadeIn) {
            return
        }

        val steps = fadeStepsFor(plan.fadeDurationMs)
        if (steps <= 0) return
        val stepDelay = (plan.fadeDurationMs / steps).coerceAtLeast(1L)
        volumeFadeJob = mainScope.launch {
            repeat(steps) { step ->
                delay(stepDelay)
                if (!::player.isInitialized) return@launch
                player.volume = ((step + 1).toFloat() / steps).coerceAtMost(1f)
            }
            if (::player.isInitialized) {
                player.volume = 1f
            }
            volumeFadeJob = null
        }
    }

    private fun resolveCurrentPlaybackStartPlan(
        useTrackTransitionFade: Boolean = false,
        forceStartupProtectionFade: Boolean = false
    ): PlaybackStartPlan {
        return resolveManagedPlaybackStartPlan(
            playbackFadeInEnabled = playbackFadeInEnabled,
            playbackFadeInDurationMs = playbackFadeInDurationMs,
            playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
            useTrackTransitionFade = useTrackTransitionFade,
            forceStartupProtectionFade = forceStartupProtectionFade
        )
    }

    fun playPlaylist(
        songs: List<SongItem>,
        startIndex: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        if (songs.isEmpty()) {
            NPLogger.w("NERI-Player", "playPlaylist called with EMPTY list")
            return
        }
        val targetSong = songs.getOrNull(startIndex.coerceIn(0, songs.lastIndex)) ?: songs.first()
        if (shouldBlockLocalRoomControl(commandSource) || shouldBlockLocalSongSwitch(targetSong, commandSource)) {
            return
        }
        suppressAutoResumeForCurrentSession = false
        consecutivePlayFailures = 0
        currentPlaylist = songs
        _currentQueueFlow.value = currentPlaylist
        currentIndex = startIndex.coerceIn(0, songs.lastIndex)

        shuffleHistory.clear()
        shuffleFuture.clear()
        if (player.shuffleModeEnabled) {
            rebuildShuffleBag(excludeIndex = currentIndex)
        } else {
            shuffleBag.clear()
        }

        maybeWarmCurrentAndUpcomingYouTubeMusic(currentIndex)
        playAtIndex(currentIndex, commandSource = commandSource)
        emitPlaybackCommand(
            type = "PLAY_PLAYLIST",
            source = commandSource,
            queue = currentPlaylist,
            currentIndex = currentIndex
        )
        ioScope.launch {
            persistState()
        }
    }

    private fun rebuildShuffleBag(excludeIndex: Int? = null) {
        shuffleBag = currentPlaylist.indices.toMutableList()
        if (excludeIndex != null) shuffleBag.remove(excludeIndex)
        shuffleBag.shuffle()
    }

    private fun playAtIndex(
        index: Int,
        resumePositionMs: Long = 0L,
        useTrackTransitionFade: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
        forceStartupProtectionFade: Boolean = false
    ) {
        if (currentPlaylist.isEmpty() || index !in currentPlaylist.indices) {
            NPLogger.w("NERI-Player", "playAtIndex called with invalid index: $index")
            return
        }

        if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
            NPLogger.e("NERI-PlayerManager", "Too many consecutive playback failures: $consecutivePlayFailures")
            mainScope.launch {
                Toast.makeText(
                    application,
                    getLocalizedString(R.string.toast_playback_stopped),
                    Toast.LENGTH_SHORT
                ).show()
            }
            stopPlaybackPreservingQueue(clearMediaUrl = true)
            return
        }

        val song = currentPlaylist[index]
        cancelPendingPauseRequest()
        _currentSongFlow.value = song
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        currentMediaUrlResolvedAtMs = 0L
        val shouldAwaitAuthoritativeStream =
            commandSource == PlaybackCommandSource.REMOTE_SYNC &&
                shouldWaitForListenTogetherAuthoritativeStream(song)
        if (shouldAwaitAuthoritativeStream) {
            stopCurrentPlaybackForListenTogetherAwaitingStream()
        }
        resumePlaybackRequested = true
        restoredShouldResumePlayback = false
        restoredResumePositionMs = 0L
        ioScope.launch {
            persistState(positionMs = resumePositionMs.coerceAtLeast(0L), shouldResumePlayback = true)
        }

        if (player.shuffleModeEnabled) {
            shuffleBag.remove(index)
        }

        playJob?.cancel()
        playbackRequestToken += 1
        val requestToken = playbackRequestToken
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        maybeWarmCurrentAndUpcomingYouTubeMusic(index)
        playJob = ioScope.launch {
            val result = resolveSongUrl(song)
            if (requestToken != playbackRequestToken || !isActive) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "播放请求已过期，跳过本次 URL 解析结果: song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
                )
                return@launch
            }

            when (result) {
                is SongUrlResult.Success -> {
                    consecutivePlayFailures = 0

                    result.noticeMessage?.let { message ->
                        postPlayerEvent(PlayerEvent.ShowError(message))
                    }
                    maybeUpdateSongDuration(song, result.durationMs ?: 0L)
                    val cacheKey = computeCacheKey(song)
                    NPLogger.d("NERI-PlayerManager", "Using custom cache key: $cacheKey for song: ${song.name}")
                    invalidateMismatchedCachedResource(
                        cacheKey = cacheKey,
                        expectedContentLength = result.expectedContentLength
                    )

                    val mediaItem = buildMediaItem(
                        _currentSongFlow.value ?: song,
                        result.url,
                        cacheKey,
                        result.mimeType
                    )

                    _currentMediaUrl.value = result.url
                    _currentPlaybackAudioInfo.value = result.audioInfo
                    currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
                    persistState(
                        positionMs = resumePositionMs.coerceAtLeast(0L),
                        shouldResumePlayback = true
                    )
                    if (requestToken != playbackRequestToken || !isActive) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "播放请求已过期，跳过媒体项装载: song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
                        )
                        return@launch
                    }

                    fadeOutCurrentPlaybackIfNeeded(
                        enabled = useTrackTransitionFade,
                        fadeOutDurationMs = playbackCrossfadeOutDurationMs
                    )
                    if (requestToken != playbackRequestToken || !isActive) {
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        if (requestToken != playbackRequestToken) {
                            return@withContext
                        }
                        val startPlan = resolveCurrentPlaybackStartPlan(
                            useTrackTransitionFade = useTrackTransitionFade,
                            forceStartupProtectionFade = forceStartupProtectionFade &&
                                resumePositionMs > 0L
                        )
                        preparePlayerForManagedStart(startPlan)
                        resetTrackEndDeduplicationState()
                        player.setMediaItem(mediaItem)
                        syncExoRepeatMode()
                        if (resumePositionMs > 0L) {
                            player.seekTo(resumePositionMs)
                            _playbackPositionMs.value = resumePositionMs
                        }
                        player.prepare()
                        startPlayerPlaybackWithFade(startPlan)
                    }
                    maybeAutoMatchBiliMetadata(song, requestToken)
                    maybeWarmCurrentAndUpcomingYouTubeMusic(index)
                }
                SongUrlResult.WaitingForAuthoritativeStream -> {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Waiting for authoritative listen-together stream: song=${song.name}, stableKey=${song.listenTogetherStableKeyOrNull()}"
                    )
                    resumePlaybackRequested = false
                    ioScope.launch {
                        persistState(
                            positionMs = resumePositionMs.coerceAtLeast(0L),
                            shouldResumePlayback = false
                        )
                    }
                }
                is SongUrlResult.RequiresLogin -> {
                    NPLogger.w("NERI-PlayerManager", "Requires login to play: id=${song.id}, source=${song.album}")
                    postPlayerEvent(
                        PlayerEvent.ShowLoginPrompt(
                            getLocalizedString(R.string.player_playback_login_required)
                        )
                    )
                    withContext(Dispatchers.Main) { next() }
                }
                is SongUrlResult.Failure -> {
                    NPLogger.e("NERI-PlayerManager", "获取播放地址失败，跳过当前歌曲: id=${song.id}, source=${song.album}")
                    consecutivePlayFailures++
                    withContext(Dispatchers.Main) { next() } // 失败时继续尝试下一首
                }
            }
        }
    }

    private fun maybeAutoMatchBiliMetadata(song: SongItem, requestToken: Long) {
        if (!isBiliTrack(song)) return
        if (song.matchedSongId != null || !song.matchedLyric.isNullOrEmpty()) return
        if (song.customName != null || song.customArtist != null || song.customCoverUrl != null) return

        ioScope.launch {
            val currentSong = _currentSongFlow.value ?: return@launch
            if (requestToken != playbackRequestToken || !currentSong.sameIdentityAs(song)) {
                return@launch
            }

            val candidate = SearchManager.findBestSearchCandidate(song.name, song.artist) ?: return@launch
            val latestSong = _currentSongFlow.value ?: return@launch
            if (requestToken != playbackRequestToken || !latestSong.sameIdentityAs(song)) {
                return@launch
            }

            replaceMetadataFromSearch(latestSong, candidate, isAuto = true)
        }
    }

    private fun maybeWarmCurrentAndUpcomingYouTubeMusic(currentSongIndex: Int) {
        val currentVideoId = currentPlaylist.getOrNull(currentSongIndex)
            ?.let { extractYouTubeMusicVideoId(it.mediaUri) }
        val nextVideoId = currentPlaylist.getOrNull(currentSongIndex + 1)
            ?.let { extractYouTubeMusicVideoId(it.mediaUri) }
        if (currentVideoId == null && nextVideoId == null) {
            return
        }
        runCatching {
            youtubeMusicPlaybackRepository.warmBootstrapAsync()
        }.onFailure { error ->
            NPLogger.w(
                "NERI-PlayerManager",
                "Warm YouTube Music bootstrap failed: ${error.message}"
            )
        }
        currentVideoId?.let { videoId ->
            runCatching {
                youtubeMusicPlaybackRepository.kickoffPlayableAudioPrefetch(
                    videoId = videoId,
                    preferredQualityOverride = youtubePreferredQuality,
                    requireDirect = true,
                    preferM4a = true
                )
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Warm current YouTube Music stream failed for $videoId: ${error.message}"
                )
            }
        }
        nextVideoId?.let { videoId ->
            runCatching {
                youtubeMusicPlaybackRepository.kickoffPlayableAudioPrefetch(
                    videoId = videoId,
                    preferredQualityOverride = youtubePreferredQuality,
                    requireDirect = true,
                    preferM4a = true
                )
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Prefetch next YouTube Music stream failed for $videoId: ${error.message}"
                )
            }
        }
    }

    private suspend fun resolveSongUrl(
        song: SongItem,
        forceRefresh: Boolean = false
    ): SongUrlResult {
        if (shouldWaitForListenTogetherAuthoritativeStream(song)) {
            return SongUrlResult.WaitingForAuthoritativeStream
        }
        if (isDirectStreamUrl(song.streamUrl)) {
            return SongUrlResult.Success(song.streamUrl.orEmpty())
        }
        if (isLocalSong(song)) {
            val localMediaUri = localMediaSource(song)
            if (localMediaUri != null && isReadableLocalMediaUri(localMediaUri)) {
                return SongUrlResult.Success(
                    url = toPlayableLocalUrl(localMediaUri) ?: localMediaUri,
                    audioInfo = buildLocalPlaybackAudioInfo(song)
                )
            }
            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
            return SongUrlResult.Failure
        }

        // Prefer locally downloaded files before remote resolution.
        val localResult = checkLocalCache(song)
        if (localResult != null) return localResult
        val cacheKey = computeCacheKey(song)
        val hasCachedData = checkExoPlayerCache(cacheKey)
        val result = when {
            isYouTubeMusicTrack(song) -> getYouTubeMusicAudioUrl(
                song = song,
                suppressError = hasCachedData,
                forceRefresh = forceRefresh
            )
            isBiliTrack(song) -> getBiliAudioUrl(song, suppressError = hasCachedData)
            else -> getNeteaseSongUrl(song, suppressError = hasCachedData)
        }

        return if (result is SongUrlResult.Failure && hasCachedData && !isYouTubeMusicTrack(song)) {
            NPLogger.d("NERI-PlayerManager", "远端解析失败但缓存完整，回退到离线缓存地址: $cacheKey")
            // Use a synthetic offline URL so ExoPlayer can hit the cache by key.
            val fallbackAudioInfo = _currentPlaybackAudioInfo.value
            SongUrlResult.Success(
                url = "http://offline.cache/$cacheKey",
                audioInfo = fallbackAudioInfo
            )
        } else {
            result
        }
    }

    private fun shouldAttemptUrlRefresh(
        error: PlaybackException,
        song: SongItem?,
        isOfflineCache: Boolean
    ): Boolean {
        if (song == null || isOfflineCache) return false
        if (isLocalSong(song)) return false
        return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }

    private fun resumePlaybackFallback(
        seekPositionMs: Long?,
        resumePlaybackAfterRefresh: Boolean
    ) {
        mainScope.launch {
            val resolvedSeekPositionMs = seekPositionMs?.coerceAtLeast(0L)
            if (resolvedSeekPositionMs != null) {
                player.seekTo(resolvedSeekPositionMs)
                _playbackPositionMs.value = resolvedSeekPositionMs
            }
            player.playWhenReady = resumePlaybackAfterRefresh
            if (resumePlaybackAfterRefresh) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    private fun refreshCurrentSongUrl(
        resumePositionMs: Long,
        allowFallback: Boolean,
        reason: String,
        bypassCooldown: Boolean = false,
        fallbackSeekPositionMs: Long? = null,
        resumePlaybackAfterRefresh: Boolean = true,
        resumedPlaybackCommandSource: PlaybackCommandSource? = null
    ) {
        val song = _currentSongFlow.value ?: return
        if (isLocalSong(song)) return
        if (urlRefreshInProgress) {
            if (allowFallback) {
                resumePlaybackFallback(
                    seekPositionMs = fallbackSeekPositionMs,
                    resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                )
            }
            return
        }

        val cacheKey = computeCacheKey(song)
        val now = SystemClock.elapsedRealtime()
        if (!bypassCooldown && lastUrlRefreshKey == cacheKey && now - lastUrlRefreshAtMs < URL_REFRESH_COOLDOWN_MS) {
            if (allowFallback) {
                resumePlaybackFallback(
                    seekPositionMs = fallbackSeekPositionMs,
                    resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                )
            } else {
                clearPendingSeekPosition()
                consecutivePlayFailures++
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
                if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
                    mainScope.launch { stopPlaybackPreservingQueue(clearMediaUrl = true) }
                } else {
                    mainScope.launch { handleTrackEnded() }
                }
            }
            return
        }

        urlRefreshInProgress = true
        lastUrlRefreshKey = cacheKey
        lastUrlRefreshAtMs = now

        ioScope.launch {
            try {
                NPLogger.d("NERI-PlayerManager", "Refreshing stream url ($reason): $cacheKey")
                val result = resolveSongUrl(
                    song = song,
                    forceRefresh = isYouTubeMusicTrack(song)
                )
                if (result is SongUrlResult.Success &&
                    _currentSongFlow.value?.sameIdentityAs(song) == true
                ) {
                    maybeUpdateSongDuration(song, result.durationMs ?: 0L)
                    withContext(Dispatchers.Main) {
                        applyResolvedMediaItem(
                            _currentSongFlow.value ?: song,
                            result.url,
                            result.mimeType,
                            result.expectedContentLength,
                            result.audioInfo,
                            resumePositionMs,
                            resumePlaybackAfterRefresh
                        )
                        consecutivePlayFailures = 0
                        if (
                            resumePlaybackAfterRefresh &&
                            resumedPlaybackCommandSource == PlaybackCommandSource.LOCAL
                        ) {
                            emitPlaybackCommand(
                                type = "PLAY",
                                source = resumedPlaybackCommandSource,
                                positionMs = resumePositionMs.coerceAtLeast(0L),
                                currentIndex = currentIndex
                            )
                        }
                    }
                } else if (allowFallback) {
                    resumePlaybackFallback(
                        seekPositionMs = fallbackSeekPositionMs,
                        resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                    )
                } else {
                    clearPendingSeekPosition()
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
                    withContext(Dispatchers.Main) { pause(commandSource = PlaybackCommandSource.REMOTE_SYNC) }
                }
            } finally {
                urlRefreshInProgress = false
            }
        }
    }

    private suspend fun applyResolvedMediaItem(
        song: SongItem,
        url: String,
        mimeType: String?,
        expectedContentLength: Long?,
        audioInfo: PlaybackAudioInfo?,
        resumePositionMs: Long,
        resumePlaybackAfterRefresh: Boolean
    ) {
        if (_currentSongFlow.value?.sameIdentityAs(song) != true) return

        val cacheKey = computeCacheKey(song)
        invalidateMismatchedCachedResource(
            cacheKey = cacheKey,
            expectedContentLength = expectedContentLength
        )
        val mediaItem = buildMediaItem(song, url, cacheKey, mimeType)

        _currentMediaUrl.value = url
        _currentPlaybackAudioInfo.value = audioInfo
        currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
        persistState()

        withContext(Dispatchers.Main) {
            preparePlayerForManagedStart(resolvePlaybackStartPlan(shouldFadeIn = false, fadeDurationMs = 0L))
            resetTrackEndDeduplicationState()
            player.setMediaItem(mediaItem)
            syncExoRepeatMode()
            if (resumePositionMs > 0) {
                player.seekTo(resumePositionMs)
                _playbackPositionMs.value = resumePositionMs
            }
            player.prepare()
            player.playWhenReady = resumePlaybackAfterRefresh
            if (resumePlaybackAfterRefresh) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    private fun checkLocalCache(song: SongItem): SongUrlResult? {
        val context = application
        val localReference = AudioDownloadManager.getLocalPlaybackUri(context, song) ?: return null
        val durationMs = if (song.durationMs <= 0L) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                val localUri = localReference.toUri()
                when (localUri.scheme?.lowercase()) {
                    "content", "android.resource" -> retriever.setDataSource(context, localUri)
                    "file" -> retriever.setDataSource(localUri.path)
                    null, "" -> retriever.setDataSource(localReference)
                    else -> retriever.setDataSource(context, localUri)
                }
                val d = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                retriever.release()
                d
            } catch (_: Exception) { null }
        } else null
        val localAudioInfo = buildLocalPlaybackAudioInfo(localReference.toUri())
        return SongUrlResult.Success(
            url = localReference,
            durationMs = durationMs,
            audioInfo = mergeLocalPlaybackAudioInfoWithRemoteQuality(
                localAudioInfo = localAudioInfo,
                previousAudioInfo = _currentPlaybackAudioInfo.value
                    ?.takeIf { _currentSongFlow.value?.sameIdentityAs(song) == true }
            )
        )
    }

    private fun checkExoPlayerCache(cacheKey: String): Boolean {
        return try {
            if (!::cache.isInitialized) return false

            val cachedSpans = cache.getCachedSpans(cacheKey)
            if (cachedSpans.isEmpty()) return false

            val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
            if (contentLength <= 0L) {
                NPLogger.d("NERI-PlayerManager", "缓存命中但缺少内容长度，视为未完成缓存: $cacheKey")
                return false
            }

            val orderedSpans = cachedSpans.sortedBy { it.position }
            var coveredUntil = 0L
            for (span in orderedSpans) {
                if (span.position > coveredUntil) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "缓存存在空洞，视为未完成缓存: $cacheKey @ ${span.position}"
                    )
                    return false
                }
                coveredUntil = maxOf(coveredUntil, span.position + span.length)
                if (coveredUntil >= contentLength) break
            }

            val isComplete = coveredUntil >= contentLength
            if (isComplete) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "缓存完整可用: $cacheKey, length=$contentLength, spans=${cachedSpans.size}"
                )
            } else {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "缓存未完整覆盖: $cacheKey, covered=$coveredUntil/$contentLength"
                )
            }

            isComplete
        } catch (e: Exception) {
            NPLogger.w("NERI-PlayerManager", "检查缓存完整性失败: ${e.message}")
            false
        }
    }

    private fun qualityLabelForNetease(key: String): String = when (key) {
        "standard" -> getLocalizedString(R.string.quality_standard)
        "higher" -> getLocalizedString(R.string.settings_audio_quality_higher)
        "exhigh" -> getLocalizedString(R.string.quality_very_high)
        "lossless" -> getLocalizedString(R.string.quality_lossless)
        "hires" -> getLocalizedString(R.string.quality_hires)
        "jyeffect" -> getLocalizedString(R.string.quality_hd_surround)
        "sky" -> getLocalizedString(R.string.quality_surround)
        "jymaster" -> getLocalizedString(R.string.settings_audio_quality_jymaster)
        else -> key
    }

    private fun qualityLabelForBili(key: String): String = when (key) {
        "dolby" -> getLocalizedString(R.string.quality_dolby)
        "hires" -> getLocalizedString(R.string.quality_hires)
        "lossless" -> getLocalizedString(R.string.quality_lossless)
        "high" -> getLocalizedString(R.string.settings_audio_quality_high)
        "medium" -> getLocalizedString(R.string.settings_audio_quality_medium)
        "low" -> getLocalizedString(R.string.settings_audio_quality_low)
        else -> key
    }

    private fun qualityLabelForYouTube(key: String): String = when (key) {
        "low" -> getLocalizedString(R.string.settings_audio_quality_low)
        "medium" -> getLocalizedString(R.string.settings_audio_quality_medium)
        "high" -> getLocalizedString(R.string.settings_audio_quality_high)
        "very_high" -> getLocalizedString(R.string.quality_very_high)
        else -> key
    }

    private fun buildNeteaseQualityOptions(): List<PlaybackQualityOption> = listOf(
        PlaybackQualityOption("standard", qualityLabelForNetease("standard")),
        PlaybackQualityOption("higher", qualityLabelForNetease("higher")),
        PlaybackQualityOption("exhigh", qualityLabelForNetease("exhigh")),
        PlaybackQualityOption("lossless", qualityLabelForNetease("lossless")),
        PlaybackQualityOption("hires", qualityLabelForNetease("hires")),
        PlaybackQualityOption("jyeffect", qualityLabelForNetease("jyeffect")),
        PlaybackQualityOption("sky", qualityLabelForNetease("sky")),
        PlaybackQualityOption("jymaster", qualityLabelForNetease("jymaster"))
    )

    private fun buildYouTubeQualityOptions(): List<PlaybackQualityOption> = listOf(
        PlaybackQualityOption("low", qualityLabelForYouTube("low")),
        PlaybackQualityOption("medium", qualityLabelForYouTube("medium")),
        PlaybackQualityOption("high", qualityLabelForYouTube("high")),
        PlaybackQualityOption("very_high", qualityLabelForYouTube("very_high"))
    )

    private fun inferBiliQualityKey(biliAudioStream: moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo): String {
        return when {
            biliAudioStream.qualityTag == "dolby" -> "dolby"
            biliAudioStream.qualityTag == "hires" -> "hires"
            biliAudioStream.bitrateKbps >= 180 -> "high"
            biliAudioStream.bitrateKbps >= 120 -> "medium"
            else -> "low"
        }
    }

    private fun buildBiliQualityOptions(
        availableStreams: List<moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo>
    ): List<PlaybackQualityOption> {
        val availableKeys = availableStreams
            .map(::inferBiliQualityKey)
            .distinct()
        val orderedKeys = listOf("dolby", "hires", "lossless", "high", "medium", "low")
        return orderedKeys
            .filter { it in availableKeys }
            .map { PlaybackQualityOption(it, qualityLabelForBili(it)) }
    }

    private fun normalizeNeteaseMimeType(type: String?): String? {
        val normalizedType = type
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when (normalizedType) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "m4a", "mp4" -> "audio/mp4"
            else -> if (normalizedType.contains('/')) normalizedType else "audio/$normalizedType"
        }
    }

    private fun buildLocalPlaybackAudioInfo(song: SongItem): PlaybackAudioInfo? {
        return runCatching {
            LocalMediaSupport.inspect(application, song)
        }.getOrNull()?.let { details ->
            PlaybackAudioInfo(
                source = PlaybackAudioSource.LOCAL,
                codecLabel = deriveCodecLabel(details.audioMimeType ?: details.mimeType),
                mimeType = details.audioMimeType ?: details.mimeType,
                bitrateKbps = details.bitrateKbps,
                sampleRateHz = details.sampleRateHz,
                bitDepth = details.bitsPerSample,
                channelCount = details.channelCount
            )
        }
    }

    private fun buildLocalPlaybackAudioInfo(localUri: Uri): PlaybackAudioInfo? {
        return runCatching {
            LocalMediaSupport.inspect(application, localUri)
        }.getOrNull()?.let { details ->
            PlaybackAudioInfo(
                source = PlaybackAudioSource.LOCAL,
                codecLabel = deriveCodecLabel(details.audioMimeType ?: details.mimeType),
                mimeType = details.audioMimeType ?: details.mimeType,
                bitrateKbps = details.bitrateKbps,
                sampleRateHz = details.sampleRateHz,
                bitDepth = details.bitsPerSample,
                channelCount = details.channelCount
            )
        }
    }

    private fun buildNeteasePlaybackAudioInfo(
        parsed: NeteasePlaybackResponseParser.PlaybackResult.Success,
        resolvedQualityKey: String,
        fallbackDurationMs: Long
    ): PlaybackAudioInfo {
        val mimeType = normalizeNeteaseMimeType(parsed.type)
        return PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityKey = resolvedQualityKey,
            qualityLabel = qualityLabelForNetease(resolvedQualityKey),
            qualityOptions = buildNeteaseQualityOptions(),
            codecLabel = deriveCodecLabel(mimeType) ?: parsed.type?.uppercase(),
            mimeType = mimeType,
            bitrateKbps = if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                null
            } else {
                estimateBitrateKbps(parsed.contentLength, fallbackDurationMs)
            }
        )
    }

    private fun buildBiliPlaybackAudioInfo(
        selectedStream: moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo,
        availableStreams: List<moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo>
    ): PlaybackAudioInfo {
        val qualityKey = inferBiliQualityKey(selectedStream)
        return PlaybackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            qualityKey = qualityKey,
            qualityLabel = qualityLabelForBili(qualityKey),
            qualityOptions = buildBiliQualityOptions(availableStreams),
            codecLabel = deriveCodecLabel(selectedStream.mimeType),
            mimeType = selectedStream.mimeType,
            bitrateKbps = selectedStream.bitrateKbps
        )
    }

    private fun buildYouTubePlaybackAudioInfo(
        playableAudio: moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio
    ): PlaybackAudioInfo {
        val qualityKey = inferYouTubeQualityKeyFromBitrate(playableAudio.bitrateKbps)
        return PlaybackAudioInfo(
            source = PlaybackAudioSource.YOUTUBE_MUSIC,
            qualityKey = qualityKey,
            qualityLabel = qualityLabelForYouTube(qualityKey),
            qualityOptions = buildYouTubeQualityOptions(),
            codecLabel = deriveCodecLabel(playableAudio.mimeType),
            mimeType = playableAudio.mimeType,
            bitrateKbps = playableAudio.bitrateKbps,
            sampleRateHz = playableAudio.sampleRateHz
        )
    }

    private fun buildNeteaseQualityCandidates(preferredQuality: String): List<String> {
        val normalizedQuality = preferredQuality.trim().lowercase().ifBlank { "exhigh" }
        val preferredIndex = NETEASE_QUALITY_FALLBACK_ORDER.indexOf(normalizedQuality)
        return if (preferredIndex >= 0) {
            NETEASE_QUALITY_FALLBACK_ORDER.drop(preferredIndex)
        } else {
            listOf(normalizedQuality, "exhigh", "standard").distinct()
        }
    }

    private fun shouldRetryNeteaseWithLowerQuality(
        reason: NeteasePlaybackResponseParser.FailureReason
    ): Boolean {
        return reason == NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ||
            reason == NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL
    }

    private fun buildNeteaseSuccessResult(
        parsed: NeteasePlaybackResponseParser.PlaybackResult.Success,
        resolvedQualityKey: String,
        fallbackDurationMs: Long
    ): SongUrlResult.Success {
        val finalUrl = if (parsed.url.startsWith("http://")) {
            parsed.url.replaceFirst("http://", "https://")
        } else {
            parsed.url
        }
        val noticeMessage = when (parsed.notice) {
            NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP ->
                getLocalizedString(R.string.player_netease_preview_only)
            null -> null
        }
        return SongUrlResult.Success(
            url = finalUrl,
            noticeMessage = noticeMessage,
            expectedContentLength = parsed.contentLength,
            audioInfo = buildNeteasePlaybackAudioInfo(
                parsed = parsed,
                resolvedQualityKey = resolvedQualityKey,
                fallbackDurationMs = fallbackDurationMs
            )
        )
    }

    private fun shouldReplaceCachedPreviewResource(
        cachedContentLength: Long,
        expectedContentLength: Long
    ): Boolean {
        val contentLengthGap = expectedContentLength - cachedContentLength
        return cachedContentLength > 0L &&
            expectedContentLength > 0L &&
            contentLengthGap >= 512L * 1024L &&
            cachedContentLength * 100L < expectedContentLength * 85L
    }

    private suspend fun invalidateMismatchedCachedResource(
        cacheKey: String,
        expectedContentLength: Long?
    ) = withContext(Dispatchers.IO) {
        val expectedLength = expectedContentLength?.takeIf { it > 0L } ?: return@withContext
        if (!::cache.isInitialized) return@withContext

        try {
            val cachedSpans = cache.getCachedSpans(cacheKey)
            if (cachedSpans.isEmpty()) return@withContext

            val cachedContentLength = ContentMetadata.getContentLength(
                cache.getContentMetadata(cacheKey)
            )
            if (!shouldReplaceCachedPreviewResource(cachedContentLength, expectedLength)) {
                return@withContext
            }

            NPLogger.w(
                "NERI-PlayerManager",
                "缓存疑似预览片段，移除旧缓存以便重新拉取完整资源: key=$cacheKey, cached=$cachedContentLength, expected=$expectedLength"
            )
            cache.removeResource(cacheKey)
        } catch (e: Exception) {
            NPLogger.w(
                "NERI-PlayerManager",
                "移除不匹配缓存失败: key=$cacheKey, error=${e.message}"
            )
        }
    }

    private suspend fun getNeteaseSongUrl(song: SongItem, suppressError: Boolean = false): SongUrlResult = withContext(Dispatchers.IO) {
        try {
            val qualityCandidates = buildNeteaseQualityCandidates(preferredQuality)
            var previewFallback: SongUrlResult.Success? = null
            var lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null

            for ((index, quality) in qualityCandidates.withIndex()) {
                val resp = neteaseClient.getSongDownloadUrl(
                    song.id,
                    level = quality
                )
                NPLogger.d("NERI-PlayerManager", "id=${song.id}, level=$quality, resp=$resp")

                when (val parsed = NeteasePlaybackResponseParser.parsePlayback(resp, song.durationMs)) {
                    is NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                        return@withContext SongUrlResult.RequiresLogin
                    }

                    is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                        val success = buildNeteaseSuccessResult(
                            parsed = parsed,
                            resolvedQualityKey = quality,
                            fallbackDurationMs = song.durationMs
                        )
                        if (parsed.notice != NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                            if (quality != preferredQuality) {
                                NPLogger.w(
                                    "NERI-PlayerManager",
                                    "当前音质不可用，已自动降级: id=${song.id}, preferred=$preferredQuality, resolved=$quality"
                                )
                            }
                            return@withContext success
                        }

                        previewFallback = success
                        if (index < qualityCandidates.lastIndex) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "当前音质仅返回试听片段，继续尝试更低音质: id=${song.id}, level=$quality"
                            )
                            continue
                        }
                        return@withContext success
                    }

                    is NeteasePlaybackResponseParser.PlaybackResult.Failure -> {
                        lastFailureReason = parsed.reason
                        if (index < qualityCandidates.lastIndex &&
                            shouldRetryNeteaseWithLowerQuality(parsed.reason)
                        ) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "当前音质不可播放，继续尝试更低音质: id=${song.id}, level=$quality, reason=${parsed.reason}"
                            )
                            continue
                        }
                        break
                    }
                }
            }

            previewFallback?.let { return@withContext it }

            if (!suppressError) {
                val messageRes = when (lastFailureReason) {
                    NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ->
                        R.string.player_netease_no_permission_switch_platform
                    NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL,
                    NeteasePlaybackResponseParser.FailureReason.UNKNOWN,
                    null -> R.string.error_no_play_url
                }
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageRes)))
            }
            SongUrlResult.Failure
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NPLogger.e("NERI-PlayerManager", "Failed to get url", e)
            if (!suppressError) {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(R.string.player_playback_url_error_detail, e.message.orEmpty())
                    )
                )
            }
            SongUrlResult.Failure
        }
    }

    private suspend fun getBiliAudioUrl(song: SongItem, suppressError: Boolean = false): SongUrlResult = withContext(Dispatchers.IO) {
        try {
            val resolved = resolveBiliSong(song, biliClient)
            if (resolved == null || resolved.cid == 0L) {
                if (!suppressError) {
                    postPlayerEvent(
                        PlayerEvent.ShowError(
                            getLocalizedString(R.string.player_playback_video_info_unavailable)
                        )
                    )
                }
                return@withContext SongUrlResult.Failure
            }

            val (availableStreams, audioStream) = biliRepo.getAudioWithDecision(
                resolved.videoInfo.bvid,
                resolved.cid
            )

            if (audioStream?.url != null) {
                NPLogger.d("NERI-PlayerManager-BiliAudioUrl", audioStream.url)
                SongUrlResult.Success(
                    url = audioStream.url,
                    mimeType = audioStream.mimeType,
                    audioInfo = buildBiliPlaybackAudioInfo(audioStream, availableStreams)
                )
            } else {
                if (!suppressError) {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                }
                SongUrlResult.Failure
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NPLogger.e("NERI-PlayerManager", "Failed to get Bili play url", e)
            if (!suppressError) {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(R.string.player_playback_url_error_detail, e.message.orEmpty())
                    )
                )
            }
            SongUrlResult.Failure
        }
    }

    private suspend fun getYouTubeMusicAudioUrl(
        song: SongItem,
        suppressError: Boolean = false,
        forceRefresh: Boolean = false
    ): SongUrlResult = withContext(Dispatchers.IO) {
        val videoId = extractYouTubeMusicVideoId(song.mediaUri)
        if (videoId.isNullOrBlank()) {
            if (!suppressError) {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
            }
            return@withContext SongUrlResult.Failure
        }

        try {
            val directPlayableAudio = youtubeMusicPlaybackRepository.getBestPlayableAudio(
                videoId = videoId,
                preferredQualityOverride = youtubePreferredQuality,
                forceRefresh = forceRefresh,
                requireDirect = true,
                preferM4a = true
            )
            val playableAudio = directPlayableAudio?.takeIf { it.url.isNotBlank() }
                ?: run {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "YouTube Music direct stream unavailable, falling back for $videoId"
                    )
                    youtubeMusicPlaybackRepository.getBestPlayableAudio(
                        videoId = videoId,
                        preferredQualityOverride = youtubePreferredQuality,
                        forceRefresh = forceRefresh,
                        preferM4a = true
                    )
                }
            val resolvedPlayableAudio = playableAudio?.takeIf { it.url.isNotBlank() }
            if (resolvedPlayableAudio != null) {
                maybeUpdateSongDuration(song, resolvedPlayableAudio.durationMs)
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Resolved YouTube Music stream: videoId=$videoId, type=${resolvedPlayableAudio.streamType}, mime=${resolvedPlayableAudio.mimeType}, contentLength=${resolvedPlayableAudio.contentLength}"
                )
                SongUrlResult.Success(
                    url = resolvedPlayableAudio.url,
                    durationMs = resolvedPlayableAudio.durationMs.takeIf { it > 0L },
                    mimeType = resolvedPlayableAudio.mimeType,
                    audioInfo = buildYouTubePlaybackAudioInfo(resolvedPlayableAudio)
                )
            } else {
                if (!suppressError) {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                }
                SongUrlResult.Failure
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NPLogger.e("NERI-PlayerManager", "Failed to get YouTube Music play url", e)
            if (!suppressError) {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(R.string.player_playback_url_error_detail, e.message.orEmpty())
                    )
                )
            }
            SongUrlResult.Failure
        }
    }

    /**
     */
    fun playBiliVideoParts(videoInfo: BiliClient.VideoBasicInfo, startIndex: Int, coverUrl: String) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        val songs = videoInfo.pages.map { page -> buildBiliPartSong(page, videoInfo, coverUrl) }
        playPlaylist(songs, startIndex)
    }

    fun play(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        cancelPendingPauseRequest(resetVolumeToFull = true)
        suppressAutoResumeForCurrentSession = false
        resumePlaybackRequested = true
        val song = _currentSongFlow.value
        val preparedInPlayer = isPreparedInPlayer()
        if (preparedInPlayer && song != null && !isLocalSong(song)) {
            val url = _currentMediaUrl.value
            if (!url.isNullOrBlank()) {
                val ageMs = if (currentMediaUrlResolvedAtMs > 0L) {
                    SystemClock.elapsedRealtime() - currentMediaUrlResolvedAtMs
                } else {
                    Long.MAX_VALUE
                }
                if (
                    ageMs >= MEDIA_URL_STALE_MS ||
                    YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeResume(song, url)
                ) {
                    refreshCurrentSongUrl(
                        resumePositionMs = player.currentPosition,
                        allowFallback = false,
                        reason = "stale_resume",
                        bypassCooldown = true,
                        resumedPlaybackCommandSource = commandSource
                    )
                    return
                }
            }
        }
        when {
            preparedInPlayer -> {
                syncExoRepeatMode()
                startPlayerPlaybackWithFade(resolveCurrentPlaybackStartPlan())
                val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
                _playbackPositionMs.value = resumePositionMs
                ioScope.launch {
                    persistState(
                        positionMs = resumePositionMs,
                        shouldResumePlayback = true
                    )
                }
                emitPlaybackCommand(
                    type = "PLAY",
                    source = commandSource,
                    positionMs = resumePositionMs,
                    currentIndex = currentIndex
                )
            }
            currentPlaylist.isNotEmpty() && currentIndex != -1 -> {
                val resumePositionMs = if (keepLastPlaybackProgressEnabled) {
                    maxOf(restoredResumePositionMs, _playbackPositionMs.value).coerceAtLeast(0L)
                } else {
                    0L
                }
                playAtIndex(currentIndex, resumePositionMs = resumePositionMs, commandSource = commandSource)
                emitPlaybackCommand(
                    type = "PLAY",
                    source = commandSource,
                    positionMs = resumePositionMs,
                    currentIndex = currentIndex
                )
            }
            currentPlaylist.isNotEmpty() -> {
                playAtIndex(0, commandSource = commandSource)
                emitPlaybackCommand(
                    type = "PLAY",
                    source = commandSource,
                    positionMs = 0L,
                    currentIndex = 0
                )
            }
            else -> {}
        }
    }

    private fun handleTrackEndedIfNeeded(source: String) {
        val currentKey = trackEndDeduplicationKey(
            mediaId = player.currentMediaItem?.mediaId,
            fallbackSongKey = _currentSongFlow.value?.stableKey()
        )
        if (!shouldHandleTrackEnd(lastHandledKey = lastHandledTrackEndKey, currentKey = currentKey)) {
            NPLogger.d(
                "NERI-PlayerManager",
                "忽略重复的曲目结束事件: source=$source, key=$currentKey"
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrackEndHandledAtMs < 500L) {
            NPLogger.d(
                "NERI-PlayerManager",
                "忽略过近的曲目结束事件: source=$source, key=$currentKey, delta=${now - lastTrackEndHandledAtMs}ms"
            )
            return
        }
        lastHandledTrackEndKey = currentKey
        lastTrackEndHandledAtMs = now
        NPLogger.d(
            "NERI-PlayerManager",
            "开始处理曲目结束事件: source=$source, key=$currentKey, index=$currentIndex, queueSize=${currentPlaylist.size}"
        )
        handleTrackEnded()
    }

    fun pause(
        forcePersist: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        cancelPendingPauseRequest()
        resumePlaybackRequested = false
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        val shouldFadeOut =
            playbackFadeInEnabled && playbackFadeOutDurationMs > 0L && ::player.isInitialized
        if (shouldFadeOut) {
            val scheduledPauseToken = playbackRequestToken
            lateinit var scheduledPauseJob: Job
            scheduledPauseJob = mainScope.launch {
                try {
                    fadeOutCurrentPlaybackIfNeeded(
                        enabled = true,
                        fadeOutDurationMs = playbackFadeOutDurationMs
                    )
                    if (scheduledPauseToken != playbackRequestToken) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "暂停请求已过期，跳过淡出后的暂停: requestToken=$scheduledPauseToken, currentToken=$playbackRequestToken"
                        )
                        return@launch
                    }
                    pauseInternal(forcePersist, resetVolumeToFull = false)
                } finally {
                    if (pendingPauseJob === scheduledPauseJob) {
                        pendingPauseJob = null
                    }
                }
            }
            pendingPauseJob = scheduledPauseJob
        } else {
            pauseInternal(forcePersist, resetVolumeToFull = true)
        }
        emitPlaybackCommand(
            type = "PAUSE",
            source = commandSource,
            positionMs = _playbackPositionMs.value,
            currentIndex = currentIndex
        )
    }

    private fun pauseInternal(forcePersist: Boolean, resetVolumeToFull: Boolean) {
        pendingPauseJob = null
        resumePlaybackRequested = false
        val currentSong = _currentSongFlow.value
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val expectedDuration = currentSong?.durationMs?.takeIf { it > 0L } ?: player.duration
        val shouldForceFlushShortLocalSong =
            currentSong?.let(::isLocalSong) == true && expectedDuration in 1L..5_000L
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        cancelVolumeFade(resetToFull = resetVolumeToFull)
        val stackHint = Throwable().stackTrace.take(6).joinToString(" <- ") {
            "${it.fileName}:${it.lineNumber}"
        }
        NPLogger.d(
            "NERI-PlayerManager",
            "pauseInternal: song=${currentSong?.name}, positionMs=$currentPosition, state=${playbackStateName(player.playbackState)}, playWhenReady=${player.playWhenReady}, forcePersist=$forcePersist, stack=[$stackHint]"
        )
        player.playWhenReady = false
        player.pause()
        if (shouldForceFlushShortLocalSong) {
            runCatching {
                player.seekTo(currentPosition.coerceAtMost(expectedDuration.coerceAtLeast(0L)))
            }
            _playbackPositionMs.value = currentPosition
        }
        if (!resetVolumeToFull) {
            runPlayerActionOnMainThread {
                if (::player.isInitialized) {
                    player.volume = 1f
                }
            }
        }
        if (forcePersist) {
            blockingIo {
                persistState(positionMs = currentPosition, shouldResumePlayback = false)
            }
        } else {
            ioScope.launch {
                persistState(positionMs = currentPosition, shouldResumePlayback = false)
            }
        }
    }

    fun togglePlayPause() {
        ensureInitialized()
        if (!initialized) return
        if (player.isPlaying || player.playWhenReady || playJob?.isActive == true) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(
        positionMs: Long,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        val resolvedPositionMs = positionMs.coerceAtLeast(0L)
        if (YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(_currentSongFlow.value, _currentMediaUrl.value)) {
            rememberPendingSeekPosition(resolvedPositionMs)
        } else {
            clearPendingSeekPosition()
        }
        player.seekTo(resolvedPositionMs)
        _playbackPositionMs.value = resolvedPositionMs
        ioScope.launch {
            persistState(
                positionMs = resolvedPositionMs,
                shouldResumePlayback = shouldResumePlaybackSnapshot()
            )
        }
        emitPlaybackCommand(
            type = "SEEK",
            source = commandSource,
            positionMs = resolvedPositionMs,
            currentIndex = currentIndex
        )
    }

    fun next(
        force: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        if (currentPlaylist.isEmpty()) return
        val isShuffle = player.shuffleModeEnabled
        val useTransitionFade =
            playbackCrossfadeNextEnabled && (player.isPlaying || player.playWhenReady)

        if (isShuffle) {
            if (shuffleFuture.isNotEmpty()) {
                val nextIdx = shuffleFuture.removeAt(shuffleFuture.lastIndex)
                if (currentIndex != -1) shuffleHistory.add(currentIndex)
                currentIndex = nextIdx
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                emitPlaybackCommand(
                    type = "NEXT",
                    source = commandSource,
                    currentIndex = currentIndex,
                    force = force
                )
                return
            }

            if (shuffleBag.isEmpty()) {
                if (force || repeatModeSetting == Player.REPEAT_MODE_ALL) {
                    rebuildShuffleBag(excludeIndex = currentIndex)
                } else {
                    stopPlaybackPreservingQueue()
                    return
                }
            }

            if (shuffleBag.isEmpty()) {
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                return
            }

            if (currentIndex != -1) shuffleHistory.add(currentIndex)

            val pick = if (shuffleBag.size == 1) 0 else Random.nextInt(shuffleBag.size)
            currentIndex = shuffleBag.removeAt(pick)
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            emitPlaybackCommand(
                type = "NEXT",
                source = commandSource,
                currentIndex = currentIndex,
                force = force
            )
        } else {
            if (currentIndex < currentPlaylist.lastIndex) {
                currentIndex++
            } else {
                if (force || repeatModeSetting == Player.REPEAT_MODE_ALL) {
                    currentIndex = 0
                } else {
                    NPLogger.d("NERI-Player", "Already at the end of the playlist.")
                    return
                }
            }
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            emitPlaybackCommand(
                type = "NEXT",
                source = commandSource,
                currentIndex = currentIndex,
                force = force
            )
        }
    }

    fun previous(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        if (currentPlaylist.isEmpty()) return
        val isShuffle = player.shuffleModeEnabled
        val useTransitionFade =
            playbackCrossfadeNextEnabled && (player.isPlaying || player.playWhenReady)

        if (isShuffle) {
            if (shuffleHistory.isNotEmpty()) {
                if (currentIndex != -1) shuffleFuture.add(currentIndex)
                val prev = shuffleHistory.removeAt(shuffleHistory.lastIndex)
                currentIndex = prev
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                emitPlaybackCommand(
                    type = "PREVIOUS",
                    source = commandSource,
                    currentIndex = currentIndex
                )
            } else {
                NPLogger.d("NERI-Player", "No previous track in shuffle history.")
            }
        } else {
            if (currentIndex > 0) {
                currentIndex--
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                emitPlaybackCommand(
                    type = "PREVIOUS",
                    source = commandSource,
                    currentIndex = currentIndex
                )
            } else {
                if (repeatModeSetting == Player.REPEAT_MODE_ALL && currentPlaylist.isNotEmpty()) {
                    currentIndex = currentPlaylist.lastIndex
                    playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                    emitPlaybackCommand(
                        type = "PREVIOUS",
                        source = commandSource,
                        currentIndex = currentIndex
                    )
                } else {
                    NPLogger.d("NERI-Player", "Already at the start of the playlist.")
                }
            }
        }
    }

    fun cycleRepeatMode() {
        ensureInitialized()
        if (!initialized) return
        val newMode = when (repeatModeSetting) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        repeatModeSetting = newMode
        _repeatModeFlow.value = newMode
        ioScope.launch {
            persistState()
        }
    }

    fun release() {
        if (!initialized) return
        resumePlaybackRequested = false
        lastAutoTrackAdvanceAtMs = 0L

        try {
            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        } catch (_: Exception) { }
        audioDeviceCallback = null

        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        cancelPendingPauseRequest(resetVolumeToFull = true)
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
        playbackSoundPersistJob?.cancel()
        playbackSoundPersistJob = null
        playJob?.cancel()
        playJob = null

        if (::player.isInitialized) {
            runCatching { player.stop() }
            player.release()
        }
        _playbackSoundState.value = playbackEffectsController.release()
        _playWhenReadyFlow.value = false
        _playerPlaybackStateFlow.value = Player.STATE_IDLE
        if (::cache.isInitialized) {
            cache.release()
        }
        conditionalHttpFactory?.close()
        conditionalHttpFactory = null

        mainScope.cancel()
        ioScope.cancel()

        _isPlayingFlow.value = false
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        currentMediaUrlResolvedAtMs = 0L
        _currentSongFlow.value = null
        _currentQueueFlow.value = emptyList()
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L

        currentPlaylist = emptyList()
        currentIndex = -1
        shuffleBag.clear()
        shuffleHistory.clear()
        shuffleFuture.clear()
        consecutivePlayFailures = 0

        initialized = false
    }

    fun setShuffle(enabled: Boolean) {
        ensureInitialized()
        if (!initialized) return
        if (player.shuffleModeEnabled == enabled) return
        player.shuffleModeEnabled = enabled
        shuffleHistory.clear()
        shuffleFuture.clear()
        if (enabled) {
            rebuildShuffleBag(excludeIndex = currentIndex)
        } else {
            shuffleBag.clear()
        }
        ioScope.launch {
            persistState()
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = mainScope.launch {
            while (isActive) {
                val positionMs = resolveDisplayedPlaybackPosition(
                    player.currentPosition.coerceAtLeast(0L)
                )
                _playbackPositionMs.value = positionMs
                maybePersistPlaybackProgress(positionMs)
                // 进度条不需要 25fps 级别刷新；适当降频能明显减少 UI/服务侧联动开销
                delay(PLAYBACK_PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() { progressJob?.cancel(); progressJob = null }

    private fun maybePersistPlaybackProgress(positionMs: Long) {
        if (currentPlaylist.isEmpty()) return
        if (!shouldResumePlaybackSnapshot()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatePersistAtMs < STATE_PERSIST_INTERVAL_MS) return
        lastStatePersistAtMs = now
        ioScope.launch {
            persistState(positionMs = positionMs, shouldResumePlayback = true)
        }
    }

    private fun stopPlaybackPreservingQueue(clearMediaUrl: Boolean = false) {
        cancelPendingPauseRequest(resetVolumeToFull = true)
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        lastHandledTrackEndKey = null
        resumePlaybackRequested = false
        lastAutoTrackAdvanceAtMs = 0L
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        _playWhenReadyFlow.value = false
        _playerPlaybackStateFlow.value = Player.STATE_IDLE
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        if (currentPlaylist.isEmpty()) {
            currentIndex = -1
            _currentSongFlow.value = null
            _currentMediaUrl.value = null
            _currentPlaybackAudioInfo.value = null
            currentMediaUrlResolvedAtMs = 0L
        } else {
            currentIndex = currentIndex.coerceIn(0, currentPlaylist.lastIndex)
            _currentSongFlow.value = currentPlaylist.getOrNull(currentIndex)
            if (clearMediaUrl) {
                _currentMediaUrl.value = null
                _currentPlaybackAudioInfo.value = null
                currentMediaUrlResolvedAtMs = 0L
            }
        }
        consecutivePlayFailures = 0
        ioScope.launch {
            persistState()
        }
    }

    fun hasItems(): Boolean = currentPlaylist.isNotEmpty()

    private fun updateCurrentFavorite(song: SongItem, add: Boolean) {
        val updatedLists = PlayerFavoritesController.optimisticUpdateFavorites(
            playlists = _playlistsFlow.value,
            add = add,
            song = song,
            application = application,
            favoritePlaylistName = getLocalizedString(R.string.favorite_my_music)
        )
        _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(updatedLists)

        ioScope.launch {
            try {
                if (add) {
                    localRepo.addToFavorites(song)
                } else {
                    localRepo.removeFromFavorites(song)
                }
            } catch (error: Exception) {
                val action = if (add) "addToFavorites" else "removeFromFavorites"
                NPLogger.e("NERI-PlayerManager", "$action failed: ${error.message}", error)
            }
        }
    }

    fun addCurrentToFavorites() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        updateCurrentFavorite(song = song, add = true)
    }

    fun removeCurrentFromFavorites() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        updateCurrentFavorite(song = song, add = false)
    }

    fun toggleCurrentFavorite() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        if (PlayerFavoritesController.isFavorite(_playlistsFlow.value, song, application)) {
            updateCurrentFavorite(song = song, add = false)
        } else {
            updateCurrentFavorite(song = song, add = true)
        }
    }

    private suspend fun persistState(
        positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
        shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot()
    ) {
        val playlistSnapshot = currentPlaylist.toList()
        val currentIndexSnapshot = currentIndex
        val mediaUrlSnapshot = _currentMediaUrl.value
        val persistedShouldResumePlayback =
            shouldResumePlayback && !suppressAutoResumeForCurrentSession
        val persistedPositionMs = if (keepLastPlaybackProgressEnabled) {
            positionMs.coerceAtLeast(0L)
        } else {
            0L
        }
        val persistedRepeatMode = if (keepPlaybackModeStateEnabled) {
            repeatModeSetting
        } else {
            Player.REPEAT_MODE_OFF
        }
        val persistedShuffleEnabled = keepPlaybackModeStateEnabled && _shuffleModeFlow.value

        withContext(Dispatchers.IO) {
            try {
                if (playlistSnapshot.isEmpty()) {
                    restoredResumePositionMs = 0L
                    restoredShouldResumePlayback = false
                    if (stateFile.exists()) stateFile.delete()
                } else {
                    val data = PersistedState(
                        playlist = playlistSnapshot.map { song ->
                            song.toPersistedSongItem(
                                includeLyrics = shouldPersistEmbeddedLyrics(song)
                            )
                        },
                        index = currentIndexSnapshot,
                        mediaUrl = mediaUrlSnapshot,
                        positionMs = persistedPositionMs,
                        shouldResumePlayback = persistedShouldResumePlayback,
                        repeatMode = persistedRepeatMode,
                        shuffleEnabled = persistedShuffleEnabled
                    )
                    stateFile.writeText(gson.toJson(data))
                }
            } catch (e: Exception) {
                NPLogger.e("PlayerManager", "Failed to persist state", e)
            }
        }
    }

    fun addCurrentToPlaylist(playlistId: Long) {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        ioScope.launch {
            try {
                localRepo.addSongToPlaylist(playlistId, song)
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "addCurrentToPlaylist failed: ${e.message}", e)
            }
        }
    }

    /** 将 B 站视频列表按音频队列播放 */
    fun playBiliVideoAsAudio(videos: List<BiliVideoItem>, startIndex: Int) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        if (videos.isEmpty()) {
            NPLogger.w("NERI-Player", "playBiliVideoAsAudio called with EMPTY list")
            return
        }
        val songs = videos.map { it.toSongItem() }
        playPlaylist(songs, startIndex)
    }


    suspend fun getNeteaseLyrics(songId: Long): List<LyricEntry> {
        return PlayerLyricsProvider.getNeteaseLyrics(songId, neteaseClient)
    }

    suspend fun getNeteaseTranslatedLyrics(songId: Long): List<LyricEntry> {
        return PlayerLyricsProvider.getNeteaseTranslatedLyrics(songId, neteaseClient)
    }

    suspend fun getTranslatedLyrics(song: SongItem): List<LyricEntry> {
        return PlayerLyricsProvider.getTranslatedLyrics(
            song = song,
            application = application,
            neteaseClient = neteaseClient,
            biliSourceTag = BILI_SOURCE_TAG
        )
    }

    suspend fun getLyrics(song: SongItem): List<LyricEntry> {
        return PlayerLyricsProvider.getLyrics(
            song = song,
            application = application,
            neteaseClient = neteaseClient,
            youtubeMusicClient = youtubeMusicClient,
            lrcLibClient = lrcLibClient,
            ytMusicLyricsCache = ytMusicLyricsCache,
            biliSourceTag = BILI_SOURCE_TAG
        )
    }

    fun playFromQueue(
        index: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) return
        if (index !in currentPlaylist.indices) return
        val targetSong = currentPlaylist[index]
        if (shouldBlockLocalRoomControl(commandSource) || shouldBlockLocalSongSwitch(targetSong, commandSource)) return

        if (player.shuffleModeEnabled) {
            if (currentIndex != -1) shuffleHistory.add(currentIndex)
            shuffleFuture.clear()
            shuffleBag.remove(index)
        }

        currentIndex = index
        playAtIndex(index, commandSource = commandSource)
        emitPlaybackCommand(
            type = "PLAY_FROM_QUEUE",
            source = commandSource,
            currentIndex = currentIndex
        )
    }

    /** 将歌曲插入到当前播放歌曲之后 */
    fun addToQueueNext(song: SongItem) {
        ensureInitialized()
        if (!initialized) return

        if (currentPlaylist.isEmpty()) {
            playPlaylist(listOf(song), 0)
            return
        }

        val currentSong = _currentSongFlow.value
        val newPlaylist = currentPlaylist.toMutableList()
        var insertIndex = (currentIndex + 1).coerceIn(0, newPlaylist.size + 1)

        // If the song already exists, move it to the next slot.
        val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
        if (existingIndex != -1) {
            if (existingIndex < insertIndex) {
                insertIndex--
            }
            newPlaylist.removeAt(existingIndex)
        }

        insertIndex = insertIndex.coerceIn(0, newPlaylist.size)
        newPlaylist.add(insertIndex, song)

        currentPlaylist = newPlaylist
        _currentQueueFlow.value = currentPlaylist
        currentIndex = if (currentSong != null) {
            queueIndexOf(currentSong, newPlaylist).takeIf { it >= 0 }
                ?: currentIndex.coerceIn(0, newPlaylist.lastIndex)
        } else {
            currentIndex.coerceIn(0, newPlaylist.lastIndex)
        }
        if (player.shuffleModeEnabled) {
            val newSongRealIndex = queueIndexOf(song, newPlaylist)

            if (newSongRealIndex != -1) {
                shuffleBag.remove(newSongRealIndex)
                shuffleFuture.add(newSongRealIndex)
            }
        }

        ioScope.launch {
            persistState()
        }
    }


    /** 将歌曲追加到当前队列末尾 */
    fun addToQueueEnd(song: SongItem) {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) {
            playPlaylist(listOf(song), 0)
            return
        }

        val currentSong = _currentSongFlow.value
        val newPlaylist = currentPlaylist.toMutableList()

        // If the song already exists, move it to the end.
        val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
        if (existingIndex != -1) {
            newPlaylist.removeAt(existingIndex)
        }

        newPlaylist.add(song)

        currentPlaylist = newPlaylist
        _currentQueueFlow.value = currentPlaylist
        currentIndex = if (currentSong != null) {
            queueIndexOf(currentSong, newPlaylist).takeIf { it >= 0 }
                ?: currentIndex.coerceIn(0, newPlaylist.lastIndex)
        } else {
            currentIndex.coerceIn(0, newPlaylist.lastIndex)
        }

        if (player.shuffleModeEnabled) {
            rebuildShuffleBag()
        }

        ioScope.launch {
            persistState()
        }
    }

    private fun restoreState() {
        try {
            if (!stateFile.exists()) return
            val type = object : TypeToken<PersistedState>() {}.type
            val data: PersistedState = gson.fromJson(stateFile.readText(), type)
            currentPlaylist = sanitizeRestoredPlaylist(
                data.playlist.map { persistedSong -> persistedSong.toSongItem() }
            )
            if (currentPlaylist.isEmpty()) {
                currentIndex = -1
                _currentQueueFlow.value = emptyList()
                _currentSongFlow.value = null
                _currentMediaUrl.value = null
                _currentPlaybackAudioInfo.value = null
                _playbackPositionMs.value = 0L
                currentMediaUrlResolvedAtMs = 0L
                restoredResumePositionMs = 0L
                restoredShouldResumePlayback = false
                resumePlaybackRequested = false
                return
            }
            val preferredSong = data.playlist.getOrNull(data.index)?.toSongItem()
            currentIndex = when {
                currentPlaylist.isEmpty() -> -1
                preferredSong != null -> queueIndexOf(preferredSong, currentPlaylist).takeIf { it >= 0 }
                    ?: data.index.coerceIn(0, currentPlaylist.lastIndex)
                data.index in currentPlaylist.indices -> data.index
                else -> 0
            }
            _currentQueueFlow.value = currentPlaylist
            _currentSongFlow.value = currentPlaylist.getOrNull(currentIndex)
            _currentMediaUrl.value = data.mediaUrl?.takeIf {
                _currentSongFlow.value?.let(::isLocalSong) != true ||
                    _currentSongFlow.value?.let(::isRestorableLocalSong) == true
            }
            repeatModeSetting = if (keepPlaybackModeStateEnabled) {
                when (data.repeatMode) {
                    Player.REPEAT_MODE_ALL,
                    Player.REPEAT_MODE_ONE,
                    Player.REPEAT_MODE_OFF -> data.repeatMode
                    else -> Player.REPEAT_MODE_OFF
                }
            } else {
                Player.REPEAT_MODE_OFF
            }
            syncExoRepeatMode()
            _repeatModeFlow.value = repeatModeSetting

            val restoreShuffleEnabled = keepPlaybackModeStateEnabled && (data.shuffleEnabled == true)
            player.shuffleModeEnabled = restoreShuffleEnabled
            _shuffleModeFlow.value = restoreShuffleEnabled
            shuffleHistory.clear()
            shuffleFuture.clear()
            if (restoreShuffleEnabled) {
                rebuildShuffleBag(excludeIndex = currentIndex)
            } else {
                shuffleBag.clear()
            }

            restoredResumePositionMs = if (keepLastPlaybackProgressEnabled) {
                data.positionMs.coerceAtLeast(0L)
            } else {
                0L
            }
            restoredShouldResumePlayback = data.shouldResumePlayback && currentIndex != -1
            // 已恢复的播放快照只代表可继续播放，不是当前已有活跃传输
            resumePlaybackRequested = false
            _playbackPositionMs.value = restoredResumePositionMs
            currentMediaUrlResolvedAtMs = 0L
        } catch (e: Exception) {
            NPLogger.w("NERI-PlayerManager", "Failed to restore state: ${e.message}")
        }
    }

    fun resumeRestoredPlaybackIfNeeded(): Long? {
        ensureInitialized()
        if (!initialized) return null
        if (!restoredShouldResumePlayback) return null
        if (currentPlaylist.isEmpty() || currentIndex !in currentPlaylist.indices) return null
        val resumeIndex = currentIndex
        val resumePositionMs = restoredResumePositionMs.coerceAtLeast(0L)
        restoredShouldResumePlayback = false
        restoredResumePositionMs = 0L
        lastStatePersistAtMs = SystemClock.elapsedRealtime()
        playAtIndex(
            resumeIndex,
            resumePositionMs = resumePositionMs,
            forceStartupProtectionFade = true
        )
        return resumePositionMs
    }

    fun suppressFutureAutoResumeForCurrentSession(forcePersist: Boolean = false) {
        ensureInitialized()
        if (!initialized || currentPlaylist.isEmpty()) return
        suppressAutoResumeForCurrentSession = true
        restoredShouldResumePlayback = false
        val positionMs = if (::player.isInitialized) {
            player.currentPosition.coerceAtLeast(0L)
        } else {
            _playbackPositionMs.value.coerceAtLeast(0L)
        }
        _playbackPositionMs.value = positionMs
        if (forcePersist) {
            blockingIo {
                persistState(positionMs = positionMs, shouldResumePlayback = false)
            }
        } else {
            ioScope.launch {
                persistState(positionMs = positionMs, shouldResumePlayback = false)
            }
        }
    }


    fun replaceMetadataFromSearch(
        originalSong: SongItem,
        selectedSong: SongSearchInfo,
        isAuto: Boolean = false
    ) {
        ioScope.launch {
            val platform = selectedSong.source

            val api = when (platform) {
                MusicPlatform.CLOUD_MUSIC -> cloudMusicSearchApi
                MusicPlatform.QQ_MUSIC -> qqMusicSearchApi
            }

            try {
                val newDetails = api.getSongInfo(selectedSong.id)

                val updatedSong = if (isAuto) {
                    originalSong.copy(
                        matchedLyric = newDetails.lyric ?: originalSong.matchedLyric,
                        matchedTranslatedLyric = newDetails.translatedLyric ?: originalSong.matchedTranslatedLyric,
                        matchedLyricSource = selectedSong.source,
                        matchedSongId = selectedSong.id
                    )
                } else {
                    applyManualSearchMetadata(
                        originalSong = originalSong,
                        songName = newDetails.songName,
                        singer = newDetails.singer,
                        coverUrl = newDetails.coverUrl,
                        lyric = newDetails.lyric,
                        translatedLyric = newDetails.translatedLyric,
                        matchedSource = selectedSong.source,
                        matchedSongId = selectedSong.id,
                        useCustomOverride = shouldApplySearchMetadataAsCustomOverride(originalSong)
                    )
                }

                updateSongInAllPlaces(originalSong, updatedSong)

            } catch (e: Exception) {
                mainScope.launch {
                    Toast.makeText(
                        application,
                        getLocalizedString(R.string.toast_match_failed, e.message.orEmpty()),
                        Toast.LENGTH_SHORT
                    ).show()
                    NPLogger.e("NERI-PlayerManager", "replaceMetadataFromSearch failed: ${e.message}", e)
                }
            }
        }
    }

    private fun shouldApplySearchMetadataAsCustomOverride(song: SongItem): Boolean {
        return isLocalSong(song) || AudioDownloadManager.getLocalPlaybackUri(application, song) != null
    }

    fun updateSongCustomInfo(
        originalSong: SongItem,
        customCoverUrl: String?,
        customName: String?,
        customArtist: String?
    ) {
        ioScope.launch {
            NPLogger.d("PlayerManager", "updateSongCustomInfo: id=${originalSong.id}, album='${originalSong.album}'")

            val currentSong = currentPlaylist.firstOrNull { it.sameIdentityAs(originalSong) }
                ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(originalSong) }
                ?: originalSong

            val baseName = currentSong.name
            val baseArtist = currentSong.artist
            val baseCoverUrl = currentSong.coverUrl
            val originalName = currentSong.originalName ?: baseName
            val originalArtist = currentSong.originalArtist ?: baseArtist
            val originalCoverUrl = currentSong.originalCoverUrl ?: baseCoverUrl

            val normalizedCustomName = normalizeCustomMetadataValue(
                desiredValue = customName,
                baseValue = baseName
            )
            val normalizedCustomArtist = normalizeCustomMetadataValue(
                desiredValue = customArtist,
                baseValue = baseArtist
            )
            val normalizedCustomCoverUrl = normalizeCustomMetadataValue(
                desiredValue = customCoverUrl,
                baseValue = baseCoverUrl
            )

            val updatedSong = currentSong.copy(
                customName = normalizedCustomName,
                customArtist = normalizedCustomArtist,
                customCoverUrl = normalizedCustomCoverUrl,
                originalName = originalName,
                originalArtist = originalArtist,
                originalCoverUrl = originalCoverUrl
            )

            updateSongInAllPlaces(originalSong, updatedSong)
        }
    }

    suspend fun updateUserLyricOffset(songToUpdate: SongItem, newOffset: Long) {
        val queueIndex = queueIndexOf(songToUpdate)
        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(userLyricOffsetMs = newOffset)
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (isCurrentSong(songToUpdate)) {
            _currentSongFlow.value = _currentSongFlow.value?.copy(userLyricOffsetMs = newOffset)
        }

        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
            ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
        }

        persistState()
    }

    suspend fun updateSongLyrics(songToUpdate: SongItem, newLyrics: String?) {
        val queueIndex = queueIndexOf(songToUpdate)
        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(matchedLyric = newLyrics)
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (isCurrentSong(songToUpdate)) {
            _currentSongFlow.value = _currentSongFlow.value?.copy(matchedLyric = newLyrics)
        }

        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
        }

        persistState()
    }

    suspend fun updateSongTranslatedLyrics(songToUpdate: SongItem, newTranslatedLyrics: String?) {
        val queueIndex = queueIndexOf(songToUpdate)
        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(matchedTranslatedLyric = newTranslatedLyrics)
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (isCurrentSong(songToUpdate)) {
            _currentSongFlow.value = _currentSongFlow.value?.copy(matchedTranslatedLyric = newTranslatedLyrics)
        }

        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
        }

        persistState()
    }

    suspend fun updateSongLyricsAndTranslation(songToUpdate: SongItem, newLyrics: String?, newTranslatedLyrics: String?) {
//        NPLogger.e("PlayerManager", "!!! FUNCTION CALLED !!! updateSongLyricsAndTranslation")
//        NPLogger.e("PlayerManager", "songId=${songToUpdate.id}, album='${songToUpdate.album}'")
//        NPLogger.e("PlayerManager", "newLyrics=${newLyrics?.take(50)}, newTranslatedLyrics=${newTranslatedLyrics?.take(50)}")

//        currentPlaylist.forEachIndexed { index, song ->
//            NPLogger.e("PlayerManager", "[$index] id=${song.id}, album='${song.album}', name='${song.name}', hasLyric=${song.matchedLyric != null}")
//        }

        val queueIndex = queueIndexOf(songToUpdate)
//        NPLogger.e("PlayerManager", "queueIndex=$queueIndex, currentPlaylist.size=${currentPlaylist.size}")

        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(
                matchedLyric = newLyrics,
                matchedTranslatedLyric = newTranslatedLyrics
            )
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
            NPLogger.e("PlayerManager", "Queue song updated")
        } else {
            NPLogger.e("PlayerManager", "Song to update was not found in queue")
        }

        NPLogger.e("PlayerManager", "Current playing song: id=${_currentSongFlow.value?.id}, album='${_currentSongFlow.value?.album}'")
        if (isCurrentSong(songToUpdate)) {
            val beforeUpdate = _currentSongFlow.value?.matchedLyric
            _currentSongFlow.value = _currentSongFlow.value?.copy(
                matchedLyric = newLyrics,
                matchedTranslatedLyric = newTranslatedLyrics
            )
            NPLogger.e("PlayerManager", "Current song lyrics updated: before=${beforeUpdate?.take(50)}, after=${_currentSongFlow.value?.matchedLyric?.take(50)}")
        } else {
            NPLogger.e("PlayerManager", "Current song does not match target update")
        }

        // 将最新歌词变更同步到本地仓库，保证内存态和持久化数据一致
        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
            NPLogger.d(
                "PlayerManager",
                "歌词更新已同步到本地仓库: id=${latestSong.id}, lyric=${latestSong.matchedLyric?.take(32)}, translated=${latestSong.matchedTranslatedLyric?.take(32)}"
            )
        } else {
            NPLogger.e("PlayerManager", "歌词更新后未找到最新歌曲副本，跳过本地仓库同步")
        }

        persistState()
        NPLogger.d("PlayerManager", "updateSongLyricsAndTranslation completed")
    }

    private suspend fun updateSongInAllPlaces(originalSong: SongItem, updatedSong: SongItem) {
        val queueIndex = queueIndexOf(originalSong)
        if (queueIndex != -1) {
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (isCurrentSong(originalSong)) {
            _currentSongFlow.value = updatedSong
        }

        withContext(Dispatchers.IO) {
            localRepo.updateSongMetadata(originalSong, updatedSong)
        }
        GlobalDownloadManager.syncDownloadedSongMetadata(updatedSong)
        AppContainer.playHistoryRepo.updateSongMetadata(originalSong, updatedSong)
        AppContainer.playlistUsageRepo.syncLocalEntries(localRepo.playlists.value)

        persistState()
    }

}

internal fun normalizeCustomMetadataValue(
    desiredValue: String?,
    baseValue: String?
): String? {
    val normalizedDesired = desiredValue?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return normalizedDesired.takeIf { it != baseValue }
}

internal fun applyManualSearchMetadata(
    originalSong: SongItem,
    songName: String,
    singer: String,
    coverUrl: String?,
    lyric: String?,
    translatedLyric: String?,
    matchedSource: MusicPlatform,
    matchedSongId: String,
    useCustomOverride: Boolean
): SongItem {
    val originalName = originalSong.originalName ?: originalSong.name
    val originalArtist = originalSong.originalArtist ?: originalSong.artist
    val originalCoverUrl = originalSong.originalCoverUrl ?: originalSong.coverUrl

    return if (useCustomOverride) {
        originalSong.copy(
            matchedLyric = lyric,
            matchedTranslatedLyric = translatedLyric,
            matchedLyricSource = matchedSource,
            matchedSongId = matchedSongId,
            customCoverUrl = normalizeCustomMetadataValue(coverUrl, originalSong.coverUrl),
            customName = normalizeCustomMetadataValue(songName, originalSong.name),
            customArtist = normalizeCustomMetadataValue(singer, originalSong.artist),
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
            originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric
        )
    } else {
        originalSong.copy(
            name = songName,
            artist = singer,
            coverUrl = coverUrl,
            matchedLyric = lyric,
            matchedTranslatedLyric = translatedLyric,
            matchedLyricSource = matchedSource,
            matchedSongId = matchedSongId,
            customCoverUrl = null,
            customName = null,
            customArtist = null,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
            originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric
        )
    }
}


