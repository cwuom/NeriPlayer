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
import android.media.AudioDeviceCallback
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.core.player.policy.PlaybackCommand
import moe.ouom.neriplayer.core.player.policy.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.resolvePlaybackSoundConfigForEngine
import moe.ouom.neriplayer.core.player.policy.shouldShowPauseButtonForPlaybackControls
import moe.ouom.neriplayer.core.player.policy.shouldBootstrapPlaybackServiceOnAppLaunch
import moe.ouom.neriplayer.core.player.policy.shouldRunPlaybackServiceInForeground
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.listentogether.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.buildStableTrackKey
import moe.ouom.neriplayer.listentogether.resolvedAudioId
import moe.ouom.neriplayer.listentogether.resolvedChannelId
import moe.ouom.neriplayer.listentogether.resolvedPlaylistContextId
import moe.ouom.neriplayer.listentogether.resolvedSubAudioId
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File


internal const val PLAYBACK_PROGRESS_UPDATE_INTERVAL_MS = 80L

@Suppress("ObjectPropertyName", "ktlint:standard:property-naming")
object PlayerManager {
    const val BILI_SOURCE_TAG = "Bilibili"
    const val NETEASE_SOURCE_TAG = "Netease"

    internal var initialized = false
    internal lateinit var application: Application
    internal lateinit var player: ExoPlayer

    internal lateinit var cache: Cache
    internal var conditionalHttpFactory: ConditionalHttpDataSourceFactory? = null

    // Helper function to get localized string
    internal fun getLocalizedString(resId: Int, vararg formatArgs: Any): String {
        val context = moe.ouom.neriplayer.util.LanguageManager.applyLanguage(application)
        return context.getString(resId, *formatArgs)
    }

    internal fun debugStackHint(
        skipFrames: Int = 2,
        maxFrames: Int = 6
    ): String {
        return Throwable().stackTrace
            .drop(skipFrames)
            .take(maxFrames)
            .joinToString(" <- ") { frame -> "${frame.fileName}:${frame.lineNumber}" }
    }

    internal fun newIoScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal fun newMainScope() = CoroutineScope(Dispatchers.Main + SupervisorJob())

    internal var ioScope = newIoScope()
    internal var mainScope = newMainScope()
    internal var progressJob: Job? = null
    internal var volumeFadeJob: Job? = null
    internal var pendingPauseJob: Job? = null
        set(value) {
            field = value
            syncPlaybackControlPlayingState()
        }
    internal var bluetoothDisconnectPauseJob: Job? = null
    internal var playbackSoundPersistJob: Job? = null
    internal var playbackSoundApplyJob: Job? = null
    internal var pendingPlaybackSoundConfig: PlaybackSoundConfig? = null
    internal var neteaseQualityRefreshJob: Job? = null
    internal var youtubeQualityRefreshJob: Job? = null
    internal var biliQualityRefreshJob: Job? = null

    internal val localRepo: LocalPlaylistRepository
        get() = LocalPlaylistRepository.getInstance(application)

    internal lateinit var stateFile: File

    internal var preferredQuality: String = "exhigh"
    internal var youtubePreferredQuality: String = "very_high"
    internal var biliPreferredQuality: String = "high"
    internal var playbackFadeInEnabled = false
    internal var playbackCrossfadeNextEnabled = false
    internal var playbackFadeInDurationMs = DEFAULT_FADE_DURATION_MS
    internal var playbackFadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    internal var playbackCrossfadeInDurationMs = DEFAULT_FADE_DURATION_MS
    internal var playbackCrossfadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    internal var playbackSoundConfig = PlaybackSoundConfig()
    internal var keepLastPlaybackProgressEnabled = true
    internal var keepPlaybackModeStateEnabled = true
    internal var stopOnBluetoothDisconnectEnabled = true
    internal var allowMixedPlaybackEnabled = false

    internal var currentPlaylist: List<SongItem> = emptyList()
    internal var currentIndex = -1

