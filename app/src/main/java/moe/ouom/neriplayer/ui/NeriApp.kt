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
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
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
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.AudioPlayerService
import moe.ouom.neriplayer.core.player.AudioReactive
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.recoverUsbExclusivePlaybackOnForeground
import moe.ouom.neriplayer.core.player.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.updateUsbExclusiveForegroundState
import moe.ouom.neriplayer.core.player.preloadRestoredStateSnapshot
import moe.ouom.neriplayer.core.player.shouldSkipLocalPlaybackSyncServiceStart
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.core.player.policy.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.shouldSyncPlaybackServiceForLocalPlaybackCommand
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotCached
import moe.ouom.neriplayer.data.storage.clearExtraStorageCaches
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.component.NeriBottomBar
import moe.ouom.neriplayer.ui.component.NeriMiniPlayer
import moe.ouom.neriplayer.ui.component.ThemeRevealOverlay
import moe.ouom.neriplayer.ui.component.blockUnderlyingTouches
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
import moe.ouom.neriplayer.ui.screen.artist.NeteaseArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.host.ExploreHostScreen
import moe.ouom.neriplayer.ui.screen.host.HomeHostScreen
import moe.ouom.neriplayer.ui.screen.host.LibraryHostScreen
import moe.ouom.neriplayer.ui.screen.host.SettingsHostScreen
import moe.ouom.neriplayer.ui.screen.playlist.BiliPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteaseAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.playlist.NeteasePlaylistDetailScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.theme.isActualSystemDarkTheme
import moe.ouom.neriplayer.ui.theme.rememberActualSystemDarkTheme
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.view.HyperBackground
import moe.ouom.neriplayer.ui.viewmodel.debug.LogViewerScreen
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.util.AnrWatchdog
import moe.ouom.neriplayer.util.CoverArtColorCache
import moe.ouom.neriplayer.util.ExceptionHandler
import moe.ouom.neriplayer.util.NativeCrashHandler
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.adjustedAccentColorArgb
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.openAppBackgroundSettings
import moe.ouom.neriplayer.util.readBackgroundBehaviorAllowance
import moe.ouom.neriplayer.util.requestIgnoreBatteryOptimizationsCompat
import moe.ouom.neriplayer.util.formatFileSize
import moe.ouom.neriplayer.util.isRemoteImageSource
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.util.rememberOfflineModeState
import moe.ouom.neriplayer.util.syncHapticFeedbackSetting
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

private val navigationGson: Gson by lazy(LazyThreadSafetyMode.PUBLICATION) { Gson() }

private fun resolveMainStartDestination(
    preferredRoute: String,
    showHomeTab: Boolean,
    devModeEnabled: Boolean
): String {
    return when (preferredRoute) {
        Destinations.Home.route -> if (showHomeTab) Destinations.Home.route else Destinations.Explore.route
        Destinations.Explore.route -> Destinations.Explore.route
        Destinations.Library.route -> Destinations.Library.route
        Destinations.Settings.route -> Destinations.Settings.route
        Destinations.Debug.route -> if (devModeEnabled) Destinations.Debug.route else if (showHomeTab) Destinations.Home.route else Destinations.Explore.route
        else -> if (showHomeTab) Destinations.Home.route else Destinations.Explore.route
    }
}

private fun SongItem?.resolveUiCoverSource(context: android.content.Context): String? {
    return this?.displayCoverUrl(context)
}

private const val NOW_PLAYING_REMOTE_BLUR_IMAGE_SIZE_PX = 640
private const val NOW_PLAYING_LOCAL_BLUR_IMAGE_SIZE_PX = 384

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun resolvedNowPlayingBlurImageSizePx(coverUrl: String?): Int {
    return if (isRemoteImageSource(coverUrl)) {
        NOW_PLAYING_REMOTE_BLUR_IMAGE_SIZE_PX
    } else {
        NOW_PLAYING_LOCAL_BLUR_IMAGE_SIZE_PX
    }
}

private fun resolvedNowPlayingBlurStrength(coverUrl: String?, configuredBlurAmount: Float): Float {
    return if (isRemoteImageSource(coverUrl)) {
        configuredBlurAmount
    } else {
        configuredBlurAmount.coerceAtMost(64f)
    }
}

@Composable
private fun TrafficRiskDownloadDialog(
    request: GlobalDownloadManager.TrafficRiskDownloadRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val networkLabel = stringResource(
        when (request.networkType) {
            TrafficNetworkType.ROAMING -> R.string.traffic_risk_network_roaming
            TrafficNetworkType.MOBILE -> R.string.traffic_risk_network_mobile
            TrafficNetworkType.WIFI -> R.string.traffic_risk_network_wifi
        }
    )
    val message = if (request.songCount <= 1) {
        stringResource(
            R.string.traffic_risk_download_single_message,
            networkLabel,
            request.songs.firstOrNull()?.displayName().orEmpty()
        )
    } else {
        stringResource(
            R.string.traffic_risk_download_batch_message,
            networkLabel,
            request.songCount
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.traffic_risk_download_title)) },
        text = { Text(message) },
        confirmButton = {
            HapticTextButton(onClick = onConfirm) {
                Text(stringResource(R.string.traffic_risk_download_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun MobileDataDownloadInterruptionDialog(
    request: GlobalDownloadManager.MobileDataDownloadInterruptionRequest,
    onContinue: () -> Unit,
    onWaitWifi: () -> Unit,
    onCancelAll: () -> Unit
) {
    val networkLabel = stringResource(
        when (request.networkType) {
            TrafficNetworkType.ROAMING -> R.string.traffic_risk_network_roaming
            TrafficNetworkType.MOBILE -> R.string.traffic_risk_network_mobile
            TrafficNetworkType.WIFI -> R.string.traffic_risk_network_wifi
        }
    )

    AlertDialog(
        onDismissRequest = onWaitWifi,
        title = { Text(stringResource(R.string.mobile_data_download_interruption_title)) },
        confirmButton = {},
        dismissButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.mobile_data_download_interruption_message,
                        networkLabel,
                        request.taskCount
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    HapticTextButton(onClick = onWaitWifi) {
                        Text(stringResource(R.string.mobile_data_download_wait_wifi))
                    }
                    HapticTextButton(onClick = onContinue) {
                        Text(stringResource(R.string.traffic_risk_download_confirm))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.End)
                ) {
                    HapticTextButton(onClick = onCancelAll) {
                        Text(
                            stringResource(R.string.mobile_data_download_cancel_all),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun UsbExclusiveBackgroundPermissionDialog(
    batteryOptimizationAllowed: Boolean,
    onRequestBatteryOptimization: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNeverShowAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_usb_exclusive_background_permission_title))
        },
        confirmButton = {},
        dismissButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_usb_exclusive_background_permission_desc))
                if (!batteryOptimizationAllowed) {
                    HapticTextButton(
                        onClick = onRequestBatteryOptimization,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_usb_exclusive_background_permission_battery))
                    }
                }
                HapticTextButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_usb_exclusive_background_permission_app_settings))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    HapticTextButton(onClick = onNeverShowAgain) {
                        Text(stringResource(R.string.settings_usb_exclusive_background_permission_never))
                    }
                    HapticTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_usb_exclusive_background_permission_later))
                    }
                }
            }
        }
    )
}

