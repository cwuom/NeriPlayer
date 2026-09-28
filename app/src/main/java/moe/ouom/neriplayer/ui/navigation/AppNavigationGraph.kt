package moe.ouom.neriplayer.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.gson.Gson
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.youtube.protocol.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.screen.download.DownloadManagerScreen
import moe.ouom.neriplayer.ui.screen.download.DownloadProgressScreen
import moe.ouom.neriplayer.ui.screen.history.RecentScreen
import moe.ouom.neriplayer.ui.screen.history.stats.PlaybackStatsScreen
import moe.ouom.neriplayer.ui.screen.debug.BiliApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.CrashLogListScreen
import moe.ouom.neriplayer.ui.screen.debug.ListenTogetherDebugScreen
import moe.ouom.neriplayer.ui.screen.debug.LogListScreen
import moe.ouom.neriplayer.ui.screen.debug.NeteaseApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.SearchApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.UsbExclusiveDebugScreen
import moe.ouom.neriplayer.ui.screen.debug.YouTubeApiProbeScreen
import moe.ouom.neriplayer.ui.screen.artist.BiliUploaderDetailScreen
import moe.ouom.neriplayer.ui.screen.artist.NeteaseArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.artist.YouTubeMusicCreatorNavigationScreen
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.YouTubeMusicPlaylistDetailScreen
import moe.ouom.neriplayer.ui.viewmodel.debug.LogViewerScreen
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.NAV_HOST_LAYER_Z_INDEX
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist

internal val navigationGson: Gson by lazy(LazyThreadSafetyMode.PUBLICATION) { Gson() }

internal fun neteasePlaylistSourceRoute(playlist: PlaylistSummary): String {
    return "playlist_detail/${Uri.encode(navigationGson.toJson(playlist))}"
}

internal fun neteaseAlbumSourceRoute(album: AlbumSummary): String {
    return "netease_album_detail/${Uri.encode(navigationGson.toJson(album))}"
}

internal fun biliPlaylistSourceRoute(playlist: BiliPlaylist): String {
    return "bili_playlist_detail/${Uri.encode(navigationGson.toJson(playlist))}"
}

internal fun biliUploaderSourceRoute(uploader: BiliUploaderSummary): String {
    return "bili_uploader_detail/${Uri.encode(navigationGson.toJson(uploader))}"
}

internal fun localPlaylistSourceRoute(id: Long): String {
    return "local_playlist_detail/$id"
}

internal class AppNavigationGraphPresentation(
    val startDestination: String,
    val coherentFeedbackEnabled: Boolean,
    val offlineMode: Boolean,
    val renderScene: @Composable AnimatedContentScope.(String?, @Composable () -> Unit) -> Unit
)

internal class AppNavigationMediaActions(
    val playSongs: (List<SongItem>, Int, String?) -> Unit,
    val playBiliAudio: (List<BiliVideoItem>, Int, String?) -> Unit,
    val playBiliParts: (BiliClient.VideoBasicInfo, Int, String, String?) -> Unit,
    val onNeteaseAlbumClick: (AlbumSummary) -> Unit,
    val onYouTubePlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    val onYouTubeCreatorClick: (YouTubeMusicCreatorSummary) -> Unit
) {
    val onSongClick: (List<SongItem>, Int) -> Unit = { songs, index ->
        playSongs(songs, index, null)
    }
}

internal class AppNavigationGraphOwner(
    val navController: NavHostController,
    val presentation: AppNavigationGraphPresentation,
    val mediaActions: AppNavigationMediaActions
) {
    fun NavGraphBuilder.registerRoutes() {
        registerDiscoveryAndMediaRoutes(
            navController, presentation, mediaActions, mediaActions.onSongClick
        )
        registerLibraryAndUtilityRoutes(
            navController, presentation, mediaActions, mediaActions.onSongClick
        )
    }
}


