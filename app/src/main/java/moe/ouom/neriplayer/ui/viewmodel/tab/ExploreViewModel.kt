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
 * File: moe.ouom.neriplayer.ui.viewmodel/ExploreViewModel
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
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicSearchResult
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicSearchResultType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.PlayerManager.biliClient
import moe.ouom.neriplayer.core.player.PlayerManager.neteaseClient
import moe.ouom.neriplayer.data.NeteaseCookieRepository
import moe.ouom.neriplayer.data.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.json.JSONObject

private const val TAG = "NERI-ExploreVM"

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

/**
 * 定义搜索源
 * @param displayName 用于在UI上显示的名称
 */
enum class SearchSource(val displayName: String) {
    YOUTUBE_MUSIC("YouTube"),
    NETEASE("Netease"),
    BILIBILI("Bilibili")
}

data class ExploreUiState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val playlists: List<NeteasePlaylist> = emptyList(),
    val selectedTag: String = "tag_all",  // String resource key
    val searching: Boolean = false,
    val searchError: String? = null,
    val searchResults: List<SongItem> = emptyList(),
    val selectedSearchSource: SearchSource = SearchSource.NETEASE,
    val ytMusicPlaylists: List<YouTubeMusicPlaylist> = emptyList(),
    val ytMusicPlaylistsLoading: Boolean = false,
    val ytMusicPlaylistsError: String? = null
)

class ExploreViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val neteaseRepo = NeteaseCookieRepository(application)
    private var highQualityLoadJob: Job? = null
    private var searchJob: Job? = null

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState

    init {
        viewModelScope.launch {
            neteaseRepo.cookieFlow.collect { raw ->
                loadHighQuality()
            }
        }
    }

    /** 设置当前搜索源 */
    fun setSearchSource(source: SearchSource) {
        if (source == _uiState.value.selectedSearchSource) return
        _uiState.value = _uiState.value.copy(
            selectedSearchSource = source,
            searchResults = emptyList(), // 切换源时清空结果
            searchError = null
        )
    }

    /** 统一搜索入口 */
    fun search(keyword: String) {
        if (keyword.isBlank()) {
            searchJob?.cancel()
            _uiState.value = _uiState.value.copy(
                searching = false,
                searchResults = emptyList(),
                searchError = null
            )
            return
        }
        when (_uiState.value.selectedSearchSource) {
            SearchSource.NETEASE -> searchNetease(keyword)
            SearchSource.BILIBILI -> searchBilibili(keyword)
            SearchSource.YOUTUBE_MUSIC -> searchYouTubeMusic(keyword)
        }
    }

    /** 搜索 Bilibili 视频 */
    private fun searchBilibili(keyword: String) {
        _uiState.value = _uiState.value.copy(searching = true, searchError = null)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                val searchPage = withContext(Dispatchers.IO) {
                    biliClient.searchVideos(keyword = keyword, page = 1)
                }
                // 将B站搜索结果转换为通用的 SongItem
                val songs = searchPage.items.map { it.toSongItem() }
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = null,
                    searchResults = songs
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = app.getString(
                        R.string.error_bilibili_search,
                        e.message ?: app.getString(R.string.github_sync_failed_message)
                    ),
                    searchResults = emptyList()
                )
            }
        }
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
        highQualityLoadJob = viewModelScope.launch {
            try {
                // Convert tag key to Chinese API category
                val apiCategory = TAG_TO_API_CATEGORY[realCat] ?: realCat
                val raw = withContext(Dispatchers.IO) {
                    neteaseClient.getHighQualityPlaylists(apiCategory, 50, 0L)
                }
                val mapped = parsePlaylists(raw)

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

    private fun parsePlaylists(raw: String): List<NeteasePlaylist> {
        val result = mutableListOf<NeteasePlaylist>()
        val root = JSONObject(raw)
        if (root.optInt("code") != 200) return emptyList()
        val arr = root.optJSONArray("playlists") ?: return emptyList()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            result.add(NeteasePlaylist(
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
    private fun searchNetease(keyword: String) {
        _uiState.value = _uiState.value.copy(searching = true, searchError = null)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    neteaseClient.searchSongs(
                        keyword = keyword,
                        limit = 30,
                        offset = 0,
                        type = 1,
                        usePersistedCookies = false
                    )
                }
                val songs = parseSongs(raw)
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = null,
                    searchResults = songs
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = app.getString(
                        R.string.error_netease_search,
                        e.message ?: app.getString(R.string.github_sync_failed_message)
                    ),
                    searchResults = emptyList()
                )
            }
        }
    }

    private fun parseSongs(raw: String): List<SongItem> {
        val list = mutableListOf<SongItem>()
        val root = JSONObject(raw)
        if (root.optInt("code") != 200) return emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        for (i in 0 until songs.length()) {
            val obj = songs.optJSONObject(i) ?: continue
            val artistsArr = obj.optJSONArray("ar")
            val artistNames = if (artistsArr != null) (0 until artistsArr.length())
                .mapNotNull { artistsArr.optJSONObject(it)?.optString("name") } else emptyList()
            val albumObj = obj.optJSONObject("al")
            list.add(SongItem(
                id = obj.optLong("id"),
                name = obj.optString("name"),
                artist = artistNames.joinToString(" / "),
                albumId = 0L,
                album = albumObj?.optString("name").orEmpty(),
                durationMs = obj.optLong("dt"),
                coverUrl = albumObj?.optString("picUrl")?.replace("http://", "https://")
            ))
        }
        return list
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

    /** 搜索 YouTube Music：只返回歌曲结果 */
    private fun searchYouTubeMusic(keyword: String) {
        _uiState.value = _uiState.value.copy(searching = true, searchError = null)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                val songs = withContext(Dispatchers.IO) {
                    AppContainer.youtubeMusicClient.search(
                        query = keyword,
                        limit = 30
                    ).map { it.toSongItem(app) }
                }
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = null,
                    searchResults = songs
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = app.getString(
                        R.string.error_youtube_search,
                        e.message ?: app.getString(R.string.github_sync_failed_message)
                    ),
                    searchResults = emptyList()
                )
            }
        }
    }

    /** 加载 YouTube Music 歌单列表 */
    fun loadYtMusicPlaylists() {
        _uiState.value = _uiState.value.copy(ytMusicPlaylistsLoading = true, ytMusicPlaylistsError = null)
        viewModelScope.launch {
            try {
                val library = withContext(Dispatchers.IO) {
                    AppContainer.youtubeMusicClient.getLibraryPlaylists()
                }
                val playlists = library.map { pl ->
                    YouTubeMusicPlaylist(
                        browseId = pl.browseId,
                        playlistId = pl.browseId.removePrefix("VL"),
                        title = pl.title,
                        subtitle = pl.subtitle,
                        coverUrl = pl.coverUrl,
                        trackCount = pl.trackCount ?: 0
                    )
                }
                _uiState.value = _uiState.value.copy(
                    ytMusicPlaylistsLoading = false,
                    ytMusicPlaylists = playlists,
                    ytMusicPlaylistsError = null
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.value = _uiState.value.copy(
                    ytMusicPlaylistsLoading = false,
                    ytMusicPlaylistsError = "YouTube Music: ${e.message ?: "unknown error"}"
                )
            }
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
        coverUrl = this.coverUrl
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
        coverUrl = coverUrl.ifBlank { null },
        mediaUri = buildYouTubeMusicMediaUri(videoId),
        originalName = title,
        originalArtist = displayArtist,
        originalCoverUrl = coverUrl.ifBlank { null }
    )
}
