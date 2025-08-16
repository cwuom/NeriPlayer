package moe.ouom.neriplayer.ui.viewmodel.playlist

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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/BiliPlaylistDetailViewModel
 * Created: 2025/8/15
 */

import android.app.Application
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.data.BiliCookieRepository
import moe.ouom.neriplayer.ui.viewmodel.BiliPlaylist
import java.io.IOException

/** Bilibili 视频条目数据模型 */
@Parcelize
data class BiliVideoItem(
    val id: Long, // avid
    val bvid: String,
    val title: String,
    val uploader: String,
    val coverUrl: String,
    val durationSec: Int
) : Parcelable

/** Bilibili 收藏夹详情页 UI 状态 */
data class BiliPlaylistDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val header: BiliPlaylist? = null,
    val videos: List<BiliVideoItem> = emptyList()
)

class BiliPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val cookieRepo = BiliCookieRepository(application)
    private val client = BiliClient(cookieRepo)

    private val _uiState = MutableStateFlow(BiliPlaylistDetailUiState())
    val uiState: StateFlow<BiliPlaylistDetailUiState> = _uiState

    private var mediaId: Long = 0L

    fun start(playlist: BiliPlaylist) {
        if (mediaId == playlist.mediaId && uiState.value.videos.isNotEmpty()) return
        mediaId = playlist.mediaId

        _uiState.value = BiliPlaylistDetailUiState(
            loading = true,
            header = playlist,
            videos = emptyList()
        )
        loadContent()
    }

    fun retry() {
        uiState.value.header?.let { start(it) }
    }

    private fun loadContent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val items = withContext(Dispatchers.IO) {
                    client.getAllFavFolderItems(mediaId)
                }

                val videos = items.mapNotNull {
                    // 仅保留视频类型的内容
                    if (it.type == 2) {
                        BiliVideoItem(
                            id = it.id,
                            bvid = it.bvid ?: "",
                            title = it.title,
                            uploader = it.upperName,
                            coverUrl = it.coverUrl.replaceFirst("http://", "https://"),
                            durationSec = it.durationSec
                        )
                    } else {
                        null
                    }
                }

                _uiState.value = _uiState.value.copy(
                    loading = false,
                    videos = videos
                )

            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "网络异常: ${e.message}"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "加载失败: ${e.message}"
                )
            }
        }
    }
}