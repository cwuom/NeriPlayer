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
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import org.json.JSONObject

data class BiliAuthUiState(
    val health: SavedCookieAuthHealth = SavedCookieAuthHealth()
)

sealed interface BiliAuthEvent {
    data class ShowSnack(val message: String) : BiliAuthEvent
    data class ShowCookies(val cookies: Map<String, String>) : BiliAuthEvent
    data object LoginSuccess : BiliAuthEvent
    data class PromptReauth(val health: SavedCookieAuthHealth) : BiliAuthEvent
}

@Suppress("unused")
class BiliAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppContainer.biliCookieRepo
    private val client = AppContainer.biliClient

    private val _uiState = MutableStateFlow(
        BiliAuthUiState(
            health = repo.getAuthHealth()
        )
    )
    val uiState: StateFlow<BiliAuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<BiliAuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var lastPromptSignature: String? = null

    init {
        viewModelScope.launch {
            repo.authHealthFlow.collect { health ->
                _uiState.value = BiliAuthUiState(health = health)
                if (!health.shouldPromptRelogin) {
                    lastPromptSignature = null
                }
            }
        }
    }

    fun refreshAuthHealth(
        promptIfNeeded: Boolean = false,
        forcePrompt: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.refreshHealth()
            var health = repo.getAuthHealthOnce()
            if (health.state != SavedCookieAuthState.Missing) {
                health = health.copy(
                    state = SavedCookieAuthState.Checking,
                    checkedAt = System.currentTimeMillis()
                )
                _uiState.value = BiliAuthUiState(health = health)
                health = validateCurrentAuth(repo.getAuthHealthOnce())
            }

            _uiState.value = BiliAuthUiState(health = health)
            if (promptIfNeeded) {
                emitPromptIfNeeded(health, forcePrompt)
            }
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

    private suspend fun validateCurrentAuth(localHealth: SavedCookieAuthHealth): SavedCookieAuthHealth {
        val checkedAt = System.currentTimeMillis()
        return when (client.validateLoginSession()) {
            true -> localHealth.copy(
                state = SavedCookieAuthState.Valid,
                checkedAt = checkedAt
            )
            false -> localHealth.copy(
                state = SavedCookieAuthState.Expired,
                checkedAt = checkedAt
            )
            null -> localHealth.copy(checkedAt = checkedAt)
        }
    }

    private suspend fun emitPromptIfNeeded(
        health: SavedCookieAuthHealth,
        forcePrompt: Boolean
    ) {
        if (!health.shouldPromptRelogin) {
            lastPromptSignature = null
            return
        }

        val promptSignature = buildString {
            append(health.state.name)
            append(':')
            append(health.savedAt)
        }
        if (!forcePrompt && promptSignature == lastPromptSignature) {
            return
        }

        lastPromptSignature = promptSignature
        _events.send(BiliAuthEvent.PromptReauth(health))
    }
}
