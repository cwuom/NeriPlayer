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

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.Coil
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.google.gson.Gson
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.effects.AudioReactive
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.lifecycle.recoverUsbExclusivePlaybackOnForeground
import moe.ouom.neriplayer.core.player.lifecycle.updateUsbExclusiveForegroundState
import moe.ouom.neriplayer.core.player.policy.usb.shouldPromptForUsbExclusiveBackgroundPermission
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.core.startup.player.PlayerStartupBootstrapper
import moe.ouom.neriplayer.core.startup.player.PlayerStartupAudioFocusRefresher
import moe.ouom.neriplayer.core.startup.player.PlayerStartupHistoryRecorder
import moe.ouom.neriplayer.core.startup.player.PlayerStartupServiceSyncCoordinator
import moe.ouom.neriplayer.core.startup.theme.StartupThemeResolver
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.playbackVisualKey
import moe.ouom.neriplayer.data.model.playbackVisualKeyAliases
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.playlist.usage.UsageEntry
import moe.ouom.neriplayer.data.settings.DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP
import moe.ouom.neriplayer.data.settings.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.AdvancedBlurQualityPreference
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.LyricFontScales
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.isCurrentBuildDimensity
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotCached
import moe.ouom.neriplayer.data.storage.clearExtraStorageCaches
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.navigation.LauncherShortcutAction
import moe.ouom.neriplayer.navigation.LauncherShortcutRequest
import moe.ouom.neriplayer.navigation.launcherShortcutMainTabRoute
import moe.ouom.neriplayer.ui.component.navigation.NeriBottomBar
import moe.ouom.neriplayer.ui.component.navigation.resolveBottomBarSelectionAlpha
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayerDefaults
import moe.ouom.neriplayer.ui.component.playback.resolvePlaybackWaiting
import moe.ouom.neriplayer.ui.component.common.ThemeRevealOverlay
import moe.ouom.neriplayer.ui.component.common.blockUnderlyingTouches
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassNavigationHandoff
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneLayer
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassSceneZIndex
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassVisibilitySceneMotion
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.isAdvancedGlassBackendSupported
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.feedback.AppFeedbackHostEffect
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.screen.DownloadManagerScreen
import moe.ouom.neriplayer.ui.screen.DownloadProgressScreen
import moe.ouom.neriplayer.ui.screen.NowPlayingScreen
import moe.ouom.neriplayer.ui.screen.RecentScreen
import moe.ouom.neriplayer.ui.screen.PlaybackStatsScreen
import moe.ouom.neriplayer.ui.screen.debug.BiliApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.CrashLogListScreen
import moe.ouom.neriplayer.ui.screen.debug.DebugCrashTestType
import moe.ouom.neriplayer.ui.screen.debug.DebugHomeScreen
import moe.ouom.neriplayer.ui.screen.debug.ListenTogetherDebugScreen
import moe.ouom.neriplayer.ui.screen.debug.LogListScreen
import moe.ouom.neriplayer.ui.screen.debug.NeteaseApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.SearchApiProbeScreen
import moe.ouom.neriplayer.ui.screen.debug.UsbExclusiveDebugScreen
import moe.ouom.neriplayer.ui.screen.debug.YouTubeApiProbeScreen
import moe.ouom.neriplayer.ui.screen.artist.BiliUploaderDetailScreen
import moe.ouom.neriplayer.ui.screen.artist.NeteaseArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.artist.YouTubeMusicCreatorNavigationScreen
import moe.ouom.neriplayer.ui.screen.host.ExploreHostScreen
import moe.ouom.neriplayer.ui.screen.host.HomeHostScreen
import moe.ouom.neriplayer.ui.screen.host.LibraryHostScreen
import moe.ouom.neriplayer.ui.screen.host.SettingsHostScreen
import moe.ouom.neriplayer.ui.screen.host.rememberHomeHostRuntimeState
import moe.ouom.neriplayer.ui.screen.tab.shouldShowHomeContinueSection
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.YouTubeMusicPlaylistDetailScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.theme.rememberActualSystemDarkTheme
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.view.HyperBackground
import moe.ouom.neriplayer.ui.viewmodel.debug.LogViewerScreen
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.util.crash.AnrWatchdog
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.util.crash.NativeCrashHandler
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.platform.openAppBackgroundSettings
import moe.ouom.neriplayer.util.platform.readBackgroundBehaviorAllowance
import moe.ouom.neriplayer.util.platform.requestIgnoreBatteryOptimizationsCompat
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.util.format.formatFileSize
import moe.ouom.neriplayer.util.media.isRemoteImageSource
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.network.rememberOfflineModeState
import moe.ouom.neriplayer.ui.haptic.syncHapticFeedbackSetting
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

private val navigationGson: Gson by lazy(LazyThreadSafetyMode.PUBLICATION) { Gson() }
private val EmptyLauncherShortcutRequestFlow =
    MutableStateFlow<LauncherShortcutRequest?>(null)
private const val LAUNCHER_SHORTCUT_PLAYLIST_READY_TIMEOUT_MS = 5000L
internal const val MAIN_TAB_LAYER_Z_INDEX = 0f
internal const val NAV_HOST_LAYER_Z_INDEX = 1f
internal const val MINI_PLAYER_OVERLAY_Z_INDEX = 2f
internal data class BottomBarLayoutInsets(
    val navContentBottomPadding: Dp,
    val screenBottomInset: Dp,
    val miniPlayerBottomPadding: Dp
)

internal fun resolveBottomBarLayoutInsets(
    baseBlurRequested: Boolean,
    bottomBarInset: Dp,
    reservedMiniPlayerHeight: Dp
): BottomBarLayoutInsets = if (baseBlurRequested) {
    BottomBarLayoutInsets(
        navContentBottomPadding = 0.dp,
        screenBottomInset = reservedMiniPlayerHeight + bottomBarInset,
        miniPlayerBottomPadding = bottomBarInset
    )
} else {
    BottomBarLayoutInsets(
        navContentBottomPadding = bottomBarInset,
        screenBottomInset = reservedMiniPlayerHeight,
        miniPlayerBottomPadding = 0.dp
    )
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

internal fun localPlaylistIdFromSourceRoute(sourceRoute: String?): Long? {
    return sourceRoute
        ?.takeIf { it.startsWith("local_playlist_detail/") }
        ?.removePrefix("local_playlist_detail/")
        ?.toLongOrNull()
}

private data class HomeUsageSnapshot(
    val entries: List<UsageEntry> = emptyList(),
    val isLoaded: Boolean = false
)

@Composable
fun NeriApp(
    initialThemeSnapshot: ThemePreferenceSnapshot = ThemePreferenceSnapshot(),
    launcherShortcutRequestFlow: StateFlow<LauncherShortcutRequest?> =
        EmptyLauncherShortcutRequestFlow,
    onLauncherShortcutRequestConsumed: (LauncherShortcutRequest) -> Unit = {},
    onIsDarkChanged: (Boolean) -> Unit = {},
    onNowPlayingVisibilityChanged: (Boolean) -> Unit = {},
    onLanguageChanged: (LanguageManager.Language) -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AppContentMountGate(
            modifier = Modifier.fillMaxSize()
        ) {
            NeriAppContent(
                initialThemeSnapshot = initialThemeSnapshot,
                launcherShortcutRequestFlow = launcherShortcutRequestFlow,
                onLauncherShortcutRequestConsumed = onLauncherShortcutRequestConsumed,
                onIsDarkChanged = onIsDarkChanged,
                onNowPlayingVisibilityChanged = onNowPlayingVisibilityChanged,
                onLanguageChanged = onLanguageChanged
            )
        }
    }
}

@Composable
internal fun AppContentMountGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // mount the interactive tree immediately; storage and recovery work run
    // from lifecycle coroutines after the first usable frame
    Box(modifier = modifier) {
        content()
    }
}

