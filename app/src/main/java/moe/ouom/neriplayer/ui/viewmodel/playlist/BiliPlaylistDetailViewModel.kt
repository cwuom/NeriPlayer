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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.core.api.bili.buildBiliPartSong
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.bili.BiliArchiveContentCache
import moe.ouom.neriplayer.data.platform.bili.BiliFavoriteFolderContentCache
import moe.ouom.neriplayer.data.platform.bili.CachedBiliArchiveVideo
import moe.ouom.neriplayer.data.platform.bili.CachedBiliFavoriteVideo
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.IOException

private const val TAG = "NERI-BiliPlaylistVM"
private const val BILI_RESOURCE_TYPE_VIDEO = 2
private const val BILI_RESOURCE_TYPE_COLLECTION = 21
private const val BILI_FAVORITE_LATEST_PAGE_SIZE = 20
private const val BILI_ARCHIVE_PAGE_SIZE = 12

/** Bilibili 视频条目数据模型 */
@Parcelize
data class BiliVideoItem(
    val id: Long, // avid
    val bvid: String,
    val title: String,
    val uploader: String,
    val uploaderMid: Long = 0L,
    val coverUrl: String,
    val durationSec: Int
) : Parcelable

/** Bilibili 收藏夹详情页 UI 状态 */
data class BiliPlaylistDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val header: BiliPlaylist? = null,
    val videos: List<BiliVideoItem> = emptyList(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false
)

internal data class BiliPagedVideoPage(
    val videos: List<BiliVideoItem>,
    val totalCount: Int,
    val hasMore: Boolean
)

internal fun mergeBiliPagedVideoPage(
    existingVideos: List<BiliVideoItem>,
    incomingVideos: List<BiliVideoItem>,
    totalCount: Int,
    hasMore: Boolean
): BiliPagedVideoPage {
    val videos = (existingVideos + incomingVideos)
        .distinctBy { video -> video.bvid.ifBlank { video.id.toString() } }
    return BiliPagedVideoPage(
        videos = videos,
        totalCount = totalCount.coerceAtLeast(videos.size),
        hasMore = hasMore && incomingVideos.isNotEmpty()
    )
}

internal fun applyBiliArchiveUploader(
    videos: List<BiliVideoItem>,
    uploader: String,
    uploaderMid: Long
): List<BiliVideoItem> {
    val resolvedUploader = uploader.trim()
    if (resolvedUploader.isEmpty()) return videos
    return videos.map { video ->
        video.copy(
            uploader = resolvedUploader,
            uploaderMid = uploaderMid.takeIf { it > 0L } ?: video.uploaderMid
        )
    }
}

private data class BiliPlaylistContentLoad(
    val videos: List<BiliVideoItem>,
    val totalCount: Int,
    val hasMore: Boolean = false
)

class BiliPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AppContainer.biliClient
    private val favoriteCacheRepo = AppContainer.biliFavoriteFolderCacheRepo
    private val archiveCacheRepo = AppContainer.biliArchiveCacheRepo

    private val _uiState = MutableStateFlow(BiliPlaylistDetailUiState())
    val uiState: StateFlow<BiliPlaylistDetailUiState> = _uiState

    private var mediaId: Long = 0L
    private var currentPlaylist: BiliPlaylist? = null
    private var archivePage: Int = 1

    fun start(playlist: BiliPlaylist, forceRefresh: Boolean = false) {
        currentPlaylist = playlist
        mediaId = playlist.mediaId
        archivePage = 1

        _uiState.value = BiliPlaylistDetailUiState(
            loading = true,
            header = playlist
        )
        NPLogger.d(
            TAG,
            "start load: mediaId=${playlist.mediaId}, kind=${playlist.kind}, forceRefresh=$forceRefresh"
        )
        loadContent(forceRefresh = forceRefresh)
    }

    fun retry() {
        (uiState.value.header ?: currentPlaylist)?.let { start(it, forceRefresh = true) }
    }

    fun refresh() {
        (uiState.value.header ?: currentPlaylist)?.let { start(it, forceRefresh = true) }
    }

    fun loadMoreVideos() {
        val state = _uiState.value
        val header = state.header ?: return
        if (
            !header.kind.hasPagedArchives() ||
            header.mid <= 0L ||
            state.loadingMore ||
            !state.hasMore
        ) {
            return
        }

        val nextPage = archivePage + 1
        _uiState.update { current ->
            if (current.header?.mediaId == header.mediaId) {
                current.copy(loadingMore = true)
            } else {
                current
            }
        }
        viewModelScope.launch {
            try {
                val merged = withContext(Dispatchers.IO) {
                    loadArchiveVideos(
                        playlist = header,
                        page = nextPage,
                        existingVideos = state.videos,
                    )
                }
                if (_uiState.value.header?.mediaId != header.mediaId) return@launch

                archivePage = nextPage
                _uiState.update { current ->
                    current.copy(
                        header = current.header?.copy(count = merged.totalCount),
                        videos = merged.videos,
                        hasMore = merged.hasMore,
                        loadingMore = false
                    )
                }
                NPLogger.d(
                    TAG,
                    "load more archives success: mediaId=${header.mediaId}, kind=${header.kind}, page=$nextPage, loaded=${merged.videos.size}, total=${merged.totalCount}"
                )
            } catch (error: Exception) {
                NPLogger.e(
                    TAG,
                    "load more archives failed: mediaId=${header.mediaId}, kind=${header.kind}, page=$nextPage",
                    error
                )
                _uiState.update { current ->
                    if (current.header?.mediaId == header.mediaId) {
                        current.copy(loadingMore = false)
                    } else {
                        current
                    }
                }
            }
        }
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
                val cachedContent = withContext(Dispatchers.IO) {
                    loadCachedContent(header, forceRefresh)
                }
                if (cachedContent != null && _uiState.value.header?.sameIdentityAs(header) == true) {
                    _uiState.update { current ->
                        current.copy(
                            loading = true,
                            error = null,
                            header = header.copy(count = cachedContent.totalCount),
                            videos = cachedContent.videos,
                            hasMore = cachedContent.hasMore,
                            loadingMore = false
                        )
                    }
                    NPLogger.d(
                        TAG,
                        "published cached content: mediaId=${header.mediaId}, kind=${header.kind}, count=${cachedContent.videos.size}"
                    )
                }
                NPLogger.d(
                    TAG,
                    "loadContent start: mediaId=${header.mediaId}, kind=${header.kind}, forceRefresh=$forceRefresh, cachedCount=${cachedContent?.videos?.size ?: 0}"
                )
                val content = withContext(Dispatchers.IO) {
                    when (header.kind) {
                        BiliPlaylistKind.COLLECTION,
                        BiliPlaylistKind.SERIES -> {
                            val page = loadArchiveVideos(header)
                            BiliPlaylistContentLoad(
                                videos = page.videos,
                                totalCount = page.totalCount,
                                hasMore = page.hasMore
                            )
                        }
                        BiliPlaylistKind.CREATED_FAVORITE,
                        BiliPlaylistKind.COLLECTED_FAVORITE -> {
                            val videos = loadFavoriteFolderVideos(
                                playlist = header,
                                forceRefresh = forceRefresh
                            )
                            BiliPlaylistContentLoad(videos, totalCount = videos.size)
                        }
                    }
                }

                archivePage = 1
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    header = header.copy(count = content.totalCount),
                    videos = content.videos,
                    hasMore = content.hasMore,
                    loadingMore = false
                )
                if (header.kind.hasPagedArchives()) {
                    withContext(Dispatchers.IO) {
                        archiveCacheRepo.save(content.toArchiveCache(header))
                    }
                    resolveMissingArchiveUploader(header)
                }
                NPLogger.d(
                    TAG,
                    "loadContent success: mediaId=${header.mediaId}, kind=${header.kind}, loaded=${content.videos.size}, total=${content.totalCount}"
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

    private fun resolveMissingArchiveUploader(playlist: BiliPlaylist) {
        if (
            playlist.subtitle.isNotBlank() ||
            playlist.mid <= 0L ||
            !playlist.kind.hasPagedArchives()
        ) {
            return
        }
        viewModelScope.launch {
            val uploader = try {
                withContext(Dispatchers.IO) {
                    client.getUploaderProfile(playlist.mid).name.trim()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(
                    TAG,
                    "resolve archive uploader failed: mediaId=${playlist.mediaId}, mid=${playlist.mid}",
                    error
                )
                return@launch
            }
            if (uploader.isBlank()) return@launch

            var cache: BiliArchiveContentCache? = null
            _uiState.update { current ->
                val header = current.header
                if (header?.sameIdentityAs(playlist) != true || header.subtitle.isNotBlank()) {
                    return@update current
                }
                val resolvedHeader = header.copy(subtitle = uploader)
                val resolvedVideos = applyBiliArchiveUploader(
                    videos = current.videos,
                    uploader = uploader,
                    uploaderMid = resolvedHeader.mid
                )
                cache = BiliPlaylistContentLoad(
                    videos = resolvedVideos,
                    totalCount = resolvedHeader.count.coerceAtLeast(resolvedVideos.size),
                    hasMore = current.hasMore
                ).toArchiveCache(resolvedHeader)
                current.copy(header = resolvedHeader, videos = resolvedVideos)
            }
            cache?.let { resolvedCache ->
                withContext(Dispatchers.IO) {
                    archiveCacheRepo.save(resolvedCache)
                }
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

    private fun loadCachedContent(
        playlist: BiliPlaylist,
        forceRefresh: Boolean
    ): BiliPlaylistContentLoad? {
        if (forceRefresh) return null
        return when {
            playlist.isFavoriteFolder() -> {
                favoriteCacheRepo.read(playlist.mediaId)?.let { cache ->
                    BiliPlaylistContentLoad(
                        videos = cache.videos.map { it.toVideoItem() },
                        totalCount = cache.totalCount.coerceAtLeast(cache.videos.size)
                    )
                }
            }
            playlist.kind.hasPagedArchives() -> {
                archiveCacheRepo.read(playlist.mediaId, playlist.kind.name)?.toContentLoad()
            }
            else -> null
        }
    }

    private fun BiliArchiveContentCache.toContentLoad(): BiliPlaylistContentLoad {
        return BiliPlaylistContentLoad(
            videos = videos.map { it.toVideoItem() },
            totalCount = totalCount.coerceAtLeast(videos.size),
            hasMore = hasMore
        )
    }

    private fun BiliPlaylistContentLoad.toArchiveCache(playlist: BiliPlaylist): BiliArchiveContentCache {
        return BiliArchiveContentCache(
            mediaId = playlist.mediaId,
            kind = playlist.kind.name,
            totalCount = totalCount,
            hasMore = hasMore,
            videos = videos.map { it.toCachedArchiveVideo() }
        )
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
                        archive.toVideoItem(
                            uploader = item.upperName.ifBlank { item.title },
                            uploaderMid = item.upperMid
                        )
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

    private suspend fun loadArchiveVideos(
        playlist: BiliPlaylist,
        page: Int = 1,
        existingVideos: List<BiliVideoItem> = emptyList()
    ): BiliPagedVideoPage {
        if (playlist.mid == 0L) {
            NPLogger.w(TAG, "loadArchiveVideos skipped because mid is 0: mediaId=${playlist.mediaId}")
            return BiliPagedVideoPage(
                videos = existingVideos,
                totalCount = playlist.count.coerceAtLeast(existingVideos.size),
                hasMore = false
            )
        }
        val uploader = playlist.subtitle.ifBlank { playlist.title }
        val result = when (playlist.kind) {
            BiliPlaylistKind.COLLECTION -> {
                val archivePage = client.getCollectionArchives(
                    mid = playlist.mid,
                    seasonId = playlist.mediaId,
                    page = page,
                    pageSize = BILI_ARCHIVE_PAGE_SIZE
                )
                mergeBiliPagedVideoPage(
                    existingVideos = existingVideos,
                    incomingVideos = archivePage.items.map { archive ->
                        archive.toVideoItem(uploader = uploader, uploaderMid = playlist.mid)
                    },
                    totalCount = archivePage.meta.total,
                    hasMore = archivePage.hasMore
                )
            }
            BiliPlaylistKind.SERIES -> {
                val archivePage = client.getSeriesArchives(
                    mid = playlist.mid,
                    seriesId = playlist.mediaId,
                    page = page,
                    pageSize = BILI_ARCHIVE_PAGE_SIZE
                )
                mergeBiliPagedVideoPage(
                    existingVideos = existingVideos,
                    incomingVideos = archivePage.items.map { archive ->
                        archive.toVideoItem(uploader = uploader, uploaderMid = playlist.mid)
                    },
                    totalCount = archivePage.total,
                    hasMore = archivePage.hasMore
                )
            }
            BiliPlaylistKind.CREATED_FAVORITE,
            BiliPlaylistKind.COLLECTED_FAVORITE -> {
                BiliPagedVideoPage(
                    videos = existingVideos,
                    totalCount = playlist.count.coerceAtLeast(existingVideos.size),
                    hasMore = false
                )
            }
        }
        NPLogger.d(
            TAG,
            "load archive page success: mediaId=${playlist.mediaId}, kind=${playlist.kind}, page=$page, loaded=${result.videos.size}, total=${result.totalCount}"
        )
        return result
    }

    private fun BiliPlaylistKind.hasPagedArchives(): Boolean {
        return this == BiliPlaylistKind.COLLECTION || this == BiliPlaylistKind.SERIES
    }

    private fun BiliClient.FavResourceItem.toVideoItem(): BiliVideoItem? {
        val resolvedBvid = bvid?.takeIf { it.isNotBlank() } ?: return null
        return BiliVideoItem(
            id = id,
            bvid = resolvedBvid,
            title = title,
            uploader = upperName,
            uploaderMid = upperMid,
            coverUrl = coverUrl.replaceFirst("http://", "https://"),
            durationSec = durationSec
        )
    }

    private fun BiliClient.CollectionArchiveItem.toVideoItem(
        uploader: String,
        uploaderMid: Long = 0L
    ): BiliVideoItem {
        return BiliVideoItem(
            id = aid,
            bvid = bvid,
            title = title,
            uploader = uploader,
            uploaderMid = uploaderMid,
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
            uploaderMid = uploaderMid,
            coverUrl = coverUrl,
            durationSec = durationSec
        )
    }

    private fun CachedBiliArchiveVideo.toVideoItem(): BiliVideoItem {
        return BiliVideoItem(
            id = id,
            bvid = bvid,
            title = title,
            uploader = uploader,
            uploaderMid = uploaderMid,
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
            uploaderMid = uploaderMid,
            coverUrl = coverUrl,
            durationSec = durationSec
        )
    }

    private fun BiliVideoItem.toCachedArchiveVideo(): CachedBiliArchiveVideo {
        return CachedBiliArchiveVideo(
            id = id,
            bvid = bvid,
            title = title,
            uploader = uploader,
            uploaderMid = uploaderMid,
            coverUrl = coverUrl,
            durationSec = durationSec
        )
    }

    private fun BiliPlaylist.sameIdentityAs(other: BiliPlaylist): Boolean {
        return mediaId == other.mediaId && kind == other.kind
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
