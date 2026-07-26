package moe.ouom.neriplayer.ui.viewmodel.customsource

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
 * File: moe.ouom.neriplayer.ui.viewmodel.customsource/CustomSourceViewModel
 * Created: 2026/7/26
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.customsource.CustomAudioSource
import moe.ouom.neriplayer.core.di.AppContainer

data class CustomSourceUiState(
    val sources: List<CustomAudioSource> = emptyList(),
    val priorityMode: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null
)

class CustomSourceViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = AppContainer.customSourceManager
    private val repo = manager.repository

    private val _uiState = MutableStateFlow(CustomSourceUiState())
    val uiState: StateFlow<CustomSourceUiState> = _uiState

    init {
        viewModelScope.launch {
            repo.load()
            combine(repo.sources, repo.priorityMode) { sources, priority ->
                sources to priority
            }.collect { (sources, priority) ->
                _uiState.value = _uiState.value.copy(
                    sources = sources,
                    priorityMode = priority
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /** 导入脚本文本:先探测运行,回填支持平台,再入库。 */
    fun importScript(scriptContent: String) {
        if (scriptContent.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, message = null)
            val result = runCatching {
                val probe = manager.probeScript(scriptContent)
                repo.importScript(
                    scriptContent = scriptContent,
                    supportedSources = if (probe.ok) probe.sources else emptyMap()
                )
                if (!probe.ok) {
                    "已导入,但脚本自检未通过: ${probe.error ?: "未知"}"
                } else if (!probe.sources.containsKey(CustomAudioSource.LX_SOURCE_NETEASE)) {
                    "已导入,但该脚本似乎不支持网易云(wy)"
                } else {
                    "导入成功"
                }
            }
            manager.onActiveSourceChanged()
            _uiState.value = _uiState.value.copy(
                busy = false,
                message = result.getOrElse { "导入失败: ${it.message}" }
            )
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repo.setEnabled(if (enabled) id else null)
            manager.onActiveSourceChanged()
        }
    }

    fun setPriorityMode(priority: Boolean) {
        viewModelScope.launch { repo.setPriorityMode(priority) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            manager.onActiveSourceChanged()
        }
    }

    /** 用一段测试:对启用音源解析测试歌曲。 */
    fun testActiveSource() {
        viewModelScope.launch {
            val active = repo.activeSource
            if (active == null) {
                _uiState.value = _uiState.value.copy(message = "请先启用一个音源")
                return@launch
            }
            _uiState.value = _uiState.value.copy(busy = true, message = null)
            val script = repo.readScript(active)
            val msg = if (script.isNullOrBlank()) {
                "脚本内容缺失"
            } else {
                val probe = runCatching { manager.probeScript(script) }.getOrNull()
                when {
                    probe == null -> "测试失败"
                    !probe.ok -> "脚本自检未通过: ${probe.error ?: "未知"}"
                    probe.sources.containsKey(CustomAudioSource.LX_SOURCE_NETEASE) ->
                        "自检通过,支持网易云,音质: ${probe.sources[CustomAudioSource.LX_SOURCE_NETEASE]?.joinToString(", ")}"
                    else -> "自检通过,但不支持网易云(wy)"
                }
            }
            _uiState.value = _uiState.value.copy(busy = false, message = msg)
        }
    }
}
