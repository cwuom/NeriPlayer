package moe.ouom.neriplayer.ui.viewmodel.artist

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
 * File: moe.ouom.neriplayer.ui.viewmodel.artist/BiliUploaderDetailViewModel
 * Created: 2026/8/3
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem

private const val TAG = "NERI-BiliUploaderVM"

data class BiliUploaderHeader(
    val mid: Long,
    val name: String,
    val avatarUrl: String,
    val sign: String,
    val bannerUrl: String
)

data class BiliUploaderDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val header: BiliUploaderHeader? = null,
    val videos: List<BiliClient.UploaderVideo> = emptyList(),
    val collections: List<BiliClient.UploaderContent> = emptyList(),
    val series: List<BiliClient.UploaderContent> = emptyList(),
    val videosHasMore: Boolean = false,
    val contentsHasMore: Boolean = false,
    val videosLoadingMore: Boolean = false,
    val contentsLoadingMore: Boolean = false
)

class BiliUploaderDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AppContainer.biliClient
    private val _uiState = MutableStateFlow(BiliUploaderDetailUiState())
    val uiState: StateFlow<BiliUploaderDetailUiState> = _uiState

    private var uploaderMid: Long = 0L
    private var videoPage: Int = 1
    private var contentPage: Int = 1
    private var loadJob: Job? = null

    fun start(summary: BiliUploaderSummary, forceRefresh: Boolean = false) {
        if (!forceRefresh && shouldKeepCurrentUploader(summary.mid)) return

        uploaderMid = summary.mid
        videoPage = 1
        contentPage = 1
        loadJob?.cancel()
        _uiState.value = BiliUploaderDetailUiState(
            loading = true,
            header = BiliUploaderHeader(
                mid = summary.mid,
                name = summary.name,
                avatarUrl = summary.avatarUrl,
                sign = "",
                bannerUrl = ""
            )
        )

        loadJob = viewModelScope.launch {
            try {
                val loaded = loadInitial(summary)
                if (uploaderMid != summary.mid) return@launch
                videoPage = loaded.videosPage
                contentPage = loaded.contentsPage
                _uiState.value = loaded.uiState.copy(loading = false, error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.e(TAG, "load uploader failed: mid=${summary.mid}", error)
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = getApplication<Application>().getString(
                            R.string.bili_uploader_load_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    fun retry() {
        val header = _uiState.value.header ?: return
        start(
            BiliUploaderSummary(
                mid = header.mid,
                name = header.name,
                avatarUrl = header.avatarUrl
            ),
            forceRefresh = true
        )
    }

    fun loadMoreVideos() {
        val state = _uiState.value
        if (uploaderMid <= 0L || state.videosLoadingMore || !state.videosHasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(videosLoadingMore = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    client.getUploaderVideos(uploaderMid, page = videoPage + 1)
                }
            }.onSuccess { page ->
                videoPage = page.page
                _uiState.update {
                    it.copy(
                        videos = (it.videos + page.items).distinctBy { video -> video.bvid },
                        videosHasMore = page.hasMore,
                        videosLoadingMore = false
                    )
                }
            }.onFailure { error ->
                NPLogger.e(TAG, "load more uploader videos failed: mid=$uploaderMid", error)
                _uiState.update {
                    it.copy(
                        videosLoadingMore = false,
                        error = getApplication<Application>().getString(
                            R.string.bili_uploader_load_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    fun loadMoreContents() {
        val state = _uiState.value
        if (uploaderMid <= 0L || state.contentsLoadingMore || !state.contentsHasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(contentsLoadingMore = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    client.getUploaderContents(uploaderMid, page = contentPage + 1)
                }
            }.onSuccess { page ->
                contentPage = page.page
                _uiState.update {
                    it.copy(
                        collections = (it.collections + page.collections)
                            .distinctBy { content -> content.id },
                        series = (it.series + page.series)
                            .distinctBy { content -> content.id },
                        contentsHasMore = page.hasMore,
                        contentsLoadingMore = false
                    )
                }
            }.onFailure { error ->
                NPLogger.e(TAG, "load more uploader content failed: mid=$uploaderMid", error)
                _uiState.update {
                    it.copy(
                        contentsLoadingMore = false,
                        error = getApplication<Application>().getString(
                            R.string.bili_uploader_load_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    suspend fun getVideoInfo(bvid: String): BiliClient.VideoBasicInfo =
        withContext(Dispatchers.IO) { client.getVideoBasicInfoByBvid(bvid) }

    private fun shouldKeepCurrentUploader(mid: Long): Boolean {
        return uploaderMid == mid && _uiState.value.header?.mid == mid
    }

    private suspend fun loadInitial(summary: BiliUploaderSummary): InitialLoad = coroutineScope {
        val profileDeferred = async(Dispatchers.IO) { client.getUploaderProfile(summary.mid) }
        val videosDeferred = async(Dispatchers.IO) { client.getUploaderVideos(summary.mid) }
        val contentsDeferred = async(Dispatchers.IO) { client.getUploaderContents(summary.mid) }

        val profile = profileDeferred.await()
        val videos = videosDeferred.await()
        val contents = contentsDeferred.await()
        val header = BiliUploaderHeader(
            mid = profile.mid,
            name = profile.name.ifBlank { summary.name },
            avatarUrl = profile.faceUrl.ifBlank { summary.avatarUrl },
            sign = profile.sign,
            bannerUrl = profile.topPhotoUrl
        )
        InitialLoad(
            uiState = BiliUploaderDetailUiState(
                header = header,
                videos = videos.items,
                collections = contents.collections,
                series = contents.series,
                videosHasMore = videos.hasMore,
                contentsHasMore = contents.hasMore
            ),
            videosPage = videos.page,
            contentsPage = contents.page
        )
    }

    private data class InitialLoad(
        val uiState: BiliUploaderDetailUiState,
        val videosPage: Int,
        val contentsPage: Int
    )
}

internal fun BiliClient.UploaderVideo.toBiliVideoItem(): BiliVideoItem {
    return BiliVideoItem(
        id = aid,
        bvid = bvid,
        title = title,
        uploader = uploaderName,
        uploaderMid = uploaderMid,
        coverUrl = coverUrl,
        durationSec = durationSec
    )
}