    /** 记录随机播放历史，支持上一首和跨轮次回退 */
    internal val shuffleHistory = mutableListOf<Int>()   // 已播放过的随机索引历史
    internal val shuffleFuture  = mutableListOf<Int>()   // queued next items for shuffle history
    internal var shuffleBag     = mutableListOf<Int>()   // remaining shuffle candidates for current cycle

    internal var consecutivePlayFailures = 0
    internal const val MAX_CONSECUTIVE_FAILURES = 10
    internal const val MEDIA_URL_STALE_MS = 10 * 60 * 1000L
    internal const val URL_REFRESH_COOLDOWN_MS = 10 * 1000L
    internal const val STATE_PERSIST_INTERVAL_MS = 15 * 1000L
    internal const val DEFAULT_FADE_DURATION_MS = 500L
    internal const val BLUETOOTH_DISCONNECT_CONFIRM_DELAY_MS = 1200L
    internal const val AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS = 2_000L
    internal const val AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS = 1_500L
    internal const val PENDING_SEEK_POSITION_TOLERANCE_MS = 1_500L
    internal const val QUALITY_CHANGE_REFRESH_DEBOUNCE_MS = 300L
    internal const val MIN_FADE_STEPS = 4
    internal const val MAX_FADE_STEPS = 30
    @Volatile
    internal var urlRefreshInProgress = false
    @Volatile
    internal var pendingSeekPositionMs: Long = C.TIME_UNSET
    internal var lastUrlRefreshKey: String? = null
    internal var lastUrlRefreshAtMs: Long = 0L
    internal var currentMediaUrlResolvedAtMs: Long = 0L
    internal var restoredResumePositionMs: Long = 0L
    internal var restoredShouldResumePlayback = false
    internal var lastStatePersistAtMs: Long = 0L
    internal var lastAutoTrackAdvanceAtMs: Long = 0L
    @Volatile
    internal var resumePlaybackRequested = false
        set(value) {
            field = value
            syncPlaybackControlPlayingState()
        }
    @Volatile
    internal var suppressAutoResumeForCurrentSession = false
    @Volatile
    internal var listenTogetherSyncPlaybackRate = 1f

    internal val _currentSongFlow = MutableStateFlow<SongItem?>(null)
    val currentSongFlow: StateFlow<SongItem?> = _currentSongFlow

    internal val _currentQueueFlow = MutableStateFlow<List<SongItem>>(emptyList())
    val currentQueueFlow: StateFlow<List<SongItem>> = _currentQueueFlow

    internal val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    /**
     * 播放/暂停按钮使用的视觉状态。
     * 它跟随用户最近一次播放控制意图，避免淡入/淡出期间图标滞后。
     */
    internal val _playbackControlPlayingFlow = MutableStateFlow(false)
    val playbackControlPlayingFlow: StateFlow<Boolean> = _playbackControlPlayingFlow

    internal val _playWhenReadyFlow = MutableStateFlow(false)
    val playWhenReadyFlow: StateFlow<Boolean> = _playWhenReadyFlow

    internal val _playerPlaybackStateFlow = MutableStateFlow(Player.STATE_IDLE)
    val playerPlaybackStateFlow: StateFlow<Int> = _playerPlaybackStateFlow

    internal val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionFlow: StateFlow<Long> = _playbackPositionMs

    internal val _shuffleModeFlow = MutableStateFlow(false)
    val shuffleModeFlow: StateFlow<Boolean> = _shuffleModeFlow

    internal val _repeatModeFlow = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatModeFlow: StateFlow<Int> = _repeatModeFlow
    internal var repeatModeSetting: Int = Player.REPEAT_MODE_OFF

    internal val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
    internal var audioDeviceCallback: AudioDeviceCallback? = null

    internal val _playerEventFlow = MutableSharedFlow<PlayerEvent>()
    val playerEventFlow: SharedFlow<PlayerEvent> = _playerEventFlow.asSharedFlow()

    internal val _playbackCommandFlow = MutableSharedFlow<PlaybackCommand>(
        extraBufferCapacity = 32
    )
    val playbackCommandFlow: SharedFlow<PlaybackCommand> = _playbackCommandFlow.asSharedFlow()

