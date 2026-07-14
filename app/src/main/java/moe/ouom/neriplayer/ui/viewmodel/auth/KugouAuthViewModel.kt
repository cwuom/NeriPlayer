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
 * File: moe.ouom.neriplayer.ui.viewmodel.auth/KugouAuthViewModel
 * Created: 2025/07/05
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import org.json.JSONObject

data class KugouAuthUiState(
    val health: SavedCookieAuthHealth = SavedCookieAuthHealth(SavedCookieAuthState.Missing),
    val hasSavedCookies: Boolean = false
)

class KugouAuthViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppContainer.kugouCookieRepo

    private val _uiState = MutableStateFlow(KugouAuthUiState())
    val uiState: StateFlow<KugouAuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.authHealthFlow.collect { health ->
                _uiState.value = KugouAuthUiState(
                    health = health,
                    hasSavedCookies = health.state != SavedCookieAuthState.Missing
                )
            }
        }
    }

    fun refreshAuthHealth() {
        repo.refreshHealth()
    }

    fun clearAuth() {
        repo.clear()
    }

    /**
     * Import cookies from a raw cookie string (key=value pairs separated by newlines or semicolons).
     */
    fun importCookiesFromRaw(raw: String) {
        val cookies = parseRawCookieString(raw)
        if (cookies.isNotEmpty()) {
            repo.saveCookies(cookies)
        }
    }

    /**
     * Import cookies from a Map (e.g. from QR login result).
     */
    fun importCookiesFromMap(cookies: Map<String, String>) {
        if (cookies.isNotEmpty()) {
            repo.saveCookies(cookies)
        }
    }

    private fun parseRawCookieString(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        // Support both newline-separated and semicolon-separated cookies
        val lines = raw.split(Regex("[\n;]"))
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex <= 0) continue
            val key = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }
}
