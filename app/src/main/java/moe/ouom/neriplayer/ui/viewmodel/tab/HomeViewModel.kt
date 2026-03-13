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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.IOException

private const val TAG = "NERI-HomeVM"
private const val HOME_SEARCH_HOT_KEYWORD = "热歌"
private const val HOME_SEARCH_RADAR_KEYWORD = "私人雷达"
private const val HOME_MAX_FAILURE_BEFORE_WARNING = 3

private class ApiCodeException(val code: Int) : IllegalStateException("api_code=$code")
private fun shouldFallbackRecommend(code: Int): Boolean = code == 301 || code == 50000005

data class HomeSectionState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

data class HomeUiState(
    val playlists: HomeSectionState<NeteasePlaylist> = HomeSectionState(),
    val hotSongs: HomeSectionState<SongItem> = HomeSectionState(),
    val radarSongs: HomeSectionState<SongItem> = HomeSectionState()
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

    private val _uiState = MutableStateFlow(
        HomeUiState(
            playlists = HomeSectionState(loading = true),
            hotSongs = HomeSectionState(loading = true),
            radarSongs = HomeSectionState(loading = true)
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState

    private var playlistJob: Job? = null
    private var hotSongsJob: Job? = null
    private var radarSongsJob: Job? = null
    private var hasRecommendLogin = false

    private fun localizedAppContext() = LanguageManager.applyLanguage(getApplication())

    init {
        // 登录后自动刷新首页推荐歌单
        viewModelScope.launch {
            repo.cookieFlow.collect { raw ->
                val cookies = raw.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                NPLogger.d(TAG, "cookieFlow updated: keys=${cookies.keys.joinToString()}")
                hasRecommendLogin = !cookies["MUSIC_U"].isNullOrBlank()
                refreshRecommend()
            }
        }
        loadHomeRecommendations(force = true)
    }

    /** 拉首页推荐歌单 */
    fun refreshRecommend() {
        playlistJob?.cancel()
        val previous = _uiState.value.playlists
        _uiState.value = _uiState.value.copy(
            playlists = previous.copy(loading = true, error = null)
        )
        playlistJob = viewModelScope.launch {
            when (val result = fetchWithRetry {
                val raw = withContext(Dispatchers.IO) {
                    client.getRecommendedPlaylists(limit = 30, usePersistedCookies = hasRecommendLogin)
                }
                try {
                    parseRecommend(raw)
                } catch (e: ApiCodeException) {
                    if (hasRecommendLogin && shouldFallbackRecommend(e.code)) {
                        val fallbackRaw = withContext(Dispatchers.IO) {
                            client.getRecommendedPlaylists(limit = 30, usePersistedCookies = false)
                        }
                        parseRecommend(fallbackRaw)
                    } else {
                        throw e
                    }
                }
            }) {
                is RetryLoadResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        playlists = HomeSectionState(items = result.items)
                    )
                }
                is RetryLoadResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        playlists = _uiState.value.playlists.copy(
                            loading = false,
                            error = buildHomeErrorMessage(result.throwable)
                        )
                    )
                }
            }
        }
    }

    /**
     * 首页歌曲推荐：
     * - 热门热曲：使用关键词“热歌”搜索 30 首
     * - 私人雷达：使用关键词“私人雷达”搜索 30 首
     */
    fun loadHomeRecommendations(force: Boolean = false) {
        val state = _uiState.value
        if (!force) {
            val alreadyLoaded =
                state.hotSongs.items.isNotEmpty() && state.radarSongs.items.isNotEmpty()
            val loading = state.hotSongs.loading || state.radarSongs.loading
            if (alreadyLoaded || loading) return
        }

        refreshHotSongs()
        refreshRadarSongs()
    }

    private fun refreshHotSongs() {
        hotSongsJob?.cancel()
        val previous = _uiState.value.hotSongs
        _uiState.value = _uiState.value.copy(
            hotSongs = previous.copy(loading = true, error = null)
        )
        hotSongsJob = viewModelScope.launch {
            when (val result = fetchWithRetry {
                val raw = withContext(Dispatchers.IO) {
                    client.searchSongs(
                        keyword = HOME_SEARCH_HOT_KEYWORD,
                        limit = 30,
                        offset = 0,
                        type = 1,
                        usePersistedCookies = false
                    )
                }
                parseSongs(raw)
            }) {
                is RetryLoadResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        hotSongs = HomeSectionState(items = result.items)
                    )
                }
                is RetryLoadResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        hotSongs = _uiState.value.hotSongs.copy(
                            loading = false,
                            error = buildHomeErrorMessage(result.throwable)
                        )
                    )
                }
            }
        }
    }

    private fun refreshRadarSongs() {
        radarSongsJob?.cancel()
        val previous = _uiState.value.radarSongs
        _uiState.value = _uiState.value.copy(
            radarSongs = previous.copy(loading = true, error = null)
        )
        radarSongsJob = viewModelScope.launch {
            when (val result = fetchWithRetry {
                val raw = withContext(Dispatchers.IO) {
                    client.searchSongs(
                        keyword = HOME_SEARCH_RADAR_KEYWORD,
                        limit = 30,
                        offset = 0,
                        type = 1,
                        usePersistedCookies = false
                    )
                }
                parseSongs(raw)
            }) {
                is RetryLoadResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        radarSongs = HomeSectionState(items = result.items)
                    )
                }
                is RetryLoadResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        radarSongs = _uiState.value.radarSongs.copy(
                            loading = false,
                            error = buildHomeErrorMessage(result.throwable)
                        )
                    )
                }
            }
        }
    }

    private suspend fun <T> fetchWithRetry(
        fetch: suspend () -> List<T>
    ): RetryLoadResult<T> {
        var lastError: Throwable? = null
        repeat(HOME_MAX_FAILURE_BEFORE_WARNING) {
            try {
                return RetryLoadResult.Success(fetch())
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }
        return RetryLoadResult.Failure(lastError ?: IllegalStateException("Unknown error"))
    }

    private fun buildHomeErrorMessage(error: Throwable): String {
        val localizedContext = localizedAppContext()
        return when (error) {
            is IOException -> localizedContext.getString(
                R.string.home_error_network,
                error.message ?: error.javaClass.simpleName
            )
            is ApiCodeException -> localizedContext.getString(R.string.error_api_code, error.code)
            else -> localizedContext.getString(
                R.string.home_error_unknown,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun parseRecommend(raw: String): List<NeteasePlaylist> {
        val result = mutableListOf<NeteasePlaylist>()
        val root = JSONObject(raw)

        val code = root.optInt("code", -1)
        if (code != 200) {
            throw ApiCodeException(code)
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
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw ApiCodeException(code)
        }
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

    private sealed interface RetryLoadResult<out T> {
        data class Success<T>(val items: List<T>) : RetryLoadResult<T>
        data class Failure(val throwable: Throwable) : RetryLoadResult<Nothing>
    }
}