@Composable
internal fun AppNavigationGraph(
    owner: AppNavigationGraphOwner
) {
    NavHost(
        navController = owner.navController,
        startDestination = owner.presentation.startDestination,
        modifier = Modifier.fillMaxSize().zIndex(NAV_HOST_LAYER_Z_INDEX)
    ) {
        owner.run { registerRoutes() }
    }
}

private fun NavGraphBuilder.registerDiscoveryAndMediaRoutes(
    navController: NavHostController,
    presentation: AppNavigationGraphPresentation,
    mediaActions: AppNavigationMediaActions,
    onSongClick: (List<SongItem>, Int) -> Unit,
) {
    registerHomeTabRoutes(presentation.coherentFeedbackEnabled)
    registerNeteaseDetailRoutes(
        navController, presentation.coherentFeedbackEnabled, presentation.offlineMode,
        mediaActions.playSongs, onSongClick, mediaActions.onNeteaseAlbumClick,
        presentation.renderScene
    )
    registerYouTubeDetailRoutes(
        navController, presentation.coherentFeedbackEnabled, presentation.offlineMode,
        onSongClick, mediaActions.onYouTubePlaylistClick,
        mediaActions.onYouTubeCreatorClick, presentation.renderScene
    )
    registerBiliDetailRoutes(
        navController, presentation.coherentFeedbackEnabled, presentation.offlineMode,
        mediaActions.playBiliAudio, mediaActions.playBiliParts,
        presentation.renderScene
    )
}

private fun NavGraphBuilder.registerLibraryAndUtilityRoutes(
    navController: NavHostController,
    presentation: AppNavigationGraphPresentation,
    mediaActions: AppNavigationMediaActions,
    onSongClick: (List<SongItem>, Int) -> Unit,
) {
    registerExploreLibraryTabsRoutes(presentation.coherentFeedbackEnabled)
    registerLibraryDetailRoutes(
        navController, presentation.coherentFeedbackEnabled, presentation.offlineMode,
        mediaActions.playSongs, onSongClick, presentation.renderScene
    )
    registerSettingsTabRoutes(presentation.coherentFeedbackEnabled)
    registerDownloadRoutes(
        navController, presentation.coherentFeedbackEnabled, presentation.offlineMode,
        presentation.renderScene
    )
    registerDebugTabRoutes(presentation.coherentFeedbackEnabled)
    registerDebugToolRoutes(
        navController, presentation.coherentFeedbackEnabled, presentation.renderScene
    )
}

private fun NavGraphBuilder.registerHomeTabRoutes(
    coherentFeedbackEnabled: Boolean
) {
    composable(
        Destinations.Home.route,
        enterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        }
    ) {}

}

private fun NavGraphBuilder.registerNeteaseDetailRoutes(
    navController: NavHostController,
    coherentFeedbackEnabled: Boolean,
    offlineMode: Boolean,
    playSongs: (List<SongItem>, Int, String?) -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onNeteaseAlbumClick: (AlbumSummary) -> Unit,
    renderScene: @Composable AnimatedContentScope.(String?, @Composable () -> Unit) -> Unit
) {
    composable(
        route = Destinations.PlaylistDetail.route,
        arguments = listOf(navArgument("playlistJson") {
            type = NavType.StringType
        }),
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val playlistJson = backStackEntry.arguments?.getString("playlistJson")
        val playlist = navigationGson.fromJson(playlistJson, PlaylistSummary::class.java)
        renderScene(
            Destinations.PlaylistDetail.route
        ) {
            NeteasePlaylistDetailScreen(
                playlist = playlist,
                onBack = { navController.popBackStack() },
                onSongClick = { songs, index ->
                    playSongs(
                        songs,
                        index,
                        neteasePlaylistSourceRoute(playlist)
                    )
                },
                offlineMode = offlineMode
            )
        }
    }

    composable(
        route = Destinations.NeteaseAlbumDetail.route,
        arguments = listOf(navArgument("playlistJson") {
            type = NavType.StringType
        }),
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val playlistJson = backStackEntry.arguments?.getString("playlistJson")
        val album = navigationGson.fromJson(playlistJson, AlbumSummary::class.java)
        renderScene(
            Destinations.NeteaseAlbumDetail.route
        ) {
            NeteaseAlbumDetailScreen(
                album = album,
                onBack = { navController.popBackStack() },
                onSongClick = { songs, index ->
                    playSongs(
                        songs,
                        index,
                        neteaseAlbumSourceRoute(album)
                    )
                },
                offlineMode = offlineMode
            )
        }
    }

    composable(
        route = Destinations.NeteaseArtistDetail.route,
        arguments = listOf(navArgument("artistJson") {
            type = NavType.StringType
        }),
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val artistJson = backStackEntry.arguments?.getString("artistJson")
        val artist = navigationGson.fromJson(artistJson, NeteaseArtistSummary::class.java)
        renderScene(
            Destinations.NeteaseArtistDetail.route
        ) {
            NeteaseArtistDetailScreen(
                artist = artist,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                offlineMode = offlineMode,
                onAlbumClick = { album ->
                    onNeteaseAlbumClick(album)
                }
            )
        }
    }

}

