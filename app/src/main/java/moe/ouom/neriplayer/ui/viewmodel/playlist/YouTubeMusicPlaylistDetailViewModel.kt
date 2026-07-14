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
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicTrack
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicUiDependencies

private const val YOUTUBE_MUSIC_PLAYLIST_SIGNATURE_TRACK_LIMIT = 100

data class YouTubeMusicPlaylistDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val playlist: YouTubeMusicPlaylist? = null,
    val tracks: List<SongItem> = emptyList(),
    val allTracksLoaded: Boolean = false
)

class YouTubeMusicPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(YouTubeMusicPlaylistDetailUiState())
    val uiState: StateFlow<YouTubeMusicPlaylistDetailUiState> = _uiState

    private val localPlaylistRepo = LocalPlaylistRepository.getInstance(application)
    private val playlistCacheRepo: YouTubeMusicPlaylistCacheRepository = AppContainer.youtubeMusicPlaylistCacheRepo
    private var currentPlaylist: YouTubeMusicPlaylist? = null

    fun start(playlist: YouTubeMusicPlaylist, forceRefresh: Boolean = false) {
        currentPlaylist = playlist
        val previous = _uiState.value.takeIf {
            forceRefresh && it.playlist?.browseId == playlist.browseId
        }
        _uiState.value = YouTubeMusicPlaylistDetailUiState(
            loading = true,
            playlist = previous?.playlist ?: playlist,
            tracks = previous?.tracks.orEmpty(),
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
                    publishCachedPlaylist(cached, playlist, loading = true)
                    val preview = runCatching {
                        withContext(Dispatchers.IO) {
                            gateway.getPlaylistDetailPreview(playlist.browseId)
                        }
                    }.getOrElse {
                        publishCachedPlaylist(cached, playlist)
                        return@launch
                    }
                    if (preview.firstPageSignature() == cached.firstPageSignature) {
                        publishCachedPlaylist(cached, playlist)
                        return@launch
                    }
                    if (preview.fullyLoaded) {
                        publishRemotePlaylist(
                            detail = preview,
                            fallback = playlist,
                            loading = false,
                            prefetchSource = "yt_playlist_detail_complete"
                        )
                        return@launch
                    }
                    publishRemotePlaylist(
                        detail = preview,
                        fallback = playlist,
                        loading = true,
                        prefetchSource = "yt_playlist_detail_preview"
                    )
                    previewPublished = true
                } else {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            gateway.getPlaylistDetailPreview(playlist.browseId)
                        }
                    }.getOrNull()?.let { preview ->
                        if (preview.fullyLoaded) {
                            publishRemotePlaylist(
                                detail = preview,
                                fallback = playlist,
                                loading = false,
                                prefetchSource = "yt_playlist_detail_complete"
                            )
                            return@launch
                        }
                        publishRemotePlaylist(
                            detail = preview,
                            fallback = playlist,
                            loading = true,
                            prefetchSource = "yt_playlist_detail_preview"
                        )
                        previewPublished = true
                    }
                }

                val detail = withContext(Dispatchers.IO) {
                    gateway.getPlaylistDetail(playlist.browseId)
                }
                publishRemotePlaylist(
                    detail = detail,
                    fallback = playlist,
                    loading = false,
                    prefetchSource = "yt_playlist_detail_load"
                )
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
        val cachedState = withContext(Dispatchers.Default) {
            val cachedPlaylist = cached.toPlaylist(fallback)
            val cachedTracks = cached.tracks
                .map { it.toSongItem(cachedPlaylist) }
                .map(::overlayUserEdits)
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
        val resolvedTracks = withContext(Dispatchers.Default) {
            detail.tracks
                .map { it.toSongItem(resolvedPlaylist) }
                .map(::overlayUserEdits)
        }
        if (detail.fullyLoaded) {
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
            coverUrl = coverUrl.ifBlank { fallback.coverUrl },
            trackCount = trackCount.takeIf { it > 0 }
                ?: tracks.size.takeIf { it > 0 }
                ?: fallback.trackCount
        )
    }

    private fun YouTubeMusicTrack.toSongItem(playlist: YouTubeMusicPlaylist): SongItem {
        val resolvedAlbum = albumName.ifBlank { playlist.title }
        return SongItem(
            id = stableYouTubeMusicId(videoId),
            name = name,
            artist = artist,
            album = resolvedAlbum,
            albumId = stableYouTubeMusicId(playlist.playlistId.ifBlank { videoId }),
            durationMs = durationMs,
            coverUrl = coverUrl.ifBlank { playlist.coverUrl },
            mediaUri = buildYouTubeMusicMediaUri(
                videoId = videoId,
                playlistId = playlist.playlistId.ifBlank { null }
            ),
            originalName = name,
            originalArtist = artist,
            originalCoverUrl = coverUrl.ifBlank { playlist.coverUrl },
            channelId = "youtubeMusic",
            audioId = videoId,
            playlistContextId = playlist.playlistId.ifBlank { null }
        )
    }

    private fun CachedYouTubeMusicPlaylistTrack.toSongItem(playlist: YouTubeMusicPlaylist): SongItem {
        val resolvedAlbum = albumName.ifBlank { playlist.title }
        return SongItem(
            id = stableYouTubeMusicId(videoId),
            name = name,
            artist = artist,
            album = resolvedAlbum,
            albumId = stableYouTubeMusicId(playlist.playlistId.ifBlank { videoId }),
            durationMs = durationMs,
            coverUrl = coverUrl.ifBlank { playlist.coverUrl },
            mediaUri = buildYouTubeMusicMediaUri(
                videoId = videoId,
                playlistId = playlist.playlistId.ifBlank { null }
            ),
            originalName = name,
            originalArtist = artist,
            originalCoverUrl = coverUrl.ifBlank { playlist.coverUrl },
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
            append(trackCount)
            append('#')
            append(title)
            append('#')
            append(subtitle)
            append('#')
            append(coverUrl)
            append('#')
            tracks.take(YOUTUBE_MUSIC_PLAYLIST_SIGNATURE_TRACK_LIMIT).forEach { track ->
                append(track.videoId)
                append(':')
                append(track.name)
                append(':')
                append(track.artist)
                append(':')
                append(track.albumName)
                append(':')
                append(track.durationMs)
                append('|')
            }
        }
    }

    private fun overlayUserEdits(baseSong: SongItem): SongItem {
        val currentMatch = PlayerManager.currentSongFlow.value
            ?.takeIf { it.sameIdentityAs(baseSong) }
        if (currentMatch != null) {
            return mergeSongEdits(baseSong, currentMatch)
        }

        val playlistMatch = localPlaylistRepo.playlists.value
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
