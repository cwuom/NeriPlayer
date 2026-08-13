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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/NeteaseCollectionDetailViewModel
 * Created: 2025/8/10
 */

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.netease.mergeNeteaseSessionCookies
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.platform.netease.CachedNeteaseArtist
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistHeader
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistTrack
import moe.ouom.neriplayer.data.platform.netease.neteaseRadarCacheContext
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.artist.parseNeteaseArtistSummaries
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseRadarPlaylistDefinitions
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.isNeteaseRadarPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.parseNeteasePlaylistDetailSummaryOrNull
import moe.ouom.neriplayer.ui.viewmodel.tab.toPlaylistSummary
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject
import java.io.IOException

private const val TAG_PD = "NERI-PlaylistVM"
private const val NETEASE_PLAYLIST_SIGNATURE_TRACK_LIMIT = 100

internal fun resolveNeteaseCollectionCoverUrl(
    primary: String?,
    fallback: String? = null
): String {
    return normalizeNeteaseCollectionCoverUrl(primary)
        ?: normalizeNeteaseCollectionCoverUrl(fallback)
        ?: ""
}

private fun normalizeNeteaseCollectionCoverUrl(url: String?): String? {
    val normalized = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return normalized.replaceFirst(Regex("^http://"), "https://")
}

internal fun refreshNeteasePlaylistCachedHeader(
    cached: CachedNeteasePlaylistDetail,
    fresh: NeteaseCollectionHeader?
): CachedNeteasePlaylistDetail {
    fresh ?: return cached
    val previous = cached.header
    return cached.copy(
        header = CachedNeteasePlaylistHeader(
            id = previous.id,
            name = fresh.name.ifBlank { previous.name },
            coverUrl = fresh.coverUrl.ifBlank { previous.coverUrl },
            playCount = fresh.playCount.takeIf { it > 0L } ?: previous.playCount,
            trackCount = fresh.trackCount.takeIf { it > 0 } ?: previous.trackCount
        )
    )
}

internal fun shouldReuseNeteasePlaylistCache(
    cached: CachedNeteasePlaylistDetail,
    expectedTrackCount: Int,
    recentTrackSignature: String,
    requireHeaderTrackCountMatch: Boolean
): Boolean {
    if (requireHeaderTrackCountMatch) {
        val cachedTrackCount = cached.header.trackCount.takeIf { it > 0 }
            ?: cached.tracks.size
        if (expectedTrackCount > 0 && cachedTrackCount != expectedTrackCount) return false
    }
    if (expectedTrackCount > 0 && cached.tracks.size < expectedTrackCount) return false
    return cached.recentTrackSignature == recentTrackSignature
}

internal fun isNeteasePlaylistCacheCompatible(
    cached: CachedNeteasePlaylistDetail,
    requestedPlaylistId: Long,
    expectedRadarCacheContext: String
): Boolean {
    if (cached.playlistId != requestedPlaylistId) return false
    if (!isNeteaseRadarPlaylist(requestedPlaylistId)) return true
    return cached.radarCacheContext == expectedRadarCacheContext
}

internal fun shouldAcceptNeteasePlaylistLoadResult(
    requestedPlaylistId: Long,
    activePlaylistId: Long,
    requestRadarCacheContext: String,
    currentRadarCacheContext: String
): Boolean {
    if (requestedPlaylistId != activePlaylistId) return false
    if (!isNeteaseRadarPlaylist(requestedPlaylistId)) return true
    return requestRadarCacheContext == currentRadarCacheContext
}

internal fun shouldAcceptNeteaseCollectionLoadResult(
    requestedCollectionId: Long,
    activeCollectionId: Long,
    requestGeneration: Long,
    activeGeneration: Long
): Boolean {
    return requestedCollectionId == activeCollectionId &&
        requestGeneration == activeGeneration
}