private fun NavGraphBuilder.registerYouTubeDetailRoutes(
    navController: NavHostController,
    coherentFeedbackEnabled: Boolean,
    offlineMode: Boolean,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onYouTubePlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    onYouTubeCreatorClick: (YouTubeMusicCreatorSummary) -> Unit,
    renderScene: @Composable AnimatedContentScope.(String?, @Composable () -> Unit) -> Unit
) {
    composable(
        route = Destinations.YouTubeMusicCreatorDetail.route,
        arguments = listOf(navArgument("creatorJson") {
            type = NavType.StringType
        }),
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val creatorJson = backStackEntry.arguments
            ?.getString("creatorJson")
        val creator = navigationGson.fromJson(
            creatorJson,
            YouTubeMusicCreatorSummary::class.java
        )
        renderScene(
            Destinations.YouTubeMusicCreatorDetail.route
        ) {
            YouTubeMusicCreatorNavigationScreen(
                creator = creator,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onPlaylistClick = onYouTubePlaylistClick,
                onCreatorClick = onYouTubeCreatorClick,
                offlineMode = offlineMode
            )
        }
    }

    composable(
        route = Destinations.YouTubeMusicPlaylistDetail.route,
        arguments = listOf(navArgument("playlistJson") {
            type = NavType.StringType
        }),
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val playlistJson = backStackEntry.arguments
            ?.getString("playlistJson")
        val playlist = navigationGson.fromJson(
            playlistJson,
            YouTubeMusicPlaylist::class.java
        )
        renderScene(
            Destinations.YouTubeMusicPlaylistDetail.route
        ) {
            YouTubeMusicPlaylistDetailScreen(
                playlist = playlist,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                offlineMode = offlineMode
            )
        }
    }

}

