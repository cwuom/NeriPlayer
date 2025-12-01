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
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.gson.Gson
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.AudioPlayerService
import moe.ouom.neriplayer.core.player.AudioReactive
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.ThemeDefaults
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.component.NeriBottomBar
import moe.ouom.neriplayer.ui.component.NeriMiniPlayer
import moe.ouom.neriplayer.ui.screen.DownloadManagerScreen
import moe.ouom.neriplayer.ui.screen.NowPlayingScreen
import moe.ouom.neriplayer.ui.screen.debug.BiliApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.DebugHomeScreen
import moe.ouom.neriplayer.ui.screen.debug.LogListScreen
import moe.ouom.neriplayer.ui.screen.debug.NeteaseApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.SearchApiProbeScreen
import moe.ouom.neriplayer.ui.screen.host.ExploreHostScreen
import moe.ouom.neriplayer.ui.screen.host.HomeHostScreen
import moe.ouom.neriplayer.ui.screen.host.LibraryHostScreen
import moe.ouom.neriplayer.ui.screen.host.SettingsHostScreen
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.view.HyperBackground
import moe.ouom.neriplayer.ui.viewmodel.debug.LogViewerScreen
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist
import moe.ouom.neriplayer.util.ExceptionHandler
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.syncHapticFeedbackSetting
import androidx.palette.graphics.Palette
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.ui.screen.RecentScreen

private fun adjustAccent(base: Color, isDark: Boolean): Color {
    val r = (base.red * 255).toInt().coerceIn(0, 255)
    val g = (base.green * 255).toInt().coerceIn(0, 255)
    val b = (base.blue * 255).toInt().coerceIn(0, 255)
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(r, g, b, hsl)

    val targetS = if (isDark) {
        (hsl[1] * 0.38f).coerceAtMost(0.30f)
    } else {
        (hsl[1] * 0.32f).coerceAtMost(0.24f)
    }

    val targetL = if (isDark) {
        hsl[2].coerceIn(0.18f, 0.26f)
    } else {
        0.90f
    }

    val outInt = ColorUtils.HSLToColor(floatArrayOf(hsl[0], targetS, targetL))

    val neutralInt = if (isDark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
    val mixed = ColorUtils.blendARGB(
        outInt,
        neutralInt,
        if (isDark) 0.22f else 0.28f
    )

    return Color(mixed)
}

/**
 * 根据封面提取播放界面强调色
 */
@Composable
private fun NowPlayingAccentBackdrop(
    coverUrl: String?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onAccentChanged: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val fallback = MaterialTheme.colorScheme.background
    var target by remember(coverUrl) { mutableStateOf<Color?>(null) }

    LaunchedEffect(coverUrl) {
        if (coverUrl.isNullOrEmpty()) {
            target = null
            onAccentChanged(null)
            return@LaunchedEffect
        }
        val loader = ImageLoader(context)
        val req = ImageRequest.Builder(context)
            .data(coverUrl)
            .allowHardware(false)
            .build()

        val result = withContext(Dispatchers.IO) { loader.execute(req) }
        val bmp = (result as? SuccessResult)?.drawable.let { it as? BitmapDrawable }?.bitmap
        target = bmp?.let {
            val p = Palette.from(it).clearFilters().generate()
            val rgb = p.getVibrantColor(
                p.getMutedColor(
                    p.getDominantColor(fallback.toArgb())
                )
            )
            adjustAccent(Color(rgb), isDark)
        }
    }

    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = target ?: fallback,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "accent-bg"
    )

    val vignetteAlpha by animateFloatAsState(
        targetValue = if (isDark) 0.12f else 0.25f, // 暗色更强一点，亮色很轻
        animationSpec = tween(300),
        label = "vignette-alpha"
    )

    val whiteMaskAlpha by animateFloatAsState(
        targetValue = if (isDark) 0f else 0.05f,
        animationSpec = tween(300),
        label = "white-mask-alpha"
    )

    Box(
        modifier = modifier
            .background(bgColor)
            .drawWithContent {
                drawContent()
                // 顶部黑色渐隐
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = vignetteAlpha),
                            Color.Transparent
                        )
                    )
                )
                // 亮色模式白色遮罩，整体柔化
                if (whiteMaskAlpha > 0f) {
                    drawRect(Color.White.copy(alpha = whiteMaskAlpha))
                }
            }
    )
}

