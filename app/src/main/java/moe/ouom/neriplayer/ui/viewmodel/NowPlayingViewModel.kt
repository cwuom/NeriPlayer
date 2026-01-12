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
        viewModelScope.launch {
            AudioDownloadManager.downloadSong(context, song)
        }
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
                    PlayerManager.updateSongLyrics(song, songDetails.lyric!!)
                    NPLogger.d("NowPlayingViewModel", "歌词已保存: songId=${song.id}, album=${song.album}, lyrics length=${songDetails.lyric!!.length}")
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

    fun restoreOriginalInfo(originalSong: SongItem) {
        // 如果有保存的原始值，则恢复到原始值
        if (originalSong.originalName != null || originalSong.originalArtist != null || originalSong.originalCoverUrl != null) {
            PlayerManager.updateSongCustomInfo(
                originalSong = originalSong,
                customCoverUrl = null,
                customName = null,
                customArtist = null
            )
            // 同时恢复基础字段到原始值
            PlayerManager.restoreToOriginalMetadata(originalSong)
        } else {
            // 没有原始值，只清除自定义字段
            PlayerManager.updateSongCustomInfo(
                originalSong = originalSong,
                customCoverUrl = null,
                customName = null,
                customArtist = null
            )
        }
    }

}