package moe.ouom.neriplayer.ui.viewmodel.debug

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
 * File: moe.ouom.neriplayer.ui.viewmodel.debug/YouTubeApiProbeViewModel
 * Created: 2026/3/21
 */

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.YouTubeAuthState

data class YouTubeApiProbeUiState(
    val running: Boolean = false,
    val authSummary: String = "",
    val status: String = "",
    val preview: String = ""
)

class YouTubeApiProbeViewModel(app: Application) : AndroidViewModel(app) {
    private val authRepo = AppContainer.youtubeAuthRepo
    private val client = AppContainer.youtubeMusicClient

    private val _ui = MutableStateFlow(
        YouTubeApiProbeUiState(
            authSummary = buildAuthSummary(),
            status = string(R.string.debug_youtube_probe_status_idle)
        )
    )
    val ui: StateFlow<YouTubeApiProbeUiState> = _ui.asStateFlow()

    fun probeHomeFeed() {
        viewModelScope.launch {
            updateRunning(string(R.string.debug_youtube_probe_status_loading_home))
            try {
                val shelves = withContext(Dispatchers.IO) { client.getHomeFeed() }
                _ui.value = _ui.value.copy(
                    running = false,
                    authSummary = buildAuthSummary(),
                    status = string(
                        R.string.debug_youtube_probe_status_home_success,
                        shelves.size
                    ),
                    preview = formatHomeFeedPreview(shelves)
                )
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(
                    running = false,
                    authSummary = buildAuthSummary(),
                    status = string(
                        R.string.debug_youtube_probe_status_home_failed,
                        error.message ?: error.javaClass.simpleName
                    ),
                    preview = ""
                )
            }
        }
    }

    fun probeLibraryPlaylists() {
        viewModelScope.launch {
            updateRunning(string(R.string.debug_youtube_probe_status_loading_library))
            try {
                val playlists = withContext(Dispatchers.IO) { client.getLibraryPlaylists() }
                _ui.value = _ui.value.copy(
                    running = false,
                    authSummary = buildAuthSummary(),
                    status = string(
                        R.string.debug_youtube_probe_status_library_success,
                        playlists.size
                    ),
                    preview = buildString {
                        appendLine(
                            string(
                                R.string.debug_youtube_probe_library_preview_count,
                                playlists.size
                            )
                        )
                        playlists.take(20).forEachIndexed { index, playlist ->
                            append(index + 1)
                            append(". ")
                            append(playlist.title)
                            playlist.trackCount?.let {
                                append(" (")
                                append(it)
                                append(")")
                            }
                            appendLine()
                        }
                        if (playlists.size > 20) {
                            append(
                                string(
                                    R.string.debug_youtube_probe_library_preview_more,
                                    playlists.size - 20
                                )
                            )
                        }
                    }.trimEnd()
                )
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(
                    running = false,
                    authSummary = buildAuthSummary(),
                    status = string(
                        R.string.debug_youtube_probe_status_library_failed,
                        error.message ?: error.javaClass.simpleName
                    ),
                    preview = ""
                )
            }
        }
    }

    fun clearAuth() {
        authRepo.clear()
        client.clearBootstrapCache()
        _ui.value = _ui.value.copy(
            running = false,
            authSummary = buildAuthSummary(),
            status = string(R.string.debug_youtube_probe_status_auth_cleared),
            preview = ""
        )
    }

    private fun updateRunning(status: String) {
        _ui.value = _ui.value.copy(
            running = true,
            authSummary = buildAuthSummary(),
            status = status,
            preview = ""
        )
    }

    private fun buildAuthSummary(): String {
        val health = authRepo.getAuthHealthOnce()
        val cookies = authRepo.getAuthOnce().normalized().cookies
        if (health.activeCookieKeys.isEmpty()) {
            return string(R.string.debug_youtube_probe_auth_anonymous)
        }
        return string(
            R.string.debug_youtube_probe_auth_logged_in,
            resolveAuthStateLabel(health.state),
            cookies.size
        )
    }

    private fun formatHomeFeedPreview(shelves: List<YouTubeMusicHomeShelf>): String {
        if (shelves.isEmpty()) {
            return string(R.string.debug_youtube_probe_home_preview_empty)
        }
        return buildString {
            shelves.forEachIndexed { index, shelf ->
                val songCount = shelf.items.count { it.videoId.isNotBlank() }
                val collectionCount = shelf.items.count { it.browseId.isNotBlank() }
                append(
                    string(
                        R.string.debug_youtube_probe_home_preview_item,
                        index + 1,
                        shelf.title,
                        shelf.items.size,
                        songCount,
                        collectionCount
                    )
                )
                appendLine()
            }
        }.trimEnd()
    }

    private fun resolveAuthStateLabel(state: YouTubeAuthState): String {
        return when (state) {
            YouTubeAuthState.Missing -> string(R.string.debug_youtube_probe_auth_state_missing)
            YouTubeAuthState.Expired -> string(R.string.debug_youtube_probe_auth_state_expired)
            YouTubeAuthState.Valid -> string(R.string.debug_youtube_probe_auth_state_valid)
            YouTubeAuthState.Stale -> string(R.string.debug_youtube_probe_auth_state_stale)
        }
    }

    private fun string(@StringRes resId: Int, vararg args: Any): String {
        return getApplication<Application>().getString(resId, *args)
    }
}