@Composable
fun NeriApp(
    onIsDarkChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val fallbackPrimary = MaterialTheme.colorScheme.primary.toArgb()
    val repo = remember { AppContainer.settingsRepo }

    val followSystemDark by repo.followSystemDarkFlow.collectAsState(initial = true)
    val dynamicColorEnabled by repo.dynamicColorFlow.collectAsState(initial = true)
    val forceDark by repo.forceDarkFlow.collectAsState(initial = false)
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    val devModeEnabled by repo.devModeEnabledFlow.collectAsState(initial = false)
    val themeSeedColor by repo.themeSeedColorFlow.collectAsState(initial = ThemeDefaults.DEFAULT_SEED_COLOR_HEX)
    val themeColorPalette by repo.themeColorPaletteFlow.collectAsState(initial = ThemeDefaults.PRESET_COLORS)
    val lyricBlurEnabled by repo.lyricBlurEnabledFlow.collectAsState(initial = true)
    val lyricFontScale by repo.lyricFontScaleFlow.collectAsState(initial = 1.0f)
    val uiDensityScale by repo.uiDensityScaleFlow.collectAsState(initial = 1.0f)
    val bypassProxy by repo.bypassProxyFlow.collectAsState(initial = true)
    val backgroundImageUri by repo.backgroundImageUriFlow.collectAsState(initial = null)
    val backgroundImageBlur by repo.backgroundImageBlurFlow.collectAsState(initial = 10f)
    val backgroundImageAlpha by repo.backgroundImageAlphaFlow.collectAsState(initial = 0.3f)
    val hapticFeedbackEnabled by repo.hapticFeedbackEnabledFlow.collectAsState(initial = true)
    val maxCacheSizeBytes by repo.maxCacheSizeBytesFlow.collectAsState(initial = 1024L * 1024 * 1024)
    val hazeState = remember { HazeState() }

    var coverSeedHex by remember { mutableStateOf<String?>(null) }   // 形如 "RRGGBB"
    val currentSong by PlayerManager.currentSongFlow.collectAsState()

    LaunchedEffect(Unit) {
        val initialCacheSize = repo.maxCacheSizeBytesFlow.first()
        PlayerManager.initialize(context.applicationContext as Application, initialCacheSize)


        // 跳过初始值，订阅之后的变更，每次切曲写入最近播放
        PlayerManager.currentSongFlow
            .drop(1)
            .filterNotNull()
            .collect { song ->
                AppContainer.playHistoryRepo.record(song)
            }
    }


    LaunchedEffect(currentSong?.coverUrl, dynamicColorEnabled, fallbackPrimary) {
        if (!dynamicColorEnabled) {
            coverSeedHex = null
            return@LaunchedEffect
        }
        val url = currentSong?.coverUrl
        if (url.isNullOrBlank()) {
            coverSeedHex = null
            return@LaunchedEffect
        }

        val loader = ImageLoader(context)
        val req = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val result = withContext(Dispatchers.IO) { loader.execute(req) }
        val bmp = (result as? SuccessResult)?.drawable.let { it as? BitmapDrawable }?.bitmap

        coverSeedHex = bmp?.let { bitmap ->
            val p = Palette.from(bitmap).clearFilters().generate()
            val base = p.getVibrantColor(
                p.getMutedColor(
                    p.getDominantColor(fallbackPrimary)
                )
            )
            val r = (base shr 16) and 0xFF
            val g = (base shr 8) and 0xFF
            val b = base and 0xFF
            String.format("%02X%02X%02X", r, g, b)
        }
    }

    // 同步触感反馈设置
    LaunchedEffect(hapticFeedbackEnabled) {
        syncHapticFeedbackSetting(hapticFeedbackEnabled)
    }

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
        val effectiveSeedHex = coverSeedHex ?: themeSeedColor
        val useSystemDynamic = dynamicColorEnabled && coverSeedHex == null

        NeriTheme(
            followSystemDark = followSystemDark,
            forceDark = forceDark,
            dynamicColor = useSystemDynamic,
            seedColorHex = effectiveSeedHex
        ) {
            val navController = rememberNavController()
            val backEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backEntry?.destination?.route

            val snackbarHostState = remember { SnackbarHostState() }

            DisposableEffect(showNowPlaying) {
                AudioReactive.enabled = showNowPlaying
                onDispose { AudioReactive.enabled = false }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val modifier = if (backgroundImageUri == null) {
                    Modifier
                } else Modifier
                    .haze(
                        hazeState,
                        HazeStyle(
                            tint = MaterialTheme.colorScheme.onSurface.copy(.0f),
                            blurRadius = 30.dp,
                            noiseFactor = HazeDefaults.noiseFactor
                        )
                    )

                CustomBackground(
                    imageUri = backgroundImageUri,
                    blur = backgroundImageBlur,
                    alpha = backgroundImageAlpha,
                    modifier = modifier
                )

                val containerColor = if (backgroundImageUri == null) {
                    MaterialTheme.colorScheme.background
                } else Color.Transparent

                val selectAlpha = if (backgroundImageUri == null) 1f else 0f

                val currentSong by PlayerManager.currentSongFlow.collectAsState()
                val isMiniPlayerVisible = currentSong != null && !showNowPlaying
                val isPlaying by PlayerManager.isPlayingFlow.collectAsState()

                val miniPlayerHeightDp = if (isMiniPlayerVisible) {
                    with(finalDensity) { miniPlayerHeightPx.toDp() }
                } else 0.dp

                CompositionLocalProvider(LocalMiniPlayerHeight provides miniPlayerHeightDp) {
                    Scaffold(
                        containerColor = containerColor,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        snackbarHost = {
                            val miniH = LocalMiniPlayerHeight.current
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier
                                    .padding(bottom = miniH)
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .imePadding()
                            )
                        },
                        bottomBar = {
                            AnimatedVisibility(
                                visible = !showNowPlaying,
                                enter = slideInVertically { it },
                                exit = slideOutVertically { it }
                            ) {
                                NeriBottomBar(
                                    modifier = Modifier.hazeChild(state = hazeState),
                                    selectAlpha = selectAlpha,
                                    items = buildList {
                                        add(Destinations.Home to Icons.Outlined.Home)
                                        add(Destinations.Explore to Icons.Outlined.Search)
                                        add(Destinations.Library to Icons.Outlined.LibraryMusic)
                                        add(Destinations.Settings to Icons.Outlined.Settings)
                                        if (devModeEnabled) add(Destinations.Debug to Icons.Outlined.BugReport)
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
                        Box(
                            modifier = Modifier.padding(
                                bottom = innerPadding.calculateBottomPadding()
                                    .coerceAtLeast(0.dp)
                            ).clipToBounds()
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = Destinations.Home.route,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .haze(
                                        hazeState,
                                        HazeStyle(
                                            tint = MaterialTheme.colorScheme.onSurface.copy(.0f),
                                            blurRadius = 30.dp,
                                            noiseFactor = HazeDefaults.noiseFactor
                                        )
                                    )
                            ) {
                                composable(
                                    Destinations.Home.route,
                                    enterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.85f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    exitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.95f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    },
                                    popEnterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.95f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    popExitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.85f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    }
                                ) {
                                    HomeHostScreen(
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
                                    route = Destinations.PlaylistDetail.route,
                                    arguments = listOf(navArgument("playlistJson") {
                                        type = NavType.StringType
                                    }),
                                    enterTransition = {
                                        slideInVertically(animationSpec = tween(220)) { it } + fadeIn()
                                    },
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = {
                                        slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()
                                    },
                                    popExitTransition = {
                                        slideOutVertically(animationSpec = tween(240)) { it } + fadeOut()
                                    }
                                ) { backStackEntry ->
                                    val playlistJson = backStackEntry.arguments?.getString("playlistJson")
                                    val playlist = Gson().fromJson(playlistJson, NeteasePlaylist::class.java)
                                    NeteasePlaylistDetailScreen(
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

                                composable(
                                    route = Destinations.NeteaseAlbumDetail.route,
                                    arguments = listOf(navArgument("playlistJson") {
                                        type = NavType.StringType
                                    }),
                                    enterTransition = {
                                        slideInVertically(animationSpec = tween(220)) { it } + fadeIn()
                                    },
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = {
                                        slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()
                                    },
                                    popExitTransition = {
                                        slideOutVertically(animationSpec = tween(240)) { it } + fadeOut()
                                    }
                                ) { backStackEntry ->
                                    val playlistJson = backStackEntry.arguments?.getString("playlistJson")
                                    val album = Gson().fromJson(playlistJson, NeteaseAlbum::class.java)
                                    NeteaseAlbumDetailScreen(
                                        album = album,
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
                                
                                composable(
                                    route = Destinations.BiliPlaylistDetail.route,
                                    arguments = listOf(navArgument("playlistJson") {
                                        type = NavType.StringType
                                    }),
                                    enterTransition = {
                                        slideInVertically(animationSpec = tween(220)) { it } + fadeIn()
                                    },
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = {
                                        slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()
                                    },
                                    popExitTransition = {
                                        slideOutVertically(animationSpec = tween(240)) { it } + fadeOut()
                                    }
                                ) { backStackEntry ->
                                    val playlistJson = backStackEntry.arguments?.getString("playlistJson")
                                    val playlist = Gson().fromJson(playlistJson, BiliPlaylist::class.java)
                                    BiliPlaylistDetailScreen(
                                        playlist = playlist,
                                        onBack = { navController.popBackStack() },
                                        onPlayAudio = { videos, index ->
                                            NPLogger.d("NERI-App", "Playing audio from Bili video: ${videos[index].title}")
                                            PlayerManager.playBiliVideoAsAudio(videos, index)
                                            showNowPlaying = true
                                        },
                                        onPlayParts = { videoInfo, index, coverUrl ->
                                            NPLogger.d("NERI-App", "Playing parts from Bili video: ${videoInfo.title}")
                                            PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
                                            showNowPlaying = true
                                        }
                                    )
                                }

                                composable(
                                    Destinations.Explore.route,
                                    enterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.85f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    exitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.95f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    },
                                    popEnterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.95f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    popExitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.85f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    }
                                ) {
                                    ExploreHostScreen(
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
                                        },
                                        onPlayParts = { videoInfo, index, coverUrl ->
                                            PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
                                            showNowPlaying = true
                                        }
                                    )
                                }

                                composable(
                                    Destinations.Library.route,
                                    enterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.85f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    exitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.95f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    },
                                    popEnterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.95f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    popExitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.85f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    }
                                ) {
                                    LibraryHostScreen(
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
                                        },
                                        onPlayParts = { videoInfo, index, coverUrl ->
                                            PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
                                            showNowPlaying = true
                                        },
                                        onOpenRecent = { navController.navigate(Destinations.Recent.route) }
                                    )
                                }

                                composable(
                                    route = Destinations.LocalPlaylistDetail.route,
                                    arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                                    enterTransition = {
                                        slideInVertically(animationSpec = tween(220)) { it } + fadeIn()
                                    },
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = {
                                        slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()
                                    },
                                    popExitTransition = {
                                        slideOutVertically(animationSpec = tween(240)) { it } + fadeOut()
                                    }
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
                                                    putParcelableArrayListExtra("playlist", ArrayList(songs))
                                                    putExtra("index", index)
                                                }
                                            )
                                            showNowPlaying = true
                                        }
                                    )
                                }

                                composable(
                                    route = Destinations.Recent.route,
                                    enterTransition = { slideInVertically(animationSpec = tween(220)) { it } + fadeIn() },
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = { slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn() },
                                    popExitTransition = { slideOutVertically(animationSpec = tween(240)) { it } + fadeOut() }
                                ) {
                                    RecentScreen(
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

                                composable(
                                    Destinations.Settings.route,
                                    enterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.85f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    exitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.95f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    },
                                    popEnterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.95f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    popExitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.85f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    }
                                ) {
                                    SettingsHostScreen(
                                        dynamicColor = dynamicColorEnabled,
                                        onDynamicColorChange = { scope.launch { repo.setDynamicColor(it) } },
                                        forceDark = forceDark,
                                        onForceDarkChange = { scope.launch { repo.setForceDark(it) } },
                                        preferredQuality = preferredQuality,
                                        onQualityChange = { scope.launch { repo.setAudioQuality(it) } },
                                        biliPreferredQuality = biliPreferredQuality,
                                        onBiliQualityChange = { scope.launch { repo.setBiliAudioQuality(it) } },
                                        seedColorHex = themeSeedColor,
                                        onSeedColorChange = { hex -> scope.launch { repo.setThemeSeedColor(hex) } },
                                        themeColorPalette = themeColorPalette,
                                        onAddColorToPalette = { hex -> scope.launch { repo.addThemePaletteColor(hex) } },
                                        onRemoveColorFromPalette = { hex -> scope.launch { repo.removeThemePaletteColor(hex) } },
                                        devModeEnabled = devModeEnabled,
                                        onDevModeChange = { enabled -> scope.launch { repo.setDevModeEnabled(enabled) } },
                                        lyricBlurEnabled = lyricBlurEnabled,
                                        onLyricBlurEnabledChange = { enabled ->
                                            scope.launch { repo.setLyricBlurEnabled(enabled) }
                                        },
                                        lyricFontScale = lyricFontScale,
                                        onLyricFontScaleChange = { scale ->
                                            scope.launch { repo.setLyricFontScale(scale) }
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
                                        hapticFeedbackEnabled = hapticFeedbackEnabled,
                                        onHapticFeedbackEnabledChange = { enabled ->
                                            scope.launch {
                                                repo.setHapticFeedbackEnabled(enabled)
                                                syncHapticFeedbackSetting(enabled)
                                            }
                                        },
                                        maxCacheSizeBytes = maxCacheSizeBytes,
                                        onMaxCacheSizeBytesChange = { size ->
                                            scope.launch { repo.setMaxCacheSizeBytes(size) }
                                        },
                                        onClearCacheClick = { PlayerManager.clearCache() }
                                    )
                                }

                                composable(
                                    route = Destinations.DownloadManager.route,
                                    enterTransition = {
                                        slideInVertically(animationSpec = tween(220)) { it } + fadeIn()
                                    },
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = {
                                        slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()
                                    },
                                    popExitTransition = {
                                        slideOutVertically(animationSpec = tween(240)) { it } + fadeOut()
                                    }
                                ) {
                                    DownloadManagerScreen(onBack = { navController.popBackStack() })
                                }

                                composable(
                                    Destinations.Debug.route,
                                    enterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.85f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    exitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.95f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    },
                                    popEnterTransition = {
                                        scaleIn(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            ),
                                            initialScale = 0.95f
                                        ) + fadeIn(animationSpec = tween(300, easing = EaseInOutCubic))
                                    },
                                    popExitTransition = {
                                        scaleOut(
                                            animationSpec = tween(200, easing = EaseInOutCubic),
                                            targetScale = 0.85f
                                        ) + fadeOut(animationSpec = tween(200, easing = EaseInOutCubic))
                                    }
                                ) {
                                    DebugHomeScreen(
                                        onOpenBiliDebug = { navController.navigate(Destinations.DebugBili.route) },
                                        onOpenNeteaseDebug = { navController.navigate(Destinations.DebugNetease.route) },
                                        onOpenSearchDebug = { navController.navigate(Destinations.DebugSearch.route) },
                                        onOpenLogs = { navController.navigate(Destinations.DebugLogsList.route) },
                                        onTestExceptionHandler = {
                                            ExceptionHandler.safeExecute("DebugTest") {
                                                throw RuntimeException("这是一个测试异常，用于验证异常处理器的功能")
                                            }
                                        },
                                        onHideDebugMode = {
                                            scope.launch { repo.setDevModeEnabled(false) }
                                            navController.navigate(Destinations.Settings.route) {
                                                popUpTo(Destinations.Debug.route) { inclusive = true }
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
                                                Destinations.DebugLogViewer.createRoute(filePath)
                                            )
                                        }
                                    )
                                }

                                composable(
                                    route = Destinations.DebugLogViewer.route,
                                    arguments = listOf(navArgument("filePath") { type = NavType.StringType })
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
                                    onHeightChanged = { heightInPixels -> miniPlayerHeightPx = heightInPixels },
                                    hazeState = hazeState,
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
                    val useSystemDynamic = dynamicColorEnabled && (coverSeedHex == null || currentSong == null)
                    val effectiveSeedHex = coverSeedHex ?: themeSeedColor

                    NeriTheme(
                        followSystemDark = false,
                        forceDark = true,
                        dynamicColor = useSystemDynamic,
                        seedColorHex = effectiveSeedHex
                    ) {
                        BackHandler { showNowPlaying = false }

                        val currentSongNP by PlayerManager.currentSongFlow.collectAsState()

                        Box(Modifier.fillMaxSize()) {
                            // 背景固定按暗色逻辑渲染
                            NowPlayingAccentBackdrop(
                                coverUrl = currentSongNP?.coverUrl,
                                isDark = true,
                                modifier = Modifier.fillMaxSize()
                            )

                            HyperBackground(
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = 0.80f },
                                isDark = true,
                                coverUrl = currentSongNP?.coverUrl
                            )

                            NowPlayingScreen(
                                onNavigateUp = { showNowPlaying = false },
                                onEnterAlbum = { album -> 
                                    navController.popBackStack()
                                    val json = Uri.encode(Gson().toJson(album))
                                    navController.navigate("netease_album_detail/$json")
                                },
                                lyricBlurEnabled = lyricBlurEnabled,
                                lyricFontScale = lyricFontScale,
                                onLyricFontScaleChange = { scale ->
                                    scope.launch { repo.setLyricFontScale(scale) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}