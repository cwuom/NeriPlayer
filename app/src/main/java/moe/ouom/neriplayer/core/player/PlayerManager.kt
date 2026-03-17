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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
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
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.buildBiliPartSong
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.biliCookieRepo
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.FavoritesPlaylist
import moe.ouom.neriplayer.data.LocalSongSupport
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.isYouTubeMusicSong
import moe.ouom.neriplayer.data.sameIdentityAs
import moe.ouom.neriplayer.data.stableKey
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.parseNeteaseLrc
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.SearchManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random
import androidx.core.net.toUri

data class AudioDevice(
    val name: String,
    val type: Int,
    val icon: ImageVector
)

/** 用于封装播放器需要通知UI的事件 */
sealed class PlayerEvent {
    data class ShowLoginPrompt(val message: String) : PlayerEvent()
    data class ShowError(val message: String) : PlayerEvent()
}

private sealed class SongUrlResult {
    data class Success(val url: String) : SongUrlResult()
    object RequiresLogin : SongUrlResult()
    object Failure : SongUrlResult()
}

/**
 * PlayerManager 负责：
 * - 初始化 ExoPlayer、缓存、渲染管线，并与应用配置（音质、Cookie 等）打通
 * - 维护播放队列与索引，暴露 StateFlow 给 UI（当前曲、队列、播放/进度、随机/循环）
 * - 解析跨平台播放地址（网易云/B 站），构造 MediaItem 与自定义缓存键
 * - 实现顺序/随机播放，包括“历史/未来/抽签袋”三栈模型，保证可回退与分叉前进
 * - 序列化/反序列化播放状态文件，实现应用重启后的恢复
 */
object PlayerManager {
    const val BILI_SOURCE_TAG = "Bilibili"
    const val NETEASE_SOURCE_TAG = "Netease"
    const val YOUTUBE_MUSIC_SOURCE_TAG = "YouTube Music|"

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
    private var bluetoothDisconnectPauseJob: Job? = null

    private val localRepo: LocalPlaylistRepository
        get() = LocalPlaylistRepository.getInstance(application)

    private lateinit var stateFile: File

    private var preferredQuality: String = "exhigh"
    private var biliPreferredQuality: String = "high"
    private var playbackFadeInEnabled = false
    private var playbackCrossfadeNextEnabled = false
    private var playbackFadeInDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackFadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackCrossfadeInDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackCrossfadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    private var keepLastPlaybackProgressEnabled = true
    private var keepPlaybackModeStateEnabled = true
    private var stopOnBluetoothDisconnectEnabled = true
    private var allowMixedPlaybackEnabled = false

    private var currentPlaylist: List<SongItem> = emptyList()
    private var currentIndex = -1

    /** 随机播放相关  */
    private val shuffleHistory = mutableListOf<Int>()   // 已经走过的路径（支持上一首）
    private val shuffleFuture  = mutableListOf<Int>()   // 预定的“下一首们”（支持先上后下仍回到原来的下一首）
    private var shuffleBag     = mutableListOf<Int>()   // 本轮还没“抽签”的下标池（不含 current）

    private var consecutivePlayFailures = 0
    private const val MAX_CONSECUTIVE_FAILURES = 10
    private const val MEDIA_URL_STALE_MS = 10 * 60 * 1000L
    private const val URL_REFRESH_COOLDOWN_MS = 30 * 1000L
    private const val STATE_PERSIST_INTERVAL_MS = 15 * 1000L
    private const val DEFAULT_FADE_DURATION_MS = 500L
    private const val BLUETOOTH_DISCONNECT_CONFIRM_DELAY_MS = 1200L
    private const val MIN_FADE_STEPS = 4
    private const val MAX_FADE_STEPS = 30
    @Volatile
    private var urlRefreshInProgress = false
    private var lastUrlRefreshKey: String? = null
    private var lastUrlRefreshAtMs: Long = 0L
    private var currentMediaUrlResolvedAtMs: Long = 0L
    private var restoredResumePositionMs: Long = 0L
    private var restoredShouldResumePlayback = false
    private var lastStatePersistAtMs: Long = 0L
    @Volatile
    private var resumePlaybackRequested = false
    @Volatile
    private var suppressAutoResumeForCurrentSession = false

    private val _currentSongFlow = MutableStateFlow<SongItem?>(null)
    val currentSongFlow: StateFlow<SongItem?> = _currentSongFlow

    private val _currentQueueFlow = MutableStateFlow<List<SongItem>>(emptyList())
    val currentQueueFlow: StateFlow<List<SongItem>> = _currentQueueFlow

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

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

    /** 向 UI 暴露当前实际播放链接，用于来源展示 */
    private val _currentMediaUrl = MutableStateFlow<String?>(null)
    val currentMediaUrlFlow: StateFlow<String?> = _currentMediaUrl

    /** 给 UI 用的歌单流 */
    private val _playlistsFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlistsFlow: StateFlow<List<LocalPlaylist>> = _playlistsFlow

    private var playJob: Job? = null
    private var playbackRequestToken = 0L
    private var lastHandledTrackEndKey: String? = null

    val audioLevelFlow get() = AudioReactive.level
    val beatImpulseFlow get() = AudioReactive.beat

    var biliRepo = AppContainer.biliPlaybackRepository
    var biliClient = AppContainer.biliClient
    var neteaseClient = AppContainer.neteaseClient
    var youtubeMusicPlaybackRepository = AppContainer.youtubeMusicPlaybackRepository

    val cloudMusicSearchApi = AppContainer.cloudMusicSearchApi
    val qqMusicSearchApi = AppContainer.qqMusicSearchApi

    // 记录当前缓存大小设置
    private var currentCacheSize: Long = 1024L * 1024 * 1024

    // 睡眠定时器（提前初始化，避免界面先于 PlayerManager.initialize 访问时崩溃）
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

    private fun fadeStepsFor(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (durationMs / 40L).toInt().coerceIn(MIN_FADE_STEPS, MAX_FADE_STEPS)
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

    private val gson = Gson()

    private fun isLocalSong(song: SongItem): Boolean = LocalSongSupport.isLocalSong(song, application)

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

    private fun sanitizeRestoredPlaylist(playlist: List<SongItem>): List<SongItem> {
        return playlist.filter { song ->
            !isLocalSong(song) || isReadableLocalSong(song)
        }
    }

    private fun isCurrentSong(song: SongItem): Boolean {
        return _currentSongFlow.value?.sameIdentityAs(song) == true
    }

    /** 在后台线程发布事件到 UI（非阻塞） */
    private fun postPlayerEvent(event: PlayerEvent) {
        ioScope.launch { _playerEventFlow.emit(event) }
    }

    /**
     * 仅允许 ExoPlayer 在“单曲循环”时循环；其余一律 OFF，由队列逻辑接管
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
     * 基于歌曲来源与所选音质构建缓存键
     * - B 站：bili-avid-可选cid-音质
     * - 网易云：netease-songId-音质
     */
    private fun computeCacheKey(song: SongItem): String {
        return when {
            isLocalSong(song) -> "local-${song.stableKey().hashCode()}"
            isYouTubeMusicSong(song) -> {
                val videoId = extractYouTubeMusicVideoId(song.mediaUri).orEmpty()
                "ytmusic-$videoId"
            }
            song.album.startsWith(BILI_SOURCE_TAG) -> {
            val parts = song.album.split('|')
            val cidPart = if (parts.size > 1) parts[1] else null
            if (cidPart != null) {
                "bili-${song.id}-$cidPart-$biliPreferredQuality"
            } else {
                "bili-${song.id}-$biliPreferredQuality"
            }
            }
            else -> "netease-${song.id}-$preferredQuality"
        }
    }

    /** 基于 URL 与缓存键构建 MediaItem（含自定义缓存键，便于跨音质/来源复用/隔离） */
    private fun buildMediaItem(song: SongItem, url: String, cacheKey: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("${song.id}|${song.album}|${song.mediaUri.orEmpty()}")
            .setUri(url.toUri())
            .setCustomCacheKey(cacheKey)
            .build()
    }

    /** 处理单曲播放结束：根据循环模式与随机三栈推进或停止 */
    private fun handleTrackEnded() {
        _playbackPositionMs.value = 0L

        // 检查睡眠定时器
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
            Player.REPEAT_MODE_ONE -> playAtIndex(currentIndex)
            Player.REPEAT_MODE_ALL -> next(force = true)
            else -> {
                if (player.shuffleModeEnabled) {
                    if (shuffleFuture.isNotEmpty() || shuffleBag.isNotEmpty()) next(force = false)
                    else stopPlaybackPreservingQueue()
                } else {
                    if (currentIndex < currentPlaylist.lastIndex) next(force = false)
                    else stopPlaybackPreservingQueue()
                }
            }
        }
    }