    /** 当前曲目的解析后媒体地址，供恢复播放和错误恢复使用 */
    internal val _currentMediaUrl = MutableStateFlow<String?>(null)
    val currentMediaUrlFlow: StateFlow<String?> = _currentMediaUrl

    internal val _currentPlaybackAudioInfo = MutableStateFlow<PlaybackAudioInfo?>(null)
    @Suppress("unused")
    val currentPlaybackAudioInfoFlow: StateFlow<PlaybackAudioInfo?> = _currentPlaybackAudioInfo

    internal val playbackEffectsController = PlaybackEffectsController()
    internal val _playbackSoundState = MutableStateFlow(PlaybackSoundState())
    val playbackSoundStateFlow: StateFlow<PlaybackSoundState> = _playbackSoundState

    /** 本地歌单快照，供收藏状态和歌单选择弹窗使用 */
    internal val _playlistsFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlistsFlow: StateFlow<List<LocalPlaylist>> = _playlistsFlow

    internal var playJob: Job? = null
    internal var playbackRequestToken = 0L
    internal var lastHandledTrackEndKey: String? = null
    internal var lastTrackEndHandledAtMs = 0L
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
    internal val ytMusicLyricsCache = android.util.LruCache<String, List<LyricEntry>>(20)

    // 当前缓存上限，设置变化后会据此重建缓存
    internal var currentCacheSize: Long = 1024L * 1024 * 1024

    var sleepTimerManager: SleepTimerManager = createSleepTimerManager()
        internal set

    internal fun createSleepTimerManager(): SleepTimerManager {
        return SleepTimerManager(
            scope = mainScope,
            onTimerExpired = {
                pause()
                sleepTimerManager.cancel()
            }
        )
    }

    internal fun isApplicationInitialized(): Boolean = this::application.isInitialized

    internal fun isPlayerInitialized(): Boolean = this::player.isInitialized

    internal fun isCacheInitialized(): Boolean = this::cache.isInitialized

    internal fun syncPlaybackControlPlayingState() {
        _playbackControlPlayingFlow.value = shouldShowPauseButtonForPlaybackControls(
            resumePlaybackRequested = resumePlaybackRequested,
            pendingPauseJobActive = pendingPauseJob?.isActive == true
        )
    }

