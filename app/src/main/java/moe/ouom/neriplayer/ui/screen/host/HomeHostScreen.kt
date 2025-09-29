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

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.UsageEntry
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.tab.HomeScreen
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist

// 用密封类承载三种目标
private sealed class HomeSelectedItem {
    data class Netease(val playlist: NeteasePlaylist) : HomeSelectedItem()
    data class Local(val playlistId: Long) : HomeSelectedItem()
    data class Bili(val playlist: BiliPlaylist) : HomeSelectedItem()
}

@Composable
fun HomeHostScreen(
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    var selected by rememberSaveable { mutableStateOf<HomeSelectedItem?>(null) }
    BackHandler(enabled = selected != null) { selected = null }

    val gridState = remember {
        LazyGridState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }

    Surface(color = Color.Transparent) {
        AnimatedContent(
            targetState = selected,
            label = "home_host_switch",
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
                HomeScreen(
                    gridState = gridState,
                    onItemClick = { pl ->
                        AppContainer.playlistUsageRepo.recordOpen(
                            id = pl.id, name = pl.name, picUrl = pl.picUrl,
                            trackCount = pl.trackCount, source = "netease"
                        )
                        selected = HomeSelectedItem.Netease(pl)
                    },
                    onOpenRecent = { entry ->
                        openRecent(entry) { next -> selected = next }
                    },
                    onSongClick = onSongClick    // 透传给 HomeScreen，点击推荐歌曲可直接播放
                )
            } else {
                when (current) {
                    is HomeSelectedItem.Netease -> {
                        PlaylistDetailScreen(
                            playlist = current.playlist,
                            onBack = { selected = null },
                            onSongClick = onSongClick
                        )
                    }
                    is HomeSelectedItem.Local -> {
                        LocalPlaylistDetailScreen(
                            playlistId = current.playlistId,
                            onBack = { selected = null },
                            onDeleted = { selected = null },
                            onSongClick = onSongClick
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
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 根据 UsageEntry 分发到不同平台详情 */
private fun openRecent(
    entry: UsageEntry,
    onSelected: (HomeSelectedItem) -> Unit
) {
    when (entry.source.lowercase()) {
        "netease" -> {
            onSelected(
                HomeSelectedItem.Netease(
                    NeteasePlaylist(
                        id = entry.id,
                        name = entry.name,
                        picUrl = entry.picUrl ?: "",
                        playCount = 0L,
                        trackCount = entry.trackCount
                    )
                )
            )
        }
        "local" -> onSelected(HomeSelectedItem.Local(entry.id))
        "bili" -> {
            val bili = BiliPlaylist(
                mediaId = entry.id,
                title = entry.name,
                coverUrl = entry.picUrl ?: "",
                count = entry.trackCount,
                fid = 0L,
                mid = 0L
            )
            onSelected(HomeSelectedItem.Bili(bili))
        }
        else -> {}
    }
}
