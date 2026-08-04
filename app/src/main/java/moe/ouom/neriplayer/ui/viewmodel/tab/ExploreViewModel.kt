package moe.ouom.neriplayer.ui.viewmodel.tab

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
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/ExploreViewModel
 * Created: 2025/8/11
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.buildBiliPartSong
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicSearchResult
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicSearchResultType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.PlayerManager.biliClient
import moe.ouom.neriplayer.core.player.PlayerManager.neteaseClient
import moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepository
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.data.platform.youtube.youtubeMusicThumbnailUrl
import moe.ouom.neriplayer.data.platform.youtube.YouTubeFeatureGate
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.search.searchValues
import moe.ouom.neriplayer.util.search.SearchTextMatcher
import org.json.JSONObject

private const val TAG = "NERI-ExploreVM"
private const val NETEASE_SEARCH_PAGE_SIZE = 30
private const val YOUTUBE_MUSIC_SEARCH_LIMIT = 30
private const val BILI_RESOURCE_TYPE_COLLECTION = 21

/**
 * Tag key to Chinese API category mapping
 */
val TAG_TO_API_CATEGORY = mapOf(
    "tag_all" to "全部",
    "tag_pop" to "流行",
    "tag_soundtrack" to "影视原声",
    "tag_chinese" to "华语",
    "tag_nostalgia" to "怀旧",
    "tag_rock" to "摇滚",
    "tag_acg" to "ACG",
    "tag_western" to "欧美",
    "tag_fresh" to "清新",
    "tag_night" to "夜晚",
    "tag_children" to "儿童",
    "tag_folk" to "民谣",
    "tag_japanese" to "日语",
    "tag_romantic" to "浪漫",
    "tag_study" to "学习",
    "tag_korean" to "韩语",
    "tag_work" to "工作",
    "tag_electronic" to "电子",
    "tag_cantonese" to "粤语",
    "tag_dance" to "舞曲",
    "tag_sad" to "伤感",
    "tag_game" to "游戏",
    "tag_afternoon_tea" to "下午茶",
    "tag_healing" to "治愈",
    "tag_rap" to "说唱",
    "tag_light_music" to "轻音乐"
)

/** 定义搜索源 */
enum class SearchSource {
    YOUTUBE_MUSIC,
    NETEASE,
    BILIBILI,
    LINK_RECOGNITION
}

enum class NeteaseExploreSearchType(val apiType: Int) {
    SONG(apiType = 1),
    PLAYLIST(apiType = 1000),
    ARTIST(apiType = 100)
}

enum class YouTubeExploreSearchType(
    val filter: YouTubeMusicSearchFilter?
) {
    SONG(YouTubeMusicSearchFilter.Song),
    VIDEO(YouTubeMusicSearchFilter.Video),
    CREATOR(null)
}

data class NeteaseSearchArtistResult(
    val artist: NeteaseArtistSummary,
    val picUrl: String?,
    val musicSize: Int,
    val albumSize: Int
)

sealed class ExploreSearchResult {
    abstract val stableKey: String

    data class Song(val song: SongItem) : ExploreSearchResult() {
        override val stableKey: String = listOfNotNull(
            "song",
            song.channelId,
            song.audioId,
            song.subAudioId,
            song.mediaUri,
            song.id.toString()
        ).joinToString("|")
    }

    data class Playlist(val playlist: PlaylistSummary) : ExploreSearchResult() {
        override val stableKey: String = "netease|playlist|${playlist.id}"
    }

    data class YouTubePlaylist(val playlist: YouTubeMusicPlaylist) : ExploreSearchResult() {
        override val stableKey: String = "youtubeMusic|playlist|${playlist.playlistId.ifBlank { playlist.browseId }}"
    }

    data class BilibiliPlaylist(val playlist: BiliPlaylist) : ExploreSearchResult() {
        override val stableKey: String =
            "bilibili|playlist|${playlist.kind}|${playlist.mediaId}|${playlist.mid}"
    }

    data class Artist(val result: NeteaseSearchArtistResult) : ExploreSearchResult() {
        override val stableKey: String = "netease|artist|${result.artist.id}"
    }

    data class YouTubeCreator(val creator: YouTubeMusicCreatorSummary) : ExploreSearchResult() {
        override val stableKey: String = "youtubeMusic|creator|${creator.browseId}"
    }

    data class Notice(
        val title: String,
        val message: String
    ) : ExploreSearchResult() {
        override val stableKey: String = "notice|$title|$message"
    }
}

data class ExploreUiState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val playlists: List<PlaylistSummary> = emptyList(),
    val selectedTag: String = "tag_all",  // String resource key
    val searching: Boolean = false,
    val searchError: String? = null,
    val searchResults: List<SongItem> = emptyList(),
    val searchItems: List<ExploreSearchResult> = emptyList(),
    val searchHasMore: Boolean = false,
    val searchLoadingMore: Boolean = false,
    val searchLoadMoreError: String? = null,
    val searchPage: Int = 0,
    val searchKeyword: String = "",
    val searchDisplayQuery: String = "",
    val selectedSearchSource: SearchSource = SearchSource.NETEASE,
    val selectedNeteaseSearchType: NeteaseExploreSearchType = NeteaseExploreSearchType.SONG,
    val selectedYouTubeMusicSearchType: YouTubeExploreSearchType = YouTubeExploreSearchType.SONG,
    val isNeteaseLoggedIn: Boolean = false,
    val ytMusicPlaylists: List<YouTubeMusicPlaylist> = emptyList(),
    val ytMusicPlaylistsLoading: Boolean = false,
    val ytMusicPlaylistsError: String? = null
)