    private data class PersistedState(
        val playlist: List<SongItem>,
        val index: Int,
        val mediaUrl: String? = null,
        val positionMs: Long = 0L,
        val shouldResumePlayback: Boolean = false,
        val repeatMode: Int? = null,
        val shuffleEnabled: Boolean? = null
    )


    fun initialize(app: Application, maxCacheSize: Long = 1024L * 1024 * 1024) {
        if (initialized) return
        application = app
        currentCacheSize = maxCacheSize

        ioScope = newIoScope()
        mainScope = newMainScope()

        runCatching {
            stateFile = File(app.filesDir, "last_playlist.json")
            runBlocking(Dispatchers.IO) {
                keepLastPlaybackProgressEnabled = settingsRepo.keepLastPlaybackProgressFlow.first()
                keepPlaybackModeStateEnabled = settingsRepo.keepPlaybackModeStateFlow.first()
            }

            // 基础网络请求工厂，支持 B 站 Cookie 注入
            val okHttpClient = AppContainer.sharedOkHttpClient
            val upstreamFactory: HttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
            val conditionalFactory = ConditionalHttpDataSourceFactory(upstreamFactory, biliCookieRepo)
            conditionalHttpFactory = conditionalFactory

            val defaultDsFactory = androidx.media3.datasource.DefaultDataSource.Factory(app, conditionalFactory)

            val finalDataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (maxCacheSize > 0) {
                val cacheDir = File(app.cacheDir, "media_cache")
                val dbProvider = StandaloneDatabaseProvider(app)

                cache = SimpleCache(
                    cacheDir,
                    LeastRecentlyUsedCacheEvictor(maxCacheSize),
                    dbProvider
                )

                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(defaultDsFactory)
                    .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            } else {
                NPLogger.d("NERI-Player", "Cache disabled by user setting (size=0).")
                defaultDsFactory
            }

            // 将最终的数据源工厂传给 MediaSourceFactory
            val mediaSourceFactory = DefaultMediaSourceFactory(finalDataSourceFactory)

            val renderersFactory = ReactiveRenderersFactory(app)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            player = ExoPlayer.Builder(app, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    setWakeMode(C.WAKE_MODE_NETWORK)
                }
            applyAudioFocusPolicy()

            val audioOffload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                )
                .build()

            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(audioOffload)
                .build()

            // 启动时就禁止 Exo 列表循环，由我们自己接管（仅单曲循环放给 Exo）
            player.repeatMode = Player.REPEAT_MODE_OFF

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    NPLogger.e("NERI-Player", "onPlayerError: ${error.errorCodeName}", error)
                    consecutivePlayFailures++

                    // 检查是否是离线缓存播放失败
                    val currentUrl = _currentMediaUrl.value
                    val isOfflineCache = currentUrl?.startsWith("http://offline.cache/") == true

                    val cause = error.cause
                    if (shouldAttemptUrlRefresh(error, _currentSongFlow.value, isOfflineCache)) {
                        refreshCurrentSongUrl(
                            resumePositionMs = player.currentPosition,
                            allowFallback = false,
                            reason = "playback_error_${error.errorCodeName}"
                        )
                        return
                    }
                    val msg = when {
                        isOfflineCache -> {
                            NPLogger.w("NERI-Player", "离线缓存播放失败，暂停当前歌曲等待重新恢复")
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

                    val shouldSkip =
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                        cause?.message?.contains("no protocol: null", ignoreCase = true) == true

                    if (isOfflineCache) {
                        pause()
                    } else if (shouldSkip) {
                        mainScope.launch { handleTrackEnded() }
                    } else {
                        pause()
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        handleTrackEndedIfNeeded(source = "playback_state_changed")
                    } else if (lastHandledTrackEndKey != null) {
                        lastHandledTrackEndKey = null
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
                    if (!playWhenReady) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "playWhenReady=false, reason=${playWhenReadyChangeReasonName(reason)}, state=${playbackStateName(player.playbackState)}, mediaId=${player.currentMediaItem?.mediaId}"
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
                    // 不接受 Exo 的列表循环（ALL）；仅维持单曲循环或关闭
                    syncExoRepeatMode()
                    _repeatModeFlow.value = repeatModeSetting
                }
            })

        player.playWhenReady = false

        // 订阅音质设置
        ioScope.launch {
            settingsRepo.audioQualityFlow.collect { q -> preferredQuality = q }
        }
        ioScope.launch {
            settingsRepo.biliAudioQualityFlow.collect { q -> biliPreferredQuality = q }
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

        // 同步本地歌单
        ioScope.launch {
            localRepo.playlists.collect { repoLists ->
                _playlistsFlow.value = deepCopyPlaylists(repoLists)
            }
        }

        setupAudioDeviceCallback()
        restoreState()

        // 初始化睡眠定时器（使用最新 scope）
        sleepTimerManager = createSleepTimerManager()

        // 初始化完成后检查是否有待播放项并尝试同步前台服务
        initialized = true
        NPLogger.d("NERI-Player", "PlayerManager initialized with cache size: $maxCacheSize")
        }.onFailure { e ->
            NPLogger.e("NERI-Player", "PlayerManager initialize failed", e)
            runCatching { conditionalHttpFactory?.close() }
            conditionalHttpFactory = null
            runCatching { if (::player.isInitialized) player.release() }
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
                // 清理音频缓存
                if (clearAudio) {
                    if (::cache.isInitialized) {
                        val keysSnapshot = HashSet(cache.keys)
                        keysSnapshot.forEach { key ->
                            try {
                                val resource = cache.getCachedSpans(key)
                                resource.forEach { totalSpaceFreed += it.length }

                                cache.removeResource(key)
                                apiRemovedCount++
                            } catch (e: Exception) { /* 忽略单个失败 */ }
                        }
                    }

                    val cacheDir = File(application.cacheDir, "media_cache")

                    if (cacheDir.exists() && cacheDir.isDirectory) {
                        val files = cacheDir.listFiles() ?: emptyArray()

                        files.forEach { file ->
                            if (file.isFile && file.name.endsWith(".exo")) {
                                if (file.delete()) {
                                    physicalDeletedCount++
                                }
                            }
                        }
                    }
                }

                // 清理图片缓存
                if (clearImage) {
                    val imageCacheDir = File(application.cacheDir, "image_cache")
                    if (imageCacheDir.exists() && imageCacheDir.isDirectory) {
                        val deleted = imageCacheDir.deleteRecursively()
                        if (deleted) {
                            // 重新创建目录
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
        // 保存引用以便 release 时注销，避免内存泄漏
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

    // 蓝牙路由切换常有瞬时抖动，这里二次确认后再暂停，避免误伤正常播放
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
                    name = bluetoothDevice.productName.toString().ifBlank { getLocalizedString(R.string.device_bluetooth_headset) },
                    type = bluetoothDevice.type,
                    icon = Icons.Default.BluetoothAudio
                )
            } catch (_: SecurityException) {
                AudioDevice(getLocalizedString(R.string.device_bluetooth_headset), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, Icons.Default.BluetoothAudio)
            }
        }
        val wiredHeadset = devices.firstOrNull { isWiredOutputType(it.type) }
        if (wiredHeadset != null) {
            return AudioDevice(getLocalizedString(R.string.device_wired_headset), wiredHeadset.type, Icons.Default.Headset)
        }
        return AudioDevice(getLocalizedString(R.string.device_speaker), AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, Icons.Default.SpeakerGroup)
    }

    private fun isBluetoothOutputType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER
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
            mainScope.launch { runCatching { player.volume = 1f } }
        }
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

