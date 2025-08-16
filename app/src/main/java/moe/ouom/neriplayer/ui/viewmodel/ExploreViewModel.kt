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
 * File: moe.ouom.neriplayer.ui.viewmodel/ExploreViewModel
 * Created: 2025/8/11
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.BiliCookieRepository
import moe.ouom.neriplayer.data.NeteaseCookieRepository
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.IOException

private const val TAG = "NERI-ExploreVM"

/**
 * 定义搜索源
 * @param displayName 用于在UI上显示的名称
 */
enum class SearchSource(val displayName: String) {
    NETEASE("网易云"),
    BILIBILI("哔哩哔哩")
}

data class ExploreUiState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val playlists: List<NeteasePlaylist> = emptyList(),
    val selectedTag: String = "全部",
    val searching: Boolean = false,
    val searchError: String? = null,
    val searchResults: List<SongItem> = emptyList(),
    val selectedSearchSource: SearchSource = SearchSource.NETEASE
)

class ExploreViewModel(application: Application) : AndroidViewModel(application) {
    private val neteaseRepo = NeteaseCookieRepository(application)
    private val neteaseClient = NeteaseClient()

    private val biliCookieRepo = BiliCookieRepository(application)
    private val biliClient = BiliClient(biliCookieRepo)

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState

    init {
        // 注入网易云 Cookie
        viewModelScope.launch {
            neteaseRepo.cookieFlow.collect { raw ->
                val cookies = raw.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                neteaseClient.setPersistedCookies(cookies)
                NPLogger.d(TAG, "Netease cookie updated: keys=${cookies.keys.joinToString()}")
                if (!cookies["MUSIC_U"].isNullOrBlank() && _uiState.value.playlists.isEmpty()) {
                    loadHighQuality()
                }
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
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), searchError = null)
            return
        }
        when (_uiState.value.selectedSearchSource) {
            SearchSource.NETEASE -> searchNetease(keyword)
            SearchSource.BILIBILI -> searchBilibili(keyword)
        }
    }

    /** 搜索 Bilibili 视频 */
    private fun searchBilibili(keyword: String) {
        _uiState.value = _uiState.value.copy(searching = true, searchError = null)
        viewModelScope.launch {
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = "Bilibili 搜索失败: ${e.message}",
                    searchResults = emptyList()
                )
            }
        }
    }

    fun setSelectedTag(tag: String) {
        if (tag == _uiState.value.selectedTag) return
        _uiState.value = _uiState.value.copy(selectedTag = tag)
    }

    fun toggleExpanded() {
        _uiState.value = _uiState.value.copy(expanded = !_uiState.value.expanded)
    }

    fun loadHighQuality(cat: String? = null) {
        val realCat = cat ?: _uiState.value.selectedTag
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    neteaseClient.getHighQualityPlaylists(realCat, 50, 0L)
                }
                val mapped = parsePlaylists(raw)

                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = null,
                    playlists = mapped,
                    selectedTag = realCat
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "加载歌单失败: ${e.message}"
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
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    neteaseClient.searchSongs(keyword, limit = 30, offset = 0, type = 1)
                }
                val songs = parseSongs(raw)
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = null,
                    searchResults = songs
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchError = "网易云搜索失败: ${e.message}",
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
}

/** Bilibili 搜索结果到通用 SongItem 的转换器 */
private fun BiliClient.SearchVideoItem.toSongItem(): SongItem {
    return SongItem(
        id = this.aid, // 使用 avid 作为唯一ID
        name = this.titlePlain,
        artist = this.author,
        album = PlayerManager.BILI_SOURCE_TAG, // 标记来源
        durationMs = this.durationSec * 1000L,
        coverUrl = this.coverUrl
    )
}