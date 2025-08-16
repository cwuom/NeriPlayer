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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
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
import moe.ouom.neriplayer.core.api.bili.BiliClientAudioDataSource
import moe.ouom.neriplayer.core.api.bili.BiliPlaybackRepository
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.BiliCookieRepository
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.NeteaseCookieRepository
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.parseNeteaseLrc
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.SearchManager
import org.json.JSONObject
import java.io.File
import android.util.Base64
import moe.ouom.neriplayer.core.api.search.CloudMusicSearchApi
import moe.ouom.neriplayer.core.api.search.QQMusicSearchApi
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
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

object PlayerManager {
    private const val FAVORITES_NAME = "我喜欢的音乐"
    const val BILI_SOURCE_TAG = "Bilibili"

    private var initialized = false
    private lateinit var application: Application
    private lateinit var player: ExoPlayer

    private lateinit var cache: Cache

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    val neteaseClient = NeteaseClient()

    private lateinit var localRepo: LocalPlaylistRepository

    private lateinit var stateFile: File

    private var preferredQuality: String = "exhigh"

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

    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)

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

    private lateinit var biliRepo: BiliPlaybackRepository
    private lateinit var biliClient: BiliClient

    internal val cloudMusicApi = CloudMusicSearchApi(neteaseClient)
    internal val qqMusicApi = QQMusicSearchApi()

    private fun isPreparedInPlayer(): Boolean = player.currentMediaItem != null

    private data class PersistedState(
        val playlist: List<SongItem>,
        val index: Int
    )

    fun initialize(app: Application) {
        if (initialized) return
        initialized = true
        application = app

        localRepo = LocalPlaylistRepository.getInstance(app)
        stateFile = File(app.filesDir, "last_playlist.json")

        val biliCookieRepo = BiliCookieRepository(app)
        val biliSettingsRepo = SettingsRepository(app)
        val biliDataSource = BiliClientAudioDataSource(BiliClient(biliCookieRepo))
        biliRepo = BiliPlaybackRepository(biliDataSource, biliSettingsRepo)
        biliClient = BiliClient(biliCookieRepo)

        val cacheDir = File(app.cacheDir, "media_cache")
        val dbProvider = StandaloneDatabaseProvider(app)
        cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(10L * 1024 * 1024 * 1024),
            dbProvider
        )

        val defaultHttpFactory = DefaultHttpDataSource.Factory()
        val conditionalHttpFactory = ConditionalHttpDataSourceFactory(defaultHttpFactory, biliCookieRepo)

        val cacheDsFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(conditionalHttpFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(cacheDsFactory)

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
                ioScope.launch { _playerEventFlow.emit(PlayerEvent.ShowError(msg)) }

                pause()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    _playbackPositionMs.value = 0L
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> playAtIndex(currentIndex)
                        Player.REPEAT_MODE_ALL -> next(force = true)
                        else -> { // REPEAT_MODE_OFF
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
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlayingFlow.value = isPlaying
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffleModeFlow.value = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatModeFlow.value = repeatMode
            }
        })

        player.playWhenReady = false

        // 订阅音质
        ioScope.launch {
            SettingsRepository(app).audioQualityFlow.collect { q -> preferredQuality = q }
        }

        // 注入登录 Cookie
        ioScope.launch {
            NeteaseCookieRepository(app).cookieFlow.collect { raw ->
                val cookies = raw.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                neteaseClient.setPersistedCookies(cookies)
                NPLogger.d("NERI-PlayerManager", "Cookies updated in PlayerManager: keys=${cookies.keys.joinToString()}")
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

                    val sourcePrefix = if (song.album?.startsWith(BILI_SOURCE_TAG) == true) "bili" else "netease"

                    val cacheKey = if (sourcePrefix == "bili") {
                        val parts = song.album?.split('|')
                        val cidPart = if (parts != null && parts.size > 1) parts[1] else null
                        if (cidPart != null) {
                            "$sourcePrefix-${song.id}-$cidPart"
                        } else {
                            "$sourcePrefix-${song.id}"
                        }
                    } else {
                        "$sourcePrefix-${song.id}"
                    }
                    NPLogger.d("NERI-PlayerManager", "Using custom cache key: $cacheKey for song: ${song.name}")

                    val mediaItem = MediaItem.Builder()
                        .setMediaId(song.id.toString())
                        .setUri(Uri.parse(result.url))
                        .setCustomCacheKey(cacheKey)
                        .build()

                    _currentMediaUrl.value = result.url

                    withContext(Dispatchers.Main) {
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.play()
                    }
                }
                is SongUrlResult.RequiresLogin -> {
                    NPLogger.w("NERI-PlayerManager", "需要登录才能播放: id=${song.id}, source=${song.album}")
                    _playerEventFlow.emit(PlayerEvent.ShowLoginPrompt("播放失败，请尝试登录对应的平台"))
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
        return if (song.album.startsWith(BILI_SOURCE_TAG)) {
            // 解析可能的 cid
            val parts = song.album.split('|')
            val cid = if (parts.size > 1) parts[1].toLongOrNull() ?: 0L else 0L
            getBiliAudioUrl(song.id, cid) // song.id 始终是 avid
        } else {
            getNeteaseSongUrl(song.id)
        }
    }

    private suspend fun getNeteaseSongUrl(songId: Long): SongUrlResult = withContext(Dispatchers.IO) {
        try {
            val resp = neteaseClient.getSongDownloadUrl(
                songId,
                bitrate = 320000,
                level = preferredQuality
            )
            NPLogger.d("NERI-PlayerManager", "id=$songId, resp=$resp")

            val root = JSONObject(resp)
            when (root.optInt("code")) {
                301 -> SongUrlResult.RequiresLogin
                200 -> {
                    val url = when (val dataObj = root.opt("data")) {
                        is JSONObject -> dataObj.optString("url", "")
                        is org.json.JSONArray -> dataObj.optJSONObject(0)?.optString("url", "")
                        else -> ""
                    }
                    if (url.isNullOrBlank()) {
                        ioScope.launch { _playerEventFlow.emit(PlayerEvent.ShowError("该歌曲暂无可用播放地址（可能需要登录或版权限制）")) }
                        SongUrlResult.Failure
                    } else {
                        val finalUrl = if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url
                        SongUrlResult.Success(finalUrl)
                    }
                }
                else -> {
                    ioScope.launch { _playerEventFlow.emit(PlayerEvent.ShowError("获取播放地址失败（${root.optInt("code")}）。")) }
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
                    _playerEventFlow.emit(PlayerEvent.ShowError("无法获取视频信息 (cid)"))
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
                _playerEventFlow.emit(PlayerEvent.ShowError("无法获取播放地址"))
                SongUrlResult.Failure
            }
        } catch (e: Exception) {
            NPLogger.e("NERI-PlayerManager", "获取B站音频URL时出错", e)
            _playerEventFlow.emit(PlayerEvent.ShowError("获取播放地址失败: ${e.message}"))
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
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        val songs = videoInfo.pages.map { page ->
            SongItem(
                id = videoInfo.aid,
                name = page.part,
                artist = videoInfo.ownerName,
                album = "$BILI_SOURCE_TAG|${page.cid}",
                durationMs = page.durationSec * 1000L,
                coverUrl = coverUrl
            )
        }
        playPlaylist(songs, startIndex)
    }

    fun play() {
        when {
            isPreparedInPlayer() -> {
                // 播放器里已经有 MediaItem，直接播
                player.play()
            }
            currentPlaylist.isNotEmpty() && currentIndex != -1 -> {
                // 有队列且知道当前位置，装载并播该首
                playAtIndex(currentIndex)
            }
            currentPlaylist.isNotEmpty() -> {
                // 有队列但没有效 index
                playAtIndex(0)
            }
            else -> { /* 没队列，啥也不做 */ }
        }
    }

    fun pause() { player.pause() }
    fun togglePlayPause() { if (player.isPlaying) pause() else play() }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _playbackPositionMs.value = positionMs
    }

    fun next(force: Boolean = false) {
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
                if (force || player.repeatMode == Player.REPEAT_MODE_ALL) {
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
                if (force || player.repeatMode == Player.REPEAT_MODE_ALL) {
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
                if (player.repeatMode == Player.REPEAT_MODE_ALL && currentPlaylist.isNotEmpty()) {
                    currentIndex = currentPlaylist.lastIndex
                    playAtIndex(currentIndex)
                } else {
                    NPLogger.d("NERI-Player", "Already at the start of the playlist.")
                }
            }
        }
    }

    fun cycleRepeatMode() {
        val newMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = newMode
    }

    fun release() {
        player.release()
        cache.release()
        mainScope.cancel()
        ioScope.cancel()
    }

    fun setShuffle(enabled: Boolean) {
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
                    stateFile.writeText(Gson().toJson(data))
                }
            } catch (e: Exception) {
                NPLogger.e("PlayerManager", "Failed to persist state", e)
            }
        }
    }

    fun addCurrentToPlaylist(playlistId: Long) {
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
     * 修改：让 playBiliVideoAsAudio 也使用统一的 playPlaylist 入口
     */
    fun playBiliVideoAsAudio(videos: List<BiliVideoItem>, startIndex: Int) {
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

    fun playFromQueue(index: Int) {
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

    private fun restoreState() {
        try {
            if (!stateFile.exists()) return
            val type = object : TypeToken<PersistedState>() {}.type
            val data: PersistedState = Gson().fromJson(stateFile.readText(), type)
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
                MusicPlatform.CLOUD_MUSIC -> cloudMusicApi
                MusicPlatform.QQ_MUSIC -> qqMusicApi
            }

            try {
                val newDetails = api.getSongInfo(selectedSong.id)

                val updatedSong = originalSong.copy(
                    name = newDetails.songName,
                    artist = newDetails.singer,
                    coverUrl = newDetails.coverUrl,
                    matchedLyric = newDetails.lyric,
                    matchedLyricSource = selectedSong.source
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
        durationMs = this.durationSec * 1000L,
        coverUrl = this.coverUrl
    )
}