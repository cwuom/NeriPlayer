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
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
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
    private val client = AppContainer.biliClient

    private val _uiState = MutableStateFlow(BiliPlaylistDetailUiState())
    val uiState: StateFlow<BiliPlaylistDetailUiState> = _uiState

    private var mediaId: Long = 0L

    fun start(playlist: BiliPlaylist) {
        // 移除缓存检查，确保每次进入都能获取最新数据
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


    /**
     * 获取单个视频的详细信息，包括分P列表
     * @param bvid 视频的 BV 号
     * @return 包含所有分P信息的 VideoBasicInfo 对象
     */
    suspend fun getVideoInfo(bvid: String): BiliClient.VideoBasicInfo {
        return withContext(Dispatchers.IO) {
            client.getVideoBasicInfoByBvid(bvid)
        }
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
                    error = "Network error: ${e.message}"  // Localized in UI
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "Load failed: ${e.message}"  // Localized in UI
                )
            }
        }
    }

    /**
     * 将 Bilibili 视频的分P转换为通用的 SongItem
     * @param page 分P信息
     * @param basicInfo 视频的基本信息
     * @param coverUrl 视频封面
     * @return 转换后的 SongItem
     */
    fun toSongItem(page: BiliClient.VideoPage, basicInfo: BiliClient.VideoBasicInfo, coverUrl: String): SongItem {
        return SongItem(
            id = basicInfo.aid * 10000 + page.page, // 使用 avid 和 page 组合成唯一 ID
            name = page.part, // 直接使用分P的标题作为歌曲名
            artist = basicInfo.ownerName,
            album = "Bilibili",
            albumId = 0L,
            durationMs = page.durationSec * 1000L,
            coverUrl = coverUrl
        )
    }
}