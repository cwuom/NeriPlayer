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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/YouTubeMusicPlaylistDetailViewModel
 * Updated: 2026/3/23
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.platform.youtube.CachedYouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.data.platform.youtube.CachedYouTubeMusicPlaylistTrack
import moe.ouom.neriplayer.data.platform.youtube.YouTubeMusicPlaylistCacheRepository
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.data.platform.youtube.youtubeMusicThumbnailUrl
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicTrack
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicUiDependencies

private const val YOUTUBE_MUSIC_PLAYLIST_SIGNATURE_TRACK_LIMIT = 100
internal const val YOUTUBE_MUSIC_PLAYLIST_CACHE_FRESHNESS_MS = 6L * 60L * 60L * 1000L

internal fun isYouTubeMusicPlaylistCacheFresh(
    cache: CachedYouTubeMusicPlaylistDetail,
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    return cache.tracks.isNotEmpty() &&
        cache.savedAtMs > 0L &&
        nowMs - cache.savedAtMs in 0 until YOUTUBE_MUSIC_PLAYLIST_CACHE_FRESHNESS_MS
}

data class YouTubeMusicPlaylistDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tracksUnavailable: Boolean = false,
    val playlist: YouTubeMusicPlaylist? = null,
    val tracks: List<SongItem> = emptyList(),
    val allTracksLoaded: Boolean = false
)

internal fun shouldMarkYouTubeMusicPlaylistTracksUnavailable(
    declaredTrackCount: Int,
    hasUsableTracks: Boolean
): Boolean = declaredTrackCount > 0 && !hasUsableTracks

internal fun retainYouTubeMusicPlaylistCreatorContext(
    playlist: YouTubeMusicPlaylist,
    previousPlaylist: YouTubeMusicPlaylist?
): YouTubeMusicPlaylist {
    return playlist.copy(
        creatorName = playlist.creatorName.trim().ifBlank {
            previousPlaylist?.creatorName?.trim().orEmpty()
        }
    )
}

internal fun resolveYouTubeMusicPlaylistTrackArtist(
    trackArtist: String,
    playlistCreatorName: String
): String {
    return trackArtist.trim().ifBlank { playlistCreatorName.trim() }
}

internal fun applyYouTubeMusicPlaylistCreatorContext(
    tracks: List<SongItem>,
    creatorName: String
): List<SongItem> {
    val resolvedCreatorName = creatorName.trim()
    if (resolvedCreatorName.isBlank()) {
        return tracks
    }
    return tracks.map { track ->
        if (track.artist.isNotBlank() || !track.customArtist.isNullOrBlank()) {
            track
        } else {
            track.copy(
                artist = resolvedCreatorName,
                originalArtist = track.originalArtist?.ifBlank { resolvedCreatorName }
                    ?: resolvedCreatorName
            )
        }
    }
}

class YouTubeMusicPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(YouTubeMusicPlaylistDetailUiState())
    val uiState: StateFlow<YouTubeMusicPlaylistDetailUiState> = _uiState

    private val localPlaylistRepo = LocalPlaylistRepository.getInstance(application)
    private val playlistCacheRepo: YouTubeMusicPlaylistCacheRepository = AppContainer.youtubeMusicPlaylistCacheRepo
    private var currentPlaylist: YouTubeMusicPlaylist? = null

    fun start(playlist: YouTubeMusicPlaylist, forceRefresh: Boolean = false) {
        val previous = _uiState.value.takeIf {
            it.playlist?.browseId == playlist.browseId
        }
        val resolvedPlaylist = retainYouTubeMusicPlaylistCreatorContext(
            playlist = playlist,
            previousPlaylist = previous?.playlist
        )
        currentPlaylist = resolvedPlaylist
        _uiState.value = YouTubeMusicPlaylistDetailUiState(
            loading = true,
            playlist = resolvedPlaylist,
            tracks = applyYouTubeMusicPlaylistCreatorContext(
                tracks = previous?.tracks.orEmpty(),
                creatorName = resolvedPlaylist.creatorName
            ),
            allTracksLoaded = previous?.allTracksLoaded == true
        )
        loadPlaylist(forceRefresh = forceRefresh)
    }

    fun retry() {
        currentPlaylist?.let { start(it, forceRefresh = true) }
    }

    private fun loadPlaylist(forceRefresh: Boolean) {
        val playlist = currentPlaylist ?: return
        val gateway = YouTubeMusicUiDependencies.libraryGateway
        if (gateway == null) {
            _uiState.value = _uiState.value.copy(
                loading = false,
                error = "YouTube Music gateway unavailable"
            )
            return
        }
        viewModelScope.launch {
            var previewPublished = false
            try {
                val cached = withContext(Dispatchers.IO) {
                    if (forceRefresh) null else playlistCacheRepo.read(playlist.browseId)
                }
                if (cached != null) {
                    publishCachedPlaylist(cached, playlist)
                    if (isYouTubeMusicPlaylistCacheFresh(cached)) {
                        return@launch
                    }
                    val preview = runCatching {
                        withContext(Dispatchers.IO) {
                            gateway.getPlaylistDetailPreview(playlist.browseId)
                        }
                    }.getOrElse {
                        publishCachedPlaylist(cached, playlist)
                        return@launch
                    }
                    if (!preview.hasUsableTracks() ||
                        preview.firstPageSignature() == cached.firstPageSignature
                    ) {
                        publishCachedPlaylist(cached, playlist)
                        return@launch
                    }
                    if (preview.fullyLoaded && preview.hasUsableTracks()) {
                        publishRemotePlaylist(
                            detail = preview,
                            fallback = playlist,
                            loading = false,
                            prefetchSource = "yt_playlist_detail_complete"
                        )
                        return@launch
                    }
                    if (preview.hasUsableTracks()) {
                        publishRemotePlaylist(
                            detail = preview,
                            fallback = playlist,
                            loading = false,
                            prefetchSource = "yt_playlist_detail_preview"
                        )
                        previewPublished = true
                    }
                } else {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            gateway.getPlaylistDetailPreview(playlist.browseId)
                        }
                    }.getOrNull()?.let { preview ->
                        if (preview.fullyLoaded && preview.hasUsableTracks()) {
                            publishRemotePlaylist(
                                detail = preview,
                                fallback = playlist,
                                loading = false,
                                prefetchSource = "yt_playlist_detail_complete"
                            )
                            return@launch
                        }
                        if (preview.hasUsableTracks()) {
                            publishRemotePlaylist(
                                detail = preview,
                                fallback = playlist,
                                loading = true,
                                prefetchSource = "yt_playlist_detail_preview"
                            )
                            previewPublished = true
                        }
                    }
                }

                val detail = withContext(Dispatchers.IO) {
                    gateway.getPlaylistDetail(playlist.browseId)
                }
                val hasUsableTracks = detail.hasUsableTracks()
                if (hasUsableTracks || !shouldMarkYouTubeMusicPlaylistTracksUnavailable(
                        declaredTrackCount = playlist.trackCount,
                        hasUsableTracks = hasUsableTracks
                    )
                ) {
                    publishRemotePlaylist(
                        detail = detail,
                        fallback = playlist,
                        loading = false,
                        prefetchSource = "yt_playlist_detail_load"
                    )
                } else if (cached != null) {
                    publishCachedPlaylist(cached, playlist)
                } else if (previewPublished) {
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        allTracksLoaded = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = null,
                        tracksUnavailable = true,
                        allTracksLoaded = false
                    )
                }
            } catch (error: Exception) {
                val cached = withContext(Dispatchers.IO) {
                    playlistCacheRepo.read(playlist.browseId)
                }
                if (cached != null) {
                    publishCachedPlaylist(cached, playlist)
                    return@launch
                }
                if (previewPublished) {
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = error.message ?: error.javaClass.simpleName
                    )
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    private suspend fun publishCachedPlaylist(
        cached: CachedYouTubeMusicPlaylistDetail,
        fallback: YouTubeMusicPlaylist,
        loading: Boolean = false
    ) {
        val localPlaylists = localPlaylistsSnapshot()
        val cachedState = withContext(Dispatchers.Default) {
            val cachedPlaylist = cached.toPlaylist(fallback)
            val cachedTracks = cached.tracks
                .map { it.toSongItem(cachedPlaylist) }
                .map { overlayUserEdits(it, localPlaylists) }
            YouTubeMusicPlaylistDetailUiState(
                loading = loading,
                playlist = cachedPlaylist,
                tracks = cachedTracks,
                allTracksLoaded = true
            )
        }
        _uiState.value = cachedState
        if (cachedState.tracks.isNotEmpty()) {
            PlayerManager.prefetchYouTubeQueueWindow(
                playlist = cachedState.tracks,
                startIndex = 0,
                source = "yt_playlist_detail_cached"
            )
        }
    }

    private suspend fun publishRemotePlaylist(
        detail: YouTubeMusicPlaylistDetail,
        fallback: YouTubeMusicPlaylist,
        loading: Boolean,
        prefetchSource: String
    ) {
        val resolvedPlaylist = detail.toPlaylist(fallback = fallback)
        val localPlaylists = localPlaylistsSnapshot()
        val resolvedTracks = withContext(Dispatchers.Default) {
            detail.tracks
                .map { it.toSongItem(resolvedPlaylist) }
                .map { overlayUserEdits(it, localPlaylists) }
        }
        if (detail.fullyLoaded && detail.hasUsableTracks()) {
            withContext(Dispatchers.IO) {
                cacheFullPlaylist(
                    browseId = fallback.browseId,
                    detail = detail,
                    playlist = resolvedPlaylist
                )
            }
        }
        _uiState.value = YouTubeMusicPlaylistDetailUiState(
            loading = loading,
            playlist = resolvedPlaylist,
            tracks = resolvedTracks,
            allTracksLoaded = detail.fullyLoaded
        )
        if (resolvedTracks.isEmpty()) {
            return
        }
        if (detail.fullyLoaded && !loading) {
            PlayerManager.prefetchYouTubeQueueWindow(
                playlist = resolvedTracks,
                startIndex = 0,
                source = prefetchSource
            )
        } else {
            PlayerManager.prefetchYouTubeQueueWindow(
                playlist = resolvedTracks,
                startIndex = 0,
                source = prefetchSource
            )
        }
    }

    private fun cacheFullPlaylist(
        browseId: String,
        detail: YouTubeMusicPlaylistDetail,
        playlist: YouTubeMusicPlaylist
    ) {
        playlistCacheRepo.save(
            CachedYouTubeMusicPlaylistDetail(
                browseId = browseId,
                playlistId = playlist.playlistId,
                title = playlist.title,
                subtitle = playlist.subtitle,
                creatorName = playlist.creatorName,
                coverUrl = playlist.coverUrl,
                trackCount = playlist.trackCount,
                firstPageSignature = detail.firstPageSignature(),
                tracks = detail.tracks.map { it.toCachedTrack() }
            )
        )
    }

    private fun YouTubeMusicPlaylistDetail.toPlaylist(
        fallback: YouTubeMusicPlaylist
    ): YouTubeMusicPlaylist {
        return fallback.copy(
            playlistId = playlistId.ifBlank { fallback.playlistId },
            title = title.ifBlank { fallback.title },
            subtitle = subtitle.ifBlank { fallback.subtitle },
            coverUrl = coverUrl.ifBlank { fallback.coverUrl },
            trackCount = trackCount.takeIf { it > 0 }
                ?: tracks.size.takeIf { it > 0 }
                ?: fallback.trackCount
        )
    }

    private fun CachedYouTubeMusicPlaylistDetail.toPlaylist(
        fallback: YouTubeMusicPlaylist
    ): YouTubeMusicPlaylist {
        return fallback.copy(
            playlistId = playlistId.ifBlank { fallback.playlistId },
            title = title.ifBlank { fallback.title },
            subtitle = subtitle.ifBlank { fallback.subtitle },
            creatorName = creatorName.orEmpty().trim().ifBlank {
                fallback.creatorName.trim()
            },
            coverUrl = coverUrl.ifBlank { fallback.coverUrl },
            trackCount = trackCount.takeIf { it > 0 }
                ?: tracks.size.takeIf { it > 0 }
                ?: fallback.trackCount
        )
    }

    private fun YouTubeMusicTrack.toSongItem(playlist: YouTubeMusicPlaylist): SongItem {
        val resolvedAlbum = albumName.ifBlank { playlist.title }
        val resolvedArtist = resolveYouTubeMusicPlaylistTrackArtist(
            trackArtist = artist,
            playlistCreatorName = playlist.creatorName
        )
        val resolvedCoverUrl = coverUrl.ifBlank {
            youtubeMusicThumbnailUrl(videoId)
        }.ifBlank { playlist.coverUrl }
        return SongItem(
            id = stableYouTubeMusicId(videoId),
            name = name,
            artist = resolvedArtist,
            album = resolvedAlbum,
            albumId = stableYouTubeMusicId(playlist.playlistId.ifBlank { videoId }),
            durationMs = durationMs,
            coverUrl = resolvedCoverUrl,
            mediaUri = buildYouTubeMusicMediaUri(
                videoId = videoId,
                playlistId = playlist.playlistId.ifBlank { null }
            ),
            originalName = name,
            originalArtist = resolvedArtist,
            originalCoverUrl = resolvedCoverUrl,
            channelId = "youtubeMusic",
            audioId = videoId,
            playlistContextId = playlist.playlistId.ifBlank { null }
        )
    }

    private fun CachedYouTubeMusicPlaylistTrack.toSongItem(playlist: YouTubeMusicPlaylist): SongItem {
        val resolvedAlbum = albumName.ifBlank { playlist.title }
        val resolvedArtist = resolveYouTubeMusicPlaylistTrackArtist(
            trackArtist = artist,
            playlistCreatorName = playlist.creatorName
        )
        val resolvedCoverUrl = coverUrl.ifBlank {
            youtubeMusicThumbnailUrl(videoId)
        }.ifBlank { playlist.coverUrl }
        return SongItem(
            id = stableYouTubeMusicId(videoId),
            name = name,
            artist = resolvedArtist,
            album = resolvedAlbum,
            albumId = stableYouTubeMusicId(playlist.playlistId.ifBlank { videoId }),
            durationMs = durationMs,
            coverUrl = resolvedCoverUrl,
            mediaUri = buildYouTubeMusicMediaUri(
                videoId = videoId,
                playlistId = playlist.playlistId.ifBlank { null }
            ),
            originalName = name,
            originalArtist = resolvedArtist,
            originalCoverUrl = resolvedCoverUrl,
            channelId = "youtubeMusic",
            audioId = videoId,
            playlistContextId = playlist.playlistId.ifBlank { null }
        )
    }

    private fun YouTubeMusicTrack.toCachedTrack(): CachedYouTubeMusicPlaylistTrack {
        return CachedYouTubeMusicPlaylistTrack(
            videoId = videoId,
            name = name,
            artist = artist,
            albumName = albumName,
            durationMs = durationMs,
            coverUrl = coverUrl
        )
    }

    private fun YouTubeMusicPlaylistDetail.firstPageSignature(): String {
        return buildString {
            append(playlistId)
            append('#')
            tracks.take(YOUTUBE_MUSIC_PLAYLIST_SIGNATURE_TRACK_LIMIT).forEach { track ->
                append(track.videoId)
                append('|')
            }
        }
    }

    private fun YouTubeMusicPlaylistDetail.hasUsableTracks(): Boolean {
        return tracks.any { it.videoId.isNotBlank() && it.name.isNotBlank() }
    }

    private suspend fun localPlaylistsSnapshot(): List<LocalPlaylist> {
        return if (localPlaylistRepo.awaitInitialized()) {
            localPlaylistRepo.playlists.value.toList()
        } else {
            emptyList()
        }
    }

    private fun overlayUserEdits(
        baseSong: SongItem,
        localPlaylists: List<LocalPlaylist>
    ): SongItem {
        val currentMatch = PlayerManager.currentSongFlow.value
            ?.takeIf { it.sameIdentityAs(baseSong) }
        if (currentMatch != null) {
            return mergeSongEdits(baseSong, currentMatch)
        }

        val playlistMatch = localPlaylists
            .asSequence()
            .flatMap { it.songs.asSequence() }
            .firstOrNull { it.sameIdentityAs(baseSong) }

        return if (playlistMatch != null) {
            mergeSongEdits(baseSong, playlistMatch)
        } else {
            baseSong
        }
    }

    private fun mergeSongEdits(baseSong: SongItem, editedSong: SongItem): SongItem {
        return baseSong.copy(
            matchedLyric = editedSong.matchedLyric ?: baseSong.matchedLyric,
            matchedTranslatedLyric = editedSong.matchedTranslatedLyric ?: baseSong.matchedTranslatedLyric,
            matchedLyricSource = editedSong.matchedLyricSource ?: baseSong.matchedLyricSource,
            matchedSongId = editedSong.matchedSongId ?: baseSong.matchedSongId,
            userLyricOffsetMs = editedSong.userLyricOffsetMs,
            customCoverUrl = editedSong.customCoverUrl,
            customName = editedSong.customName,
            customArtist = editedSong.customArtist,
            originalName = editedSong.originalName ?: baseSong.originalName,
            originalArtist = editedSong.originalArtist ?: baseSong.originalArtist,
            originalCoverUrl = editedSong.originalCoverUrl ?: baseSong.originalCoverUrl,
            originalLyric = editedSong.originalLyric ?: baseSong.originalLyric,
            originalTranslatedLyric = editedSong.originalTranslatedLyric ?: baseSong.originalTranslatedLyric
        )
    }
}
