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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.YouTubeMusicPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.tab.home.HomeScreen
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.navigation.animateMainTabDetailCloseRootRevealFraction
import moe.ouom.neriplayer.ui.navigation.clipMainTabDetailCloseRoot
import moe.ouom.neriplayer.ui.navigation.rememberMainTabSceneRestoredEntry
import moe.ouom.neriplayer.ui.navigation.shouldSuppressRestoredMainTabHostEntry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.util.restoreBiliPlaylist
import moe.ouom.neriplayer.ui.util.restoreAlbumSummary
import moe.ouom.neriplayer.ui.util.restorePlaylistSummary
import moe.ouom.neriplayer.ui.util.restoreYouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.util.SAVED_TYPE_KEY
import moe.ouom.neriplayer.ui.util.biliPlaylistKindOrDefault
import moe.ouom.neriplayer.ui.util.restoreSavedByType
import moe.ouom.neriplayer.ui.util.savedMap
import moe.ouom.neriplayer.ui.util.savedNonBlankString
import moe.ouom.neriplayer.ui.util.savedNumber
import moe.ouom.neriplayer.ui.util.toSaveMap
import moe.ouom.neriplayer.util.media.CoverArtColorCache

// 用密封类承载四种目标
internal sealed class HomeSelectedItem {
    abstract fun saveState(): Map<String, Any?>

    data class Netease(val playlist: PlaylistSummary) : HomeSelectedItem() {
        override fun saveState() = hashMapOf(SAVED_TYPE_KEY to "netease", "playlist" to playlist.toSaveMap())
    }
    data class NeteaseAlbumList(val album: AlbumSummary) : HomeSelectedItem() {
        override fun saveState() = hashMapOf(SAVED_TYPE_KEY to "neteaseAlbum", "album" to album.toSaveMap())
    }
    data class Local(val playlistId: Long) : HomeSelectedItem() {
        override fun saveState() = hashMapOf(SAVED_TYPE_KEY to "local", "playlistId" to playlistId)
    }
    data class LocalArtist(val artistName: String) : HomeSelectedItem() {
        override fun saveState() = hashMapOf(SAVED_TYPE_KEY to "localArtist", "artistName" to artistName)
    }
    data class Bili(val playlist: BiliPlaylist) : HomeSelectedItem() {
        override fun saveState() = hashMapOf(SAVED_TYPE_KEY to "bili", "playlist" to playlist.toSaveMap())
    }
    data class YouTubeMusic(val playlist: YouTubeMusicPlaylist) : HomeSelectedItem() {
        override fun saveState() = hashMapOf(SAVED_TYPE_KEY to "ytmusic", "playlist" to playlist.toSaveMap())
    }
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
    var continuePagerPage by mutableIntStateOf(0)
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
        VideoBasicInfo,
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

    val navigationTransition = rememberHostPredictiveBackTransition(
        targetState = selected,
        backEnabled = selected != null,
        backTargetState = null,
        onBack = { closeSelectedDetail() },
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
                                continuePagerPage = runtimeState.continuePagerPage,
                                onContinuePagerPageChanged = { page ->
                                    runtimeState.continuePagerPage = page
                                },
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
                                            subtitle = entry.subtitle,
                                            updateLastOpened = false
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

internal val homeSelectedItemSaver = mapSaver<HomeSelectedItem?>(
    save = { item -> item?.saveState().orEmpty() },
    restore = { saved -> restoreSavedByType(saved, homeSelectedItemRestorers) }
)

private val homeSelectedItemRestorers: Map<String, (Map<String, Any?>) -> HomeSelectedItem?> = mapOf(
    "local" to { saved -> saved.savedNumber("playlistId")?.toLong()?.let(HomeSelectedItem::Local) },
    "localArtist" to { saved -> saved.savedNonBlankString("artistName")?.let(HomeSelectedItem::LocalArtist) },
    "neteaseAlbum" to { saved -> restoreAlbumSummary(saved.savedMap("album"))?.let(HomeSelectedItem::NeteaseAlbumList) },
    "netease" to { saved -> restorePlaylistSummary(saved.savedMap("playlist"))?.let(HomeSelectedItem::Netease) },
    "bili" to { saved -> restoreBiliPlaylist(saved.savedMap("playlist"))?.let(HomeSelectedItem::Bili) },
    "ytmusic" to { saved -> restoreYouTubeMusicPlaylist(saved.savedMap("playlist"))?.let(HomeSelectedItem::YouTubeMusic) }
)

/** 根据 UsageEntry 分发到不同平台详情 */
private fun openRecent(
    entry: UsageEntry,
    onSelected: (HomeSelectedItem) -> Unit
) {
    recentSelectedItem(entry)?.let(onSelected)
}

internal fun recentSelectedItem(entry: UsageEntry): HomeSelectedItem? =
    recentSelectedItemFactories[entry.source.lowercase()]?.invoke(entry)

private val recentSelectedItemFactories: Map<String, (UsageEntry) -> HomeSelectedItem?> = mapOf(
    "netease" to ::recentNeteaseItem,
    "neteasealbum" to ::recentNeteaseAlbumItem,
    "local" to { entry -> HomeSelectedItem.Local(entry.id) },
    PlaylistUsageRepository.SOURCE_LOCAL_ARTIST.lowercase() to { entry -> HomeSelectedItem.LocalArtist(entry.name) },
    "bili" to ::recentBiliItem,
    "youtubemusic" to ::recentYouTubeMusicItem
)

private fun recentNeteaseItem(entry: UsageEntry): HomeSelectedItem = HomeSelectedItem.Netease(
    PlaylistSummary(
        id = entry.id,
        name = entry.name,
        picUrl = entry.picUrl.orEmpty(),
        playCount = 0L,
        trackCount = entry.trackCount
    )
)

private fun recentNeteaseAlbumItem(entry: UsageEntry): HomeSelectedItem = HomeSelectedItem.NeteaseAlbumList(
    AlbumSummary(
        id = entry.id,
        name = entry.name,
        picUrl = entry.picUrl.orEmpty(),
        size = entry.trackCount
    )
)

private fun recentBiliItem(entry: UsageEntry): HomeSelectedItem = HomeSelectedItem.Bili(
    BiliPlaylist(
        mediaId = entry.id,
        title = entry.name,
        coverUrl = entry.picUrl.orEmpty(),
        count = entry.trackCount,
        fid = entry.fid ?: 0L,
        mid = entry.mid ?: 0L,
        kind = biliPlaylistKindOrDefault(entry.subtype),
        subtitle = entry.subtitle.orEmpty()
    )
)

private fun recentYouTubeMusicItem(entry: UsageEntry): HomeSelectedItem? {
    val browseId = recentYouTubeMusicBrowseId(entry) ?: return null
    return HomeSelectedItem.YouTubeMusic(
        YouTubeMusicPlaylist(
            browseId = browseId,
            playlistId = entry.playlistId.orEmpty().ifBlank { browseId.removePrefix(YOUTUBE_PLAYLIST_BROWSE_PREFIX) },
            title = entry.name,
            subtitle = "",
            coverUrl = entry.picUrl.orEmpty(),
            trackCount = entry.trackCount
        )
    )
}

private fun recentYouTubeMusicBrowseId(entry: UsageEntry): String? =
    entry.browseId?.takeIf(String::isNotBlank)
        ?: entry.playlistId?.takeIf(String::isNotBlank)
            ?.let { YOUTUBE_PLAYLIST_BROWSE_PREFIX + it.removePrefix(YOUTUBE_PLAYLIST_BROWSE_PREFIX) }

private const val YOUTUBE_PLAYLIST_BROWSE_PREFIX = "VL"
