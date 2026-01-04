@file:OptIn(UnstableApi::class)

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
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.biliCookieRepo
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.parseNeteaseLrc
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

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
    private const val FAVORITES_NAME = "我喜欢的音乐"
    const val BILI_SOURCE_TAG = "Bilibili"
    const val NETEASE_SOURCE_TAG = "Netease"

    private var initialized = false
    private lateinit var application: Application
    private lateinit var player: ExoPlayer

    private lateinit var cache: Cache

    private fun newIoScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun newMainScope() = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var ioScope = newIoScope()
    private var mainScope = newMainScope()
    private var progressJob: Job? = null

    private lateinit var localRepo: LocalPlaylistRepository

    private lateinit var stateFile: File

    private var preferredQuality: String = "exhigh"
    private var biliPreferredQuality: String = "high"

    private var currentPlaylist: List<SongItem> = emptyList()
    private var currentIndex = -1

    /** 随机播放相关  */
    private val shuffleHistory = mutableListOf<Int>()   // 已经走过的路径（支持上一首）
    private val shuffleFuture  = mutableListOf<Int>()   // 预定的“下一首们”（支持先上后下仍回到原来的下一首）
    private var shuffleBag     = mutableListOf<Int>()   // 本轮还没“抽签”的下标池（不含 current）

    private var consecutivePlayFailures = 0
    private const val MAX_CONSECUTIVE_FAILURES = 10

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

    val audioLevelFlow get() = AudioReactive.level
    val beatImpulseFlow get() = AudioReactive.beat

    var biliRepo = AppContainer.biliPlaybackRepository
    var biliClient = AppContainer.biliClient
    var neteaseClient = AppContainer.neteaseClient

    val cloudMusicSearchApi = AppContainer.cloudMusicSearchApi
    val qqMusicSearchApi = AppContainer.qqMusicSearchApi

    // 记录当前缓存大小设置
    private var currentCacheSize: Long = 1024L * 1024 * 1024

    private fun isPreparedInPlayer(): Boolean = player.currentMediaItem != null

    private val gson = Gson()

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

    /**
     * 基于歌曲来源与所选音质构建缓存键
     * - B 站：bili-avid-可选cid-音质
     * - 网易云：netease-songId-音质
     */
    private fun computeCacheKey(song: SongItem): String {
        val isBili = song.album.startsWith(BILI_SOURCE_TAG)
        return if (isBili) {
            val parts = song.album.split('|')
            val cidPart = if (parts.size > 1) parts[1] else null
            if (cidPart != null) {
                "bili-${song.id}-$cidPart-$biliPreferredQuality"
            } else {
                "bili-${song.id}-$biliPreferredQuality"
            }
        } else {
            "netease-${song.id}-$preferredQuality"
        }
    }

    /** 基于 URL 与缓存键构建 MediaItem（含自定义缓存键，便于跨音质/来源复用/隔离） */
    private fun buildMediaItem(song: SongItem, url: String, cacheKey: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(Uri.parse(url))
            .setCustomCacheKey(cacheKey)
            .build()
    }

    /** 处理单曲播放结束：根据循环模式与随机三栈推进或停止 */
    private fun handleTrackEnded() {
        _playbackPositionMs.value = 0L
        when (repeatModeSetting) {
            Player.REPEAT_MODE_ONE -> playAtIndex(currentIndex)
            Player.REPEAT_MODE_ALL -> next(force = true)
            else -> {
                if (player.shuffleModeEnabled) {
                    if (shuffleFuture.isNotEmpty() || shuffleBag.isNotEmpty()) next(force = false)
                    else stopAndClearPlaylist()
                } else {
                    if (currentIndex < currentPlaylist.lastIndex) next(force = false)
                    else stopAndClearPlaylist()
                }
            }
        }
    }

    private data class PersistedState(
        val playlist: List<SongItem>,
        val index: Int
    )


    fun initialize(app: Application, maxCacheSize: Long = 1024L * 1024 * 1024) {
        if (initialized) return
        initialized = true
        application = app
        currentCacheSize = maxCacheSize

        ioScope = newIoScope()
        mainScope = newMainScope()

        localRepo = LocalPlaylistRepository.getInstance(app)
        stateFile = File(app.filesDir, "last_playlist.json")

        // 基础网络请求工厂，支持 B 站 Cookie 注入
        val okHttpClient = AppContainer.sharedOkHttpClient
        val upstreamFactory: HttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
        val conditionalHttpFactory = ConditionalHttpDataSourceFactory(upstreamFactory, biliCookieRepo)

        // 默认数据源工厂
        val defaultDsFactory = androidx.media3.datasource.DefaultDataSource.Factory(app, conditionalHttpFactory)

        // 决定是否启用缓存层
        val finalDataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (maxCacheSize > 0) {
            val cacheDir = File(app.cacheDir, "media_cache")
            val dbProvider = StandaloneDatabaseProvider(app)

            // 创建 LRU 缓存实例
            cache = SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(maxCacheSize),
                dbProvider
            )

            // 包裹成缓存数据源
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(defaultDsFactory)
                // 如果缓存写入失败（如空间不足），忽略错误直接播放网络流
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            // 缓存大小为 0，直接使用直连数据源
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

                val cause = error.cause
                val msg = when {
                    cause?.message?.contains("no protocol: null", ignoreCase = true) == true ->
                        "播放地址无效\n请尝试登录或切换音质\n或检查你是否对此歌曲有访问权限"
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "网络连接失败，请检查网络后重试"
                    else ->
                        "播放失败：${error.errorCodeName}"
                }
                postPlayerEvent(PlayerEvent.ShowError(msg))

                pause()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) handleTrackEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlayingFlow.value = isPlaying
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffleModeFlow.value = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                // 不接受 Exo 的列表循环（ALL）；仅维持单曲循环或关闭
                // 重申应用层的 repeat 状态，并钳制 Exo 的实际 repeat
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

        // 同步本地歌单
        ioScope.launch {
            localRepo.playlists.collect { repoLists ->
                _playlistsFlow.value = deepCopyPlaylists(repoLists)
            }
        }

        setupAudioDeviceCallback()
        restoreState()

        // 初始化完成后检查是否有待播放项并尝试同步前台服务
        NPLogger.d("NERI-Player", "PlayerManager initialized with cache size: $maxCacheSize")
    }

    fun clearCache() {
        ioScope.launch(Dispatchers.IO) {
            var apiRemovedCount = 0
            var physicalDeletedCount = 0
            var totalSpaceFreed = 0L

            try {
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
                            val length = file.length()
                            if (file.delete()) {
                                physicalDeletedCount++
                            }
                        }
                    }
                }

                NPLogger.d("NERI-Player", "Cache Clear: API removed $apiRemovedCount keys, Physically deleted $physicalDeletedCount .exo files.")

                withContext(Dispatchers.Main) {
                    val msg = if (physicalDeletedCount > 0 || apiRemovedCount > 0) {
                        "清理完成"
                    } else {
                        "缓存已为空"
                    }
                    Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                NPLogger.e("NERI-Player", "Clear cache failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "清理出错: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    private fun handleDeviceChange(audioManager: AudioManager) {
        val previousDevice = _currentAudioDevice.value
        val newDevice = getCurrentAudioDevice(audioManager)
        _currentAudioDevice.value = newDevice
        if (player.isPlaying &&
            previousDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
            newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            NPLogger.d("NERI-PlayerManager", "Audio output changed to speaker, pausing playback.")
            pause()
        }
    }

    private fun getCurrentAudioDevice(audioManager: AudioManager): AudioDevice {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bluetoothDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (bluetoothDevice != null) {
            return try {
                AudioDevice(
                    name = bluetoothDevice.productName.toString().ifBlank { "蓝牙耳机" },
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    icon = Icons.Default.BluetoothAudio
                )
            } catch (_: SecurityException) {
                AudioDevice("蓝牙耳机", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, Icons.Default.BluetoothAudio)
            }
        }
        val wiredHeadset = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        if (wiredHeadset != null) {
            return AudioDevice("有线耳机", AudioDeviceInfo.TYPE_WIRED_HEADSET, Icons.Default.Headset)
        }
        return AudioDevice("手机扬声器", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, Icons.Default.SpeakerGroup)
    }

    fun playPlaylist(songs: List<SongItem>, startIndex: Int) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        if (songs.isEmpty()) {
            NPLogger.w("NERI-Player", "playPlaylist called with EMPTY list")
            return
        }
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

    private fun playAtIndex(index: Int) {
        if (currentPlaylist.isEmpty() || index !in currentPlaylist.indices) {
            NPLogger.w("NERI-Player", "playAtIndex called with invalid index: $index")
            return
        }

        if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
            NPLogger.e("NERI-PlayerManager", "已连续失败 $consecutivePlayFailures 次，停止播放")
            mainScope.launch { Toast.makeText(application, "多首歌曲无法播放，已停止", Toast.LENGTH_SHORT).show() }
            stopAndClearPlaylist()
            return
        }

        val song = currentPlaylist[index]
        _currentSongFlow.value = song
        ioScope.launch {
            persistState()
        }

        // 当前曲不应再出现在洗牌袋中
        if (player.shuffleModeEnabled) {
            shuffleBag.remove(index)
        }

        playJob?.cancel()
        _playbackPositionMs.value = 0L
        playJob = ioScope.launch {
            val result = resolveSongUrl(song)

            when (result) {
                is SongUrlResult.Success -> {
                    consecutivePlayFailures = 0

                    val cacheKey = computeCacheKey(song)
                    NPLogger.d("NERI-PlayerManager", "Using custom cache key: $cacheKey for song: ${song.name}")

                    val mediaItem = buildMediaItem(song, result.url, cacheKey)

                    _currentMediaUrl.value = result.url

                    withContext(Dispatchers.Main) {
                        player.setMediaItem(mediaItem)
                        // 每次切歌后都钳制 Exo 的循环状态，避免单媒体项“列表循环”
                        syncExoRepeatMode()
                        player.prepare()
                        player.play()
                    }
                }
                is SongUrlResult.RequiresLogin -> {
                    NPLogger.w("NERI-PlayerManager", "需要登录才能播放: id=${song.id}, source=${song.album}")
                    postPlayerEvent(PlayerEvent.ShowLoginPrompt("播放失败，请尝试登录对应的平台"))
                    withContext(Dispatchers.Main) { next() } // 自动跳到下一首
                }
                is SongUrlResult.Failure -> {
                    NPLogger.e("NERI-PlayerManager", "获取播放 URL 失败, 跳过: id=${song.id}, source=${song.album}")
                    consecutivePlayFailures++
                    withContext(Dispatchers.Main) { next() } // 自动跳到下一首
                }
            }
        }
    }

    private suspend fun resolveSongUrl(song: SongItem): SongUrlResult {
        // 优先检查本地缓存
        val localResult = checkLocalCache(song)
        if (localResult != null) return localResult

        return if (song.album.startsWith(BILI_SOURCE_TAG)) {
            // 解析可能的 cid
            val parts = song.album.split('|')
            val cid = if (parts.size > 1) parts[1].toLongOrNull() ?: 0L else 0L
            getBiliAudioUrl(song.id, cid) // song.id 始终是 avid
        } else {
            getNeteaseSongUrl(song.id)
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

    private suspend fun getNeteaseSongUrl(songId: Long): SongUrlResult = withContext(Dispatchers.IO) {
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
                        postPlayerEvent(PlayerEvent.ShowError("该歌曲暂无可用播放地址（可能需要登录或版权限制）"))
                        SongUrlResult.Failure
                    } else {
                        val finalUrl = if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url
                        SongUrlResult.Success(finalUrl)
                    }
                }
                else -> {
                    postPlayerEvent(PlayerEvent.ShowError("获取播放地址失败（${root.optInt("code")}）"))
                    SongUrlResult.Failure
                }
            }
        } catch (e: Exception) {
            NPLogger.e("NERI-PlayerManager", "获取URL时出错", e)
            SongUrlResult.Failure
        }
    }

    private suspend fun getBiliAudioUrl(avid: Long, cid: Long = 0): SongUrlResult = withContext(Dispatchers.IO) {
        try {
            var finalCid = cid
            val bvid: String
            if (finalCid == 0L) {
                val videoInfo = biliClient.getVideoBasicInfoByAvid(avid)
                bvid = videoInfo.bvid
                finalCid = videoInfo.pages.firstOrNull()?.cid ?: 0L
                if (finalCid == 0L) {
                    postPlayerEvent(PlayerEvent.ShowError("无法获取视频信息 (cid)"))
                    return@withContext SongUrlResult.Failure
                }
            } else {
                bvid = biliClient.getVideoBasicInfoByAvid(avid).bvid
            }

            val audioStream = biliRepo.getBestPlayableAudio(bvid, finalCid)

            if (audioStream?.url != null) {
                NPLogger.d("NERI-PlayerManager-BiliAudioUrl", audioStream.url)
                SongUrlResult.Success(audioStream.url)
            } else {
                postPlayerEvent(PlayerEvent.ShowError("无法获取播放地址"))
                SongUrlResult.Failure
            }
        } catch (e: Exception) {
            NPLogger.e("NERI-PlayerManager", "获取B站音频URL时出错", e)
            postPlayerEvent(PlayerEvent.ShowError("获取播放地址失败: ${e.message}"))
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
        val songs = videoInfo.pages.map { page ->
            SongItem(
                id = videoInfo.aid,
                name = page.part,
                artist = videoInfo.ownerName,
                album = "$BILI_SOURCE_TAG|${page.cid}",
                albumId = 0,
                durationMs = page.durationSec * 1000L,
                coverUrl = coverUrl
            )
        }
        playPlaylist(songs, startIndex)
    }

    fun play() {
        ensureInitialized()
        if (!initialized) return
        when {
            isPreparedInPlayer() -> {
                syncExoRepeatMode()
                player.play()
            }
            currentPlaylist.isNotEmpty() && currentIndex != -1 -> playAtIndex(currentIndex)
            currentPlaylist.isNotEmpty() -> playAtIndex(0)
            else -> {}
        }
    }

    fun pause() {
        ensureInitialized()
        if (!initialized) return
        player.pause()
    }

    fun togglePlayPause() {
        ensureInitialized()
        if (!initialized) return
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        ensureInitialized()
        if (!initialized) return
        player.seekTo(positionMs)
        _playbackPositionMs.value = positionMs
    }

    fun next(force: Boolean = false) {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) return
        val isShuffle = player.shuffleModeEnabled

        if (isShuffle) {
            // 如果有预定下一首，优先走它
            if (shuffleFuture.isNotEmpty()) {
                val nextIdx = shuffleFuture.removeLast()
                if (currentIndex != -1) shuffleHistory.add(currentIndex)
                currentIndex = nextIdx
                playAtIndex(currentIndex)
                return
            }

            // 没有预定下一首，需要抽新随机
            if (shuffleBag.isEmpty()) {
                if (force || repeatModeSetting == Player.REPEAT_MODE_ALL) {
                    rebuildShuffleBag(excludeIndex = currentIndex) // 新一轮，避免同曲连播
                } else {
                    NPLogger.d("NERI-Player", "Shuffle finished and repeat is off, stopping.")
                    stopAndClearPlaylist()
                    return
                }
            }

            if (shuffleBag.isEmpty()) {
                // 仅一首歌等极端情况
                playAtIndex(currentIndex)
                return
            }

            if (currentIndex != -1) shuffleHistory.add(currentIndex)
            // 新随机 -> 断开未来路径
            shuffleFuture.clear()

            val pick = if (shuffleBag.size == 1) 0 else Random.nextInt(shuffleBag.size)
            currentIndex = shuffleBag.removeAt(pick)
            playAtIndex(currentIndex)
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
            playAtIndex(currentIndex)
        }
    }

    fun previous() {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) return
        val isShuffle = player.shuffleModeEnabled

        if (isShuffle) {
            if (shuffleHistory.isNotEmpty()) {
                // 回退一步，同时把当前曲放到未来栈，以便再前进能回到原来的下一首
                if (currentIndex != -1) shuffleFuture.add(currentIndex)
                val prev = shuffleHistory.removeLast()
                currentIndex = prev
                playAtIndex(currentIndex)
            } else {
                NPLogger.d("NERI-Player", "No previous track in shuffle history.")
            }
        } else {
            if (currentIndex > 0) {
                currentIndex--
                playAtIndex(currentIndex)
            } else {
                if (repeatModeSetting == Player.REPEAT_MODE_ALL && currentPlaylist.isNotEmpty()) {
                    currentIndex = currentPlaylist.lastIndex
                    playAtIndex(currentIndex)
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
    }

    fun release() {
        if (!initialized) return

        try {
            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        } catch (_: Exception) { }
        audioDeviceCallback = null

        stopProgressUpdates()
        playJob?.cancel()
        playJob = null

        if (::player.isInitialized) {
            runCatching { player.stop() }
            player.release()
        }
        if (::cache.isInitialized) {
            cache.release()
        }

        mainScope.cancel()
        ioScope.cancel()

        _isPlayingFlow.value = false
        _currentMediaUrl.value = null
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
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = mainScope.launch {
            while (isActive) {
                _playbackPositionMs.value = player.currentPosition
                delay(40)
            }
        }
    }

    private fun stopProgressUpdates() { progressJob?.cancel(); progressJob = null }

    private fun stopAndClearPlaylist() {
        playJob?.cancel()
        playJob = null
        player.stop()
        player.clearMediaItems()
        _isPlayingFlow.value = false
        _currentSongFlow.value = null
        _currentMediaUrl.value = null
        _playbackPositionMs.value = 0L
        currentIndex = -1
        currentPlaylist = emptyList()
        _currentQueueFlow.value = emptyList()
        consecutivePlayFailures = 0
        shuffleBag.clear()
        shuffleHistory.clear()
        shuffleFuture.clear()
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
                // 确保收藏歌单存在
                if (_playlistsFlow.value.none { it.name == FAVORITES_NAME }) {
                    localRepo.createPlaylist(FAVORITES_NAME)
                }
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
        val songId = _currentSongFlow.value?.id ?: return
        val updatedLists = optimisticUpdateFavorites(add = false, songId = songId)
        _playlistsFlow.value = deepCopyPlaylists(updatedLists)
        ioScope.launch {
            try {
                localRepo.removeFromFavorites(songId)
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
        val fav = _playlistsFlow.value.firstOrNull { it.name == FAVORITES_NAME }
        val isFav = fav?.songs?.any { it.id == song.id } == true
        if (isFav) removeCurrentFromFavorites() else addCurrentToFavorites()
    }

    /** 本地乐观修改收藏歌单 */
    private fun optimisticUpdateFavorites(
        add: Boolean,
        song: SongItem? = null,
        songId: Long? = null
    ): List<LocalPlaylist> {
        val lists = _playlistsFlow.value
        val favIdx = lists.indexOfFirst { it.name == FAVORITES_NAME }
        val base = lists.map { LocalPlaylist(it.id, it.name, it.songs.toMutableList()) }.toMutableList()

        if (favIdx >= 0) {
            val fav = base[favIdx]
            if (add && song != null) {
                if (fav.songs.none { it.id == song.id }) fav.songs.add(song)
            } else if (!add && songId != null) {
                fav.songs.removeAll { it.id == songId }
            }
        } else {
            if (add && song != null) {
                base += LocalPlaylist(
                    id = System.currentTimeMillis(),
                    name = FAVORITES_NAME,
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
                songs = pl.songs.toMutableList()
            )
        }
    }

    private suspend fun persistState() {
        withContext(Dispatchers.IO) {
            try {
                if (currentPlaylist.isEmpty()) {
                    if (stateFile.exists()) stateFile.delete()
                } else {
                    val data = PersistedState(currentPlaylist, currentIndex)
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
        // 优先检查本地翻译歌词缓存
        val context = application
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
        return if (song.album.startsWith(BILI_SOURCE_TAG)) {
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
        val existingIndex = newPlaylist.indexOfFirst { it.id == song.id && it.album == song.album }
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
            currentIndex = newPlaylist.indexOfFirst { it.id == currentSong.id && it.album == currentSong.album }
        }

        if (player.shuffleModeEnabled) {
            val newSongRealIndex = newPlaylist.indexOfFirst { it.id == song.id && it.album == song.album }

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

        val newPlaylist = currentPlaylist.toMutableList()

        // 检查歌曲是否已存在于队列中
        val existingIndex = newPlaylist.indexOfFirst { it.id == song.id && it.album == song.album }
        if (existingIndex != -1) {
            newPlaylist.removeAt(existingIndex)
        }

        newPlaylist.add(song)

        // 更新播放队列
        currentPlaylist = newPlaylist
        _currentQueueFlow.value = currentPlaylist

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
            currentPlaylist = data.playlist
            currentIndex = data.index
            _currentQueueFlow.value = currentPlaylist
            _currentSongFlow.value = currentPlaylist.getOrNull(currentIndex)
        } catch (e: Exception) {
            NPLogger.w("NERI-PlayerManager", "Failed to restore state: ${e.message}")
        }
    }


    fun replaceMetadataFromSearch(originalSong: SongItem, selectedSong: SongSearchInfo) {
        ioScope.launch {
            val platform = selectedSong.source

            val api = when (platform) {
                MusicPlatform.CLOUD_MUSIC -> cloudMusicSearchApi
                MusicPlatform.QQ_MUSIC -> qqMusicSearchApi
            }

            try {
                val newDetails = api.getSongInfo(selectedSong.id)

                val updatedSong = originalSong.copy(
                    name = newDetails.songName,
                    artist = newDetails.singer,
                    coverUrl = newDetails.coverUrl,
                    matchedLyric = newDetails.lyric,
                    matchedLyricSource = selectedSong.source,
                    matchedSongId = selectedSong.id
                )

                updateSongInAllPlaces(originalSong, updatedSong)

            } catch (e: Exception) {
                mainScope.launch { Toast.makeText(application, "匹配失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    suspend fun updateUserLyricOffset(songToUpdate: SongItem, newOffset: Long) {
        val queueIndex = currentPlaylist.indexOfFirst { it.id == songToUpdate.id && it.album == songToUpdate.album }
        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(userLyricOffsetMs = newOffset)
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (_currentSongFlow.value?.id == songToUpdate.id && _currentSongFlow.value?.album == songToUpdate.album) {
            _currentSongFlow.value = _currentSongFlow.value?.copy(userLyricOffsetMs = newOffset)
        }

        withContext(Dispatchers.IO) {
            localRepo.updateSongMetadata(
                songToUpdate.id,
                songToUpdate.album,
                songToUpdate.copy(userLyricOffsetMs = newOffset)
            )
        }

        persistState()
    }

    private suspend fun updateSongInAllPlaces(originalSong: SongItem, updatedSong: SongItem) {
        val originalId = originalSong.id
        val originalAlbum = originalSong.album

        val queueIndex = currentPlaylist.indexOfFirst { it.id == originalId && it.album == originalAlbum }
        if (queueIndex != -1) {
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (_currentSongFlow.value?.id == originalId && _currentSongFlow.value?.album == originalAlbum) {
            _currentSongFlow.value = updatedSong
        }

        withContext(Dispatchers.IO) {
            localRepo.updateSongMetadata(originalId, originalAlbum, updatedSong)
        }

        persistState()
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
