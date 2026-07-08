package moe.ouom.neriplayer.ui.screen.host

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
 * File: moe.ouom.neriplayer.ui.screen.host/HomeHostScreen
 * Created: 2025/1/17
 */

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.playlist.usage.UsageEntry
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.YouTubeMusicPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.tab.HomeScreen
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.util.restoreBiliPlaylist
import moe.ouom.neriplayer.ui.util.restoreAlbumSummary
import moe.ouom.neriplayer.ui.util.restorePlaylistSummary
import moe.ouom.neriplayer.ui.util.restoreYouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.util.toSaveMap

// 用密封类承载四种目标
private sealed class HomeSelectedItem {
    data class Netease(val playlist: PlaylistSummary) : HomeSelectedItem()
    data class NeteaseAlbumList(val album: AlbumSummary) : HomeSelectedItem()
    data class Local(val playlistId: Long) : HomeSelectedItem()
    data class LocalArtist(val artistName: String) : HomeSelectedItem()
    data class Bili(val playlist: BiliPlaylist) : HomeSelectedItem()
    data class YouTubeMusic(val playlist: YouTubeMusicPlaylist) : HomeSelectedItem()
}

@Composable
fun HomeHostScreen(
    showContinueCard: Boolean = true,
    showTrendingCard: Boolean = true,
    showRadarCard: Boolean = true,
    showRecommendedCard: Boolean = true,
    offlineMode: Boolean = false,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    var selected by rememberSaveable(stateSaver = homeSelectedItemSaver) {
        mutableStateOf(null)
    }
    var skipDetailCloseAnimation by rememberSaveable { mutableStateOf(false) }

    fun closeSelectedDetail() {
        skipDetailCloseAnimation = false
        selected = null
    }

    fun closeDeletedLocalPlaylist() {
        skipDetailCloseAnimation = true
        selected = null
    }

    fun openLocalArtist(artistName: String) {
        val currentArtistName = (selected as? HomeSelectedItem.LocalArtist)?.artistName
        if (currentArtistName == artistName) return
        skipDetailCloseAnimation = false
        selected = HomeSelectedItem.LocalArtist(artistName)
    }

    LaunchedEffect(selected) {
        if (selected != null) {
            skipDetailCloseAnimation = false
        }
    }

    PredictiveBackHandler(enabled = selected != null) { progress ->
        try {
            progress.collect { }
            closeSelectedDetail()
        } catch (_: CancellationException) {
        }
    }

    val gridState = rememberSaveable(saver = LazyGridState.Saver) {
        LazyGridState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }

    Surface(color = Color.Transparent) {
        AnimatedContent(
            targetState = selected,
            label = "home_host_switch",
            transitionSpec = {
                if (targetState == null && skipDetailCloseAnimation) {
                    fadeIn(animationSpec = tween(0)) togetherWith fadeOut(animationSpec = tween(0))
                } else if (initialState == null && targetState != null) {
                    (slideInVertically(animationSpec = tween(220)) { it } + fadeIn()) togetherWith
                            (fadeOut(animationSpec = tween(160)))
                } else {
                    (slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()) togetherWith
                            (slideOutVertically(animationSpec = tween(240)) { it } + fadeOut())
                }.using(SizeTransform(clip = false))
            }
        ) { current ->
            if (current == null) {
                HomeScreen(
                    showContinueCard = showContinueCard,
                    showTrendingCard = showTrendingCard,
                    showRadarCard = showRadarCard,
                    showRecommendedCard = showRecommendedCard,
                    offlineMode = offlineMode,
                    gridState = gridState,
                    onItemClick = { pl ->
                        skipDetailCloseAnimation = false
                        AppContainer.playlistUsageRepo.recordOpen(
                            id = pl.id, name = pl.name, picUrl = pl.picUrl,
                            trackCount = pl.trackCount, source = "netease"
                        )
                        selected = HomeSelectedItem.Netease(pl)
                    },
                    onYouTubeMusicPlaylistClick = { pl ->
                        skipDetailCloseAnimation = false
                        AppContainer.playlistUsageRepo.recordOpen(
                            id = stableYouTubeMusicId(pl.playlistId.ifBlank { pl.browseId }),
                            name = pl.title,
                            picUrl = pl.coverUrl,
                            trackCount = pl.trackCount,
                            source = "youtubeMusic",
                            browseId = pl.browseId,
                            playlistId = pl.playlistId
                        )
                        selected = HomeSelectedItem.YouTubeMusic(pl)
                    },
                    onOpenRecent = { entry ->
                        skipDetailCloseAnimation = false
                        openRecent(entry) { next -> selected = next }
                    },
                    onSongClick = onSongClick    // 透传给 HomeScreen，点击推荐歌曲可直接播放
                )
            } else {
                when (current) {
                    is HomeSelectedItem.NeteaseAlbumList -> {
                        NeteaseAlbumDetailScreen(
                            album = current.album,
                            onBack = { selected = null },
                            onSongClick = onSongClick,
                            offlineMode = offlineMode
                        )
                    }
                    is HomeSelectedItem.Netease -> {
                        NeteasePlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onSongClick = onSongClick,
                            offlineMode = offlineMode
                        )
                    }
                    is HomeSelectedItem.Local -> {
                        LocalPlaylistDetailScreen(
                            playlistId = current.playlistId,
                            onBack = { closeSelectedDetail() },
                            onDeleted = { closeDeletedLocalPlaylist() },
                            onSongClick = onSongClick,
                            offlineMode = offlineMode
                        )
                    }
                    is HomeSelectedItem.LocalArtist -> {
                        LocalArtistDetailScreen(
                            artistName = current.artistName,
                            onBack = { closeSelectedDetail() },
                            onSongClick = onSongClick,
                            onArtistClick = ::openLocalArtist,
                            offlineMode = offlineMode
                        )
                    }
                    is HomeSelectedItem.Bili -> {
                        BiliPlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onPlayAudio = { videos, index ->
                                PlayerManager.playBiliVideoAsAudio(videos, index)
                            },
                            onPlayParts = { videoInfo, index, coverUrl ->
                                PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
                            },
                            offlineMode = offlineMode
                        )
                    }
                    is HomeSelectedItem.YouTubeMusic -> {
                        YouTubeMusicPlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onSongClick = onSongClick,
                            offlineMode = offlineMode
                        )
                    }
                }
            }
        }
    }
}

