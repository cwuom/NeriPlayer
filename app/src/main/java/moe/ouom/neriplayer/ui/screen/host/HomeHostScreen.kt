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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.api.bili.BiliClient
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
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.animateMainTabDetailCloseRootRevealFraction
import moe.ouom.neriplayer.ui.clipMainTabDetailCloseRoot
import moe.ouom.neriplayer.ui.rememberMainTabSceneRestoredEntry
import moe.ouom.neriplayer.ui.shouldSuppressRestoredMainTabHostEntry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.util.restoreBiliPlaylist
import moe.ouom.neriplayer.ui.util.restoreAlbumSummary
import moe.ouom.neriplayer.ui.util.restorePlaylistSummary
import moe.ouom.neriplayer.ui.util.restoreYouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.util.toSaveMap
import moe.ouom.neriplayer.util.media.CoverArtColorCache

// 用密封类承载四种目标
private sealed class HomeSelectedItem {
    data class Netease(val playlist: PlaylistSummary) : HomeSelectedItem()
    data class NeteaseAlbumList(val album: AlbumSummary) : HomeSelectedItem()
    data class Local(val playlistId: Long) : HomeSelectedItem()
    data class LocalArtist(val artistName: String) : HomeSelectedItem()
    data class Bili(val playlist: BiliPlaylist) : HomeSelectedItem()
    data class YouTubeMusic(val playlist: YouTubeMusicPlaylist) : HomeSelectedItem()
}

private val HomeSelectedItem?.navigationDepth: Int
    get() = if (this == null) 0 else 1

@Stable
class HomeHostRuntimeState {
    val gridState: LazyGridState = LazyGridState(
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0
    )
    val radarPlaylistListState: LazyListState = LazyListState(
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0
    )
    val topAppBarState: TopAppBarState = TopAppBarState(
        initialHeightOffsetLimit = -Float.MAX_VALUE,
        initialHeightOffset = 0f,
        initialContentOffset = 0f
    )
    var pendingGridRestoreIndex by mutableStateOf<Int?>(null)
    var pendingGridRestoreOffset by mutableIntStateOf(0)
    var pendingGridRestoreKey by mutableStateOf<String?>(null)
    var pendingGridRestoreArmed by mutableStateOf(false)
    var pendingRadarPlaylistRestoreIndex by mutableStateOf<Int?>(null)
    var pendingRadarPlaylistRestoreOffset by mutableIntStateOf(0)
    var homeScrollAnchorIndexes by mutableStateOf<Map<String, Int>>(emptyMap())
}

@Composable
fun rememberHomeHostRuntimeState(): HomeHostRuntimeState {
    return remember { HomeHostRuntimeState() }
}

