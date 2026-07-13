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
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.bili.BiliFavoriteFolderContentCache
import moe.ouom.neriplayer.data.platform.bili.CachedBiliFavoriteVideo
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.IOException

private const val TAG = "NERI-BiliPlaylistVM"
private const val BILI_RESOURCE_TYPE_VIDEO = 2
private const val BILI_RESOURCE_TYPE_COLLECTION = 21
private const val BILI_FAVORITE_LATEST_PAGE_SIZE = 20

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
    private val favoriteCacheRepo = AppContainer.biliFavoriteFolderCacheRepo

    private val _uiState = MutableStateFlow(BiliPlaylistDetailUiState())
    val uiState: StateFlow<BiliPlaylistDetailUiState> = _uiState

    private var mediaId: Long = 0L
    private var currentPlaylist: BiliPlaylist? = null

    fun start(playlist: BiliPlaylist, forceRefresh: Boolean = false) {
        currentPlaylist = playlist
        mediaId = playlist.mediaId
        val cachedVideos = if (!forceRefresh && playlist.isFavoriteFolder()) {
            favoriteCacheRepo.read(playlist.mediaId)?.videos.orEmpty().map { it.toVideoItem() }
        } else {
            emptyList()
        }

        _uiState.value = BiliPlaylistDetailUiState(
            loading = true,
            header = playlist.copy(count = cachedVideos.size.takeIf { it > 0 } ?: playlist.count),
            videos = cachedVideos
        )
        NPLogger.d(
            TAG,
            "start load: mediaId=${playlist.mediaId}, kind=${playlist.kind}, forceRefresh=$forceRefresh, cachedCount=${cachedVideos.size}"
        )
        loadContent(forceRefresh = forceRefresh)
    }

    fun retry() {
        (uiState.value.header ?: currentPlaylist)?.let { start(it, forceRefresh = true) }
    }

    fun refresh() {
        (uiState.value.header ?: currentPlaylist)?.let { start(it, forceRefresh = true) }
    }


    /**
     * 获取单个视频的详细信息，包括分P列表
     * @param bvid 视频的 BV 号
     * @return 包含所有分P信息的 VideoBasicInfo 对象
     */
    suspend fun getVideoInfo(bvid: String): BiliClient.VideoBasicInfo {
        return withContext(Dispatchers.IO) {
            NPLogger.d(TAG, "getVideoInfo start: bvid=$bvid")
            runCatching { client.getVideoBasicInfoByBvid(bvid) }
                .onSuccess {
                    NPLogger.d(TAG, "getVideoInfo success: bvid=$bvid, pages=${it.pages.size}")
                }
                .onFailure {
                    NPLogger.e(TAG, "getVideoInfo failed: bvid=$bvid", it)
                }
                .getOrThrow()
        }
    }

    private fun loadContent(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val header = uiState.value.header ?: return@launch
                NPLogger.d(
                    TAG,
                    "loadContent start: mediaId=${header.mediaId}, kind=${header.kind}, forceRefresh=$forceRefresh, cachedCount=${uiState.value.videos.size}"
                )
                val videos = withContext(Dispatchers.IO) {
                    when (header.kind) {
                        BiliPlaylistKind.COLLECTION -> loadCollectionVideos(header)
                        BiliPlaylistKind.CREATED_FAVORITE,
                        BiliPlaylistKind.COLLECTED_FAVORITE -> loadFavoriteFolderVideos(
                            playlist = header,
                            forceRefresh = forceRefresh
                        )
                    }
                }

                _uiState.value = _uiState.value.copy(
                    loading = false,
                    header = header.copy(count = videos.size),
                    videos = videos
                )
                NPLogger.d(
                    TAG,
                    "loadContent success: mediaId=${header.mediaId}, kind=${header.kind}, count=${videos.size}"
                )

            } catch (e: IOException) {
                val hasCachedVideos = uiState.value.videos.isNotEmpty()
                NPLogger.e(
                    TAG,
                    "loadContent network failed: mediaId=$mediaId, hasCachedVideos=$hasCachedVideos",
                    e
                )
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = if (hasCachedVideos) null else "Network error: ${e.message}"
                )
            } catch (e: Exception) {
                val hasCachedVideos = uiState.value.videos.isNotEmpty()
                NPLogger.e(
                    TAG,
                    "loadContent failed: mediaId=$mediaId, hasCachedVideos=$hasCachedVideos",
                    e
                )
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = if (hasCachedVideos) null else "Load failed: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadFavoriteFolderVideos(
        playlist: BiliPlaylist,
        forceRefresh: Boolean
    ): List<BiliVideoItem> {
        val cached = favoriteCacheRepo.read(playlist.mediaId)
        NPLogger.d(
            TAG,
            "loadFavoriteFolderVideos start: mediaId=${playlist.mediaId}, forceRefresh=$forceRefresh, hasCache=${cached != null}, cachedCount=${cached?.videos?.size ?: 0}"
        )
        val latestPageResult = runCatching {
            client.getFavFolderContents(
                mediaId = playlist.mediaId,
                page = 1,
                pageSize = BILI_FAVORITE_LATEST_PAGE_SIZE
            )
        }
        if (latestPageResult.isFailure && cached != null && !forceRefresh) {
            NPLogger.w(
                TAG,
                "loadFavoriteFolderVideos fallback to cache: mediaId=${playlist.mediaId}, message=${latestPageResult.exceptionOrNull()?.message}"
            )
            return cached.videos.map { it.toVideoItem() }
        }
        latestPageResult.exceptionOrNull()?.let { error ->
            NPLogger.e(
                TAG,
                "loadFavoriteFolderVideos latest page failed: mediaId=${playlist.mediaId}, forceRefresh=$forceRefresh",
                error
            )
        }

        val latestPage = latestPageResult.getOrThrow()
        val latestSignature = latestPage.latestPageSignature()
        if (!forceRefresh && cached?.latestPageSignature == latestSignature) {
            NPLogger.d(
                TAG,
                "loadFavoriteFolderVideos reuse cached signature: mediaId=${playlist.mediaId}, count=${cached.videos.size}"
            )
            return cached.videos.map { it.toVideoItem() }
        }

        val items = client.getAllFavFolderItems(playlist.mediaId, latestPage)
        val videos = mapFavoriteItemsToVideos(items)
        favoriteCacheRepo.save(
            BiliFavoriteFolderContentCache(
                mediaId = playlist.mediaId,
                latestPageSignature = latestSignature,
                totalCount = latestPage.info.count,
                videos = videos.map { it.toCachedVideo() }
            )
        )
        NPLogger.d(
            TAG,
            "loadFavoriteFolderVideos refreshed: mediaId=${playlist.mediaId}, items=${items.size}, videos=${videos.size}"
        )
        return videos
    }

    private suspend fun mapFavoriteItemsToVideos(items: List<BiliClient.FavResourceItem>): List<BiliVideoItem> {
        val videos = ArrayList<BiliVideoItem>(items.size)
        for (item in items) {
            when (item.type) {
                BILI_RESOURCE_TYPE_VIDEO -> item.toVideoItem()?.let(videos::add)
                BILI_RESOURCE_TYPE_COLLECTION -> {
                    val collectionVideos = runCatching {
                        client.getAllCollectionArchives(mid = item.upperMid, seasonId = item.id)
                    }.onFailure { error ->
                        NPLogger.w(
                            TAG,
                            "load collection videos failed: seasonId=${item.id}, upperMid=${item.upperMid}, title=${item.title}",
                            error
                        )
                    }.getOrDefault(emptyList())
                    collectionVideos.mapTo(videos) { archive ->
                        archive.toVideoItem(uploader = item.upperName.ifBlank { item.title })
                    }
                }
            }
        }
        return videos.distinctBy { it.bvid.ifBlank { it.id.toString() } }
    }

    private fun BiliClient.FavResourcePage.latestPageSignature(): String {
        return buildString {
            append(info.count)
            append('#')
            items.forEach { item ->
                append(item.type)
                append(':')
                append(item.id)
                append(':')
                append(item.bvid.orEmpty())
                append(':')
                append(item.favTime ?: 0L)
                append(':')
                append(item.durationSec)
                append(':')
                append(item.title)
                append('|')
            }
        }
    }

    private fun BiliPlaylist.isFavoriteFolder(): Boolean {
        return kind == BiliPlaylistKind.CREATED_FAVORITE || kind == BiliPlaylistKind.COLLECTED_FAVORITE
    }

    private suspend fun loadCollectionVideos(playlist: BiliPlaylist): List<BiliVideoItem> {
        if (playlist.mid == 0L) {
            NPLogger.w(TAG, "loadCollectionVideos skipped because mid is 0: mediaId=${playlist.mediaId}")
            return emptyList()
        }
        val videos = client.getAllCollectionArchives(mid = playlist.mid, seasonId = playlist.mediaId)
            .map { archive ->
                archive.toVideoItem(uploader = playlist.subtitle.ifBlank { playlist.title })
            }
        NPLogger.d(TAG, "loadCollectionVideos success: mediaId=${playlist.mediaId}, count=${videos.size}")
        return videos
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

    private fun CachedBiliFavoriteVideo.toVideoItem(): BiliVideoItem {
        return BiliVideoItem(
            id = id,
            bvid = bvid,
            title = title,
            uploader = uploader,
            coverUrl = coverUrl,
            durationSec = durationSec
        )
    }

    private fun BiliVideoItem.toCachedVideo(): CachedBiliFavoriteVideo {
        return CachedBiliFavoriteVideo(
            id = id,
            bvid = bvid,
            title = title,
            uploader = uploader,
            coverUrl = coverUrl,
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
