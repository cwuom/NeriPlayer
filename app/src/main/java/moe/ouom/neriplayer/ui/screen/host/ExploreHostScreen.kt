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
 * File: moe.ouom.neriplayer.ui.screen.host/ExploreHostScreen
 * Created: 2025/8/11
 */

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.YouTubeMusicPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.tab.ExploreScreen
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.data.model.SongItem

// 探索页选中项
private sealed class ExploreSelectedItem {
    data class Netease(val playlist: PlaylistSummary) : ExploreSelectedItem()
    data class YouTubeMusic(val playlist: YouTubeMusicPlaylist) : ExploreSelectedItem()
}

private val ExploreSelectedItem?.navigationDepth: Int
    get() = if (this == null) 0 else 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreHostScreen(
    offlineMode: Boolean = false,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onSongPlayPreservingQueue: (SongItem) -> Unit = {},
    onSongPlayNext: (SongItem) -> Unit = {},
    onSongAddToQueueEnd: (SongItem) -> Unit = {},
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> },
    coherentFeedbackEnabled: Boolean = false,
    renderScene: @Composable (
        revealTopFraction: Float,
        contentTranslationYFraction: Float,
        contentScale: Float,
        content: @Composable () -> Unit
    ) -> Unit = { _, _, _, content ->
        content()
    }
) {
    var selected by remember { mutableStateOf<ExploreSelectedItem?>(null) }
    LaunchedEffect(offlineMode) {
        if (offlineMode) {
            selected = null
        }
    }

    PredictiveBackHandler(enabled = selected != null) { progress ->
        try {
            progress.collect { }
            selected = null
        } catch (_: CancellationException) {
        }
    }

    val gridStateSaver: Saver<LazyGridState, *> = LazyGridState.Saver
    val gridState = rememberSaveable(saver = gridStateSaver) {
        LazyGridState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val topAppBarState = rememberTopAppBarState()
    var pendingGridRestoreIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingGridRestoreOffset by rememberSaveable { mutableIntStateOf(0) }
    var pendingTopAppBarHeightOffset by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var pendingTopAppBarContentOffset by rememberSaveable { mutableFloatStateOf(Float.NaN) }

    fun captureExploreScrollPosition() {
        val position = gridState.captureHostScrollPosition()
        pendingGridRestoreIndex = position.index
        pendingGridRestoreOffset = position.offset
        pendingTopAppBarHeightOffset = topAppBarState.heightOffset
        pendingTopAppBarContentOffset = topAppBarState.contentOffset
    }

    LaunchedEffect(selected, pendingGridRestoreIndex) {
        val restoreIndex = pendingGridRestoreIndex ?: return@LaunchedEffect
        if (selected != null) return@LaunchedEffect
        gridState.restoreHostScrollPosition(
            HostScrollPosition(
                index = restoreIndex,
                offset = pendingGridRestoreOffset
            )
        )
        if (!pendingTopAppBarHeightOffset.isNaN()) {
            topAppBarState.heightOffset = pendingTopAppBarHeightOffset
        }
        if (!pendingTopAppBarContentOffset.isNaN()) {
            topAppBarState.contentOffset = pendingTopAppBarContentOffset
        }
        pendingGridRestoreIndex = null
        pendingGridRestoreOffset = 0
        pendingTopAppBarHeightOffset = Float.NaN
        pendingTopAppBarContentOffset = Float.NaN
    }
    val navigationTransition = updateTransition(
        targetState = selected,
        label = "explore_host_switch"
    )

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        navigationTransition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                advancedGlassHostNavigationTransition(
                    forward = targetState.navigationDepth > initialState.navigationDepth,
                    coherentFeedbackEnabled = coherentFeedbackEnabled
                ).using(SizeTransform(clip = true))
            }
        ) { current ->
            val sceneMotion = navigationTransition.animateAdvancedGlassSceneMotion(
                sceneState = current,
                coherentFeedbackEnabled = coherentFeedbackEnabled,
                navigationDepth = { item -> item.navigationDepth },
                label = "explore_host_scene"
            )
            renderScene(
                sceneMotion.revealTopFraction,
                sceneMotion.contentTranslationYFraction,
                sceneMotion.contentScale
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (current == null) {
                        ExploreScreen(
                            gridState = gridState,
                            topAppBarState = topAppBarState,
                            offlineMode = offlineMode,
                            onPlay = { pl ->
                                captureExploreScrollPosition()
                                AppContainer.playlistUsageRepo.recordOpen(
                                    id = pl.id,
                                    name = pl.name,
                                    picUrl = pl.picUrl,
                                    trackCount = pl.trackCount,
                                    source = "netease"
                                )
                                selected = ExploreSelectedItem.Netease(pl)
                            },
                            onYouTubeMusicPlaylistClick = { pl ->
                                captureExploreScrollPosition()
                                AppContainer.playlistUsageRepo.recordOpen(
                                    id = stableYouTubeMusicId(
                                        pl.playlistId.ifBlank { pl.browseId }
                                    ),
                                    name = pl.title,
                                    picUrl = pl.coverUrl,
                                    trackCount = pl.trackCount,
                                    source = "youtubeMusic",
                                    browseId = pl.browseId,
                                    playlistId = pl.playlistId
                                )
                                selected = ExploreSelectedItem.YouTubeMusic(pl)
                            },
                            onSongClick = onSongClick,
                            onSongPlayPreservingQueue = onSongPlayPreservingQueue,
                            onSongPlayNext = onSongPlayNext,
                            onSongAddToQueueEnd = onSongAddToQueueEnd,
                            onPlayParts = onPlayParts
                        )
                    } else {
                        when (current) {
                            is ExploreSelectedItem.Netease -> {
                                NeteasePlaylistDetailScreen(
                                    playlist = current.playlist,
                                    onBack = { selected = null },
                                    onSongClick = onSongClick,
                                    offlineMode = offlineMode
                                )
                            }

                            is ExploreSelectedItem.YouTubeMusic -> {
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
    }
}
