package moe.ouom.neriplayer.ui.viewmodel.playlist

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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/NeteaseCloudViewModel
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.PlaylistLoadFailure
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.api.client.mergeNeteaseSessionCookies
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseCloudPage
import moe.ouom.neriplayer.ui.component.playlist.playlistLoadFailure

private const val TAG_CLOUD = "NERI-NeteaseCloudVM"
private const val CLOUD_PAGE_SIZE = 200

data class NeteaseCloudUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val loginRequired: Boolean = false,
    val error: PlaylistLoadFailure? = null,
    val tracks: List<SongItem> = emptyList(),
    val totalCount: Int = 0,
    val usedBytes: Long = 0L,
    val maxBytes: Long = 0L
)

class NeteaseCloudViewModel internal constructor(
    application: Application,
    private val client: NeteaseClient,
    private val cookieRepo: NeteaseCookieRepository
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        AppContainer.neteaseClient,
        AppContainer.neteaseCookieRepo
    )

    private val _uiState = MutableStateFlow(NeteaseCloudUiState())
    val uiState: StateFlow<NeteaseCloudUiState> = _uiState

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var currentOwner: Any? = null
    private var currentOfflineMode: Boolean = false

    fun start(owner: Any, offlineMode: Boolean = false) {
        if (currentOwner === owner) return
        currentOwner = owner
        currentOfflineMode = offlineMode
        reload()
    }

    fun refresh(owner: Any) {
        if (currentOwner !== owner) return
        reload()
    }

    fun retry(owner: Any) {
        if (currentOwner !== owner) return
        reload()
    }

    fun updateOfflineMode(owner: Any, offlineMode: Boolean) {
        if (currentOwner !== owner || currentOfflineMode == offlineMode) return
        currentOfflineMode = offlineMode
        if (offlineMode) {
            cancelLoad()
            _uiState.value = _uiState.value.copy(
                loading = false,
                loadingMore = false,
                refreshing = false,
                error = PlaylistLoadFailure.Offline
            )
        } else {
            reload()
        }
    }

    fun stop(owner: Any) {
        if (currentOwner !== owner) return
        cancelLoad()
        currentOwner = null
        // 云盘可能有上千首，离开页面后不保留整份 SongItem 列表
        _uiState.value = NeteaseCloudUiState(loading = false)
    }

    private fun cancelLoad() {
        loadGeneration += 1L
        loadJob?.cancel()
        loadJob = null
    }

    private fun reload() {
        loadJob?.cancel()
        loadGeneration += 1L
        val generation = loadGeneration
        val hasTracks = _uiState.value.tracks.isNotEmpty()
        _uiState.value = _uiState.value.copy(
            loading = !hasTracks,
            loadingMore = false,
            refreshing = hasTracks,
            loginRequired = false,
            error = null
        )
        loadJob = viewModelScope.launch {
            if (currentOfflineMode) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    refreshing = false,
                    error = PlaylistLoadFailure.Offline
                )
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    cookieRepo.withCurrentCookies { cookies ->
                        client.setPersistedCookies(cookies)
                    }
                }
                if (!isCurrent(generation)) return@launch
                if (!client.hasLogin()) {
                    _uiState.value = NeteaseCloudUiState(
                        loading = false,
                        loginRequired = true
                    )
                    return@launch
                }
                loadAllPages(generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!isCurrent(generation)) return@launch
                NPLogger.e(TAG_CLOUD, "NetEase cloud request failed", e)
                failLoad(e, generation)
            } catch (e: Exception) {
                if (!isCurrent(generation)) return@launch
                NPLogger.e(TAG_CLOUD, "NetEase cloud parse failed", e)
                failLoad(e, generation)
            }
        }
    }

    private suspend fun loadAllPages(generation: Long) {
        var offset = 0
        val loaded = mutableListOf<SongItem>()
        val seenIds = mutableSetOf<Long>()
        while (true) {
            val raw = withContext(Dispatchers.IO) {
                client.getCloudSongsCancellable(offset, CLOUD_PAGE_SIZE)
            }
            if (!isCurrent(generation)) return
            val page = withContext(Dispatchers.Default) {
                parseNeteaseCloudPage(raw, offset)
            }
            if (!isCurrent(generation)) return
            NPLogger.d(
                TAG_CLOUD,
                "page offset=$offset, songs=${page.songs.size}, consumed=${page.consumed}, " +
                    "total=${page.totalCount}, hasMore=${page.hasMore}"
            )
            val sizeBefore = loaded.size
            page.songs.forEach { song ->
                if (seenIds.add(song.id)) loaded.add(song)
            }
            _uiState.value = NeteaseCloudUiState(
                loading = false,
                loadingMore = page.hasMore,
                refreshing = false,
                loginRequired = false,
                error = null,
                tracks = loaded.toList(),
                totalCount = maxOf(page.totalCount, loaded.size),
                usedBytes = page.usedBytes,
                maxBytes = page.maxBytes
            )
            if (!page.hasMore || page.consumed <= 0) break
            // offset 未生效时服务端会重复返回同一页，没有新增歌曲就停止
            if (loaded.size == sizeBefore) break
            val nextOffset = offset + page.consumed
            // count 是服务端给出的上限，避免 hasMore 异常时无限翻页
            if (page.totalCount > 0 && nextOffset >= page.totalCount) break
            offset = nextOffset
        }
        if (_uiState.value.loadingMore) {
            _uiState.value = _uiState.value.copy(loadingMore = false)
        }
        withContext(Dispatchers.IO) { persistSessionCookies() }
    }

    private fun failLoad(error: Throwable, generation: Long) {
        if (!isCurrent(generation)) return
        _uiState.value = _uiState.value.copy(
            loading = false,
            loadingMore = false,
            refreshing = false,
            error = playlistLoadFailure(error)
        )
    }

    private fun isCurrent(generation: Long): Boolean {
        return currentOwner != null && loadGeneration == generation
    }

    private suspend fun persistSessionCookies() {
        val persisted = cookieRepo.getCookiesOnce()
        val updated = mergeNeteaseSessionCookies(
            persistedCookies = persisted,
            runtimeCookies = client.getNeteaseRequestCookies()
        )
        if (updated != persisted) {
            cookieRepo.saveCookiesIfCurrent(
                expectedCookies = persisted,
                cookies = updated
            )
        }
    }
}
