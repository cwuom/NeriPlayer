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
import moe.ouom.neriplayer.core.api.bili.buildBiliPartSong
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import java.io.IOException

private const val BILI_RESOURCE_TYPE_VIDEO = 2
private const val BILI_RESOURCE_TYPE_COLLECTION = 21

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
                val header = uiState.value.header ?: return@launch
                val videos = withContext(Dispatchers.IO) {
                    when (header.kind) {
                        BiliPlaylistKind.COLLECTION -> loadCollectionVideos(header)
                        BiliPlaylistKind.CREATED_FAVORITE,
                        BiliPlaylistKind.COLLECTED_FAVORITE -> loadFavoriteFolderVideos(header)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    loading = false,
                    header = header.copy(count = videos.size),
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

    private suspend fun loadFavoriteFolderVideos(playlist: BiliPlaylist): List<BiliVideoItem> {
        val items = client.getAllFavFolderItems(playlist.mediaId)
        val videos = ArrayList<BiliVideoItem>(items.size)
        for (item in items) {
            when (item.type) {
                BILI_RESOURCE_TYPE_VIDEO -> item.toVideoItem()?.let(videos::add)
                BILI_RESOURCE_TYPE_COLLECTION -> {
                    val collectionVideos = runCatching {
                        client.getAllCollectionArchives(mid = item.upperMid, seasonId = item.id)
                    }.getOrDefault(emptyList())
                    collectionVideos.mapTo(videos) { archive ->
                        archive.toVideoItem(uploader = item.upperName.ifBlank { item.title })
                    }
                }
            }
        }
        return videos.distinctBy { it.bvid.ifBlank { it.id.toString() } }
    }

    private suspend fun loadCollectionVideos(playlist: BiliPlaylist): List<BiliVideoItem> {
        if (playlist.mid == 0L) return emptyList()
        return client.getAllCollectionArchives(mid = playlist.mid, seasonId = playlist.mediaId)
            .map { archive ->
                archive.toVideoItem(uploader = playlist.subtitle.ifBlank { playlist.title })
            }
    }

    private fun BiliClient.FavResourceItem.toVideoItem(): BiliVideoItem? {
        val resolvedBvid = bvid?.takeIf { it.isNotBlank() } ?: return null
        return BiliVideoItem(
            id = id,
            bvid = resolvedBvid,
            title = title,
            uploader = upperName,
            coverUrl = coverUrl.replaceFirst("http://", "https://"),
            durationSec = durationSec
        )
    }

    private fun BiliClient.CollectionArchiveItem.toVideoItem(uploader: String): BiliVideoItem {
        return BiliVideoItem(
            id = aid,
            bvid = bvid,
            title = title,
            uploader = uploader,
            coverUrl = coverUrl.replaceFirst("http://", "https://"),
            durationSec = durationSec
        )
    }

    /**
     * 将 Bilibili 视频的分P转换为通用的 SongItem
     * @param page 分P信息
     * @param basicInfo 视频的基本信息
     * @param coverUrl 视频封面
     * @return 转换后的 SongItem
     */
    fun toSongItem(page: BiliClient.VideoPage, basicInfo: BiliClient.VideoBasicInfo, coverUrl: String): SongItem {
        return buildBiliPartSong(page, basicInfo, coverUrl)
    }
}
