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

import moe.ouom.neriplayer.data.sync.mapping.toSongItem
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliPagedItems
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.cache.archive.BiliArchiveCacheRepository
import moe.ouom.neriplayer.platform.bilibili.cache.favorite.BiliFavoriteFolderCacheRepository
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.buildBiliPartSong
import moe.ouom.neriplayer.data.model.bilibili.collection.CollectionArchiveItem
import moe.ouom.neriplayer.data.model.bilibili.collection.FavResourceItem
import moe.ouom.neriplayer.data.model.bilibili.collection.FavResourcePage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.cache.archive.BiliArchiveContentCache
import moe.ouom.neriplayer.data.model.bilibili.cache.favorite.BiliFavoriteFolderContentCache
import moe.ouom.neriplayer.data.model.bilibili.cache.archive.CachedBiliArchiveVideo
import moe.ouom.neriplayer.data.model.bilibili.cache.favorite.CachedBiliFavoriteVideo
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.collections.mergeDistinctBy
import moe.ouom.neriplayer.ui.component.playlist.playlistLoadFailure
import moe.ouom.neriplayer.data.model.playlist.PlaylistLoadFailure
import moe.ouom.neriplayer.R as AppR

private const val TAG = "NERI-BiliPlaylistVM"
private const val BILI_RESOURCE_TYPE_VIDEO = 2
private const val BILI_RESOURCE_TYPE_COLLECTION = 21
private const val BILI_FAVORITE_LATEST_PAGE_SIZE = 20
private const val BILI_ARCHIVE_PAGE_SIZE = 12