@Composable
fun HomeHostScreen(
    showContinueCard: Boolean = true,
    showTrendingCard: Boolean = true,
    showRadarCard: Boolean = true,
    showRecommendedCard: Boolean = true,
    homeUsageEntries: List<UsageEntry> = emptyList(),
    homeUsageLoaded: Boolean = true,
    offlineMode: Boolean = false,
    runtimeState: HomeHostRuntimeState = rememberHomeHostRuntimeState(),
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onSongClickWithSourceRoute: (List<SongItem>, Int, String?) -> Unit = { songs, index, _ ->
        onSongClick(songs, index)
    },
    onPlayBiliAudioWithSourceRoute: (List<BiliVideoItem>, Int, String?) -> Unit = { videos, index, _ ->
        PlayerManager.playBiliVideoAsAudio(videos, index)
    },
    onPlayBiliPartsWithSourceRoute: (
        BiliClient.VideoBasicInfo,
        Int,
        String,
        String?
    ) -> Unit = { videoInfo, index, coverUrl, _ ->
        PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
    },
    neteasePlaylistSourceRoute: (PlaylistSummary) -> String? = { null },
    neteaseAlbumSourceRoute: (AlbumSummary) -> String? = { null },
    biliPlaylistSourceRoute: (BiliPlaylist) -> String? = { null },
    localPlaylistSourceRoute: (Long) -> String? = { null },
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
    var selected by rememberSaveable(stateSaver = homeSelectedItemSaver) {
        mutableStateOf(null)
    }
    var skipDetailCloseAnimation by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingNeteaseCoverWarmupJob by remember { mutableStateOf<Job?>(null) }
    var pendingNeteaseCoverWarmupToken by remember { mutableIntStateOf(0) }
    val gridState = runtimeState.gridState

    fun clearPendingHomeScrollRestore() {
        runtimeState.pendingGridRestoreIndex = null
        runtimeState.pendingGridRestoreOffset = 0
        runtimeState.pendingGridRestoreKey = null
        runtimeState.pendingGridRestoreArmed = false
        runtimeState.pendingRadarPlaylistRestoreIndex = null
        runtimeState.pendingRadarPlaylistRestoreOffset = 0
    }

    fun captureHomeScrollPosition() {
        val position = gridState.captureHostScrollPosition()
        runtimeState.pendingGridRestoreIndex = position.index
        runtimeState.pendingGridRestoreOffset = position.offset
        runtimeState.pendingGridRestoreKey = position.key
        val radarPosition = runtimeState.radarPlaylistListState.captureHostScrollPosition()
        runtimeState.pendingRadarPlaylistRestoreIndex = radarPosition.index
        runtimeState.pendingRadarPlaylistRestoreOffset = radarPosition.offset
        runtimeState.pendingGridRestoreArmed = false
    }

    fun ensureHomeScrollPositionCaptured() {
        if (runtimeState.pendingGridRestoreIndex == null) {
            captureHomeScrollPosition()
        }
    }

    fun cancelPendingNeteaseCoverWarmup() {
        pendingNeteaseCoverWarmupToken += 1
        pendingNeteaseCoverWarmupJob?.cancel()
        pendingNeteaseCoverWarmupJob = null
    }

    fun openAfterNeteaseCoverWarmup(
        coverUrl: String?,
        item: HomeSelectedItem
    ) {
        val token = pendingNeteaseCoverWarmupToken + 1
        pendingNeteaseCoverWarmupToken = token
        pendingNeteaseCoverWarmupJob?.cancel()
        pendingNeteaseCoverWarmupJob = scope.launch {
            CoverArtColorCache.preload(context, coverUrl, offlineMode)
            if (pendingNeteaseCoverWarmupToken == token) {
                ensureHomeScrollPositionCaptured()
                selected = item
                pendingNeteaseCoverWarmupJob = null
            }
        }
    }

    fun openHomeSelectedItem(item: HomeSelectedItem) {
        when (item) {
            is HomeSelectedItem.Netease -> {
                openAfterNeteaseCoverWarmup(item.playlist.picUrl, item)
            }
            is HomeSelectedItem.NeteaseAlbumList -> {
                openAfterNeteaseCoverWarmup(item.album.picUrl, item)
            }
            else -> {
                cancelPendingNeteaseCoverWarmup()
                ensureHomeScrollPositionCaptured()
                selected = item
            }
        }
    }

    fun closeSelectedDetail() {
        cancelPendingNeteaseCoverWarmup()
        skipDetailCloseAnimation = false
        selected = null
    }

    fun closeDeletedLocalPlaylist() {
        cancelPendingNeteaseCoverWarmup()
        skipDetailCloseAnimation = true
        selected = null
    }

    LaunchedEffect(selected) {
        if (selected != null) {
            skipDetailCloseAnimation = false
            if (runtimeState.pendingGridRestoreIndex != null) {
                runtimeState.pendingGridRestoreArmed = true
                runtimeState.homeScrollAnchorIndexes = emptyMap()
            }
        }
    }

    PredictiveBackHandler(enabled = selected != null) { progress ->
        try {
            progress.collect { }
            closeSelectedDetail()
        } catch (_: CancellationException) {
        }
    }

    val navigationTransition = updateTransition(
        targetState = selected,
        label = "home_host_switch"
    )

    val pendingGridRestoreIndex = runtimeState.pendingGridRestoreIndex
    val pendingGridRestoreOffset = runtimeState.pendingGridRestoreOffset
    val pendingGridRestoreKey = runtimeState.pendingGridRestoreKey
    val pendingGridRestoreArmed = runtimeState.pendingGridRestoreArmed
    val homeScrollAnchorIndexes = runtimeState.homeScrollAnchorIndexes
    LaunchedEffect(
        selected,
        pendingGridRestoreIndex,
        pendingGridRestoreKey,
        pendingGridRestoreArmed,
        homeScrollAnchorIndexes
    ) {
        val restoreIndex = pendingGridRestoreIndex ?: return@LaunchedEffect
        if (selected != null) return@LaunchedEffect
        if (!pendingGridRestoreArmed) return@LaunchedEffect
        val restoreKey = pendingGridRestoreKey
        val resolvedIndex = restoreKey?.let { homeScrollAnchorIndexes[it] }
        if (restoreKey != null && resolvedIndex == null && homeScrollAnchorIndexes.isEmpty()) {
            return@LaunchedEffect
        }
        gridState.restoreHostScrollPosition(
            HostScrollPosition(
                index = restoreIndex,
                offset = pendingGridRestoreOffset,
                key = restoreKey
            ),
            resolvedIndex = resolvedIndex
        )
        val radarRestoreIndex = runtimeState.pendingRadarPlaylistRestoreIndex
        if (radarRestoreIndex != null) {
            runtimeState.radarPlaylistListState.restoreHostScrollPosition(
                HostScrollPosition(
                    index = radarRestoreIndex,
                    offset = runtimeState.pendingRadarPlaylistRestoreOffset
                )
            )
        }
        clearPendingHomeScrollRestore()
    }
    val suppressRestoredSceneEntry = rememberMainTabSceneRestoredEntry()
    val detailCloseRootRevealFraction =
        navigationTransition.animateMainTabDetailCloseRootRevealFraction(
            navigationDepth = { item -> item.navigationDepth },
            label = "home_host_detail_close"
        )

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        navigationTransition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (
                    shouldSuppressRestoredMainTabHostEntry(
                        restoredEntry = suppressRestoredSceneEntry,
                        initialDepth = initialState.navigationDepth,
                        targetDepth = targetState.navigationDepth
                    )
                ) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (targetState == null && skipDetailCloseAnimation) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    advancedGlassHostNavigationTransition(
                        forward = targetState.navigationDepth > initialState.navigationDepth,
                        coherentFeedbackEnabled = coherentFeedbackEnabled,
                        targetContentZIndex = targetState.navigationDepth.toFloat()
                    )
                }.using(SizeTransform(clip = true))
            }
        ) { current ->
            val suppressRestoredSceneMotion = shouldSuppressRestoredMainTabHostEntry(
                restoredEntry = suppressRestoredSceneEntry,
                initialDepth = navigationTransition.currentState.navigationDepth,
                targetDepth = navigationTransition.targetState.navigationDepth
            )
            val sceneMotion = if (suppressRestoredSceneMotion) {
                AdvancedGlassSceneMotion.None
            } else {
                navigationTransition.animateAdvancedGlassSceneMotion(
                    sceneState = current,
                    coherentFeedbackEnabled = coherentFeedbackEnabled,
                    navigationDepth = { item -> item.navigationDepth },
                    label = "home_host_scene"
                )
            }
            renderScene(
                sceneMotion.revealTopFraction,
                sceneMotion.contentTranslationYFraction,
                sceneMotion.contentScale,
                current.navigationDepth
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (current == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipMainTabDetailCloseRoot(
                                    detailCloseRootRevealFraction
                                )
                        ) {
                            HomeScreen(
                                showContinueCard = showContinueCard,
                                showTrendingCard = showTrendingCard,
                                showRadarCard = showRadarCard,
                                showRecommendedCard = showRecommendedCard,
                                usageEntries = homeUsageEntries,
                                usageLoaded = homeUsageLoaded,
                                offlineMode = offlineMode,
                                gridState = gridState,
                                radarPlaylistListState = runtimeState.radarPlaylistListState,
                                topAppBarState = runtimeState.topAppBarState,
                                onScrollAnchorIndexesChanged = { indexes ->
                                    if (runtimeState.homeScrollAnchorIndexes != indexes) {
                                        runtimeState.homeScrollAnchorIndexes = indexes
                                    }
                                },
                                onItemClick = { pl ->
                                    skipDetailCloseAnimation = false
                                    captureHomeScrollPosition()
                                    AppContainer.launchBackgroundIo {
                                        AppContainer.playlistUsageRepo.recordOpen(
                                            id = pl.id,
                                            name = pl.name,
                                            picUrl = pl.picUrl,
                                            trackCount = pl.trackCount,
                                            source = "netease"
                                        )
                                    }
                                    openHomeSelectedItem(HomeSelectedItem.Netease(pl))
                                },
                                onYouTubeMusicPlaylistClick = { pl ->
                                    skipDetailCloseAnimation = false
                                    captureHomeScrollPosition()
                                    AppContainer.launchBackgroundIo {
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
                                    }
                                    openHomeSelectedItem(HomeSelectedItem.YouTubeMusic(pl))
                                },
                                onOpenRecent = { entry ->
                                    skipDetailCloseAnimation = false
                                    captureHomeScrollPosition()
                                    AppContainer.launchBackgroundIo {
                                        AppContainer.playlistUsageRepo.recordOpen(
                                            id = entry.id,
                                            name = entry.name,
                                            picUrl = entry.picUrl,
                                            trackCount = entry.trackCount,
                                            source = entry.source,
                                            fid = entry.fid ?: 0L,
                                            mid = entry.mid ?: 0L,
                                            browseId = entry.browseId,
                                            playlistId = entry.playlistId,
                                            subtype = entry.subtype,
                                            subtitle = entry.subtitle
                                        )
                                    }
                                    openRecent(entry, ::openHomeSelectedItem)
                                },
                                onSongClick = onSongClick
                            )
                        }
                    } else {
                        when (current) {
                            is HomeSelectedItem.NeteaseAlbumList -> {
                                NeteaseAlbumDetailScreen(
                                    album = current.album,
                                    onBack = { selected = null },
                                    onSongClick = { songs, index ->
                                        onSongClickWithSourceRoute(
                                            songs,
                                            index,
                                            neteaseAlbumSourceRoute(current.album)
                                        )
                                    },
                                    offlineMode = offlineMode
                                )
                            }

                            is HomeSelectedItem.Netease -> {
                                NeteasePlaylistDetailScreen(
                                    playlist = current.playlist,
                                    onBack = { selected = null },
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

                            is HomeSelectedItem.Local -> {
                                LocalPlaylistDetailScreen(
                                    playlistId = current.playlistId,
                                    onBack = ::closeSelectedDetail,
                                    onDeleted = ::closeDeletedLocalPlaylist,
                                    onSongClick = { songs, index ->
                                        onSongClickWithSourceRoute(
                                            songs,
                                            index,
                                            localPlaylistSourceRoute(current.playlistId)
                                        )
                                    },
                                    offlineMode = offlineMode
                                )
                            }

                            is HomeSelectedItem.LocalArtist -> {
                                LocalArtistDetailScreen(
                                    artistName = current.artistName,
                                    onBack = ::closeSelectedDetail,
                                    onSongClick = onSongClick,
                                    offlineMode = offlineMode
                                )
                            }

                            is HomeSelectedItem.Bili -> {
                                BiliPlaylistDetailScreen(
                                    playlist = current.playlist,
                                    onBack = { selected = null },
                                    onPlayAudio = { videos, index ->
                                        onPlayBiliAudioWithSourceRoute(
                                            videos,
                                            index,
                                            biliPlaylistSourceRoute(current.playlist)
                                        )
                                    },
                                    onPlayParts = { videoInfo, index, coverUrl ->
                                        onPlayBiliPartsWithSourceRoute(
                                            videoInfo,
                                            index,
                                            coverUrl,
                                            biliPlaylistSourceRoute(current.playlist)
                                        )
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
                kind = kind,
                subtitle = entry.subtitle.orEmpty()
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
