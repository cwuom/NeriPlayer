package moe.ouom.neriplayer.ui.viewmodel

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
 * File: moe.ouom.neriplayer.ui.viewmodel/NowPlayingViewModel
 * Created: 2025/8/17
 */

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.SearchManager
import moe.ouom.neriplayer.navigation.Destinations
import androidx.core.content.ContextCompat
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.AudioPlayerService
import moe.ouom.neriplayer.ui.NeriApp
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.R

data class ManualSearchState(
    val keyword: String = "",
    val selectedPlatform: MusicPlatform = MusicPlatform.CLOUD_MUSIC,
    val searchResults: List<SongSearchInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class NowPlayingViewModel : ViewModel() {

    private val _manualSearchState = MutableStateFlow(ManualSearchState())
    val manualSearchState = _manualSearchState.asStateFlow()

    fun prepareForSearch(initialKeyword: String) {
        _manualSearchState.update {
            it.copy(
                keyword = initialKeyword,
                searchResults = emptyList(), // 清空上次结果
                error = null
            )
        }
    }

    fun onKeywordChange(newKeyword: String) {
        _manualSearchState.update { it.copy(keyword = newKeyword) }
    }

    fun selectPlatform(platform: MusicPlatform) {
        _manualSearchState.update { it.copy(selectedPlatform = platform) }
        performSearch()
    }

    fun performSearch() {
        if (_manualSearchState.value.keyword.isBlank()) return

        viewModelScope.launch {
            _manualSearchState.update { it.copy(isLoading = true, error = null) }
            try {
                val results = SearchManager.search(
                    keyword = _manualSearchState.value.keyword,
                    platform = _manualSearchState.value.selectedPlatform,
                )
                _manualSearchState.update { it.copy(isLoading = false, searchResults = results) }

            } catch (e: Exception) {
                _manualSearchState.update { it.copy(isLoading = false, error = "Search failed: ${e.message}") }  // Localized in UI
            }
        }
    }

    fun onSongSelected(originalSong: SongItem, selectedSong: SongSearchInfo) {
        PlayerManager.replaceMetadataFromSearch(originalSong, selectedSong)
    }

    fun downloadSong(context: Context, song: SongItem) {
        GlobalDownloadManager.startDownload(context, song)
    }

    fun fillLyrics(context: Context, song: SongItem, selectedSong: SongSearchInfo, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val platform = selectedSong.source
                val api = when (platform) {
                    MusicPlatform.CLOUD_MUSIC -> {
                        val client = AppContainer.neteaseClient
                        if (client == null) {
                            NPLogger.e("NowPlayingViewModel", "neteaseClient is null")
                            onComplete(false, context.getString(R.string.music_lyrics_fill_failed))
                            return@launch
                        }
                        moe.ouom.neriplayer.core.api.search.CloudMusicSearchApi(client)
                    }
                    MusicPlatform.QQ_MUSIC -> moe.ouom.neriplayer.core.api.search.QQMusicSearchApi()
                }

                val songDetails = api.getSongInfo(selectedSong.id)

                if (!songDetails.lyric.isNullOrBlank()) {
                    // 一次性更新歌词和翻译歌词，避免数据竞争
                    PlayerManager.updateSongLyricsAndTranslation(
                        song,
                        songDetails.lyric!!,
                        songDetails.translatedLyric
                    )
                    NPLogger.d("NowPlayingViewModel", "歌词已保存: songId=${song.id}, album=${song.album}, lyrics length=${songDetails.lyric.length}, hasTranslation=${!songDetails.translatedLyric.isNullOrBlank()}")
                    onComplete(true, context.getString(R.string.music_lyrics_filled_success))
                } else {
                    NPLogger.w("NowPlayingViewModel", "获取的歌词为空: searchSongId=${selectedSong.id}")
                    onComplete(false, context.getString(R.string.music_lyrics_empty))
                }
            } catch (e: Exception) {
                NPLogger.e("NowPlayingViewModel", "获取歌词失败", e)
                onComplete(false, context.getString(R.string.music_lyrics_fill_failed))
            }
        }
    }

    fun updateSongInfo(
        originalSong: SongItem,
        newCoverUrl: String?,
        newName: String,
        newArtist: String
    ) {
        PlayerManager.updateSongCustomInfo(
            originalSong = originalSong,
            customCoverUrl = newCoverUrl,
            customName = newName,
            customArtist = newArtist
        )
    }

    data class OriginalSongInfo(
        val name: String,
        val artist: String,
        val coverUrl: String?,
        val shouldClearLyrics: Boolean = false,  // B站音源应该清除歌词
        val lyric: String? = null,  // 网易云音源的原始歌词
        val translatedLyric: String? = null  // 网易云音源的原始翻译歌词
    )

    fun fetchOriginalInfo(context: Context, originalSong: SongItem, onResult: (Boolean, OriginalSongInfo?, String) -> Unit) {
        viewModelScope.launch {
            try {
                val isBili = originalSong.album.startsWith("Bilibili")

                if (isBili) {
                    // B站视频：从B站获取原始信息
                    val appContainer = moe.ouom.neriplayer.core.di.AppContainer
                    val videoInfo = appContainer.biliClient.getVideoBasicInfoByAvid(originalSong.id)

                    val parts = originalSong.album.split('|')
                    val targetCid = if (parts.size > 1) parts[1].toLongOrNull() else null
                    val pageInfo = if (targetCid != null) {
                        videoInfo.pages.firstOrNull { it.cid == targetCid }
                    } else {
                        videoInfo.pages.firstOrNull()
                    }

                    val coverUrl = videoInfo.coverUrl.let {
                        if (it.startsWith("http://")) it.replaceFirst("http://", "https://") else it
                    }

                    val info = OriginalSongInfo(
                        name = pageInfo?.part ?: videoInfo.title,
                        artist = videoInfo.ownerName,
                        coverUrl = coverUrl,
                        shouldClearLyrics = true  // B站音源应该清除歌词
                    )
                    onResult(true, info, context.getString(R.string.music_restore_success))
                } else {
                    // 网易云音乐：从网易云获取原始信息
                    val appContainer = moe.ouom.neriplayer.core.di.AppContainer
                    val songDetails = appContainer.cloudMusicSearchApi?.getSongInfo(originalSong.id.toString())

                    if (songDetails != null) {
                        val coverUrl = songDetails.coverUrl?.let {
                            if (it.startsWith("http://")) it.replaceFirst("http://", "https://") else it
                        }

                        val info = OriginalSongInfo(
                            name = songDetails.songName,
                            artist = songDetails.singer,
                            coverUrl = coverUrl,
                            shouldClearLyrics = false,  // 网易云音源不清除歌词
                            lyric = songDetails.lyric,  // 保存原始歌词
                            translatedLyric = songDetails.translatedLyric  // 保存原始翻译歌词
                        )
                        onResult(true, info, context.getString(R.string.music_restore_success))
                    } else {
                        onResult(false, null, context.getString(R.string.music_restore_failed))
                    }
                }
            } catch (e: Exception) {
                NPLogger.e("NowPlayingViewModel", "获取原始信息失败", e)
                onResult(false, null, context.getString(R.string.music_restore_failed))
            }
        }
    }

}