private fun NavGraphBuilder.registerBiliDetailRoutes(
    navController: NavHostController,
    coherentFeedbackEnabled: Boolean,
    offlineMode: Boolean,
    playBiliAudio: (List<BiliVideoItem>, Int, String?) -> Unit,
    playBiliParts: (BiliClient.VideoBasicInfo, Int, String, String?) -> Unit,
    renderScene: @Composable AnimatedContentScope.(String?, @Composable () -> Unit) -> Unit
) {
    composable(
        route = Destinations.BiliPlaylistDetail.route,
        arguments = listOf(navArgument("playlistJson") {
            type = NavType.StringType
        }),
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val playlist = navigationGson.fromJson(
            biliPlaylistRouteJson(backStackEntry), BiliPlaylist::class.java
        )
        val suppressBiliPlaylistVisibilityTransition =
            shouldUseInstantBiliUploaderPlaylistTransition(
                initialRoute = previousNavigationRoute(navController),
                targetRoute = Destinations.BiliPlaylistDetail.route
            )
        renderScene(
            Destinations.BiliPlaylistDetail.route
        ) {
            BiliPlaylistDetailScreen(
                playlist = playlist,
                suppressVisibilityTransition =
                    suppressBiliPlaylistVisibilityTransition,
                onBack = { navController.popBackStack() },
                onPlayAudio = { videos, index ->
                    playBiliAudio(
                        videos,
                        index,
                        biliPlaylistSourceRoute(playlist)
                    )
                },
                onPlayParts = { videoInfo, index, coverUrl ->
                    playBiliParts(
                        videoInfo,
                        index,
                        coverUrl,
                        biliPlaylistSourceRoute(playlist)
                    )
                },
                offlineMode = offlineMode
            )
        }
    }

    composable(
        route = Destinations.BiliUploaderDetail.route,
        arguments = listOf(navArgument("uploaderJson") {
            type = NavType.StringType
        }),
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val uploaderJson = backStackEntry.arguments
            ?.getString("uploaderJson")
        val uploader = navigationGson.fromJson(
            uploaderJson,
            BiliUploaderSummary::class.java
        )
        renderScene(
            Destinations.BiliUploaderDetail.route
        ) {
            BiliUploaderDetailScreen(
                uploader = uploader,
                onBack = { navController.popBackStack() },
                onPlayAudio = { videos, index ->
                    playBiliAudio(
                        videos,
                        index,
                        biliUploaderSourceRoute(uploader)
                    )
                },
                onPlayParts = { videoInfo, index, coverUrl ->
                    playBiliParts(
                        videoInfo,
                        index,
                        coverUrl,
                        biliUploaderSourceRoute(uploader)
                    )
                },
                onContentClick = { playlist ->
                    navController.navigate(
                        biliPlaylistSourceRoute(playlist)
                    ) {
                        launchSingleTop = true
                    }
                },
                offlineMode = offlineMode
            )
        }
    }

}

private fun NavGraphBuilder.registerExploreLibraryTabsRoutes(
    coherentFeedbackEnabled: Boolean
) {
    composable(
        Destinations.Explore.route,
        enterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        }
    ) {}

    composable(
        Destinations.Library.route,
        enterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        }
    ) {}

}

private fun NavGraphBuilder.registerLibraryDetailRoutes(
    navController: NavHostController,
    coherentFeedbackEnabled: Boolean,
    offlineMode: Boolean,
    playSongs: (List<SongItem>, Int, String?) -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit,
    renderScene: @Composable AnimatedContentScope.(String?, @Composable () -> Unit) -> Unit
) {
    composable(
        route = Destinations.LocalPlaylistDetail.route,
        arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("playlistId") ?: 0L
        renderScene(
            Destinations.LocalPlaylistDetail.route
        ) {
            LocalPlaylistDetailScreen(
                playlistId = id,
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                onSongClick = { songs, index ->
                    playSongs(
                        songs,
                        index,
                        localPlaylistSourceRoute(id)
                    )
                },
                offlineMode = offlineMode
            )
        }
    }

    composable(
        route = Destinations.Recent.route,
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.Recent.route) {
            RecentScreen(
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                offlineMode = offlineMode
            )
        }
    }

    composable(
        route = Destinations.PlaybackStats.route,
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.PlaybackStats.route) {
            PlaybackStatsScreen(
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                offlineMode = offlineMode
            )
        }
    }

}

private fun NavGraphBuilder.registerSettingsTabRoutes(
    coherentFeedbackEnabled: Boolean
) {
    composable(
        Destinations.Settings.route,
        enterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        }
    ) {}

}

