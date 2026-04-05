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
 * File: moe.ouom.neriplayer.ui.viewmodel.auth/YouTubeAuthViewModel
 * Created: 2026/3/16
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.parseRawCookieText
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthHealth
import moe.ouom.neriplayer.data.auth.youtube.evaluateYouTubeAuthHealth

data class YouTubeAuthUiState(
    val health: YouTubeAuthHealth = evaluateYouTubeAuthHealth(YouTubeAuthBundle()),
    val hasSavedAuth: Boolean = false
)

sealed interface YouTubeAuthEvent {
    data class ShowSnack(val message: String) : YouTubeAuthEvent
    data class ShowCookies(val cookies: Map<String, String>) : YouTubeAuthEvent
    data object LoginSuccess : YouTubeAuthEvent
}

class YouTubeAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppContainer.youtubeAuthRepo

    private val _uiState = MutableStateFlow(
        YouTubeAuthUiState(
            health = repo.getAuthHealth(),
            hasSavedAuth = repo.getAuthOnce().hasPersistedAuth()
        )
    )
    val uiState: StateFlow<YouTubeAuthUiState>
        get() = _uiState.asStateFlow()

    private val _events = Channel<YouTubeAuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    init {
        viewModelScope.launch {
            repo.authHealthFlow.collect { health ->
                _uiState.update { current ->
                    current.copy(health = health)
                }
            }
        }
        viewModelScope.launch {
            repo.authFlow.collect { bundle ->
                _uiState.update { current ->
                    current.copy(hasSavedAuth = bundle.hasPersistedAuth())
                }
            }
        }
    }

    fun refreshAuthHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.refreshHealth()
            val health = repo.getAuthHealthOnce()
            _uiState.update { current ->
                current.copy(health = health)
            }
        }
    }

    fun clearAuth() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.clear()
            _events.send(
                YouTubeAuthEvent.ShowSnack(
                    getApplication<Application>().getString(R.string.auth_cookie_cleared)
                )
            )
        }
    }

    fun importAuthFromJson(json: String) {
        importAuthBundle(YouTubeAuthBundle.fromJson(json))
    }

    fun importCookiesFromRaw(raw: String) {
        if (raw.isBlank()) {
            emitSnack(getApplication<Application>().getString(R.string.auth_cookie_empty))
            return
        }

        val parsed = parseRawCookieText(raw)
        if (parsed.isEmpty()) {
            emitSnack(getApplication<Application>().getString(R.string.auth_cookie_invalid))
            return
        }

        importAuthBundle(
            YouTubeAuthBundle(
                cookieHeader = parsed.entries.joinToString("; ") { (key, value) -> "$key=$value" },
                cookies = parsed,
                savedAt = System.currentTimeMillis()
            )
        )
    }

    private fun importAuthBundle(bundle: YouTubeAuthBundle) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = bundle.normalized(
                savedAt = bundle.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            if (!normalized.isUsable()) {
                _events.send(
                    YouTubeAuthEvent.ShowSnack(
                        getApplication<Application>().getString(R.string.settings_youtube_auth_missing)
                    )
                )
                return@launch
            }

            repo.saveAuth(normalized)
            _uiState.value = YouTubeAuthUiState(health = repo.getAuthHealthOnce())
            _events.send(
                YouTubeAuthEvent.ShowCookies(
                    normalized.cookies.ifEmpty { parseRawCookieText(normalized.cookieHeader) }
                )
            )
            _events.send(
                YouTubeAuthEvent.ShowSnack(
                    getApplication<Application>().getString(R.string.auth_cookie_saved)
                )
            )
            _events.send(YouTubeAuthEvent.LoginSuccess)
        }
    }

    private fun emitSnack(message: String) {
        viewModelScope.launch {
            _events.send(YouTubeAuthEvent.ShowSnack(message))
        }
    }

    private fun YouTubeAuthBundle.hasPersistedAuth(): Boolean {
        return cookies.isNotEmpty() || cookieHeader.isNotBlank() || authorization.isNotBlank()
    }
}
