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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicDebugProbeResult
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicLocaleResolver
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.YouTubeAuthState

data class YouTubeApiProbeUiState(
    val running: Boolean = false,
    val authSummary: String = "",
    val status: String = "",
    val summary: String = "",
    val rawJson: String = "",
    val videoId: String = "",
    val browseId: String = "",
    val hl: String = "",
    val gl: String = "",
    val forceRefresh: Boolean = false
)

class YouTubeApiProbeViewModel(app: Application) : AndroidViewModel(app) {
    private val authRepo = AppContainer.youtubeAuthRepo
    private val client = AppContainer.youtubeMusicClient
    private val preferredLocale = YouTubeMusicLocaleResolver.preferred()

    private val _ui = MutableStateFlow(
        YouTubeApiProbeUiState(
            authSummary = buildAuthSummary(),
            status = string(R.string.debug_youtube_probe_status_idle),
            hl = preferredLocale.hl,
            gl = preferredLocale.gl
        )
    )
    val ui: StateFlow<YouTubeApiProbeUiState> = _ui.asStateFlow()

    fun onVideoIdChange(value: String) {
        _ui.value = _ui.value.copy(videoId = value.trim())
    }

    fun onBrowseIdChange(value: String) {
        _ui.value = _ui.value.copy(browseId = value.trim())
    }

    fun onHlChange(value: String) {
        _ui.value = _ui.value.copy(hl = value.trim())
    }

    fun onGlChange(value: String) {
        _ui.value = _ui.value.copy(gl = value.trim().uppercase())
    }

    fun onForceRefreshChange(enabled: Boolean) {
        _ui.value = _ui.value.copy(forceRefresh = enabled)
    }

    fun probeBootstrap() {
        runProbe(R.string.debug_youtube_probe_action_bootstrap) {
            client.debugBootstrap(
                hl = ui.value.hl,
                gl = ui.value.gl,
                forceRefresh = ui.value.forceRefresh
            )
        }
    }

    fun probeHomeFeed() {
        runProbe(R.string.debug_youtube_probe_action_home) {
            client.debugHomeFeedRaw(
                hl = ui.value.hl,
                gl = ui.value.gl,
                forceRefresh = ui.value.forceRefresh
            )
        }
    }

    fun probeLibraryPlaylists() {
        runProbe(R.string.debug_youtube_probe_action_library) {
            client.debugLibraryPlaylistsRaw(
                hl = ui.value.hl,
                gl = ui.value.gl,
                forceRefresh = ui.value.forceRefresh
            )
        }
    }

    fun probeBrowse() {
        val browseId = ui.value.browseId
        if (browseId.isBlank()) {
            _ui.value = _ui.value.copy(
                status = string(
                    R.string.debug_youtube_probe_status_failed_generic,
                    string(R.string.debug_youtube_probe_action_browse),
                    string(R.string.debug_youtube_probe_browse_required)
                )
            )
            return
        }
        runProbe(R.string.debug_youtube_probe_action_browse) {
            client.debugBrowseRaw(
                browseId = browseId,
                hl = ui.value.hl,
                gl = ui.value.gl,
                forceRefresh = ui.value.forceRefresh
            )
        }
    }

    fun probePlayer() {
        val videoId = ui.value.videoId
        if (videoId.isBlank()) {
            _ui.value = _ui.value.copy(
                status = string(
                    R.string.debug_youtube_probe_status_failed_generic,
                    string(R.string.debug_youtube_probe_action_player),
                    string(R.string.debug_youtube_probe_video_required)
                )
            )
            return
        }
        runProbe(R.string.debug_youtube_probe_action_player) {
            client.debugPlayerRaw(
                videoId = videoId,
                hl = ui.value.hl,
                gl = ui.value.gl,
                forceRefresh = ui.value.forceRefresh
            )
        }
    }

    fun probeLyrics() {
        val videoId = ui.value.videoId
        if (videoId.isBlank()) {
            _ui.value = _ui.value.copy(
                status = string(
                    R.string.debug_youtube_probe_status_failed_generic,
                    string(R.string.debug_youtube_probe_action_lyrics),
                    string(R.string.debug_youtube_probe_video_required)
                )
            )
            return
        }
        runProbe(R.string.debug_youtube_probe_action_lyrics) {
            client.debugLyricsRaw(
                videoId = videoId,
                hl = ui.value.hl,
                gl = ui.value.gl,
                forceRefresh = ui.value.forceRefresh
            )
        }
    }

    fun clearAuth() {
        authRepo.clear()
        client.clearBootstrapCache()
        _ui.value = _ui.value.copy(
            running = false,
            authSummary = buildAuthSummary(),
            status = string(R.string.debug_youtube_probe_status_auth_cleared),
            summary = "",
            rawJson = ""
        )
    }

    fun clearPreview() {
        _ui.value = _ui.value.copy(
            summary = "",
            rawJson = ""
        )
    }

    fun copyRawJson() {
        val rawJson = ui.value.rawJson
        if (rawJson.isBlank()) {
            return
        }
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("youtube_api_probe_raw_json", rawJson)
        )
        _ui.value = _ui.value.copy(
            status = string(R.string.debug_youtube_probe_status_copied_raw)
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

    private fun runProbe(
        @StringRes actionRes: Int,
        block: suspend () -> YouTubeMusicDebugProbeResult
    ) {
        viewModelScope.launch {
            val actionLabel = string(actionRes)
            _ui.value = _ui.value.copy(
                running = true,
                authSummary = buildAuthSummary(),
                status = string(R.string.debug_youtube_probe_status_loading_generic, actionLabel),
                summary = "",
                rawJson = ""
            )
            try {
                val result = withContext(Dispatchers.IO) { block() }
                _ui.value = _ui.value.copy(
                    running = false,
                    authSummary = buildAuthSummary(),
                    status = string(
                        R.string.debug_youtube_probe_status_success_generic,
                        actionLabel,
                        result.summary
                    ),
                    summary = result.summary,
                    rawJson = result.rawJson
                )
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(
                    running = false,
                    authSummary = buildAuthSummary(),
                    status = string(
                        R.string.debug_youtube_probe_status_failed_generic,
                        actionLabel,
                        error.message ?: error.javaClass.simpleName
                    ),
                    summary = "",
                    rawJson = ""
                )
            }
        }
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
