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
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.data.NeteaseCookieRepository
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.ui.viewmodel.SongItem
import org.json.JSONObject
import java.io.File
import kotlin.math.max

object PlayerManager {
    private var initialized = false
    private lateinit var application: Application
    private lateinit var player: ExoPlayer

    private lateinit var cache: Cache

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null

    private val neteaseClient = NeteaseClient()

    private var preferredQuality: String = "exhigh"

    // --- 播放列表管理 ---
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

            // 这个 Listener 不再需要，因为我们一次只处理一个 MediaItem
            // onMediaItemTransition 不会像以前那样在播放列表内部切换时触发
            // override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}

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
    }

    /**
     * 播放一个新列表。
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
     * 根据索引播放当前列表中的歌曲（核心懒加载逻辑）。
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
                // 可以增加错误提示，或者直接跳到下一首
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
                // 如果需要，可以刷新Cookie
                // val cookies = NeteaseCookieRepository(application).getCookiesOnce()
                // neteaseClient.setPersistedCookies(cookies)

                val resp = neteaseClient.getSongDownloadUrl(
                    songId,
                    bitrate = 320000,
                    level = preferredQuality
                )
                Log.d("NERI-PlayerManager", "id=$songId, resp=$resp")

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
                Log.d("NERI-Player", "Already at the end of the playlist.")
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
                Log.d("NERI-Player", "Already at the start of the playlist.")
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