    internal fun updateResumePlaybackRequested(requested: Boolean) {
        resumePlaybackRequested = requested
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

    internal fun markAutoTrackAdvance() {
        lastAutoTrackAdvanceAtMs = SystemClock.elapsedRealtime()
    }

    internal fun fadeStepsFor(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (durationMs / 40L).toInt().coerceIn(MIN_FADE_STEPS, MAX_FADE_STEPS)
    }

    internal fun runPlayerActionOnMainThread(action: () -> Unit) {
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

    internal fun applyAudioFocusPolicy() {
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

    internal fun isPreparedInPlayer(): Boolean =
        player.currentMediaItem != null && (
            player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING
            )

    fun setListenTogetherSyncPlaybackRate(rate: Float) {
        ensureInitialized()
        val resolvedRate = rate.coerceIn(0.95f, 1.05f)
        if (kotlin.math.abs(listenTogetherSyncPlaybackRate - resolvedRate) < 0.001f) return
        NPLogger.d(
            "NERI-PlayerManager",
            "setListenTogetherSyncPlaybackRate(): old=$listenTogetherSyncPlaybackRate, new=$resolvedRate, stack=[${debugStackHint()}]"
        )
        listenTogetherSyncPlaybackRate = resolvedRate
        schedulePlaybackSoundConfigApply(
            previousConfig = playbackSoundConfig,
            newConfig = playbackSoundConfig
        )
    }

    fun resetListenTogetherSyncPlaybackRate() {
        setListenTogetherSyncPlaybackRate(1f)
    }

    @Suppress("unused")
    fun resetForListenTogetherJoin() {
        ensureInitialized()
        if (!initialized) return
        NPLogger.d(
            "NERI-PlayerManager",
            "resetForListenTogetherJoin(): currentSong=${_currentSongFlow.value?.name}, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, isPlaying=${_isPlayingFlow.value}, stack=[${debugStackHint()}]"
        )
        cancelPendingPauseRequest(resetVolumeToFull = true)
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        updateResumePlaybackRequested(false)
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
        NPLogger.d("NERI-PlayerManager", "resetForListenTogetherJoin(): state cleared")
        ioScope.launch {
            persistState(positionMs = 0L, shouldResumePlayback = false)
        }
    }

    internal fun pendingSeekPositionOrNull(): Long? {
        return pendingSeekPositionMs.takeIf { it != C.TIME_UNSET }
    }

    internal fun rememberPendingSeekPosition(positionMs: Long) {
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
    }

    internal fun clearPendingSeekPosition() {
        pendingSeekPositionMs = C.TIME_UNSET
    }

    internal fun resolveDisplayedPlaybackPosition(actualPositionMs: Long): Long {
        val actual = actualPositionMs.coerceAtLeast(0L)
        val pending = pendingSeekPositionOrNull() ?: return actual
        return if (kotlin.math.abs(actual - pending) <= PENDING_SEEK_POSITION_TOLERANCE_MS) {
            clearPendingSeekPosition()
            actual
        } else {
            pending
        }
    }

    internal val gson = Gson()

    internal fun isLocalSong(song: SongItem): Boolean = LocalSongSupport.isLocalSong(song, application)

    internal fun isDirectStreamUrl(url: String?): Boolean {
        val normalized = url?.trim().orEmpty()
        return normalized.startsWith("https://", ignoreCase = true) ||
            normalized.startsWith("http://", ignoreCase = true)
    }

    internal fun activeListenTogetherRoomState() = AppContainer.listenTogetherSessionManager.roomState.value

    internal fun activeListenTogetherSessionState() = AppContainer.listenTogetherSessionManager.sessionState.value

    internal fun isListenTogetherActive(): Boolean {
        return !activeListenTogetherSessionState().roomId.isNullOrBlank()
    }

    internal fun isCurrentUserControllerInListenTogether(): Boolean {
        val session = activeListenTogetherSessionState()
        val room = activeListenTogetherRoomState()
        val sessionUserId = session.userUuid?.trim()?.takeIf { it.isNotBlank() }
        val controllerUserId = room?.controllerUserUuid?.trim()?.takeIf { it.isNotBlank() }
            ?: room?.controllerUserId?.trim()?.takeIf { it.isNotBlank() }
        return sessionUserId != null && controllerUserId != null && sessionUserId == controllerUserId
    }

    internal fun currentListenTogetherTargetStableKey(): String? {
        val room = activeListenTogetherRoomState() ?: return null
        return room.track?.stableKey ?: room.queue.getOrNull(room.currentIndex)?.stableKey
    }

    internal fun currentListenTogetherTargetStreamUrl(): String? {
        val room = activeListenTogetherRoomState() ?: return null
        return room.track?.streamUrl ?: room.queue.getOrNull(room.currentIndex)?.streamUrl
    }

    internal fun SongItem.listenTogetherStableKeyOrNull(): String? {
        val channel = resolvedChannelId() ?: return null
        val audioId = resolvedAudioId() ?: return null
        return buildStableTrackKey(
            channelId = channel,
            audioId = audioId,
            subAudioId = resolvedSubAudioId(),
            playlistContextId = resolvedPlaylistContextId()
        )
    }

    internal fun shouldWaitForListenTogetherAuthoritativeStream(song: SongItem): Boolean {
        if (!isListenTogetherActive()) return false
        if (isCurrentUserControllerInListenTogether()) return false
        val room = activeListenTogetherRoomState() ?: return false
        if (!room.settings.shareAudioLinks || room.roomStatus != "active") return false
        if (isDirectStreamUrl(currentListenTogetherTargetStreamUrl())) return false
        val targetStableKey = currentListenTogetherTargetStableKey() ?: return false
        val songStableKey = song.listenTogetherStableKeyOrNull() ?: return false
        return songStableKey == targetStableKey
    }

    internal fun stopCurrentPlaybackForListenTogetherAwaitingStream() {
        NPLogger.d(
            "NERI-PlayerManager",
            "stopCurrentPlaybackForListenTogetherAwaitingStream(): currentSong=${_currentSongFlow.value?.name}, mediaUrl=${_currentMediaUrl.value}, targetStableKey=${currentListenTogetherTargetStableKey()}, stack=[${debugStackHint()}]"
        )
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

    internal fun rejectListenTogetherControl(
        messageResId: Int,
        debugReason: String? = null
    ): Boolean {
        NPLogger.w(
            "NERI-PlayerManager",
            "rejectListenTogetherControl(): messageResId=$messageResId, reason=${debugReason ?: "unspecified"}, sessionRoomId=${activeListenTogetherSessionState().roomId}, roomStatus=${activeListenTogetherRoomState()?.roomStatus}, stack=[${debugStackHint()}]"
        )
        postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageResId)))
        return true
    }

    internal fun shouldBlockLocalRoomControl(commandSource: PlaybackCommandSource): Boolean {
        if (commandSource != PlaybackCommandSource.LOCAL) return false
        if (!isListenTogetherActive()) return false
        val room = activeListenTogetherRoomState()
        if (room?.roomStatus == "controller_offline" && !isCurrentUserControllerInListenTogether()) {
            return rejectListenTogetherControl(
                R.string.listen_together_error_controller_offline,
                debugReason = "local_control_blocked:controller_offline"
            )
        }
        if (room?.settings?.allowMemberControl == false && !isCurrentUserControllerInListenTogether()) {
            return rejectListenTogetherControl(
                R.string.listen_together_error_member_control_disabled,
                debugReason = "local_control_blocked:member_control_disabled"
            )
        }
        return false
    }

    internal fun shouldBlockLocalSongSwitch(song: SongItem, commandSource: PlaybackCommandSource): Boolean {
        if (commandSource != PlaybackCommandSource.LOCAL) return false
        if (!isListenTogetherActive()) return false
        if (!isLocalSong(song)) return false
        return rejectListenTogetherControl(
            R.string.listen_together_error_local_playback_blocked,
            debugReason = "local_song_switch_blocked:${song.stableKey()}"
        )
    }

    internal fun isYouTubeMusicTrack(song: SongItem): Boolean {
        return song.channelId == ListenTogetherChannels.YOUTUBE_MUSIC || isYouTubeMusicSong(song)
    }

    internal fun isBiliTrack(song: SongItem): Boolean {
        return song.channelId == ListenTogetherChannels.BILIBILI ||
            song.album.startsWith(BILI_SOURCE_TAG)
    }
    internal fun shouldPersistEmbeddedLyrics(song: SongItem): Boolean = !isLocalSong(song)

    internal fun queueIndexOf(song: SongItem, playlist: List<SongItem> = currentPlaylist): Int {
        return playlist.indexOfFirst { it.sameIdentityAs(song) }
    }

    internal fun localMediaSource(song: SongItem): String? {
        return song.localFilePath?.takeIf { it.isNotBlank() }
            ?: song.mediaUri?.takeIf { it.isNotBlank() }
    }

    internal fun toPlayableLocalUrl(mediaUri: String?): String? {
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

    internal fun isReadableLocalMediaUri(mediaUri: String?): Boolean {
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

    internal fun isRestorableLocalMediaUri(mediaUri: String?): Boolean {
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

    internal fun isRestorableLocalSong(song: SongItem): Boolean {
        return isRestorableLocalMediaUri(localMediaSource(song))
    }

    internal fun sanitizeRestoredPlaylist(playlist: List<SongItem>): List<SongItem> {
        return playlist.filter { song ->
            !isLocalSong(song) || isRestorableLocalSong(song)
        }
    }

    internal fun isCurrentSong(song: SongItem): Boolean {
        return _currentSongFlow.value?.sameIdentityAs(song) == true
    }

    internal fun maybeUpdateSongDuration(song: SongItem, durationMs: Long) {
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

    internal fun maybeBackfillCurrentSongDurationFromPlayer() {
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

    internal fun applyPlaybackSoundConfig(
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

    internal fun schedulePlaybackSoundConfigApply(
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

    internal fun applyPlaybackSoundConfigIfChanged(newConfig: PlaybackSoundConfig) {
        val normalizedConfig = newConfig.copy(
            speed = normalizePlaybackSpeed(newConfig.speed),
            pitch = normalizePlaybackPitch(newConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(newConfig.loudnessGainMb)
        )
        if (normalizedConfig == playbackSoundConfig) return
        applyPlaybackSoundConfig(normalizedConfig, persist = false)
    }

    internal fun persistPlaybackSoundConfig(config: PlaybackSoundConfig) {
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

    internal fun scheduleQualityRefresh(
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

    internal suspend fun refreshCurrentSongForQualityChange(
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

    internal fun postPlayerEvent(event: PlayerEvent) {
        ioScope.launch { _playerEventFlow.emit(event) }
    }

    internal fun emitPlaybackCommand(
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

    internal fun resetTrackEndDeduplicationState() {
        lastHandledTrackEndKey = null
        lastTrackEndHandledAtMs = 0L
    }

    /**
     */
    internal fun syncExoRepeatMode() {
        val desired = if (repeatModeSetting == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        if (player.repeatMode != desired) {
            player.repeatMode = desired
        }
    }

    internal fun shouldResumePlaybackSnapshot(): Boolean {
        return resumePlaybackRequested || playJob?.isActive == true
    }

    /**
     */
    internal fun computeCacheKey(song: SongItem): String {
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

    internal fun buildMediaItem(
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

internal fun cancelVolumeFade(resetToFull: Boolean = false) =
        cancelVolumeFadeImpl(resetToFull)

    internal fun cancelPendingPauseRequest(resetVolumeToFull: Boolean = false) =
        cancelPendingPauseRequestImpl(resetVolumeToFull)

    fun initialize(app: Application, maxCacheSize: Long = 1024L * 1024 * 1024) =
        initializeImpl(app, maxCacheSize)

    @Suppress("unused")
    suspend fun clearCache(
        clearAudio: Boolean = true,
        clearImage: Boolean = true
    ): Pair<Boolean, String> = clearCacheImpl(clearAudio, clearImage)

    internal fun ensureInitialized() = ensureInitializedImpl()

    fun handleAudioBecomingNoisy(): Boolean = handleAudioBecomingNoisyImpl()

    internal fun refreshCurrentSongUrl(
        resumePositionMs: Long,
        allowFallback: Boolean,
        reason: String,
        bypassCooldown: Boolean = false,
        fallbackSeekPositionMs: Long? = null,
        resumePlaybackAfterRefresh: Boolean = true,
        resumedPlaybackCommandSource: PlaybackCommandSource? = null
    ) = refreshCurrentSongUrlImpl(
        resumePositionMs = resumePositionMs,
        allowFallback = allowFallback,
        reason = reason,
        bypassCooldown = bypassCooldown,
        fallbackSeekPositionMs = fallbackSeekPositionMs,
        resumePlaybackAfterRefresh = resumePlaybackAfterRefresh,
        resumedPlaybackCommandSource = resumedPlaybackCommandSource
    )

    internal fun handleTrackEndedIfNeeded(source: String) =
        handleTrackEndedIfNeededImpl(source)

    fun playPlaylist(
        songs: List<SongItem>,
        startIndex: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = playPlaylistImpl(songs, startIndex, commandSource)

    fun playBiliVideoParts(videoInfo: BiliClient.VideoBasicInfo, startIndex: Int, coverUrl: String) =
        playBiliVideoPartsImpl(videoInfo, startIndex, coverUrl)

    fun play(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) =
        playImpl(commandSource)

    fun pause(
        forcePersist: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = pauseImpl(forcePersist, commandSource)

    fun togglePlayPause() = togglePlayPauseImpl()

    fun seekTo(
        positionMs: Long,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = seekToImpl(positionMs, commandSource)

    fun next(
        force: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = nextImpl(force, commandSource)

    fun previous(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) =
        previousImpl(commandSource)

    fun cycleRepeatMode() = cycleRepeatModeImpl()

    fun release() = releaseImpl()

    fun setShuffle(enabled: Boolean) = setShuffleImpl(enabled)

    internal fun stopProgressUpdates() = stopProgressUpdatesImpl()

    internal fun stopPlaybackPreservingQueue(clearMediaUrl: Boolean = false) =
        stopPlaybackPreservingQueueImpl(clearMediaUrl)

    fun hasItems(): Boolean = hasItemsImpl()

    fun addCurrentToFavorites() = addCurrentToFavoritesImpl()

    fun removeCurrentFromFavorites() = removeCurrentFromFavoritesImpl()

    fun toggleCurrentFavorite() = toggleCurrentFavoriteImpl()

    internal suspend fun persistState(
        positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
        shouldResumePlayback: Boolean =
            currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot()
    ) = persistStateImpl(positionMs, shouldResumePlayback)

    fun addCurrentToPlaylist(playlistId: Long) = addCurrentToPlaylistImpl(playlistId)

    fun playBiliVideoAsAudio(videos: List<BiliVideoItem>, startIndex: Int) =
        playBiliVideoAsAudioImpl(videos, startIndex)

    @Suppress("unused")
    suspend fun getNeteaseLyrics(songId: Long): List<LyricEntry> =
        getNeteaseLyricsImpl(songId)

    @Suppress("unused")
    suspend fun getNeteaseTranslatedLyrics(songId: Long): List<LyricEntry> =
        getNeteaseTranslatedLyricsImpl(songId)

    suspend fun getTranslatedLyrics(song: SongItem): List<LyricEntry> =
        getTranslatedLyricsImpl(song)

    suspend fun getLyrics(song: SongItem): List<LyricEntry> = getLyricsImpl(song)

    fun playFromQueue(
        index: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = playFromQueueImpl(index, commandSource)

    fun addToQueueNext(song: SongItem) = addToQueueNextImpl(song)

    fun addToQueueEnd(song: SongItem) = addToQueueEndImpl(song)

    fun resumeRestoredPlaybackIfNeeded(): Long? = resumeRestoredPlaybackIfNeededImpl()

    fun suppressFutureAutoResumeForCurrentSession(forcePersist: Boolean = false) =
        suppressFutureAutoResumeForCurrentSessionImpl(forcePersist)

    fun replaceMetadataFromSearch(
        originalSong: SongItem,
        selectedSong: SongSearchInfo,
        isAuto: Boolean = false
    ) = replaceMetadataFromSearchImpl(originalSong, selectedSong, isAuto)

    fun updateSongCustomInfo(
        originalSong: SongItem,
        customCoverUrl: String?,
        customName: String?,
        customArtist: String?
    ) = updateSongCustomInfoImpl(originalSong, customCoverUrl, customName, customArtist)

    fun hydrateSongMetadata(originalSong: SongItem, updatedSong: SongItem) =
        hydrateSongMetadataImpl(originalSong, updatedSong)

    suspend fun updateUserLyricOffset(songToUpdate: SongItem, newOffset: Long) =
        updateUserLyricOffsetImpl(songToUpdate, newOffset)

    suspend fun updateSongLyrics(songToUpdate: SongItem, newLyrics: String?) =
        updateSongLyricsImpl(songToUpdate, newLyrics)

    suspend fun updateSongTranslatedLyrics(
        songToUpdate: SongItem,
        newTranslatedLyrics: String?
    ) = updateSongTranslatedLyricsImpl(songToUpdate, newTranslatedLyrics)

    suspend fun updateSongLyricsAndTranslation(
        songToUpdate: SongItem,
        newLyrics: String?,
        newTranslatedLyrics: String?
    ) = updateSongLyricsAndTranslationImpl(songToUpdate, newLyrics, newTranslatedLyrics)
}
