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
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.ExoDatabaseProvider
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

object PlayerManager {
    private var initialized = false
    private lateinit var application: Application
    private lateinit var player: ExoPlayer

    private lateinit var cache: Cache

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null

    private val neteaseClient = NeteaseClient()

    private var preferredQuality: String = "exhigh"

    // 播放列表管理
    private var currentPlaylist: List<SongItem> = emptyList()
    private var currentIndex = -1

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


    // 暴露当前音频设备
    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
    val currentAudioDeviceFlow: StateFlow<AudioDevice?> = _currentAudioDevice


    fun initialize(app: Application) {
        if (initialized) return
        initialized = true
        application = app

        // 10GB LRU 缓存
        val cacheDir = File(app.cacheDir, "media_cache")
        val db = ExoDatabaseProvider(app)
        cache = SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(10L * 1024 * 1024 * 1024), db)

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
                Log.e("NERI-Player", "onPlayerError: ${error.errorCodeName}", error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    _playbackPositionMs.value = 0L
                    // 自动播放下一首
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> {
                            if (currentIndex < currentPlaylist.size - 1) {
                                next()
                            } else {
                                // 列表播完，重置状态
                                _isPlayingFlow.value = false
                                _currentSongFlow.value = null
                                currentIndex = -1
                                currentPlaylist = emptyList()
                            }
                        }
                        Player.REPEAT_MODE_ALL -> next(force = true) // 强制循环到下一首
                        Player.REPEAT_MODE_ONE -> playAtIndex(currentIndex) // 重新播放当前
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

        ioScope.launch {
            SettingsRepository(app).audioQualityFlow.collect { q -> preferredQuality = q }
        }

        ioScope.launch {
            val cookies = NeteaseCookieRepository(app).getCookiesOnce()
            neteaseClient.setPersistedCookies(cookies)
        }

        setupAudioDeviceCallback()
    }

    private fun setupAudioDeviceCallback() {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 初始化当前设备状态
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

        // 更新设备信息 StateFlow
        _currentAudioDevice.value = newDevice

        // 如果从非扬声器切换到扬声器，并且正在播放，则暂停
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

    /**
     * 播放一个新列表
     */
    fun playPlaylist(songs: List<SongItem>, startIndex: Int) {
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        if (songs.isEmpty()) {
            Log.w("NERI-Player", "playPlaylist called with EMPTY list")
            return
        }

        currentPlaylist = songs
        currentIndex = startIndex
        playAtIndex(currentIndex)
    }

    /**
     * 根据索引播放当前列表中的歌曲（核心懒加载逻辑）
     */
    private fun playAtIndex(index: Int) {
        if (currentPlaylist.isEmpty() || index !in currentPlaylist.indices) {
            Log.w("NERI-Player", "playAtIndex called with invalid index: $index")
            return
        }

        val song = currentPlaylist[index]
        _currentSongFlow.value = song // 立即更新UI上的歌曲信息

        ioScope.launch {
            val url = getSongUrl(song.id)
            if (url == null) {
                Log.e("NERI-PlayerManager", "获取播放 URL 失败, 跳过: id=${song.id}")
                withContext(Dispatchers.Main) { next() }
                return@launch
            }

            val mediaItem = MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(Uri.parse(url))
                .build()

            withContext(Dispatchers.Main) {
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }
        }
    }

    private suspend fun getSongUrl(songId: Long): String? {
        return withContext(Dispatchers.IO) {
            try {
                val resp = neteaseClient.getSongDownloadUrl(
                    songId,
                    bitrate = 320000,
                    level = preferredQuality
                )
                NPLogger.d("NERI-PlayerManager", "id=$songId, resp=$resp")

                val root = JSONObject(resp)
                val url = when (val dataObj = root.opt("data")) {
                    is JSONObject -> dataObj.optString("url", "")
                    is org.json.JSONArray -> dataObj.optJSONObject(0)?.optString("url", "")
                    else -> ""
                }

                if (url.isNullOrBlank()) {
                    Log.e("NERI-PlayerManager", "获取播放 URL 失败: id=$songId, 返回=$resp")
                    null
                } else {
                    if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url
                }
            } catch (e: Exception) {
                Log.e("NERI-PlayerManager", "获取URL时出错", e)
                null
            }
        }
    }

    fun play() {
        if (hasItems()) {
            player.play()
        } else if (currentPlaylist.isNotEmpty() && currentIndex != -1) {
            // 如果播放器是暂停后被系统回收了，但我们的管理器里还有状态，就恢复播放
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
            // force 用于 REPEAT_ALL 模式，即使是最后一首也要跳到第一首
            if (currentIndex < currentPlaylist.size - 1) {
                currentIndex++
            } else if (force) {
                currentIndex = 0
            } else {
                NPLogger.d("NERI-Player", "Already at the end of the playlist.")
                return // 到头了，不播了
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


    fun hasItems(): Boolean = player.currentMediaItem != null
}