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
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.data.NeteaseCookieRepository
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.IOException

private const val TAG = "NERI-ExploreVM"

data class ExploreUiState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val playlists: List<NeteasePlaylist> = emptyList(),
    val selectedTag: String = "全部",
)

class ExploreViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = NeteaseCookieRepository(application)
    private val client = NeteaseClient()

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState

    init {
        // 登录后自动注入 Cookie 并触发刷新
        viewModelScope.launch {
            repo.cookieFlow.collect { raw ->
                val cookies = raw.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                client.setPersistedCookies(cookies)
                NPLogger.d(TAG, "cookieFlow updated: keys=${cookies.keys.joinToString()}")
                if (!cookies["MUSIC_U"].isNullOrBlank()) {
                    NPLogger.d(TAG, "Detected login cookie, refreshing high-quality playlists")
                    loadHighQuality() // 自动按当前选中标签刷新
                }
            }
        }
    }

    /** 设置当前选中标签（仅更新状态，不发请求） */
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
                val cookies = withContext(Dispatchers.IO) { repo.getCookiesOnce() }.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                client.setPersistedCookies(cookies)

                val raw = withContext(Dispatchers.IO) {
                    client.getHighQualityPlaylists(realCat, 50, 0L)
                }
                val mapped = parsePlaylists(raw)

                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = null,
                    playlists = mapped,
                    selectedTag = realCat
                )
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "网络异常：${e.message}"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "解析错误：${e.message}"
                )
            }
        }
    }

    private fun parsePlaylists(raw: String): List<NeteasePlaylist> {
        val result = mutableListOf<NeteasePlaylist>()
        val root = JSONObject(raw)
        NPLogger.d("NERI-ParsePlaylists", raw)
        if (root.optInt("code") != 200) return emptyList()
        val arr = root.optJSONArray("playlists") ?: return emptyList()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id", 0)
            val name = obj.optString("name")
            val coverImgUrl = obj.optString("coverImgUrl").replace("http://", "https://")
            val playCount = obj.optLong("playCount", 0)
            val trackCount = obj.optInt("trackCount", 0)
            if (id != 0L && name.isNotBlank() && coverImgUrl.isNotBlank()) {
                result.add(NeteasePlaylist(id, name, coverImgUrl, playCount, trackCount))
            }
        }
        return result
    }
}
