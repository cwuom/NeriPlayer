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
 * File: moe.ouom.neriplayer.ui.viewmodel/LocalPlaylistDetailViewModel
 * Created: 2025/8/11
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.LocalPlaylistRepository.Companion.FAVORITES_NAME

/** 本地歌单详情页面状态 */
data class LocalPlaylistDetailUiState(
    val playlist: LocalPlaylist? = null
)

class LocalPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = LocalPlaylistRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LocalPlaylistDetailUiState())
    val uiState: StateFlow<LocalPlaylistDetailUiState> = _uiState

    private var playlistId: Long = 0L

    fun start(id: Long) {
        if (playlistId == id && _uiState.value.playlist != null) return
        playlistId = id
        viewModelScope.launch {
            repo.playlists.collect { list ->
                _uiState.value = LocalPlaylistDetailUiState(list.firstOrNull { it.id == id })
            }
        }
    }

    fun rename(newName: String) {
        viewModelScope.launch {
            var name = newName
            if (newName == FAVORITES_NAME) {
                name = FAVORITES_NAME + "_2"
            }
            repo.renamePlaylist(playlistId, name)
        }
    }

    fun removeSongs(ids: List<Long>) {
        viewModelScope.launch {
            val pid = uiState.value.playlist?.id ?: return@launch
            repo.removeSongsFromPlaylist(pid, ids)
        }
    }

    /** 删除：把结果回调给 UI */
    fun delete(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repo.deletePlaylist(playlistId)
            onResult(ok)
        }
    }

    fun moveSong(from: Int, to: Int) {
        viewModelScope.launch { repo.moveSong(playlistId, from, to) }
    }

    fun removeSong(songId: Long) {
        viewModelScope.launch { repo.removeSongFromPlaylist(playlistId, songId) }
    }
}