private val homeSelectedItemSaver = mapSaver<HomeSelectedItem?>(
    save = { item ->
        when (item) {
            null -> emptyMap()
            is HomeSelectedItem.Local -> hashMapOf(
                "type" to "local",
                "playlistId" to item.playlistId
            )
            is HomeSelectedItem.LocalArtist -> hashMapOf(
                "type" to "localArtist",
                "artistName" to item.artistName
            )
            is HomeSelectedItem.Netease -> hashMapOf(
                "type" to "netease",
                "playlist" to item.playlist.toSaveMap()
            )
            is HomeSelectedItem.NeteaseAlbumList -> hashMapOf(
                "type" to "neteaseAlbum",
                "album" to item.album.toSaveMap()
            )
            is HomeSelectedItem.Bili -> hashMapOf(
                "type" to "bili",
                "playlist" to item.playlist.toSaveMap()
            )
            is HomeSelectedItem.YouTubeMusic -> hashMapOf(
                "type" to "ytmusic",
                "playlist" to item.playlist.toSaveMap()
            )
        }
    },
    restore = { saved ->
        when (saved["type"] as? String) {
            null -> null
            "local" -> (saved["playlistId"] as? Number)?.toLong()?.let { HomeSelectedItem.Local(it) }
            "localArtist" -> (saved["artistName"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { HomeSelectedItem.LocalArtist(it) }
            "neteaseAlbum" -> restoreAlbumSummary(saved["album"] as? Map<*, *>)?.let { HomeSelectedItem.NeteaseAlbumList(it) }
            "netease" -> restorePlaylistSummary(saved["playlist"] as? Map<*, *>)?.let { HomeSelectedItem.Netease(it) }
            "bili" -> restoreBiliPlaylist(saved["playlist"] as? Map<*, *>)?.let { HomeSelectedItem.Bili(it) }
            "ytmusic" -> restoreYouTubeMusicPlaylist(saved["playlist"] as? Map<*, *>)?.let { HomeSelectedItem.YouTubeMusic(it) }
            else -> null
        }
    }
)

/** 根据 UsageEntry 分发到不同平台详情 */
private fun openRecent(
    entry: UsageEntry,
    onSelected: (HomeSelectedItem) -> Unit
) {
    when (entry.source.lowercase()) {
        "netease" -> {
            onSelected(
                HomeSelectedItem.Netease(
                    PlaylistSummary(
                        id = entry.id,
                        name = entry.name,
                        picUrl = entry.picUrl ?: "",
                        playCount = 0L,
                        trackCount = entry.trackCount
                    )
                )
            )
        }
        "neteasealbum" -> {
            onSelected(
                HomeSelectedItem.NeteaseAlbumList(
                    AlbumSummary(
                        id = entry.id,
                        name = entry.name,
                        picUrl = entry.picUrl ?: "",
                        size = entry.trackCount
                    )
                )
            )
        }
        "local" -> {
            onSelected(HomeSelectedItem.Local(entry.id))
        }
        PlaylistUsageRepository.SOURCE_LOCAL_ARTIST.lowercase() -> {
            onSelected(HomeSelectedItem.LocalArtist(entry.name))
        }
        "bili" -> {
            val kind = entry.subtype
                ?.let { runCatching { BiliPlaylistKind.valueOf(it) }.getOrNull() }
                ?: BiliPlaylistKind.CREATED_FAVORITE
            val bili = BiliPlaylist(
                mediaId = entry.id,
                title = entry.name,
                coverUrl = entry.picUrl ?: "",
                count = entry.trackCount,
                fid = entry.fid ?: 0L,
                mid = entry.mid ?: 0L,
                kind = kind
            )
            onSelected(HomeSelectedItem.Bili(bili))
        }
        "youtubemusic" -> {
            val resolvedBrowseId = entry.browseId
                ?.takeIf { it.isNotBlank() }
                ?: entry.playlistId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("VL")) it else "VL$it" }
                ?: return
            onSelected(
                HomeSelectedItem.YouTubeMusic(
                    YouTubeMusicPlaylist(
                        browseId = resolvedBrowseId,
                        playlistId = entry.playlistId.orEmpty().ifBlank {
                            if (resolvedBrowseId.startsWith("VL")) {
                                resolvedBrowseId.removePrefix("VL")
                            } else {
                                resolvedBrowseId
                            }
                        },
                        title = entry.name,
                        subtitle = "",
                        coverUrl = entry.picUrl ?: "",
                        trackCount = entry.trackCount
                    )
                )
            )
        }
        else -> {}
    }
}