private const val THEME_REVEAL_SNAPSHOT_MAX_DIMENSION_PX = 1080
private const val THEME_REVEAL_STABLE_DRAW_PASSES = 1
private val THEME_REVEAL_SNAPSHOT_CONFIG = Bitmap.Config.RGB_565
private val ROOT_HAZE_BLUR_RADIUS = 24.dp

internal data class ThemeRevealSnapshotDimensions(
    val width: Int,
    val height: Int
)

internal fun resolveThemeRevealSnapshotDimensions(
    width: Int,
    height: Int,
    maxDimensionPx: Int = THEME_REVEAL_SNAPSHOT_MAX_DIMENSION_PX
): ThemeRevealSnapshotDimensions {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val maxDimension = maxOf(safeWidth, safeHeight)
    val downsampleRatio = (maxDimension.toFloat() / maxDimensionPx)
        .coerceAtLeast(1f)
    return ThemeRevealSnapshotDimensions(
        width = (safeWidth / downsampleRatio).roundToInt().coerceAtLeast(1),
        height = (safeHeight / downsampleRatio).roundToInt().coerceAtLeast(1)
    )
}

private fun View.drawScaledThemeRevealBitmap(): Bitmap? {
    if (width <= 0 || height <= 0) {
        return null
    }
    val snapshotDimensions = resolveThemeRevealSnapshotDimensions(
        width = width,
        height = height
    )
    return runCatching {
        createBitmap(
            snapshotDimensions.width,
            snapshotDimensions.height,
            THEME_REVEAL_SNAPSHOT_CONFIG
        ).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.scale(
                snapshotDimensions.width.toFloat() / width.toFloat(),
                snapshotDimensions.height.toFloat() / height.toFloat()
            )
            draw(canvas)
        }
    }.getOrNull()
}

