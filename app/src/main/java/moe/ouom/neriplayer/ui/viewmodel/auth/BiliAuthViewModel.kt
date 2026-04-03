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
 * File: moe.ouom.neriplayer.ui.viewmodel.auth/BiliAuthViewModel
 * Created: 2025/8/13
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import org.json.JSONObject

data class BiliAuthUiState(
    val health: SavedCookieAuthHealth = SavedCookieAuthHealth()
)

sealed interface BiliAuthEvent {
    data class ShowSnack(val message: String) : BiliAuthEvent
    data class ShowCookies(val cookies: Map<String, String>) : BiliAuthEvent
    data object LoginSuccess : BiliAuthEvent
}

class BiliAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppContainer.biliCookieRepo

    private val _uiState = MutableStateFlow(
        BiliAuthUiState(
            health = repo.getAuthHealth()
        )
    )
    val uiState: StateFlow<BiliAuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<BiliAuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            repo.authHealthFlow.collect { health ->
                _uiState.value = BiliAuthUiState(health = health)
            }
        }
    }

    fun refreshAuthHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.refreshHealth()
            _uiState.value = BiliAuthUiState(health = repo.getAuthHealthOnce())
        }
    }

    fun importCookiesFromRaw(raw: String) {
        val map = linkedMapOf<String, String>()
        raw.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach {
                val idx = it.indexOf('=')
                val key = it.substring(0, idx).trim()
                val value = it.substring(idx + 1).trim()
                if (key.isNotBlank()) map[key] = value
            }
        importCookiesFromMap(map)
    }

    fun importCookiesFromMap(map: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (map.isEmpty()) {
                _events.send(
                    BiliAuthEvent.ShowSnack(
                        getApplication<Application>().getString(R.string.auth_cookie_empty)
                    )
                )
                return@launch
            }
            if (map["SESSDATA"].isNullOrBlank()) {
                _events.send(
                    BiliAuthEvent.ShowSnack(
                        getApplication<Application>().getString(R.string.auth_cookie_invalid)
                    )
                )
                return@launch
            }

            repo.saveCookies(map)
            _events.send(BiliAuthEvent.ShowCookies(map))
            _events.send(
                BiliAuthEvent.ShowSnack(
                    getApplication<Application>().getString(R.string.auth_cookie_saved)
                )
            )
            _events.send(BiliAuthEvent.LoginSuccess)
        }
    }

    fun parseJsonToMap(json: String): Map<String, String> {
        return runCatching {
            val obj = JSONObject(json)
            val out = linkedMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                out[key] = obj.optString(key, "")
            }
            out
        }.getOrElse { emptyMap() }
    }

}