/** Bilibili 收藏夹详情页 UI 状态 */
data class BiliPlaylistDetailUiState(
    val loading: Boolean = true,
    val error: PlaylistLoadFailure? = null,
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
    val videos = existingVideos.mergeDistinctBy(incomingVideos) { video -> video.dedupeKey() }
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

internal data class BiliFavoriteFolderRefresh(
    val videos: List<BiliVideoItem>,
    val complete: Boolean
)

/** 分页缺失时不能当作完整列表, 有旧缓存就继续展示旧缓存, 没有才展示这次拿到的部分内容 */
internal fun resolveBiliFavoriteFolderRefresh(
    fetchedVideos: List<BiliVideoItem>,
    missingPages: Int,
    cachedVideos: List<BiliVideoItem>?
): BiliFavoriteFolderRefresh {
    if (missingPages == 0) return BiliFavoriteFolderRefresh(fetchedVideos, complete = true)
    return BiliFavoriteFolderRefresh(cachedVideos ?: fetchedVideos, complete = false)
}

private fun BiliVideoItem.dedupeKey(): String = bvid.ifBlank { id.toString() }

internal fun BiliPlaylist.sameIdentityAs(other: BiliPlaylist): Boolean {
    return mediaId == other.mediaId && kind == other.kind
}

internal fun BiliPlaylist.isFavoriteFolder(): Boolean {
    return kind == BiliPlaylistKind.CREATED_FAVORITE || kind == BiliPlaylistKind.COLLECTED_FAVORITE
}

internal fun BiliPlaylistKind.hasPagedArchives(): Boolean {
    return this == BiliPlaylistKind.COLLECTION || this == BiliPlaylistKind.SERIES
}

internal fun FavResourcePage.latestPageSignature(): String {
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

internal fun FavResourceItem.toVideoItem(): BiliVideoItem? {
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

private fun CollectionArchiveItem.toVideoItem(
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

/** 收藏夹里的合集会展开成其中的视频, 合集加载失败时记作缺页而不是空合集 */
internal suspend fun mapBiliFavoriteItemsToVideos(
    items: List<FavResourceItem>,
    loadCollection: suspend (upperMid: Long, seasonId: Long) -> BiliPagedItems<CollectionArchiveItem>
): BiliPagedItems<BiliVideoItem> {
    val videos = ArrayList<BiliVideoItem>(items.size)
    var missingPages = 0
    for (item in items) {
        when (item.type) {
            BILI_RESOURCE_TYPE_VIDEO -> item.toVideoItem()?.let(videos::add)
            BILI_RESOURCE_TYPE_COLLECTION -> {
                val collection = loadFavoriteCollection(item, loadCollection)
                missingPages += collection.missingPages
                val uploader = item.upperName.ifBlank { item.title }
                collection.items.mapTo(videos) { archive ->
                    archive.toVideoItem(uploader = uploader, uploaderMid = item.upperMid)
                }
            }
        }
    }
    return BiliPagedItems(videos.distinctBy { it.dedupeKey() }, missingPages)
}

private suspend fun loadFavoriteCollection(
    item: FavResourceItem,
    loadCollection: suspend (upperMid: Long, seasonId: Long) -> BiliPagedItems<CollectionArchiveItem>
): BiliPagedItems<CollectionArchiveItem> {
    return runCatching {
        loadCollection(item.upperMid, item.id)
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "load collection videos failed: seasonId=${item.id}, upperMid=${item.upperMid}, title=${item.title}",
            error
        )
    }.getOrDefault(BiliPagedItems(emptyList(), missingPages = 1))
}

private data class BiliPlaylistContentLoad(
    val videos: List<BiliVideoItem>,
    val totalCount: Int,
    val hasMore: Boolean = false,
    val complete: Boolean = true
)

class BiliPlaylistDetailViewModel internal constructor(
    application: Application,
    private val client: BiliClient,
    private val favoriteCacheRepo: BiliFavoriteFolderCacheRepository,
    private val archiveCacheRepo: BiliArchiveCacheRepository
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        AppContainer.biliClient,
        AppContainer.biliFavoriteFolderCacheRepo,
        AppContainer.biliArchiveCacheRepo
    )

    private val _uiState = MutableStateFlow(BiliPlaylistDetailUiState())
    val uiState: StateFlow<BiliPlaylistDetailUiState> = _uiState

    private var currentPlaylist: BiliPlaylist? = null
    private var currentOwner: Any? = null
    private var currentOfflineMode: Boolean = false
    private var archivePage: Int = 1
    @Volatile private var loadGeneration: Long = 0L
    private var contentJob: Job? = null
    private var moreJob: Job? = null
    private var uploaderJob: Job? = null

    fun start(
        playlist: BiliPlaylist,
        forceRefresh: Boolean = false,
        owner: Any? = null,
        offlineMode: Boolean = false
    ) {
        val previous = _uiState.value.takeIf { it.header?.sameIdentityAs(playlist) == true }
        cancelLoads()
        val generation = loadGeneration
        currentPlaylist = playlist
        currentOwner = owner
        currentOfflineMode = offlineMode
        archivePage = 1

        _uiState.value = BiliPlaylistDetailUiState(
            loading = true,
            header = playlist,
            videos = previous?.videos.orEmpty(),
            hasMore = previous?.hasMore ?: false
        )
        NPLogger.d(
            TAG,
            "start load: mediaId=${playlist.mediaId}, kind=${playlist.kind}, forceRefresh=$forceRefresh"
        )
        loadContent(playlist, generation, forceRefresh, offlineMode)
    }

    fun retry() {
        (uiState.value.header ?: currentPlaylist)?.let {
            start(it, forceRefresh = true, owner = currentOwner, offlineMode = currentOfflineMode)
        }
    }

    fun refresh() {
        if (_uiState.value.error != null) return
        (uiState.value.header ?: currentPlaylist)?.let {
            start(it, forceRefresh = true, owner = currentOwner, offlineMode = currentOfflineMode)
        }
    }

    fun updateOfflineMode(owner: Any, offlineMode: Boolean) {
        if (currentOwner !== owner) return
        if (currentOfflineMode == offlineMode) return
        currentOfflineMode = offlineMode
        if (offlineMode) {
            (_uiState.value.header ?: currentPlaylist)?.let {
                start(it, owner = owner, offlineMode = true)
            }
        }
    }

    fun stop(owner: Any) {
        if (currentOwner !== owner) return
        stop()
    }

    fun stop() {
        cancelLoads()
        _uiState.update { it.copy(loading = false, loadingMore = false) }
    }

    private fun cancelLoads() {
        loadGeneration += 1L
        contentJob?.cancel()
        moreJob?.cancel()
        uploaderJob?.cancel()
    }

    private fun isCurrentLoad(playlist: BiliPlaylist, generation: Long): Boolean =
        generation == loadGeneration && _uiState.value.header?.sameIdentityAs(playlist) == true

    private fun blockContent(playlist: BiliPlaylist, generation: Long, message: PlaylistLoadFailure) {
        if (!isCurrentLoad(playlist, generation)) return
        cancelLoads()
        _uiState.update { it.copy(loading = false, loadingMore = false, error = message) }
    }

    fun loadMoreVideos() {
        val state = _uiState.value
        val header = state.header ?: return
        if (
            currentOfflineMode ||
            !header.kind.hasPagedArchives() ||
            header.mid <= 0L ||
            state.loading ||
            state.error != null ||
            state.loadingMore ||
            !state.hasMore
        ) {
            return
        }

        val nextPage = archivePage + 1
        val generation = loadGeneration
        _uiState.update { current ->
            if (current.header?.mediaId == header.mediaId) {
                current.copy(loadingMore = true)
            } else {
                current
            }
        }
        moreJob = viewModelScope.launch {
            try {
                val merged = withContext(Dispatchers.IO) {
                    loadArchiveVideos(
                        playlist = header,
                        page = nextPage,
                        existingVideos = state.videos,
                    )
                }
                if (!isCurrentLoad(header, generation)) return@launch

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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.e(
                    TAG,
                    "load more archives failed: mediaId=${header.mediaId}, kind=${header.kind}, page=$nextPage",
                    error
                )
                blockContent(header, generation, playlistLoadFailure(error))
            }
        }
    }


    /**
     * 获取单个视频的详细信息，包括分P列表
     * @param bvid 视频的 BV 号
     * @return 包含所有分P信息的 VideoBasicInfo 对象
     */
    suspend fun getVideoInfo(bvid: String): VideoBasicInfo {
        check(_uiState.value.error == null) { getApplication<Application>().getString(AppR.string.playlist_error_title) }
        check(!currentOfflineMode) { getApplication<Application>().getString(AppR.string.playlist_error_network) }
        val generation = loadGeneration
        val info = withContext(Dispatchers.IO) {
            NPLogger.d(TAG, "getVideoInfo start: bvid=$bvid")
            runCatching { client.getVideoBasicInfoByBvid(bvid) }
                .onSuccess {
                    NPLogger.d(TAG, "getVideoInfo success: bvid=$bvid, pages=${it.pages.size}")
                }
                .onFailure {
                    if (it !is CancellationException) NPLogger.e(TAG, "getVideoInfo failed: bvid=$bvid", it)
                }
                .getOrThrow()
        }
        if (generation != loadGeneration || _uiState.value.error != null) {
            throw CancellationException("Playlist load superseded")
        }
        return info
    }

    private fun loadContent(
        header: BiliPlaylist,
        generation: Long,
        forceRefresh: Boolean,
        offlineMode: Boolean
    ) {
        contentJob = viewModelScope.launch {
            try {
                val cachedContent = withContext(Dispatchers.IO) {
                    loadCachedContent(header, forceRefresh = forceRefresh && !offlineMode)
                }
                if (!isCurrentLoad(header, generation)) return@launch
                if (offlineMode) {
                    val state = _uiState.value
                    val offlineContent = if (header.kind.hasPagedArchives() && state.videos.isNotEmpty()) {
                        BiliPlaylistContentLoad(
                            videos = state.videos,
                            totalCount = state.header?.count?.coerceAtLeast(state.videos.size) ?: state.videos.size,
                            hasMore = state.hasMore
                        )
                    } else {
                        cachedContent
                    }
                    if (offlineContent == null) {
                        blockContent(
                            header, generation,
                            PlaylistLoadFailure.Offline
                        )
                    } else {
                        _uiState.update { current ->
                            current.copy(
                                loading = false,
                                error = null,
                                header = header.copy(count = offlineContent.totalCount),
                                videos = offlineContent.videos,
                                hasMore = offlineContent.hasMore,
                                loadingMore = false
                            )
                        }
                    }
                    return@launch
                }
                if (cachedContent != null && _uiState.value.videos.isEmpty()) {
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
                            val refresh = loadFavoriteFolderVideos(
                                playlist = header,
                                forceRefresh = forceRefresh,
                                generation = generation
                            )
                            BiliPlaylistContentLoad(
                                videos = refresh.videos,
                                totalCount = refresh.videos.size,
                                complete = refresh.complete
                            )
                        }
                    }
                }

                if (!isCurrentLoad(header, generation)) return@launch
                archivePage = 1
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = if (content.complete) {
                        null
                    } else {
                        PlaylistLoadFailure.Incomplete
                    },
                    header = header.copy(count = content.totalCount),
                    videos = content.videos,
                    hasMore = content.hasMore,
                    loadingMore = false
                )
                if (!content.complete) {
                    blockContent(
                        header, generation,
                        PlaylistLoadFailure.Incomplete
                    )
                    return@launch
                }
                if (header.kind.hasPagedArchives()) {
                    withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentLoad(header, generation)) return@withContext
                        archiveCacheRepo.save(content.toArchiveCache(header))
                    }
                    if (isCurrentLoad(header, generation)) resolveMissingArchiveUploader(header, generation)
                }
                NPLogger.d(
                    TAG,
                    "loadContent success: mediaId=${header.mediaId}, kind=${header.kind}, loaded=${content.videos.size}, total=${content.totalCount}"
                )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "loadContent failed: mediaId=${header.mediaId}",
                    e
                )
                blockContent(header, generation, playlistLoadFailure(e))
            }
        }
    }

    private fun resolveMissingArchiveUploader(playlist: BiliPlaylist, generation: Long) {
        if (
            currentOfflineMode ||
            playlist.subtitle.isNotBlank() ||
            playlist.mid <= 0L ||
            !playlist.kind.hasPagedArchives()
        ) {
            return
        }
        uploaderJob = viewModelScope.launch {
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
            if (!isCurrentLoad(playlist, generation)) return@launch

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
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentLoad(playlist, generation)) return@withContext
                    archiveCacheRepo.save(resolvedCache)
                }
            }
        }
    }

    private suspend fun loadFavoriteFolderVideos(
        playlist: BiliPlaylist,
        forceRefresh: Boolean,
        generation: Long
    ): BiliFavoriteFolderRefresh {
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
            return BiliFavoriteFolderRefresh(cached.videos.map { it.toVideoItem() }, complete = true)
        }

        val items = client.getAllFavFolderItemsResult(playlist.mediaId, latestPage)
        val videos = mapFavoriteItemsToVideos(items.items)
        val missingPages = items.missingPages + videos.missingPages
        val refresh = resolveBiliFavoriteFolderRefresh(
            fetchedVideos = videos.items,
            missingPages = missingPages,
            cachedVideos = cached?.videos?.map { it.toVideoItem() }
        )
        if (!refresh.complete) {
            NPLogger.w(
                TAG,
                "loadFavoriteFolderVideos incomplete, keep previous cache: mediaId=${playlist.mediaId}, missingPages=$missingPages, fetched=${videos.items.size}, hasCache=${cached != null}"
            )
            return refresh
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentLoad(playlist, generation)) throw CancellationException("Playlist load superseded")
        favoriteCacheRepo.save(
            BiliFavoriteFolderContentCache(
                mediaId = playlist.mediaId,
                latestPageSignature = latestSignature,
                totalCount = latestPage.info.count,
                videos = videos.items.map { it.toCachedVideo() }
            )
        )
        NPLogger.d(
            TAG,
            "loadFavoriteFolderVideos refreshed: mediaId=${playlist.mediaId}, items=${items.items.size}, videos=${videos.items.size}"
        )
        return refresh
    }

    private fun loadCachedContent(
        playlist: BiliPlaylist,
        forceRefresh: Boolean
    ): BiliPlaylistContentLoad? {
        if (forceRefresh) return null
        return when {
            playlist.isFavoriteFolder() -> {
                favoriteCacheRepo.read(playlist.mediaId)?.takeIf { it.mediaId == playlist.mediaId }?.let { cache ->
                    BiliPlaylistContentLoad(
                        videos = cache.videos.map { it.toVideoItem() },
                        totalCount = cache.totalCount.coerceAtLeast(cache.videos.size)
                    )
                }
            }
            playlist.kind.hasPagedArchives() -> {
                archiveCacheRepo.read(playlist.mediaId, playlist.kind.name)
                    ?.takeIf { it.mediaId == playlist.mediaId && it.kind == playlist.kind.name }
                    ?.toContentLoad()
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

    private suspend fun mapFavoriteItemsToVideos(items: List<FavResourceItem>): BiliPagedItems<BiliVideoItem> {
        return mapBiliFavoriteItemsToVideos(items) { upperMid, seasonId ->
            client.getAllCollectionArchivesResult(mid = upperMid, seasonId = seasonId)
        }
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

    /**
     * 将 Bilibili 视频的分P转换为通用的 SongItem
     * @param page 分P信息
     * @param basicInfo 视频的基本信息
     * @param coverUrl 视频封面
     * @return 转换后的 SongItem
     */
    fun toSongItem(page: VideoPage, basicInfo: VideoBasicInfo, coverUrl: String): SongItem {
        return buildBiliPartSong(page, basicInfo, coverUrl)
    }
}