private suspend fun captureThemeRevealSnapshot(
    activity: Activity?,
    fallbackView: View
): ImageBitmap? {
    val windowBitmap = activity?.let { currentActivity ->
        suspendCancellableCoroutine { continuation ->
            val decorView = currentActivity.window.decorView
            if (decorView.width <= 0 || decorView.height <= 0) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val snapshotDimensions = resolveThemeRevealSnapshotDimensions(
                width = decorView.width,
                height = decorView.height
            )
            val bitmap = createBitmap(
                snapshotDimensions.width,
                snapshotDimensions.height,
                THEME_REVEAL_SNAPSHOT_CONFIG
            )

            PixelCopy.request(
                currentActivity.window,
                bitmap,
                { result ->
                    continuation.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    return windowBitmap?.asImageBitmap() ?: captureThemeRevealFallbackSnapshot(fallbackView)
}

private suspend fun captureThemeRevealFallbackSnapshot(view: View): ImageBitmap? {
    return withContext(Dispatchers.Main.immediate) {
        runCatching {
            if (view.width > 0 && view.height > 0) {
                view.drawScaledThemeRevealBitmap()?.asImageBitmap()
            } else {
                null
            }
        }.getOrNull()
    }
}

private suspend fun awaitNextDraw(view: View) {
    if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
        return
    }

    withTimeoutOrNull(120L) {
        suspendCancellableCoroutine { continuation ->
            val observer = view.viewTreeObserver
            var handled = false
            val drawListener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (handled) return
                    handled = true
                    view.post {
                        if (observer.isAlive) {
                            observer.removeOnDrawListener(this)
                        }
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
            }

            observer.addOnDrawListener(drawListener)
            continuation.invokeOnCancellation {
                if (handled) {
                    return@invokeOnCancellation
                }
                handled = true
                view.post {
                    if (observer.isAlive) {
                        observer.removeOnDrawListener(drawListener)
                    }
                }
            }
            view.invalidate()
        }
    }
}

private suspend fun awaitStableDraw(view: View) {
    repeat(THEME_REVEAL_STABLE_DRAW_PASSES) {
        awaitNextDraw(view)
    }
}

private const val COVER_SEED_WARMUP_DELAY_MS = 180L

internal fun resolveCoverSeedWarmupDelayMillis(
    showNowPlaying: Boolean,
    dynamicColorEnabled: Boolean,
    hasCachedSample: Boolean
): Long {
    if (!dynamicColorEnabled || showNowPlaying || hasCachedSample) {
        return 0L
    }
    return COVER_SEED_WARMUP_DELAY_MS
}

/**
 * 根据封面提取播放界面强调色
 */
@Composable
private fun NowPlayingAccentBackdrop(
    coverUrl: String?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    offlineMode: Boolean = false,
    onAccentChanged: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val fallback = if (isDark) Color(0xFF121212) else Color(0xFFF5F5F5)
    var target by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(coverUrl, isDark, refreshKey, offlineMode) {
        if (coverUrl.isNullOrEmpty()) {
            target = null
            onAccentChanged(null)
            return@LaunchedEffect
        }
        val cached = CoverArtColorCache.peek(coverUrl)
        if (cached != null) {
            target = Color(adjustedAccentColorArgb(cached.baseColorArgb, isDark))
            onAccentChanged(cached.seedHex)
        }
        val sample = CoverArtColorCache.getOrLoad(context, coverUrl, offlineMode)
        if (sample != null) {
            target = Color(adjustedAccentColorArgb(sample.baseColorArgb, isDark))
            onAccentChanged(sample.seedHex)
        } else if (cached == null) {
            target = null
            onAccentChanged(null)
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
private fun OfflineModeBottomBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.offline_mode_bottom_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
fun NeriApp(
    initialThemeSnapshot: ThemePreferenceSnapshot = ThemePreferenceSnapshot(),
    onIsDarkChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var appContentReady by rememberSaveable { mutableStateOf(false) }
    val bootstrapIsDark = when {
        initialThemeSnapshot.forceDark -> true
        initialThemeSnapshot.followSystemDark -> isActualSystemDarkTheme(context)
        else -> false
    }

    LaunchedEffect(Unit) {
        // 先交一个极轻的背景首帧，下一帧再挂整棵导航和状态订阅树
        withFrameNanos { }
        appContentReady = true
    }

    if (!appContentReady) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (bootstrapIsDark) Color(0xFF121212) else Color.White)
        )
        return
    }

    NeriAppContent(
        initialThemeSnapshot = initialThemeSnapshot,
        onIsDarkChanged = onIsDarkChanged
    )
}

@Composable
private fun NeriAppContent(
    initialThemeSnapshot: ThemePreferenceSnapshot = ThemePreferenceSnapshot(),
    onIsDarkChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val offlineMode by rememberOfflineModeState()
    val rootView = LocalView.current
    val repo = remember { AppContainer.settingsRepo }
    val systemDark = rememberActualSystemDarkTheme()
    val application = remember(context) { context.applicationContext as Application }
    SideEffect {
        // 播放点击可能早于启动预加载完成，先绑定上下文避免懒初始化缺入口
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
    val floatingLyricsPreferences by repo.floatingLyricsPreferencesFlow.collectAsStateWithLifecycle(
        initialValue = FloatingLyricsPreferences()
    )
    val advancedLyricsEnabled by repo.advancedLyricsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val advancedBlurEnabled by repo.advancedBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val advancedBlurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val effectiveAdvancedBlurEnabled = advancedBlurAvailable && advancedBlurEnabled
    val nowPlayingAudioReactiveEnabled by repo.nowPlayingAudioReactiveEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingDynamicBackgroundEnabled by repo.nowPlayingDynamicBackgroundEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingCoverBlurBackgroundEnabled by repo.nowPlayingCoverBlurBackgroundEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val nowPlayingCoverBlurAmount by repo.nowPlayingCoverBlurAmountFlow.collectAsStateWithLifecycle(initialValue = 1.5f)
    val nowPlayingCoverBlurDarken by repo.nowPlayingCoverBlurDarkenFlow.collectAsStateWithLifecycle(initialValue = 0.2f)
    val lyricFontScale by repo.lyricFontScaleFlow.collectAsStateWithLifecycle(initialValue = 1.0f)
    val uiDensityScale by repo.uiDensityScaleFlow.collectAsStateWithLifecycle(initialValue = 1.0f)
    val bypassProxy by repo.bypassProxyFlow.collectAsStateWithLifecycle(initialValue = true)
    val backgroundImageUri by repo.backgroundImageUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val downloadDirectoryUri by repo.downloadDirectoryUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val downloadFileNameTemplate by repo.downloadFileNameTemplateFlow.collectAsStateWithLifecycle(initialValue = null)
    val backgroundImageBlur by repo.backgroundImageBlurFlow.collectAsStateWithLifecycle(initialValue = 0f)
    val backgroundImageAlpha by repo.backgroundImageAlphaFlow.collectAsStateWithLifecycle(initialValue = 0.3f)
    val hapticFeedbackEnabled by repo.hapticFeedbackEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val showCoverSourceBadge by repo.showCoverSourceBadgeFlow.collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingToolbarDockEnabled by repo.nowPlayingToolbarDockEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingKeepScreenOn by repo.nowPlayingKeepScreenOnFlow.collectAsStateWithLifecycle(initialValue = true)
    val showNowPlayingTitle by repo.nowPlayingShowTitleFlow.collectAsStateWithLifecycle(initialValue = true)
    val showNowPlayingProgressQualitySwitch by repo.nowPlayingProgressShowQualitySwitchFlow.collectAsStateWithLifecycle(initialValue = true)
    val showNowPlayingProgressAudioCodec by repo.nowPlayingProgressShowAudioCodecFlow.collectAsStateWithLifecycle(initialValue = true)
    val showNowPlayingProgressAudioSpec by repo.nowPlayingProgressShowAudioSpecFlow.collectAsStateWithLifecycle(initialValue = true)
    val showLyricTranslation by repo.showLyricTranslationFlow.collectAsStateWithLifecycle(initialValue = true)
    val defaultStartDestination by repo.defaultStartDestinationFlow.collectAsStateWithLifecycle(initialValue = Destinations.Home.route)
    val showHomeContinueCard by repo.homeCardContinueFlow.collectAsStateWithLifecycle(initialValue = true)
    val showHomeTrendingCard by repo.homeCardTrendingFlow.collectAsStateWithLifecycle(initialValue = true)
    val showHomeRadarCard by repo.homeCardRadarFlow.collectAsStateWithLifecycle(initialValue = true)
    val showHomeRecommendedCard by repo.homeCardRecommendedFlow.collectAsStateWithLifecycle(initialValue = true)
    val playbackFadeIn by repo.playbackFadeInFlow.collectAsStateWithLifecycle(initialValue = false)
    val playbackCrossfadeNext by repo.playbackCrossfadeNextFlow.collectAsStateWithLifecycle(initialValue = false)
    val playbackFadeInDurationMs by repo.playbackFadeInDurationMsFlow.collectAsStateWithLifecycle(initialValue = 500L)
    val playbackFadeOutDurationMs by repo.playbackFadeOutDurationMsFlow.collectAsStateWithLifecycle(initialValue = 500L)
    val playbackCrossfadeInDurationMs by repo.playbackCrossfadeInDurationMsFlow.collectAsStateWithLifecycle(initialValue = 500L)
    val playbackCrossfadeOutDurationMs by repo.playbackCrossfadeOutDurationMsFlow.collectAsStateWithLifecycle(initialValue = 500L)
    val keepLastPlaybackProgress by repo.keepLastPlaybackProgressFlow.collectAsStateWithLifecycle(initialValue = true)
    val keepPlaybackModeState by repo.keepPlaybackModeStateFlow.collectAsStateWithLifecycle(initialValue = true)
    val neteaseAutoSourceSwitch by repo.neteaseAutoSourceSwitchFlow.collectAsStateWithLifecycle(
        initialValue = startupPlaybackPreferences.neteaseAutoSourceSwitch
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
    val homeUsageEntries by AppContainer.playlistUsageRepo.frequentPlaylistsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingFollowSystemDark by remember { mutableStateOf<Boolean?>(null) }
    var pendingForceDark by remember { mutableStateOf<Boolean?>(null) }
    var themeRevealSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var themeRevealOriginWindow by remember { mutableStateOf<Offset?>(null) }
    var themeRevealStartRadiusPx by remember { mutableFloatStateOf(0f) }
    var themeRevealFallbackColorArgb by remember { mutableStateOf<Int?>(null) }
    var themeRevealCaptureInFlight by remember { mutableStateOf(false) }
    var themeRevealCaptureJob by remember { mutableStateOf<Job?>(null) }
    var themeRevealCaptureToken by remember { mutableIntStateOf(0) }
    var pendingBackgroundImageAlpha by remember { mutableStateOf<Float?>(null) }
    var coverArtRefreshToken by remember { mutableIntStateOf(0) }
    var showUsbExclusiveBackgroundPermissionDialog by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
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
        pendingFollowSystemDark = null
        pendingForceDark = null
        themeRevealSnapshot = null
        themeRevealOriginWindow = null
        themeRevealStartRadiusPx = 0f
        themeRevealFallbackColorArgb = null
    }
    val clearThemeRevealState = {
        themeRevealCaptureToken += 1
        themeRevealCaptureJob?.cancel()
        themeRevealCaptureJob = null
        themeRevealCaptureInFlight = false
        clearThemeRevealVisualState()
    }

    // 缓存当前封面的取色结果，避免开关动态取色时先闪到默认种子色
    var coverSeedHex by remember { mutableStateOf<String?>(null) }
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val displayCoverUrl = rememberSongDisplayCoverUrl(currentSong)
    val scope = rememberCoroutineScope()
    var pendingTrafficRiskDownloadRequest by remember {
        mutableStateOf<GlobalDownloadManager.TrafficRiskDownloadRequest?>(null)
    }
    LaunchedEffect(Unit) {
        GlobalDownloadManager.trafficRiskDownloadRequests.collect { request ->
            pendingTrafficRiskDownloadRequest = request
        }
    }

    val scheduleAudioServiceStart: (String, Boolean) -> Unit = { source, forceForeground ->
        scope.launch {
            // 让 currentSong/mini player 先过一帧，避免和服务启动抢主线程
            withFrameNanos { }
            // 本地切歌时先让播放器和首帧稳定下来，再补系统媒体会话同步
            if (source.startsWith("local_playback_command_")) {
                delay(450L)
            }
            if (
                shouldSkipLocalPlaybackSyncServiceStart(
                    source = source,
                    serviceReady = AudioPlayerService.isReadyForPassiveLocalPlaybackSync(),
                    hasItems = PlayerManager.hasItems(),
                    hasLocalCurrentSong = PlayerManager.currentSongFlow.value?.let {
                        LocalSongSupport.isLocalSong(it, context)
                    } == true,
                    usbExclusivePlaybackActive = PlayerManager
                        .isUsbExclusivePlaybackActiveForForegroundService()
                )
            ) {
                NPLogger.d(
                    "NERI-App",
                    "Skipping audio service sync because active playback service is already tracking " +
                        "source=$source serviceInstance=" +
                        AudioPlayerService.isInstanceActiveForDiagnostics() +
                        " serviceForeground=" +
                        AudioPlayerService.isForegroundActiveForDiagnostics()
                )
                return@launch
            }
            NPLogger.d("NERI-App", "Starting audio service: source=$source")
            AudioPlayerService.startSyncService(
                context,
                source,
                forceForeground = forceForeground
            )
        }
    }

    fun updateStartupAudioFocus(reason: String) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!PlayerManager.isPlayerInitialized()) return
        if (
            reason == "lifecycle_resume" &&
            PlayerManager.isUsbExclusiveNativePlaybackStable()
        ) {
            NPLogger.d(
                "NERI-App",
                "Skipping startup audio focus refresh during stable USB native playback"
            )
            return
        }
        val transportActive = runCatching { PlayerManager.isTransportActive() }
            .getOrDefault(false)
        val usbExclusiveNativeActive = PlayerManager.shouldUseUsbExclusiveFocusGuard()
        StartupAudioFocusController.updateForForeground(
            context = context,
            enabled = preemptAudioFocus || usbExclusiveNativeActive,
            allowMixedPlayback = allowMixedPlayback,
            usbExclusivePlayback = usbExclusivePlayback,
            usbExclusiveNativeActive = usbExclusiveNativeActive,
            transportActive = transportActive,
            reason = reason
        )
    }

    LaunchedEffect(application) {
        val exactStartupPlaybackPreferences = withContext(Dispatchers.IO) {
            readPlaybackPreferenceSnapshot(application)
        }
        val startupRestoreSnapshot = preloadRestoredStateSnapshot(
            app = application,
            keepLastPlaybackProgressEnabled =
                exactStartupPlaybackPreferences.keepLastPlaybackProgress,
            keepPlaybackModeStateEnabled =
                exactStartupPlaybackPreferences.keepPlaybackModeState
        )
        withFrameNanos { }
        PlayerManager.initializePreloaded(
            app = application,
            startupPlaybackPreferences = exactStartupPlaybackPreferences,
            restoredStateSnapshot = startupRestoreSnapshot
        )
        NPLogger.d("NERI-App", "PlayerManager.initialize called")
        NPLogger.d(
            "NERI-App",
            "Player bootstrap state hasItems=${PlayerManager.hasItems()} transportActive=${PlayerManager.isTransportActive()} isPlaying=${PlayerManager.isPlayingFlow.value}"
        )
        val shouldBootstrapPlaybackService =
            PlayerManager.hasItems() && PlayerManager.shouldBootstrapPlaybackServiceOnAppLaunch()
        val shouldBootstrapPreemptAudioFocusSession =
            !shouldBootstrapPlaybackService &&
                exactStartupPlaybackPreferences.preemptAudioFocus &&
                !exactStartupPlaybackPreferences.allowMixedPlayback &&
                PlayerManager.hasItems()
        StartupAudioFocusController.updateForForeground(
            context = context,
            enabled = exactStartupPlaybackPreferences.preemptAudioFocus ||
                PlayerManager.shouldUseUsbExclusiveFocusGuard(),
            allowMixedPlayback = exactStartupPlaybackPreferences.allowMixedPlayback,
            usbExclusivePlayback = exactStartupPlaybackPreferences.usbExclusivePlayback,
            usbExclusiveNativeActive = PlayerManager.shouldUseUsbExclusiveFocusGuard(),
            transportActive = PlayerManager.isTransportActive() || shouldBootstrapPlaybackService,
            reason = "app_bootstrap"
        )
        if (shouldBootstrapPlaybackService) {
            NPLogger.d("NERI-App", "Starting audio service from app bootstrap")
            scheduleAudioServiceStart("app_bootstrap", true)
        } else if (shouldBootstrapPreemptAudioFocusSession) {
            NPLogger.d("NERI-App", "Starting audio service for preempt audio focus session")
            scheduleAudioServiceStart("preempt_audio_focus_bootstrap", false)
        } else {
            NPLogger.d(
                "NERI-App",
                "Skip audio service bootstrap: hasItems=${PlayerManager.hasItems()} currentSong=${PlayerManager.currentSongFlow.value != null}"
            )
        }

        launch {
            PlayerManager.playbackCommandFlow.collect { command ->
                if (command.source != PlaybackCommandSource.LOCAL) {
                    return@collect
                }
                if (!shouldSyncPlaybackServiceForLocalPlaybackCommand(command.type)) {
                    return@collect
                }
                if (!PlayerManager.hasItems()) {
                    return@collect
                }
                // 恢复旧队列后点继续播放，也要像切新歌一样补一次系统媒体会话同步
                scheduleAudioServiceStart(
                    "local_playback_command_${command.type.lowercase()}",
                    PlayerManager.shouldRunPlaybackServiceInForeground()
                )
            }
        }

        launch {
            // 跳过初始值，订阅之后的变更，每次切曲写入最近播放
            var lastRecordedSongKey: String? = null
            PlayerManager.currentSongFlow
                .drop(1)
                .filterNotNull()
                .collect { song ->
                    val songKey = song.stableKey()
                    if (songKey == lastRecordedSongKey) {
                        return@collect
                    }
                    lastRecordedSongKey = songKey
                    // 首次切歌时先让播放稳定下来，避免历史记录和同步调度挤占首帧
                    delay(700L)
                    if (PlayerManager.currentSongFlow.value?.stableKey() != songKey) {
                        return@collect
                    }
                    AppContainer.playHistoryRepo.record(song)
                }
            }

    }

    LaunchedEffect(preemptAudioFocus, allowMixedPlayback, usbExclusivePlayback) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            updateStartupAudioFocus("settings_changed")
        } else if ((!preemptAudioFocus && !usbExclusivePlayback) || allowMixedPlayback) {
            StartupAudioFocusController.release("settings_changed_inactive")
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

    LaunchedEffect(displayCoverUrl, coverArtRefreshToken, showNowPlaying, dynamicColorEnabled, offlineMode) {
        if (displayCoverUrl.isNullOrBlank() || !dynamicColorEnabled) {
            coverSeedHex = null
            return@LaunchedEffect
        }
        val cachedSample = CoverArtColorCache.peek(displayCoverUrl)
        if (cachedSample != null) {
            coverSeedHex = cachedSample.seedHex
        }

        if (showNowPlaying && isRemoteImageSource(displayCoverUrl)) {
            coverArtImageLoader.enqueue(
                offlineCachedImageRequest(
                    context = context,
                    data = displayCoverUrl,
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

        CoverArtColorCache.preload(context, displayCoverUrl, offlineMode)?.let { sample ->
            coverSeedHex = sample.seedHex
        }
    }

    // 同步触感反馈设置
    LaunchedEffect(hapticFeedbackEnabled) {
        syncHapticFeedbackSetting(hapticFeedbackEnabled)
    }

    val defaultDensity = LocalDensity.current
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }

    val finalDensity = remember(defaultDensity, uiDensityScale) {
        Density(
            density = defaultDensity.density * uiDensityScale,
            fontScale = defaultDensity.fontScale
        )
    }

    val isDark = themeMode.resolveUseDark(systemDark)
    val hazeState = remember { HazeState() }
    val preferredQuality by repo.audioQualityFlow.collectAsStateWithLifecycle(initialValue = "exhigh")
    val youtubePreferredQuality by repo.youtubeAudioQualityFlow.collectAsStateWithLifecycle(initialValue = "very_high")
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
    val themeRevealActive =
        themeRevealOriginWindow != null &&
            themeRevealFallbackColorArgb != null

    LaunchedEffect(isDark, themeRevealActive, themeRevealCaptureInFlight) {
        if (!themeRevealActive && !themeRevealCaptureInFlight) {
            onIsDarkChanged(isDark)
        }
    }

    fun requestThemeModeChange(
        targetMode: ThemeMode,
        originInWindow: Offset,
        startRadiusPx: Float
    ) {
        if (
            themeRevealCaptureInFlight ||
            pendingFollowSystemDark != null ||
            pendingForceDark != null ||
            themeRevealOriginWindow != null
        ) {
            return
        }

        if (targetMode == themeMode) {
            return
        }

        val nextFollowSystemDark = targetMode.followSystemDark
        val nextForceDark = targetMode.forceDark
        val nextDark = targetMode.resolveUseDark(systemDark)
        if (nextDark == isDark) {
            pendingFollowSystemDark = nextFollowSystemDark
            pendingForceDark = nextForceDark
            scope.launch {
                repo.setThemeMode(
                    followSystemDark = nextFollowSystemDark,
                    forceDark = nextForceDark
                )
            }
            return
        }

        val activity = context as? Activity
        val captureView = activity?.window?.decorView?.rootView ?: rootView.rootView
        val captureToken = themeRevealCaptureToken + 1
        themeRevealCaptureToken = captureToken
        themeRevealCaptureInFlight = true

        val captureJob = scope.launch {
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
                if (themeRevealCaptureToken == captureToken) {
                    themeRevealCaptureJob = null
                    themeRevealCaptureInFlight = false
                }
                return@launch
            }

            clearThemeRevealVisualState()
            themeRevealSnapshot = snapshot
            themeRevealFallbackColorArgb = currentThemeBackgroundArgb
            themeRevealOriginWindow = originInWindow
            themeRevealStartRadiusPx = startRadiusPx.coerceAtLeast(1f)
            try {
                pendingFollowSystemDark = nextFollowSystemDark
                pendingForceDark = nextForceDark
                repo.setThemeMode(
                    followSystemDark = nextFollowSystemDark,
                    forceDark = nextForceDark
                )
            } finally {
                if (themeRevealCaptureToken == captureToken) {
                    themeRevealCaptureJob = null
                    themeRevealCaptureInFlight = false
                }
            }
        }
        themeRevealCaptureJob = captureJob
    }

    fun requestThemeToggle(originInWindow: Offset, startRadiusPx: Float) {
        val targetMode = if (isDark) ThemeMode.LIGHT else ThemeMode.DARK
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
                        PlayerManager.recoverUsbExclusivePlaybackOnForeground("lifecycle_resume")
                    }
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
                        StartupAudioFocusController.release("lifecycle_${event.name.lowercase()}")
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    clearThemeRevealState()
                    StartupAudioFocusController.release("lifecycle_${event.name.lowercase()}")
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

    fun playSongsAndOpenNowPlaying(songs: List<SongItem>, index: Int) {
        restoreLyricsAfterAlbumBack = false
        lyricsAlbumRouteObserved = false
        PlayerManager.prefetchYouTubePlayableUrlWindow(
            playlist = songs,
            startIndex = index,
            source = "ui_click_before_play"
        )
        showNowPlaying = true
        // 播放队列可能包含歌词等大字段，避免通过 Binder 传整份歌单导致崩溃
        PlayerManager.playPlaylist(songs, index)
        scheduleAudioServiceStart(
            "play_songs_and_open_now_playing",
            true
        )
    }

    fun playSongPreservingQueueAndOpenNowPlaying(song: SongItem) {
        restoreLyricsAfterAlbumBack = false
        lyricsAlbumRouteObserved = false
        PlayerManager.prefetchYouTubePlayableUrlWindow(
            playlist = listOf(song),
            startIndex = 0,
            source = "ui_click_preserve_queue_before_play"
        )
        showNowPlaying = true
        PlayerManager.replaceCurrentInQueueAndPlay(song)
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

    fun playBiliAudioAndOpenNowPlaying(videos: List<BiliVideoItem>, index: Int) {
        restoreLyricsAfterAlbumBack = false
        lyricsAlbumRouteObserved = false
        showNowPlaying = true
        NPLogger.d("NERI-App", "Playing audio from Bili video: ${videos[index].title}")
        PlayerManager.playBiliVideoAsAudio(videos, index)
        ensureAudioServiceStarted(source = "play_bili_audio_and_open_now_playing")
    }

    fun playBiliPartsAndOpenNowPlaying(
        videoInfo: BiliClient.VideoBasicInfo,
        index: Int,
        coverUrl: String
    ) {
        restoreLyricsAfterAlbumBack = false
        lyricsAlbumRouteObserved = false
        showNowPlaying = true
        NPLogger.d("NERI-App", "Playing parts from Bili video: ${videoInfo.title}")
        PlayerManager.playBiliVideoParts(videoInfo, index, coverUrl)
        ensureAudioServiceStarted(source = "play_bili_parts_and_open_now_playing")
    }

    CompositionLocalProvider(LocalDensity provides finalDensity) {
        val activeCoverSeedHex = if (displayCoverUrl == null) null else coverSeedHex
        val effectiveSeedHex = if (dynamicColorEnabled) {
            activeCoverSeedHex ?: themeSeedColor
        } else {
            themeSeedColor
        }
        val useSystemDynamic =
            dynamicColorEnabled && activeCoverSeedHex == null && displayCoverUrl == null

        NeriTheme(
            followSystemDark = followSystemDark,
            forceDark = forceDark,
            dynamicColor = useSystemDynamic,
            seedColorHex = effectiveSeedHex,
            paletteStyle = themePaletteStyle,
            colorSpec = themeColorSpec,
            systemDark = systemDark
        ) {
            val navController = rememberNavController()
            val backEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backEntry?.destination?.route
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
            val showHomeTab =
                (showHomeContinueCard && homeUsageEntries.isNotEmpty()) ||
                    showHomeTrendingCard ||
                    showHomeRadarCard ||
                    showHomeRecommendedCard
            val effectiveStartDestination = remember(defaultStartDestination, showHomeTab, devModeEnabled) {
                resolveMainStartDestination(
                    preferredRoute = defaultStartDestination,
                    showHomeTab = showHomeTab,
                    devModeEnabled = devModeEnabled
                )
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

            Box(modifier = Modifier.fillMaxSize()) {
                val modifier = if (backgroundImageUri == null || !effectiveAdvancedBlurEnabled) {
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
                    alpha = effectiveBackgroundImageAlpha,
                    modifier = modifier
                )

                val containerColor = if (backgroundImageUri == null) {
                    MaterialTheme.colorScheme.background
                } else Color.Transparent

                val selectAlpha = if (backgroundImageUri == null) 1f else 0f
                val bottomBarHazeModifier =
                    if (effectiveAdvancedBlurEnabled) Modifier.hazeChild(state = hazeState) else Modifier

                val isMiniPlayerVisible = currentSong != null && !showNowPlaying
                val isPlaybackControlPlaying by PlayerManager.playbackControlPlayingFlow.collectAsStateWithLifecycle()
                val usbPlaybackPreparing by PlayerManager.usbExclusivePlaybackPreparingFlow
                    .collectAsStateWithLifecycle()
                val reservedMiniPlayerHeightDp = if (isMiniPlayerVisible) {
                    moe.ouom.neriplayer.ui.component.NeriMiniPlayerDefaults.Height
                } else {
                    0.dp
                }

                LaunchedEffect(currentRoute, showHomeTab, effectiveStartDestination) {
                    if (!showHomeTab && currentRoute == Destinations.Home.route) {
                        navController.navigate(effectiveStartDestination) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }

                CompositionLocalProvider(LocalMiniPlayerHeight provides reservedMiniPlayerHeightDp) {
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
                                        .then(bottomBarHazeModifier)
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
                                startDestination = effectiveStartDestination,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (effectiveAdvancedBlurEnabled) {
                                            Modifier.haze(
                                                hazeState,
                                                HazeStyle(
                                                    tint = MaterialTheme.colorScheme.onSurface.copy(.0f),
                                                    blurRadius = ROOT_HAZE_BLUR_RADIUS,
                                                    noiseFactor = HazeDefaults.noiseFactor
                                                )
                                            )
                                        } else {
                                            Modifier
                                        }
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
                                        showContinueCard = showHomeContinueCard,
                                        showTrendingCard = showHomeTrendingCard,
                                        showRadarCard = showHomeRadarCard,
                                        showRecommendedCard = showHomeRecommendedCard,
                                        offlineMode = offlineMode,
                                        onSongClick = ::playSongsAndOpenNowPlaying
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
                                    val playlist = navigationGson.fromJson(playlistJson, PlaylistSummary::class.java)
                                    NeteasePlaylistDetailScreen(
                                        playlist = playlist,
                                        onBack = { navController.popBackStack() },
                                        onSongClick = ::playSongsAndOpenNowPlaying,
                                        offlineMode = offlineMode
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
                                    val album = navigationGson.fromJson(playlistJson, AlbumSummary::class.java)
                                    NeteaseAlbumDetailScreen(
                                        album = album,
                                        onBack = { navController.popBackStack() },
                                        onSongClick = ::playSongsAndOpenNowPlaying,
                                        offlineMode = offlineMode
                                    )
                                }

                                composable(
                                    route = Destinations.NeteaseArtistDetail.route,
                                    arguments = listOf(navArgument("artistJson") {
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
                                    val artistJson = backStackEntry.arguments?.getString("artistJson")
                                    val artist = navigationGson.fromJson(artistJson, NeteaseArtistSummary::class.java)
                                    NeteaseArtistDetailScreen(
                                        artist = artist,
                                        onBack = { navController.popBackStack() },
                                        onSongClick = ::playSongsAndOpenNowPlaying,
                                        offlineMode = offlineMode,
                                        onAlbumClick = { album ->
                                            val json = Uri.encode(navigationGson.toJson(album))
                                            navController.navigate("netease_album_detail/$json")
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
                                    val playlist = navigationGson.fromJson(playlistJson, BiliPlaylist::class.java)
                                    BiliPlaylistDetailScreen(
                                        playlist = playlist,
                                        onBack = { navController.popBackStack() },
                                        onPlayAudio = ::playBiliAudioAndOpenNowPlaying,
                                        onPlayParts = ::playBiliPartsAndOpenNowPlaying,
                                        offlineMode = offlineMode
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
                                        offlineMode = offlineMode,
                                        onSongClick = ::playSongsAndOpenNowPlaying,
                                        onSongPlayPreservingQueue = ::playSongPreservingQueueAndOpenNowPlaying,
                                        onSongPlayNext = ::addSongToQueueNextFromSearch,
                                        onSongAddToQueueEnd = ::addSongToQueueEndFromSearch,
                                        onPlayParts = ::playBiliPartsAndOpenNowPlaying
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
                                        onSongClick = ::playSongsAndOpenNowPlaying,
                                        onPlayParts = ::playBiliPartsAndOpenNowPlaying,
                                        onOpenRecent = { navController.navigate(Destinations.Recent.route) },
                                        onOpenStats = { navController.navigate(Destinations.PlaybackStats.route) },
                                        offlineMode = offlineMode
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
                                        onSongClick = ::playSongsAndOpenNowPlaying,
                                        offlineMode = offlineMode
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
                                        onSongClick = ::playSongsAndOpenNowPlaying,
                                        offlineMode = offlineMode
                                    )
                                }

                                composable(
                                    route = Destinations.PlaybackStats.route,
                                    enterTransition = { slideInVertically(animationSpec = tween(220)) { it } + fadeIn() },
                                    exitTransition = { fadeOut(animationSpec = tween(160)) },
                                    popEnterTransition = { slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn() },
                                    popExitTransition = { slideOutVertically(animationSpec = tween(240)) { it } + fadeOut() }
                                ) {
                                    PlaybackStatsScreen(
                                        onBack = { navController.popBackStack() },
                                        onSongClick = ::playSongsAndOpenNowPlaying,
                                        offlineMode = offlineMode
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
                                        mobileDataNeteaseAudioQuality =
                                            mobileDataNeteaseAudioQuality,
                                        onMobileDataNeteaseAudioQualityChange = { quality ->
                                            scope.launch {
                                                repo.setMobileDataNeteaseAudioQuality(quality)
                                            }
                                        },
                                        mobileDataYouTubeAudioQuality =
                                            mobileDataYouTubeAudioQuality,
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
                                        onSeedColorChange = { hex -> scope.launch { repo.setThemeSeedColor(hex) } },
                                        themeColorPalette = themeColorPalette,
                                        onAddColorToPalette = { hex -> scope.launch { repo.addThemePaletteColor(hex) } },
                                        onRemoveColorFromPalette = { hex -> scope.launch { repo.removeThemePaletteColor(hex) } },
                                        themePaletteStyle = themePaletteStyleValue,
                                        onThemePaletteStyleChange = { style ->
                                            scope.launch { repo.setThemePaletteStyle(style) }
                                        },
                                        themeColorSpec = themeColorSpecValue,
                                        onThemeColorSpecChange = { spec ->
                                            scope.launch { repo.setThemeColorSpec(spec) }
                                        },
                                        devModeEnabled = devModeEnabled,
                                        onDevModeChange = { enabled -> scope.launch { repo.setDevModeEnabled(enabled) } },
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
                                        floatingLyricsPreferences = floatingLyricsPreferences,
                                        onFloatingLyricsPreferencesChange = { preferences ->
                                            scope.launch { repo.setFloatingLyricsPreferences(preferences) }
                                        },
                                        advancedBlurEnabled = advancedBlurEnabled,
                                        onAdvancedBlurEnabledChange = { enabled ->
                                            scope.launch { repo.setAdvancedBlurEnabled(enabled) }
                                        },
                                        nowPlayingAudioReactiveEnabled = nowPlayingAudioReactiveEnabled,
                                        onNowPlayingAudioReactiveEnabledChange = { enabled ->
                                            scope.launch { repo.setNowPlayingAudioReactiveEnabled(enabled) }
                                        },
                                        nowPlayingDynamicBackgroundEnabled = nowPlayingDynamicBackgroundEnabled,
                                        onNowPlayingDynamicBackgroundEnabledChange = { enabled ->
                                            scope.launch { repo.setNowPlayingDynamicBackgroundEnabled(enabled) }
                                        },
                                        nowPlayingCoverBlurBackgroundEnabled = nowPlayingCoverBlurBackgroundEnabled,
                                        onNowPlayingCoverBlurBackgroundEnabledChange = { enabled ->
                                            scope.launch { repo.setNowPlayingCoverBlurBackgroundEnabled(enabled) }
                                        },
                                        nowPlayingCoverBlurAmount = nowPlayingCoverBlurAmount,
                                        onNowPlayingCoverBlurAmountChange = { amount ->
                                            scope.launch { repo.setNowPlayingCoverBlurAmount(amount) }
                                        },
                                        nowPlayingCoverBlurDarken = nowPlayingCoverBlurDarken,
                                        onNowPlayingCoverBlurDarkenChange = { amount ->
                                            scope.launch { repo.setNowPlayingCoverBlurDarken(amount) }
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
                                        defaultStartDestination = defaultStartDestination,
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
                                        homeHasRecentUsage = homeUsageEntries.isNotEmpty(),
                                        playbackFadeIn = playbackFadeIn,
                                        onPlaybackFadeInChange = { enabled ->
                                            scope.launch { repo.setPlaybackFadeIn(enabled) }
                                        },
                                        playbackCrossfadeNext = playbackCrossfadeNext,
                                        onPlaybackCrossfadeNextChange = { enabled ->
                                            scope.launch { repo.setPlaybackCrossfadeNext(enabled) }
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
                                        keepLastPlaybackProgress = keepLastPlaybackProgress,
                                        onKeepLastPlaybackProgressChange = { enabled ->
                                            scope.launch { repo.setKeepLastPlaybackProgress(enabled) }
                                        },
                                        keepPlaybackModeState = keepPlaybackModeState,
                                        onKeepPlaybackModeStateChange = { enabled ->
                                            scope.launch { repo.setKeepPlaybackModeState(enabled) }
                                        },
                                        neteaseAutoSourceSwitch = neteaseAutoSourceSwitch,
                                        onNeteaseAutoSourceSwitchChange = { enabled ->
                                            scope.launch { repo.setNeteaseAutoSourceSwitch(enabled) }
                                        },
                                        stopOnBluetoothDisconnect = stopOnBluetoothDisconnect,
                                        onStopOnBluetoothDisconnectChange = { enabled ->
                                            scope.launch { repo.setStopOnBluetoothDisconnect(enabled) }
                                        },
                                        usbExclusivePlayback = usbExclusivePlayback,
                                        onUsbExclusivePlaybackChange = { enabled ->
                                            if (PlayerManager.beginUsbExclusiveToggleTransitionFromUi(enabled)) {
                                                scope.launch { repo.setUsbExclusivePlayback(enabled) }
                                                if (
                                                    enabled &&
                                                    !usbExclusivePlayback &&
                                                    !usbExclusiveBackgroundPermissionPromptSuppressed &&
                                                    !context.readBackgroundBehaviorAllowance().fullyAllowed
                                                ) {
                                                    showUsbExclusiveBackgroundPermissionDialog = true
                                                }
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
                                                    val result = clearExtraStorageCaches(context, options)
                                                    messages += if (result.success) {
                                                        context.getString(
                                                            R.string.storage_extra_cache_clear_complete,
                                                            formatFileSize(result.freedBytes)
                                                        )
                                                    } else {
                                                        context.getString(R.string.storage_extra_cache_clear_partial)
                                                    }
                                                }
                                                snackbarHostState.showSnackbar(messages.joinToString(" · "))
                                            }
                                        },
                                        onBeforeLanguageRestart = clearThemeRevealState
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
                                    DownloadManagerScreen(
                                        onBack = { navController.popBackStack() },
                                        onOpenDownloadProgress = { navController.navigate(Destinations.DownloadProgress.route) },
                                        offlineMode = offlineMode
                                    )
                                }

                                composable(
                                    route = Destinations.DownloadProgress.route,
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
                                    DownloadProgressScreen(onBack = { navController.popBackStack() })
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
                                        onOpenBiliDebug = { navController.navigate(Destinations.DebugBili.route) },
                                        onOpenNeteaseDebug = { navController.navigate(Destinations.DebugNetease.route) },
                                        onOpenSearchDebug = { navController.navigate(Destinations.DebugSearch.route) },
                                        onOpenLogs = { navController.navigate(Destinations.DebugLogsList.route) },
                                        onOpenCrashLogs = { navController.navigate(Destinations.DebugCrashLogsList.route) },
                                        onTestExceptionHandler = { crashType ->
                                            val crashMessage = context.getString(R.string.test_exception_message)
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
                                composable(Destinations.DebugListenTogether.route) { ListenTogetherDebugScreen() }
                                composable(Destinations.DebugUsbExclusive.route) { UsbExclusiveDebugScreen() }
                                composable(Destinations.DebugYouTube.route) { YouTubeApiProbeScreen() }
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

                                composable(Destinations.DebugCrashLogsList.route) {
                                    CrashLogListScreen(
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
                                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                    initialOffsetY = { it / 2 }
                                ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                                exit = slideOutVertically(
                                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                    targetOffsetY = { it / 2 }
                                ) + fadeOut(animationSpec = tween(durationMillis = 120))
                            ) {
                                NeriMiniPlayer(
                                    title = currentSong?.displayName()
                                        ?: context.getString(R.string.nowplaying_no_playback),
                                    artist = currentSong?.displayArtist() ?: "",
                                    coverUrl = displayCoverUrl,
                                    isPlaying = isPlaybackControlPlaying,
                                    playPauseEnabled = !usbPlaybackPreparing,
                                    modifier = Modifier,
                                    onPlayPause = { PlayerManager.togglePlayPause() },
                                    onPrevious = { PlayerManager.previous() },
                                    onNext = { PlayerManager.next() },
                                    onExpand = { showNowPlaying = true },
                                    hazeState = hazeState,
                                    enableHaze = effectiveAdvancedBlurEnabled,
                                    offlineMode = offlineMode
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
                    val currentCoverUrl = displayCoverUrl
                    val activeCoverSeedHex = if (currentCoverUrl == null) null else coverSeedHex
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
                        val nowPlayingCoverUrl = remember(currentSong, context) {
                            currentSong.resolveUiCoverSource(context)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .blockUnderlyingTouches()
                        ) {
                            val coverBlurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            val hasCoverBlur =
                                coverBlurAvailable &&
                                    nowPlayingCoverBlurBackgroundEnabled &&
                                    !nowPlayingCoverUrl.isNullOrBlank()
                            val blurStrength = nowPlayingCoverBlurAmount.coerceIn(0f, 500f)
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
                            var stableCoverUrl by remember { mutableStateOf<String?>(null) }
                            var stableBlurStrength by remember { mutableStateOf<Float?>(null) }
                            var coverBlurLoadFailed by remember { mutableStateOf(false) }
                            val coverBlurRequestKey = remember(nowPlayingCoverUrl, effectiveBlurStrength) {
                                if (nowPlayingCoverUrl.isNullOrBlank()) {
                                    null
                                } else {
                                    "nowplaying-blur:$nowPlayingCoverUrl:$effectiveBlurStrength"
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
                                            .memoryCacheKey("nowplaying-blur:$url:$effectiveBlurStrength")
                                            .diskCacheKey("nowplaying-blur:$url:$effectiveBlurStrength")
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

                            LaunchedEffect(hasCoverBlur, nowPlayingCoverUrl, effectiveBlurStrength) {
                                if (!hasCoverBlur) {
                                    stableCoverUrl = null
                                    stableBlurStrength = null
                                    coverBlurLoadFailed = false
                                } else {
                                    coverBlurLoadFailed = false
                                }
                            }

                            val blurBackdropCoverUrl = stableCoverUrl ?: nowPlayingCoverUrl
                            val useCoverBlurBackground = hasCoverBlur && !coverBlurLoadFailed

                            if (!useCoverBlurBackground) {
                                // 背景固定按暗色逻辑渲染
                                NowPlayingAccentBackdrop(
                                    coverUrl = nowPlayingCoverUrl,
                                    isDark = true,
                                    refreshKey = coverArtRefreshToken,
                                    modifier = Modifier.fillMaxSize(),
                                    offlineMode = offlineMode
                                )
                            }

                            if (useCoverBlurBackground) {
                                // 先铺一层强调色背景，避免首次加载和旋转重建时黑底闪烁
                                NowPlayingAccentBackdrop(
                                    coverUrl = blurBackdropCoverUrl,
                                    isDark = true,
                                    refreshKey = coverArtRefreshToken,
                                    modifier = Modifier.fillMaxSize(),
                                    offlineMode = offlineMode
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
                                            .memoryCacheKey("nowplaying-blur:$stableCoverUrl:$stableBlurStrength")
                                            .diskCacheKey("nowplaying-blur:$stableCoverUrl:$stableBlurStrength")
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
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(nowPlayingCoverUrl)
                                        .allowHardware(false)
                                        .bitmapConfig(Bitmap.Config.RGB_565)
                                        .size(blurImageSizePx)
                                        .precision(Precision.INEXACT)
                                        .memoryCacheKey("nowplaying-blur:$nowPlayingCoverUrl:$effectiveBlurStrength")
                                        .diskCacheKey("nowplaying-blur:$nowPlayingCoverUrl:$effectiveBlurStrength")
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
                                            stableCoverUrl = null
                                            stableBlurStrength = null
                                            coverBlurLoadFailed = true
                                        }
                                    }
                                )
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
                                    refreshKey = coverArtRefreshToken,
                                    offlineMode = offlineMode
                                )
                            }

                            CompositionLocalProvider(LocalMiniPlayerHeight provides 0.dp) {
                                NowPlayingScreen(
                                    onNavigateUp = { showNowPlaying = false },
                                    showLyricsScreen = showNowPlayingLyrics,
                                    onShowLyricsScreenChange = { showNowPlayingLyrics = it },
                                    onEnterAlbum = { album ->
                                        val json = Uri.encode(navigationGson.toJson(album))
                                        navController.navigate("netease_album_detail/$json")
                                        if (showNowPlayingLyrics) {
                                            restoreLyricsAfterAlbumBack = true
                                        }
                                    },
                                    onEnterArtist = ::navigateToNeteaseArtist,
                                    lyricBlurEnabled = lyricBlurEnabled,
                                    lyricBlurAmount = lyricBlurAmount,
                                    lyricFontScale = lyricFontScale,
                                    onLyricFontScaleChange = { scale ->
                                        scope.launch { repo.setLyricFontScale(scale) }
                                    },
                                    advancedLyricsEnabled = advancedLyricsEnabled,
                                    showCoverSourceBadge = showCoverSourceBadge,
                                    showLyricTranslation = showLyricTranslation,
                                    showNowPlayingTitle = showNowPlayingTitle,
                                    offlineMode = offlineMode
                                )
                            }
                        }
                    }
                }

                val revealOrigin = themeRevealOriginWindow
                val revealFallbackColor = themeRevealFallbackColorArgb?.let(::Color)
                if (revealOrigin != null && revealFallbackColor != null) {
                    ThemeRevealOverlay(
                        snapshot = themeRevealSnapshot,
                        fallbackColor = revealFallbackColor,
                        originInWindow = revealOrigin,
                        modifier = Modifier.fillMaxSize(),
                        startRadiusPx = themeRevealStartRadiusPx,
                        legacySnapshotDim = true,
                        durationMillis = 720,
                        onFinished = clearThemeRevealState
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
