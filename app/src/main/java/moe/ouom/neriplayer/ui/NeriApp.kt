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

import moe.ouom.neriplayer.data.identity.stableKey
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.Coil
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.audio.reactive.AudioReactive
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lifecycle.recoverUsbExclusivePlaybackOnForeground
import moe.ouom.neriplayer.core.player.lifecycle.updateUsbExclusiveForegroundState
import moe.ouom.neriplayer.core.player.policy.usb.shouldPromptForUsbExclusiveBackgroundPermission
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.core.startup.player.PlayerStartupBootstrapper
import moe.ouom.neriplayer.core.startup.player.PlayerStartupAudioFocusRefresher
import moe.ouom.neriplayer.core.startup.player.PlayerStartupHistoryRecorder
import moe.ouom.neriplayer.core.startup.player.PlayerStartupServicePlanner
import moe.ouom.neriplayer.core.startup.player.PlayerStartupServiceSyncCoordinator
import moe.ouom.neriplayer.core.startup.theme.StartupThemeResolver
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.identity.playbackVisualKeyAliases
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.settings.appearance.DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP
import moe.ouom.neriplayer.data.settings.appearance.AdvancedBlurQualityPreference
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.appearance.ThemeDefaults
import moe.ouom.neriplayer.data.model.settings.appearance.ThemeMode
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.appearance.isCurrentBuildDimensity
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotCached
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.data.model.navigation.LauncherShortcutAction
import moe.ouom.neriplayer.data.model.navigation.LauncherShortcutRequest
import moe.ouom.neriplayer.navigation.launcherShortcutMainTabRoute
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassNavigationHandoff
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.isAdvancedGlassBackendSupported
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingScreen
import moe.ouom.neriplayer.ui.screen.debug.DebugHomeScreen
import moe.ouom.neriplayer.ui.screen.host.ExploreHostScreen
import moe.ouom.neriplayer.ui.screen.host.HomeHostScreen
import moe.ouom.neriplayer.ui.screen.host.LibraryHostScreen
import moe.ouom.neriplayer.ui.screen.host.rememberHomeHostRuntimeState
import moe.ouom.neriplayer.ui.screen.tab.home.shouldShowHomeContinueSection
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.theme.rememberActualSystemDarkTheme
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.ui.debug.appDebugCrashActionOwner
import moe.ouom.neriplayer.ui.dialog.AppTrafficRiskDialogHost
import moe.ouom.neriplayer.ui.dialog.AppUsbBackgroundPermissionDialogHost
import moe.ouom.neriplayer.util.platform.openAppBackgroundSettings
import moe.ouom.neriplayer.util.platform.readBackgroundBehaviorAllowance
import moe.ouom.neriplayer.util.platform.requestIgnoreBatteryOptimizationsCompat
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.util.media.isRemoteImageSource
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.network.rememberOfflineModeState
import moe.ouom.neriplayer.ui.haptic.syncHapticFeedbackSetting
import moe.ouom.neriplayer.ui.navigation.AppBottomBarPresentation
import moe.ouom.neriplayer.ui.navigation.playbackSourceNavigationAction
import moe.ouom.neriplayer.ui.navigation.shouldSuppressPlaybackNavigation
import moe.ouom.neriplayer.ui.navigation.AppMiniPlayerPresentation
import moe.ouom.neriplayer.ui.navigation.AppNavigationGraph
import moe.ouom.neriplayer.ui.navigation.AppNavigationGraphOwner
import moe.ouom.neriplayer.ui.navigation.AppNavigationGraphPresentation
import moe.ouom.neriplayer.ui.navigation.AppNavigationMediaActions
import moe.ouom.neriplayer.ui.navigation.AppNavigationScaffold
import moe.ouom.neriplayer.ui.navigation.AppNavigationSceneRenderer
import moe.ouom.neriplayer.ui.navigation.AppStartupDestinationEffect
import moe.ouom.neriplayer.ui.navigation.MainTabGlassOwner
import moe.ouom.neriplayer.ui.navigation.MainTabLayerHost
import moe.ouom.neriplayer.ui.navigation.biliPlaylistSourceRoute
import moe.ouom.neriplayer.ui.navigation.biliUploaderSourceRoute
import moe.ouom.neriplayer.ui.navigation.localPlaylistSourceRoute
import moe.ouom.neriplayer.ui.navigation.mainTabDetailContentOffsetEasing
import moe.ouom.neriplayer.ui.navigation.navigationGson
import moe.ouom.neriplayer.ui.navigation.neteaseAlbumSourceRoute
import moe.ouom.neriplayer.ui.navigation.neteasePlaylistSourceRoute
import moe.ouom.neriplayer.ui.navigation.rememberMainTabLayerTransitionState
import moe.ouom.neriplayer.ui.navigation.resolveMainStartDestination
import moe.ouom.neriplayer.ui.navigation.resolveMainTabBackgroundMotionDurationMillis
import moe.ouom.neriplayer.ui.navigation.resolveMainTabNavigationMotionState
import moe.ouom.neriplayer.ui.navigation.resolveMainTabNavigationMotionTarget
import moe.ouom.neriplayer.ui.navigation.selectMainTabRouteContent
import moe.ouom.neriplayer.ui.navigation.shouldAcceptObservedMainTabRoute
import moe.ouom.neriplayer.ui.navigation.shouldDispatchMainTabNavigation
import moe.ouom.neriplayer.ui.navigation.shouldUseAdvancedGlassNavigationHandoff
import moe.ouom.neriplayer.ui.playback.visual.AppNowPlayingOverlay
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayBackground
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.playback.visual.PLAYBACK_COVER_SEED_GRACE_MS
import moe.ouom.neriplayer.ui.playback.visual.PlaybackCoverSeed
import moe.ouom.neriplayer.ui.playback.visual.playbackVisualCoverRequest
import moe.ouom.neriplayer.ui.playback.visual.rememberPlaybackVisualCoverState
import moe.ouom.neriplayer.ui.playback.visual.resolveActiveCoverSeedHex
import moe.ouom.neriplayer.ui.playback.visual.resolveCoverSeedWarmupDelayMillis
import moe.ouom.neriplayer.ui.settings.route.AppSettingsHostEnvironment
import moe.ouom.neriplayer.ui.settings.route.isAppSettingsVisible
import moe.ouom.neriplayer.ui.settings.route.AppSettingsRoute
import moe.ouom.neriplayer.ui.theme.background.CustomBackground
import moe.ouom.neriplayer.ui.theme.reveal.AppThemeRevealOverlayHost
import moe.ouom.neriplayer.ui.theme.reveal.THEME_REVEAL_WATCHDOG_DELAY_MILLIS
import moe.ouom.neriplayer.ui.theme.reveal.appThemeRevealPresentation
import moe.ouom.neriplayer.ui.theme.reveal.awaitStableDraw
import moe.ouom.neriplayer.ui.theme.reveal.captureThemeRevealSnapshot
import moe.ouom.neriplayer.ui.theme.reveal.resolveThemeToggleTarget
import moe.ouom.neriplayer.ui.theme.reveal.shouldBlockThemeModeChange
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

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
    onNowPlayingOpenChanged: (Boolean) -> Unit = {},
    onPhoneLandscapeBack: (() -> Unit)? = null,
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
                onNowPlayingOpenChanged = onNowPlayingOpenChanged,
                onPhoneLandscapeBack = onPhoneLandscapeBack,
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
    onNowPlayingOpenChanged: (Boolean) -> Unit = {},
    onPhoneLandscapeBack: (() -> Unit)? = null,
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
    var nowPlayingOverlayMounted by remember { mutableStateOf(showNowPlaying) }
    val latestOnNowPlayingOpenChanged by rememberUpdatedState(onNowPlayingOpenChanged)
    LaunchedEffect(showNowPlaying) {
        // 方向跟随页面状态，不能让旋转重建时的旧覆盖层销毁关闭横屏
        latestOnNowPlayingOpenChanged(showNowPlaying)
    }
    var showNowPlayingLyrics by rememberSaveable { mutableStateOf(false) }
    var currentPlaybackSourceRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreLyricsAfterAlbumBack by rememberSaveable { mutableStateOf(false) }
    var lyricsAlbumRouteObserved by rememberSaveable { mutableStateOf(false) }
    val devModeEnabled by repo.devModeEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val alwaysRecordLogsEnabled by repo.alwaysRecordLogsEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val themeSeedColor by repo.themeSeedColorFlow.collectAsStateWithLifecycle(initialValue = ThemeDefaults.DEFAULT_SEED_COLOR_HEX)
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
        initialValue = repo.defaultLyricFontScales
    )
    val backgroundImageUri by repo.backgroundImageUriFlow.collectAsStateWithLifecycle(initialValue = null)
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
    var coverArtRefreshToken by rememberSaveable { mutableIntStateOf(0) }
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
    // 旋转重建时直接复用当前封面颜色，避免首帧回到默认主题
    var coverSeed by remember {
        mutableStateOf(playbackVisualCoverUrl?.let { url ->
            CoverArtColorCache.peek(url)?.let { sample ->
                PlaybackCoverSeed(url, sample.seedHex, currentSongVisualKey)
            }
        })
    }
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

    val currentBootstrapServiceStart by rememberUpdatedState {
        PlayerStartupServicePlanner.plan(
            hasItems = PlayerManager.hasItems(),
            shouldBootstrapPlaybackService = PlayerManager.shouldBootstrapPlaybackServiceOnAppLaunch(),
            preemptAudioFocus = preemptAudioFocus,
            allowMixedPlayback = allowMixedPlayback
        )
    }
    val serviceSyncCoordinator = remember(application) {
        PlayerStartupServiceSyncCoordinator(
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
            currentBootstrapServiceStart = { currentBootstrapServiceStart() },
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
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(serviceSyncCoordinator, lifecycleOwner, windowInfo) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { windowInfo.isWindowFocused }.collectLatest { windowFocused ->
                if (windowFocused) {
                    serviceSyncCoordinator.pendingServiceStartFlow.collect { pendingStart ->
                        if (pendingStart != null) {
                            serviceSyncCoordinator.retryPendingServiceStart()
                        }
                    }
                }
            }
        }
    }
    val scheduleAudioServiceStart: (String, Boolean) -> Unit = { source, forceForeground ->
        scope.launch {
            serviceSyncCoordinator.requestServiceStart(
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
        if (!dynamicColorEnabled) {
            coverSeed = null
            return@LaunchedEffect
        }
        if (playbackVisualCoverUrl.isNullOrBlank()) {
            delay(PLAYBACK_COVER_SEED_GRACE_MS.milliseconds)
            if (
                latestPlaybackVisualCoverUrl.isNullOrBlank() &&
                latestPlaybackSongKey == currentSongVisualKey
            ) {
                coverSeed = null
            }
            return@LaunchedEffect
        }
        val cachedSample = CoverArtColorCache.peek(playbackVisualCoverUrl)
        currentCoroutineContext().ensureActive()
        if (
            cachedSample != null &&
            latestPlaybackVisualCoverUrl == playbackVisualCoverUrl &&
            latestPlaybackSongKey == currentSongVisualKey
        ) {
            coverSeed = PlaybackCoverSeed(
                coverUrl = playbackVisualCoverUrl,
                seedHex = cachedSample.seedHex,
                songKey = currentSongVisualKey
            )
        }

        if (showNowPlaying && isRemoteImageSource(playbackVisualCoverUrl)) {
            coverArtImageLoader.enqueue(
                offlineCachedImageRequest(
                    context = context,
                    data = playbackVisualCoverUrl,
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
            delay(warmupDelayMillis.milliseconds)
        }

        CoverArtColorCache.preload(context, playbackVisualCoverUrl, offlineMode)?.let { sample ->
            currentCoroutineContext().ensureActive()
            if (
                latestPlaybackVisualCoverUrl == playbackVisualCoverUrl &&
                latestPlaybackSongKey == currentSongVisualKey
            ) {
                coverSeed = PlaybackCoverSeed(
                    coverUrl = playbackVisualCoverUrl,
                    seedHex = sample.seedHex,
                    songKey = currentSongVisualKey
                )
            }
        }
    }

    // 同步触感反馈设置
    LaunchedEffect(hapticFeedbackEnabled) {
        syncHapticFeedbackSetting(hapticFeedbackEnabled)
    }


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
        videoInfo: VideoBasicInfo,
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
        videoInfo: VideoBasicInfo,
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
                buildSet {
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
                if (
                    shouldAcceptObservedMainTabRoute(
                        observedRoute = currentRoute,
                        pendingRoute = pendingMainTabRoute
                    )
                ) {
                    selectedMainTabRoute = checkNotNull(currentRoute)
                    if (pendingMainTabRoute == currentRoute) {
                        pendingMainTabRoute = null
                    }
                }
            }
            var visibleMainTabGlassOwners by remember(navHostStartDestination) {
                mutableStateOf(
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
                                CoreCommonR.string.launcher_shortcut_no_resumable_queue
                            )
                        }
                    }
                    LauncherShortcutAction.ShuffleFavorites -> {
                        val playlistsReady = withTimeoutOrNull(
                            LAUNCHER_SHORTCUT_PLAYLIST_READY_TIMEOUT_MS.milliseconds
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
                                CoreCommonR.string.launcher_shortcut_favorites_empty
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
            val homeHostRuntimeState = rememberHomeHostRuntimeState()

            val navigationSceneRenderer = AppNavigationSceneRenderer(
                advancedGlassController = advancedGlassController,
                backgroundImageUri = backgroundImageUri,
                backgroundImageBlur = backgroundImageBlur,
                effectiveBackgroundImageAlpha = effectiveBackgroundImageAlpha,
                coherentFeedbackEnabled = coherentFeedbackEnabled,
                currentRoute = currentRoute,
                visibleNavigationRoutes = visibleNavigationRoutes,
                mainTabNavigationMotion = mainTabNavigationMotion
            )

            @Composable
            fun RenderMainTabRoute(route: String) {
                val homeContent: @Composable () -> Unit = {
                    HomeHostScreen(
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
                            navigationSceneRenderer.RenderMainTabNavigationScene(
                                revealTop,
                                translationY,
                                scale,
                                sceneDepth = sceneDepth,
                                content = sceneContent
                            )
                        }
                    )
                }
                val exploreContent: @Composable () -> Unit = {
                    ExploreHostScreen(
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
                            navigationSceneRenderer.RenderMainTabNavigationScene(
                                revealTop,
                                translationY,
                                scale,
                                sceneDepth = sceneDepth,
                                content = sceneContent
                            )
                        }
                    )
                }
                val libraryContent: @Composable () -> Unit = {
                    LibraryHostScreen(
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
                            navigationSceneRenderer.RenderMainTabNavigationScene(
                                revealTop,
                                translationY,
                                scale,
                                sceneDepth = sceneDepth,
                                content = sceneContent
                            )
                        }
                    )
                }
                val settingsContent: @Composable () -> Unit = {
                    AppSettingsRoute(
                        repo = repo,
                        application = AppContainer.applicationContext,
                        initialThemeSnapshot = initialThemeSnapshot,
                        startupPlaybackPreferences = startupPlaybackPreferences,
                        environment = AppSettingsHostEnvironment(
                            isDarkTheme = isDark,
                            themeMode = themeMode,
                            onThemeToggleRequest = ::requestThemeToggle,
                            onThemeModeRequest = ::requestThemeModeChange,
                            backgroundImageAlpha = effectiveBackgroundImageAlpha,
                            defaultStartDestination = currentDefaultStartDestination,
                            homeHasRecentUsage = homeUsageSnapshot.entries.isNotEmpty(),
                            onBeforeLanguageRestart = clearThemeRevealState,
                            onLanguageChanged = onLanguageChanged,
                            coherentFeedbackEnabled = coherentFeedbackEnabled,
                            settingsVisible = isAppSettingsVisible(selectedMainTabRoute, showNowPlaying)
                        ),
                        onBackgroundImageAlphaPreview = { pendingBackgroundImageAlpha = it },
                        snackbarHostState = snackbarHostState,
                        renderScene = { revealTop, translationY, scale, sceneDepth, sceneContent ->
                            navigationSceneRenderer.RenderMainTabNavigationScene(
                                revealTop, translationY, scale,
                                sceneDepth = sceneDepth,
                                content = sceneContent
                            )
                        }
                    )
                }
                val debugContent: @Composable () -> Unit = {
                        val debugHomeScrollState = rememberScrollState()
                        val crashActionOwner = remember(context, composeResources) {
                            appDebugCrashActionOwner(context) {
                                composeResources.getString(CoreCommonR.string.test_exception_message)
                            }
                        }
                        navigationSceneRenderer.RenderMainTabNavigationScene(
                            revealTopFraction = 0f,
                            contentTranslationYFraction = 0f,
                            contentScale = 1f,
                            sceneDepth = 0
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
                            onTestExceptionHandler = crashActionOwner::dispatch,
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
                selectMainTabRouteContent(
                    route = route,
                    home = homeContent,
                    explore = exploreContent,
                    library = libraryContent,
                    settings = settingsContent,
                    debug = debugContent
                )?.invoke()
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

            val onOpenCurrentPlaybackSource = playbackSourceNavigationAction(
                currentPlaybackSourceRoute,
                ::navigateToPlaybackSourceRoute
            )

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

                    AppStartupDestinationEffect(
                        navController = navController,
                        currentRoute = currentRoute,
                        showHomeTab = showHomeTab,
                        effectiveStartDestination = effectiveStartDestination,
                        defaultStartDestination = defaultStartDestination,
                        navHostStartDestination = navHostStartDestination
                    )

                    AppNavigationScaffold(
                        bottomBar = AppBottomBarPresentation(
                            items = bottomBarItems,
                            currentDestination = backEntry?.destination,
                            showNowPlaying = shouldSuppressPlaybackNavigation(
                                showNowPlaying, nowPlayingOverlayMounted,
                                LocalConfiguration.current.smallestScreenWidthDp
                            ),
                            offlineMode = offlineMode,
                            alwaysUseNewTabStyle = alwaysUseNewTabStyle,
                            backgroundImageUri = backgroundImageUri
                        ),
                        miniPlayer = AppMiniPlayerPresentation(
                            song = currentSong,
                            coverUrl = displayCoverUrl,
                            visualCoverUrl = playbackVisualCoverUrl,
                            songVisualKey = currentSongVisualKey,
                            visualCoverSongKey = playbackVisualCoverState.ownerSongKey,
                            enableBlur = effectiveAdvancedBlurEnabled
                        ),
                        baseBlurRequested = advancedGlassController.isBaseBlurRequested,
                        snackbarHostState = snackbarHostState,
                        onMainTabSelected = ::navigateToMainTab,
                        onExpandNowPlaying = { showNowPlaying = true },
                        onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource
                    ) { _ ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
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
                                AppNavigationGraph(
                                    owner = AppNavigationGraphOwner(
                                        navController = navController,
                                        presentation = AppNavigationGraphPresentation(
                                            navHostStartDestination,
                                            coherentFeedbackEnabled,
                                            offlineMode
                                        ) { route, content ->
                                            navigationSceneRenderer.run {
                                                RenderNavHostScene(route, content)
                                            }
                                        },
                                        mediaActions = AppNavigationMediaActions(
                                            playSongs = { songs, index, sourceRoute ->
                                                playSongsAndOpenNowPlaying(
                                                    songs,
                                                    index,
                                                    sourceRoute
                                                )
                                            },
                                            playBiliAudio = ::playBiliAudioAndOpenNowPlayingWithSource,
                                            playBiliParts = ::playBiliPartsAndOpenNowPlayingWithSource,
                                            onNeteaseAlbumClick = { navigateToNeteaseAlbum(it) },
                                            onYouTubePlaylistClick = ::navigateToYouTubeMusicPlaylist,
                                            onYouTubeCreatorClick = ::navigateToYouTubeMusicCreator
                                        )
                                    )
                                )
                            }
                        }
                    }

                    AppNowPlayingOverlay(
                        visible = showNowPlaying,
                        cover = NowPlayingOverlayCover(
                            url = playbackVisualCoverUrl,
                            songKey = currentSongVisualKey,
                            song = currentSong,
                            assetRefreshKey = coverAssetRefreshKey
                        ),
                        queueFlow = PlayerManager.currentQueueFlow,
                        theme = NowPlayingOverlayTheme(
                            dynamicColorEnabled = dynamicColorEnabled,
                            activeCoverSeedHex = activeCoverSeedHex,
                            seedColorHex = themeSeedColor,
                            paletteStyle = themePaletteStyle,
                            colorSpec = themeColorSpec
                        ),
                        background = NowPlayingOverlayBackground(
                            blurEnabled = nowPlayingCoverBlurBackgroundEnabled,
                            blurAmount = nowPlayingCoverBlurAmount,
                            blurDarken = nowPlayingCoverBlurDarken,
                            dynamicEnabled = effectiveDynamicBackgroundEnabled,
                            offlineMode = offlineMode
                        ),
                        onVisibilityChanged = { mounted ->
                            nowPlayingOverlayMounted = mounted
                            latestOnNowPlayingVisibilityChanged(mounted)
                        },
                        onClose = { showNowPlaying = false }
                    ) {
                        NowPlayingScreen(
                            onNavigateUp = { showNowPlaying = false },
                            onPhoneLandscapeBack = onPhoneLandscapeBack,
                            onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource,
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

                    AppThemeRevealOverlayHost(
                        presentation = appThemeRevealPresentation(
                            themeRevealOriginWindow,
                            themeRevealFallbackColorArgb,
                            themeRevealCaptureToken
                        ),
                        snapshot = themeRevealSnapshot,
                        startRadiusPx = themeRevealStartRadiusPx,
                        onFinished = finishThemeReveal
                    )

                    AppTrafficRiskDialogHost(
                        request = pendingTrafficRiskDownloadRequest,
                        onConfirm = { request ->
                            pendingTrafficRiskDownloadRequest = null
                            GlobalDownloadManager.confirmTrafficRiskDownload(context, request)
                        },
                        onDismiss = { pendingTrafficRiskDownloadRequest = null }
                    )

                    AppUsbBackgroundPermissionDialogHost(
                        visible = showUsbExclusiveBackgroundPermissionDialog,
                        readBatteryOptimizationAllowed = {
                            context.readBackgroundBehaviorAllowance().ignoringBatteryOptimizations
                        },
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
                        onDismiss = { showUsbExclusiveBackgroundPermissionDialog = false }
                    )

            }
        }
    }
}
