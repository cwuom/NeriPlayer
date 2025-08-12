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
 * Created: 2025/8/11
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.data.NeteaseCookieRepository
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.ui.viewmodel.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.File

data class AudioDevice(
    val name: String,
    val type: Int, // e.g., AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
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
    private var initialized = false
    private lateinit var application: Application
    private lateinit var player: ExoPlayer

    private lateinit var cache: Cache

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null

    private val neteaseClient = NeteaseClient()

    private var preferredQuality: String = "exhigh"

    private var currentPlaylist: List<SongItem> = emptyList()
    private var currentIndex = -1
    private var consecutivePlayFailures = 0
    private const val MAX_CONSECUTIVE_FAILURES = 10

    private val _currentSongFlow = MutableStateFlow<SongItem?>(null)
    val currentSongFlow: StateFlow<SongItem?> = _currentSongFlow

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionFlow: StateFlow<Long> = _playbackPositionMs

    private val _shuffleModeFlow = MutableStateFlow(false)
    val shuffleModeFlow: StateFlow<Boolean> = _shuffleModeFlow

    private val _repeatModeFlow = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatModeFlow: StateFlow<Int> = _repeatModeFlow

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
    val currentAudioDeviceFlow: StateFlow<AudioDevice?> = _currentAudioDevice

    private val _playerEventFlow = MutableSharedFlow<PlayerEvent>()
    val playerEventFlow: SharedFlow<PlayerEvent> = _playerEventFlow.asSharedFlow()

    fun initialize(app: Application) {
        if (initialized) return
        initialized = true
        application = app

        val cacheDir = File(app.cacheDir, "media_cache")
        val dbProvider = StandaloneDatabaseProvider(app)
        cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(10L * 1024 * 1024 * 1024),
            dbProvider
        )

        val httpFactory = DefaultHttpDataSource.Factory()
        val cacheDsFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(cacheDsFactory)

        player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                NPLogger.e("NERI-Player", "onPlayerError: ${error.errorCodeName}", error)
                consecutivePlayFailures++

                val cause = error.cause
                val msg = when {
                    cause?.message?.contains("no protocol: null", ignoreCase = true) == true ->
                        "播放地址无效。请尝试登录"
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
                        Player.REPEAT_MODE_OFF -> {
                            if (currentIndex < currentPlaylist.size - 1) {
                                next()
                            } else {
                                stopAndClearPlaylist()
                            }
                        }
                        Player.REPEAT_MODE_ALL -> next(force = true)
                        Player.REPEAT_MODE_ONE -> playAtIndex(currentIndex)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlayingFlow.value = isPlaying
                if (isPlaying) {
                    startProgressUpdates()
                } else {
                    stopProgressUpdates()
                }
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

        // 订阅 CookieFlow，登录后立刻注入最新 Cookie
        ioScope.launch {
            NeteaseCookieRepository(app).cookieFlow.collect { raw ->
                val cookies = raw.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                neteaseClient.setPersistedCookies(cookies)
                NPLogger.d("NERI-PlayerManager", "Cookies updated in PlayerManager: keys=${cookies.keys.joinToString()}")
                if (!cookies["MUSIC_U"].isNullOrBlank()) {
                    NPLogger.d("NERI-PlayerManager", "Detected login cookie, applied new cookie for playback")
                }
            }
        }

        setupAudioDeviceCallback()
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
        currentIndex = startIndex
        playAtIndex(currentIndex)
    }

    private fun playAtIndex(index: Int) {
        if (currentPlaylist.isEmpty() || index !in currentPlaylist.indices) {
            NPLogger.w("NERI-Player", "playAtIndex called with invalid index: $index")
            return
        }

        if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
            NPLogger.e("NERI-PlayerManager", "已连续失败 $consecutivePlayFailures 次，停止播放。")
            mainScope.launch {
                Toast.makeText(application, "多首歌曲无法播放，已停止", Toast.LENGTH_SHORT).show()
            }
            stopAndClearPlaylist()
            return
        }

        val song = currentPlaylist[index]
        _currentSongFlow.value = song

        ioScope.launch {
            when (val result = getSongUrl(song.id)) {
                is SongUrlResult.Success -> {
                    consecutivePlayFailures = 0
                    val mediaItem = MediaItem.Builder()
                        .setMediaId(song.id.toString())
                        .setUri(Uri.parse(result.url))
                        .build()

                    withContext(Dispatchers.Main) {
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.play()
                    }
                }
                is SongUrlResult.RequiresLogin -> {
                    NPLogger.w("NERI-PlayerManager", "需要登录才能播放: id=${song.id}")
                    _playerEventFlow.emit(PlayerEvent.ShowLoginPrompt("播放失败，请尝试登录"))
                    withContext(Dispatchers.Main) {
                        stopAndClearPlaylist()
                    }
                }
                is SongUrlResult.Failure -> {
                    NPLogger.e("NERI-PlayerManager", "获取播放 URL 失败, 跳过: id=${song.id}")
                    consecutivePlayFailures++
                    withContext(Dispatchers.Main) { next() }
                }
            }
        }
    }

    private suspend fun getSongUrl(songId: Long): SongUrlResult {
        return withContext(Dispatchers.IO) {
            try {
                val resp = neteaseClient.getSongDownloadUrl(
                    songId,
                    bitrate = 320000,
                    level = preferredQuality
                )
                NPLogger.d("NERI-PlayerManager", "id=$songId, resp=$resp")

                val root = JSONObject(resp)
                when (root.optInt("code")) {
                    301 -> return@withContext SongUrlResult.RequiresLogin
                    200 -> {
                        val url = when (val dataObj = root.opt("data")) {
                            is JSONObject -> dataObj.optString("url", "")
                            is org.json.JSONArray -> dataObj.optJSONObject(0)?.optString("url", "")
                            else -> ""
                        }

                        if (url.isNullOrBlank()) {
                            ioScope.launch {
                                _playerEventFlow.emit(
                                    PlayerEvent.ShowError("该歌曲暂无可用播放地址（可能需要登录或版权限制）")
                                )
                            }
                            return@withContext SongUrlResult.Failure
                        } else {
                            val finalUrl = if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url
                            return@withContext SongUrlResult.Success(finalUrl)
                        }
                    }
                    else -> {
                        ioScope.launch {
                            _playerEventFlow.emit(PlayerEvent.ShowError("获取播放地址失败（${root.optInt("code")}）。"))
                        }
                        return@withContext SongUrlResult.Failure
                    }
                }
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "获取URL时出错", e)
                return@withContext SongUrlResult.Failure
            }
        }
    }

    fun play() {
        if (hasItems()) {
            player.play()
        } else if (currentPlaylist.isNotEmpty() && currentIndex != -1) {
            playAtIndex(currentIndex)
        }
    }

    fun pause() {
        player.pause()
    }

    fun togglePlayPause() {
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _playbackPositionMs.value = positionMs
    }

    fun next(force: Boolean = false) {
        if (currentPlaylist.isEmpty()) return

        if (player.shuffleModeEnabled) {
            currentIndex = (0 until currentPlaylist.size).random()
        } else {
            if (currentIndex < currentPlaylist.size - 1) {
                currentIndex++
            } else if (force) {
                currentIndex = 0
            } else {
                NPLogger.d("NERI-Player", "Already at the end of the playlist.")
                return
            }
        }
        playAtIndex(currentIndex)
    }

    fun previous() {
        if (currentPlaylist.isEmpty()) return

        if (player.shuffleModeEnabled) {
            currentIndex = (0 until currentPlaylist.size).random()
        } else {
            if (currentIndex > 0) {
                currentIndex--
            } else {
                NPLogger.d("NERI-Player", "Already at the start of the playlist.")
                return
            }
        }
        playAtIndex(currentIndex)
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

    @androidx.annotation.OptIn(UnstableApi::class)
    fun release() {
        player.release()
        cache.release()
        mainScope.cancel()
        ioScope.cancel()
    }

    fun setShuffle(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = mainScope.launch {
            while (isActive) {
                _playbackPositionMs.value = player.currentPosition
                delay(100)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun stopAndClearPlaylist() {
        player.stop()
        player.clearMediaItems()
        _isPlayingFlow.value = false
        _currentSongFlow.value = null
        currentIndex = -1
        currentPlaylist = emptyList()
        consecutivePlayFailures = 0
    }

    fun hasItems(): Boolean = player.currentMediaItem != null
}