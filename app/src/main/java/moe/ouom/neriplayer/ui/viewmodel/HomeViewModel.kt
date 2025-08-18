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
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.NeteaseCookieRepository
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

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppContainer.neteaseCookieRepo
    private val client = AppContainer.neteaseClient

    private val _uiState = MutableStateFlow(HomeUiState(loading = true))
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        // 登录后自动刷新
        viewModelScope.launch {
            repo.cookieFlow.collect { raw ->
                val cookies = raw.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                NPLogger.d(TAG, "cookieFlow updated: keys=${cookies.keys.joinToString()}")
                if (!cookies["MUSIC_U"].isNullOrBlank()) {
                    NPLogger.d(TAG, "Detected login cookie, refreshing recommend")
                    refreshRecommend()
                }
            }
        }
        // 首次进入也拉一次
        refreshRecommend()
    }

    /** 拉首页推荐 */
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
                    error = "网络异常或服务器异常：${e.message ?: e.javaClass.simpleName}"
                )
            } catch (e: Exception) {
                _uiState.value = HomeUiState(
                    loading = false,
                    error = "解析/未知错误：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun parseRecommend(raw: String): List<NeteasePlaylist> {
        val result = mutableListOf<NeteasePlaylist>()
        val root = JSONObject(raw)

        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException("接口返回异常 code=$code")
        }

        val arr = root.optJSONArray("result") ?: return emptyList()
        val size = minOf(arr.length(), 30)
        for (i in 0 until size) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id", 0L)
            val name = obj.optString("name", "")
            val picUrl = obj.optString("picUrl", "")
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
}