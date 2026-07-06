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
 * File: moe.ouom.neriplayer.ui.viewmodel.debug/NeteaseApiProbeViewModel
 * Updated: 2026/3/23
 */


import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import kotlin.system.measureTimeMillis

private const val TAG_PROBE = "NERI-NeteaseApiProbeVM"
private const val DEFAULT_PROBE_SONG_ID = "33894312"

data class ProbeUiState(
    val running: Boolean = false,
    val lastMessage: String = "",
    val lastJsonPreview: String = "",
    val authSummary: String = "",
    val keyword: String = "mili",
    val songId: String = DEFAULT_PROBE_SONG_ID,
    val resultSummary: String = ""
)

class NeteaseApiProbeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppContainer.neteaseCookieRepo
    private val client = AppContainer.neteaseClient

    private val _ui = MutableStateFlow(
        ProbeUiState(
            authSummary = buildAuthSummary()
        )
    )
    val ui: StateFlow<ProbeUiState> = _ui.asStateFlow()

    fun onKeywordChange(value: String) {
        _ui.value = _ui.value.copy(keyword = value)
    }

    fun onSongIdChange(value: String) {
        _ui.value = _ui.value.copy(songId = value.filter { it.isDigit() }.take(20))
    }

    /** 初始化 Cookie */
    private suspend fun ensureCookies() {
        val cookies = withContext(Dispatchers.IO) { repo.getCookiesOnce() }.toMutableMap()
        if (!cookies.containsKey("os")) cookies["os"] = "pc"
        runCatching { withContext(Dispatchers.IO) { client.ensureWeapiSession() } }
        NPLogger.d(TAG_PROBE, "Cookies injected: keys=${cookies.keys.joinToString()}")
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun callAccountAndCopy() = launchAndCopy("account") {
        val raw = client.getCurrentUserAccount()
        raw
    }

    fun callUserIdAndCopy() = launchAndCopy("userId") {
        val id = client.getCurrentUserId()
        """{"code":200,"userId":$id}"""
    }

    fun callCreatedPlaylistsAndCopy() = launchAndCopy("createdPlaylists") {
        client.getUserCreatedPlaylists(0)
    }

    fun callStaredAlbums() = launchAndCopy("staredAlbums") {
        client.getUserStaredAlbums(0)
    }

    fun callSubscribedPlaylistsAndCopy() = launchAndCopy("subscribedPlaylists") {
        client.getUserSubscribedPlaylists(0)
    }

    fun callLikedPlaylistIdAndCopy() = launchAndCopy("likedPlaylistId") {
        client.getLikedPlaylistId(0)
    }

    fun callLyric33894312AndCopy() = launchAndCopy("lyric_33894312") {
        client.getLyricNew(33894312L)
    }

    fun callSearchAndCopy() {
        val keyword = _ui.value.keyword.trim()
        if (keyword.isBlank()) {
            _ui.value = _ui.value.copy(
                lastMessage = getApplication<Application>().getString(R.string.debug_netease_probe_keyword_required)
            )
            return
        }
        launchAndCopy("search") {
            client.searchSongs(
                keyword = keyword,
                limit = 20,
                offset = 0,
                type = 1,
                usePersistedCookies = true
            )
        }
    }

    fun callSongDetailAndCopy() {
        val songId = _ui.value.songId.toLongOrNull()
        if (songId == null || songId <= 0L) {
            _ui.value = _ui.value.copy(
                lastMessage = getApplication<Application>().getString(R.string.debug_netease_probe_song_id_required)
            )
            return
        }
        launchAndCopy("songDetail") {
            client.getSongDetail(listOf(songId))
        }
    }

    fun callSongLyricAndCopy() {
        val songId = _ui.value.songId.toLongOrNull()
        if (songId == null || songId <= 0L) {
            _ui.value = _ui.value.copy(
                lastMessage = getApplication<Application>().getString(R.string.debug_netease_probe_song_id_required)
            )
            return
        }
        launchAndCopy("songLyric") {
            client.getLyricNew(songId)
        }
    }

    fun callAllAndCopy() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                running = true,
                lastMessage = getApplication<Application>().getString(R.string.debug_all_api_calling),
                lastJsonPreview = "",
                resultSummary = "",
                authSummary = buildAuthSummary()
            )
            try {
                ensureCookies()
                var result = ""
                val elapsedMs = measureTimeMillis {
                    val accountRaw = withContext(Dispatchers.IO) { client.getCurrentUserAccount() }
                    val userId = withContext(Dispatchers.IO) { client.getCurrentUserId() }
                    val createdRaw = withContext(Dispatchers.IO) { client.getUserCreatedPlaylists(0) }
                    val subsRaw = withContext(Dispatchers.IO) { client.getUserSubscribedPlaylists(0) }
                    val likedPlIdRaw = withContext(Dispatchers.IO) { client.getLikedPlaylistId(0) }
                    val lyric33894312Raw = withContext(Dispatchers.IO) { client.getLyricNew(33894312L) }

                    // 不包含 liked songs list
                    result = JSONObject().apply {
                        put("account", JSONObject(accountRaw))
                        put("userId", userId)
                        put("createdPlaylists", JSONObject(createdRaw))
                        put("subscribedPlaylists", JSONObject(subsRaw))
                        put("likedPlaylistId", JSONObject(likedPlIdRaw))
                        put("lyric_33894312", JSONObject(lyric33894312Raw))
                    }.toString()
                }

                copyToClipboard("netease_api_all", result)
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = getApplication<Application>().getString(R.string.debug_all_api_ok),
                    lastJsonPreview = formatJson(result),
                    resultSummary = buildSummary("all", result, elapsedMs),
                    authSummary = buildAuthSummary()
                )
            } catch (e: IOException) {
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = getApplication<Application>().getString(R.string.debug_network_error, e.message ?: e.javaClass.simpleName),
                    authSummary = buildAuthSummary()
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = getApplication<Application>().getString(R.string.debug_call_failed, e.message ?: e.javaClass.simpleName),
                    authSummary = buildAuthSummary()
                )
            }
        }
    }

    private fun launchAndCopy(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                running = true,
                lastMessage = getApplication<Application>().getString(R.string.debug_calling, label),
                lastJsonPreview = "",
                resultSummary = "",
                authSummary = buildAuthSummary()
            )
            try {
                ensureCookies()
                var raw = ""
                val elapsedMs = measureTimeMillis {
                    raw = withContext(Dispatchers.IO) { block() }
                }
                copyToClipboard("netease_api_$label", raw)
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = getApplication<Application>().getString(R.string.debug_copied_label, label),
                    lastJsonPreview = formatJson(raw),
                    resultSummary = buildSummary(label, raw, elapsedMs),
                    authSummary = buildAuthSummary()
                )
            } catch (e: IOException) {
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = getApplication<Application>().getString(R.string.debug_network_error, e.message ?: e.javaClass.simpleName),
                    authSummary = buildAuthSummary()
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = getApplication<Application>().getString(R.string.debug_call_failed, e.message ?: e.javaClass.simpleName),
                    authSummary = buildAuthSummary()
                )
            }
        }
    }

    private fun buildAuthSummary(): String {
        val health = repo.getAuthHealthOnce()
        val cookies = repo.getCookiesOnce()
        if (health.state == SavedCookieAuthState.Missing) {
            return getApplication<Application>().getString(R.string.debug_netease_probe_auth_missing)
        }
        return getApplication<Application>().getString(
            R.string.debug_netease_probe_auth_logged_in,
            resolveAuthStateLabel(health.state),
            cookies.size
        )
    }

    private fun resolveAuthStateLabel(state: SavedCookieAuthState): String {
        return when (state) {
            SavedCookieAuthState.Missing -> getApplication<Application>().getString(R.string.debug_netease_probe_auth_state_missing)
            SavedCookieAuthState.Valid -> getApplication<Application>().getString(R.string.debug_netease_probe_auth_state_valid)
            else -> getApplication<Application>().getString(R.string.debug_netease_probe_auth_state_unknown)
        }
    }

    private fun buildSummary(action: String, raw: String, elapsedMs: Long): String {
        val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val code = when (parsed) {
            is JSONObject -> parsed.opt("code")?.toString().orEmpty().ifBlank { "-" }
            else -> "-"
        }
        val topKeys = when (parsed) {
            is JSONObject -> buildList {
                val iterator = parsed.keys()
                while (iterator.hasNext()) {
                    add(iterator.next())
                }
            }.joinToString(", ").ifBlank { "-" }
            is JSONArray -> "[array]"
            else -> "-"
        }
        return getApplication<Application>().getString(
            R.string.debug_netease_probe_summary_template,
            action,
            elapsedMs,
            code,
            topKeys,
            raw.length
        )
    }

    private fun formatJson(raw: String): String {
        return runCatching {
            when (val parsed = JSONTokener(raw).nextValue()) {
                is JSONObject -> parsed.toString(2)
                is JSONArray -> parsed.toString(2)
                else -> raw
            }
        }.getOrDefault(raw)
    }
}
