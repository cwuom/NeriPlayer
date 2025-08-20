package moe.ouom.neriplayer.ui

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
 * File: moe.ouom.neriplayer.ui/NeriApp
 * Created: 2025/8/8
 */

import android.app.Application
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.gson.Gson
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.AudioPlayerService
import moe.ouom.neriplayer.core.player.AudioReactive
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.ThemeDefaults
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.component.NeriBottomBar
import moe.ouom.neriplayer.ui.component.NeriMiniPlayer
import moe.ouom.neriplayer.ui.screen.NowPlayingScreen
import moe.ouom.neriplayer.ui.screen.debug.BiliApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.DebugHomeScreen
import moe.ouom.neriplayer.ui.screen.debug.LogListScreen
import moe.ouom.neriplayer.ui.screen.debug.NeteaseApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.SearchApiProbeScreen
import moe.ouom.neriplayer.ui.screen.DownloadManagerScreen
import moe.ouom.neriplayer.ui.screen.host.ExploreHostScreen
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.tab.HomeScreen
import moe.ouom.neriplayer.ui.screen.tab.LibraryScreen
import moe.ouom.neriplayer.ui.screen.tab.SettingsScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.view.HyperBackground
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.debug.LogViewerScreen
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist
import moe.ouom.neriplayer.util.NPLogger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun NeriApp(
    onIsDarkChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember { AppContainer.settingsRepo }

    val followSystemDark by repo.followSystemDarkFlow.collectAsState(initial = true)
    val dynamicColorEnabled by repo.dynamicColorFlow.collectAsState(initial = true)
    val forceDark by repo.forceDarkFlow.collectAsState(initial = false)
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    val devModeEnabled by repo.devModeEnabledFlow.collectAsState(initial = false)
    val themeSeedColor by repo.themeSeedColorFlow.collectAsState(initial = ThemeDefaults.DEFAULT_SEED_COLOR_HEX)
    val lyricBlurEnabled by repo.lyricBlurEnabledFlow.collectAsState(initial = true)
    val uiDensityScale by repo.uiDensityScaleFlow.collectAsState(initial = 1.0f)
    val bypassProxy by repo.bypassProxyFlow.collectAsState(initial = true)
    val backgroundImageUri by repo.backgroundImageUriFlow.collectAsState(initial = null)
    val backgroundImageBlur by repo.backgroundImageBlurFlow.collectAsState(initial = 10f)
    val backgroundImageAlpha by repo.backgroundImageAlphaFlow.collectAsState(initial = 0.3f)


    val defaultDensity = LocalDensity.current
    var miniPlayerHeightPx by remember { mutableStateOf(0) }

    val finalDensity = remember(defaultDensity, uiDensityScale) {
        Density(
            density = defaultDensity.density * uiDensityScale,
            fontScale = defaultDensity.fontScale
        )
    }


    val isDark = when {
        forceDark -> true
        followSystemDark -> isSystemInDarkTheme()
        else -> false
    }
    LaunchedEffect(Unit) {
        // 初始化播放器并在已有播放队列时同步到前台服务
        PlayerManager.initialize(context.applicationContext as Application)
        NPLogger.d("NERI-App", "PlayerManager.initialize called")
        NPLogger.d("PlayerManager.hasItems()", PlayerManager.hasItems().toString())
        if (PlayerManager.hasItems()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AudioPlayerService::class.java).apply {
                    action = AudioPlayerService.ACTION_SYNC
                }
            )
        }
    }

    LaunchedEffect(isDark) { onIsDarkChanged(isDark) }

    val scope = rememberCoroutineScope()
    val preferredQuality by repo.audioQualityFlow.collectAsState(initial = "exhigh")
    val biliPreferredQuality by repo.biliAudioQualityFlow.collectAsState(initial = "high")


    CompositionLocalProvider(LocalDensity provides finalDensity) {
        NeriTheme(
            followSystemDark = followSystemDark,
            forceDark = forceDark,
            dynamicColor = dynamicColorEnabled,
            seedColorHex = themeSeedColor
        ) {
            val navController = rememberNavController()
            val backEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backEntry?.destination?.route

            val snackbarHostState = remember { SnackbarHostState() }


            DisposableEffect(showNowPlaying) {
                AudioReactive.enabled = showNowPlaying
                onDispose {
                    AudioReactive.enabled = false
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                CustomBackground(
                    imageUri = backgroundImageUri,
                    blur = backgroundImageBlur,
                    alpha = backgroundImageAlpha
                )

                val containerColor = if (backgroundImageUri == null) {
                    MaterialTheme.colorScheme.background
                } else Color.Transparent

                val currentSong by PlayerManager.currentSongFlow.collectAsState()
                val isMiniPlayerVisible = currentSong != null && !showNowPlaying
                val isPlaying by PlayerManager.isPlayingFlow.collectAsState()

                val miniPlayerHeightDp = if (isMiniPlayerVisible) {
                    with(finalDensity) { miniPlayerHeightPx.toDp() }
                } else {
                    0.dp
                }

                CompositionLocalProvider(LocalMiniPlayerHeight provides miniPlayerHeightDp) {
                    Scaffold(
                        containerColor = containerColor,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            AnimatedVisibility(
                                visible = !showNowPlaying,
                                enter = slideInVertically { it },
                                exit = slideOutVertically { it }
                            ) {
                                NeriBottomBar(
                                    items = buildList {
                                        add(Destinations.Home to Icons.Outlined.Home)
                                        add(Destinations.Explore to Icons.Outlined.Search)
                                        add(Destinations.Library to Icons.Outlined.LibraryMusic)
                                        add(Destinations.Settings to Icons.Outlined.Settings)
                                        if (devModeEnabled) {
                                            add(Destinations.Debug to Icons.Outlined.BugReport)
                                        }
                                    },
                                    currentDestination = backEntry?.destination,
                                    onItemSelected = { dest ->
                                        if (currentRoute != dest.route) {
                                            navController.navigate(dest.route) {
                                                popUpTo(navController.graph.startDestinationId) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            NavHost(
                                navController = navController,
                                startDestination = Destinations.Home.route,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                composable(
                                    Destinations.Home.route,
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = { slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn() }
                                ) {
                                    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
                                    HomeScreen(
                                        gridState = gridState,
                                        onItemClick = { playlist ->
                                            val playlistJson = URLEncoder.encode(
                                                Gson().toJson(playlist),
                                                StandardCharsets.UTF_8.name()
                                            )
                                            navController.navigate(
                                                Destinations.PlaylistDetail.createRoute(
                                                    playlistJson
                                                )
                                            )
                                        }
                                    )
                                }

                                composable(
                                    route = Destinations.PlaylistDetail.route,
                                    arguments = listOf(navArgument("playlistJson") {
                                        type = NavType.StringType
                                    }),
                                    enterTransition = { slideInVertically(animationSpec = tween(220)) { it } + fadeIn() },
                                    popExitTransition = { slideOutVertically(animationSpec = tween(240)) { it } + fadeOut() }
                                ) { backStackEntry ->
                                    val playlistJson = backStackEntry.arguments?.getString("playlistJson")
                                    val playlist =
                                        Gson().fromJson(playlistJson, NeteasePlaylist::class.java)
                                    PlaylistDetailScreen(
                                        playlist = playlist,
                                        onBack = { navController.popBackStack() },
                                        onSongClick = { songs, index ->
                                            ContextCompat.startForegroundService(
                                                context,
                                                Intent(context, AudioPlayerService::class.java).apply {
                                                    action = AudioPlayerService.ACTION_PLAY
                                                    putParcelableArrayListExtra(
                                                        "playlist",
                                                        ArrayList(songs)
                                                    )
                                                    putExtra("index", index)
                                                }
                                            )
                                            showNowPlaying = true
                                        }
                                    )
                                }


                                composable(
                                    route = Destinations.BiliPlaylistDetail.route,
                                    arguments = listOf(navArgument("playlistJson") {
                                        type = NavType.StringType
                                    }),
                                    enterTransition = { slideInVertically(animationSpec = tween(220)) { it } + fadeIn() },
                                    popExitTransition = { slideOutVertically(animationSpec = tween(240)) { it } + fadeOut() }
                                ) { backStackEntry ->
                                    val playlistJson = backStackEntry.arguments?.getString("playlistJson")
                                    val playlist = Gson().fromJson(playlistJson, BiliPlaylist::class.java)
                                    BiliPlaylistDetailScreen(
                                        playlist = playlist,
                                        onBack = { navController.popBackStack() },
                                        onPlayAudio = { videos, index ->
                                            NPLogger.d(
                                                "NERI-App",
                                                "Playing audio from Bili video: ${videos[index].title}"
                                            )
                                            PlayerManager.playBiliVideoAsAudio(videos, index)
                                            showNowPlaying = true // 显示播放界面
                                        },
                                        onPlayParts = { videoInfo, index, coverUrl ->
                                            NPLogger.d(
                                                "NERI-App",
                                                "Playing parts from Bili video: ${videoInfo.title}"
                                            )
                                            PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
                                            showNowPlaying = true
                                        }
                                    )
                                }

                                composable(
                                    Destinations.Explore.route,
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = { slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn() }
                                ) {
                                    ExploreHostScreen(
                                        onSongClick = { songs, index ->
                                            ContextCompat.startForegroundService(
                                                context,
                                                Intent(context, AudioPlayerService::class.java).apply {
                                                    action = AudioPlayerService.ACTION_PLAY
                                                    putParcelableArrayListExtra(
                                                        "playlist",
                                                        ArrayList(songs)
                                                    )
                                                    putExtra("index", index)
                                                }
                                            )
                                            showNowPlaying = true
                                        },
                                        onPlayParts = { videoInfo, index, coverUrl ->
                                            PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
                                            showNowPlaying = true
                                        })
                                }

                                composable(
                                    Destinations.Library.route,
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = { slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn() }
                                ) {
                                    LibraryScreen(
                                        onLocalPlaylistClick = { playlist ->
                                            navController.navigate(
                                                Destinations.LocalPlaylistDetail.createRoute(
                                                    playlist.id
                                                )
                                            )
                                        },
                                        onNeteasePlaylistClick = { playlist ->
                                            val playlistJson = URLEncoder.encode(
                                                Gson().toJson(playlist),
                                                StandardCharsets.UTF_8.name()
                                            )
                                            navController.navigate(
                                                Destinations.PlaylistDetail.createRoute(
                                                    playlistJson
                                                )
                                            )
                                        },
                                        onBiliPlaylistClick = { playlist ->
                                            val playlistJson = URLEncoder.encode(
                                                Gson().toJson(playlist),
                                                StandardCharsets.UTF_8.name()
                                            )
                                            navController.navigate(
                                                Destinations.BiliPlaylistDetail.createRoute(
                                                    playlistJson
                                                )
                                            )
                                        }
                                    )
                                }



                                composable(
                                    route = Destinations.LocalPlaylistDetail.route,
                                    arguments = listOf(navArgument("playlistId") {
                                        type = NavType.LongType
                                    }),
                                    enterTransition = { slideInVertically(animationSpec = tween(220)) { it } + fadeIn() },
                                    popExitTransition = { slideOutVertically(animationSpec = tween(240)) { it } + fadeOut() }
                                ) { backStackEntry ->
                                    val id = backStackEntry.arguments?.getLong("playlistId") ?: 0L
                                    LocalPlaylistDetailScreen(
                                        playlistId = id,
                                        onBack = { navController.popBackStack() },
                                        onDeleted = { navController.popBackStack() },
                                        onSongClick = { songs, index ->
                                            ContextCompat.startForegroundService(
                                                context,
                                                Intent(context, AudioPlayerService::class.java).apply {
                                                    action = AudioPlayerService.ACTION_PLAY
                                                    putParcelableArrayListExtra(
                                                        "playlist",
                                                        ArrayList(songs)
                                                    )
                                                    putExtra("index", index)
                                                }
                                            )
                                            showNowPlaying = true
                                        }
                                    )
                                }

                                composable(Destinations.Settings.route) {
                                    SettingsScreen(
                                        dynamicColor = dynamicColorEnabled,
                                        onDynamicColorChange = { scope.launch { repo.setDynamicColor(it) } },
                                        forceDark = forceDark,
                                        onForceDarkChange = { scope.launch { repo.setForceDark(it) } },
                                        preferredQuality = preferredQuality,
                                        onQualityChange = { scope.launch { repo.setAudioQuality(it) } },
                                        biliPreferredQuality = biliPreferredQuality,
                                        onBiliQualityChange = {
                                            scope.launch { repo.setBiliAudioQuality(it) }
                                        },
                                        seedColorHex = themeSeedColor,
                                        onSeedColorChange = { hex ->
                                            scope.launch { repo.setThemeSeedColor(hex) }
                                        },
                                        devModeEnabled = devModeEnabled,
                                        onDevModeChange = { enabled ->
                                            scope.launch { repo.setDevModeEnabled(enabled) }
                                        },
                                        lyricBlurEnabled = lyricBlurEnabled,
                                        onLyricBlurEnabledChange = { enabled ->
                                            scope.launch { repo.setLyricBlurEnabled(enabled) }
                                        },
                                        uiDensityScale = uiDensityScale,
                                        onUiDensityScaleChange = { scale ->
                                            scope.launch { repo.setUiDensityScale(scale) }
                                        },
                                        bypassProxy = bypassProxy,
                                        onBypassProxyChange = { enabled ->
                                            scope.launch { repo.setBypassProxy(enabled) }
                                        },
                                        backgroundImageUri = backgroundImageUri,
                                        onBackgroundImageChange = { uri ->
                                            scope.launch { repo.setBackgroundImageUri(uri?.toString()) }
                                        },
                                        backgroundImageBlur = backgroundImageBlur,
                                        onBackgroundImageBlurChange = { blur ->
                                            scope.launch { repo.setBackgroundImageBlur(blur) }
                                        },
                                        backgroundImageAlpha = backgroundImageAlpha,
                                        onBackgroundImageAlphaChange = { alpha ->
                                            scope.launch { repo.setBackgroundImageAlpha(alpha) }
                                        },
                                        onNavigateToDownloadManager = {
                                            navController.navigate(Destinations.DownloadManager.route)
                                        }
                                    )
                                }
                                
                                composable(
                                    route = Destinations.DownloadManager.route,
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = { slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn() }
                                ) {
                                    DownloadManagerScreen(
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Destinations.Debug.route) {
                                    DebugHomeScreen(
                                        onOpenBiliDebug = { navController.navigate(Destinations.DebugBili.route) },
                                        onOpenNeteaseDebug = { navController.navigate(Destinations.DebugNetease.route) },
                                        onOpenSearchDebug = { navController.navigate(Destinations.DebugSearch.route) },
                                        onOpenLogs = { navController.navigate(Destinations.DebugLogsList.route) },
                                        onHideDebugMode = {
                                            scope.launch {
                                                repo.setDevModeEnabled(false)
                                            }
                                            navController.navigate(Destinations.Settings.route) {
                                                popUpTo(Destinations.Debug.route) {
                                                    inclusive = true
                                                }
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                }
                                composable(Destinations.DebugBili.route) { BiliApiProbeScreen() }
                                composable(Destinations.DebugNetease.route) { NeteaseApiProbeScreen() }
                                composable(Destinations.DebugSearch.route) { SearchApiProbeScreen() }
                                composable(Destinations.DebugLogsList.route) {
                                    LogListScreen(
                                        onBack = { navController.popBackStack() },
                                        onLogFileClick = { filePath ->
                                            navController.navigate(
                                                Destinations.DebugLogViewer.createRoute(
                                                    filePath
                                                )
                                            )
                                        }
                                    )
                                }

                                composable(
                                    route = Destinations.DebugLogViewer.route,
                                    arguments = listOf(navArgument("filePath") {
                                        type = NavType.StringType
                                    })
                                ) { backStackEntry ->
                                    val filePath = backStackEntry.arguments?.getString("filePath") ?: ""
                                    LogViewerScreen(
                                        filePath = filePath,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = currentSong != null && !showNowPlaying,
                                modifier = Modifier.align(Alignment.BottomStart),
                                enter = slideInVertically(
                                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                                    initialOffsetY = { it }
                                ) + fadeIn(animationSpec = tween(durationMillis = 200)) + scaleIn(
                                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                                    initialScale = 0.8f
                                ),
                                exit = slideOutVertically(
                                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                                    targetOffsetY = { it }
                                ) + fadeOut(animationSpec = tween(durationMillis = 150)) + scaleOut(
                                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                    targetScale = 0.8f
                                )
                            ) {
                                NeriMiniPlayer(
                                    title = currentSong?.name ?: "暂无播放",
                                    artist = currentSong?.artist ?: "",
                                    coverUrl = currentSong?.coverUrl,
                                    isPlaying = isPlaying,
                                    onPlayPause = { PlayerManager.togglePlayPause() },
                                    onExpand = { showNowPlaying = true },
                                    onHeightChanged = { heightInPixels ->
                                        miniPlayerHeightPx = heightInPixels
                                    }
                                )
                            }
                        }
                    }
                }



                AnimatedVisibility(
                    visible = showNowPlaying,
                    enter = slideInVertically(
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        initialOffsetY = { fullHeight -> fullHeight }
                    ) + fadeIn(animationSpec = tween(durationMillis = 150)),
                    exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                        targetOffsetY = { fullHeight -> fullHeight }
                    ) + fadeOut(animationSpec = tween(durationMillis = 150))
                ) {
                    BackHandler { showNowPlaying = false }
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(if (isDark) Color(0xFF121212) else Color.White)
                        )
                        HyperBackground(modifier = Modifier.matchParentSize(), isDark = isDark)
                        NowPlayingScreen(
                            onNavigateUp = { showNowPlaying = false },
                            lyricBlurEnabled = lyricBlurEnabled
                        )
                    }
                }
            }
        }
    }
}