internal fun ExploreUiState.withYouTubeDisabled(): ExploreUiState {
    val youtubeWasSelected = selectedSearchSource == SearchSource.YOUTUBE_MUSIC
    return copy(
        selectedSearchSource = if (youtubeWasSelected) SearchSource.NETEASE else selectedSearchSource,
        searching = if (youtubeWasSelected) false else searching,
        searchResults = if (youtubeWasSelected) emptyList() else searchResults,
        searchItems = if (youtubeWasSelected) emptyList() else searchItems,
        searchHasMore = if (youtubeWasSelected) false else searchHasMore,
        searchLoadingMore = if (youtubeWasSelected) false else searchLoadingMore,
        searchLoadMoreError = if (youtubeWasSelected) null else searchLoadMoreError,
        searchPage = if (youtubeWasSelected) 0 else searchPage,
        searchKeyword = if (youtubeWasSelected) "" else searchKeyword,
        searchDisplayQuery = if (youtubeWasSelected) "" else searchDisplayQuery,
        searchError = if (youtubeWasSelected) null else searchError,
        ytMusicPlaylists = emptyList(),
        ytMusicPlaylistsLoading = false,
        ytMusicPlaylistsError = null
    )
}

internal fun rankExploreSongSearchResults(
    query: String,
    songs: List<SongItem>
): List<SongItem> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank() || songs.size <= 1) return songs

    val scoredSongs = songs.mapIndexed { index, song ->
        RankedSongSearchResult(
            song = song,
            index = index,
            score = SearchTextMatcher.score(normalizedQuery, song.searchTokens())
        )
    }
    val matchedSongs = scoredSongs.filter { it.score != null }
    if (matchedSongs.isEmpty()) return songs

    return matchedSongs
        .sortedWith(
            compareBy<RankedSongSearchResult> { it.score ?: Int.MAX_VALUE }
                .thenBy { it.index }
        )
        .map { it.song } +
        scoredSongs
            .filter { it.score == null }
            .map { it.song }
}

private data class RankedSongSearchResult(
    val song: SongItem,
    val index: Int,
    val score: Int?
)

private fun SongItem.searchTokens(): List<Any?> {
    return searchValues()
}

internal fun mergeExploreSearchResults(
    existing: List<ExploreSearchResult>,
    incoming: List<ExploreSearchResult>
): List<ExploreSearchResult> {
    if (existing.isEmpty()) return incoming.distinctBy { it.stableKey }
    if (incoming.isEmpty()) return existing

    val seen = existing.mapTo(mutableSetOf()) { it.stableKey }
    val merged = ArrayList<ExploreSearchResult>(existing.size + incoming.size)
    merged += existing
    incoming.forEach { item ->
        if (seen.add(item.stableKey)) {
            merged += item
        }
    }
    return merged
}

internal fun searchSongItems(items: List<ExploreSearchResult>): List<SongItem> {
    return items.mapNotNull { (it as? ExploreSearchResult.Song)?.song }
}

internal fun hasMoreExploreSearchResults(
    totalCount: Int?,
    loadedCount: Int,
    pageItemCount: Int,
    pageSize: Int
): Boolean {
    return if (totalCount != null) {
        loadedCount < totalCount
    } else {
        pageItemCount >= pageSize
    }
}

internal fun shouldLoadExploreSearchMore(
    resultCount: Int,
    lastVisibleItemIndex: Int?,
    hasMore: Boolean,
    searching: Boolean,
    loadingMore: Boolean,
    loadMoreFailed: Boolean = false,
    prefetchDistance: Int = 6
): Boolean {
    if (!hasMore || searching || loadingMore || loadMoreFailed || resultCount <= 0) return false
    val lastVisible = lastVisibleItemIndex ?: return false
    return lastVisible >= resultCount - prefetchDistance
}

private data class ExploreSearchFetchResult(
    val items: List<ExploreSearchResult>,
    val page: Int,
    val hasMore: Boolean
) {
    val songs: List<SongItem> = searchSongItems(items)
}

class ExploreViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val neteaseRepo = NeteaseCookieRepository(application)
    private var highQualityLoadJob: Job? = null
    private var searchJob: Job? = null
    private var searchMoreJob: Job? = null
    private var ytMusicPlaylistsJob: Job? = null
    private var ytMusicPlaylistsPending = false
    private var searchRequestVersion = 0L
    private var youtubeEnabled = YouTubeFeatureGate.isEnabled()

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState

    init {
        viewModelScope.launch {
            neteaseRepo.authHealthFlow.collect { health ->
                val isLoggedIn = health.state != SavedCookieAuthState.Missing
                _uiState.value = _uiState.value.copy(isNeteaseLoggedIn = isLoggedIn)
            }
        }
        viewModelScope.launch {
            neteaseRepo.cookieFlow.collect {
                NPLogger.d(TAG, "cookieFlow updated, reload high quality playlists tag=${_uiState.value.selectedTag}")
                loadHighQuality()
            }
        }
        viewModelScope.launch {
            AppContainer.settingsRepo.youtubeEnabledFlow.collect { enabled ->
                youtubeEnabled = enabled
                if (!enabled) {
                    disableYouTubeSource()
                }
            }
        }
    }

    /** 设置当前搜索源 */
    fun setSearchSource(source: SearchSource) {
        if (source == SearchSource.YOUTUBE_MUSIC && !youtubeEnabled) return
        if (source == _uiState.value.selectedSearchSource) return
        NPLogger.d(TAG, "setSearchSource: ${_uiState.value.selectedSearchSource} -> $source")
        searchJob?.cancel()
        searchMoreJob?.cancel()
        invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            selectedSearchSource = source,
            searching = false,
            searchResults = emptyList(), // 切换源时清空结果
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = "",
            searchDisplayQuery = "",
            searchError = null
        )
    }

    fun setNeteaseSearchType(type: NeteaseExploreSearchType) {
        if (type == _uiState.value.selectedNeteaseSearchType) return
        NPLogger.d(TAG, "setNeteaseSearchType: ${_uiState.value.selectedNeteaseSearchType} -> $type")
        searchJob?.cancel()
        searchMoreJob?.cancel()
        invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            selectedNeteaseSearchType = type,
            searching = false,
            searchError = null,
            searchResults = emptyList(),
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = "",
            searchDisplayQuery = ""
        )
    }

    fun setYouTubeMusicSearchType(type: YouTubeExploreSearchType) {
        if (type == _uiState.value.selectedYouTubeMusicSearchType) return
        NPLogger.d(
            TAG,
            "setYouTubeMusicSearchType: ${_uiState.value.selectedYouTubeMusicSearchType} -> $type"
        )
        searchJob?.cancel()
        searchMoreJob?.cancel()
        invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            selectedYouTubeMusicSearchType = type,
            searching = false,
            searchError = null,
            searchResults = emptyList(),
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = "",
            searchDisplayQuery = ""
        )
    }

    /** 统一搜索入口 */
    fun search(keyword: String, displayQuery: String = keyword) {
        val apiKeyword = keyword.trim()
        val matchQuery = displayQuery.trim().ifBlank { apiKeyword }
        if (apiKeyword.isBlank()) {
            NPLogger.d(TAG, "search cleared because keyword is blank")
            searchJob?.cancel()
            searchMoreJob?.cancel()
            invalidateSearchRequest()
            _uiState.value = _uiState.value.copy(
                searching = false,
                searchResults = emptyList(),
                searchItems = emptyList(),
                searchHasMore = false,
                searchLoadingMore = false,
                searchLoadMoreError = null,
                searchPage = 0,
                searchKeyword = "",
                searchDisplayQuery = "",
                searchError = null
            )
            return
        }
        val source = _uiState.value.selectedSearchSource
        val requestVersion = beginSearchRequest(apiKeyword, matchQuery)
        NPLogger.d(
            TAG,
            "search start: source=$source, request=$requestVersion, keyword=$apiKeyword, display=$matchQuery"
        )
        when (source) {
            SearchSource.NETEASE -> searchNetease(apiKeyword, matchQuery, requestVersion)
            SearchSource.BILIBILI -> searchBilibili(apiKeyword, matchQuery, requestVersion)
            SearchSource.YOUTUBE_MUSIC -> searchYouTubeMusic(apiKeyword, matchQuery, requestVersion)
            SearchSource.LINK_RECOGNITION -> searchRecognizedLink(apiKeyword, requestVersion)
        }
    }

    fun loadMoreSearchResults() {
        val state = _uiState.value
        if (
            state.searchKeyword.isBlank() ||
            !state.searchHasMore ||
            state.searching ||
            state.searchLoadingMore ||
            searchMoreJob?.isActive == true
        ) {
            return
        }

        val source = state.selectedSearchSource
        val neteaseType = state.selectedNeteaseSearchType
        val keyword = state.searchKeyword
        val matchQuery = state.searchDisplayQuery.ifBlank { keyword }
        val nextPage = state.searchPage + 1
        val requestVersion = searchRequestVersion
        _uiState.value = state.copy(searchLoadingMore = true, searchLoadMoreError = null)
        NPLogger.d(
            TAG,
            "search load more: source=$source, request=$requestVersion, keyword=$keyword, page=$nextPage, type=$neteaseType"
        )
        searchMoreJob = viewModelScope.launch {
            try {
                val result = when (source) {
                    SearchSource.NETEASE -> fetchNeteaseSearchPage(
                        keyword = keyword,
                        matchQuery = matchQuery,
                        page = nextPage,
                        type = neteaseType
                    )
                    SearchSource.BILIBILI -> fetchBilibiliSearchPage(
                        keyword = keyword,
                        matchQuery = matchQuery,
                        page = nextPage
                    )
                    SearchSource.YOUTUBE_MUSIC,
                    SearchSource.LINK_RECOGNITION -> return@launch
                }
                updateSearchStateIfCurrent(requestVersion, source) {
                    val merged = mergeExploreSearchResults(it.searchItems, result.items)
                    it.copy(
                        searching = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchError = null,
                        searchItems = merged,
                        searchResults = searchSongItems(merged),
                        searchPage = result.page,
                        searchHasMore = result.hasMore
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search load more failed: source=$source, request=$requestVersion, keyword=$keyword, page=$nextPage",
                    e
                )
                updateSearchStateIfCurrent(requestVersion, source) {
                    it.copy(
                        searchLoadingMore = false,
                        searchLoadMoreError = searchErrorMessage(source, e),
                        searchHasMore = true
                    )
                }
            }
        }
    }

    /** 搜索 Bilibili 视频 */
    private fun searchBilibili(keyword: String, matchQuery: String, requestVersion: Long) {
        searchJob = viewModelScope.launch {
            try {
                val result = fetchBilibiliSearchPage(keyword, matchQuery, page = 1)
                NPLogger.d(
                    TAG,
                    "search Bilibili success: request=$requestVersion, keyword=$keyword, count=${result.items.size}, page=${result.page}, hasMore=${result.hasMore}"
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.BILIBILI) {
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = result.songs,
                        searchItems = result.items,
                        searchPage = result.page,
                        searchHasMore = result.hasMore
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search Bilibili failed: request=$requestVersion, keyword=$keyword",
                    e
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.BILIBILI) {
                    it.copy(
                        searching = false,
                        searchError = app.getString(
                            R.string.error_bilibili_search,
                            e.message ?: app.getString(R.string.github_sync_failed_message)
                        ),
                        searchResults = emptyList(),
                        searchItems = emptyList(),
                        searchHasMore = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchPage = 0
                    )
                }
            }
        }
    }

    private suspend fun fetchBilibiliSearchPage(
        keyword: String,
        matchQuery: String,
        page: Int
    ): ExploreSearchFetchResult {
        val searchPage = withContext(Dispatchers.IO) {
            biliClient.searchVideos(keyword = keyword, page = page)
        }
        val songs = rankExploreSongSearchResults(
            query = matchQuery,
            songs = searchPage.items.map { it.toSongItem() }
        )
        return ExploreSearchFetchResult(
            items = songs.map { ExploreSearchResult.Song(it) },
            page = searchPage.page,
            hasMore = searchPage.page < searchPage.numPages && searchPage.items.isNotEmpty()
        )
    }

    private fun beginSearchRequest(keyword: String, displayQuery: String): Long {
        searchJob?.cancel()
        searchMoreJob?.cancel()
        val requestVersion = invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            searching = true,
            searchError = null,
            searchResults = emptyList(),
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = keyword,
            searchDisplayQuery = displayQuery
        )
        return requestVersion
    }

    private fun invalidateSearchRequest(): Long {
        searchRequestVersion += 1
        return searchRequestVersion
    }

    private fun isSearchRequestCurrent(requestVersion: Long, source: SearchSource): Boolean {
        val currentState = _uiState.value
        return searchRequestVersion == requestVersion && currentState.selectedSearchSource == source
    }

    private inline fun updateSearchStateIfCurrent(
        requestVersion: Long,
        source: SearchSource,
        transform: (ExploreUiState) -> ExploreUiState
    ) {
        if (!isSearchRequestCurrent(requestVersion, source)) {
            val currentState = _uiState.value
            NPLogger.d(
                TAG,
                "drop stale search update: source=$source, request=$requestVersion, currentRequest=$searchRequestVersion, currentSource=${currentState.selectedSearchSource}"
            )
            return
        }
        _uiState.value = transform(_uiState.value)
    }

    fun toggleExpanded() {
        _uiState.value = _uiState.value.copy(expanded = !_uiState.value.expanded)
    }

    fun loadHighQuality(cat: String? = null) {
        val currentState = _uiState.value
        val realCat = cat ?: currentState.selectedTag
        val previousTag = currentState.selectedTag
        val previousPlaylists = currentState.playlists

        highQualityLoadJob?.cancel()
        _uiState.value = currentState.copy(
            loading = true,
            error = null,
            selectedTag = realCat
        )
        NPLogger.d(
            TAG,
            "loadHighQuality start: tag=$realCat, apiCategory=${TAG_TO_API_CATEGORY[realCat] ?: realCat}, previousCount=${previousPlaylists.size}"
        )
        highQualityLoadJob = viewModelScope.launch {
            try {
                // Convert tag key to Chinese API category
                val apiCategory = TAG_TO_API_CATEGORY[realCat] ?: realCat
                val raw = withContext(Dispatchers.IO) {
                    neteaseClient.getHighQualityPlaylists(apiCategory, 50, 0L)
                }
                val mapped = parsePlaylists(raw)
                NPLogger.d(TAG, "loadHighQuality success: tag=$realCat, count=${mapped.size}")

                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = null,
                    playlists = mapped,
                    selectedTag = realCat
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val shouldRestorePreviousContent = previousPlaylists.isNotEmpty() && realCat != previousTag
                NPLogger.e(
                    TAG,
                    "loadHighQuality failed: tag=$realCat, restorePrevious=$shouldRestorePreviousContent",
                    e
                )
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = app.getString(
                        R.string.error_load_playlist,
                        e.message ?: app.getString(R.string.github_sync_failed_message)
                    ),
                    playlists = if (shouldRestorePreviousContent) previousPlaylists else emptyList(),
                    selectedTag = if (shouldRestorePreviousContent) previousTag else realCat
                )
            }
        }
    }

    private fun parsePlaylists(raw: String): List<PlaylistSummary> {
        val result = mutableListOf<PlaylistSummary>()
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        if (code != 200) {
            NPLogger.w(TAG, "parsePlaylists unexpected code=$code")
            return emptyList()
        }
        val arr = root.optJSONArray("playlists") ?: return emptyList()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            result.add(PlaylistSummary(
                id = obj.optLong("id"),
                name = obj.optString("name"),
                picUrl = obj.optString("coverImgUrl").replace("http://", "https://"),
                playCount = obj.optLong("playCount"),
                trackCount = obj.optInt("trackCount")
            ))
        }
        return result
    }

    /** 搜索网易云歌曲 */
    private fun searchNetease(keyword: String, matchQuery: String, requestVersion: Long) {
        if (neteaseRepo.getAuthHealthOnce().state == SavedCookieAuthState.Missing) {
            updateSearchStateIfCurrent(requestVersion, SearchSource.NETEASE) {
                it.copy(
                    searching = false,
                    searchError = app.getString(R.string.netease_login_required_search),
                    searchResults = emptyList(),
                    searchItems = emptyList(),
                    searchHasMore = false,
                    searchLoadingMore = false,
                    searchLoadMoreError = null,
                    searchPage = 0
                )
            }
            return
        }
        val type = _uiState.value.selectedNeteaseSearchType
        searchJob = viewModelScope.launch {
            try {
                val result = fetchNeteaseSearchPage(keyword, matchQuery, page = 1, type = type)
                NPLogger.d(
                    TAG,
                    "search Netease success: request=$requestVersion, keyword=$keyword, type=$type, count=${result.items.size}, hasMore=${result.hasMore}"
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.NETEASE) {
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = result.songs,
                        searchItems = result.items,
                        searchPage = result.page,
                        searchHasMore = result.hasMore
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search Netease failed: request=$requestVersion, keyword=$keyword",
                    e
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.NETEASE) {
                    it.copy(
                        searching = false,
                        searchError = app.getString(
                            R.string.error_netease_search,
                            e.message ?: app.getString(R.string.github_sync_failed_message)
                        ),
                        searchResults = emptyList(),
                        searchItems = emptyList(),
                        searchHasMore = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchPage = 0
                    )
                }
            }
        }
    }

    private fun searchRecognizedLink(input: String, requestVersion: Long) {
        searchJob = viewModelScope.launch {
            try {
                val target = recognizeExploreLink(input)
                if (target == null) {
                    updateSearchStateIfCurrent(requestVersion, SearchSource.LINK_RECOGNITION) {
                        it.copy(
                            searching = false,
                            searchError = app.getString(R.string.explore_link_invalid),
                            searchResults = emptyList(),
                            searchItems = emptyList(),
                            searchHasMore = false,
                            searchPage = 0
                        )
                    }
                    return@launch
                }

                val item = withContext(Dispatchers.IO) {
                    resolveExploreLinkTarget(target)
                }
                NPLogger.d(
                    TAG,
                    "link recognized: request=$requestVersion, target=$target, item=${item.stableKey}"
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.LINK_RECOGNITION) {
                    val items = listOf(item)
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = searchSongItems(items),
                        searchItems = items,
                        searchPage = 1,
                        searchHasMore = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(TAG, "link recognition failed: request=$requestVersion, input=$input", e)
                updateSearchStateIfCurrent(requestVersion, SearchSource.LINK_RECOGNITION) {
                    it.copy(
                        searching = false,
                        searchError = app.getString(
                            R.string.error_link_recognition,
                            e.message ?: app.getString(R.string.github_sync_failed_message)
                        ),
                        searchResults = emptyList(),
                        searchItems = emptyList(),
                        searchHasMore = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchPage = 0
                    )
                }
            }
        }
    }

    private suspend fun resolveExploreLinkTarget(target: ExploreLinkTarget): ExploreSearchResult {
        return when (target) {
            is ExploreLinkTarget.NeteaseSong -> ExploreSearchResult.Song(
                fetchLinkedNeteaseSong(target.id)
            )
            is ExploreLinkTarget.NeteasePlaylist -> ExploreSearchResult.Playlist(
                fetchLinkedNeteasePlaylist(target.id)
            )
            is ExploreLinkTarget.NeteaseArtist -> ExploreSearchResult.Artist(
                fetchLinkedNeteaseArtist(target.id)
            )
            is ExploreLinkTarget.NeteaseShortLink -> resolveExploreLinkTarget(
                resolveNeteaseShortLink(target.url)
            )
            is ExploreLinkTarget.BiliVideo -> fetchLinkedBiliVideo(target)
            is ExploreLinkTarget.BiliFavoriteFolder -> ExploreSearchResult.BilibiliPlaylist(
                fetchLinkedBiliFavoriteFolder(target.mediaId)
            )
            is ExploreLinkTarget.BiliFavoriteFolderByOwner -> ExploreSearchResult.BilibiliPlaylist(
                fetchLinkedBiliFavoriteFolder(target.ownerMid, target.folderId)
            )
            is ExploreLinkTarget.BiliCollection -> ExploreSearchResult.BilibiliPlaylist(
                fetchLinkedBiliCollection(target.ownerMid, target.seasonId)
            )
            is ExploreLinkTarget.BiliShortLink -> resolveExploreLinkTarget(
                resolveBiliShortLink(target.url)
            )
            is ExploreLinkTarget.YouTubeVideo -> ExploreSearchResult.Song(
                fetchLinkedYouTubeVideo(target)
            )
            is ExploreLinkTarget.YouTubePlaylist -> ExploreSearchResult.YouTubePlaylist(
                fetchLinkedYouTubePlaylist(target.playlistId)
            )
            is ExploreLinkTarget.Unsupported -> ExploreSearchResult.Notice(
                title = app.getString(R.string.explore_link_unsupported_title),
                message = app.getString(
                    R.string.explore_link_unsupported_message,
                    target.platform,
                    target.type
                )
            )
        }
    }

    private suspend fun resolveBiliShortLink(url: String): ExploreLinkTarget {
        val finalUrl = expandExploreRedirectUrl(url, AppContainer.sharedOkHttpClient)
        return recognizeExploreLink(finalUrl)
            ?.takeIf { it !is ExploreLinkTarget.BiliShortLink }
            ?: error(app.getString(R.string.explore_link_invalid))
    }

    private suspend fun resolveNeteaseShortLink(url: String): ExploreLinkTarget {
        val finalUrl = expandExploreRedirectUrl(url, AppContainer.sharedOkHttpClient)
        return recognizeExploreLink(finalUrl)
            ?.takeIf { it !is ExploreLinkTarget.NeteaseShortLink }
            ?: error(app.getString(R.string.explore_link_invalid))
    }

    private fun fetchLinkedNeteaseSong(songId: Long): SongItem {
        val raw = neteaseClient.getSongDetail(listOf(songId))
        return parseNeteaseSongDetail(raw) ?: SongItem(
            id = songId,
            name = app.getString(R.string.explore_link_netease_song_fallback, songId),
            artist = "",
            album = "",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = "https://music.163.com/#/song?id=$songId",
            channelId = "netease",
            audioId = songId.toString()
        )
    }

    private fun fetchLinkedNeteasePlaylist(playlistId: Long): PlaylistSummary {
        return runCatching {
            parseLinkedNeteasePlaylist(
                raw = neteaseClient.getPlaylistDetail(playlistId),
                fallbackId = playlistId
            )
        }.getOrElse {
            PlaylistSummary(
                id = playlistId,
                name = app.getString(R.string.explore_link_netease_playlist_fallback, playlistId),
                picUrl = "",
                playCount = 0L,
                trackCount = 0
            )
        }
    }

    private fun fetchLinkedNeteaseArtist(artistId: Long): NeteaseSearchArtistResult {
        return runCatching {
            parseLinkedNeteaseArtist(
                raw = neteaseClient.getArtistDetail(artistId),
                fallbackId = artistId
            )
        }.getOrElse {
            NeteaseSearchArtistResult(
                artist = NeteaseArtistSummary(
                    id = artistId,
                    name = app.getString(R.string.explore_link_netease_artist_fallback, artistId)
                ),
                picUrl = null,
                musicSize = 0,
                albumSize = 0
            )
        }
    }

    private suspend fun fetchLinkedBiliVideo(
        target: ExploreLinkTarget.BiliVideo
    ): ExploreSearchResult {
        val info = target.bvid
            ?.let { biliClient.getVideoBasicInfoByBvid(it) }
            ?: target.avid
                ?.let { biliClient.getVideoBasicInfoByAvid(it) }
            ?: error(app.getString(R.string.explore_link_invalid))
        val collectionTarget = info.toExploreLinkCollectionTarget(target)
        if (collectionTarget != null) {
            return ExploreSearchResult.BilibiliPlaylist(
                fetchLinkedBiliCollection(
                    ownerMid = collectionTarget.ownerMid,
                    seasonId = collectionTarget.seasonId
                )
            )
        }
        return ExploreSearchResult.Song(info.toExploreLinkSong(target))
    }

    private suspend fun fetchLinkedBiliFavoriteFolder(mediaId: Long): BiliPlaylist {
        val folder = biliClient.getFavFolderInfo(mediaId)
        return folder.toExploreBiliPlaylist(BiliPlaylistKind.CREATED_FAVORITE)
    }

    private suspend fun fetchLinkedBiliFavoriteFolder(
        ownerMid: Long,
        folderId: Long
    ): BiliPlaylist {
        val created = biliClient.getUserCreatedFavFolders(ownerMid)
        created.firstOrNull { it.fid == folderId || it.mediaId == folderId }?.let { folder ->
            return hydrateLinkedBiliFolder(folder, BiliPlaylistKind.CREATED_FAVORITE)
        }

        val collected = runCatching {
            biliClient.getUserCollectedFavFolders(ownerMid)
        }.getOrDefault(emptyList())
        collected.firstOrNull { it.fid == folderId || it.mediaId == folderId }?.let { folder ->
            return hydrateLinkedBiliFolder(folder, BiliPlaylistKind.COLLECTED_FAVORITE)
        }
        error(app.getString(R.string.explore_link_invalid))
    }

    private suspend fun hydrateLinkedBiliFolder(
        folder: BiliClient.FavFolder,
        fallbackKind: BiliPlaylistKind
    ): BiliPlaylist {
        if (folder.itemType == BILI_RESOURCE_TYPE_COLLECTION) {
            return fetchLinkedBiliCollection(folder.mid, folder.mediaId)
        }
        val detail = runCatching {
            biliClient.getFavFolderInfo(folder.mediaId)
        }.getOrDefault(folder)
        return detail.toExploreBiliPlaylist(fallbackKind)
    }

    private suspend fun fetchLinkedBiliCollection(
        ownerMid: Long,
        seasonId: Long
    ): BiliPlaylist {
        val page = biliClient.getCollectionArchives(
            mid = ownerMid,
            seasonId = seasonId,
            page = 1
        )
        val meta = page.meta
        return BiliPlaylist(
            mediaId = meta.seasonId.takeIf { it > 0L } ?: seasonId,
            fid = 0L,
            mid = meta.mid.takeIf { it > 0L } ?: ownerMid,
            title = meta.title.ifBlank {
                app.getString(R.string.explore_link_bili_playlist_fallback, seasonId)
            },
            count = meta.total,
            coverUrl = meta.coverUrl,
            kind = BiliPlaylistKind.COLLECTION
        )
    }

    private fun BiliClient.FavFolder.toExploreBiliPlaylist(
        fallbackKind: BiliPlaylistKind
    ): BiliPlaylist {
        val resolvedKind = if (itemType == BILI_RESOURCE_TYPE_COLLECTION) {
            BiliPlaylistKind.COLLECTION
        } else {
            fallbackKind
        }
        return BiliPlaylist(
            mediaId = mediaId,
            fid = fid,
            mid = mid,
            title = title.ifBlank {
                app.getString(R.string.explore_link_bili_playlist_fallback, mediaId)
            },
            count = count,
            coverUrl = coverUrl.replaceFirst("http://", "https://"),
            kind = resolvedKind,
            subtitle = upperName
        )
    }

    private suspend fun fetchLinkedYouTubeVideo(target: ExploreLinkTarget.YouTubeVideo): SongItem {
        val matched = runCatching {
            AppContainer.youtubeMusicClient.search(target.videoId, limit = 5)
                .firstOrNull { it.videoId == target.videoId }
        }.getOrNull()
        if (matched != null) {
            val matchedSong = matched.toSongItem(app)
            return matchedSong.copy(
                coverUrl = matchedSong.coverUrl ?: youtubeMusicThumbnailUrl(matched.videoId),
                originalCoverUrl = matchedSong.originalCoverUrl
                    ?: youtubeMusicThumbnailUrl(matched.videoId),
                mediaUri = buildYouTubeMusicMediaUri(
                    videoId = matched.videoId,
                    playlistId = target.playlistId
                ),
                playlistContextId = target.playlistId
            )
        }

        val metadata = runCatching {
            AppContainer.youtubeMusicClient.getVideoMetadata(target.videoId)
        }.getOrNull()
        val displayAlbum = app.getString(R.string.youtube_search_type_video)
        val displayName = metadata?.title.orEmpty().ifBlank {
            app.getString(R.string.explore_link_youtube_video_fallback, target.videoId)
        }
        val displayArtist = metadata?.authorName.orEmpty().ifBlank { "YouTube" }
        val coverUrl = metadata?.thumbnailUrl.orEmpty().ifBlank {
            youtubeMusicThumbnailUrl(target.videoId)
        }
        return SongItem(
            id = stableYouTubeMusicId(target.videoId),
            name = displayName,
            artist = displayArtist,
            album = displayAlbum,
            albumId = stableYouTubeMusicId("${target.videoId}|$displayAlbum"),
            durationMs = 0L,
            coverUrl = coverUrl,
            mediaUri = buildYouTubeMusicMediaUri(
                videoId = target.videoId,
                playlistId = target.playlistId
            ),
            originalName = displayName,
            originalArtist = displayArtist,
            originalCoverUrl = coverUrl,
            channelId = "youtubeMusic",
            audioId = target.videoId,
            playlistContextId = target.playlistId
        )
    }

    private suspend fun fetchLinkedYouTubePlaylist(playlistId: String): YouTubeMusicPlaylist {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        return runCatching {
            val detail = AppContainer.youtubeMusicClient.getPlaylistDetailPreview(browseId)
            YouTubeMusicPlaylist(
                browseId = detail.browseId.ifBlank { browseId },
                playlistId = detail.playlistId.ifBlank { playlistId.removePrefix("VL") },
                title = detail.title.ifBlank {
                    app.getString(R.string.explore_link_youtube_playlist_fallback, playlistId)
                },
                subtitle = detail.subtitle,
                coverUrl = detail.coverUrl,
                trackCount = detail.trackCount ?: detail.tracks.size
            )
        }.getOrElse {
            YouTubeMusicPlaylist(
                browseId = browseId,
                playlistId = playlistId.removePrefix("VL"),
                title = app.getString(R.string.explore_link_youtube_playlist_fallback, playlistId),
                subtitle = "",
                coverUrl = "",
                trackCount = 0
            )
        }
    }

    private fun parseLinkedNeteasePlaylist(
        raw: String,
        fallbackId: Long
    ): PlaylistSummary {
        val root = JSONObject(raw)
        val playlist = root.optJSONObject("playlist")
            ?: return PlaylistSummary(
                id = fallbackId,
                name = app.getString(R.string.explore_link_netease_playlist_fallback, fallbackId),
                picUrl = "",
                playCount = 0L,
                trackCount = 0
            )
        return PlaylistSummary(
            id = playlist.optLong("id", fallbackId),
            name = playlist.optString(
                "name",
                app.getString(R.string.explore_link_netease_playlist_fallback, fallbackId)
            ),
            picUrl = playlist.optString("coverImgUrl", "")
                .replaceFirst("http://", "https://"),
            playCount = playlist.optLong("playCount", 0L),
            trackCount = playlist.optInt("trackCount", 0)
        )
    }

    private fun parseLinkedNeteaseArtist(
        raw: String,
        fallbackId: Long
    ): NeteaseSearchArtistResult {
        val root = JSONObject(raw)
        val artist = root.optJSONObject("data")?.optJSONObject("artist")
            ?: root.optJSONObject("artist")
        val fallbackName = app.getString(R.string.explore_link_netease_artist_fallback, fallbackId)
        if (artist == null) {
            return NeteaseSearchArtistResult(
                artist = NeteaseArtistSummary(id = fallbackId, name = fallbackName),
                picUrl = null,
                musicSize = 0,
                albumSize = 0
            )
        }
        return NeteaseSearchArtistResult(
            artist = NeteaseArtistSummary(
                id = artist.optLong("id", fallbackId),
                name = artist.optString("name", fallbackName).ifBlank { fallbackName }
            ),
            picUrl = artist.optString("cover", "")
                .ifBlank { artist.optString("picUrl", "") }
                .ifBlank { artist.optString("avatar", "") }
                .ifBlank { artist.optString("img1v1Url", "") }
                .replaceFirst("http://", "https://")
                .takeIf { it.isNotBlank() },
            musicSize = artist.optInt("musicSize", 0),
            albumSize = artist.optInt("albumSize", 0)
        )
    }

    private suspend fun fetchNeteaseSearchPage(
        keyword: String,
        matchQuery: String,
        page: Int,
        type: NeteaseExploreSearchType
    ): ExploreSearchFetchResult {
        val offset = (page - 1).coerceAtLeast(0) * NETEASE_SEARCH_PAGE_SIZE
        val raw = withContext(Dispatchers.IO) {
            neteaseClient.searchSongs(
                keyword = keyword,
                limit = NETEASE_SEARCH_PAGE_SIZE,
                offset = offset,
                type = type.apiType,
                usePersistedCookies = false
            )
        }
        val parsed = parseNeteaseSearchResults(raw, type)
        val items = if (type == NeteaseExploreSearchType.SONG) {
            rankExploreSongSearchResults(matchQuery, parsed.items.mapNotNull {
                (it as? ExploreSearchResult.Song)?.song
            }).map { ExploreSearchResult.Song(it) }
        } else {
            parsed.items
        }
        return ExploreSearchFetchResult(
            items = items,
            page = page,
            hasMore = hasMoreExploreSearchResults(
                totalCount = parsed.totalCount,
                loadedCount = offset + parsed.items.size,
                pageItemCount = parsed.items.size,
                pageSize = NETEASE_SEARCH_PAGE_SIZE
            )
        )
    }

    suspend fun getVideoInfoByAvid(avid: Long): BiliClient.VideoBasicInfo {
        return withContext(Dispatchers.IO) {
            biliClient.getVideoBasicInfoByAvid(avid)
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
        return buildBiliPartSong(page, basicInfo, coverUrl)
    }

    private fun searchYouTubeMusic(keyword: String, matchQuery: String, requestVersion: Long) {
        if (!youtubeEnabled) return
        val searchType = _uiState.value.selectedYouTubeMusicSearchType
        searchJob = viewModelScope.launch {
            try {
                val items = when (searchType) {
                    YouTubeExploreSearchType.SONG,
                    YouTubeExploreSearchType.VIDEO -> {
                        val songs = withContext(Dispatchers.IO) {
                            AppContainer.youtubeMusicClient.search(
                                query = keyword,
                                limit = YOUTUBE_MUSIC_SEARCH_LIMIT,
                                filter = requireNotNull(searchType.filter)
                            ).map { it.toSongItem(app) }
                        }.let { rankExploreSongSearchResults(matchQuery, it) }
                        songs.map(ExploreSearchResult::Song)
                    }
                    YouTubeExploreSearchType.CREATOR -> {
                        withContext(Dispatchers.IO) {
                            AppContainer.youtubeMusicClient.searchCreators(
                                query = keyword,
                                limit = YOUTUBE_MUSIC_SEARCH_LIMIT
                            )
                        }.map(ExploreSearchResult::YouTubeCreator)
                    }
                }
                if (!isSearchRequestCurrent(requestVersion, SearchSource.YOUTUBE_MUSIC)) return@launch
                NPLogger.d(
                    TAG,
                    "search YouTube Music success: request=$requestVersion, type=$searchType, keyword=$keyword, count=${items.size}"
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.YOUTUBE_MUSIC) {
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = searchSongItems(items),
                        searchItems = items,
                        searchPage = 1,
                        searchHasMore = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search YouTube Music failed: request=$requestVersion, type=$searchType, keyword=$keyword",
                    e
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.YOUTUBE_MUSIC) {
                    it.copy(
                        searching = false,
                        searchError = app.getString(
                            R.string.error_youtube_search,
                            e.message ?: app.getString(R.string.github_sync_failed_message)
                        ),
                        searchResults = emptyList(),
                        searchItems = emptyList(),
                        searchHasMore = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchPage = 0
                    )
                }
            }
        }
    }

    /** 加载 YouTube Music 歌单列表 */
    fun loadYtMusicPlaylists() {
        if (!youtubeEnabled) return
        if (ytMusicPlaylistsJob?.isActive == true) {
            ytMusicPlaylistsPending = true
            NPLogger.d(TAG, "loadYtMusicPlaylists coalesced while loading")
            return
        }
        ytMusicPlaylistsPending = false
        _uiState.value = _uiState.value.copy(ytMusicPlaylistsLoading = true, ytMusicPlaylistsError = null)
        NPLogger.d(TAG, "loadYtMusicPlaylists start")
        ytMusicPlaylistsJob = viewModelScope.launch {
            try {
                val library = withContext(Dispatchers.IO) {
                    AppContainer.youtubeMusicClient.getLibraryPlaylists(
                        resolveMissingTrackCounts = false
                    )
                }
                val playlists = library.map { pl ->
                    YouTubeMusicPlaylist(
                        browseId = pl.browseId,
                        playlistId = pl.playlistId,
                        title = pl.title,
                        subtitle = pl.subtitle,
                        coverUrl = pl.coverUrl,
                        trackCount = pl.trackCount ?: 0
                    )
                }
                NPLogger.d(TAG, "loadYtMusicPlaylists success: count=${playlists.size}")
                _uiState.value = _uiState.value.copy(
                    ytMusicPlaylistsLoading = false,
                    ytMusicPlaylists = playlists,
                    ytMusicPlaylistsError = null
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                NPLogger.e(TAG, "loadYtMusicPlaylists failed", e)
                _uiState.value = _uiState.value.copy(
                    ytMusicPlaylistsLoading = false,
                    ytMusicPlaylistsError = "YouTube Music: ${e.message ?: "unknown error"}"
                )
            } finally {
                val completedJob = coroutineContext[Job]
                if (ytMusicPlaylistsJob === completedJob) {
                    ytMusicPlaylistsJob = null
                    if (ytMusicPlaylistsPending && youtubeEnabled) {
                        ytMusicPlaylistsPending = false
                        loadYtMusicPlaylists()
                    }
                }
            }
        }
    }

    private fun disableYouTubeSource() {
        if (_uiState.value.selectedSearchSource == SearchSource.YOUTUBE_MUSIC) {
            searchJob?.cancel()
            searchMoreJob?.cancel()
            invalidateSearchRequest()
        }
        ytMusicPlaylistsJob?.cancel()
        ytMusicPlaylistsJob = null
        ytMusicPlaylistsPending = false
        _uiState.value = _uiState.value.withYouTubeDisabled()
    }

    private fun searchErrorMessage(source: SearchSource, error: Exception): String {
        val fallback = error.message ?: app.getString(R.string.github_sync_failed_message)
        return when (source) {
            SearchSource.NETEASE -> app.getString(R.string.error_netease_search, fallback)
            SearchSource.BILIBILI -> app.getString(R.string.error_bilibili_search, fallback)
            SearchSource.YOUTUBE_MUSIC -> app.getString(R.string.error_youtube_search, fallback)
            SearchSource.LINK_RECOGNITION -> app.getString(R.string.error_link_recognition, fallback)
        }
    }
}

/** Bilibili 搜索结果到通用 SongItem 的转换器 */
private fun BiliClient.SearchVideoItem.toSongItem(): SongItem {
    return SongItem(
        id = this.aid, // 使用 avid 作为唯一ID
        name = this.titlePlain,
        artist = this.author,
        album = PlayerManager.BILI_SOURCE_TAG, // 标记来源
        albumId = 0L,
        durationMs = this.durationSec * 1000L,
        coverUrl = this.coverUrl,
        channelId = "bilibili",
        audioId = this.aid.toString()
    )
}

private fun BiliClient.VideoBasicInfo.toSongItem(): SongItem {
    return SongItem(
        id = aid,
        name = title,
        artist = ownerName,
        album = PlayerManager.BILI_SOURCE_TAG,
        albumId = 0L,
        durationMs = durationSec * 1000L,
        coverUrl = coverUrl,
        channelId = "bilibili",
        audioId = aid.toString()
    )
}

internal fun BiliClient.VideoBasicInfo.toExploreLinkSong(
    target: ExploreLinkTarget.BiliVideo
): SongItem {
    val selectedPage = target.cid
        ?.let { cid -> pages.firstOrNull { page -> page.cid == cid } }
        ?: target.page?.let { pageNumber ->
            pages.firstOrNull { page -> page.page == pageNumber }
        }
    return selectedPage?.let { page ->
        toSongItem().copy(
            album = "${PlayerManager.BILI_SOURCE_TAG}|${page.cid}",
            durationMs = page.durationSec * 1_000L,
            subAudioId = page.cid.toString()
        )
    } ?: toSongItem()
}

internal fun BiliClient.VideoBasicInfo.toExploreLinkCollectionTarget(
    target: ExploreLinkTarget.BiliVideo
): ExploreLinkTarget.BiliCollection? {
    if (!target.isCollectionShare) return null
    val season = ugcSeason
    val seasonId = target.seasonId ?: season?.id ?: return null
    val collectionOwnerMid = season?.mid?.takeIf { it > 0L }
        ?: ownerMid.takeIf { it > 0L }
        ?: return null
    return ExploreLinkTarget.BiliCollection(
        ownerMid = collectionOwnerMid,
        seasonId = seasonId
    )
}

private fun YouTubeMusicSearchResult.toSongItem(app: Application): SongItem {
    val displayArtist = artist.ifBlank { "YouTube" }
    val displayAlbum = album.ifBlank {
        when (type) {
            YouTubeMusicSearchResultType.Song -> app.getString(R.string.youtube_search_type_song)
            YouTubeMusicSearchResultType.Video -> app.getString(R.string.youtube_search_type_video)
        }
    }
    return SongItem(
        id = stableYouTubeMusicId(videoId),
        name = title,
        artist = displayArtist,
        album = displayAlbum,
        albumId = stableYouTubeMusicId("$videoId|$displayAlbum"),
        durationMs = durationMs,
        coverUrl = coverUrl.ifBlank { youtubeMusicThumbnailUrl(videoId) },
        mediaUri = buildYouTubeMusicMediaUri(videoId),
        originalName = title,
        originalArtist = displayArtist,
        originalCoverUrl = coverUrl.ifBlank { youtubeMusicThumbnailUrl(videoId) },
        channelId = "youtubeMusic",
        audioId = videoId
    )
}
