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
 * File: moe.ouom.neriplayer.ui.screen.host/LibraryHostScreen
 * Created: 2025/1/17
 */

import android.os.Parcelable
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import kotlinx.parcelize.Parcelize
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.ui.screen.artist.NeteaseArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.YouTubeMusicPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.tab.LibraryTab
import moe.ouom.neriplayer.ui.screen.tab.LibraryScreen
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.util.toSaveMap
import moe.ouom.neriplayer.ui.util.restoreBiliPlaylist
import moe.ouom.neriplayer.ui.util.restoreAlbumSummary
import moe.ouom.neriplayer.ui.util.restorePlaylistSummary
import moe.ouom.neriplayer.ui.util.restoreYouTubeMusicPlaylist

@Parcelize
sealed class LibrarySelectedItem : Parcelable {
    @Parcelize
    data class Local(val playlistId: Long) : LibrarySelectedItem()
    @Parcelize
    data class LocalArtist(val artistName: String) : LibrarySelectedItem()
    @Parcelize
    data class Netease(val playlist: PlaylistSummary) : LibrarySelectedItem()
    @Parcelize
    data class NeteaseAlbum(val album: AlbumSummary) : LibrarySelectedItem()
    @Parcelize
    data class NeteaseArtist(val artist: NeteaseArtistSummary) : LibrarySelectedItem()
    @Parcelize
    data class NeteaseArtistAlbum(
        val artist: NeteaseArtistSummary,
        val album: AlbumSummary
    ) : LibrarySelectedItem()
    @Parcelize
    data class Bili(val playlist: BiliPlaylist) : LibrarySelectedItem()
    @Parcelize
    data class YouTubeMusic(val playlist: YouTubeMusicPlaylist) : LibrarySelectedItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHostScreen(
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> },
    onOpenRecent: () -> Unit,
    onOpenStats: () -> Unit = {},
    offlineMode: Boolean = false
) {
    var selected by rememberSaveable(stateSaver = librarySelectedItemSaver) {
        mutableStateOf(null)
    }
    var skipDetailCloseAnimation by rememberSaveable { mutableStateOf(false) }
    var pendingLocalListRestoreIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingLocalListRestoreOffset by rememberSaveable { mutableStateOf(0) }
    // 保存当前选中的标签页类型，避免国际化切换后索引错位
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.LOCAL) }
    val libraryStateHolder = rememberSaveableStateHolder()

    fun closeSelectedDetail() {
        skipDetailCloseAnimation = false
        selected = when (val current = selected) {
            is LibrarySelectedItem.NeteaseArtistAlbum -> LibrarySelectedItem.NeteaseArtist(current.artist)
            else -> null
        }
    }

    fun openNeteaseArtist(artist: NeteaseArtistSummary) {
        val currentArtist = (selected as? LibrarySelectedItem.NeteaseArtist)?.artist
        if (currentArtist?.id == artist.id) return
        skipDetailCloseAnimation = false
        selected = LibrarySelectedItem.NeteaseArtist(artist)
    }

    fun closeDeletedLocalPlaylist() {
        skipDetailCloseAnimation = true
        selected = null
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

    // 保存各个列表的滚动状态
    val localListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val favoriteListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val neteaseAlbumSaver: Saver<LazyListState, *> = LazyListState.Saver
    val neteaseListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val youtubeMusicListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val biliListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val qqMusicListSaver: Saver<LazyListState, *> = LazyListState.Saver

    val localListState = rememberSaveable(saver = localListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val favoriteListState = rememberSaveable(saver = favoriteListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val neteaseListState = rememberSaveable(saver = neteaseListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val neteaseAlbumState = rememberSaveable(saver = neteaseAlbumSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val youtubeMusicListState = rememberSaveable(saver = youtubeMusicListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val biliListState = rememberSaveable(saver = biliListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val qqMusicListState = rememberSaveable(saver = qqMusicListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val topAppBarState = rememberTopAppBarState()
    val context = LocalContext.current

    LaunchedEffect(selected, selectedTab, pendingLocalListRestoreIndex) {
        val restoreIndex = pendingLocalListRestoreIndex ?: return@LaunchedEffect
        if (selected != null || selectedTab != LibraryTab.LOCAL) return@LaunchedEffect

        var attempts = 0
        while (localListState.layoutInfo.totalItemsCount == 0 && attempts < 4) {
            withFrameNanos { }
            attempts++
        }

        val itemCount = localListState.layoutInfo.totalItemsCount
        if (itemCount > 0) {
            val safeIndex = restoreIndex.coerceAtMost(itemCount - 1)
            localListState.scrollToItem(safeIndex, pendingLocalListRestoreOffset)
        }
        pendingLocalListRestoreIndex = null
        pendingLocalListRestoreOffset = 0
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        AnimatedContent(
            targetState = selected,
            modifier = Modifier.fillMaxSize(),
            label = "library_host_switch",
            transitionSpec = {
                if (targetState == null && skipDetailCloseAnimation) {
                    fadeIn(animationSpec = tween(0)) togetherWith fadeOut(animationSpec = tween(0))
                } else if (initialState == null && targetState != null) {
                    (slideInVertically(animationSpec = tween(220)) { it } + fadeIn()) togetherWith
                        fadeOut(animationSpec = tween(160))
                } else if (targetState == null) {
                    fadeIn(animationSpec = tween(160)) togetherWith
                        (slideOutVertically(animationSpec = tween(240)) { it } + fadeOut())
                } else {
                    (slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()) togetherWith
                        (slideOutVertically(animationSpec = tween(240)) { it } + fadeOut())
                }.using(SizeTransform(clip = false))
            }
        ) { current ->
            if (current == null) {
                libraryStateHolder.SaveableStateProvider("library_screen") {
                    LibraryScreen(
                        initialTab = selectedTab,
                        onTabChange = { selectedTab = it },
                        localListState = localListState,
                        favoriteListState = favoriteListState,
                        neteaseAlbumState = neteaseAlbumState,
                        neteaseListState = neteaseListState,
                        youtubeMusicListState = youtubeMusicListState,
                        biliListState = biliListState,
                        qqMusicListState = qqMusicListState,
                        topAppBarState = topAppBarState,
                        offlineMode = offlineMode,
                        onLocalPlaylistClick = { playlist ->
                            skipDetailCloseAnimation = false
                            pendingLocalListRestoreIndex = localListState.firstVisibleItemIndex
                            pendingLocalListRestoreOffset = localListState.firstVisibleItemScrollOffset
                            selected = LibrarySelectedItem.Local(playlist.id)
                            AppContainer.playlistUsageRepo.recordOpen(
                                id = playlist.id,
                                name = playlist.name,
                                picUrl = playlist.displayCoverUrl(context),
                                trackCount = playlist.songs.size,
                                source = "local"
                            )
                        },
                        onLocalArtistClick = { artist ->
                            skipDetailCloseAnimation = false
                            pendingLocalListRestoreIndex = localListState.firstVisibleItemIndex
                            pendingLocalListRestoreOffset = localListState.firstVisibleItemScrollOffset
                            selected = LibrarySelectedItem.LocalArtist(artist.name)
                            AppContainer.playlistUsageRepo.recordOpen(
                                id = artist.id,
                                name = artist.name,
                                picUrl = artist.coverSong?.displayCoverUrl(context),
                                trackCount = artist.songs.size,
                                source = PlaylistUsageRepository.SOURCE_LOCAL_ARTIST
                            )
                        },
                        onNeteasePlaylistClick = { playlist ->
                            skipDetailCloseAnimation = false
                            selected = LibrarySelectedItem.Netease(playlist)
                            AppContainer.playlistUsageRepo.recordOpen(
                                id = playlist.id,
                                name = playlist.name,
                                picUrl = playlist.picUrl,
                                trackCount = playlist.trackCount,
                                source = "netease"
                            )
                        },
                        onNeteaseAlbumClick = { album ->
                            skipDetailCloseAnimation = false
                            selected = LibrarySelectedItem.NeteaseAlbum(album)
                            AppContainer.playlistUsageRepo.recordOpen(
                                id = album.id,
                                name = album.name,
                                picUrl = album.picUrl,
                                trackCount = album.size,
                                source = "neteaseAlbum"
                            )
                        },
                        onNeteaseArtistClick = { artist ->
                            openNeteaseArtist(artist)
                        },
                        onYouTubeMusicPlaylistClick = { playlist ->
                            skipDetailCloseAnimation = false
                            selected = LibrarySelectedItem.YouTubeMusic(playlist)
                            AppContainer.playlistUsageRepo.recordOpen(
                                id = stableYouTubeMusicId(playlist.playlistId.ifBlank { playlist.browseId }),
                                name = playlist.title,
                                picUrl = playlist.coverUrl,
                                trackCount = playlist.trackCount,
                                source = "youtubeMusic",
                                browseId = playlist.browseId,
                                playlistId = playlist.playlistId
                            )
                        },
                        onBiliPlaylistClick = { playlist ->
                            skipDetailCloseAnimation = false
                            selected = LibrarySelectedItem.Bili(playlist)
                            AppContainer.playlistUsageRepo.recordOpen(
                                id = playlist.mediaId,
                                name = playlist.title,
                                picUrl = playlist.coverUrl,
                                trackCount = playlist.count,
                                source = "bili",
                                mid = playlist.mid,
                                fid = playlist.fid,
                                subtype = playlist.kind.name
                            )
                        },
                        onOpenRecent = onOpenRecent,
                        onOpenStats = onOpenStats
                    )
                }
            } else {
                when (current) {
                    is LibrarySelectedItem.Local -> {
                        LocalPlaylistDetailScreen(
                            playlistId = current.playlistId,
                            onBack = { closeSelectedDetail() },
                            onDeleted = { closeDeletedLocalPlaylist() },
                            onSongClick = onSongClick,
                            offlineMode = offlineMode
                        )
                    }

                    is LibrarySelectedItem.LocalArtist -> {
                        LocalArtistDetailScreen(
                            artistName = current.artistName,
                            onBack = { closeSelectedDetail() },
                            onSongClick = onSongClick,
                            offlineMode = offlineMode
                        )
                    }

                    is LibrarySelectedItem.NeteaseAlbum -> {
                        NeteaseAlbumDetailScreen(
                            onBack = { selected = null },
                            onSongClick = onSongClick,
                            album = current.album,
                            offlineMode = offlineMode
                        )
                    }

                    is LibrarySelectedItem.Netease -> {
                        NeteasePlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onSongClick = onSongClick,
                            offlineMode = offlineMode
                        )
                    }

                    is LibrarySelectedItem.NeteaseArtist -> {
                        libraryStateHolder.SaveableStateProvider("netease_artist_${current.artist.id}") {
                            NeteaseArtistDetailScreen(
                                artist = current.artist,
                                onBack = { selected = null },
                                onSongClick = onSongClick,
                                offlineMode = offlineMode,
                                onAlbumClick = { album ->
                                    selected = LibrarySelectedItem.NeteaseArtistAlbum(current.artist, album)
                                }
                            )
                        }
                    }

                    is LibrarySelectedItem.NeteaseArtistAlbum -> {
                        NeteaseAlbumDetailScreen(
                            onBack = { selected = LibrarySelectedItem.NeteaseArtist(current.artist) },
                            onSongClick = onSongClick,
                            album = current.album,
                            offlineMode = offlineMode
                        )
                    }

                    is LibrarySelectedItem.YouTubeMusic -> {
                        YouTubeMusicPlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onSongClick = onSongClick,
                            offlineMode = offlineMode
                        )
                    }

                    is LibrarySelectedItem.Bili -> {
                        BiliPlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onPlayAudio = { videos, index ->
                                PlayerManager.playBiliVideoAsAudio(videos, index)
                            },
                            onPlayParts = onPlayParts,
                            offlineMode = offlineMode
                        )
                    }
                }
            }
        }
    }
}

private val librarySelectedItemSaver = mapSaver<LibrarySelectedItem?>(
    save = { item ->
        when (item) {
            null -> emptyMap()
            is LibrarySelectedItem.Local -> hashMapOf(
                "type" to "local",
                "playlistId" to item.playlistId
            )
            is LibrarySelectedItem.LocalArtist -> hashMapOf(
                "type" to "localArtist",
                "artistName" to item.artistName
            )
            is LibrarySelectedItem.NeteaseAlbum -> hashMapOf(
                "type" to "neteaseAlbum",
                "album" to item.album.toSaveMap()
            )
            is LibrarySelectedItem.Netease -> hashMapOf(
                "type" to "netease",
                "playlist" to item.playlist.toSaveMap()
            )
            is LibrarySelectedItem.NeteaseArtist -> hashMapOf(
                "type" to "neteaseArtist",
                "artistId" to item.artist.id,
                "artistName" to item.artist.name
            )
            is LibrarySelectedItem.NeteaseArtistAlbum -> hashMapOf(
                "type" to "neteaseArtistAlbum",
                "artistId" to item.artist.id,
                "artistName" to item.artist.name,
                "album" to item.album.toSaveMap()
            )
            is LibrarySelectedItem.Bili -> hashMapOf(
                "type" to "bili",
                "playlist" to item.playlist.toSaveMap()
            )
            is LibrarySelectedItem.YouTubeMusic -> hashMapOf(
                "type" to "ytmusic",
                "playlist" to item.playlist.toSaveMap()
            )
        }
    },
    restore = { saved ->
        when (saved["type"] as? String) {
            null -> null
            "local" -> (saved["playlistId"] as? Number)?.toLong()?.let { LibrarySelectedItem.Local(it) }
            "localArtist" -> (saved["artistName"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { LibrarySelectedItem.LocalArtist(it) }
            "neteaseAlbum" -> restoreAlbumSummary(saved["album"] as? Map<*, *>)?.let { LibrarySelectedItem.NeteaseAlbum(it) }
            "netease" -> restorePlaylistSummary(saved["playlist"] as? Map<*, *>)?.let { LibrarySelectedItem.Netease(it) }
            "neteaseArtist" -> restoreNeteaseArtistSummary(
                id = (saved["artistId"] as? Number)?.toLong(),
                name = saved["artistName"] as? String
            )?.let { LibrarySelectedItem.NeteaseArtist(it) }
            "neteaseArtistAlbum" -> {
                val artist = restoreNeteaseArtistSummary(
                    id = (saved["artistId"] as? Number)?.toLong(),
                    name = saved["artistName"] as? String
                )
                val album = restoreAlbumSummary(saved["album"] as? Map<*, *>)
                if (artist != null && album != null) {
                    LibrarySelectedItem.NeteaseArtistAlbum(artist, album)
                } else {
                    null
                }
            }
            "bili" -> restoreBiliPlaylist(saved["playlist"] as? Map<*, *>)?.let { LibrarySelectedItem.Bili(it) }
            "ytmusic" -> restoreYouTubeMusicPlaylist(saved["playlist"] as? Map<*, *>)?.let { LibrarySelectedItem.YouTubeMusic(it) }
            else -> null
        }
    }
)

private fun restoreNeteaseArtistSummary(id: Long?, name: String?): NeteaseArtistSummary? {
    val resolvedId = id?.takeIf { it > 0L } ?: return null
    val resolvedName = name?.takeIf { it.isNotBlank() } ?: return null
    return NeteaseArtistSummary(id = resolvedId, name = resolvedName)
}
