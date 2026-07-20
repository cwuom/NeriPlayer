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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import kotlinx.parcelize.Parcelize
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
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
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
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

private val LibrarySelectedItem?.navigationDepth: Int
    get() = when (this) {
        null -> 0
        is LibrarySelectedItem.NeteaseArtistAlbum -> 2
        else -> 1
    }

private enum class LibraryScrollSource {
    Local,
    Favorite,
    NeteasePlaylist,
    NeteaseAlbum,
    YouTubeMusic,
    Bili
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHostScreen(
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> },
    onOpenRecent: () -> Unit,
    onOpenStats: () -> Unit = {},
    offlineMode: Boolean = false,
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
    var selected by rememberSaveable(stateSaver = librarySelectedItemSaver) {
        mutableStateOf(null)
    }
    var skipDetailCloseAnimation by rememberSaveable { mutableStateOf(false) }
    var pendingScrollSource by rememberSaveable {
        mutableStateOf<LibraryScrollSource?>(null)
    }
    var pendingListRestoreIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingListRestoreOffset by rememberSaveable { mutableIntStateOf(0) }
    var pendingTopAppBarHeightOffset by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var pendingTopAppBarContentOffset by rememberSaveable { mutableFloatStateOf(Float.NaN) }
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

    fun listStateFor(source: LibraryScrollSource): LazyListState = when (source) {
        LibraryScrollSource.Local -> localListState
        LibraryScrollSource.Favorite -> favoriteListState
        LibraryScrollSource.NeteasePlaylist -> neteaseListState
        LibraryScrollSource.NeteaseAlbum -> neteaseAlbumState
        LibraryScrollSource.YouTubeMusic -> youtubeMusicListState
        LibraryScrollSource.Bili -> biliListState
    }

    fun captureLibraryScrollPosition(source: LibraryScrollSource) {
        val position = listStateFor(source).captureHostScrollPosition()
        pendingScrollSource = source
        pendingListRestoreIndex = position.index
        pendingListRestoreOffset = position.offset
        pendingTopAppBarHeightOffset = topAppBarState.heightOffset
        pendingTopAppBarContentOffset = topAppBarState.contentOffset
    }

    fun sourceForFavoriteAwareDestination(
        regularSource: LibraryScrollSource
    ): LibraryScrollSource = if (selectedTab == LibraryTab.FAVORITE) {
        LibraryScrollSource.Favorite
    } else {
        regularSource
    }

    LaunchedEffect(selected, pendingScrollSource, pendingListRestoreIndex) {
        val source = pendingScrollSource ?: return@LaunchedEffect
        val restoreIndex = pendingListRestoreIndex ?: return@LaunchedEffect
        if (selected != null) return@LaunchedEffect
        listStateFor(source).restoreHostScrollPosition(
            HostScrollPosition(
                index = restoreIndex,
                offset = pendingListRestoreOffset
            )
        )
        if (!pendingTopAppBarHeightOffset.isNaN()) {
            topAppBarState.heightOffset = pendingTopAppBarHeightOffset
        }
        if (!pendingTopAppBarContentOffset.isNaN()) {
            topAppBarState.contentOffset = pendingTopAppBarContentOffset
        }
        pendingScrollSource = null
        pendingListRestoreIndex = null
        pendingListRestoreOffset = 0
        pendingTopAppBarHeightOffset = Float.NaN
        pendingTopAppBarContentOffset = Float.NaN
    }
    val navigationTransition = updateTransition(
        targetState = selected,
        label = "library_host_switch"
    )

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        navigationTransition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState == null && skipDetailCloseAnimation) {
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
                label = "library_host_scene"
            )
            renderScene(
                sceneMotion.revealTopFraction,
                sceneMotion.contentTranslationYFraction,
                sceneMotion.contentScale
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                                captureLibraryScrollPosition(LibraryScrollSource.Local)
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
                                captureLibraryScrollPosition(LibraryScrollSource.Local)
                                selected = LibrarySelectedItem.LocalArtist(artist.name)
                                AppContainer.playlistUsageRepo.recordOpen(
                                    id = artist.id,
                                    name = artist.name,
                                    picUrl = artist.displayCoverUrl(context),
                                    trackCount = artist.songs.size,
                                    source = PlaylistUsageRepository.SOURCE_LOCAL_ARTIST
                                )
                            },
                            onNeteasePlaylistClick = { playlist ->
                                skipDetailCloseAnimation = false
                                captureLibraryScrollPosition(
                                    sourceForFavoriteAwareDestination(
                                        LibraryScrollSource.NeteasePlaylist
                                    )
                                )
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
                                captureLibraryScrollPosition(
                                    sourceForFavoriteAwareDestination(
                                        LibraryScrollSource.NeteaseAlbum
                                    )
                                )
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
                                captureLibraryScrollPosition(LibraryScrollSource.Favorite)
                                openNeteaseArtist(artist)
                            },
                            onYouTubeMusicPlaylistClick = { playlist ->
                                skipDetailCloseAnimation = false
                                captureLibraryScrollPosition(
                                    sourceForFavoriteAwareDestination(
                                        LibraryScrollSource.YouTubeMusic
                                    )
                                )
                                selected = LibrarySelectedItem.YouTubeMusic(playlist)
                                AppContainer.playlistUsageRepo.recordOpen(
                                    id = stableYouTubeMusicId(
                                        playlist.playlistId.ifBlank { playlist.browseId }
                                    ),
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
                                captureLibraryScrollPosition(
                                    sourceForFavoriteAwareDestination(LibraryScrollSource.Bili)
                                )
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
                            libraryStateHolder.SaveableStateProvider(
                                "netease_artist_${current.artist.id}"
                            ) {
                                NeteaseArtistDetailScreen(
                                    artist = current.artist,
                                    onBack = { selected = null },
                                    onSongClick = onSongClick,
                                    offlineMode = offlineMode,
                                    onAlbumClick = { album ->
                                        selected = LibrarySelectedItem.NeteaseArtistAlbum(
                                            current.artist,
                                            album
                                        )
                                    }
                                )
                            }
                        }

                        is LibrarySelectedItem.NeteaseArtistAlbum -> {
                            NeteaseAlbumDetailScreen(
                                onBack = {
                                    selected = LibrarySelectedItem.NeteaseArtist(current.artist)
                                },
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
