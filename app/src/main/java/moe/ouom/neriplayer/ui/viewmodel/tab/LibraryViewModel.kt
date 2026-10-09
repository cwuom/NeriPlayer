package moe.ouom.neriplayer.ui.viewmodel.tab

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
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/LibraryViewModel
 * Created: 2025/8/11
 */

import moe.ouom.neriplayer.platform.youtube.api.auth.hasSavedAuthMaterial
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.collection.FavFolder
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLibraryPlaylist
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.youtube.auth.buildRefreshObserverFingerprint
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistDeleteResult
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val BILI_DETAIL_BATCH_SIZE = 6
private const val BILI_RESOURCE_TYPE_COLLECTION = 21

/** 媒体库页面 UI 状态 */
data class LibraryUiState(
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val neteasePlaylists: List<PlaylistSummary> = emptyList(),
    val neteaseAlbums: List<AlbumSummary> = emptyList(),
    val neteaseError: String? = null,
    val youtubeMusicPlaylists: List<YouTubeMusicPlaylist> = emptyList(),
    val youtubeMusicError: String? = null,
    val biliPlaylists: List<BiliPlaylist> = emptyList(),
    val biliError: String? = null
)

@Suppress("unused")
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val localRepo = LocalPlaylistRepository.getInstance(application)

    private val neteaseCookieRepo = AppContainer.neteaseCookieRepo
    private val neteaseClient = AppContainer.neteaseClient

    private val biliCookieRepo = AppContainer.biliCookieRepo
    private val biliClient = AppContainer.biliClient
    private val youtubeAuthRepo = AppContainer.youtubeAuthRepo


    private val _uiState = MutableStateFlow(
        LibraryUiState(localPlaylists = localRepo.playlists.value)
    )
    val uiState: StateFlow<LibraryUiState> = _uiState
    private var lastYouTubeAuthFingerprint: String? = null
    private var youtubeMusicPlaylistsJob: Job? = null
    private var youtubeMusicPlaylistsPending = false
    private var youtubeEnabled = YouTubeFeatureGate.isEnabled()
    private var lastObservedYouTubeEnabled: Boolean? = null

    init {
        // 本地歌单
        viewModelScope.launch {
            if (!localRepo.awaitInitialized()) return@launch
            localRepo.playlists.collect { list ->
                _uiState.value = _uiState.value.copy(localPlaylists = list)
            }
        }

        // 网易云 歌单
        viewModelScope.launch {
            neteaseCookieRepo.cookieFlow.collect { cookies ->
                val mutable = cookies.toMutableMap()
                mutable.putIfAbsent("os", "pc")
                if (!cookies["MUSIC_U"].isNullOrBlank()) {
                    refreshNeteasePlaylists()
                } else {
                    _uiState.value = _uiState.value.copy(
                        neteasePlaylists = emptyList(),
                        neteaseError = null
                    )
                }
            }
        }
        // 网易云 专辑
        viewModelScope.launch {
            neteaseCookieRepo.cookieFlow.collect { cookies ->
                val mutable = cookies.toMutableMap()
                mutable.putIfAbsent("os", "pc")
                if (!cookies["MUSIC_U"].isNullOrBlank()) {
                    refreshNeteaseAlbums()
                } else {
                    _uiState.value = _uiState.value.copy(
                        neteaseAlbums = emptyList(),
                        neteaseError = null
                    )
                }
            }
        }

        // YouTube Music
        viewModelScope.launch {
            combine(
                youtubeAuthRepo.authFlow,
                AppContainer.settingsRepo.youtubeEnabledFlow
            ) { bundle, enabled ->
                bundle to enabled
            }.collect { (bundle, enabled) ->
                youtubeEnabled = enabled
                val nextFingerprint = bundle.buildRefreshObserverFingerprint()
                val authChanged = nextFingerprint != lastYouTubeAuthFingerprint
                val enabledChanged = enabled != lastObservedYouTubeEnabled
                if (!authChanged && !enabledChanged) {
                    return@collect
                }
                lastYouTubeAuthFingerprint = nextFingerprint
                lastObservedYouTubeEnabled = enabled
                if (!enabled || !bundle.hasYouTubeMusicCookieContext()) {
                    youtubeMusicPlaylistsJob?.cancel()
                    youtubeMusicPlaylistsJob = null
                    youtubeMusicPlaylistsPending = false
                    _uiState.value = _uiState.value.copy(
                        youtubeMusicPlaylists = emptyList(),
                        youtubeMusicError = null
                    )
                } else {
                    refreshYouTubeMusicPlaylists()
                }
            }
        }

        // Bilibili
        viewModelScope.launch {
            biliCookieRepo.cookieFlow.collect { cookies ->
                if (!cookies["SESSDATA"].isNullOrBlank()) {
                    refreshBilibili()
                } else {
                    _uiState.value = _uiState.value.copy(
                        biliPlaylists = emptyList(),
                        biliError = null
                    )
                }
            }
        }
    }

    fun refreshBilibili() {
        viewModelScope.launch {
            try {
                val account = resolveLibraryBiliMid(
                    cookies = biliCookieRepo.getCookiesOnce(),
                    fetchLoginMid = biliClient::fetchLoginMid,
                    saveUserMid = biliCookieRepo::saveUserMid
                )
                val mid = when (account) {
                    is LibraryBiliMid.Ready -> account.mid
                    LibraryBiliMid.Persisted, LibraryBiliMid.Superseded -> return@launch
                    LibraryBiliMid.Missing -> {
                        _uiState.value = _uiState.value.copy(biliError = getApplication<Application>().getString(CoreCommonR.string.error_get_user_id))
                        return@launch
                    }
                }
                val mapped = withContext(Dispatchers.IO) {
                    val created = async { biliClient.getUserCreatedFavFolders(mid) }
                    val collected = async {
                        runCatching { biliClient.getUserCollectedFavFolders(mid) }
                            .onFailure { error ->
                                NPLogger.w(
                                    "LibraryViewModel-Bili",
                                    "Failed to fetch collected fav folders",
                                    error
                                )
                            }
                            .getOrDefault(emptyList())
                    }

                    val createdPlaylists = mapBiliFolders(
                        folders = created.await(),
                        kind = BiliPlaylistKind.CREATED_FAVORITE,
                        currentMid = mid
                    )
                    val collectedPlaylists = mapBiliFolders(
                        folders = collected.await(),
                        kind = BiliPlaylistKind.COLLECTED_FAVORITE,
                        currentMid = mid
                    )

                    (createdPlaylists + collectedPlaylists)
                        .filter { it.mediaId != 0L && it.title.isNotBlank() }
                        .distinctBy { "${it.kind}:${it.mediaId}" }
                }

                NPLogger.d("LibraryViewModel-Bili", mapped)

                _uiState.value = _uiState.value.copy(biliPlaylists = mapped, biliError = null)
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(biliError = e.message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(biliError = e.message)
            }
        }
    }

    private suspend fun mapBiliFolders(
        folders: List<FavFolder>,
        kind: BiliPlaylistKind,
        currentMid: Long
    ): List<BiliPlaylist> = coroutineScope {
        folders
            .filter { it.state == 0 && it.mediaId != 0L }
            .chunked(BILI_DETAIL_BATCH_SIZE)
            .flatMap { batch ->
                batch.map { folder ->
                    async(Dispatchers.IO) {
                        mapLibraryBiliFolder(
                            folder = folder,
                            kind = kind,
                            currentMid = currentMid,
                            onDetailFailure = ::logBiliFolderDetailFailure,
                            loadDetail = biliClient::getFavFolderInfo
                        )
                    }
                }.awaitAll()
            }
            .filterNotNull()
    }

    private fun logBiliFolderDetailFailure(error: Throwable) {
        NPLogger.e(
            "LibraryViewModel-Bili",
            getApplication<Application>().getString(CoreCommonR.string.music_get_detail_failed),
            error
        )
    }


    fun refreshNeteasePlaylists() {
        viewModelScope.launch {
            try {
                val uid = withContext(Dispatchers.IO) { neteaseClient.getCurrentUserId() }
                val raw = withContext(Dispatchers.IO) { neteaseClient.getUserPlaylists(uid) }
                val mapped = parseNeteaseLibraryPlaylists(raw)
                _uiState.value = _uiState.value.copy(
                    neteasePlaylists = mapped,
                    neteaseError = null
                )
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(neteaseError = e.message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(neteaseError = e.message)
            }
        }
    }
    
    fun refreshNeteaseAlbums() {
        viewModelScope.launch {
            try {
                val uid = withContext(Dispatchers.IO) { neteaseClient.getCurrentUserId() }
                val raw = withContext(Dispatchers.IO) { neteaseClient.getUserStaredAlbums(uid) }
                val mapped = parseNeteaseLibraryAlbums(raw)
                _uiState.value = _uiState.value.copy(
                    neteaseAlbums = mapped,
                    neteaseError = null
                )
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(neteaseError = e.message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(neteaseError = e.message)
            }
        }
    }

    fun refreshYouTubeMusicPlaylists() {
        if (!youtubeEnabled) return
        val runningJob = youtubeMusicPlaylistsJob
        if (runningJob?.isActive == true) {
            youtubeMusicPlaylistsPending = true
            NPLogger.d("LibraryViewModel", "refreshYouTubeMusicPlaylists coalesced while loading")
            return
        }
        youtubeMusicPlaylistsPending = false
        youtubeMusicPlaylistsJob = viewModelScope.launch {
            try {
                val playlists = withContext(Dispatchers.IO) {
                    AppContainer.youtubeMusicClient.getLibraryPlaylists(
                        resolveMissingTrackCounts = false
                    )
                }
                _uiState.value = _uiState.value.copy(
                    youtubeMusicPlaylists = playlists.map(::mapYouTubeMusicPlaylist),
                    youtubeMusicError = null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(
                    youtubeMusicPlaylists = emptyList(),
                    youtubeMusicError = e.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    youtubeMusicPlaylists = emptyList(),
                    youtubeMusicError = e.message
                )
            } finally {
                val completedJob = coroutineContext[Job]
                if (youtubeMusicPlaylistsJob === completedJob) {
                    youtubeMusicPlaylistsJob = null
                    if (youtubeMusicPlaylistsPending && youtubeEnabled) {
                        youtubeMusicPlaylistsPending = false
                        refreshYouTubeMusicPlaylists()
                    }
                }
            }
        }
    }

    fun createLocalPlaylist(name: String) {
        launchPlaylistMutation("createLocalPlaylist") { localRepo.createPlaylist(name) }
    }

    fun addSongToFavorites(song: SongItem) {
        launchPlaylistMutation("addSongToFavorites") { localRepo.addToFavorites(song) }
    }

    fun renameLocalPlaylist(playlistId: Long, newName: String) {
        launchPlaylistMutation("renameLocalPlaylist") {
            localRepo.renamePlaylist(playlistId, newName)
        }
    }

    fun deleteLocalPlaylist(
        playlistId: Long,
        onResult: (Result<List<LocalPlaylistDeleteResult>>) -> Unit = {}
    ) {
        deleteLocalPlaylists(listOf(playlistId), onResult)
    }

    fun deleteLocalPlaylists(
        playlistIds: List<Long>,
        onResult: (Result<List<LocalPlaylistDeleteResult>>) -> Unit = {}
    ) {
        viewModelScope.launch {
            onResult(
                runLocalPlaylistMutationSafely("deleteLocalPlaylists") {
                    localRepo.deletePlaylistsWithResult(playlistIds)
                }
            )
        }
    }

    fun reorderLocalPlaylists(order: List<Long>) {
        launchPlaylistMutation("reorderLocalPlaylists") { localRepo.reorderPlaylists(order) }
    }

    private fun launchPlaylistMutation(
        operation: String,
        mutation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            runLocalPlaylistMutationSafely(operation, mutation)
        }
    }

    private fun moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle.hasYouTubeMusicCookieContext(): Boolean {
        return hasSavedAuthMaterial()
    }

    private fun mapYouTubeMusicPlaylist(
        playlist: YouTubeMusicLibraryPlaylist
    ): YouTubeMusicPlaylist {
        return YouTubeMusicPlaylist(
            browseId = playlist.browseId,
            playlistId = playlist.playlistId,
            title = playlist.title,
            subtitle = playlist.subtitle,
            coverUrl = playlist.coverUrl,
            trackCount = playlist.trackCount ?: 0
        )
    }
}

/** 合集不需要再请求收藏夹详情, 详情请求失败时退回列表里的摘要 */
internal suspend fun mapLibraryBiliFolder(
    folder: FavFolder,
    kind: BiliPlaylistKind,
    currentMid: Long,
    onDetailFailure: (Throwable) -> Unit,
    loadDetail: suspend (mediaId: Long) -> FavFolder?
): BiliPlaylist? {
    val resolvedKind = if (folder.itemType == BILI_RESOURCE_TYPE_COLLECTION) BiliPlaylistKind.COLLECTION else kind
    val detail = if (resolvedKind == BiliPlaylistKind.COLLECTION) {
        null
    } else {
        loadLibraryBiliFolderDetail(folder.mediaId, onDetailFailure, loadDetail)
    }
    val source = detail ?: folder
    val title = source.title.takeIf { it.isNotBlank() } ?: return null
    return BiliPlaylist(
        mediaId = source.mediaId,
        fid = source.fid,
        mid = source.mid,
        title = title,
        count = source.count,
        coverUrl = source.coverUrl.replace("http://", "https://"),
        kind = resolvedKind,
        subtitle = source.libraryOwnerLabel(currentMid)
    )
}

/** 详情只用于补全列表字段, 失败时记录日志并回退到列表数据 */
private suspend fun loadLibraryBiliFolderDetail(
    mediaId: Long,
    onFailure: (Throwable) -> Unit,
    loadDetail: suspend (mediaId: Long) -> FavFolder?
): FavFolder? {
    return runCatching { loadDetail(mediaId) }
        .onFailure(onFailure)
        .getOrNull()
}

private fun FavFolder.libraryOwnerLabel(currentMid: Long): String {
    return upperName.ifBlank {
        if (mid != 0L && mid != currentMid) mid.toString() else ""
    }
}

internal fun parseNeteaseLibraryPlaylists(raw: String): List<PlaylistSummary> {
    val items = neteaseLibraryItems(raw) ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        items.optJSONObject(index)?.toNeteaseLibraryPlaylist()
    }
}

internal fun parseNeteaseLibraryAlbums(raw: String): List<AlbumSummary> {
    val items = neteaseLibraryItems(raw) ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        items.optJSONObject(index)?.toNeteaseLibraryAlbum()
    }
}

private fun neteaseLibraryItems(raw: String): JSONArray? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    return root.optJSONArray("playlist")
}

private fun JSONObject.toNeteaseLibraryPlaylist(): PlaylistSummary? {
    val id = optLong("id", 0L)
    val name = optString("name", "")
    if (id == 0L || name.isBlank()) return null
    return PlaylistSummary(
        id,
        name,
        optString("coverImgUrl", "").replaceFirst("http://", "https://"),
        optLong("playCount", 0L),
        optInt("trackCount", 0)
    )
}

private fun JSONObject.toNeteaseLibraryAlbum(): AlbumSummary? {
    val dataInfo = optJSONObject("dataInfo") ?: return null
    val album = dataInfo.optJSONObject("data") ?: return null
    val id = album.optLong("id", 0L)
    val name = album.optString("name", "")
    if (id == 0L || name.isBlank()) return null
    return AlbumSummary(
        id,
        name,
        dataInfo.optString("picUrl", "").replaceFirst("http://", "https://"),
        album.optInt("size", 0)
    )
}
