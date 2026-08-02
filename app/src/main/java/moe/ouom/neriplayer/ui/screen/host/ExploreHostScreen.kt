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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.screen.artist.NeteaseArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.YouTubeMusicPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.tab.ExploreScreen
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.rememberMainTabSceneRestoredEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.media.CoverArtColorCache

// 探索页选中项
private sealed class ExploreSelectedItem {
    data class Netease(val playlist: PlaylistSummary) : ExploreSelectedItem()
    data class NeteaseArtist(val artist: NeteaseArtistSummary) : ExploreSelectedItem()
    data class NeteaseArtistAlbum(
        val artist: NeteaseArtistSummary,
        val album: AlbumSummary
    ) : ExploreSelectedItem()
    data class Bilibili(val playlist: BiliPlaylist) : ExploreSelectedItem()
    data class YouTubeMusic(val playlist: YouTubeMusicPlaylist) : ExploreSelectedItem()
}

private val ExploreSelectedItem?.navigationDepth: Int
    get() = when (this) {
        null -> 0
        is ExploreSelectedItem.NeteaseArtistAlbum -> 2
        else -> 1
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreHostScreen(
    offlineMode: Boolean = false,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onSongClickWithSourceRoute: (List<SongItem>, Int, String?) -> Unit = { songs, index, _ ->
        onSongClick(songs, index)
    },
    neteasePlaylistSourceRoute: (PlaylistSummary) -> String? = { null },
    onSongPlayPreservingQueue: (SongItem) -> Unit = {},
    onSongPlayNext: (SongItem) -> Unit = {},
    onSongAddToQueueEnd: (SongItem) -> Unit = {},
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> },
    coherentFeedbackEnabled: Boolean = false,
    renderScene: @Composable (
        revealTopFraction: Float,
        contentTranslationYFraction: Float,
        contentScale: Float,
        sceneDepth: Int,
        content: @Composable () -> Unit
    ) -> Unit = { _, _, _, _, content ->
        content()
    }
) {
    var selected by remember { mutableStateOf<ExploreSelectedItem?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingNeteaseCoverWarmupJob by remember { mutableStateOf<Job?>(null) }
    var pendingNeteaseCoverWarmupToken by remember { mutableIntStateOf(0) }

    fun cancelPendingNeteaseCoverWarmup() {
        pendingNeteaseCoverWarmupToken += 1
        pendingNeteaseCoverWarmupJob?.cancel()
        pendingNeteaseCoverWarmupJob = null
    }

    fun openAfterNeteaseCoverWarmup(
        coverUrl: String?,
        item: ExploreSelectedItem
    ) {
        val token = pendingNeteaseCoverWarmupToken + 1
        pendingNeteaseCoverWarmupToken = token
        pendingNeteaseCoverWarmupJob?.cancel()
        pendingNeteaseCoverWarmupJob = scope.launch {
            CoverArtColorCache.preload(context, coverUrl, offlineMode)
            if (pendingNeteaseCoverWarmupToken == token) {
                selected = item
                pendingNeteaseCoverWarmupJob = null
            }
        }
    }

    fun openExploreSelectedItem(item: ExploreSelectedItem) {
        when (item) {
            is ExploreSelectedItem.Netease -> {
                openAfterNeteaseCoverWarmup(item.playlist.picUrl, item)
            }
            is ExploreSelectedItem.NeteaseArtistAlbum -> {
                openAfterNeteaseCoverWarmup(item.album.picUrl, item)
            }
            else -> {
                cancelPendingNeteaseCoverWarmup()
                selected = item
            }
        }
    }

    fun closeSelectedDetail() {
        cancelPendingNeteaseCoverWarmup()
        selected = when (val current = selected) {
            is ExploreSelectedItem.NeteaseArtistAlbum -> {
                ExploreSelectedItem.NeteaseArtist(current.artist)
            }
            else -> null
        }
    }

    LaunchedEffect(offlineMode) {
        if (offlineMode) {
            cancelPendingNeteaseCoverWarmup()
            selected = null
        }
    }

    PredictiveBackHandler(enabled = selected != null) { progress ->
        try {
            progress.collect { }
            closeSelectedDetail()
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
    val suppressRestoredSceneEntry = rememberMainTabSceneRestoredEntry()

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        navigationTransition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (suppressRestoredSceneEntry && targetState != initialState) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    advancedGlassHostNavigationTransition(
                        forward = targetState.navigationDepth > initialState.navigationDepth,
                        coherentFeedbackEnabled = coherentFeedbackEnabled
                    )
                }.using(SizeTransform(clip = true))
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
                sceneMotion.contentScale,
                current.navigationDepth
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
                                openExploreSelectedItem(ExploreSelectedItem.Netease(pl))
                            },
                            onBiliPlaylistClick = { playlist ->
                                captureExploreScrollPosition()
                                openExploreSelectedItem(ExploreSelectedItem.Bilibili(playlist))
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
                                openExploreSelectedItem(ExploreSelectedItem.YouTubeMusic(pl))
                            },
                            onNeteaseArtistClick = { artist ->
                                captureExploreScrollPosition()
                                openExploreSelectedItem(ExploreSelectedItem.NeteaseArtist(artist))
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
                                    onBack = ::closeSelectedDetail,
                                    onSongClick = { songs, index ->
                                        onSongClickWithSourceRoute(
                                            songs,
                                            index,
                                            neteasePlaylistSourceRoute(current.playlist)
                                        )
                                    },
                                    offlineMode = offlineMode
                                )
                            }

                            is ExploreSelectedItem.NeteaseArtist -> {
                                NeteaseArtistDetailScreen(
                                    artist = current.artist,
                                    onBack = ::closeSelectedDetail,
                                    onSongClick = onSongClick,
                                    onAlbumClick = { album ->
                                        openExploreSelectedItem(
                                            ExploreSelectedItem.NeteaseArtistAlbum(
                                                artist = current.artist,
                                                album = album
                                            )
                                        )
                                    },
                                    offlineMode = offlineMode
                                )
                            }

                            is ExploreSelectedItem.NeteaseArtistAlbum -> {
                                NeteaseAlbumDetailScreen(
                                    album = current.album,
                                    onBack = ::closeSelectedDetail,
                                    onSongClick = onSongClick,
                                    offlineMode = offlineMode
                                )
                            }

                            is ExploreSelectedItem.Bilibili -> {
                                BiliPlaylistDetailScreen(
                                    playlist = current.playlist,
                                    onBack = ::closeSelectedDetail,
                                    onPlayAudio = { videos, index ->
                                        onSongClick(videos.map(BiliVideoItem::toExploreSongItem), index)
                                    },
                                    onPlayParts = onPlayParts,
                                    offlineMode = offlineMode
                                )
                            }

                            is ExploreSelectedItem.YouTubeMusic -> {
                                YouTubeMusicPlaylistDetailScreen(
                                    playlist = current.playlist,
                                    onBack = ::closeSelectedDetail,
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

private fun BiliVideoItem.toExploreSongItem(): SongItem {
    return SongItem(
        id = id,
        name = title,
        artist = uploader,
        album = PlayerManager.BILI_SOURCE_TAG,
        albumId = 0L,
        durationMs = durationSec * 1000L,
        coverUrl = coverUrl,
        channelId = "bilibili",
        audioId = id.toString()
    )
}
