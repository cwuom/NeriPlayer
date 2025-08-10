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
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.gson.Gson
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.AudioPlayerService
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.component.NeriBottomBar
import moe.ouom.neriplayer.ui.component.NeriMiniPlayer
import moe.ouom.neriplayer.ui.screen.NowPlayingScreen
import moe.ouom.neriplayer.ui.screen.PlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.host.ExploreHostScreen
import moe.ouom.neriplayer.ui.screen.tab.HomeScreen
import moe.ouom.neriplayer.ui.screen.tab.LibraryScreen
import moe.ouom.neriplayer.ui.screen.tab.SettingsScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.view.HyperBackground
import moe.ouom.neriplayer.ui.viewmodel.NeteasePlaylist
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NeriApp(
    onIsDarkChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }

    val followSystemDark by repo.followSystemDarkFlow.collectAsState(initial = true)
    val dynamicColorEnabled by repo.dynamicColorFlow.collectAsState(initial = true)
    val forceDark by repo.forceDarkFlow.collectAsState(initial = false)

    var showNowPlaying by rememberSaveable { mutableStateOf(false) }

    val isDark = when {
        forceDark -> true
        followSystemDark -> isSystemInDarkTheme()
        else -> false
    }
    LaunchedEffect(Unit) {
        PlayerManager.initialize(context.applicationContext as Application)
        Log.d("NERI-App","PlayerManager.initialize called")
    }

    LaunchedEffect(isDark) { onIsDarkChanged(isDark) }

    val scope = rememberCoroutineScope()
    val preferredQuality by repo.audioQualityFlow.collectAsState(initial = "exhigh")

    NeriTheme(
        followSystemDark = followSystemDark,
        forceDark = forceDark,
        dynamicColor = dynamicColorEnabled
    ) {
        val navController = rememberNavController()
        val backEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backEntry?.destination?.route

        val showBottomBar = !showNowPlaying

        val snackbarHostState = remember { SnackbarHostState() }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (showBottomBar) {
                        Column {
                            val currentSong by PlayerManager.currentSongFlow.collectAsState()
                            val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
                            NeriMiniPlayer(
                                title = currentSong?.name ?: "暂无播放",
                                artist = currentSong?.artist ?: "",
                                coverUrl = currentSong?.coverUrl,
                                isPlaying = isPlaying,
                                onPlayPause = { PlayerManager.togglePlayPause() },
                                onExpand = { showNowPlaying = true }
                            )
                            NeriBottomBar(
                                items = listOf(
                                    Destinations.Home to Icons.Outlined.Home,
                                    Destinations.Explore to Icons.Outlined.Search,
                                    Destinations.Library to Icons.Outlined.LibraryMusic,
                                    Destinations.Settings to Icons.Outlined.Settings,
                                ),
                                currentDestination = backEntry?.destination,
                                onItemSelected = { dest ->
                                    if (currentRoute != dest.route) {
                                        navController.navigate(dest.route) {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = Destinations.Home.route,
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable(Destinations.Home.route,
                        exitTransition = { fadeOut(animationSpec = tween(160)) },
                        popEnterTransition = { slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn() }
                    ) {
                        val gridState = rememberSaveable(saver = LazyGridState.Saver) {
                            LazyGridState()
                        }

                        HomeScreen(
                            gridState = gridState,
                            onItemClick = { playlist ->
                                val playlistJson = URLEncoder.encode(Gson().toJson(playlist), StandardCharsets.UTF_8.name())
                                navController.navigate("playlist_detail/$playlistJson")
                            }
                        )
                    }

                    composable(
                        route = "playlist_detail/{playlistJson}",
                        arguments = listOf(navArgument("playlistJson") { type = NavType.StringType }),
                        enterTransition = { slideInVertically(animationSpec = tween(220)) { it } + fadeIn() },
                        popExitTransition = { slideOutVertically(animationSpec = tween(240)) { it } + fadeOut() }
                    ) { backStackEntry ->
                        val playlistJson = backStackEntry.arguments?.getString("playlistJson")
                        val playlist = Gson().fromJson(playlistJson, NeteasePlaylist::class.java)

                        PlaylistDetailScreen(
                            playlist = playlist,
                            onBack = { navController.popBackStack() },
                            onSongClick = { songs, index ->
                                ContextCompat.startForegroundService(
                                    context,
                                    Intent(context, AudioPlayerService::class.java).apply {
                                        action = AudioPlayerService.ACTION_PLAY
                                        putParcelableArrayListExtra("playlist", ArrayList(songs))
                                        putExtra("index", index)
                                    }
                                )
                                showNowPlaying = true
                            }
                        )
                    }

                    composable(Destinations.Explore.route) {
                        ExploreHostScreen(onSongClick = { songs, index ->
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, AudioPlayerService::class.java).apply {
                                    action = AudioPlayerService.ACTION_PLAY
                                    putParcelableArrayListExtra("playlist", ArrayList(songs))
                                    putExtra("index", index)
                                }
                            )
                            showNowPlaying = true
                        })
                    }
                    composable(Destinations.Library.route) {
                        LibraryScreen(onPlay = { showNowPlaying = true })
                    }
                    composable(Destinations.Settings.route) {
                        SettingsScreen(
                            dynamicColor = dynamicColorEnabled,
                            onDynamicColorChange = { scope.launch { repo.setDynamicColor(it) } },
                            followSystemDark = followSystemDark,
                            forceDark = forceDark,
                            onForceDarkChange = { scope.launch { repo.setForceDark(it) } },
                            preferredQuality = preferredQuality,
                            onQualityChange = { scope.launch { repo.setAudioQuality(it) } }
                        )
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
                    HyperBackground(
                        modifier = Modifier.matchParentSize(),
                        isDark = isDark
                    )
                    NowPlayingScreen(
                        onNavigateUp = { showNowPlaying = false }
                    )
                }
            }
        }
    }
}