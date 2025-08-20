package moe.ouom.neriplayer.ui.viewmodel.auth

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
 * File: moe.ouom.neriplayer.ui.viewmodel/BiliAuthViewModel
 * Created: 2025/8/13
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.BiliCookieRepository
import org.json.JSONObject

sealed interface BiliAuthEvent {
    data class ShowSnack(val message: String) : BiliAuthEvent
    data class ShowCookies(val cookies: Map<String, String>) : BiliAuthEvent
    data object LoginSuccess : BiliAuthEvent
}

class BiliAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = BiliCookieRepository(app)

    private val _events = Channel<BiliAuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 从 raw "k=v; k2=v2" 文本导入 */
    fun importCookiesFromRaw(raw: String) {
        val map = linkedMapOf<String, String>()
        raw.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach {
                val idx = it.indexOf('=')
                val k = it.substring(0, idx).trim()
                val v = it.substring(idx + 1).trim()
                if (k.isNotBlank()) map[k] = v
            }
        importCookiesFromMap(map)
    }

    /** 从 Map 导入并持久化 */
    fun importCookiesFromMap(map: Map<String, String>) {
        viewModelScope.launch {
            if (map.isEmpty()) {
                _events.send(BiliAuthEvent.ShowSnack("Cookie 为空"))
                return@launch
            }
            repo.saveCookies(map)
            _events.send(BiliAuthEvent.ShowCookies(map))
            _events.send(BiliAuthEvent.LoginSuccess)
        }
    }

    /** 供 Activity 回调解析 JSON */
    fun parseJsonToMap(json: String): Map<String, String> {
        return runCatching {
            val obj = JSONObject(json)
            val out = linkedMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = obj.optString(k, "")
            }
            out
        }.getOrElse { emptyMap() }
    }
}