    private fun startPlayerPlaybackWithFade(
        shouldFadeIn: Boolean,
        fadeDurationMs: Long = playbackFadeInDurationMs
    ) {
        cancelVolumeFade(resetToFull = !shouldFadeIn)
        val durationMs = fadeDurationMs.coerceAtLeast(0L)
        if (!shouldFadeIn || durationMs <= 0L) {
            mainScope.launch {
                if (!::player.isInitialized) return@launch
                player.volume = 1f
                player.playWhenReady = true
                player.play()
            }
            return
        }

        mainScope.launch {
            if (!::player.isInitialized) return@launch
            player.volume = 0f
            player.playWhenReady = true
            player.play()
        }

        val steps = fadeStepsFor(durationMs)
        if (steps <= 0) return
        val stepDelay = (durationMs / steps).coerceAtLeast(1L)
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

    fun playPlaylist(songs: List<SongItem>, startIndex: Int) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        if (songs.isEmpty()) {
            NPLogger.w("NERI-Player", "playPlaylist called with EMPTY list")
            return
        }
        suppressAutoResumeForCurrentSession = false
        consecutivePlayFailures = 0
        currentPlaylist = songs
        _currentQueueFlow.value = currentPlaylist
        currentIndex = startIndex.coerceIn(0, songs.lastIndex)

        // 清空历史与未来，重建洗牌袋
        shuffleHistory.clear()
        shuffleFuture.clear()
        if (player.shuffleModeEnabled) {
            rebuildShuffleBag(excludeIndex = currentIndex)
        } else {
            shuffleBag.clear()
        }

        playAtIndex(currentIndex)
        ioScope.launch {
            persistState()
        }
    }

    /** 重建随机抽签袋，必要时排除当前曲，避免同曲立刻连播 */
    private fun rebuildShuffleBag(excludeIndex: Int? = null) {
        shuffleBag = currentPlaylist.indices.toMutableList()
        if (excludeIndex != null) shuffleBag.remove(excludeIndex)
        shuffleBag.shuffle()
    }

    private fun playAtIndex(
        index: Int,
        resumePositionMs: Long = 0L,
        useTrackTransitionFade: Boolean = false
    ) {
        if (currentPlaylist.isEmpty() || index !in currentPlaylist.indices) {
            NPLogger.w("NERI-Player", "playAtIndex called with invalid index: $index")
            return
        }

        if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
            NPLogger.e("NERI-PlayerManager", "已连续失败 $consecutivePlayFailures 次，停止播放")
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
        _currentSongFlow.value = song
        _currentMediaUrl.value = null
        currentMediaUrlResolvedAtMs = 0L
        resumePlaybackRequested = true
        restoredShouldResumePlayback = false
        restoredResumePositionMs = 0L
        ioScope.launch {
            persistState(positionMs = resumePositionMs.coerceAtLeast(0L), shouldResumePlayback = true)
        }

        // 当前曲不应再出现在洗牌袋中
        if (player.shuffleModeEnabled) {
            shuffleBag.remove(index)
        }

        playJob?.cancel()
        playbackRequestToken += 1
        val requestToken = playbackRequestToken
        _playbackPositionMs.value = 0L
        playJob = ioScope.launch {
            val result = resolveSongUrl(song)
            if (requestToken != playbackRequestToken || !isActive) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "忽略已过期的播放请求: song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
                )
                return@launch
            }

