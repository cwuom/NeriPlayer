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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.platform.netease.CachedNeteaseArtist
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistHeader
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistTrack
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.artist.parseNeteaseArtistSummaries
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
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

class NeteaseCollectionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AppContainer.neteaseClient
    private val cookieRepo = AppContainer.neteaseCookieRepo
    private val playlistCacheRepo = AppContainer.neteasePlaylistCacheRepo

    private val _uiState = MutableStateFlow(NeteaseCollectionDetailUiState())
    val uiState: StateFlow<NeteaseCollectionDetailUiState> = _uiState

    private var playlistId: Long = 0L
    private var currentPlaylist: PlaylistSummary? = null

    init {
        viewModelScope.launch {
            cookieRepo.cookieFlow.collect { saved ->
                val cookies = saved.toMutableMap()
                cookies.putIfAbsent("os", "pc")

                val loggedIn = !cookies["MUSIC_U"].isNullOrBlank()
                val hasCsrf = !cookies["__csrf"].isNullOrBlank()
                if (loggedIn && !hasCsrf) {
                    withContext(Dispatchers.IO) {
                        try {
                            NPLogger.d(TAG_PD, "csrf missing after login, preheating weapi session…")
                            client.ensureWeapiSession()
                        } catch (e: Exception) {
                            NPLogger.w(TAG_PD, "ensureWeapiSession failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun startPlaylist(playlist: PlaylistSummary, forceRefresh: Boolean = false) {
        currentPlaylist = playlist
        playlistId = playlist.id
        val previous = _uiState.value.takeIf {
            val header = it.header
            forceRefresh && header?.id == playlist.id && header?.isAlbum == false
        }

        // 用入口数据把 header 预填
        _uiState.value = NeteaseCollectionDetailUiState(
            loading = true,
            header = previous?.header ?: NeteaseCollectionHeader(
                id = playlist.id,
                isAlbum = false,
                name = playlist.name,
                coverUrl = toHttps(playlist.picUrl) ?: "",
                playCount = playlist.playCount,
                trackCount = playlist.trackCount
            ),
            tracks = previous?.tracks.orEmpty()
        )

        viewModelScope.launch {
            loadPlaylist(playlist, forceRefresh)
        }
    }

    private suspend fun loadPlaylist(playlist: PlaylistSummary, forceRefresh: Boolean) {
        val cached = withContext(Dispatchers.IO) {
            if (forceRefresh) null else playlistCacheRepo.read(playlist.id)
        }
        if (cached != null) {
            publishCachedPlaylist(cached, playlist, loading = true)
        }

        try {
            // 再读一次当前持久化 Cookie，并注入
            val cookies = withContext(Dispatchers.IO) { cookieRepo.getCookiesOnce() }.toMutableMap()
            cookies.putIfAbsent("os", "pc")

            val raw = withContext(Dispatchers.IO) { client.getPlaylistDetail(playlist.id) }
            NPLogger.d(TAG_PD, "detail head=${raw.take(500)}")

            val parsed = parseDetailFromPlaylist(raw)
            if (!forceRefresh && cached != null && shouldReuseCachedPlaylist(cached, parsed)) {
                publishCachedPlaylist(cached, playlist)
                NPLogger.d(
                    TAG_PD,
                    "reuse NetEase playlist cache: playlistId=${playlist.id}, count=${cached.tracks.size}"
                )
                return
            }

            val tracks = resolvePlaylistTracks(parsed)
            _uiState.value = NeteaseCollectionDetailUiState(
                loading = false,
                error = null,
                header = parsed.header,
                tracks = tracks
            )
            withContext(Dispatchers.IO) {
                playlistCacheRepo.save(parsed.toCache(tracks))
            }
        } catch (e: IOException) {
            val fallback = cached ?: withContext(Dispatchers.IO) { playlistCacheRepo.read(playlist.id) }
            if (fallback != null) {
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
            val fallback = cached ?: withContext(Dispatchers.IO) { playlistCacheRepo.read(playlist.id) }
            if (fallback != null) {
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

    private suspend fun publishCachedPlaylist(
        cached: CachedNeteasePlaylistDetail,
        fallback: PlaylistSummary,
        loading: Boolean = false
    ) {
        _uiState.value = withContext(Dispatchers.Default) {
            NeteaseCollectionDetailUiState(
                loading = loading,
                error = null,
                header = cached.header.toHeader(fallback),
                tracks = cached.tracks.map { it.toSongItem() }
            )
        }
    }

    private fun shouldReuseCachedPlaylist(
        cached: CachedNeteasePlaylistDetail,
        parsed: ParsedDetail
    ): Boolean {
        val expectedTrackCount = parsed.expectedTrackCount()
        val cachedTrackCount = cached.header.trackCount.takeIf { it > 0 } ?: cached.tracks.size
        if (expectedTrackCount > 0 && cachedTrackCount != expectedTrackCount) return false
        if (expectedTrackCount > 0 && cached.tracks.size < expectedTrackCount) return false
        return cached.recentTrackSignature == parsed.recentTrackSignature()
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

    private fun ParsedDetail.toCache(tracks: List<SongItem>): CachedNeteasePlaylistDetail {
        return CachedNeteasePlaylistDetail(
            playlistId = header.id,
            header = header.toCachedHeader(),
            recentTrackSignature = recentTrackSignature(),
            tracks = tracks.map { it.toCachedTrack() }
        )
    }

    private fun CachedNeteasePlaylistHeader.toHeader(fallback: PlaylistSummary): NeteaseCollectionHeader {
        return NeteaseCollectionHeader(
            id = id,
            isAlbum = false,
            name = name.ifBlank { fallback.name },
            coverUrl = coverUrl.ifBlank { toHttps(fallback.picUrl) ?: "" },
            playCount = playCount.takeIf { it > 0L } ?: fallback.playCount,
            trackCount = trackCount.takeIf { it > 0 } ?: fallback.trackCount
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
        currentPlaylist = null
        playlistId = album.id

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

        viewModelScope.launch {
            try {
                // 再读一次当前持久化 Cookie，并注入
                val cookies = withContext(Dispatchers.IO) { cookieRepo.getCookiesOnce() }.toMutableMap()
                cookies.putIfAbsent("os", "pc")

                val raw = withContext(Dispatchers.IO) { client.getAlbumDetail(playlistId) }
                NPLogger.d(TAG_PD, "detail head=${raw.take(500)}")

                val (header, tracks) = parseDetailFromAlbum(
                    raw = raw,
                    coverFallback = album.picUrl
                )

                _uiState.value = NeteaseCollectionDetailUiState(
                    loading = false,
                    error = null,
                    header = header,
                    tracks = tracks
                )
            } catch (e: IOException) {
                NPLogger.e(TAG_PD, "Network/Server error", e)
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "Network or server error: ${e.message ?: e.javaClass.simpleName}"  // Localized in UI
                )
            } catch (e: Exception) {
                NPLogger.e(TAG_PD, "Unexpected error", e)
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "Parse/unknown error: ${e.message ?: e.javaClass.simpleName}"  // Localized in UI
                )
            }
        }
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
            async(Dispatchers.IO) {
                index to client.getSongDetail(ids)
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
