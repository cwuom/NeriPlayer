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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.components.NeriBottomBar
import moe.ouom.neriplayer.ui.components.NeriMiniPlayer
import moe.ouom.neriplayer.ui.screens.ExploreScreen
import moe.ouom.neriplayer.ui.screens.HomeHostScreen
import moe.ouom.neriplayer.ui.screens.LibraryScreen
import moe.ouom.neriplayer.ui.screens.NowPlayingScreen
import moe.ouom.neriplayer.ui.screens.SettingsScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme

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
    LaunchedEffect(isDark) { onIsDarkChanged(isDark) }

    val scope = rememberCoroutineScope()

    NeriTheme(
        followSystemDark = followSystemDark,
        forceDark = forceDark,
        dynamicColor = dynamicColorEnabled
    ) {
        val navController = rememberNavController()
        val backEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backEntry?.destination?.route

        val bottomDestinations = listOf(
            Destinations.Home, Destinations.Explore, Destinations.Library, Destinations.Settings
        )
        val showBottomBar = !showNowPlaying && bottomDestinations.any { it.route == currentRoute }

        val snackbarHostState = remember { SnackbarHostState() }

        SharedTransitionLayout {
            val coverSharedState = rememberSharedContentState(key = "cover_art")
            val titleSharedState = rememberSharedContentState(key = "title_meta")

            AnimatedVisibility(
                visible = !showNowPlaying,
                enter = fadeIn() + scaleIn(initialScale = 0.98f),
                exit = fadeOut() + scaleOut(targetScale = 1.02f)
            ) {
                val avScope: AnimatedVisibilityScope = this

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (showBottomBar) {
                            Column {
                                NeriMiniPlayer(
                                    title = "Chill Vibes",
                                    artist = "Neri",
                                    onExpand = { showNowPlaying = true },
                                    sharedScope = this@SharedTransitionLayout,
                                    animatedScope = avScope,
                                    coverSharedState = coverSharedState,
                                    titleSharedState = titleSharedState
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
                        composable(
                            route = Destinations.Home.route,
                            enterTransition = { fadeIn(animationSpec = tween(200)) },
                            exitTransition = { fadeOut(animationSpec = tween(200)) },
                            popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                            popExitTransition = { fadeOut(animationSpec = tween(200)) }
                        ) {
                            HomeHostScreen(onSongClick = { /* TODO 播放 */ })
                        }

                        composable(Destinations.Explore.route) {
                            ExploreScreen(onPlay = { showNowPlaying = true })
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
                                onForceDarkChange = { scope.launch { repo.setForceDark(it) } }
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showNowPlaying) {
                BackHandler { showNowPlaying = false }
                NowPlayingScreen(
                    onNavigateUp = { showNowPlaying = false },
                    sharedScope = this@SharedTransitionLayout,
                    animatedScope = this,
                    coverSharedState = coverSharedState,
                    titleSharedState = titleSharedState
                )
            }
        }
    }
}