private fun NavGraphBuilder.registerDownloadRoutes(
    navController: NavHostController,
    coherentFeedbackEnabled: Boolean,
    offlineMode: Boolean,
    renderScene: @Composable AnimatedContentScope.(String?, @Composable () -> Unit) -> Unit
) {
    composable(
        route = Destinations.DownloadManager.route,
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) {
        val downloadManagerListState = rememberSaveable(
            saver = LazyListState.Saver
        ) { LazyListState() }
        renderScene(Destinations.DownloadManager.route) {
            DownloadManagerScreen(
                onBack = { navController.popBackStack() },
                onOpenDownloadProgress = {
                    navController.navigate(
                        Destinations.DownloadProgress.route
                    )
                },
                listState = downloadManagerListState,
                offlineMode = offlineMode
            )
        }
    }

    composable(
        route = Destinations.DownloadProgress.route,
        enterTransition = {
            transparentDetailEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            transparentDetailExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            transparentDetailPopEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            transparentDetailPopExitTransition(coherentFeedbackEnabled)
        }
    ) {
        val downloadProgressListState = rememberSaveable(
            saver = LazyListState.Saver
        ) { LazyListState() }
        renderScene(Destinations.DownloadProgress.route) {
            DownloadProgressScreen(
                onBack = { navController.popBackStack() },
                listState = downloadProgressListState
            )
        }
    }

}

private fun NavGraphBuilder.registerDebugTabRoutes(
    coherentFeedbackEnabled: Boolean
) {
    composable(
        Destinations.Debug.route,
        enterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            mainTabEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            mainTabExitTransition(coherentFeedbackEnabled)
        }
    ) {}

}

private fun NavGraphBuilder.registerDebugToolRoutes(
    navController: NavHostController,
    coherentFeedbackEnabled: Boolean,
    renderScene: @Composable AnimatedContentScope.(String?, @Composable () -> Unit) -> Unit
) {
    composable(
        route = Destinations.DebugListenTogether.route,
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.DebugListenTogether.route) {
            ListenTogetherDebugScreen()
        }
    }

    composable(
        route = Destinations.DebugUsbExclusive.route,
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.DebugUsbExclusive.route) {
            UsbExclusiveDebugScreen()
        }
    }

    composable(
        route = Destinations.DebugYouTube.route,
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.DebugYouTube.route) {
            YouTubeApiProbeScreen()
        }
    }

    composable(
        route = Destinations.DebugBili.route,
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.DebugBili.route) {
            BiliApiProbeScreen()
        }
    }

    composable(
        route = Destinations.DebugNetease.route,
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.DebugNetease.route) {
            NeteaseApiProbeScreen()
        }
    }

    composable(
        route = Destinations.DebugSearch.route,
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.DebugSearch.route) {
            SearchApiProbeScreen()
        }
    }

    composable(
        route = Destinations.DebugLogsList.route,
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.DebugLogsList.route) {
            LogListScreen(
                onBack = { navController.popBackStack() },
                onLogFileClick = { filePath ->
                    navController.navigate(
                        Destinations.DebugLogViewer.createRoute(filePath)
                    )
                }
            )
        }
    }

    composable(
        route = Destinations.DebugCrashLogsList.route,
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) {
        renderScene(Destinations.DebugCrashLogsList.route) {
            CrashLogListScreen(
                onBack = { navController.popBackStack() },
                onLogFileClick = { filePath ->
                    navController.navigate(
                        Destinations.DebugLogViewer.createRoute(filePath)
                    )
                }
            )
        }
    }

    composable(
        route = Destinations.DebugLogViewer.route,
        arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
        enterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        exitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        },
        popEnterTransition = {
            debugNavigationEnterTransition(coherentFeedbackEnabled)
        },
        popExitTransition = {
            debugNavigationExitTransition(coherentFeedbackEnabled)
        }
    ) { backStackEntry ->
        val filePath = debugLogViewerFilePath(backStackEntry.arguments?.getString("filePath"))
        renderScene(Destinations.DebugLogViewer.route) {
            LogViewerScreen(
                filePath = filePath,
                onBack = { navController.popBackStack() }
            )
        }
    }

}

private fun biliPlaylistRouteJson(entry: NavBackStackEntry): String? =
    entry.arguments?.getString("playlistJson")

private fun previousNavigationRoute(navController: NavHostController): String? =
    navController.previousBackStackEntry?.let { it.destination.route }

internal fun debugLogViewerFilePath(path: String?): String = path.orEmpty()