            when (result) {
                is SongUrlResult.Success -> {
                    consecutivePlayFailures = 0

                    val cacheKey = computeCacheKey(song)
                    NPLogger.d("NERI-PlayerManager", "Using custom cache key: $cacheKey for song: ${song.name}")

                    val mediaItem = buildMediaItem(song, result.url, cacheKey)

                    _currentMediaUrl.value = result.url
                    currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
                    persistState(
                        positionMs = resumePositionMs.coerceAtLeast(0L),
                        shouldResumePlayback = true
                    )
                    if (requestToken != playbackRequestToken || !isActive) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "媒体项准备前请求已失效: song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
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
                        player.setMediaItem(mediaItem)
                        // 每次切歌后都钳制 Exo 的循环状态，避免单媒体项“列表循环”
                        syncExoRepeatMode()
                        syncExoRepeatMode()
                        player.prepare()
                        if (resumePositionMs > 0L) {
                            player.seekTo(resumePositionMs)
                            _playbackPositionMs.value = resumePositionMs
                        }
                        startPlayerPlaybackWithFade(
                            shouldFadeIn = useTrackTransitionFade || playbackFadeInEnabled,
                            fadeDurationMs = if (useTrackTransitionFade) {
                                playbackCrossfadeInDurationMs
                            } else {
                                playbackFadeInDurationMs
                            }
                        )
                    }
                    maybeAutoMatchBiliMetadata(song, requestToken)
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
                    NPLogger.e("NERI-PlayerManager", "获取播放 URL 失败, 跳过: id=${song.id}, source=${song.album}")
                    consecutivePlayFailures++
                    withContext(Dispatchers.Main) { next() } // 自动跳到下一首
                }
            }
        }
    }

    private fun maybeAutoMatchBiliMetadata(song: SongItem, requestToken: Long) {
        if (!song.album.startsWith(BILI_SOURCE_TAG)) return
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

    private suspend fun resolveSongUrl(song: SongItem): SongUrlResult {
        if (isLocalSong(song)) {
            val localMediaUri = localMediaSource(song)
            if (localMediaUri != null && isReadableLocalMediaUri(localMediaUri)) {
                return SongUrlResult.Success(toPlayableLocalUrl(localMediaUri) ?: localMediaUri)
            }
            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
            return SongUrlResult.Failure
        }

        // 优先检查本地下载的文件
        val localResult = checkLocalCache(song)
        if (localResult != null) return localResult

        val cacheKey = computeCacheKey(song)
        val hasCachedData = checkExoPlayerCache(cacheKey)
        val result = when {
            isYouTubeMusicSong(song) -> getYouTubeMusicAudioUrl(song, suppressError = hasCachedData)
            song.album.startsWith(BILI_SOURCE_TAG) -> getBiliAudioUrl(song, suppressError = hasCachedData)
            else -> getNeteaseSongUrl(song.id, suppressError = hasCachedData)
        }

        // 如果网络失败但有缓存，使用虚拟URL让ExoPlayer使用缓存
        return if (result is SongUrlResult.Failure && hasCachedData) {
            NPLogger.d("NERI-PlayerManager", "网络失败但有缓存，尝试离线播放: $cacheKey")
            // 使用虚拟URL，ExoPlayer会因为customCacheKey自动使用缓存
            SongUrlResult.Success("http://offline.cache/$cacheKey")
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

    private fun refreshCurrentSongUrl(
        resumePositionMs: Long,
        allowFallback: Boolean,
        reason: String
    ) {
        val song = _currentSongFlow.value ?: return
        if (isLocalSong(song)) return
        if (urlRefreshInProgress) {
            if (allowFallback) {
                mainScope.launch {
                    player.playWhenReady = true
                    player.play()
                }
            }
            return
        }

        val cacheKey = computeCacheKey(song)
        val now = SystemClock.elapsedRealtime()
        if (lastUrlRefreshKey == cacheKey && now - lastUrlRefreshAtMs < URL_REFRESH_COOLDOWN_MS) {
            if (allowFallback) {
                mainScope.launch {
                    player.playWhenReady = true
                    player.play()
                }
            } else {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
                mainScope.launch { pause() }
            }
            return
        }

        urlRefreshInProgress = true
        lastUrlRefreshKey = cacheKey
        lastUrlRefreshAtMs = now

        ioScope.launch {
            try {
                NPLogger.d("NERI-PlayerManager", "Refreshing stream url ($reason): $cacheKey")
                val result = resolveSongUrl(song)
                if (result is SongUrlResult.Success &&
                    _currentSongFlow.value?.sameIdentityAs(song) == true
                ) {
                    applyResolvedMediaItem(song, result.url, resumePositionMs)
                    consecutivePlayFailures = 0
                } else if (allowFallback) {
                    withContext(Dispatchers.Main) {
                        player.playWhenReady = true
                        player.play()
                    }
                } else {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
                    withContext(Dispatchers.Main) { pause() }
                }
            } finally {
                urlRefreshInProgress = false
            }
        }
    }

    private suspend fun applyResolvedMediaItem(
        song: SongItem,
        url: String,
        resumePositionMs: Long
    ) {
        if (_currentSongFlow.value?.sameIdentityAs(song) != true) return

        val cacheKey = computeCacheKey(song)
        val mediaItem = buildMediaItem(song, url, cacheKey)

        _currentMediaUrl.value = url
        currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
        persistState()

        withContext(Dispatchers.Main) {
            player.setMediaItem(mediaItem)
            syncExoRepeatMode()
            player.prepare()
            if (resumePositionMs > 0) {
                player.seekTo(resumePositionMs)
            }
            player.playWhenReady = true
            player.play()
        }
    }

    /** 检查歌曲是否有本地缓存，如果有则优先使用本地文件 */
    private fun checkLocalCache(song: SongItem): SongUrlResult? {
        val context = application
        val localPath = AudioDownloadManager.getLocalFilePath(context, song)
        return if (localPath != null) {
            SongUrlResult.Success("file://$localPath")
        } else null
    }

    /** 只有完整缓存才允许离线兜底，避免半首歌缓存被误当成可播放资源 */
    private fun checkExoPlayerCache(cacheKey: String): Boolean {
        return try {
            if (!::cache.isInitialized) return false

            val cachedSpans = cache.getCachedSpans(cacheKey)
            if (cachedSpans.isEmpty()) return false

            val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
            if (contentLength <= 0L) {
                NPLogger.d("NERI-PlayerManager", "缓存长度未知，不启用离线兜底: $cacheKey")
                return false
            }

            val orderedSpans = cachedSpans.sortedBy { it.position }
            var coveredUntil = 0L
            for (span in orderedSpans) {
                if (span.position > coveredUntil) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "缓存存在空洞，不启用离线兜底: $cacheKey @ ${span.position}"
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
                    "找到完整缓存数据: $cacheKey, 长度: $contentLength, 片段数: ${cachedSpans.size}"
                )
            } else {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "缓存未完整覆盖，不启用离线兜底: $cacheKey, 已覆盖: $coveredUntil/$contentLength"
                )
            }

            isComplete
        } catch (e: Exception) {
            NPLogger.w("NERI-PlayerManager", "检查缓存失败: ${e.message}")
            false
        }
    }

    private suspend fun getNeteaseSongUrl(songId: Long, suppressError: Boolean = false): SongUrlResult = withContext(Dispatchers.IO) {
        try {
            val resp = neteaseClient.getSongDownloadUrl(
                songId,
                level = preferredQuality
            )
            NPLogger.d("NERI-PlayerManager", "id=$songId, resp=$resp")

            val root = JSONObject(resp)
            when (root.optInt("code")) {
                301 -> SongUrlResult.RequiresLogin
                200 -> {
                    val url = when (val dataObj = root.opt("data")) {
                        is JSONObject -> dataObj.optString("url", "")
                        is JSONArray -> dataObj.optJSONObject(0)?.optString("url", "")
                        else -> ""
                    }
                    if (url.isNullOrBlank()) {
                        if (!suppressError) {
                            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                        }
                        SongUrlResult.Failure
                    } else {
                        val finalUrl = if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url
                        SongUrlResult.Success(finalUrl)
                    }
                }
                else -> {
                    if (!suppressError) {
                        postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                    }
                    SongUrlResult.Failure
                }
            }
        } catch (e: Exception) {
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

            val audioStream = biliRepo.getBestPlayableAudio(resolved.videoInfo.bvid, resolved.cid)

            if (audioStream?.url != null) {
                NPLogger.d("NERI-PlayerManager-BiliAudioUrl", audioStream.url)
                SongUrlResult.Success(audioStream.url)
            } else {
                if (!suppressError) {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                }
                SongUrlResult.Failure
            }
        } catch (e: Exception) {
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
        suppressError: Boolean = false
    ): SongUrlResult = withContext(Dispatchers.IO) {
        val videoId = extractYouTubeMusicVideoId(song.mediaUri)
        if (videoId.isNullOrBlank()) {
            if (!suppressError) {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
            }
            return@withContext SongUrlResult.Failure
        }

        try {
            val url = youtubeMusicPlaybackRepository.getBestPlayableAudioUrl(videoId)
            if (!url.isNullOrBlank()) {
                SongUrlResult.Success(url)
            } else {
                if (!suppressError) {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                }
                SongUrlResult.Failure
            }
        } catch (e: Exception) {
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
     * 播放 Bilibili 视频的所有分 P
     * @param videoInfo 包含所有分 P 信息的视频详情对象
     * @param startIndex 从第几个分 P 开始播放
     * @param coverUrl 封面 URL
     */
    fun playBiliVideoParts(videoInfo: BiliClient.VideoBasicInfo, startIndex: Int, coverUrl: String) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        val songs = videoInfo.pages.map { page -> buildBiliPartSong(page, videoInfo, coverUrl) }
        playPlaylist(songs, startIndex)
    }

    fun play() {
        ensureInitialized()
        if (!initialized) return
        suppressAutoResumeForCurrentSession = false
        resumePlaybackRequested = true
        val song = _currentSongFlow.value
        if (isPreparedInPlayer() && song != null && !isLocalSong(song)) {
            val url = _currentMediaUrl.value
            if (!url.isNullOrBlank()) {
                val ageMs = if (currentMediaUrlResolvedAtMs > 0L) {
                    SystemClock.elapsedRealtime() - currentMediaUrlResolvedAtMs
                } else {
                    Long.MAX_VALUE
                }
                if (ageMs >= MEDIA_URL_STALE_MS) {
                    refreshCurrentSongUrl(
                        resumePositionMs = player.currentPosition,
                        allowFallback = true,
                        reason = "stale_resume"
                    )
                    return
                }
            }
        }
        when {
            isPreparedInPlayer() -> {
                syncExoRepeatMode()
                startPlayerPlaybackWithFade(
                    shouldFadeIn = playbackFadeInEnabled,
                    fadeDurationMs = playbackFadeInDurationMs
                )
                val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
                _playbackPositionMs.value = resumePositionMs
                ioScope.launch {
                    persistState(
                        positionMs = resumePositionMs,
                        shouldResumePlayback = true
                    )
                }
            }
            currentPlaylist.isNotEmpty() && currentIndex != -1 -> {
                val resumePositionMs = if (keepLastPlaybackProgressEnabled) {
                    maxOf(restoredResumePositionMs, _playbackPositionMs.value).coerceAtLeast(0L)
                } else {
                    0L
                }
                playAtIndex(currentIndex, resumePositionMs = resumePositionMs)
            }
            currentPlaylist.isNotEmpty() -> playAtIndex(0)
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
                "忽略重复的结束回调: source=$source, key=$currentKey"
            )
            return
        }
        lastHandledTrackEndKey = currentKey
        NPLogger.d(
            "NERI-PlayerManager",
            "处理播放结束: source=$source, key=$currentKey, index=$currentIndex, queueSize=${currentPlaylist.size}"
        )
        handleTrackEnded()
    }

    fun pause(forcePersist: Boolean = false) {
        ensureInitialized()
        if (!initialized) return
        val shouldFadeOut =
            playbackFadeInEnabled && playbackFadeOutDurationMs > 0L && ::player.isInitialized
        if (shouldFadeOut) {
            mainScope.launch {
                fadeOutCurrentPlaybackIfNeeded(
                    enabled = true,
                    fadeOutDurationMs = playbackFadeOutDurationMs
                )
                pauseInternal(forcePersist, resetVolumeToFull = false)
            }
        } else {
            pauseInternal(forcePersist, resetVolumeToFull = true)
        }
    }

    private fun pauseInternal(forcePersist: Boolean, resetVolumeToFull: Boolean) {
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
        NPLogger.d(
            "NERI-PlayerManager",
            "pauseInternal: song=${currentSong?.name}, positionMs=$currentPosition, state=${playbackStateName(player.playbackState)}, playWhenReady=${player.playWhenReady}, forcePersist=$forcePersist"
        )
        player.playWhenReady = false
        player.pause()
        if (shouldForceFlushShortLocalSong) {
            // 超短本地音频在极端编码下可能把已排队的 PCM 继续播完，这里用一次同位 seek 强制刷新渲染链
            runCatching {
                player.seekTo(currentPosition.coerceAtMost(expectedDuration.coerceAtLeast(0L)))
            }
            _playbackPositionMs.value = currentPosition
        }
        if (!resetVolumeToFull) {
            mainScope.launch {
                if (::player.isInitialized) {
                    player.volume = 1f
                }
            }
        }
        if (forcePersist) {
            runBlocking(Dispatchers.IO) {
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

    fun seekTo(positionMs: Long) {
        ensureInitialized()
        if (!initialized) return
        player.seekTo(positionMs)
        _playbackPositionMs.value = positionMs
        ioScope.launch {
            persistState(
                positionMs = positionMs.coerceAtLeast(0L),
                shouldResumePlayback = shouldResumePlaybackSnapshot()
            )
        }
    }

    fun next(force: Boolean = false) {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) return
        val isShuffle = player.shuffleModeEnabled
        val useTransitionFade =
            playbackCrossfadeNextEnabled && (player.isPlaying || player.playWhenReady)

        if (isShuffle) {
            // 如果有预定下一首，优先走它
            if (shuffleFuture.isNotEmpty()) {
                val nextIdx = shuffleFuture.removeAt(shuffleFuture.lastIndex)
                if (currentIndex != -1) shuffleHistory.add(currentIndex)
                currentIndex = nextIdx
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                return
            }

            // 没有预定下一首，需要抽新随机
            if (shuffleBag.isEmpty()) {
                if (force || repeatModeSetting == Player.REPEAT_MODE_ALL) {
                    rebuildShuffleBag(excludeIndex = currentIndex) // 新一轮，避免同曲连播
                } else {
                    NPLogger.d("NERI-Player", "Shuffle finished and repeat is off, stopping.")
                    stopPlaybackPreservingQueue()
                    return
                }
            }

            if (shuffleBag.isEmpty()) {
                // 仅一首歌等极端情况
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                return
            }

            if (currentIndex != -1) shuffleHistory.add(currentIndex)
            // 新随机 -> 断开未来路径
            shuffleFuture.clear()

            val pick = if (shuffleBag.size == 1) 0 else Random.nextInt(shuffleBag.size)
            currentIndex = shuffleBag.removeAt(pick)
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
        } else {
            // 顺序播放
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
        }
    }

    fun previous() {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) return
        val isShuffle = player.shuffleModeEnabled
        val useTransitionFade =
            playbackCrossfadeNextEnabled && (player.isPlaying || player.playWhenReady)

        if (isShuffle) {
            if (shuffleHistory.isNotEmpty()) {
                // 回退一步，同时把当前曲放到未来栈，以便再前进能回到原来的下一首
                if (currentIndex != -1) shuffleFuture.add(currentIndex)
                val prev = shuffleHistory.removeAt(shuffleHistory.lastIndex)
                currentIndex = prev
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            } else {
                NPLogger.d("NERI-Player", "No previous track in shuffle history.")
            }
        } else {
            if (currentIndex > 0) {
                currentIndex--
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            } else {
                if (repeatModeSetting == Player.REPEAT_MODE_ALL && currentPlaylist.isNotEmpty()) {
                    currentIndex = currentPlaylist.lastIndex
                    playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
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
        // 仅当单曲循环时让 Exo 循环；其余交给我们的队列推进
        syncExoRepeatMode()
        ioScope.launch {
            persistState()
        }
    }

    fun release() {
        if (!initialized) return
        resumePlaybackRequested = false

        try {
            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        } catch (_: Exception) { }
        audioDeviceCallback = null

        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
        playJob?.cancel()
        playJob = null

        if (::player.isInitialized) {
            runCatching { player.stop() }
            player.release()
        }
        if (::cache.isInitialized) {
            cache.release()
        }
        conditionalHttpFactory?.close()
        conditionalHttpFactory = null

        mainScope.cancel()
        ioScope.cancel()

        _isPlayingFlow.value = false
        _currentMediaUrl.value = null
        currentMediaUrlResolvedAtMs = 0L
        _currentSongFlow.value = null
        _currentQueueFlow.value = emptyList()
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
                val positionMs = player.currentPosition.coerceAtLeast(0L)
                _playbackPositionMs.value = positionMs
                maybePersistPlaybackProgress(positionMs)
                delay(40)
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
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        lastHandledTrackEndKey = null
        resumePlaybackRequested = false
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        _playbackPositionMs.value = 0L
        if (currentPlaylist.isEmpty()) {
            currentIndex = -1
            _currentSongFlow.value = null
            _currentMediaUrl.value = null
            currentMediaUrlResolvedAtMs = 0L
        } else {
            currentIndex = currentIndex.coerceIn(0, currentPlaylist.lastIndex)
            _currentSongFlow.value = currentPlaylist.getOrNull(currentIndex)
            if (clearMediaUrl) {
                _currentMediaUrl.value = null
                currentMediaUrlResolvedAtMs = 0L
            }
        }
        consecutivePlayFailures = 0
        ioScope.launch {
            persistState()
        }
    }

    fun hasItems(): Boolean = currentPlaylist.isNotEmpty()


    /** 添加当前歌到“我喜欢的音乐” */
    fun addCurrentToFavorites() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        val updatedLists = optimisticUpdateFavorites(add = true, song = song)
        _playlistsFlow.value = deepCopyPlaylists(updatedLists)
        ioScope.launch {
            try {
                localRepo.addToFavorites(song)
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "addToFavorites failed: ${e.message}", e)
            }
        }
    }

    /** 从“我喜欢的音乐”移除当前歌 */
    fun removeCurrentFromFavorites() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        val updatedLists = optimisticUpdateFavorites(add = false, song = song)
        _playlistsFlow.value = deepCopyPlaylists(updatedLists)
        ioScope.launch {
            try {
                localRepo.removeFromFavorites(song)
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "removeFromFavorites failed: ${e.message}", e)
            }
        }
    }

    /** 切换收藏状态 */
    fun toggleCurrentFavorite() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        val fav = FavoritesPlaylist.firstOrNull(_playlistsFlow.value, application)
        val isFav = fav?.songs?.any { it.sameIdentityAs(song) } == true
        if (isFav) removeCurrentFromFavorites() else addCurrentToFavorites()
    }

    /** 本地乐观修改收藏歌单 */
    private fun optimisticUpdateFavorites(
        add: Boolean,
        song: SongItem? = null
    ): List<LocalPlaylist> {
        val lists = _playlistsFlow.value
        val favIdx = lists.indexOfFirst { FavoritesPlaylist.isSystemPlaylist(it, application) }
        val base = lists.map {
            LocalPlaylist(
                id = it.id,
                name = it.name,
                songs = it.songs.toMutableList(),
                modifiedAt = it.modifiedAt,
                customCoverUrl = it.customCoverUrl
            )
        }.toMutableList()

        if (favIdx >= 0) {
            val fav = base[favIdx]
            if (add && song != null) {
                if (fav.songs.none { it.sameIdentityAs(song) }) fav.songs.add(song)
            } else if (!add && song != null) {
                fav.songs.removeAll { it.sameIdentityAs(song) }
            }
        } else {
            if (add && song != null) {
                base += LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = getLocalizedString(R.string.favorite_my_music),
                    songs = mutableListOf(song)
                )
            }
        }
        return base
    }

    /** 深拷贝列表，确保 Compose 稳定重组 */
    private fun deepCopyPlaylists(src: List<LocalPlaylist>): List<LocalPlaylist> {
        return src.map { pl ->
            LocalPlaylist(
                id = pl.id,
                name = pl.name,
                songs = pl.songs.toMutableList(),
                modifiedAt = pl.modifiedAt,
                customCoverUrl = pl.customCoverUrl
            )
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
                        playlist = playlistSnapshot,
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

    /**
     * 让 playBiliVideoAsAudio 也使用统一的 playPlaylist 入口
     */
    fun playBiliVideoAsAudio(videos: List<BiliVideoItem>, startIndex: Int) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        if (videos.isEmpty()) {
            NPLogger.w("NERI-Player", "playBiliVideoAsAudio called with EMPTY list")
            return
        }
        // 转换为通用的 SongItem 列表，然后调用统一的播放入口
        val songs = videos.map { it.toSongItem() }
        playPlaylist(songs, startIndex)
    }


    /** 获取网易云歌词 */
    suspend fun getNeteaseLyrics(songId: Long): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = neteaseClient.getLyricNew(songId)
                val lrc = JSONObject(raw).optJSONObject("lrc")?.optString("lyric") ?: ""
                parseNeteaseLrc(lrc)
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "getNeteaseLyrics failed: ${e.message}", e)
                emptyList()
            }
        }
    }

    /** 获取网易云歌词翻译（tlyric） */
    suspend fun getNeteaseTranslatedLyrics(songId: Long): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = neteaseClient.getLyricNew(songId)
                val tlyric = JSONObject(raw).optJSONObject("tlyric")?.optString("lyric") ?: ""
                if (tlyric.isBlank()) emptyList() else parseNeteaseLrc(tlyric)
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "getNeteaseTranslatedLyrics failed: ${e.message}", e)
                emptyList()
            }
        }
    }

    /** 同时获取歌词与翻译 */
    suspend fun getNeteaseLyricsWithTrans(songId: Long): Pair<List<LyricEntry>, List<LyricEntry>> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = neteaseClient.getLyricNew(songId)
                val obj = JSONObject(raw)
                val lrc = obj.optJSONObject("lrc")?.optString("lyric") ?: ""
                val tlyric = obj.optJSONObject("tlyric")?.optString("lyric") ?: ""
                val base = if (lrc.isBlank()) emptyList() else parseNeteaseLrc(lrc)
                val trans = if (tlyric.isBlank()) emptyList() else parseNeteaseLrc(tlyric)
                base to trans
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "getNeteaseLyricsWithTrans failed: ${e.message}", e)
                emptyList<LyricEntry>() to emptyList()
            }
        }
    }

    /** 根据歌曲来源返回可用的翻译（如果有） */
    suspend fun getTranslatedLyrics(song: SongItem): List<LyricEntry> {
        val context = application

        // 优先检查本地翻译歌词缓存
        val localTransPath = AudioDownloadManager.getTranslatedLyricFilePath(context, song)
        if (localTransPath != null) {
            try {
                val transContent = File(localTransPath).readText()
                return parseNeteaseLrc(transContent)
            } catch (e: Exception) {
                NPLogger.w("NERI-PlayerManager", "本地翻译歌词读取失败: ${e.message}")
            }
        }

        // 本地没有，从网络获取
        // B站歌曲在匹配网易云信息后应使用匹配到的歌曲 ID 获取翻译
        if (isYouTubeMusicSong(song)) {
            return emptyList()
        }

        if (song.album.startsWith(BILI_SOURCE_TAG)) {
            return when (song.matchedLyricSource) {
                MusicPlatform.CLOUD_MUSIC -> {
                    val matchedId = song.matchedSongId?.toLongOrNull()
                    if (matchedId != null) getNeteaseTranslatedLyrics(matchedId) else emptyList()
                }
                else -> emptyList()
            }
        }

        return when (song.matchedLyricSource) {
            null, MusicPlatform.CLOUD_MUSIC -> getNeteaseTranslatedLyrics(song.id)
            else -> emptyList()
        }
    }

    /** 获取歌词，优先使用本地缓存 */
    suspend fun getLyrics(song: SongItem): List<LyricEntry> {
        if (isYouTubeMusicSong(song)) {
            return emptyList()
        }
        // 最优先使用song.matchedLyric中的歌词
        if (!song.matchedLyric.isNullOrBlank()) {
            try {
                return parseNeteaseLrc(song.matchedLyric)
            } catch (e: Exception) {
                NPLogger.w("NERI-PlayerManager", "匹配歌词解析失败: ${e.message}")
            }
        }

        // 其次检查本地歌词缓存
        val context = application
        val localLyricPath = AudioDownloadManager.getLyricFilePath(context, song)
        if (localLyricPath != null) {
            try {
                val lrcContent = File(localLyricPath).readText()
                return parseNeteaseLrc(lrcContent)
            } catch (e: Exception) {
                NPLogger.w("NERI-PlayerManager", "本地歌词读取失败: ${e.message}")
            }
        }

        // 最后回退到在线获取
        return if (isYouTubeMusicSong(song)) {
            emptyList()
        } else if (song.album.startsWith(BILI_SOURCE_TAG)) {
            emptyList() // B站暂时没有歌词API
        } else {
            getNeteaseLyrics(song.id)
        }
    }

    fun playFromQueue(index: Int) {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) return
        if (index !in currentPlaylist.indices) return

        // 用户点选队列，视作新路径分叉
        if (player.shuffleModeEnabled) {
            if (currentIndex != -1) shuffleHistory.add(currentIndex)
            shuffleFuture.clear()
            shuffleBag.remove(index)
        }

        currentIndex = index
        playAtIndex(index)
    }

    /**
     * 将歌曲添加到播放队列的下一个位置
     * @param song 要添加的歌曲
     */
    fun addToQueueNext(song: SongItem) {
        ensureInitialized()
        if (!initialized) return

        // 空队列特殊处理
        if (currentPlaylist.isEmpty()) {
            playPlaylist(listOf(song), 0)
            return
        }

        val currentSong = _currentSongFlow.value
        val newPlaylist = currentPlaylist.toMutableList()
        var insertIndex = (currentIndex + 1).coerceIn(0, newPlaylist.size + 1)

        // 检查歌曲是否已存在
        val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
        if (existingIndex != -1) {
            newPlaylist.removeAt(existingIndex)
            // 如果移除的歌曲在插入位置之前，插入位置需要前移一位
            if (existingIndex < insertIndex) {
                insertIndex--
            }
        }

        // 确保索引安全
        insertIndex = insertIndex.coerceIn(0, newPlaylist.size)
        newPlaylist.add(insertIndex, song)

        // 更新列表
        currentPlaylist = newPlaylist
        _currentQueueFlow.value = currentPlaylist
        if (currentSong != null) {
            currentIndex = queueIndexOf(currentSong, newPlaylist)
        } else {
            currentIndex = currentIndex.coerceIn(0, newPlaylist.lastIndex)
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


    /**
     * 将歌曲添加到播放队列的末尾
     * @param song 要添加的歌曲
     */
    fun addToQueueEnd(song: SongItem) {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) {
            // 如果当前没有播放队列，直接播放这首歌
            playPlaylist(listOf(song), 0)
            return
        }

        val currentSong = _currentSongFlow.value
        val newPlaylist = currentPlaylist.toMutableList()

        // 检查歌曲是否已存在于队列中
        val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
        if (existingIndex != -1) {
            newPlaylist.removeAt(existingIndex)
        }

        newPlaylist.add(song)

        // 更新播放队列
        currentPlaylist = newPlaylist
        _currentQueueFlow.value = currentPlaylist
        currentIndex = if (currentSong != null) {
            queueIndexOf(currentSong, newPlaylist).takeIf { it >= 0 }
                ?: currentIndex.coerceIn(0, newPlaylist.lastIndex)
        } else {
            currentIndex.coerceIn(0, newPlaylist.lastIndex)
        }

        // 如果启用了随机播放，需要重建随机播放袋
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
            currentPlaylist = sanitizeRestoredPlaylist(data.playlist)
            if (currentPlaylist.isEmpty()) {
                currentIndex = -1
                _currentQueueFlow.value = emptyList()
                _currentSongFlow.value = null
                _currentMediaUrl.value = null
                _playbackPositionMs.value = 0L
                currentMediaUrlResolvedAtMs = 0L
                restoredResumePositionMs = 0L
                restoredShouldResumePlayback = false
                resumePlaybackRequested = false
                return
            }
            val preferredSong = data.playlist.getOrNull(data.index)
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
                    _currentSongFlow.value?.let(::isReadableLocalSong) == true
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
            resumePlaybackRequested = restoredShouldResumePlayback
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
        playAtIndex(resumeIndex, resumePositionMs = resumePositionMs)
        return resumePositionMs
    }

    fun rearmRestoredPlayback(positionMs: Long): Boolean {
        ensureInitialized()
        if (!initialized) return false
        if (currentPlaylist.isEmpty() || currentIndex !in currentPlaylist.indices) return false
        val resumePositionMs = positionMs.coerceAtLeast(0L)
        restoredResumePositionMs = resumePositionMs
        restoredShouldResumePlayback = true
        suppressAutoResumeForCurrentSession = false
        resumePlaybackRequested = true
        ioScope.launch {
            persistState(positionMs = resumePositionMs, shouldResumePlayback = true)
        }
        return true
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
            runBlocking(Dispatchers.IO) {
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
                    originalSong.copy(
                        name = newDetails.songName,
                        artist = newDetails.singer,
                        coverUrl = newDetails.coverUrl,
                        // 直接使用获取的歌词，如果为null则清除现有歌词（B站音源默认无歌词）
                        matchedLyric = newDetails.lyric,
                        matchedTranslatedLyric = newDetails.translatedLyric,
                        matchedLyricSource = selectedSong.source,
                        matchedSongId = selectedSong.id,
                        // 清除所有自定义字段，强制使用获取的信息
                        customCoverUrl = null,
                        customName = null,
                        customArtist = null,
                        // 保存原始值以便还原
                        originalName = originalSong.originalName ?: originalSong.name,
                        originalArtist = originalSong.originalArtist ?: originalSong.artist,
                        originalCoverUrl = originalSong.originalCoverUrl ?: originalSong.coverUrl,
                        originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
                        originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric
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

    fun updateSongCustomInfo(
        originalSong: SongItem,
        customCoverUrl: String?,
        customName: String?,
        customArtist: String?
    ) {
        ioScope.launch {
            NPLogger.d("PlayerManager", "updateSongCustomInfo: id=${originalSong.id}, album='${originalSong.album}'")

            // 从当前播放列表中获取最新的歌曲状态,保留歌词等字段
            val currentSong = currentPlaylist.firstOrNull { it.sameIdentityAs(originalSong) } ?: originalSong

            val originalName = currentSong.originalName ?: currentSong.name
            val originalArtist = currentSong.originalArtist ?: currentSong.artist
            val originalCoverUrl = currentSong.originalCoverUrl ?: currentSong.coverUrl

            val normalizedCustomName = customName?.trim()
                ?.takeIf { it.isNotBlank() && it != originalName }
            val normalizedCustomArtist = customArtist?.trim()
                ?.takeIf { it.isNotBlank() && it != originalArtist }
            val normalizedCustomCoverUrl = customCoverUrl
                ?.takeIf { it.isNotBlank() && it != originalCoverUrl }

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

    fun restoreToOriginalMetadata(originalSong: SongItem) {
        ioScope.launch {
            try {
                val isBili = originalSong.album.startsWith(BILI_SOURCE_TAG)
                val isYouTubeMusic = isYouTubeMusicSong(originalSong)

                if (isLocalSong(originalSong)) {
                    val restoredName = originalSong.originalName ?: originalSong.name
                    val restoredArtist = originalSong.originalArtist ?: originalSong.artist
                    val restoredCover = originalSong.originalCoverUrl ?: originalSong.coverUrl
                    val updatedSong = originalSong.copy(
                        name = restoredName,
                        artist = restoredArtist,
                        coverUrl = restoredCover,
                        matchedLyric = null,
                        matchedTranslatedLyric = null,
                        matchedLyricSource = null,
                        matchedSongId = null,
                        customCoverUrl = null,
                        customName = null,
                        customArtist = null,
                        originalName = restoredName,
                        originalArtist = restoredArtist,
                        originalCoverUrl = restoredCover,
                        originalLyric = originalSong.originalLyric,
                        originalTranslatedLyric = originalSong.originalTranslatedLyric
                    )
                    updateSongInAllPlaces(originalSong, updatedSong)
                } else if (isYouTubeMusic) {
                    val restoredName = originalSong.originalName ?: originalSong.name
                    val restoredArtist = originalSong.originalArtist ?: originalSong.artist
                    val restoredCover = originalSong.originalCoverUrl ?: originalSong.coverUrl
                    val updatedSong = originalSong.copy(
                        name = restoredName,
                        artist = restoredArtist,
                        coverUrl = restoredCover,
                        matchedLyric = null,
                        matchedTranslatedLyric = null,
                        matchedLyricSource = null,
                        matchedSongId = null,
                        customCoverUrl = null,
                        customName = null,
                        customArtist = null,
                        originalName = restoredName,
                        originalArtist = restoredArtist,
                        originalCoverUrl = restoredCover,
                        originalLyric = originalSong.originalLyric,
                        originalTranslatedLyric = originalSong.originalTranslatedLyric
                    )
                    updateSongInAllPlaces(originalSong, updatedSong)
                } else if (isBili) {
                    val resolved = resolveBiliSong(originalSong, biliClient)
                        ?: throw IllegalStateException("无法解析 B 站视频信息")
                    val coverUrl = resolved.videoInfo.coverUrl.let {
                        if (it.startsWith("http://")) it.replaceFirst("http://", "https://") else it
                    }

                    val updatedSong = originalSong.copy(
                        name = resolved.pageInfo?.part ?: resolved.videoInfo.title,
                        artist = resolved.videoInfo.ownerName,
                        album = "$BILI_SOURCE_TAG|${resolved.cid}",
                        coverUrl = coverUrl,
                        matchedLyric = null,
                        matchedTranslatedLyric = null,
                        matchedLyricSource = null,
                        matchedSongId = null,
                        customCoverUrl = null,
                        customName = null,
                        customArtist = null,
                        originalName = resolved.pageInfo?.part ?: resolved.videoInfo.title,
                        originalArtist = resolved.videoInfo.ownerName,
                        originalCoverUrl = coverUrl,
                        originalLyric = null,
                        originalTranslatedLyric = null
                    )
                    updateSongInAllPlaces(originalSong, updatedSong)
                } else {
                    val songDetails = cloudMusicSearchApi?.getSongInfo(originalSong.id.toString())

                    if (songDetails != null) {
                        val coverUrl = songDetails.coverUrl?.let {
                            if (it.startsWith("http://")) it.replaceFirst("http://", "https://") else it
                        }

                        val updatedSong = originalSong.copy(
                            name = songDetails.songName,
                            artist = songDetails.singer,
                            coverUrl = coverUrl,
                            matchedLyric = null,
                            matchedTranslatedLyric = null,
                            matchedLyricSource = null,
                            matchedSongId = null,
                            customCoverUrl = null,
                            customName = null,
                            customArtist = null,
                            originalName = songDetails.songName,
                            originalArtist = songDetails.singer,
                            originalCoverUrl = coverUrl,
                            originalLyric = null,
                            originalTranslatedLyric = null
                        )
                        updateSongInAllPlaces(originalSong, updatedSong)
                    } else {
                        // 如果API调用失败，至少清除自定义字段
                        val updatedSong = originalSong.copy(
                            matchedLyric = null,
                            matchedTranslatedLyric = null,
                            matchedLyricSource = null,
                            matchedSongId = null,
                            customCoverUrl = null,
                            customName = null,
                            customArtist = null,
                            originalName = null,
                            originalArtist = null,
                            originalCoverUrl = null,
                            originalLyric = null,
                            originalTranslatedLyric = null
                        )
                        updateSongInAllPlaces(originalSong, updatedSong)
                    }
                }
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "恢复原始信息失败", e)
                mainScope.launch {
                    postPlayerEvent(
                        PlayerEvent.ShowError(
                            getLocalizedString(R.string.player_playback_restore_failed, e.message.orEmpty())
                        )
                    )
                }
            }
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

        // 从队列中获取最新的歌曲信息，避免覆盖其他字段
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

        // 从队列中获取最新的歌曲信息，避免覆盖其他字段
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

        // 打印播放列表中所有歌曲的ID和album，帮助调试匹配问题
//        NPLogger.e("PlayerManager", "=== 当前播放列表中的所有歌曲 ===")
//        currentPlaylist.forEachIndexed { index, song ->
//            NPLogger.e("PlayerManager", "[$index] id=${song.id}, album='${song.album}', name='${song.name}', hasLyric=${song.matchedLyric != null}")
//        }
//        NPLogger.e("PlayerManager", "=== 播放列表打印完毕 ===")

        val queueIndex = queueIndexOf(songToUpdate)
//        NPLogger.e("PlayerManager", "queueIndex=$queueIndex, currentPlaylist.size=${currentPlaylist.size}")

        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(
                matchedLyric = newLyrics,
                matchedTranslatedLyric = newTranslatedLyrics
            )
//            NPLogger.e("PlayerManager", "更新前: matchedLyric=${currentPlaylist[queueIndex].matchedLyric?.take(50)}")
//            NPLogger.e("PlayerManager", "更新后: matchedLyric=${updatedSong.matchedLyric?.take(50)}")
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
            NPLogger.e("PlayerManager", "已更新队列中的歌曲")
        } else {
            NPLogger.e("PlayerManager", "未找到歌曲在队列中！")
        }

        NPLogger.e("PlayerManager", "当前播放歌曲: id=${_currentSongFlow.value?.id}, album='${_currentSongFlow.value?.album}'")
        if (isCurrentSong(songToUpdate)) {
            val beforeUpdate = _currentSongFlow.value?.matchedLyric
            _currentSongFlow.value = _currentSongFlow.value?.copy(
                matchedLyric = newLyrics,
                matchedTranslatedLyric = newTranslatedLyrics
            )
            NPLogger.e("PlayerManager", "已更新当前播放歌曲: 更新前=${beforeUpdate?.take(50)}, 更新后=${_currentSongFlow.value?.matchedLyric?.take(50)}")
        } else {
            NPLogger.e("PlayerManager", "当前播放歌曲不匹配！")
        }

        // 从队列中获取最新的歌曲信息，避免覆盖其他字段
        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
            NPLogger.d("PlayerManager", "已持久化到数据库")
        } else {
            NPLogger.e("PlayerManager", "未找到最新歌曲！")
        }

        persistState()
        NPLogger.d("PlayerManager", "updateSongLyricsAndTranslation完成")
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

        persistState()
    }

}

private fun playbackStateName(state: Int): String {
    return when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }
}

private fun playWhenReadyChangeReasonName(reason: Int): String {
    return when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
        else -> "UNKNOWN($reason)"
    }
}

private fun BiliVideoItem.toSongItem(): SongItem {
    return SongItem(
        id = this.id, // avid
        name = this.title,
        artist = this.uploader,
        album = PlayerManager.BILI_SOURCE_TAG,
        albumId = 0,
        durationMs = this.durationSec * 1000L,
        coverUrl = this.coverUrl
    )
}