internal fun shouldHandleInitialNeteaseRadarContextEmission(
    isFirstEmission: Boolean,
    initialRadarCacheContext: String,
    emittedRadarCacheContext: String
): Boolean = !isFirstEmission || initialRadarCacheContext != emittedRadarCacheContext

internal fun resetNeteaseRadarPlaylistSummaryForContextChange(
    playlist: PlaylistSummary
): PlaylistSummary {
    if (!isNeteaseRadarPlaylist(playlist.id)) return playlist
    return NeteaseRadarPlaylistDefinitions
        .firstOrNull { definition -> definition.id == playlist.id }
        ?.toPlaylistSummary()
        ?: playlist.copy(
            name = playlist.name,
            picUrl = "",
            playCount = 0L,
            trackCount = 0
        )
}

internal fun prepareNeteasePlaylistEntryForContext(
    playlist: PlaylistSummary,
    knownCacheContext: String?,
    currentCacheContext: String
): PlaylistSummary {
    if (
        !isNeteaseRadarPlaylist(playlist.id) ||
        knownCacheContext != null && knownCacheContext == currentCacheContext
    ) {
        return playlist
    }
    return resetNeteaseRadarPlaylistSummaryForContextChange(playlist)
}

internal fun applyNeteaseRadarPlaylistHeader(
    playlist: PlaylistSummary,
    detailHeader: NeteaseCollectionHeader
): NeteaseCollectionHeader {
    if (!isNeteaseRadarPlaylist(playlist.id)) {
        return detailHeader
    }
    return detailHeader.copy(
        name = playlist.name.ifBlank { detailHeader.name },
        coverUrl = resolveNeteaseCollectionCoverUrl(
            primary = playlist.picUrl,
            fallback = detailHeader.coverUrl
        ),
        playCount = playlist.playCount.takeIf { it > 0L } ?: detailHeader.playCount,
        trackCount = playlist.trackCount.takeIf { it > 0 } ?: detailHeader.trackCount
    )
}

internal fun CachedNeteasePlaylistHeader.toNeteaseCollectionHeader(
    fallback: PlaylistSummary
): NeteaseCollectionHeader {
    return NeteaseCollectionHeader(
        id = id,
        isAlbum = false,
        name = name.ifBlank { fallback.name },
        coverUrl = coverUrl.ifBlank {
            normalizeNeteaseCollectionCoverUrl(fallback.picUrl) ?: ""
        },
        playCount = playCount.takeIf { it > 0L } ?: fallback.playCount,
        trackCount = trackCount.takeIf { it > 0 } ?: fallback.trackCount
    )
}

internal fun resolveNeteasePlaylistDisplayHeader(
    playlist: PlaylistSummary,
    detailHeader: NeteaseCollectionHeader,
    freshRadarHeader: PlaylistSummary?,
    cachedRadarHeader: CachedNeteasePlaylistHeader?
): NeteaseCollectionHeader {
    if (!isNeteaseRadarPlaylist(playlist.id)) return detailHeader
    val fallbackHeader = cachedRadarHeader?.toNeteaseCollectionHeader(playlist)
        ?: applyNeteaseRadarPlaylistHeader(playlist, detailHeader)
    return freshRadarHeader?.let { header ->
        applyNeteaseRadarPlaylistHeader(header, fallbackHeader)
    } ?: fallbackHeader
}

internal fun matchingNeteaseRadarCacheHeader(
    cached: CachedNeteasePlaylistDetail?,
    playlistId: Long,
    expectedRadarCacheContext: String
): CachedNeteasePlaylistHeader? {
    return cached
        ?.takeIf { cache ->
            !isNeteaseRadarPlaylist(playlistId) ||
                cache.radarCacheContext == expectedRadarCacheContext
        }
        ?.header
}

class NeteaseCollectionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AppContainer.neteaseClient
    private val cookieRepo = AppContainer.neteaseCookieRepo
    private val playlistCacheRepo = AppContainer.neteasePlaylistCacheRepo

    private val _uiState = MutableStateFlow(NeteaseCollectionDetailUiState())
    val uiState: StateFlow<NeteaseCollectionDetailUiState> = _uiState

    private var playlistId: Long = 0L
    private var currentPlaylist: PlaylistSummary? = null
    private var visibleRadarCacheContext: String? = null
    private var playlistLoadJob: Job? = null
    private var playlistLoadGeneration: Long = 0L
    private val initialRadarCacheContext = neteaseRadarCacheContext(cookieRepo.getCookiesOnce())

    init {
        viewModelScope.launch {
            var isFirstRadarContextEmission = true
            cookieRepo.cookieFlow
                .map(::neteaseRadarCacheContext)
                .distinctUntilChanged()
                .collect { radarCacheContext ->
                    val shouldHandleEmission = shouldHandleInitialNeteaseRadarContextEmission(
                        isFirstEmission = isFirstRadarContextEmission,
                        initialRadarCacheContext = initialRadarCacheContext,
                        emittedRadarCacheContext = radarCacheContext
                    )
                    isFirstRadarContextEmission = false
                    if (!shouldHandleEmission) return@collect
                    val playlist = currentPlaylist
                        ?.takeIf { isNeteaseRadarPlaylist(it.id) }
                        ?: return@collect
                    if (visibleRadarCacheContext == radarCacheContext) return@collect
                    startPlaylist(
                        playlist = resetNeteaseRadarPlaylistSummaryForContextChange(playlist),
                        forceRefresh = true
                    )
                }
        }
    }

    fun startPlaylist(playlist: PlaylistSummary, forceRefresh: Boolean = false) {
        playlistLoadJob?.cancel()
        playlistLoadGeneration += 1L
        val loadGeneration = playlistLoadGeneration
        val radarCacheContext = neteaseRadarCacheContext(cookieRepo.getCookiesOnce())
        val entryPlaylist = prepareNeteasePlaylistEntryForContext(
            playlist = playlist,
            knownCacheContext = visibleRadarCacheContext,
            currentCacheContext = radarCacheContext
        )
        currentPlaylist = entryPlaylist
        playlistId = entryPlaylist.id
        val isRadarPlaylist = isNeteaseRadarPlaylist(entryPlaylist.id)
        val previous = _uiState.value.takeIf {
            val header = it.header
            forceRefresh &&
                header?.id == entryPlaylist.id &&
                header.isAlbum == false &&
                (!isRadarPlaylist || visibleRadarCacheContext == radarCacheContext)
        }
        visibleRadarCacheContext = radarCacheContext.takeIf { isRadarPlaylist }

        // 用入口数据把 header 预填
        _uiState.value = NeteaseCollectionDetailUiState(
            loading = true,
            header = previous?.header ?: NeteaseCollectionHeader(
                id = entryPlaylist.id,
                isAlbum = false,
                name = entryPlaylist.name,
                coverUrl = toHttps(entryPlaylist.picUrl) ?: "",
                playCount = entryPlaylist.playCount,
                trackCount = entryPlaylist.trackCount
            ),
            tracks = previous?.tracks.orEmpty()
        )

        playlistLoadJob = viewModelScope.launch {
            loadPlaylist(
                playlist = entryPlaylist,
                forceRefresh = forceRefresh,
                loadGeneration = loadGeneration
            )
        }
    }

    private suspend fun loadPlaylist(
        playlist: PlaylistSummary,
        forceRefresh: Boolean,
        loadGeneration: Long
    ) {
        var radarCacheContext = neteaseRadarCacheContext(cookieRepo.getCookiesOnce())
        val requestStartedAtMs = System.currentTimeMillis()
        val cached = readCompatiblePlaylistCache(playlist.id)
        if (
            !forceRefresh &&
            cached != null &&
            isCurrentPlaylistLoad(
                playlist = playlist,
                loadGeneration = loadGeneration,
                radarCacheContext = cached.radarCacheContext ?: radarCacheContext
            )
        ) {
            publishCachedPlaylist(cached, playlist, loading = true)
        }

        try {
            radarCacheContext = preparePlaylistRequest(playlist.id)
            if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) return
            val (raw, radarHeader) = coroutineScope {
                val detailRequest = async {
                    client.getPlaylistDetailCancellable(playlist.id)
                }
                val radarHeaderRequest = async {
                    loadRadarPlaylistHeader(playlist)
                }
                detailRequest.await() to radarHeaderRequest.await()
            }
            if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) return
            if (isNeteaseRadarPlaylist(playlist.id)) {
                withContext(Dispatchers.IO) {
                    persistNeteaseSessionCookies(radarCacheContext)
                }
            }
            NPLogger.d(TAG_PD, "detail head=${raw.take(500)}")

            val parsed = parseDetailFromPlaylist(raw)
            val displayHeader = resolveNeteasePlaylistDisplayHeader(
                playlist = playlist,
                detailHeader = parsed.header,
                freshRadarHeader = radarHeader,
                cachedRadarHeader = matchingNeteaseRadarCacheHeader(
                    cached = cached,
                    playlistId = playlist.id,
                    expectedRadarCacheContext = radarCacheContext
                )
            )
            if (
                !forceRefresh &&
                cached != null &&
                isNeteasePlaylistCacheCompatible(
                    cached = cached,
                    requestedPlaylistId = playlist.id,
                    expectedRadarCacheContext = radarCacheContext
                ) &&
                shouldReuseCachedPlaylist(cached, parsed)
            ) {
                if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) return
                val reusableCache = if (isNeteaseRadarPlaylist(playlist.id)) {
                    refreshNeteasePlaylistCachedHeader(cached, displayHeader)
                } else {
                    cached
                }
                val headerFallback = radarHeader ?: playlist
                publishCachedPlaylist(reusableCache, headerFallback)
                if (reusableCache != cached) {
                    withContext(Dispatchers.IO) {
                        if (isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) {
                            playlistCacheRepo.saveIfNewer(reusableCache)
                        }
                    }
                }
                NPLogger.d(
                    TAG_PD,
                    "reuse NetEase playlist cache: playlistId=${playlist.id}, count=${cached.tracks.size}"
                )
                return
            }

            val tracks = resolvePlaylistTracks(parsed)
            if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) return
            visibleRadarCacheContext = radarCacheContext.takeIf {
                isNeteaseRadarPlaylist(playlist.id)
            }
            _uiState.value = NeteaseCollectionDetailUiState(
                loading = false,
                error = null,
                header = displayHeader,
                tracks = tracks
            )
            withContext(Dispatchers.IO) {
                if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) {
                    return@withContext
                }
                if (client.hasLogin()) {
                    persistNeteaseSessionCookies(radarCacheContext)
                }
                if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) {
                    return@withContext
                }
                playlistCacheRepo.saveIfNewer(
                    parsed.toCache(
                        tracks = tracks,
                        displayHeader = displayHeader,
                        radarCacheContext = radarCacheContext,
                        savedAtMs = requestStartedAtMs
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) return
            val fallback = readCompatiblePlaylistCache(playlist.id)
            if (fallback != null) {
                if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) return
                NPLogger.w(TAG_PD, "NetEase playlist detail failed, fallback to cache: playlistId=${playlist.id}", e)
                publishCachedPlaylist(fallback, playlist)
                return
            }
            NPLogger.e(TAG_PD, "Network/Server error", e)
            _uiState.value = _uiState.value.copy(
                loading = false,
                error = "Network or server error: ${e.message ?: e.javaClass.simpleName}"  // Localized in UI
            )
        } catch (e: Exception) {
            if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) return
            val fallback = readCompatiblePlaylistCache(playlist.id)
            if (fallback != null) {
                if (!isCurrentPlaylistLoad(playlist, loadGeneration, radarCacheContext)) return
                NPLogger.w(TAG_PD, "NetEase playlist detail parse failed, fallback to cache: playlistId=${playlist.id}", e)
                publishCachedPlaylist(fallback, playlist)
                return
            }
            NPLogger.e(TAG_PD, "Unexpected error", e)
            _uiState.value = _uiState.value.copy(
                loading = false,
                error = "Parse/unknown error: ${e.message ?: e.javaClass.simpleName}"  // Localized in UI
            )
        }
    }

    private fun isCurrentPlaylistLoad(
        playlist: PlaylistSummary,
        loadGeneration: Long,
        radarCacheContext: String
    ): Boolean {
        if (
            !shouldAcceptNeteaseCollectionLoadResult(
                requestedCollectionId = playlist.id,
                activeCollectionId = playlistId,
                requestGeneration = loadGeneration,
                activeGeneration = playlistLoadGeneration
            )
        ) {
            return false
        }
        return shouldAcceptNeteasePlaylistLoadResult(
            requestedPlaylistId = playlist.id,
            activePlaylistId = playlistId,
            requestRadarCacheContext = radarCacheContext,
            currentRadarCacheContext = neteaseRadarCacheContext(cookieRepo.getCookiesOnce())
        )
    }

    private suspend fun preparePlaylistRequest(playlistId: Long): String {
        return withContext(Dispatchers.IO) {
            val cookies = syncNeteaseClientCookies()
            val radarCacheContext = neteaseRadarCacheContext(cookies)
            if (
                !isNeteaseRadarPlaylist(playlistId) ||
                cookies["MUSIC_U"].isNullOrBlank()
            ) {
                return@withContext radarCacheContext
            }
            try {
                client.ensurePersonalizedSession()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG_PD, "radar session preheat failed: ${error.message}")
            }
            radarCacheContext
        }
    }

    private fun syncNeteaseClientCookies(): Map<String, String> {
        return cookieRepo.withCurrentCookies { cookies ->
            client.setPersistedCookies(cookies)
            cookies
        }
    }

    private suspend fun readCompatiblePlaylistCache(
        playlistId: Long
    ): CachedNeteasePlaylistDetail? {
        return withContext(Dispatchers.IO) {
            val radarCacheContext = neteaseRadarCacheContext(cookieRepo.getCookiesOnce())
            val requestedRadarCacheContext = radarCacheContext.takeIf {
                isNeteaseRadarPlaylist(playlistId)
            }
            playlistCacheRepo.read(playlistId, requestedRadarCacheContext)?.takeIf { cached ->
                isNeteasePlaylistCacheCompatible(
                    cached = cached,
                    requestedPlaylistId = playlistId,
                    expectedRadarCacheContext = radarCacheContext
                )
            }
        }
    }

    private suspend fun loadRadarPlaylistHeader(playlist: PlaylistSummary): PlaylistSummary? {
        if (!isNeteaseRadarPlaylist(playlist.id)) return null
        return try {
            val raw = client.getRadarPlaylistMetadataCancellable(playlist.id)
            parseNeteasePlaylistDetailSummaryOrNull(raw)?.takeIf { header ->
                header.id == playlist.id
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG_PD, "radar metadata failed: playlistId=${playlist.id}, error=${error.message}")
            null
        }
    }

    private fun persistNeteaseSessionCookies(expectedRadarCacheContext: String? = null) {
        val persisted = cookieRepo.getCookiesOnce()
        if (
            expectedRadarCacheContext != null &&
            neteaseRadarCacheContext(persisted) != expectedRadarCacheContext
        ) {
            return
        }
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

    private suspend fun publishCachedPlaylist(
        cached: CachedNeteasePlaylistDetail,
        fallback: PlaylistSummary,
        loading: Boolean = false
    ) {
        visibleRadarCacheContext = cached.radarCacheContext.takeIf {
            isNeteaseRadarPlaylist(cached.playlistId)
        }
        _uiState.value = withContext(Dispatchers.Default) {
            NeteaseCollectionDetailUiState(
                loading = loading,
                error = null,
                header = cached.header.toNeteaseCollectionHeader(fallback),
                tracks = cached.tracks.map { it.toSongItem() }
            )
        }
    }

    private fun shouldReuseCachedPlaylist(
        cached: CachedNeteasePlaylistDetail,
        parsed: ParsedDetail
    ): Boolean {
        return shouldReuseNeteasePlaylistCache(
            cached = cached,
            expectedTrackCount = parsed.expectedTrackCount(),
            recentTrackSignature = parsed.recentTrackSignature(),
            requireHeaderTrackCountMatch = !isNeteaseRadarPlaylist(parsed.header.id)
        )
    }

    private suspend fun resolvePlaylistTracks(parsed: ParsedDetail): List<SongItem> {
        return if (
            parsed.trackIds.isNotEmpty() &&
            parsed.trackIds.size > parsed.tracks.size
        ) {
            fetchFullPlaylistTracks(parsed.trackIds, parsed.tracks)
        } else {
            parsed.tracks
        }
    }

    private fun ParsedDetail.expectedTrackCount(): Int {
        return header.trackCount.takeIf { it > 0 }
            ?: trackIds.size.takeIf { it > 0 }
            ?: tracks.size
    }

    private fun ParsedDetail.recentTrackSignature(): String {
        val ids = trackIds.ifEmpty { tracks.map { it.id } }
        return buildString {
            append(expectedTrackCount())
            append('#')
            ids.take(NETEASE_PLAYLIST_SIGNATURE_TRACK_LIMIT).forEachIndexed { index, id ->
                append(index)
                append(':')
                append(id)
                append('|')
            }
        }
    }

    private fun ParsedDetail.toCache(
        tracks: List<SongItem>,
        displayHeader: NeteaseCollectionHeader = header,
        radarCacheContext: String,
        savedAtMs: Long = System.currentTimeMillis()
    ): CachedNeteasePlaylistDetail {
        return CachedNeteasePlaylistDetail(
            playlistId = header.id,
            header = displayHeader.toCachedHeader(),
            recentTrackSignature = recentTrackSignature(),
            tracks = tracks.map { it.toCachedTrack() },
            radarCacheContext = radarCacheContext.takeIf {
                isNeteaseRadarPlaylist(header.id)
            },
            savedAtMs = savedAtMs
        )
    }

    private fun NeteaseCollectionHeader.toCachedHeader(): CachedNeteasePlaylistHeader {
        return CachedNeteasePlaylistHeader(
            id = id,
            name = name,
            coverUrl = coverUrl,
            playCount = playCount,
            trackCount = trackCount
        )
    }

    private fun SongItem.toCachedTrack(): CachedNeteasePlaylistTrack {
        return CachedNeteasePlaylistTrack(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            coverUrl = coverUrl,
            audioId = audioId,
            artists = neteaseArtists.orEmpty().map {
                CachedNeteaseArtist(id = it.id, name = it.name)
            },
            addedAt = addedAt
        )
    }

    private fun CachedNeteasePlaylistTrack.toSongItem(): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            coverUrl = coverUrl,
            originalCoverUrl = coverUrl,
            channelId = "netease",
            audioId = audioId ?: id.toString(),
            neteaseArtists = artists.map {
                NeteaseArtistSummary(id = it.id, name = it.name)
            },
            addedAt = addedAt
        )
    }

    fun startAlbum(album: AlbumSummary) {
        // 专辑依然每次刷新，避免和歌单缓存混用
        playlistLoadJob?.cancel()
        playlistLoadGeneration += 1L
        val loadGeneration = playlistLoadGeneration
        val albumId = album.id
        currentPlaylist = null
        playlistId = albumId
        visibleRadarCacheContext = null

        // 用入口数据把 header 预填
        _uiState.value = NeteaseCollectionDetailUiState(
            loading = true,
            header = NeteaseCollectionHeader(
                id = album.id,
                isAlbum = true,
                name = album.name,
                coverUrl = toHttps(album.picUrl) ?: "",
                playCount = 0,
                trackCount = album.size
            ),
            tracks = emptyList()
        )

        playlistLoadJob = viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    syncNeteaseClientCookies()
                    client.getAlbumDetail(albumId)
                }
                if (!isCurrentCollectionLoad(albumId, loadGeneration)) return@launch
                NPLogger.d(TAG_PD, "detail head=${raw.take(500)}")

                val (header, tracks) = parseDetailFromAlbum(
                    raw = raw,
                    coverFallback = album.picUrl
                )
                if (!isCurrentCollectionLoad(albumId, loadGeneration)) return@launch

                _uiState.value = NeteaseCollectionDetailUiState(
                    loading = false,
                    error = null,
                    header = header,
                    tracks = tracks
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!isCurrentCollectionLoad(albumId, loadGeneration)) return@launch
                NPLogger.e(TAG_PD, "Network/Server error", e)
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "Network or server error: ${e.message ?: e.javaClass.simpleName}"  // Localized in UI
                )
            } catch (e: Exception) {
                if (!isCurrentCollectionLoad(albumId, loadGeneration)) return@launch
                NPLogger.e(TAG_PD, "Unexpected error", e)
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "Parse/unknown error: ${e.message ?: e.javaClass.simpleName}"  // Localized in UI
                )
            }
        }
    }

    private fun isCurrentCollectionLoad(
        collectionId: Long,
        loadGeneration: Long
    ): Boolean {
        return shouldAcceptNeteaseCollectionLoadResult(
            requestedCollectionId = collectionId,
            activeCollectionId = playlistId,
            requestGeneration = loadGeneration,
            activeGeneration = playlistLoadGeneration
        )
    }

    fun retry() {
        val h = _uiState.value.header ?: return
        if (h.isAlbum) {
            startAlbum(
                AlbumSummary(
                    id = h.id,
                    name = h.name,
                    picUrl = h.coverUrl,
                    size = h.trackCount
                )
            )
        } else {
            val current = currentPlaylist?.takeIf { it.id == h.id }
            startPlaylist(
                current?.copy(
                    name = h.name,
                    picUrl = h.coverUrl,
                    playCount = h.playCount,
                    trackCount = h.trackCount
                ) ?: PlaylistSummary(
                    id = h.id,
                    name = h.name,
                    picUrl = h.coverUrl,
                    playCount = h.playCount,
                    trackCount = h.trackCount
                ),
                forceRefresh = true
            )
        }
    }

    private fun toHttps(url: String?): String? =
        url?.replaceFirst(Regex("^http://"), "https://")

    private fun parseDetailFromPlaylist(raw: String): ParsedDetail {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        require(code == 200) { getApplication<Application>().getString(R.string.error_api_code, code) }

        val pl = root.optJSONObject("playlist") ?: error(getApplication<Application>().getString(R.string.error_missing_node, "playlist"))

        val header = NeteaseCollectionHeader(
            id = pl.optLong("id"),
            name = pl.optString("name"),
            coverUrl = toHttps(pl.optString("coverImgUrl", "")) ?: "",
            playCount = pl.optLong("playCount", 0L),
            trackCount = pl.optInt("trackCount", 0),
            isAlbum = false
        )

        val list = mutableListOf<SongItem>()
        val tracksArr = pl.optJSONArray("tracks")
        if (tracksArr != null) {
            for (i in 0 until tracksArr.length()) {
                val t = tracksArr.optJSONObject(i) ?: continue
                parseSongItem(t)?.let { list.add(it) }
            }
        }
        val trackIds = mutableListOf<Long>()
        val trackIdsArr = pl.optJSONArray("trackIds")
        if (trackIdsArr != null) {
            for (i in 0 until trackIdsArr.length()) {
                val id = trackIdsArr.optJSONObject(i)?.optLong("id", 0L) ?: 0L
                if (id != 0L) trackIds.add(id)
            }
        }
        return ParsedDetail(header, list, trackIds)
    }

    private fun parseDetailFromAlbum(
        raw: String,
        coverFallback: String? = null
    ): ParsedDetail {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        require(code == 200) { getApplication<Application>().getString(R.string.error_api_code, code) }

        val al = root.optJSONObject("album") ?: error(getApplication<Application>().getString(R.string.error_missing_node, "album"))
        val cover = resolveNeteaseCollectionCoverUrl(
            primary = al.optString("picUrl", ""),
            fallback = coverFallback
        )

        val header = NeteaseCollectionHeader(
            id = al.optLong("id"),
            name = al.optString("name"),
            coverUrl = cover,
            playCount = 0L,
            trackCount = al.optInt("size", 0),
            isAlbum = true
        )

        val list = mutableListOf<SongItem>()
        val tracksArr = root.optJSONArray("songs")
        if (tracksArr != null) {
            for (i in 0 until tracksArr.length()) {
                val t = tracksArr.optJSONObject(i) ?: continue
                parseSongItem(t, coverFallback = cover)?.let { list.add(it) }
            }
        }
        return ParsedDetail(header, list)
    }
    
    private data class ParsedDetail(
        val header: NeteaseCollectionHeader,
        val tracks: List<SongItem>,
        val trackIds: List<Long> = emptyList()
    )

    private fun parseSongItem(
        t: JSONObject,
        coverFallback: String? = null
    ): SongItem? {
        val id = t.optLong("id", 0L)
        val name = t.optString("name", "")
        if (id == 0L || name.isBlank()) return null

        val artistItems = parseNeteaseArtistSummaries(t.optJSONArray("ar"))
        val artist = artistItems.joinToString(" / ") { it.name }
        val al = t.optJSONObject("al") ?: t.optJSONObject("album")
        val albumName = al?.optString("name", "") ?: ""
        val albumId = al?.optLong("id", 0L) ?: 0L
        val cover = resolveNeteaseCollectionCoverUrl(
            primary = al?.optString("picUrl", ""),
            fallback = coverFallback
        )
        val duration = t.optLong("dt", 0L)

        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = "Netease$albumName",
            albumId = albumId,
            durationMs = duration,
            coverUrl = cover.takeIf { it.isNotBlank() },
            originalCoverUrl = cover.takeIf { it.isNotBlank() },
            channelId = "netease",
            audioId = id.toString(),
            neteaseArtists = artistItems
        )
    }

    private suspend fun fetchFullPlaylistTracks(
        trackIds: List<Long>,
        existing: List<SongItem>
    ): List<SongItem> = coroutineScope {
        val existingMap = existing.associateBy { it.id }
        val missingIds = trackIds.filterNot { existingMap.containsKey(it) }
        if (missingIds.isEmpty()) {
            return@coroutineScope trackIds.mapNotNull { existingMap[it] }
        }

        val pageSize = 300
        val pages = missingIds.chunked(pageSize)
        val deferred = pages.mapIndexed { index, ids ->
            async {
                index to client.getSongDetailCancellable(ids)
            }
        }
        val fetchedMap = mutableMapOf<Long, SongItem>()
        deferred.awaitAll()
            .sortedBy { it.first }
            .forEach { (_, raw) ->
                parseSongDetail(raw).forEach { song ->
                    fetchedMap[song.id] = song
                }
            }
        val merged = existingMap + fetchedMap
        trackIds.mapNotNull { merged[it] }
    }

    private fun parseSongDetail(raw: String): List<SongItem> {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        require(code == 200) { getApplication<Application>().getString(R.string.error_api_code, code) }
        val songs = root.optJSONArray("songs") ?: return emptyList()
        val out = mutableListOf<SongItem>()
        for (i in 0 until songs.length()) {
            val t = songs.optJSONObject(i) ?: continue
            parseSongItem(t)?.let { out.add(it) }
        }
        return out
    }
}
