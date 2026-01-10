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
 * File: moe.ouom.neriplayer.ui.viewmodel/HomeViewModel
 * Created: 2025/8/10
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
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.IOException

private const val TAG = "NERI-HomeVM"

data class HomeUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val playlists: List<NeteasePlaylist> = emptyList()
)

/** UI 使用的精简数据模型 */
@Parcelize
data class NeteasePlaylist(
    val id: Long,
    val name: String,
    val picUrl: String,
    val playCount: Long,
    val trackCount: Int
) : Parcelable

@Parcelize
data class NeteaseAlbum(
    val id: Long,
    val name: String,
    val picUrl: String,
    val size: Int
) : Parcelable

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppContainer.neteaseCookieRepo
    private val client = AppContainer.neteaseClient

    private val _uiState = MutableStateFlow(HomeUiState(loading = true))
    val uiState: StateFlow<HomeUiState> = _uiState

    // 首页歌曲推荐：热门热曲 / 私人雷达
    private val _hotSongsFlow = MutableStateFlow<List<SongItem>>(emptyList())
    val hotSongsFlow: StateFlow<List<SongItem>> = _hotSongsFlow

    private val _radarSongsFlow = MutableStateFlow<List<SongItem>>(emptyList())
    val radarSongsFlow: StateFlow<List<SongItem>> = _radarSongsFlow

    init {
        // 登录后自动刷新首页推荐歌单
        viewModelScope.launch {
            repo.cookieFlow.collect { raw ->
                val cookies = raw.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                NPLogger.d(TAG, "cookieFlow updated: keys=${cookies.keys.joinToString()}")
                if (!cookies["MUSIC_U"].isNullOrBlank()) {
                    NPLogger.d(TAG, "Detected login cookie, refreshing recommend")
                    refreshRecommend()
                    // 登录后也触发歌曲推荐加载
                    loadHomeRecommendations()
                }
            }
        }
        // 首次进入拉一次
        refreshRecommend()
        loadHomeRecommendations()
    }

    /** 拉首页推荐歌单 */
    fun refreshRecommend() {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val cookies = withContext(Dispatchers.IO) { repo.getCookiesOnce() }.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"

                val raw = withContext(Dispatchers.IO) { client.getRecommendedPlaylists(limit = 30) }
                val mapped = parseRecommend(raw)

                _uiState.value = HomeUiState(
                    loading = false,
                    error = null,
                    playlists = mapped
                )
            } catch (e: IOException) {
                _uiState.value = HomeUiState(
                    loading = false,
                    error = "Network or server error: ${e.message ?: e.javaClass.simpleName}"  // Localized in UI
                )
            } catch (e: Exception) {
                _uiState.value = HomeUiState(
                    loading = false,
                    error = "Parse/unknown error: ${e.message ?: e.javaClass.simpleName}"  // Localized in UI
                )
            }
        }
    }

    /**
     * 首页歌曲推荐：
     * - 热门热曲：使用关键词“热歌”搜索 30 首
     * - 私人雷达：使用关键词“私人雷达”搜索 30 首
     */
    fun loadHomeRecommendations() {
        // 已经有数据就不重复拉
        if (_hotSongsFlow.value.isNotEmpty() && _radarSongsFlow.value.isNotEmpty()) return

        viewModelScope.launch {
            // 热门热曲
            launch {
                runCatching {
                    val raw = withContext(Dispatchers.IO) {
                        client.searchSongs(keyword = getApplication<Application>().getString(R.string.home_search_hot), limit = 30, offset = 0, type = 1)
                    }
                    parseSongs(raw)
                }.onSuccess { _hotSongsFlow.value = it }
            }

            // 私人雷达
            launch {
                runCatching {
                    val raw = withContext(Dispatchers.IO) {
                        client.searchSongs(keyword = getApplication<Application>().getString(R.string.home_search_radar), limit = 30, offset = 0, type = 1)
                    }
                    parseSongs(raw)
                }.onSuccess { _radarSongsFlow.value = it }
            }
        }
    }

    // 解析函数

    private fun parseRecommend(raw: String): List<NeteasePlaylist> {
        val result = mutableListOf<NeteasePlaylist>()
        val root = JSONObject(raw)

        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException(getApplication<Application>().getString(R.string.error_api_code, code))
        }

        val arr = root.optJSONArray("result") ?: return emptyList()
        val size = minOf(arr.length(), 30)
        for (i in 0 until size) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id", 0L)
            val name = obj.optString("name", "")
            val picUrl = obj.optString("picUrl", "").replace("http://", "https://")
            val playCount = obj.optLong("playCount", 0L)
            val trackCount = obj.optInt("trackCount", 0)

            if (id != 0L && name.isNotBlank() && picUrl.isNotBlank()) {
                result.add(
                    NeteasePlaylist(
                        id = id,
                        name = name,
                        picUrl = picUrl,
                        playCount = playCount,
                        trackCount = trackCount
                    )
                )
            }
        }
        return result
    }

    /** 将网易云搜索结果解析为 SongItem 列表 */
    private fun parseSongs(raw: String): List<SongItem> {
        val list = mutableListOf<SongItem>()
        val root = JSONObject(raw)
        if (root.optInt("code") != 200) return emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        for (i in 0 until songs.length()) {
            val obj = songs.optJSONObject(i) ?: continue
            val artistsArr = obj.optJSONArray("ar")
            val artistNames =
                if (artistsArr != null) (0 until artistsArr.length())
                    .mapNotNull { artistsArr.optJSONObject(it)?.optString("name") }
                else emptyList()
            val albumObj = obj.optJSONObject("al")
            list.add(
                SongItem(
                    id = obj.optLong("id"),
                    name = obj.optString("name"),
                    artist = artistNames.joinToString(" / "),
                    album = albumObj?.optString("name").orEmpty(),
                    albumId = 0L,
                    durationMs = obj.optLong("dt"),
                    coverUrl = albumObj?.optString("picUrl")?.replace("http://", "https://")
                )
            )
        }
        return list
    }
}