@Composable
private fun NeriAppContent(
    initialThemeSnapshot: ThemePreferenceSnapshot = ThemePreferenceSnapshot(),
    launcherShortcutRequestFlow: StateFlow<LauncherShortcutRequest?> =
        EmptyLauncherShortcutRequestFlow,
    onLauncherShortcutRequestConsumed: (LauncherShortcutRequest) -> Unit = {},
    onIsDarkChanged: (Boolean) -> Unit = {},
    onNowPlayingVisibilityChanged: (Boolean) -> Unit = {},
    onLanguageChanged: (LanguageManager.Language) -> Unit = {}
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val latestOnNowPlayingVisibilityChanged by rememberUpdatedState(
        onNowPlayingVisibilityChanged
    )
    val latestOnLauncherShortcutRequestConsumed by rememberUpdatedState(
        onLauncherShortcutRequestConsumed
    )
    val launcherShortcutRequest by launcherShortcutRequestFlow.collectAsStateWithLifecycle()
    val offlineMode by rememberOfflineModeState()
    val rootView = LocalView.current
    val repo = remember { AppContainer.settingsRepo }
    val systemDark = rememberActualSystemDarkTheme()
    val application = remember(context) { context.applicationContext as Application }
    SideEffect {
        // 播放点击可能早于启动预加载完成, 先绑定上下文避免懒初始化缺入口
        PlayerManager.bindApplication(application)
    }
    val startupPlaybackPreferences = remember(application) {
        readPlaybackPreferenceSnapshotCached(application) ?: PlaybackPreferenceSnapshot()
    }
    val coverArtImageLoader = remember(context) { Coil.imageLoader(context) }

    val storedFollowSystemDark by repo.followSystemDarkFlow.collectAsStateWithLifecycle(
        initialValue = initialThemeSnapshot.followSystemDark
    )
    val dynamicColorEnabled by repo.dynamicColorFlow.collectAsStateWithLifecycle(
        initialValue = initialThemeSnapshot.dynamicColor
    )
    val storedForceDark by repo.forceDarkFlow.collectAsStateWithLifecycle(
        initialValue = initialThemeSnapshot.forceDark
    )
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showNowPlayingLyrics by rememberSaveable { mutableStateOf(false) }
    var currentPlaybackSourceRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreLyricsAfterAlbumBack by rememberSaveable { mutableStateOf(false) }
    var lyricsAlbumRouteObserved by rememberSaveable { mutableStateOf(false) }
    val devModeEnabled by repo.devModeEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val alwaysRecordLogsEnabled by repo.alwaysRecordLogsEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val themeSeedColor by repo.themeSeedColorFlow.collectAsStateWithLifecycle(initialValue = ThemeDefaults.DEFAULT_SEED_COLOR_HEX)
    val themeColorPalette by repo.themeColorPaletteFlow.collectAsStateWithLifecycle(initialValue = ThemeDefaults.PRESET_COLORS)
    val themePaletteStyleValue by repo.themePaletteStyleFlow.collectAsStateWithLifecycle(
        initialValue = ThemeDefaults.DEFAULT_PALETTE_STYLE
    )
    val themeColorSpecValue by repo.themeColorSpecFlow.collectAsStateWithLifecycle(
        initialValue = ThemeDefaults.DEFAULT_COLOR_SPEC
    )
    val themePaletteStyle = remember(themePaletteStyleValue) {
        PaletteStyle.valueOf(ThemeDefaults.normalizePaletteStyle(themePaletteStyleValue))
    }
    val themeColorSpec = remember(themeColorSpecValue) {
        ColorSpec.SpecVersion.valueOf(ThemeDefaults.normalizeColorSpec(themeColorSpecValue))
    }
    val lyricBlurEnabled by repo.lyricBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val lyricBlurAmount by repo.lyricBlurAmountFlow.collectAsStateWithLifecycle(initialValue = 1.5f)
    val cloudMusicLyricDefaultOffsetMs by repo.cloudMusicLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = startupPlaybackPreferences.cloudMusicLyricDefaultOffsetMs)
    val qqMusicLyricDefaultOffsetMs by repo.qqMusicLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = startupPlaybackPreferences.qqMusicLyricDefaultOffsetMs)
    val kugouLyricDefaultOffsetMs by repo.kugouLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = startupPlaybackPreferences.kugouLyricDefaultOffsetMs)
    val lrclibLyricDefaultOffsetMs by repo.lrclibLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = startupPlaybackPreferences.lrclibLyricDefaultOffsetMs)
    val amllTtmlLyricDefaultOffsetMs by repo.amllTtmlLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = startupPlaybackPreferences.amllTtmlLyricDefaultOffsetMs)
    val floatingLyricsPreferences by repo.floatingLyricsPreferencesFlow.collectAsStateWithLifecycle(
        initialValue = FloatingLyricsPreferences()
    )
    val advancedLyricsEnabled by repo.advancedLyricsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val coherentFeedbackEnabled by repo.coherentFeedbackEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val advancedBlurEnabled by repo.advancedBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val enhancedAdvancedBlurEnabled by repo.enhancedAdvancedBlurEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val enhancedAdvancedBlurRadiusDp by repo.enhancedAdvancedBlurRadiusDpFlow
        .collectAsStateWithLifecycle(
            initialValue = DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP
        )
    val initialAdvancedBlurQuality = remember {
        AdvancedBlurQualityPreference.defaultForDevice(isCurrentBuildDimensity())
    }
    val advancedBlurQuality by repo.advancedBlurQualityFlow.collectAsStateWithLifecycle(
        initialValue = initialAdvancedBlurQuality
    )
    val advancedBlurAvailable = isAdvancedGlassBackendSupported(Build.VERSION.SDK_INT)
    val effectiveAdvancedBlurEnabled = advancedBlurAvailable && advancedBlurEnabled
    val nowPlayingAudioReactiveEnabled by repo.nowPlayingAudioReactiveEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingDynamicBackgroundEnabled by repo.nowPlayingDynamicBackgroundEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingCoverBlurBackgroundEnabled by repo.nowPlayingCoverBlurBackgroundEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val nowPlayingCoverBlurAmount by repo.nowPlayingCoverBlurAmountFlow.collectAsStateWithLifecycle(initialValue = 1.5f)
    val nowPlayingCoverBlurDarken by repo.nowPlayingCoverBlurDarkenFlow.collectAsStateWithLifecycle(initialValue = 0.2f)
    val lyricFontScales by repo.lyricFontScalesFlow.collectAsStateWithLifecycle(
        initialValue = LyricFontScales(
            coverLyric = 1.0f,
            coverTranslation = 1.0f,
            lyricsPageLyric = 1.0f,
            lyricsPageTranslation = 1.0f
        )
    )
    val uiDensityScale by repo.uiDensityScaleFlow.collectAsStateWithLifecycle(initialValue = 1.0f)
    val bypassProxy by repo.bypassProxyFlow.collectAsStateWithLifecycle(initialValue = true)
    val backgroundImageUri by repo.backgroundImageUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val downloadDirectoryUri by repo.downloadDirectoryUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val downloadFileNameTemplate by repo.downloadFileNameTemplateFlow.collectAsStateWithLifecycle(initialValue = null)
    val backgroundImageBlur by repo.backgroundImageBlurFlow.collectAsStateWithLifecycle(initialValue = 0f)
    val backgroundImageAlpha by repo.backgroundImageAlphaFlow.collectAsStateWithLifecycle(initialValue = 0.3f)
    val hapticFeedbackEnabled by repo.hapticFeedbackEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val showCoverSourceBadge by repo.showCoverSourceBadgeFlow.collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingKeepScreenOn by repo.nowPlayingKeepScreenOnFlow.collectAsStateWithLifecycle(initialValue = true)
    val showLyricTranslation by repo.showLyricTranslationFlow.collectAsStateWithLifecycle(initialValue = true)
    val defaultStartDestination: String? by repo.defaultStartDestinationFlow
        .collectAsStateWithLifecycle(initialValue = null)
    val alwaysUseNewTabStyle by repo.alwaysUseNewTabStyleFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val showHomeContinueCard by repo.homeCardContinueFlow.collectAsStateWithLifecycle(initialValue = true)
    val showHomeTrendingCard by repo.homeCardTrendingFlow.collectAsStateWithLifecycle(initialValue = true)
    val showHomeRadarCard by repo.homeCardRadarFlow.collectAsStateWithLifecycle(initialValue = true)
    val showHomeRecommendedCard by repo.homeCardRecommendedFlow.collectAsStateWithLifecycle(initialValue = true)
    val playbackFadeIn by repo.playbackFadeInFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.playbackFadeIn
    )
    val playbackCrossfadeNext by repo.playbackCrossfadeNextFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.playbackCrossfadeNext
    )
    val sleepTimerFinishCurrentOnExpiry by repo.sleepTimerFinishCurrentOnExpiryFlow
        .collectAsStateWithLifecycle(
            initialValue = startupPlaybackPreferences.sleepTimerFinishCurrentOnExpiry
        )
    val playbackFadeInDurationMs by repo.playbackFadeInDurationMsFlow.collectAsStateWithLifecycle(initialValue = 500L)
    val playbackFadeOutDurationMs by repo.playbackFadeOutDurationMsFlow.collectAsStateWithLifecycle(initialValue = 500L)
    val playbackCrossfadeInDurationMs by repo.playbackCrossfadeInDurationMsFlow.collectAsStateWithLifecycle(initialValue = 500L)
    val playbackCrossfadeOutDurationMs by repo.playbackCrossfadeOutDurationMsFlow.collectAsStateWithLifecycle(initialValue = 500L)
    val playbackVolumeNormalizationEnabled by repo.playbackVolumeNormalizationEnabledFlow
        .collectAsStateWithLifecycle(
            initialValue = startupPlaybackPreferences.playbackVolumeNormalizationEnabled
        )
    val playbackHighResolutionOutputEnabled by repo.playbackHighResolutionOutputEnabledFlow
        .collectAsStateWithLifecycle(
            initialValue = startupPlaybackPreferences.playbackHighResolutionOutputEnabled
        )
    val playbackVolumeBalance by repo.playbackVolumeBalanceFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.playbackVolumeBalance
    )
    val keepLastPlaybackProgress by repo.keepLastPlaybackProgressFlow.collectAsStateWithLifecycle(initialValue = true)
    val rememberLongFormPlaybackProgress by repo.rememberLongFormPlaybackProgressFlow
        .collectAsStateWithLifecycle(
            initialValue = startupPlaybackPreferences.rememberLongFormPlaybackProgress
        )
    val keepPlaybackModeState by repo.keepPlaybackModeStateFlow.collectAsStateWithLifecycle(initialValue = true)
    val neteaseAutoSourceSwitch by repo.neteaseAutoSourceSwitchFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.neteaseAutoSourceSwitch
    )
    val neteaseLocalSourceFallback by repo.neteaseLocalSourceFallbackFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.neteaseLocalSourceFallback
    )
    val stopOnBluetoothDisconnect by repo.stopOnBluetoothDisconnectFlow.collectAsStateWithLifecycle(initialValue = true)
    val usbExclusivePlayback by repo.usbExclusivePlaybackFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.usbExclusivePlayback
    )
    val usbExclusiveBackgroundPermissionPromptSuppressed by repo
        .usbExclusiveBackgroundPermissionPromptSuppressedFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val allowMixedPlayback by repo.allowMixedPlaybackFlow.collectAsStateWithLifecycle(initialValue = false)
    val preemptAudioFocus by repo.preemptAudioFocusFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.preemptAudioFocus
    )
    val maxCacheSizeBytes by repo.maxCacheSizeBytesFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.maxCacheSizeBytes
    )
    val homeUsageSnapshot by produceState(
        initialValue = HomeUsageSnapshot(),
        key1 = context
    ) {
        val usageFlow = withContext(Dispatchers.IO) {
            AppContainer.playlistUsageRepo.frequentPlaylistsFlow
        }
        usageFlow.collect { entries ->
            value = HomeUsageSnapshot(entries = entries, isLoaded = true)
        }
    }
    val showHomeTab =
        shouldShowHomeContinueSection(
            showContinueCard = showHomeContinueCard,
            usageLoaded = homeUsageSnapshot.isLoaded,
            hasUsage = homeUsageSnapshot.entries.isNotEmpty()
        ) ||
            showHomeTrendingCard ||
            showHomeRadarCard ||
            showHomeRecommendedCard
    var pendingFollowSystemDark by remember { mutableStateOf<Boolean?>(null) }
    var pendingForceDark by remember { mutableStateOf<Boolean?>(null) }
    var themeRevealSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var themeRevealOriginWindow by remember { mutableStateOf<Offset?>(null) }
    var themeRevealStartRadiusPx by remember { mutableFloatStateOf(0f) }
    var themeRevealFallbackColorArgb by remember { mutableStateOf<Int?>(null) }
    var themeRevealCaptureInFlight by remember { mutableStateOf(false) }
    var themeRevealCaptureJob by remember { mutableStateOf<Job?>(null) }
    var themeRevealCaptureToken by remember { mutableIntStateOf(0) }
    var themeModeWriteInFlight by remember { mutableStateOf(false) }
    var pendingBackgroundImageAlpha by remember { mutableStateOf<Float?>(null) }
    var coverArtRefreshToken by remember { mutableIntStateOf(0) }
    var showUsbExclusiveBackgroundPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var usbExclusiveBackgroundPermissionPromptHandled by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val startupAudioFocusRefresher = remember(context) {
        PlayerStartupAudioFocusRefresher(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> false
                else -> lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        lifecycleResumed,
        usbExclusivePlayback,
        usbExclusiveBackgroundPermissionPromptSuppressed
    ) {
        if (!usbExclusivePlayback) {
            usbExclusiveBackgroundPermissionPromptHandled = false
            showUsbExclusiveBackgroundPermissionDialog = false
            return@LaunchedEffect
        }
        val shouldInspectBackgroundBehavior = lifecycleResumed &&
            !usbExclusiveBackgroundPermissionPromptSuppressed &&
            !usbExclusiveBackgroundPermissionPromptHandled
        val backgroundBehaviorAllowed = if (shouldInspectBackgroundBehavior) {
            context.readBackgroundBehaviorAllowance().fullyAllowed
        } else {
            true
        }
        if (
            shouldPromptForUsbExclusiveBackgroundPermission(
                usbExclusiveEnabled = usbExclusivePlayback,
                appResumed = lifecycleResumed,
                promptSuppressed = usbExclusiveBackgroundPermissionPromptSuppressed,
                backgroundBehaviorAllowed = backgroundBehaviorAllowed,
                promptHandledInCurrentSession = usbExclusiveBackgroundPermissionPromptHandled
            )
        ) {
            showUsbExclusiveBackgroundPermissionDialog = true
        }
        if (shouldInspectBackgroundBehavior) {
            usbExclusiveBackgroundPermissionPromptHandled = true
        }
    }

    val followSystemDark = pendingFollowSystemDark ?: storedFollowSystemDark
    val forceDark = pendingForceDark ?: storedForceDark
    val themeMode = remember(followSystemDark, forceDark) {
        ThemeMode.fromPreferenceFlags(
            forceDark = forceDark,
            followSystemDark = followSystemDark
        )
    }
    val effectiveBackgroundImageAlpha = pendingBackgroundImageAlpha ?: backgroundImageAlpha

    val clearThemeRevealVisualState = {
        themeRevealSnapshot = null
        themeRevealOriginWindow = null
        themeRevealStartRadiusPx = 0f
        themeRevealFallbackColorArgb = null
    }
    val clearPendingThemeModeChange = {
        pendingFollowSystemDark = null
        pendingForceDark = null
    }
    val clearThemeRevealState = {
        themeRevealCaptureToken += 1
        themeRevealCaptureJob?.cancel()
        themeRevealCaptureJob = null
        themeRevealCaptureInFlight = false
        themeModeWriteInFlight = false
        clearPendingThemeModeChange()
        clearThemeRevealVisualState()
    }
    val finishThemeReveal = { captureToken: Int ->
        if (themeRevealCaptureToken == captureToken) {
            clearThemeRevealVisualState()
        }
    }

    // 缓存当前封面的取色结果, 避免开关动态取色时先闪到默认种子色
    var coverSeed by remember { mutableStateOf<PlaybackCoverSeed?>(null) }
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val displayCoverUrl = rememberSongDisplayCoverUrl(currentSong)
    val currentSongKey = remember(currentSong) { currentSong?.stableKey() }
    val coverAssetRootGeneration by LocalAssetInvalidationBus.rootGenerationFlow
        .collectAsStateWithLifecycle()
    val coverAssetSongRevisionFlow = remember(currentSongKey) {
        LocalAssetInvalidationBus.revisionFlow(currentSongKey.orEmpty())
    }
    val coverAssetSongRevision by coverAssetSongRevisionFlow.collectAsStateWithLifecycle(
        initialValue = LocalAssetInvalidationBus.currentSongRevision(currentSongKey.orEmpty())
    )
    val currentSongVisualKey = remember(currentSong) { currentSong?.playbackVisualKey() }
    val currentSongVisualKeyAliases = remember(currentSong) {
        currentSong?.playbackVisualKeyAliases().orEmpty()
    }
    val playbackVisualCoverState = rememberPlaybackVisualCoverState(
        playbackVisualCoverRequest(displayCoverUrl, currentSongVisualKey)
    )
    val playbackVisualCoverUrl = playbackVisualCoverState.url
    val coverAssetRefreshKey = remember(
        coverArtRefreshToken,
        coverAssetRootGeneration,
        coverAssetSongRevision
    ) {
        var result = coverArtRefreshToken
        result = 31 * result + coverAssetRootGeneration.hashCode()
        result = 31 * result + coverAssetSongRevision.hashCode()
        result
    }
    val scope = rememberCoroutineScope()
    var pendingTrafficRiskDownloadRequest by remember {
        mutableStateOf<GlobalDownloadManager.TrafficRiskDownloadRequest?>(null)
    }
    LaunchedEffect(Unit) {
        GlobalDownloadManager.trafficRiskDownloadRequests.collect { request ->
            pendingTrafficRiskDownloadRequest = request
        }
    }

    val serviceSyncCoordinator = remember(context) {
        PlayerStartupServiceSyncCoordinator(
            awaitUiFrame = { withFrameNanos { } },
            isServiceReadyForPassiveLocalPlaybackSync = AudioPlayerService::isReadyForPassiveLocalPlaybackSync,
            hasItems = PlayerManager::hasItems,
            hasLocalCurrentSong = {
                PlayerManager.currentSongFlow.value?.let { song ->
                    LocalSongSupport.isLocalSong(song, context)
                } == true
            },
            isUsbExclusivePlaybackActiveForForegroundService =
                PlayerManager::isUsbExclusivePlaybackActiveForForegroundService,
            shouldRunPlaybackServiceInForeground = PlayerManager::shouldRunPlaybackServiceInForeground,
            isServiceInstanceActiveForDiagnostics = AudioPlayerService::isInstanceActiveForDiagnostics,
            isServiceForegroundActiveForDiagnostics = AudioPlayerService::isForegroundActiveForDiagnostics,
            startService = { source, forceForeground ->
                AudioPlayerService.startSyncService(
                    context,
                    source,
                    forceForeground = forceForeground
                )
            },
            playbackCommandFlow = PlayerManager.playbackCommandFlow
        )
    }
    val scheduleAudioServiceStart: (String, Boolean) -> Unit = { source, forceForeground ->
        scope.launch {
            serviceSyncCoordinator.startServiceAfterUiFrame(
                source = source,
                forceForeground = forceForeground
            )
        }
    }
    var playbackBootstrapReady by remember { mutableStateOf(false) }

    fun updateStartupAudioFocus(reason: String) {
        startupAudioFocusRefresher.refreshForeground(
            lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            reason = reason,
            preemptAudioFocus = preemptAudioFocus,
            allowMixedPlayback = allowMixedPlayback,
            usbExclusivePlayback = usbExclusivePlayback
        )
    }

    LaunchedEffect(application) {
        playbackBootstrapReady = false
        PlayerStartupBootstrapper(
            app = application,
            context = context,
            awaitUiFrameBeforePlayerInit = {
                withFrameNanos { }
            }
        ).bootstrap().serviceStart?.let { serviceStart ->
            scheduleAudioServiceStart(serviceStart.source, serviceStart.forceForeground)
        }
        playbackBootstrapReady = true

        launch {
            serviceSyncCoordinator.collectLocalPlaybackCommands()
        }

        val historyRecorder = PlayerStartupHistoryRecorder(
            currentSongFlow = PlayerManager.currentSongFlow,
            recordSong = AppContainer.playHistoryRepo::record,
            startupSongToSkip = PlayerManager.currentSongFlow.value
        )
        launch {
            historyRecorder.run()
        }

    }

    LaunchedEffect(preemptAudioFocus, allowMixedPlayback, usbExclusivePlayback) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            updateStartupAudioFocus("settings_changed")
        } else {
            startupAudioFocusRefresher.releaseForInactiveSettingsChange(
                preemptAudioFocus = preemptAudioFocus,
                usbExclusivePlayback = usbExclusivePlayback,
                allowMixedPlayback = allowMixedPlayback
            )
        }
    }

    LaunchedEffect(storedFollowSystemDark, pendingFollowSystemDark) {
        if (pendingFollowSystemDark != null && pendingFollowSystemDark == storedFollowSystemDark) {
            pendingFollowSystemDark = null
        }
    }
    LaunchedEffect(backgroundImageAlpha, pendingBackgroundImageAlpha) {
        if (
            pendingBackgroundImageAlpha != null &&
            abs((pendingBackgroundImageAlpha ?: backgroundImageAlpha) - backgroundImageAlpha) < 0.001f
        ) {
            pendingBackgroundImageAlpha = null
        }
    }

    LaunchedEffect(storedForceDark, pendingForceDark) {
        if (pendingForceDark != null && pendingForceDark == storedForceDark) {
            pendingForceDark = null
        }
    }

    val latestPlaybackVisualCoverUrl by rememberUpdatedState(playbackVisualCoverUrl)
    val latestPlaybackSongKey by rememberUpdatedState(currentSongVisualKey)
    LaunchedEffect(
        playbackVisualCoverUrl,
        currentSongVisualKey,
        coverArtRefreshToken,
        showNowPlaying,
        dynamicColorEnabled,
        offlineMode
    ) {
        val requestCoverUrl = playbackVisualCoverUrl
        val requestSongKey = currentSongVisualKey
        if (!dynamicColorEnabled) {
            coverSeed = null
            return@LaunchedEffect
        }
        if (requestCoverUrl.isNullOrBlank()) {
            delay(PLAYBACK_COVER_SEED_GRACE_MS)
            if (
                latestPlaybackVisualCoverUrl.isNullOrBlank() &&
                    latestPlaybackSongKey == requestSongKey
            ) {
                coverSeed = null
            }
            return@LaunchedEffect
        }
        val cachedSample = CoverArtColorCache.peek(requestCoverUrl)
        currentCoroutineContext().ensureActive()
        if (
            cachedSample != null &&
                latestPlaybackVisualCoverUrl == requestCoverUrl &&
                latestPlaybackSongKey == requestSongKey
        ) {
            coverSeed = PlaybackCoverSeed(
                coverUrl = requestCoverUrl,
                seedHex = cachedSample.seedHex,
                songKey = requestSongKey
            )
        }

        if (showNowPlaying && isRemoteImageSource(requestCoverUrl)) {
            coverArtImageLoader.enqueue(
                offlineCachedImageRequest(
                    context = context,
                    data = requestCoverUrl,
                    sizePx = 256,
                    allowHardware = false,
                    offlineMode = offlineMode
                )
            )
        }

        val warmupDelayMillis = resolveCoverSeedWarmupDelayMillis(
            showNowPlaying = showNowPlaying,
            dynamicColorEnabled = dynamicColorEnabled,
            hasCachedSample = cachedSample != null
        )
        if (warmupDelayMillis > 0L) {
            delay(warmupDelayMillis)
        }

        CoverArtColorCache.preload(context, requestCoverUrl, offlineMode)?.let { sample ->
            currentCoroutineContext().ensureActive()
            if (
                latestPlaybackVisualCoverUrl == requestCoverUrl &&
                    latestPlaybackSongKey == requestSongKey
            ) {
                coverSeed = PlaybackCoverSeed(
                    coverUrl = requestCoverUrl,
                    seedHex = sample.seedHex,
                    songKey = requestSongKey
                )
            }
        }
    }

    // 同步触感反馈设置
    LaunchedEffect(hapticFeedbackEnabled) {
        syncHapticFeedbackSetting(hapticFeedbackEnabled)
    }

    var bottomBarHeightPx by remember { mutableIntStateOf(0) }

    val isDark = StartupThemeResolver.resolveModeUseDark(
        mode = themeMode,
        systemDark = systemDark
    )
    val initialMainStartDestination = resolveMainStartDestination(
        preferredRoute = defaultStartDestination,
        showHomeTab = showHomeTab,
        devModeEnabled = devModeEnabled
    )
    val currentDefaultStartDestination =
        defaultStartDestination ?: initialMainStartDestination
    val backgroundGlassBackdrop = rememberAdvancedGlassBackdrop()
    val contentGlassBackdrop = rememberAdvancedGlassBackdrop()
    val advancedGlassController = remember(
        advancedBlurEnabled,
        enhancedAdvancedBlurEnabled,
        enhancedAdvancedBlurRadiusDp,
        advancedBlurQuality
    ) {
        AdvancedGlassController(
            sdkInt = Build.VERSION.SDK_INT,
            advancedBlurEnabled = advancedBlurEnabled,
            enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
            backendReady = isAdvancedGlassBackendSupported(Build.VERSION.SDK_INT),
            enhancedAdvancedBlurRadiusDp = enhancedAdvancedBlurRadiusDp,
            advancedBlurQuality = advancedBlurQuality
        )
    }
    val preferredQuality by repo.audioQualityFlow.collectAsStateWithLifecycle(initialValue = "exhigh")
    val youtubePreferredQuality by repo.youtubeAudioQualityFlow.collectAsStateWithLifecycle(initialValue = "high")
    val biliPreferredQuality by repo.biliAudioQualityFlow.collectAsStateWithLifecycle(initialValue = "high")
    val mobileDataFollowDefaultAudioQuality by repo.mobileDataFollowDefaultAudioQualityFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.mobileDataFollowDefaultAudioQuality
    )
    val mobileDataNeteaseAudioQuality by repo.mobileDataNeteaseAudioQualityFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.mobileDataNeteaseAudioQuality
    )
    val mobileDataYouTubeAudioQuality by repo.mobileDataYouTubeAudioQualityFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.mobileDataYouTubeAudioQuality
    )
    val mobileDataBiliAudioQuality by repo.mobileDataBiliAudioQualityFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.mobileDataBiliAudioQuality
    )
    val currentThemeBackgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    // retained main-tab scenes can keep an earlier callback, so read the current theme state at click time
    val latestThemeMode by rememberUpdatedState(themeMode)
    val latestIsDark by rememberUpdatedState(isDark)
    val latestSystemDark by rememberUpdatedState(systemDark)
    val latestThemeBackgroundArgb by rememberUpdatedState(currentThemeBackgroundArgb)
    val themeRevealActive =
        themeRevealOriginWindow != null &&
            themeRevealFallbackColorArgb != null
    val latestThemeRevealActive by rememberUpdatedState(themeRevealActive)

    LaunchedEffect(isDark, themeRevealActive, themeRevealCaptureInFlight) {
        if (!themeRevealActive && !themeRevealCaptureInFlight) {
            onIsDarkChanged(isDark)
        }
    }

    val activeThemeRevealToken = themeRevealCaptureToken
    LaunchedEffect(themeRevealActive, activeThemeRevealToken) {
        if (!themeRevealActive) {
            return@LaunchedEffect
        }
        delay(THEME_REVEAL_WATCHDOG_DELAY_MILLIS.milliseconds)
        finishThemeReveal(activeThemeRevealToken)
    }

    fun requestThemeModeChange(
        targetMode: ThemeMode,
        originInWindow: Offset,
        startRadiusPx: Float
    ) {
        if (
            shouldBlockThemeModeChange(
                captureInFlight = themeRevealCaptureInFlight,
                writeInFlight = themeModeWriteInFlight,
                revealActive = latestThemeRevealActive,
                hasPendingThemePreference = pendingFollowSystemDark != null ||
                    pendingForceDark != null
            )
        ) {
            return
        }

        if (targetMode == latestThemeMode) {
            return
        }

        val nextFollowSystemDark = targetMode.followSystemDark
        val nextForceDark = targetMode.forceDark
        val nextDark = targetMode.resolveUseDark(latestSystemDark)
        if (nextDark == latestIsDark) {
            themeModeWriteInFlight = true
            scope.launch {
                try {
                    repo.setThemeMode(
                        followSystemDark = nextFollowSystemDark,
                        forceDark = nextForceDark
                    )
                } finally {
                    themeModeWriteInFlight = false
                }
            }
            return
        }

        val activity = context as? Activity
        val captureView = activity?.window?.decorView?.rootView ?: rootView.rootView
        val captureToken = themeRevealCaptureToken + 1
        themeRevealCaptureToken = captureToken
        themeRevealCaptureInFlight = true

        val captureJob = scope.launch {
            var themeWriteStarted = false
            var themeWriteCompleted = false
            try {
                awaitStableDraw(captureView)
                val snapshot = runCatching {
                    captureThemeRevealSnapshot(
                        activity = activity,
                        fallbackView = captureView
                    )
                }.getOrNull()
                val lifecycleActive = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                val activityValid = activity == null || (!activity.isFinishing && !activity.isDestroyed)
                if (themeRevealCaptureToken != captureToken || !lifecycleActive || !activityValid) {
                    return@launch
                }

                clearThemeRevealVisualState()
                themeRevealSnapshot = snapshot
                themeRevealFallbackColorArgb = latestThemeBackgroundArgb
                themeRevealOriginWindow = originInWindow
                themeRevealStartRadiusPx = startRadiusPx.coerceAtLeast(1f)
                pendingFollowSystemDark = nextFollowSystemDark
                pendingForceDark = nextForceDark
                themeModeWriteInFlight = true
                themeWriteStarted = true
                repo.setThemeMode(
                    followSystemDark = nextFollowSystemDark,
                    forceDark = nextForceDark
                )
                themeWriteCompleted = true
            } finally {
                if (themeRevealCaptureToken == captureToken) {
                    if (themeWriteStarted && !themeWriteCompleted) {
                        clearPendingThemeModeChange()
                        clearThemeRevealVisualState()
                    }
                    themeRevealCaptureJob = null
                    themeRevealCaptureInFlight = false
                    themeModeWriteInFlight = false
                }
            }
        }
        themeRevealCaptureJob = captureJob
    }

    fun requestThemeToggle(originInWindow: Offset, startRadiusPx: Float) {
        val targetMode = resolveThemeToggleTarget(latestIsDark)
        requestThemeModeChange(
            targetMode = targetMode,
            originInWindow = originInWindow,
            startRadiusPx = startRadiusPx
        )
    }

    DisposableEffect(lifecycleOwner, preemptAudioFocus, allowMixedPlayback, usbExclusivePlayback) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    PlayerManager.updateUsbExclusiveForegroundState(
                        foreground = true,
                        reason = "lifecycle_resume"
                    )
                    coverArtRefreshToken += 1
                    if (!PlayerManager.isUsbExclusiveNativePlaybackStable()) {
                        updateStartupAudioFocus("lifecycle_resume")
                    }
                    PlayerManager.recoverUsbExclusivePlaybackOnForeground("lifecycle_resume")
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    PlayerManager.updateUsbExclusiveForegroundState(
                        foreground = false,
                        reason = "lifecycle_${event.name.lowercase()}"
                    )
                    clearThemeRevealState()
                    val keepUsbExclusiveFocus = PlayerManager.isPlayerInitialized() &&
                        PlayerManager.usbExclusivePlaybackEnabled &&
                        PlayerManager.shouldUseUsbExclusiveFocusGuard()
                    if (keepUsbExclusiveFocus) {
                        updateStartupAudioFocus("lifecycle_${event.name.lowercase()}_keep_usb")
                    } else {
                        startupAudioFocusRefresher.release("lifecycle_${event.name.lowercase()}")
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    clearThemeRevealState()
                    startupAudioFocusRefresher.release("lifecycle_${event.name.lowercase()}")
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(rootView, backgroundImageUri) {
        clearThemeRevealState()
    }

    fun playSongsAndOpenNowPlaying(
        songs: List<SongItem>,
        index: Int,
        sourceRoute: String? = null
    ) {
        restoreLyricsAfterAlbumBack = false
        lyricsAlbumRouteObserved = false
        currentPlaybackSourceRoute = sourceRoute
        PlayerManager.prefetchYouTubePlayableUrlWindow(
            playlist = songs,
            startIndex = index,
            source = "ui_click_before_play"
        )
        // 播放队列可能包含歌词等大字段, 避免通过 Binder 传整份歌单导致崩溃
        val localPlaylistId = localPlaylistIdFromSourceRoute(sourceRoute)
        if (localPlaylistId == null) {
            PlayerManager.playPlaylist(songs, index)
        } else {
            PlayerManager.playLocalPlaylist(
                playlistId = localPlaylistId,
                songs = songs,
                startIndex = index
            )
        }
        // 先提交当前歌曲, 播放页首帧不能继续绘制上一首封面
        showNowPlaying = true
        scheduleAudioServiceStart(
            "play_songs_and_open_now_playing",
            true
        )
    }

    fun playSongPreservingQueueAndOpenNowPlaying(song: SongItem) {
        restoreLyricsAfterAlbumBack = false
        lyricsAlbumRouteObserved = false
        currentPlaybackSourceRoute = null
        PlayerManager.prefetchYouTubePlayableUrlWindow(
            playlist = listOf(song),
            startIndex = 0,
            source = "ui_click_preserve_queue_before_play"
        )
        PlayerManager.replaceCurrentInQueueAndPlay(song)
        // 先提交当前歌曲, 播放页首帧不能继续绘制上一首封面
        showNowPlaying = true
        scheduleAudioServiceStart(
            "play_search_result_preserve_queue",
            true
        )
    }

    fun addSongToQueueNextFromSearch(song: SongItem) {
        PlayerManager.addToQueueNext(song)
        scheduleAudioServiceStart("search_result_play_next", false)
    }

    fun addSongToQueueEndFromSearch(song: SongItem) {
        PlayerManager.addToQueueEnd(song)
        scheduleAudioServiceStart("search_result_add_to_queue_end", false)
    }

    fun ensureAudioServiceStarted(source: String = "ensure_audio_service_started") {
        NPLogger.d(
            "NERI-App",
            "ensureAudioServiceStarted hasItems=${PlayerManager.hasItems()} transportActive=${PlayerManager.isTransportActive()} isPlaying=${PlayerManager.isPlayingFlow.value}"
        )
        scheduleAudioServiceStart(source, false)
    }

    fun playBiliAudioAndOpenNowPlayingWithSource(
        videos: List<BiliVideoItem>,
        index: Int,
        sourceRoute: String?
    ) {
        restoreLyricsAfterAlbumBack = false
        lyricsAlbumRouteObserved = false
        currentPlaybackSourceRoute = sourceRoute
        NPLogger.d("NERI-App", "Playing audio from Bili video: ${videos[index].title}")
        PlayerManager.playBiliVideoAsAudio(videos, index)
        showNowPlaying = true
        ensureAudioServiceStarted(source = "play_bili_audio_and_open_now_playing")
    }

    fun playBiliPartsAndOpenNowPlayingWithSource(
        videoInfo: BiliClient.VideoBasicInfo,
        index: Int,
        coverUrl: String,
        sourceRoute: String?
    ) {
        restoreLyricsAfterAlbumBack = false
        lyricsAlbumRouteObserved = false
        currentPlaybackSourceRoute = sourceRoute
        NPLogger.d("NERI-App", "Playing parts from Bili video: ${videoInfo.title}")
        PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
        showNowPlaying = true
        ensureAudioServiceStarted(source = "play_bili_parts_and_open_now_playing")
    }

    fun playBiliPartsAndOpenNowPlaying(
        videoInfo: BiliClient.VideoBasicInfo,
        index: Int,
        coverUrl: String
    ) {
        playBiliPartsAndOpenNowPlayingWithSource(
            videoInfo = videoInfo,
            index = index,
            coverUrl = coverUrl,
            null
        )
    }

    fun neteasePlaylistSourceRoute(playlist: PlaylistSummary): String {
        return "playlist_detail/${Uri.encode(navigationGson.toJson(playlist))}"
    }

    fun neteaseAlbumSourceRoute(album: AlbumSummary): String {
        return "netease_album_detail/${Uri.encode(navigationGson.toJson(album))}"
    }

    fun biliPlaylistSourceRoute(playlist: BiliPlaylist): String {
        return "bili_playlist_detail/${Uri.encode(navigationGson.toJson(playlist))}"
    }

    fun biliUploaderSourceRoute(uploader: BiliUploaderSummary): String {
        return "bili_uploader_detail/${Uri.encode(navigationGson.toJson(uploader))}"
    }

    fun localPlaylistSourceRoute(id: Long): String {
        return "local_playlist_detail/$id"
    }

    val activeCoverSeedHex = resolveActiveCoverSeedHex(
        visualCoverUrl = playbackVisualCoverUrl,
        sampledCoverUrl = coverSeed?.coverUrl,
        sampledSeedHex = coverSeed?.seedHex,
        currentSongKey = currentSongVisualKey,
        sampledSongKey = coverSeed?.songKey
    )
        val effectiveSeedHex = if (dynamicColorEnabled) {
            activeCoverSeedHex ?: themeSeedColor
        } else {
            themeSeedColor
        }
        val useSystemDynamic =
            dynamicColorEnabled && activeCoverSeedHex == null && playbackVisualCoverUrl == null

    NeriTheme(
            followSystemDark = followSystemDark,
            forceDark = forceDark,
            dynamicColor = useSystemDynamic,
            seedColorHex = effectiveSeedHex,
            paletteStyle = themePaletteStyle,
            colorSpec = themeColorSpec,
            systemDark = systemDark
    ) {
            // changing NavHost's start destination rebuilds its graph and clears the live stack
            val navHostStartDestination = remember { initialMainStartDestination }
            var awaitingPersistedStartDestination by rememberSaveable {
                mutableStateOf(defaultStartDestination == null)
            }
            val navController = rememberNavController()
            val backEntry by navController.currentBackStackEntryAsState()
            // Keep every NavHost entry that is still participating in the transition active
            val visibleNavigationEntries by navController.visibleEntries
                .collectAsStateWithLifecycle()
            val visibleNavigationOwners: Set<Any> = remember(
                visibleNavigationEntries,
                backEntry,
                lifecycleOwner
            ) {
                buildSet {
                    if (visibleNavigationEntries.isNotEmpty()) {
                        addAll(visibleNavigationEntries)
                    } else {
                        backEntry?.let(::add)
                    }
                    add(lifecycleOwner)
                }
            }
            val currentRoute = backEntry?.destination?.route
            val visibleNavigationRoutes = remember(visibleNavigationEntries, currentRoute) {
                buildSet<String?> {
                    visibleNavigationEntries.forEach { entry ->
                        add(entry.destination.route)
                    }
                    add(currentRoute)
                }
            }
            val mainTabMotionTarget = resolveMainTabNavigationMotionTarget(
                currentRoute = currentRoute,
                visibleRoutes = visibleNavigationRoutes,
                coherentFeedbackEnabled = coherentFeedbackEnabled
            )
            var mainTabDetailContentHeightPx by remember {
                mutableIntStateOf(0)
            }
            val mainTabBackgroundProgress by animateFloatAsState(
                targetValue = mainTabMotionTarget.targetProgress,
                animationSpec = tween(
                    durationMillis = resolveMainTabBackgroundMotionDurationMillis(
                        targetProgress = mainTabMotionTarget.targetProgress,
                        coherentFeedbackEnabled = coherentFeedbackEnabled,
                        debugSceneVisible = mainTabMotionTarget.debugSceneVisible
                    ),
                    easing = mainTabDetailContentOffsetEasing()
                ),
                label = "main_tab_detail_content_handoff"
            )
            val mainTabNavigationMotion = resolveMainTabNavigationMotionState(
                backgroundMotion = mainTabMotionTarget.backgroundMotion,
                progress = mainTabBackgroundProgress
            )
            val effectiveStartDestination = remember(
                currentDefaultStartDestination,
                showHomeTab,
                devModeEnabled
            ) {
                resolveMainStartDestination(
                    preferredRoute = currentDefaultStartDestination,
                    showHomeTab = showHomeTab,
                    devModeEnabled = devModeEnabled
                )
            }
            var selectedMainTabRoute by rememberSaveable(navHostStartDestination) {
                mutableStateOf(navHostStartDestination)
            }
            var pendingMainTabRoute by remember(navHostStartDestination) {
                mutableStateOf<String?>(null)
            }
            val mainTabTransitionState = rememberMainTabLayerTransitionState(
                selectedMainTabRoute
            )
            LaunchedEffect(currentRoute, navHostStartDestination) {
                val observedRoute = currentRoute
                if (
                    shouldAcceptObservedMainTabRoute(
                        observedRoute = observedRoute,
                        pendingRoute = pendingMainTabRoute
                    )
                ) {
                    selectedMainTabRoute = checkNotNull(observedRoute)
                    if (pendingMainTabRoute == observedRoute) {
                        pendingMainTabRoute = null
                    }
                }
            }
            var visibleMainTabGlassOwners by remember(navHostStartDestination) {
                mutableStateOf<Set<MainTabGlassOwner>>(
                    setOf(MainTabGlassOwner(navHostStartDestination))
                )
            }
            val activeAdvancedGlassOwners: Set<Any> = remember(
                visibleNavigationOwners,
                visibleMainTabGlassOwners,
                selectedMainTabRoute
            ) {
                visibleNavigationOwners +
                    visibleMainTabGlassOwners +
                    MainTabGlassOwner(selectedMainTabRoute)
            }
            fun navigateToMainTab(route: String) {
                if (selectedMainTabRoute != route) {
                    selectedMainTabRoute = route
                }
                mainTabTransitionState.request(route)
                if (
                    !shouldDispatchMainTabNavigation(
                        currentRoute = currentRoute,
                        pendingRoute = pendingMainTabRoute,
                        targetRoute = route
                    )
                ) {
                    return
                }
                pendingMainTabRoute = route
                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            fun showLauncherShortcutToast(messageRes: Int) {
                AppFeedback.showToast(
                    context = context,
                    message = composeResources.getString(messageRes)
                )
            }
            LaunchedEffect(launcherShortcutRequest, playbackBootstrapReady) {
                val request = launcherShortcutRequest ?: return@LaunchedEffect
                if (!playbackBootstrapReady) return@LaunchedEffect

                launcherShortcutMainTabRoute(request.action)?.let { route ->
                    navigateToMainTab(route)
                    latestOnLauncherShortcutRequestConsumed(request)
                    return@LaunchedEffect
                }

                when (request.action) {
                    LauncherShortcutAction.ContinuePlayback -> {
                        if (PlayerManager.hasItems()) {
                            showNowPlaying = true
                            PlayerManager.play()
                            scheduleAudioServiceStart(
                                "launcher_shortcut_continue_playback",
                                true
                            )
                        } else {
                            navigateToMainTab(Destinations.Library.route)
                            showLauncherShortcutToast(
                                R.string.launcher_shortcut_no_resumable_queue
                            )
                        }
                    }
                    LauncherShortcutAction.ShuffleFavorites -> {
                        val playlistsReady = withTimeoutOrNull(
                            LAUNCHER_SHORTCUT_PLAYLIST_READY_TIMEOUT_MS
                        ) {
                            PlayerManager.localPlaylistsReadyFlow.first { ready -> ready }
                        } == true
                        val favoritesSongs = if (playlistsReady) {
                            FavoritesPlaylist
                                .firstOrNull(PlayerManager.playlistsFlow.value, context)
                                ?.songs
                                .orEmpty()
                        } else {
                            emptyList()
                        }
                        if (favoritesSongs.isEmpty()) {
                            navigateToMainTab(Destinations.Library.route)
                            showLauncherShortcutToast(
                                R.string.launcher_shortcut_favorites_empty
                            )
                        } else {
                            PlayerManager.setShuffle(true)
                            playSongsAndOpenNowPlaying(
                                songs = favoritesSongs,
                                index = Random.nextInt(favoritesSongs.size),
                                sourceRoute = localPlaylistSourceRoute(
                                    FavoritesPlaylist.SYSTEM_ID
                                )
                            )
                        }
                    }
                    LauncherShortcutAction.OpenExplore,
                    LauncherShortcutAction.OpenLibrary -> Unit
                }

                latestOnLauncherShortcutRequestConsumed(request)
            }
            suspend fun preloadNeteaseDetailRouteCover(route: String) {
                val coverUrl = runCatching {
                    when {
                        route.startsWith("playlist_detail/") -> {
                            val json = Uri.decode(route.substringAfter("playlist_detail/"))
                            navigationGson.fromJson(json, PlaylistSummary::class.java).picUrl
                        }
                        route.startsWith("netease_album_detail/") -> {
                            val json = Uri.decode(route.substringAfter("netease_album_detail/"))
                            navigationGson.fromJson(json, AlbumSummary::class.java).picUrl
                        }
                        else -> null
                    }
                }.getOrNull()
                CoverArtColorCache.preload(context, coverUrl, offlineMode)
            }
            fun navigateToNeteaseAlbum(
                album: AlbumSummary,
                afterNavigate: () -> Unit = {}
            ) {
                scope.launch {
                    CoverArtColorCache.preload(context, album.picUrl, offlineMode)
                    navController.navigate(neteaseAlbumSourceRoute(album)) {
                        launchSingleTop = true
                    }
                    afterNavigate()
                }
            }
            fun navigateToPlaybackSourceRoute(route: String) {
                scope.launch {
                    preloadNeteaseDetailRouteCover(route)
                    showNowPlayingLyrics = false
                    showNowPlaying = false
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                }
            }
            fun navigateToNeteaseArtist(artist: NeteaseArtistSummary) {
                val json = Uri.encode(navigationGson.toJson(artist))
                val currentEntry = navController.currentBackStackEntry
                val currentIsArtist =
                    currentEntry?.destination?.route == Destinations.NeteaseArtistDetail.route
                val currentArtist = currentEntry
                    ?.arguments
                    ?.getString("artistJson")
                    ?.let {
                        runCatching {
                            navigationGson.fromJson(it, NeteaseArtistSummary::class.java)
                        }.getOrNull()
                    }
                if (currentArtist?.id == artist.id) {
                    return
                }
                if (currentIsArtist) {
                    navController.popBackStack()
                }
                navController.navigate("netease_artist_detail/$json") {
                    launchSingleTop = true
                }
            }
            fun navigateToBiliUploader(uploader: BiliUploaderSummary) {
                if (uploader.mid <= 0L) return
                val currentEntry = navController.currentBackStackEntry
                val currentIsUploader =
                    currentEntry?.destination?.route == Destinations.BiliUploaderDetail.route
                val currentUploader = currentEntry
                    ?.arguments
                    ?.getString("uploaderJson")
                    ?.let {
                        runCatching {
                            navigationGson.fromJson(it, BiliUploaderSummary::class.java)
                        }.getOrNull()
                    }
                if (currentUploader?.mid == uploader.mid) {
                    return
                }
                if (currentIsUploader) {
                    navController.popBackStack()
                }
                navController.navigate(biliUploaderSourceRoute(uploader)) {
                    launchSingleTop = true
                }
            }
            fun navigateToYouTubeMusicCreator(creator: YouTubeMusicCreatorSummary) {
                if (creator.browseId.isBlank()) return
                val currentEntry = navController.currentBackStackEntry
                val currentCreator = currentEntry
                    ?.takeIf {
                        it.destination.route == Destinations.YouTubeMusicCreatorDetail.route
                    }
                    ?.arguments
                    ?.getString("creatorJson")
                    ?.let { creatorJson ->
                        runCatching {
                            navigationGson.fromJson(
                                creatorJson,
                                YouTubeMusicCreatorSummary::class.java
                            )
                        }.getOrNull()
                    }
                if (currentCreator?.browseId == creator.browseId) {
                    return
                }
                val json = Uri.encode(navigationGson.toJson(creator))
                navController.navigate("youtube_music_creator_detail/$json") {
                    launchSingleTop = true
                }
            }
            fun navigateToYouTubeMusicPlaylist(playlist: YouTubeMusicPlaylist) {
                if (playlist.browseId.isBlank()) return
                val currentEntry = navController.currentBackStackEntry
                val currentPlaylist = currentEntry
                    ?.takeIf {
                        it.destination.route == Destinations.YouTubeMusicPlaylistDetail.route
                    }
                    ?.arguments
                    ?.getString("playlistJson")
                    ?.let { playlistJson ->
                        runCatching {
                            navigationGson.fromJson(
                                playlistJson,
                                YouTubeMusicPlaylist::class.java
                            )
                        }.getOrNull()
                    }
                if (currentPlaylist?.browseId == playlist.browseId) {
                    return
                }
                val json = Uri.encode(navigationGson.toJson(playlist))
                navController.navigate("youtube_music_playlist_detail/$json") {
                    launchSingleTop = true
                }
            }
            LaunchedEffect(currentRoute, restoreLyricsAfterAlbumBack) {
                if (!restoreLyricsAfterAlbumBack) {
                    lyricsAlbumRouteObserved = false
                    return@LaunchedEffect
                }
                if (currentRoute == Destinations.NeteaseAlbumDetail.route) {
                    lyricsAlbumRouteObserved = true
                    return@LaunchedEffect
                }
                if (lyricsAlbumRouteObserved) {
                    restoreLyricsAfterAlbumBack = false
                    lyricsAlbumRouteObserved = false
                    showNowPlayingLyrics = true
                    showNowPlaying = true
                }
            }
            val bottomBarItems = remember(showHomeTab, devModeEnabled) {
                buildList {
                    if (showHomeTab) add(Destinations.Home to Icons.Outlined.Home)
                    add(Destinations.Explore to Icons.Outlined.Search)
                    add(Destinations.Library to Icons.Outlined.LibraryMusic)
                    add(Destinations.Settings to Icons.Outlined.Settings)
                    if (devModeEnabled) add(Destinations.Debug to Icons.Outlined.BugReport)
                }
            }

            val snackbarHostState = remember { SnackbarHostState() }
            val managedLibraryProcessingState by
                ManagedLibraryProcessingCoordinator.state.collectAsStateWithLifecycle()
            val managedMigrationProgress by
                ManagedDownloadStorage.migrationProgressFlow.collectAsStateWithLifecycle()
            var managedProcessingBannerDisplayState by remember {
                mutableStateOf<ManagedLibraryProcessingState>(
                    ManagedLibraryProcessingState.Idle
                )
            }
            var managedProcessingBannerDisplayProgress by remember {
                mutableStateOf<ManagedDownloadStorage.MigrationProgress?>(null)
            }
            var managedProcessingBannerCollapsed by rememberSaveable {
                mutableStateOf(false)
            }
            LaunchedEffect(managedLibraryProcessingState, managedMigrationProgress) {
                if (managedLibraryProcessingState != ManagedLibraryProcessingState.Idle) {
                    managedProcessingBannerDisplayState = managedLibraryProcessingState
                    managedProcessingBannerDisplayProgress = managedMigrationProgress
                } else if (managedMigrationProgress != null) {
                    managedProcessingBannerDisplayProgress = managedMigrationProgress
                }
            }
            LaunchedEffect(managedLibraryProcessingState.operationId) {
                if (managedLibraryProcessingState != ManagedLibraryProcessingState.Idle) {
                    managedProcessingBannerCollapsed = false
                }
            }
            val managedProcessingBannerState =
                managedLibraryProcessingState.takeIf {
                    it != ManagedLibraryProcessingState.Idle
                } ?: managedProcessingBannerDisplayState
            val managedProcessingBannerProgress =
                if (managedLibraryProcessingState != ManagedLibraryProcessingState.Idle) {
                    managedMigrationProgress
                } else {
                    managedProcessingBannerDisplayProgress
                }
            val homeHostRuntimeState = rememberHomeHostRuntimeState()

            @Composable
            fun RenderNavigationScene(
                revealTopFraction: Float = 0f,
                contentTranslationYFraction: Float = 0f,
                contentScale: Float = 1f,
                navigationDepth: Int = 0,
                fixedBackground: Boolean = false,
                content: @Composable () -> Unit
            ) {
                AdvancedGlassSceneLayer(
                    controller = advancedGlassController,
                    modifier = Modifier.advancedGlassSceneZIndex(navigationDepth),
                    motion = AdvancedGlassSceneMotion(
                        revealTopFraction = revealTopFraction,
                        contentTranslationYFraction = contentTranslationYFraction,
                        contentScale = contentScale
                    ),
                    disableStretchOverscroll = backgroundImageUri != null,
                    fixedBackground = fixedBackground,
                    background = {
                        // 场景自绘壁纸背景, 玻璃模糊要采样它
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            CustomBackground(
                                imageUri = backgroundImageUri,
                                blur = backgroundImageBlur,
                                alpha = effectiveBackgroundImageAlpha
                            )
                        }
                    },
                    content = { content() }
                )
            }

            @Composable
            fun AnimatedContentScope.RenderNavHostScene(
                sceneRoute: String?,
                content: @Composable () -> Unit
            ) {
                val sceneDepth = transparentNavigationDepth(sceneRoute)
                val currentDepth = transparentNavigationDepth(currentRoute)
                val enteringFromDeeperScene = sceneRoute == currentRoute &&
                    visibleNavigationRoutes.any { route ->
                        transparentNavigationDepth(route) > sceneDepth
                    }
                val exitingToDeeperScene = sceneRoute != currentRoute &&
                    currentDepth > sceneDepth
                val motion = transition.animateAdvancedGlassVisibilitySceneMotion(
                    coherentFeedbackEnabled = coherentFeedbackEnabled,
                    enteringFromDeeperScene = enteringFromDeeperScene,
                    exitingToDeeperScene = exitingToDeeperScene,
                    label = "nav_scene_${sceneRoute.orEmpty()}"
                )
                RenderNavigationScene(
                    revealTopFraction = motion.revealTopFraction,
                    contentTranslationYFraction = motion.contentTranslationYFraction,
                    contentScale = motion.contentScale,
                    navigationDepth = sceneDepth,
                    fixedBackground = false,
                    content = content
                )
            }

            @Composable
            fun RenderMainTabNavigationScene(
                revealTopFraction: Float,
                contentTranslationYFraction: Float,
                contentScale: Float,
                sceneDepth: Int = 0,
                content: @Composable () -> Unit
            ) {
                val applyExternalDrawerMotion =
                    mainTabNavigationMotion.backgroundMotion == MainTabBackgroundMotion.DRAWER_SINK
                RenderNavigationScene(
                    revealTopFraction = revealTopFraction,
                    contentTranslationYFraction = contentTranslationYFraction +
                        if (applyExternalDrawerMotion) {
                            mainTabNavigationMotion.backgroundTransform.translationYFraction
                        } else {
                            0f
                        },
                    contentScale = contentScale * if (applyExternalDrawerMotion) {
                        mainTabNavigationMotion.backgroundTransform.scale
                    } else {
                        1f
                    },
                    navigationDepth = sceneDepth,
                    // 只有 tab 根列表 (sceneDepth 0) 走固定背景, 横滑切 tab 时壁纸不动
                    // 嵌套详情必须保留不透明自背景, 靠揭示裁剪盖住退出列表, 否则两页内容互透叠印
                    fixedBackground = backgroundImageUri != null && sceneDepth == 0,
                    content = content
                )
            }

            @Composable
            fun RenderMainTabRoute(route: String) {
                when (route) {
                    Destinations.Home.route -> HomeHostScreen(
                        showContinueCard = showHomeContinueCard,
                        showTrendingCard = showHomeTrendingCard,
                        showRadarCard = showHomeRadarCard,
                        showRecommendedCard = showHomeRecommendedCard,
                        homeUsageEntries = homeUsageSnapshot.entries,
                        homeUsageLoaded = homeUsageSnapshot.isLoaded,
                        offlineMode = offlineMode,
                        runtimeState = homeHostRuntimeState,
                        onSongClick = ::playSongsAndOpenNowPlaying,
                        onSongClickWithSourceRoute = ::playSongsAndOpenNowPlaying,
                        onPlayBiliAudioWithSourceRoute = ::playBiliAudioAndOpenNowPlayingWithSource,
                        onPlayBiliPartsWithSourceRoute = ::playBiliPartsAndOpenNowPlayingWithSource,
                        neteasePlaylistSourceRoute = ::neteasePlaylistSourceRoute,
                        neteaseAlbumSourceRoute = ::neteaseAlbumSourceRoute,
                        biliPlaylistSourceRoute = ::biliPlaylistSourceRoute,
                        localPlaylistSourceRoute = ::localPlaylistSourceRoute,
                        coherentFeedbackEnabled = coherentFeedbackEnabled,
                        renderScene = { revealTop, translationY, scale, sceneDepth, sceneContent ->
                            RenderMainTabNavigationScene(
                                revealTop,
                                translationY,
                                scale,
                                sceneDepth = sceneDepth,
                                content = sceneContent
                            )
                        }
                    )

                    Destinations.Explore.route -> ExploreHostScreen(
                        offlineMode = offlineMode,
                        onSongClick = ::playSongsAndOpenNowPlaying,
                        onSongClickWithSourceRoute = ::playSongsAndOpenNowPlaying,
                        neteasePlaylistSourceRoute = ::neteasePlaylistSourceRoute,
                        onSongPlayPreservingQueue =
                            ::playSongPreservingQueueAndOpenNowPlaying,
                        onSongPlayNext = ::addSongToQueueNextFromSearch,
                        onSongAddToQueueEnd = ::addSongToQueueEndFromSearch,
                        onPlayParts = ::playBiliPartsAndOpenNowPlaying,
                        coherentFeedbackEnabled = coherentFeedbackEnabled,
                        renderScene = { revealTop, translationY, scale, sceneDepth, sceneContent ->
                            RenderMainTabNavigationScene(
                                revealTop,
                                translationY,
                                scale,
                                sceneDepth = sceneDepth,
                                content = sceneContent
                            )
                        }
                    )

                    Destinations.Library.route -> LibraryHostScreen(
                        onSongClick = ::playSongsAndOpenNowPlaying,
                        onSongClickWithSourceRoute = ::playSongsAndOpenNowPlaying,
                        onPlayBiliAudioWithSourceRoute = ::playBiliAudioAndOpenNowPlayingWithSource,
                        onPlayBiliPartsWithSourceRoute = ::playBiliPartsAndOpenNowPlayingWithSource,
                        neteasePlaylistSourceRoute = ::neteasePlaylistSourceRoute,
                        neteaseAlbumSourceRoute = ::neteaseAlbumSourceRoute,
                        biliPlaylistSourceRoute = ::biliPlaylistSourceRoute,
                        localPlaylistSourceRoute = ::localPlaylistSourceRoute,
                        onOpenRecent = {
                            navController.navigate(Destinations.Recent.route)
                        },
                        onOpenStats = {
                            navController.navigate(Destinations.PlaybackStats.route)
                        },
                        offlineMode = offlineMode,
                        coherentFeedbackEnabled = coherentFeedbackEnabled,
                        renderScene = { revealTop, translationY, scale, sceneDepth, sceneContent ->
                            RenderMainTabNavigationScene(
                                revealTop,
                                translationY,
                                scale,
                                sceneDepth = sceneDepth,
                                content = sceneContent
                            )
                        }
                    )

                    Destinations.Settings.route -> SettingsHostScreen(
                        dynamicColor = dynamicColorEnabled,
                        onDynamicColorChange = { scope.launch { repo.setDynamicColor(it) } },
                        isDarkTheme = isDark,
                        themeMode = themeMode,
                        onThemeToggleRequest = ::requestThemeToggle,
                        onThemeModeRequest = ::requestThemeModeChange,
                        preferredQuality = preferredQuality,
                        onQualityChange = { scope.launch { repo.setAudioQuality(it) } },
                        youtubePreferredQuality = youtubePreferredQuality,
                        onYouTubeQualityChange = {
                            scope.launch { repo.setYouTubeAudioQuality(it) }
                        },
                        biliPreferredQuality = biliPreferredQuality,
                        onBiliQualityChange = { scope.launch { repo.setBiliAudioQuality(it) } },
                        mobileDataFollowDefaultAudioQuality =
                            mobileDataFollowDefaultAudioQuality,
                        onMobileDataFollowDefaultAudioQualityChange = { enabled ->
                            scope.launch {
                                repo.setMobileDataFollowDefaultAudioQuality(enabled)
                            }
                        },
                        mobileDataNeteaseAudioQuality = mobileDataNeteaseAudioQuality,
                        onMobileDataNeteaseAudioQualityChange = { quality ->
                            scope.launch {
                                repo.setMobileDataNeteaseAudioQuality(quality)
                            }
                        },
                        mobileDataYouTubeAudioQuality = mobileDataYouTubeAudioQuality,
                        onMobileDataYouTubeAudioQualityChange = { quality ->
                            scope.launch {
                                repo.setMobileDataYouTubeAudioQuality(quality)
                            }
                        },
                        mobileDataBiliAudioQuality = mobileDataBiliAudioQuality,
                        onMobileDataBiliAudioQualityChange = { quality ->
                            scope.launch {
                                repo.setMobileDataBiliAudioQuality(quality)
                            }
                        },
                        seedColorHex = themeSeedColor,
                        onSeedColorChange = { hex ->
                            scope.launch { repo.setThemeSeedColor(hex) }
                        },
                        themeColorPalette = themeColorPalette,
                        onAddColorToPalette = { hex ->
                            scope.launch { repo.addThemePaletteColor(hex) }
                        },
                        onRemoveColorFromPalette = { hex ->
                            scope.launch { repo.removeThemePaletteColor(hex) }
                        },
                        themePaletteStyle = themePaletteStyleValue,
                        onThemePaletteStyleChange = { style ->
                            scope.launch { repo.setThemePaletteStyle(style) }
                        },
                        themeColorSpec = themeColorSpecValue,
                        onThemeColorSpecChange = { spec ->
                            scope.launch { repo.setThemeColorSpec(spec) }
                        },
                        devModeEnabled = devModeEnabled,
                        onDevModeChange = { enabled ->
                            scope.launch { repo.setDevModeEnabled(enabled) }
                        },
                        lyricBlurEnabled = lyricBlurEnabled,
                        onLyricBlurEnabledChange = { enabled ->
                            scope.launch { repo.setLyricBlurEnabled(enabled) }
                        },
                        lyricBlurAmount = lyricBlurAmount,
                        onLyricBlurAmountChange = { amount ->
                            scope.launch { repo.setLyricBlurAmount(amount) }
                        },
                        cloudMusicLyricDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
                        onCloudMusicLyricDefaultOffsetMsChange = { offsetMs ->
                            scope.launch {
                                val previousOffset = cloudMusicLyricDefaultOffsetMs
                                if (previousOffset == offsetMs) {
                                    return@launch
                                }
                                PlayerManager.rebaseUserLyricOffsetsForSource(
                                    targetSource = MusicPlatform.CLOUD_MUSIC,
                                    previousDefaultOffsetMs = previousOffset,
                                    newDefaultOffsetMs = offsetMs
                                )
                                runCatching {
                                    repo.setCloudMusicLyricDefaultOffsetMs(offsetMs)
                                }.onFailure {
                                    PlayerManager.rebaseUserLyricOffsetsForSource(
                                        targetSource = MusicPlatform.CLOUD_MUSIC,
                                        previousDefaultOffsetMs = offsetMs,
                                        newDefaultOffsetMs = previousOffset
                                    )
                                }.getOrThrow()
                            }
                        },
                        qqMusicLyricDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
                        onQqMusicLyricDefaultOffsetMsChange = { offsetMs ->
                            scope.launch {
                                val previousOffset = qqMusicLyricDefaultOffsetMs
                                if (previousOffset == offsetMs) {
                                    return@launch
                                }
                                PlayerManager.rebaseUserLyricOffsetsForSource(
                                    targetSource = MusicPlatform.QQ_MUSIC,
                                    previousDefaultOffsetMs = previousOffset,
                                    newDefaultOffsetMs = offsetMs
                                )
                                runCatching {
                                    repo.setQqMusicLyricDefaultOffsetMs(offsetMs)
                                }.onFailure {
                                    PlayerManager.rebaseUserLyricOffsetsForSource(
                                        targetSource = MusicPlatform.QQ_MUSIC,
                                        previousDefaultOffsetMs = offsetMs,
                                        newDefaultOffsetMs = previousOffset
                                    )
                                }.getOrThrow()
                            }
                        },
                        kugouLyricDefaultOffsetMs = kugouLyricDefaultOffsetMs,
                        onKugouLyricDefaultOffsetMsChange = { offsetMs ->
                            scope.launch { repo.setKugouLyricDefaultOffsetMs(offsetMs) }
                        },
                        lrclibLyricDefaultOffsetMs = lrclibLyricDefaultOffsetMs,
                        onLrclibLyricDefaultOffsetMsChange = { offsetMs ->
                            scope.launch { repo.setLrclibLyricDefaultOffsetMs(offsetMs) }
                        },
                        amllTtmlLyricDefaultOffsetMs = amllTtmlLyricDefaultOffsetMs,
                        onAmllTtmlLyricDefaultOffsetMsChange = { offsetMs ->
                            scope.launch { repo.setAmllTtmlLyricDefaultOffsetMs(offsetMs) }
                        },
                        onResetAllLyricDefaultOffsets = {
                            scope.launch {
                                var cloudRebased = false
                                var qqRebased = false
                                try {
                                    PlayerManager.rebaseUserLyricOffsetsForSource(
                                        targetSource = MusicPlatform.CLOUD_MUSIC,
                                        previousDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
                                        newDefaultOffsetMs = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
                                    )
                                    cloudRebased = true
                                    PlayerManager.rebaseUserLyricOffsetsForSource(
                                        targetSource = MusicPlatform.QQ_MUSIC,
                                        previousDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
                                        newDefaultOffsetMs = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
                                    )
                                    qqRebased = true
                                    repo.resetLyricDefaultOffsets()
                                } catch (error: Throwable) {
                                    if (qqRebased) {
                                        PlayerManager.rebaseUserLyricOffsetsForSource(
                                            targetSource = MusicPlatform.QQ_MUSIC,
                                            previousDefaultOffsetMs = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
                                            newDefaultOffsetMs = qqMusicLyricDefaultOffsetMs
                                        )
                                    }
                                    if (cloudRebased) {
                                        PlayerManager.rebaseUserLyricOffsetsForSource(
                                            targetSource = MusicPlatform.CLOUD_MUSIC,
                                            previousDefaultOffsetMs = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
                                            newDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs
                                        )
                                    }
                                    throw error
                                }
                            }
                        },
                        floatingLyricsPreferences = floatingLyricsPreferences,
                        onFloatingLyricsPreferencesChange = { preferences ->
                            scope.launch { repo.setFloatingLyricsPreferences(preferences) }
                        },
                        advancedBlurEnabled = advancedBlurEnabled,
                        onAdvancedBlurEnabledChange = { enabled ->
                            scope.launch { repo.setAdvancedBlurEnabled(enabled) }
                        },
                        enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
                        onEnhancedAdvancedBlurEnabledChange = { enabled ->
                            scope.launch {
                                repo.setEnhancedAdvancedBlurEnabled(enabled)
                            }
                        },
                        enhancedAdvancedBlurRadiusDp = enhancedAdvancedBlurRadiusDp,
                        onEnhancedAdvancedBlurRadiusDpChange = { radiusDp ->
                            scope.launch {
                                repo.setEnhancedAdvancedBlurRadiusDp(radiusDp)
                            }
                        },
                        advancedBlurQuality = advancedBlurQuality,
                        onAdvancedBlurQualityChange = { quality ->
                            scope.launch { repo.setAdvancedBlurQuality(quality) }
                        },
                        nowPlayingAudioReactiveEnabled = nowPlayingAudioReactiveEnabled,
                        onNowPlayingAudioReactiveEnabledChange = { enabled ->
                            scope.launch { repo.setNowPlayingAudioReactiveEnabled(enabled) }
                        },
                        nowPlayingDynamicBackgroundEnabled = nowPlayingDynamicBackgroundEnabled,
                        onNowPlayingDynamicBackgroundEnabledChange = { enabled ->
                            scope.launch { repo.setNowPlayingDynamicBackgroundEnabled(enabled) }
                        },
                        nowPlayingCoverBlurBackgroundEnabled =
                            nowPlayingCoverBlurBackgroundEnabled,
                        onNowPlayingCoverBlurBackgroundEnabledChange = { enabled ->
                            scope.launch {
                                repo.setNowPlayingCoverBlurBackgroundEnabled(enabled)
                            }
                        },
                        nowPlayingCoverBlurAmount = nowPlayingCoverBlurAmount,
                        onNowPlayingCoverBlurAmountChange = { amount ->
                            scope.launch { repo.setNowPlayingCoverBlurAmount(amount) }
                        },
                        nowPlayingCoverBlurDarken = nowPlayingCoverBlurDarken,
                        onNowPlayingCoverBlurDarkenChange = { amount ->
                            scope.launch { repo.setNowPlayingCoverBlurDarken(amount) }
                        },
                        lyricFontScales = lyricFontScales,
                        onLyricFontScaleChange = { target: LyricFontScaleTarget, scale ->
                            scope.launch { repo.setLyricFontScale(target, scale) }
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
                        downloadDirectoryUri = downloadDirectoryUri,
                        downloadFileNameTemplate = downloadFileNameTemplate,
                        onDownloadDirectoryUriChange = { uri, label ->
                            scope.launch {
                                repo.setDownloadDirectory(uri, label)
                                ManagedDownloadStorage.updateConfiguredTreeUri(uri)
                                ManagedDownloadStorage.updateCustomDirectoryLabel(label)
                            }
                        },
                        onDownloadFileNameTemplateChange = { template ->
                            scope.launch { repo.setDownloadFileNameTemplate(template) }
                        },
                        backgroundImageBlur = backgroundImageBlur,
                        onBackgroundImageBlurChange = {},
                        onBackgroundImageBlurChangeFinished = { blur ->
                            scope.launch { repo.setBackgroundImageBlur(blur) }
                        },
                        backgroundImageAlpha = effectiveBackgroundImageAlpha,
                        onBackgroundImageAlphaChange = { alpha ->
                            pendingBackgroundImageAlpha = alpha
                        },
                        onBackgroundImageAlphaChangeFinished = { alpha ->
                            pendingBackgroundImageAlpha = alpha
                            scope.launch { repo.setBackgroundImageAlpha(alpha) }
                        },
                        defaultStartDestination = currentDefaultStartDestination,
                        onDefaultStartDestinationChange = { route ->
                            scope.launch { repo.setDefaultStartDestination(route) }
                        },
                        showHomeContinueCard = showHomeContinueCard,
                        onShowHomeContinueCardChange = { enabled ->
                            scope.launch { repo.setHomeCardContinue(enabled) }
                        },
                        showHomeTrendingCard = showHomeTrendingCard,
                        onShowHomeTrendingCardChange = { enabled ->
                            scope.launch { repo.setHomeCardTrending(enabled) }
                        },
                        showHomeRadarCard = showHomeRadarCard,
                        onShowHomeRadarCardChange = { enabled ->
                            scope.launch { repo.setHomeCardRadar(enabled) }
                        },
                        showHomeRecommendedCard = showHomeRecommendedCard,
                        onShowHomeRecommendedCardChange = { enabled ->
                            scope.launch { repo.setHomeCardRecommended(enabled) }
                        },
                        homeHasRecentUsage = homeUsageSnapshot.entries.isNotEmpty(),
                        playbackFadeIn = playbackFadeIn,
                        onPlaybackFadeInChange = { enabled ->
                            scope.launch { repo.setPlaybackFadeIn(enabled) }
                        },
                        playbackCrossfadeNext = playbackCrossfadeNext,
                        onPlaybackCrossfadeNextChange = { enabled ->
                            scope.launch { repo.setPlaybackCrossfadeNext(enabled) }
                        },
                        sleepTimerFinishCurrentOnExpiry = sleepTimerFinishCurrentOnExpiry,
                        onSleepTimerFinishCurrentOnExpiryChange = { enabled ->
                            scope.launch {
                                repo.setSleepTimerFinishCurrentOnExpiry(enabled)
                            }
                        },
                        playbackFadeInDurationMs = playbackFadeInDurationMs,
                        onPlaybackFadeInDurationMsChange = { duration ->
                            scope.launch { repo.setPlaybackFadeInDurationMs(duration) }
                        },
                        playbackFadeOutDurationMs = playbackFadeOutDurationMs,
                        onPlaybackFadeOutDurationMsChange = { duration ->
                            scope.launch { repo.setPlaybackFadeOutDurationMs(duration) }
                        },
                        playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
                        onPlaybackCrossfadeInDurationMsChange = { duration ->
                            scope.launch { repo.setPlaybackCrossfadeInDurationMs(duration) }
                        },
                        playbackCrossfadeOutDurationMs = playbackCrossfadeOutDurationMs,
                        onPlaybackCrossfadeOutDurationMsChange = { duration ->
                            scope.launch { repo.setPlaybackCrossfadeOutDurationMs(duration) }
                        },
                        playbackVolumeNormalizationEnabled =
                            playbackVolumeNormalizationEnabled,
                        onPlaybackVolumeNormalizationEnabledChange = { enabled ->
                            PlayerManager.setPlaybackVolumeNormalizationEnabled(enabled)
                        },
                        playbackHighResolutionOutputEnabled =
                            playbackHighResolutionOutputEnabled,
                        onPlaybackHighResolutionOutputEnabledChange = { enabled ->
                            PlayerManager.setPlaybackHighResolutionOutputEnabled(enabled)
                            AppFeedback.show(
                                context = context,
                                message = composeResources.getString(R.string.settings_restart_hint)
                            )
                        },
                        playbackVolumeBalance = playbackVolumeBalance,
                        onPlaybackVolumeBalanceChange = { balance ->
                            PlayerManager.setPlaybackVolumeBalance(balance)
                        },
                        keepLastPlaybackProgress = keepLastPlaybackProgress,
                        onKeepLastPlaybackProgressChange = { enabled ->
                            scope.launch { repo.setKeepLastPlaybackProgress(enabled) }
                        },
                        rememberLongFormPlaybackProgress = rememberLongFormPlaybackProgress,
                        onRememberLongFormPlaybackProgressChange = { enabled ->
                            scope.launch {
                                repo.setRememberLongFormPlaybackProgress(enabled)
                            }
                        },
                        keepPlaybackModeState = keepPlaybackModeState,
                        onKeepPlaybackModeStateChange = { enabled ->
                            scope.launch { repo.setKeepPlaybackModeState(enabled) }
                        },
                        neteaseAutoSourceSwitch = neteaseAutoSourceSwitch,
                        onNeteaseAutoSourceSwitchChange = { enabled ->
                            scope.launch { repo.setNeteaseAutoSourceSwitch(enabled) }
                        },
                        neteaseLocalSourceFallback = neteaseLocalSourceFallback,
                        onNeteaseLocalSourceFallbackChange = { enabled ->
                            scope.launch { repo.setNeteaseLocalSourceFallback(enabled) }
                        },
                        stopOnBluetoothDisconnect = stopOnBluetoothDisconnect,
                        onStopOnBluetoothDisconnectChange = { enabled ->
                            scope.launch { repo.setStopOnBluetoothDisconnect(enabled) }
                        },
                        usbExclusivePlayback = usbExclusivePlayback,
                        onUsbExclusivePlaybackChange = { enabled ->
                            if (PlayerManager.beginUsbExclusiveToggleTransitionFromUi(enabled)) {
                                scope.launch { repo.setUsbExclusivePlayback(enabled) }
                            }
                        },
                        allowMixedPlayback = allowMixedPlayback,
                        onAllowMixedPlaybackChange = { enabled ->
                            scope.launch { repo.setAllowMixedPlayback(enabled) }
                        },
                        preemptAudioFocus = preemptAudioFocus,
                        onPreemptAudioFocusChange = { enabled ->
                            scope.launch { repo.setPreemptAudioFocus(enabled) }
                        },
                        maxCacheSizeBytes = maxCacheSizeBytes,
                        onMaxCacheSizeBytesChange = { size ->
                            scope.launch { repo.setMaxCacheSizeBytes(size) }
                        },
                        onClearCacheClick = { options ->
                            scope.launch {
                                val messages = mutableListOf<String>()
                                if (options.needsPlayerCacheClear) {
                                    val (_, message) = PlayerManager.clearCache(
                                        clearAudio = options.audioCache,
                                        clearImage = options.imageCache
                                    )
                                    messages += message
                                }
                                if (options.needsExtraCacheClear) {
                                    if (options.lyricsCache) {
                                        PlayerLyricsProvider.clearLyricsCaches(
                                            neteaseLyricsCache = PlayerManager.neteaseLyricsCache,
                                            ytMusicLyricsCache = PlayerManager.ytMusicLyricsCache
                                        )
                                        withContext(Dispatchers.IO) {
                                            PlayerLyricsProvider.clearPersistentLyricCache(
                                                AppContainer.applicationContext
                                            )
                                        }
                                    }
                                    val result = clearExtraStorageCaches(context, options)
                                    messages += when {
                                        !result.success -> composeResources.getString(
                                            R.string.storage_extra_cache_clear_partial
                                        )
                                        result.roomBytesMadeReusable > 0L ->
                                            composeResources.getString(
                                                R.string.storage_extra_cache_clear_room_complete,
                                                formatFileSize(result.freedBytes),
                                                formatFileSize(result.roomBytesMadeReusable)
                                            )
                                        else -> composeResources.getString(
                                            R.string.storage_extra_cache_clear_complete,
                                            formatFileSize(result.freedBytes)
                                        )
                                    }
                                }
                                snackbarHostState.showNeriSnackbar(messages.joinToString(" · "))
                            }
                        },
                        onBeforeLanguageRestart = clearThemeRevealState,
                        onLanguageChanged = onLanguageChanged,
                        coherentFeedbackEnabled = coherentFeedbackEnabled,
                        renderScene = { revealTop, translationY, scale, sceneDepth, sceneContent ->
                            RenderMainTabNavigationScene(
                                revealTop,
                                translationY,
                                scale,
                                sceneDepth = sceneDepth,
                                content = sceneContent
                            )
                        }
                    )

                    Destinations.Debug.route -> {
                        val debugHomeScrollState = rememberScrollState()
                        RenderMainTabNavigationScene(
                            revealTopFraction = 0f,
                            contentTranslationYFraction = 0f,
                            contentScale = 1f
                        ) {
                            DebugHomeScreen(
                            scrollState = debugHomeScrollState,
                            alwaysRecordLogsEnabled = alwaysRecordLogsEnabled,
                            onAlwaysRecordLogsChange = { enabled ->
                                scope.launch { repo.setAlwaysRecordLogsEnabled(enabled) }
                            },
                            onOpenListenTogetherDebug = {
                                navController.navigate(Destinations.DebugListenTogether.route)
                            },
                            onOpenUsbExclusiveDebug = {
                                navController.navigate(Destinations.DebugUsbExclusive.route)
                            },
                            onOpenYouTubeDebug = {
                                navController.navigate(Destinations.DebugYouTube.route)
                            },
                            onOpenBiliDebug = {
                                navController.navigate(Destinations.DebugBili.route)
                            },
                            onOpenNeteaseDebug = {
                                navController.navigate(Destinations.DebugNetease.route)
                            },
                            onOpenSearchDebug = {
                                navController.navigate(Destinations.DebugSearch.route)
                            },
                            onOpenLogs = {
                                navController.navigate(Destinations.DebugLogsList.route)
                            },
                            onOpenCrashLogs = {
                                navController.navigate(Destinations.DebugCrashLogsList.route)
                            },
                            onTestExceptionHandler = { crashType ->
                            val crashMessage = composeResources.getString(R.string.test_exception_message)
                            when (crashType) {
                                DebugCrashTestType.JvmHandled -> {
                                    ExceptionHandler.safeExecute("DebugTestHandled") {
                                        throw RuntimeException(crashMessage)
                                    }
                                }

                                DebugCrashTestType.JvmUncaughtMain -> {
                                    Handler(Looper.getMainLooper()).post {
                                        throw RuntimeException(crashMessage)
                                    }
                                }

                                DebugCrashTestType.JvmUncaughtWorker -> {
                                    Thread {
                                        throw RuntimeException(crashMessage)
                                    }.start()
                                }

                                DebugCrashTestType.MainThreadAnr -> {
                                    AnrWatchdog.triggerTestAnr(context)
                                }

                                DebugCrashTestType.NativeSigSegv -> {
                                    Handler(Looper.getMainLooper()).post {
                                        NativeCrashHandler.triggerTestCrash(
                                            context = context,
                                            crashType = NativeCrashHandler.TestCrashType.SigSegv
                                        )
                                    }
                                }

                                DebugCrashTestType.NativeSigAbrt -> {
                                    Handler(Looper.getMainLooper()).post {
                                        NativeCrashHandler.triggerTestCrash(
                                            context = context,
                                            crashType = NativeCrashHandler.TestCrashType.SigAbrt
                                        )
                                    }
                                }
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
                    }
                }
            }

            val effectiveDynamicBackgroundEnabled =
                nowPlayingDynamicBackgroundEnabled && !nowPlayingCoverBlurBackgroundEnabled
            val effectiveAudioReactiveEnabled =
                nowPlayingAudioReactiveEnabled && effectiveDynamicBackgroundEnabled

            DisposableEffect(showNowPlaying, effectiveAudioReactiveEnabled, lifecycleResumed) {
                AudioReactive.enabled = showNowPlaying && effectiveAudioReactiveEnabled && lifecycleResumed
                onDispose { AudioReactive.enabled = false }
            }

            DisposableEffect(showNowPlaying, lifecycleResumed) {
                PlayerManager.updateInteractiveNowPlayingVisible(showNowPlaying && lifecycleResumed)
                onDispose { PlayerManager.updateInteractiveNowPlayingVisible(false) }
            }

            val activity = remember(context) { context.findActivity() }
            DisposableEffect(activity, showNowPlaying, nowPlayingKeepScreenOn, lifecycleResumed) {
                val window = activity?.window
                val keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                val shouldKeepScreenOn = showNowPlaying && nowPlayingKeepScreenOn && lifecycleResumed
                val wasKeepScreenOn = window?.attributes?.flags?.and(keepScreenOnFlag) == keepScreenOnFlag
                if (shouldKeepScreenOn) {
                    window?.addFlags(keepScreenOnFlag)
                }
                onDispose {
                    if (shouldKeepScreenOn && !wasKeepScreenOn) {
                        window?.clearFlags(keepScreenOnFlag)
                    }
                }
            }

            AdvancedGlassHost(
                controller = advancedGlassController,
                backgroundBackdrop = backgroundGlassBackdrop,
                contentBackdrop = contentGlassBackdrop,
                activeNavigationOwners = activeAdvancedGlassOwners,
                disableStretchOverscroll = backgroundImageUri != null
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .captureAdvancedGlassBackdrop(backgroundGlassBackdrop)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    CustomBackground(
                        imageUri = backgroundImageUri,
                        blur = backgroundImageBlur,
                        alpha = effectiveBackgroundImageAlpha
                    )
                }

                val containerColor = Color.Transparent

                val selectAlpha = resolveBottomBarSelectionAlpha(
                    hasCustomBackground = backgroundImageUri != null,
                    alwaysUseNewTabStyle = alwaysUseNewTabStyle
                )

                val isMiniPlayerVisible = currentSong != null && !showNowPlaying
                val isPlaybackControlPlaying by PlayerManager.playbackControlPlayingFlow.collectAsStateWithLifecycle()
                val isAudioRouteMuted by PlayerManager.audioRouteMuteSuppressedFlow
                    .collectAsStateWithLifecycle()
                val isPlaying by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
                val usbPlaybackPreparing by PlayerManager.usbExclusivePlaybackPreparingFlow
                    .collectAsStateWithLifecycle()
                val isPlaybackWaiting = resolvePlaybackWaiting(
                    playbackRequested = isPlaybackControlPlaying,
                    isPlaying = isPlaying,
                    usbPlaybackPreparing = usbPlaybackPreparing
                )
                val reservedMiniPlayerHeightDp = if (isMiniPlayerVisible) {
                    NeriMiniPlayerDefaults.Height
                } else {
                    0.dp
                }

                LaunchedEffect(
                    currentRoute,
                    showHomeTab,
                    effectiveStartDestination,
                    defaultStartDestination,
                    awaitingPersistedStartDestination
                ) {
                    val resolvedPersistedRoute = effectiveStartDestination.takeIf {
                        defaultStartDestination != null
                    }
                    val shouldApplyPersistedRoute =
                        shouldApplyPersistedStartupDestination(
                            awaitingPersistedRoute = awaitingPersistedStartDestination,
                            currentRoute = currentRoute,
                            initialFallbackRoute = navHostStartDestination,
                            resolvedPersistedRoute = resolvedPersistedRoute
                        )
                    if (defaultStartDestination != null && currentRoute != null) {
                        awaitingPersistedStartDestination = false
                    }
                    val requiredRoute = when {
                        shouldApplyPersistedRoute -> resolvedPersistedRoute
                        !showHomeTab && currentRoute == Destinations.Home.route ->
                            effectiveStartDestination
                        else -> null
                    }
                    requiredRoute?.let { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) {
                                inclusive = shouldApplyPersistedRoute
                                saveState = !shouldApplyPersistedRoute
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }

                CompositionLocalProvider(LocalMiniPlayerHeight provides reservedMiniPlayerHeightDp) {
                    AppFeedbackHostEffect(snackbarHostState)
                    Scaffold(
                        containerColor = containerColor,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        snackbarHost = {
                            val miniH = LocalMiniPlayerHeight.current
                            NeriSnackbarHost(
                                hostState = snackbarHostState,
                                bottomPadding = miniH
                            )
                        },
                        bottomBar = {
                            val bottomBarVisibilityProgress by animateFloatAsState(
                                targetValue = if (showNowPlaying) 0f else 1f,
                                animationSpec = tween(
                                    durationMillis = if (showNowPlaying) 220 else 280,
                                    easing = FastOutSlowInEasing
                                ),
                                label = "bottom_bar_visibility"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clipToBounds()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .onSizeChanged { size ->
                                            if (size.height > 0) {
                                                bottomBarHeightPx = size.height
                                            }
                                        }
                                        .graphicsLayer {
                                            translationY =
                                                (1f - bottomBarVisibilityProgress) * bottomBarHeightPx
                                                    .toFloat()
                                            alpha = bottomBarVisibilityProgress
                                        }
                                ) {
                                    AnimatedVisibility(visible = offlineMode) {
                                        OfflineModeBottomBanner()
                                    }

                                    NeriBottomBar(
                                        modifier = Modifier.fillMaxWidth(),
                                        selectAlpha = selectAlpha,
                                        items = bottomBarItems,
                                        currentDestination = backEntry?.destination,
                                        onItemSelected = { dest ->
                                            navigateToMainTab(dest.route)
                                        }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        val bottomBarInset = innerPadding.calculateBottomPadding()
                            .coerceAtLeast(0.dp)
                        val bottomBarLayoutInsets = resolveBottomBarLayoutInsets(
                            baseBlurRequested = advancedGlassController.isBaseBlurRequested,
                            bottomBarInset = bottomBarInset,
                            reservedMiniPlayerHeight = reservedMiniPlayerHeightDp
                        )
                        CompositionLocalProvider(
                            LocalMiniPlayerHeight provides bottomBarLayoutInsets.screenBottomInset
                        ) {
                            val managedProcessingBannerActive =
                                managedLibraryProcessingState != ManagedLibraryProcessingState.Idle
                            val processingRevealEdgePx = with(LocalDensity.current) {
                                MANAGED_LIBRARY_PROCESSING_REVEAL_EDGE.toPx()
                            }
                            val processingRevealThresholdPx = with(LocalDensity.current) {
                                MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD.toPx()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        bottom = bottomBarLayoutInsets.navContentBottomPadding
                                    )
                                    .clipToBounds()
                                    .managedProcessingRevealGesture(
                                        collapsed = managedProcessingBannerActive &&
                                            managedProcessingBannerCollapsed,
                                        edgePx = processingRevealEdgePx,
                                        thresholdPx = processingRevealThresholdPx,
                                        onExpand = {
                                            managedProcessingBannerCollapsed = false
                                        }
                                    )
                            ) {
                                AnimatedVisibility(
                                    visible = managedProcessingBannerActive &&
                                        !managedProcessingBannerCollapsed,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .windowInsetsPadding(WindowInsets.statusBars)
                                        .zIndex(MANAGED_LIBRARY_PROCESSING_Z_INDEX),
                                    enter =
                                        fadeIn(animationSpec = tween(durationMillis = 180)) +
                                            slideInVertically(
                                                animationSpec = tween(
                                                    durationMillis = 240,
                                                    easing = FastOutSlowInEasing
                                                ),
                                                initialOffsetY = { -it / 2 }
                                            ) +
                                            expandVertically(
                                                animationSpec = tween(
                                                    durationMillis = 240,
                                                    easing = FastOutSlowInEasing
                                                ),
                                                expandFrom = Alignment.Top
                                            ),
                                    exit =
                                        fadeOut(animationSpec = tween(durationMillis = 160)) +
                                            slideOutVertically(
                                                animationSpec = tween(
                                                    durationMillis = 220,
                                                    easing = FastOutSlowInEasing
                                                ),
                                                targetOffsetY = { -it / 2 }
                                            ) +
                                            shrinkVertically(
                                                animationSpec = tween(
                                                    durationMillis = 220,
                                                    easing = FastOutSlowInEasing
                                                ),
                                                shrinkTowards = Alignment.Top
                                            )
                                ) {
                                    ManagedLibraryProcessingBanner(
                                        state = managedProcessingBannerState,
                                        migrationProgress = managedProcessingBannerProgress,
                                        modifier = Modifier,
                                        interactive = managedProcessingBannerActive &&
                                            !managedProcessingBannerCollapsed,
                                        onCollapsedChange = {
                                            managedProcessingBannerCollapsed = it
                                        }
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clipToBounds()
                                ) {
                                // Keep the effect on a stable layer outside NavHost transitions
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .captureAdvancedGlassBackdrop(contentGlassBackdrop)
                                ) {
                                    MainTabLayerHost(
                                        selectedRoute = selectedMainTabRoute,
                                        transitionState = mainTabTransitionState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onSizeChanged { size ->
                                                if (size.height > 0) {
                                                    mainTabDetailContentHeightPx = size.height
                                                }
                                            }
                                            .offset {
                                                IntOffset(
                                                    x = 0,
                                                    y = (
                                                        mainTabNavigationMotion.tabLayerTransform
                                                            .translationYFraction *
                                                            mainTabDetailContentHeightPx
                                                    ).roundToInt()
                                                )
                                            }
                                            .graphicsLayer {
                                                scaleX = mainTabNavigationMotion.tabLayerTransform.scale
                                                scaleY = mainTabNavigationMotion.tabLayerTransform.scale
                                                alpha = mainTabNavigationMotion.tabLayerTransform.alpha
                                                transformOrigin = TransformOrigin.Center
                                            }
                                            .zIndex(MAIN_TAB_LAYER_Z_INDEX),
                                        onVisibleGlassOwnersChanged = {
                                            visibleMainTabGlassOwners = it
                                        },
                                        content = { route ->
                                            RenderMainTabRoute(route)
                                        }
                                    )
                                    AdvancedGlassNavigationHandoff(
                                        enabled = shouldUseAdvancedGlassNavigationHandoff(
                                            visibleNavigationRoutes
                                        )
                                    ) {
                                        NavHost(
                                            navController = navController,
                                            startDestination = navHostStartDestination,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .zIndex(NAV_HOST_LAYER_Z_INDEX)
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
                                    RenderNavHostScene(
                                        Destinations.PlaylistDetail.route
                                    ) {
                                        NeteasePlaylistDetailScreen(
                                            playlist = playlist,
                                            onBack = { navController.popBackStack() },
                                            onSongClick = { songs, index ->
                                                playSongsAndOpenNowPlaying(
                                                    songs = songs,
                                                    index = index,
                                                    sourceRoute = neteasePlaylistSourceRoute(playlist)
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
                                    RenderNavHostScene(
                                        Destinations.NeteaseAlbumDetail.route
                                    ) {
                                        NeteaseAlbumDetailScreen(
                                            album = album,
                                            onBack = { navController.popBackStack() },
                                            onSongClick = { songs, index ->
                                                playSongsAndOpenNowPlaying(
                                                    songs = songs,
                                                    index = index,
                                                    sourceRoute = neteaseAlbumSourceRoute(album)
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
                                    RenderNavHostScene(
                                        Destinations.NeteaseArtistDetail.route
                                    ) {
                                        NeteaseArtistDetailScreen(
                                            artist = artist,
                                            onBack = { navController.popBackStack() },
                                            onSongClick = ::playSongsAndOpenNowPlaying,
                                            offlineMode = offlineMode,
                                            onAlbumClick = { album ->
                                                navigateToNeteaseAlbum(album)
                                            }
                                        )
                                    }
                                }

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
                                    RenderNavHostScene(
                                        Destinations.YouTubeMusicCreatorDetail.route
                                    ) {
                                        YouTubeMusicCreatorNavigationScreen(
                                            creator = creator,
                                            onBack = { navController.popBackStack() },
                                            onSongClick = ::playSongsAndOpenNowPlaying,
                                            onPlaylistClick = ::navigateToYouTubeMusicPlaylist,
                                            onCreatorClick = ::navigateToYouTubeMusicCreator,
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
                                    RenderNavHostScene(
                                        Destinations.YouTubeMusicPlaylistDetail.route
                                    ) {
                                        YouTubeMusicPlaylistDetailScreen(
                                            playlist = playlist,
                                            onBack = { navController.popBackStack() },
                                            onSongClick = ::playSongsAndOpenNowPlaying,
                                            offlineMode = offlineMode
                                        )
                                    }
                                }

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
                                    val playlistJson = backStackEntry.arguments?.getString("playlistJson")
                                    val playlist = navigationGson.fromJson(playlistJson, BiliPlaylist::class.java)
                                    val suppressBiliPlaylistVisibilityTransition =
                                        shouldUseInstantBiliUploaderPlaylistTransition(
                                            initialRoute = navController.previousBackStackEntry
                                                ?.destination
                                                ?.route,
                                            targetRoute = Destinations.BiliPlaylistDetail.route
                                        )
                                    RenderNavHostScene(
                                        Destinations.BiliPlaylistDetail.route
                                    ) {
                                        BiliPlaylistDetailScreen(
                                            playlist = playlist,
                                            suppressVisibilityTransition =
                                                suppressBiliPlaylistVisibilityTransition,
                                            onBack = { navController.popBackStack() },
                                            onPlayAudio = { videos, index ->
                                                playBiliAudioAndOpenNowPlayingWithSource(
                                                    videos = videos,
                                                    index = index,
                                                    sourceRoute = biliPlaylistSourceRoute(playlist)
                                                )
                                            },
                                            onPlayParts = { videoInfo, index, coverUrl ->
                                                playBiliPartsAndOpenNowPlayingWithSource(
                                                    videoInfo = videoInfo,
                                                    index = index,
                                                    coverUrl = coverUrl,
                                                    sourceRoute = biliPlaylistSourceRoute(playlist)
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
                                    RenderNavHostScene(
                                        Destinations.BiliUploaderDetail.route
                                    ) {
                                        BiliUploaderDetailScreen(
                                            uploader = uploader,
                                            onBack = { navController.popBackStack() },
                                            onPlayAudio = { videos, index ->
                                                playBiliAudioAndOpenNowPlayingWithSource(
                                                    videos = videos,
                                                    index = index,
                                                    sourceRoute = biliUploaderSourceRoute(uploader)
                                                )
                                            },
                                            onPlayParts = { videoInfo, index, coverUrl ->
                                                playBiliPartsAndOpenNowPlayingWithSource(
                                                    videoInfo = videoInfo,
                                                    index = index,
                                                    coverUrl = coverUrl,
                                                    sourceRoute = biliUploaderSourceRoute(uploader)
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
                                    RenderNavHostScene(
                                        Destinations.LocalPlaylistDetail.route
                                    ) {
                                        LocalPlaylistDetailScreen(
                                            playlistId = id,
                                            onBack = { navController.popBackStack() },
                                            onDeleted = { navController.popBackStack() },
                                            onSongClick = { songs, index ->
                                                playSongsAndOpenNowPlaying(
                                                    songs = songs,
                                                    index = index,
                                                    sourceRoute = localPlaylistSourceRoute(id)
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
                                    RenderNavHostScene(Destinations.Recent.route) {
                                        RecentScreen(
                                            onBack = { navController.popBackStack() },
                                            onSongClick = ::playSongsAndOpenNowPlaying,
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
                                    RenderNavHostScene(Destinations.PlaybackStats.route) {
                                        PlaybackStatsScreen(
                                            onBack = { navController.popBackStack() },
                                            onSongClick = ::playSongsAndOpenNowPlaying,
                                            offlineMode = offlineMode
                                        )
                                    }
                                }

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
                                    RenderNavHostScene(Destinations.DownloadManager.route) {
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
                                    RenderNavHostScene(Destinations.DownloadProgress.route) {
                                        DownloadProgressScreen(
                                            onBack = { navController.popBackStack() },
                                            listState = downloadProgressListState
                                        )
                                    }
                                }

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
                                    RenderNavHostScene(Destinations.DebugListenTogether.route) {
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
                                    RenderNavHostScene(Destinations.DebugUsbExclusive.route) {
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
                                    RenderNavHostScene(Destinations.DebugYouTube.route) {
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
                                    RenderNavHostScene(Destinations.DebugBili.route) {
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
                                    RenderNavHostScene(Destinations.DebugNetease.route) {
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
                                    RenderNavHostScene(Destinations.DebugSearch.route) {
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
                                    RenderNavHostScene(Destinations.DebugLogsList.route) {
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
                                    RenderNavHostScene(Destinations.DebugCrashLogsList.route) {
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
                                    val filePath = backStackEntry.arguments?.getString("filePath") ?: ""
                                    RenderNavHostScene(Destinations.DebugLogViewer.route) {
                                        LogViewerScreen(
                                            filePath = filePath,
                                            onBack = { navController.popBackStack() }
                                        )
                                    }
                                }
                                        }
                                    }
                                }
                                Box(modifier = Modifier.fillMaxSize()) {
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = currentSong != null && !showNowPlaying,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(
                                                bottom = bottomBarLayoutInsets.miniPlayerBottomPadding
                                            )
                                            .zIndex(MINI_PLAYER_OVERLAY_Z_INDEX),
                                        enter = slideInVertically(
                                            animationSpec = tween(
                                                durationMillis = 220,
                                                easing = FastOutSlowInEasing
                                            ),
                                            initialOffsetY = { it / 2 }
                                        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                                        exit = slideOutVertically(
                                            animationSpec = tween(
                                                durationMillis = 180,
                                                easing = FastOutSlowInEasing
                                            ),
                                            targetOffsetY = { it / 2 }
                                        ) + fadeOut(animationSpec = tween(durationMillis = 120))
                                    ) {
                                        NeriMiniPlayer(
                                            title = currentSong?.displayName()
                                                ?: composeResources.getString(
                                                    R.string.nowplaying_no_playback
                                                ),
                                            artist = currentSong?.displayArtist() ?: "",
                                            coverUrl = displayCoverUrl,
                                            visualCoverUrl = playbackVisualCoverUrl,
                                            coverIdentityKey = currentSongVisualKey,
                                            visualCoverIdentityKey = playbackVisualCoverState.ownerSongKey,
                                            hasCurrentSong = currentSong != null,
                                            isPlaying = isPlaybackControlPlaying,
                                            playPauseEnabled = !usbPlaybackPreparing,
                                            modifier = Modifier,
                                            onPlayPause = { PlayerManager.togglePlayPause() },
                                            onPrevious = { PlayerManager.previous() },
                                            onNext = { PlayerManager.next() },
                                            onExpand = { showNowPlaying = true },
                                            enableBlur = effectiveAdvancedBlurEnabled,
                                            offlineMode = offlineMode,
                                            isPlaybackWaiting = isPlaybackWaiting,
                                            isAudioRouteMuted = isAudioRouteMuted
                                        )
                                    }
                                }
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
                    DisposableEffect(Unit) {
                        latestOnNowPlayingVisibilityChanged(true)
                        onDispose {
                            latestOnNowPlayingVisibilityChanged(false)
                        }
                    }
                    val currentCoverUrl = playbackVisualCoverUrl
                    val effectiveSeedHex = if (dynamicColorEnabled) {
                        activeCoverSeedHex ?: themeSeedColor
                    } else {
                        themeSeedColor
                    }
                    val useSystemDynamic =
                        dynamicColorEnabled && activeCoverSeedHex == null && currentCoverUrl == null

                    NeriTheme(
                        followSystemDark = false,
                        forceDark = true,
                        dynamicColor = useSystemDynamic,
                        seedColorHex = effectiveSeedHex,
                        paletteStyle = themePaletteStyle,
                        colorSpec = themeColorSpec
                    ) {
                        BackHandler { showNowPlaying = false }

                        val nowPlayingQueue by PlayerManager.currentQueueFlow.collectAsStateWithLifecycle()
                        val nowPlayingCoverUrl = currentCoverUrl

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .blockUnderlyingTouches()
                        ) {
                            val coverBlurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            val blurStrength = nowPlayingCoverBlurAmount.coerceIn(0f, 500f)
                            var stableCoverUrl by remember { mutableStateOf<String?>(null) }
                            var stableBlurStrength by remember { mutableStateOf<Float?>(null) }
                            var coverBlurLoadFailed by remember { mutableStateOf(false) }
                            val hasCoverBlur =
                                coverBlurAvailable &&
                                    nowPlayingCoverBlurBackgroundEnabled &&
                                    (!nowPlayingCoverUrl.isNullOrBlank() ||
                                        !stableCoverUrl.isNullOrBlank())
                            val effectiveBlurStrength = remember(nowPlayingCoverUrl, blurStrength) {
                                resolvedNowPlayingBlurStrength(
                                    coverUrl = nowPlayingCoverUrl,
                                    configuredBlurAmount = blurStrength
                                )
                            }
                            val blurImageSizePx = remember(nowPlayingCoverUrl) {
                                resolvedNowPlayingBlurImageSizePx(nowPlayingCoverUrl)
                            }
                            val shouldPreloadCoverBlurNeighbors = remember(nowPlayingCoverUrl) {
                                isRemoteImageSource(nowPlayingCoverUrl)
                            }
                            val imageLoader = remember(context) { Coil.imageLoader(context) }
                            val coverBlurAssetVersion = remember(
                                coverAssetRefreshKey,
                                currentSongVisualKey,
                                nowPlayingCoverUrl
                            ) {
                                "$coverAssetRefreshKey:$currentSongVisualKey:$nowPlayingCoverUrl"
                            }
                            val coverBlurRequestKey = remember(
                                nowPlayingCoverUrl,
                                currentSongVisualKey,
                                effectiveBlurStrength,
                                coverBlurAssetVersion
                            ) {
                                if (nowPlayingCoverUrl.isNullOrBlank()) {
                                    null
                                } else {
                                    "nowplaying-blur:$currentSongVisualKey:$nowPlayingCoverUrl:" +
                                        "$effectiveBlurStrength:$coverBlurAssetVersion"
                                }
                            }
                            val latestCoverBlurRequestKey by rememberUpdatedState(coverBlurRequestKey)
                            val currentQueueIndex = remember(nowPlayingQueue, currentSong) {
                                val current = currentSong ?: return@remember -1
                                nowPlayingQueue.indexOfFirst { it.sameIdentityAs(current) }
                            }
                            val preloadCoverUrls = remember(
                                nowPlayingQueue,
                                currentQueueIndex,
                                shouldPreloadCoverBlurNeighbors
                            ) {
                                if (currentQueueIndex == -1 || !shouldPreloadCoverBlurNeighbors) {
                                    emptyList()
                                } else {
                                    listOfNotNull(
                                        nowPlayingQueue.getOrNull(currentQueueIndex - 1)
                                            .resolveUiCoverSource(context),
                                        nowPlayingQueue.getOrNull(currentQueueIndex + 1)
                                            .resolveUiCoverSource(context)
                                    ).distinct()
                                }
                            }

                            LaunchedEffect(
                                hasCoverBlur,
                                effectiveBlurStrength,
                                blurImageSizePx,
                                preloadCoverUrls,
                                coverBlurAssetVersion,
                                offlineMode
                            ) {
                                if (!hasCoverBlur || preloadCoverUrls.isEmpty()) return@LaunchedEffect
                                preloadCoverUrls.forEach { url ->
                                    imageLoader.enqueue(
                                        ImageRequest.Builder(context)
                                            .data(url)
                                            .allowHardware(false)
                                            .bitmapConfig(Bitmap.Config.RGB_565)
                                            .size(blurImageSizePx)
                                            .precision(Precision.INEXACT)
                                            .memoryCacheKey(
                                                "nowplaying-blur:$url:$effectiveBlurStrength:" +
                                                    coverBlurAssetVersion
                                            )
                                            .diskCacheKey(
                                                "nowplaying-blur:$url:$effectiveBlurStrength:" +
                                                    coverBlurAssetVersion
                                            )
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .networkCachePolicy(
                                                if (offlineMode && isRemoteImageSource(url)) {
                                                    CachePolicy.DISABLED
                                                } else {
                                                    CachePolicy.ENABLED
                                                }
                                            )
                                            .transformations(
                                                if (effectiveBlurStrength > 0f) {
                                                    listOf(BlurTransformation(context, effectiveBlurStrength))
                                                } else {
                                                    emptyList()
                                                }
                                            )
                                            .build()
                                    )
                                }
                            }

                            val latestBlurSongKey by rememberUpdatedState(currentSongVisualKey)
                            val latestBlurCoverUrl by rememberUpdatedState(nowPlayingCoverUrl)
                            LaunchedEffect(
                                coverBlurAvailable,
                                nowPlayingCoverBlurBackgroundEnabled,
                                currentSongVisualKey,
                                nowPlayingCoverUrl
                            ) {
                                if (!coverBlurAvailable || !nowPlayingCoverBlurBackgroundEnabled) {
                                    stableCoverUrl = null
                                    stableBlurStrength = null
                                    coverBlurLoadFailed = false
                                    return@LaunchedEffect
                                }
                                coverBlurLoadFailed = false
                                if (currentSongVisualKey != null ||
                                    !nowPlayingCoverUrl.isNullOrBlank()
                                ) {
                                    return@LaunchedEffect
                                }
                                delay(PLAYBACK_VISUAL_COVER_GRACE_MS)
                                if (shouldClearNowPlayingBlurCover(
                                        currentSongKey = latestBlurSongKey,
                                        requestedCoverUrl = latestBlurCoverUrl,
                                        clearDelayElapsed = true
                                    )
                                ) {
                                    stableCoverUrl = null
                                    stableBlurStrength = null
                                    coverBlurLoadFailed = false
                                }
                            }

                            val blurBackdropCoverUrl = stableCoverUrl ?: nowPlayingCoverUrl
                            val useCoverBlurBackground = hasCoverBlur && !coverBlurLoadFailed

                            if (!useCoverBlurBackground) {
                                // 背景固定按暗色逻辑渲染
                                NowPlayingAccentBackdrop(
                                    request = backdropAccentRequest(
                                        nowPlayingCoverUrl, currentSongVisualKey, true,
                                        coverAssetRefreshKey, offlineMode
                                    ),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            if (useCoverBlurBackground) {
                                // 先铺一层强调色背景, 避免首次加载和旋转重建时黑底闪烁
                                NowPlayingAccentBackdrop(
                                    request = backdropAccentRequest(
                                        blurBackdropCoverUrl, currentSongVisualKey, true,
                                        coverAssetRefreshKey, offlineMode
                                    ),
                                    modifier = Modifier.fillMaxSize()
                                )
                                val shouldShowStable =
                                    stableCoverUrl != null &&
                                        (
                                            stableCoverUrl != nowPlayingCoverUrl ||
                                                stableBlurStrength != effectiveBlurStrength
                                            )
                                if (shouldShowStable) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(stableCoverUrl)
                                            .allowHardware(false)
                                            .bitmapConfig(Bitmap.Config.RGB_565)
                                            .size(blurImageSizePx)
                                            .precision(Precision.INEXACT)
                                            .memoryCacheKey(
                                                "nowplaying-blur:$stableCoverUrl:$stableBlurStrength:" +
                                                    coverBlurAssetVersion
                                            )
                                            .diskCacheKey(
                                                "nowplaying-blur:$stableCoverUrl:$stableBlurStrength:" +
                                                    coverBlurAssetVersion
                                            )
                                            .networkCachePolicy(
                                                if (offlineMode && isRemoteImageSource(stableCoverUrl)) {
                                                    CachePolicy.DISABLED
                                                } else {
                                                    CachePolicy.ENABLED
                                                }
                                            )
                                            .transformations(
                                                if ((stableBlurStrength ?: 0f) > 0f) {
                                                    listOf(BlurTransformation(context, stableBlurStrength ?: 0f))
                                                } else {
                                                    emptyList()
                                                }
                                            )
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                if (!nowPlayingCoverUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(nowPlayingCoverUrl)
                                            .crossfade(NOW_PLAYING_BACKGROUND_CROSSFADE_MS)
                                            .allowHardware(false)
                                            .bitmapConfig(Bitmap.Config.RGB_565)
                                            .size(blurImageSizePx)
                                            .precision(Precision.INEXACT)
                                            .memoryCacheKey(
                                                "nowplaying-blur:$nowPlayingCoverUrl:$effectiveBlurStrength:" +
                                                    coverBlurAssetVersion
                                            )
                                            .diskCacheKey(
                                                "nowplaying-blur:$nowPlayingCoverUrl:$effectiveBlurStrength:" +
                                                    coverBlurAssetVersion
                                            )
                                            .networkCachePolicy(
                                                if (offlineMode && isRemoteImageSource(nowPlayingCoverUrl)) {
                                                    CachePolicy.DISABLED
                                                } else {
                                                    CachePolicy.ENABLED
                                                }
                                            )
                                            .transformations(
                                                if (effectiveBlurStrength > 0f) {
                                                    listOf(BlurTransformation(context, effectiveBlurStrength))
                                                } else {
                                                    emptyList()
                                                }
                                            )
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                        onSuccess = {
                                            if (latestCoverBlurRequestKey == coverBlurRequestKey) {
                                                stableCoverUrl = nowPlayingCoverUrl
                                                stableBlurStrength = effectiveBlurStrength
                                                coverBlurLoadFailed = false
                                            }
                                        },
                                        onError = {
                                            if (latestCoverBlurRequestKey == coverBlurRequestKey) {
                                                coverBlurLoadFailed = stableCoverUrl.isNullOrBlank()
                                            }
                                        }
                                    )
                                }
                                if (nowPlayingCoverBlurDarken > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = nowPlayingCoverBlurDarken.coerceIn(0f, 0.8f)))
                                    )
                                }
                            } else if (effectiveDynamicBackgroundEnabled) {
                                HyperBackground(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = 0.80f },
                                    isDark = true,
                                    coverUrl = nowPlayingCoverUrl,
                                    refreshKey = coverAssetRefreshKey,
                                    offlineMode = offlineMode,
                                    coverIdentityKey = currentSongVisualKey
                                )
                            }

                            CompositionLocalProvider(LocalMiniPlayerHeight provides 0.dp) {
                                val currentSourceRoute = currentPlaybackSourceRoute
                                NowPlayingScreen(
                                    onNavigateUp = { showNowPlaying = false },
                                    onOpenCurrentPlaybackSource = currentSourceRoute?.let { route ->
                                        {
                                            navigateToPlaybackSourceRoute(route)
                                        }
                                    },
                                    showLyricsScreen = showNowPlayingLyrics,
                                    onShowLyricsScreenChange = { showNowPlayingLyrics = it },
                                    onEnterAlbum = { album ->
                                        val shouldRestoreLyrics = showNowPlayingLyrics
                                        navigateToNeteaseAlbum(album) {
                                            if (shouldRestoreLyrics) {
                                                restoreLyricsAfterAlbumBack = true
                                            }
                                        }
                                    },
                                    onEnterArtist = ::navigateToNeteaseArtist,
                                    onEnterBiliUploader = ::navigateToBiliUploader,
                                    onEnterYouTubeCreator = ::navigateToYouTubeMusicCreator,
                                    lyricBlurEnabled = lyricBlurEnabled,
                                    lyricBlurAmount = lyricBlurAmount,
                                    lyricFontScales = lyricFontScales,
                                    onLyricFontScaleChange = { target, scale ->
                                        scope.launch { repo.setLyricFontScale(target, scale) }
                                    },
                                    advancedLyricsEnabled = advancedLyricsEnabled,
                                    showCoverSourceBadge = showCoverSourceBadge,
                                    showLyricTranslation = showLyricTranslation,
                                    offlineMode = offlineMode,
                                    resolvedCoverUrl = displayCoverUrl,
                                    visualCoverUrl = playbackVisualCoverUrl,
                                    playbackSongKey = currentSongVisualKey,
                                    playbackSongKeyAliases = currentSongVisualKeyAliases,
                                    visualCoverSongKey = playbackVisualCoverState.ownerSongKey
                                )
                            }
                        }
                    }
                }

                val revealOrigin = themeRevealOriginWindow
                val revealFallbackColor = themeRevealFallbackColorArgb?.let(::Color)
                if (revealOrigin != null && revealFallbackColor != null) {
                    val revealCaptureToken = themeRevealCaptureToken
                    ThemeRevealOverlay(
                        snapshot = themeRevealSnapshot,
                        fallbackColor = revealFallbackColor,
                        originInWindow = revealOrigin,
                        modifier = Modifier.fillMaxSize(),
                        startRadiusPx = themeRevealStartRadiusPx,
                        legacySnapshotDim = true,
                        durationMillis = THEME_REVEAL_DURATION_MILLIS,
                        onFinished = { finishThemeReveal(revealCaptureToken) }
                    )
                }

                pendingTrafficRiskDownloadRequest?.let { request ->
                    TrafficRiskDownloadDialog(
                        request = request,
                        onConfirm = {
                            pendingTrafficRiskDownloadRequest = null
                            GlobalDownloadManager.confirmTrafficRiskDownload(context, request)
                        },
                        onDismiss = {
                            pendingTrafficRiskDownloadRequest = null
                        }
                    )
                }

                if (showUsbExclusiveBackgroundPermissionDialog) {
                    UsbExclusiveBackgroundPermissionDialog(
                        batteryOptimizationAllowed = context
                            .readBackgroundBehaviorAllowance()
                            .ignoringBatteryOptimizations,
                        onRequestBatteryOptimization = {
                            showUsbExclusiveBackgroundPermissionDialog = false
                            context.requestIgnoreBatteryOptimizationsCompat()
                        },
                        onOpenAppSettings = {
                            showUsbExclusiveBackgroundPermissionDialog = false
                            context.openAppBackgroundSettings()
                        },
                        onNeverShowAgain = {
                            showUsbExclusiveBackgroundPermissionDialog = false
                            scope.launch {
                                repo.setUsbExclusiveBackgroundPermissionPromptSuppressed(true)
                            }
                        },
                        onDismiss = {
                            showUsbExclusiveBackgroundPermissionDialog = false
                        }
                    )
                }

            }
        }
    }
}
}
