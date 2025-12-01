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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import kotlinx.parcelize.Parcelize
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.tab.LibraryScreen
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.util.toSaveMap
import moe.ouom.neriplayer.ui.util.restoreBiliPlaylist
import moe.ouom.neriplayer.ui.util.restoreNeteaseAlbum
import moe.ouom.neriplayer.ui.util.restoreNeteasePlaylist

@Parcelize
sealed class LibrarySelectedItem : Parcelable {
    @Parcelize
    data class Local(val playlistId: Long) : LibrarySelectedItem()
    @Parcelize
    data class Netease(val playlist: NeteasePlaylist) : LibrarySelectedItem()
    @Parcelize
    data class NeteaseAlbumlist(val album: NeteaseAlbum) : LibrarySelectedItem()
    @Parcelize
    data class Bili(val playlist: BiliPlaylist) : LibrarySelectedItem()
}

@Composable
fun LibraryHostScreen(
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> },
    onOpenRecent: () -> Unit
) {
    var selected by rememberSaveable(stateSaver = librarySelectedItemSaver) {
        mutableStateOf<LibrarySelectedItem?>(null)
    }
    // 保存当前选中的标签页索引
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    BackHandler(enabled = selected != null) { selected = null }

    // 保存各个列表的滚动状态
    val localListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val neteaseAlbumSaver: Saver<LazyListState, *> = LazyListState.Saver
    val neteaseListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val biliListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val qqMusicListSaver: Saver<LazyListState, *> = LazyListState.Saver

    val localListState = rememberSaveable(saver = localListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val neteaseListState = rememberSaveable(saver = neteaseListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val neteaseAlbumState = rememberSaveable(saver = neteaseAlbumSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val biliListState = rememberSaveable(saver = biliListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val qqMusicListState = rememberSaveable(saver = qqMusicListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }

    Surface(color = Color.Transparent) {
        AnimatedContent(
            targetState = selected,
            label = "library_host_switch",
            transitionSpec = {
                if (initialState == null && targetState != null) {
                    (slideInVertically(animationSpec = tween(220)) { it } + fadeIn()) togetherWith
                            (fadeOut(animationSpec = tween(160)))
                } else {
                    (slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()) togetherWith
                            (slideOutVertically(animationSpec = tween(240)) { it } + fadeOut())
                }.using(SizeTransform(clip = false))
            }
        ) { current ->
            if (current == null) {
                LibraryScreen(
                    initialTabIndex = selectedTabIndex,
                    onTabIndexChange = { selectedTabIndex = it },
                    localListState = localListState,
                    neteaseAlbumState = neteaseAlbumState,
                    neteaseListState = neteaseListState,
                    biliListState = biliListState,
                    qqMusicListState = qqMusicListState,
                    onLocalPlaylistClick = { playlist -> 
                        selected = LibrarySelectedItem.Local(playlist.id)
                        AppContainer.playlistUsageRepo.recordOpen(
                            id = playlist.id, name = playlist.name, picUrl = playlist.songs.last().coverUrl,
                            trackCount = playlist.songs.size, source = "local"
                        )
                    },
                    onNeteasePlaylistClick = { playlist -> 
                        selected = LibrarySelectedItem.Netease(playlist)
                        AppContainer.playlistUsageRepo.recordOpen(
                            id = playlist.id, name = playlist.name, picUrl = playlist.picUrl,
                            trackCount = playlist.trackCount, source = "netease"
                        )
                    },
                    onNeteaseAlbumClick = { album -> 
                        selected = LibrarySelectedItem.NeteaseAlbumlist(album)
                        AppContainer.playlistUsageRepo.recordOpen(
                            id = album.id, name = album.name, picUrl = album.picUrl,
                            trackCount = album.size, source = "neteaseAlbum"
                        )
                    },
                    onBiliPlaylistClick = { playlist -> 
                        selected = LibrarySelectedItem.Bili(playlist)
                        AppContainer.playlistUsageRepo.recordOpen(
                            id = playlist.mediaId, name = playlist.title, picUrl = playlist.coverUrl,
                            trackCount = playlist.count, source = "bili", mid = playlist.mid, fid = playlist.fid
                        )
                    },
                    onOpenRecent = onOpenRecent
                )
            } else {
                when (current) {
                    is LibrarySelectedItem.Local -> {
                        LocalPlaylistDetailScreen(
                            playlistId = current.playlistId,
                            onBack = { selected = null },
                            onDeleted = { selected = null },
                            onSongClick = onSongClick
                        )
                    }
                    is LibrarySelectedItem.NeteaseAlbumlist -> {
                        NeteaseAlbumDetailScreen(
                            onBack = { selected = null },
                            onSongClick = onSongClick,
                            album = current.album
                        )
                    }
                    is LibrarySelectedItem.Netease -> {
                        NeteasePlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onSongClick = onSongClick
                        )
                    }
                    is LibrarySelectedItem.Bili -> {
                        BiliPlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onPlayAudio = { videos, index ->
                                PlayerManager.playBiliVideoAsAudio(videos, index)
                            },
                            onPlayParts = onPlayParts
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
            null -> emptyMap<String, Any?>()
            is LibrarySelectedItem.Local -> hashMapOf(
                "type" to "local",
                "playlistId" to item.playlistId
            )
            is LibrarySelectedItem.NeteaseAlbumlist -> hashMapOf(
                "type" to "neteaseAlbum",
                "album" to item.album.toSaveMap()
            )
            is LibrarySelectedItem.Netease -> hashMapOf(
                "type" to "netease",
                "playlist" to item.playlist.toSaveMap()
            )
            is LibrarySelectedItem.Bili -> hashMapOf(
                "type" to "bili",
                "playlist" to item.playlist.toSaveMap()
            )
        }
    },
    restore = { saved ->
        when (saved["type"] as? String) {
            null -> null
            "local" -> (saved["playlistId"] as? Number)?.toLong()?.let { LibrarySelectedItem.Local(it) }
            "neteaseAlbum" -> restoreNeteaseAlbum(saved["album"] as? Map<*, *>)?.let { LibrarySelectedItem.NeteaseAlbumlist(it) }
            "netease" -> restoreNeteasePlaylist(saved["playlist"] as? Map<*, *>)?.let { LibrarySelectedItem.Netease(it) }
            "bili" -> restoreBiliPlaylist(saved["playlist"] as? Map<*, *>)?.let { LibrarySelectedItem.Bili(it) }
            else -> null
        }
    }
)
