package moe.ouom.neriplayer.ui.screen.tab

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
 * File: moe.ouom.neriplayer.ui.screen.tab/SettingsScreen
 * Created: 2025/8/8
 */

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.NeteaseWebLoginActivity
import moe.ouom.neriplayer.activity.YouTubeWebLoginActivity
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.BILI_AUTH_STALE_AFTER_MS
import moe.ouom.neriplayer.data.NETEASE_AUTH_STALE_AFTER_MS
import moe.ouom.neriplayer.data.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.SavedCookieAuthState
import moe.ouom.neriplayer.data.ThemeDefaults
import moe.ouom.neriplayer.data.BackgroundImageStorage
import moe.ouom.neriplayer.data.YOUTUBE_AUTH_STALE_AFTER_MS
import moe.ouom.neriplayer.data.YouTubeAuthHealth
import moe.ouom.neriplayer.data.YouTubeAuthState
import moe.ouom.neriplayer.listentogether.isDefaultListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.data.github.SecureTokenStorage
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.util.HapticButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.convertTimestampToDate
import moe.ouom.neriplayer.util.formatFileSize
import java.io.File
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SdStorage
import moe.ouom.neriplayer.ui.component.HsvPicker
import moe.ouom.neriplayer.ui.component.LanguageSettingItem


/**
 * 脱敏处理cookie值，只显示首尾各2个字符，中间用***代替
 * 例如: "abcde" -> "ab***de"
 */
private fun maskCookieValue(value: String): String {
    return when {
        value.length <= 4 -> "***"
        else -> "${value.take(2)}***${value.takeLast(2)}"
    }
}

private val SettingsItemShape = RoundedCornerShape(18.dp)

private fun Modifier.settingsItemClickable(onClick: () -> Unit): Modifier {
    return clip(SettingsItemShape).clickable(onClick = onClick)
}

/** 可复用的折叠区头部 */
@Composable
private fun ExpandableHeader(
    icon: ImageVector,
    title: String,
    subtitleCollapsed: String,
    subtitleExpanded: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    arrowRotation: Float = 0f
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(if (expanded) subtitleExpanded else subtitleCollapsed) },
        trailingContent = {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                modifier = Modifier.rotate(arrowRotation.takeIf { it != 0f } ?: if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        modifier = Modifier.settingsItemClickable { onToggle() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** 主题色预览行（当关闭系统动态取色时显示） */
@Composable
private fun ThemeSeedListItem(seedColorHex: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.ColorLens,
                contentDescription = stringResource(R.string.settings_theme_color),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_theme_color)) },
        supportingContent = { Text(stringResource(R.string.settings_theme_color_desc)) },
        trailingContent = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(("#$seedColorHex").toColorInt()))
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** UI 缩放设置入口 */
@Composable
private fun UiScaleListItem(currentScale: Float, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.ZoomInMap,
                contentDescription = stringResource(R.string.settings_ui_scale),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_ui_scale_dpi)) },
        supportingContent = { Text(stringResource(R.string.settings_ui_scale_current, "%.2f".format(currentScale))) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ThemeModeActionButton(
    isDarkTheme: Boolean,
    onToggleRequest: (Offset, Float) -> Unit
) {
    var centerInWindow by remember { mutableStateOf<Offset?>(null) }
    var revealStartRadiusPx by remember { mutableFloatStateOf(18f) }
    val contentDescription = if (isDarkTheme) {
        stringResource(R.string.settings_theme_toggle_light)
    } else {
        stringResource(R.string.settings_theme_toggle_dark)
    }
    val iconProgress by animateFloatAsState(
        targetValue = if (isDarkTheme) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "theme_toggle_icon_progress"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isDarkTheme) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        },
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "theme_toggle_container_color"
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(containerColor)
    ) {
        HapticIconButton(
            onClick = {
                centerInWindow?.let { onToggleRequest(it, revealStartRadiusPx) }
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    revealStartRadiusPx = maxOf(
                        coordinates.size.width,
                        coordinates.size.height
                    ) / 2f
                    centerInWindow = coordinates.positionInWindow() + Offset(
                        x = coordinates.size.width / 2f,
                        y = coordinates.size.height / 2f
                    )
                }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.DarkMode,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            alpha = 1f - iconProgress
                            val scale = 0.56f + (1f - iconProgress) * 0.44f
                            scaleX = scale
                            scaleY = scale
                            rotationZ = -56f * iconProgress
                        }
                )
                Icon(
                    imageVector = Icons.Outlined.LightMode,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            alpha = iconProgress
                            val scale = 0.56f + iconProgress * 0.44f
                            scaleX = scale
                            scaleY = scale
                            rotationZ = 56f * (1f - iconProgress)
                        }
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    listState: androidx.compose.foundation.lazy.LazyListState,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    onThemeToggleRequest: (Offset, Float) -> Unit,
    preferredQuality: String,
    onQualityChange: (String) -> Unit,
    youtubePreferredQuality: String,
    onYouTubeQualityChange: (String) -> Unit,
    biliPreferredQuality: String,
    onBiliQualityChange: (String) -> Unit,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    seedColorHex: String,
    onSeedColorChange: (String) -> Unit,
    themeColorPalette: List<String>,
    onAddColorToPalette: (String) -> Unit,
    onRemoveColorFromPalette: (String) -> Unit,
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
    lyricBlurAmount: Float,
    onLyricBlurAmountChange: (Float) -> Unit,
    advancedBlurEnabled: Boolean,
    onAdvancedBlurEnabledChange: (Boolean) -> Unit,
    nowPlayingAudioReactiveEnabled: Boolean,
    onNowPlayingAudioReactiveEnabledChange: (Boolean) -> Unit,
    nowPlayingDynamicBackgroundEnabled: Boolean,
    onNowPlayingDynamicBackgroundEnabledChange: (Boolean) -> Unit,
    nowPlayingCoverBlurBackgroundEnabled: Boolean,
    onNowPlayingCoverBlurBackgroundEnabledChange: (Boolean) -> Unit,
    nowPlayingCoverBlurAmount: Float,
    onNowPlayingCoverBlurAmountChange: (Float) -> Unit,
    nowPlayingCoverBlurDarken: Float,
    onNowPlayingCoverBlurDarkenChange: (Float) -> Unit,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    uiDensityScale: Float,
    onUiDensityScaleChange: (Float) -> Unit,
    bypassProxy: Boolean,
    onBypassProxyChange: (Boolean) -> Unit,
    backgroundImageUri: String?,
    onBackgroundImageChange: (Uri?) -> Unit,
    backgroundImageBlur: Float,
    onBackgroundImageBlurChange: (Float) -> Unit,
    backgroundImageAlpha: Float,
    onBackgroundImageAlphaChange: (Float) -> Unit,
    hapticFeedbackEnabled: Boolean,
    onHapticFeedbackEnabledChange: (Boolean) -> Unit,
    showCoverSourceBadge: Boolean,
    onShowCoverSourceBadgeChange: (Boolean) -> Unit,
    silentGitHubSyncFailure: Boolean,
    onSilentGitHubSyncFailureChange: (Boolean) -> Unit,
    showLyricTranslation: Boolean,
    onShowLyricTranslationChange: (Boolean) -> Unit,
    defaultStartDestination: String,
    onDefaultStartDestinationChange: (String) -> Unit,
    autoShowKeyboard: Boolean,
    onAutoShowKeyboardChange: (Boolean) -> Unit,
    showHomeContinueCard: Boolean,
    onShowHomeContinueCardChange: (Boolean) -> Unit,
    showHomeTrendingCard: Boolean,
    onShowHomeTrendingCardChange: (Boolean) -> Unit,
    showHomeRadarCard: Boolean,
    onShowHomeRadarCardChange: (Boolean) -> Unit,
    showHomeRecommendedCard: Boolean,
    onShowHomeRecommendedCardChange: (Boolean) -> Unit,
    playbackFadeIn: Boolean,
    onPlaybackFadeInChange: (Boolean) -> Unit,
    playbackCrossfadeNext: Boolean,
    onPlaybackCrossfadeNextChange: (Boolean) -> Unit,
    playbackFadeInDurationMs: Long,
    onPlaybackFadeInDurationMsChange: (Long) -> Unit,
    playbackFadeOutDurationMs: Long,
    onPlaybackFadeOutDurationMsChange: (Long) -> Unit,
    playbackCrossfadeInDurationMs: Long,
    onPlaybackCrossfadeInDurationMsChange: (Long) -> Unit,
    playbackCrossfadeOutDurationMs: Long,
    onPlaybackCrossfadeOutDurationMsChange: (Long) -> Unit,
    keepLastPlaybackProgress: Boolean,
    onKeepLastPlaybackProgressChange: (Boolean) -> Unit,
    keepPlaybackModeState: Boolean,
    onKeepPlaybackModeStateChange: (Boolean) -> Unit,
    stopOnBluetoothDisconnect: Boolean,
    onStopOnBluetoothDisconnectChange: (Boolean) -> Unit,
    allowMixedPlayback: Boolean,
    onAllowMixedPlaybackChange: (Boolean) -> Unit,
    onNavigateToDownloadManager: () -> Unit = {},
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onClearCacheClick: (clearAudio: Boolean, clearImage: Boolean) -> Unit,
    onBeforeLanguageRestart: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listenTogetherPreferences = remember { AppContainer.listenTogetherPreferences }
    val listenTogetherApi = remember { AppContainer.listenTogetherApi }
    val listenTogetherSessionManager = remember { AppContainer.listenTogetherSessionManager }
    val listenTogetherSessionState by listenTogetherSessionManager.sessionState.collectAsState()
    val listenTogetherWorkerBaseUrl by listenTogetherPreferences.workerBaseUrlFlow.collectAsState(initial = "")
    val internationalEnabled by AppContainer.settingsRepo.internationalizationEnabledFlow
        .collectAsState(initial = false)

    // 登录菜单的状态
    var loginExpanded by rememberSaveable { mutableStateOf(false) }
    // 仅用于示意展开箭头的旋转，后续可复用至 ExpandableHeader 的 arrowRotation 入参
    val arrowRotation by animateFloatAsState(targetValue = if (loginExpanded) 180f else 0f, label = "arrow")

    // 个性化菜单的状态
    var personalizationExpanded by rememberSaveable { mutableStateOf(false) }
    val personalizationArrowRotation by animateFloatAsState(targetValue = if (personalizationExpanded) 180f else 0f, label = "personalization_arrow")

    // 动效设置菜单的状态
    var motionExpanded by rememberSaveable { mutableStateOf(false) }
    val motionArrowRotation by animateFloatAsState(targetValue = if (motionExpanded) 180f else 0f, label = "motion_arrow")

    LaunchedEffect(nowPlayingDynamicBackgroundEnabled, nowPlayingCoverBlurBackgroundEnabled) {
        if (nowPlayingCoverBlurBackgroundEnabled) {
            if (nowPlayingDynamicBackgroundEnabled) {
                onNowPlayingDynamicBackgroundEnabledChange(false)
            }
            if (nowPlayingAudioReactiveEnabled) {
                onNowPlayingAudioReactiveEnabledChange(false)
            }
        } else if (!nowPlayingDynamicBackgroundEnabled && nowPlayingAudioReactiveEnabled) {
            onNowPlayingAudioReactiveEnabledChange(false)
        }
    }

    // 网络配置菜单的状态
    var networkExpanded by rememberSaveable { mutableStateOf(false) }
    val networkArrowRotation by animateFloatAsState(targetValue = if (networkExpanded) 180f else 0f, label = "network_arrow")

    var listenTogetherExpanded by rememberSaveable { mutableStateOf(false) }
    val listenTogetherArrowRotation by animateFloatAsState(
        targetValue = if (listenTogetherExpanded) 180f else 0f,
        label = "listen_together_arrow"
    )

    // 音质设置菜单的状态
    var audioQualityExpanded by rememberSaveable { mutableStateOf(false) }
    val audioQualityArrowRotation by animateFloatAsState(targetValue = if (audioQualityExpanded) 180f else 0f, label = "audio_quality_arrow")

    // 播放设置菜单的状态
    var playbackExpanded by rememberSaveable { mutableStateOf(false) }
    val playbackArrowRotation by animateFloatAsState(targetValue = if (playbackExpanded) 180f else 0f, label = "playback_arrow")

    // 下载管理菜单的状态
    var downloadManagerExpanded by rememberSaveable { mutableStateOf(false) }
    val downloadManagerArrowRotation by animateFloatAsState(targetValue = if (downloadManagerExpanded) 180f else 0f, label = "download_manager_arrow")

    // 备份与恢复菜单的状态
    var backupRestoreExpanded by rememberSaveable { mutableStateOf(false) }
    val backupRestoreArrowRotation by animateFloatAsState(targetValue = if (backupRestoreExpanded) 180f else 0f, label = "backup_restore_arrow")

    // 缓存设置的状态
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var cacheExpanded by rememberSaveable { mutableStateOf(false) }
    val cacheArrowRotation by animateFloatAsState(targetValue = if (cacheExpanded) 180f else 0f, label = "backup_restore_arrow")

    // 缓存类型选择状态
    var clearAudioCache by remember { mutableStateOf(true) }
    var clearImageCache by remember { mutableStateOf(true) }

    // 存储占用详情状态
    var showStorageDetails by remember { mutableStateOf(false) }
    var storageDetails by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }


    // 各种对话框和弹窗的显示状态 //
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNeteaseSheet by remember { mutableStateOf(false) }
    var showYouTubeQualityDialog by remember { mutableStateOf(false) }
    var showBiliQualityDialog by remember { mutableStateOf(false) }
    var showDefaultStartDestinationDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var showBiliSheet by remember { mutableStateOf(false) }
    var showBiliCookieDialog by remember { mutableStateOf(false) }
    var showBiliReauthDialog by remember { mutableStateOf(false) }
    var showYouTubeSheet by remember { mutableStateOf(false) }
    var showYouTubeCookieDialog by remember { mutableStateOf(false) }

    var showNeteaseReauthDialog by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
    var showGitHubConfigDialog by remember { mutableStateOf(false) }
    var showClearGitHubConfigDialog by remember { mutableStateOf(false) }
    var showListenTogetherResetUuidDialog by remember { mutableStateOf(false) }
    var showListenTogetherServerDialog by remember { mutableStateOf(false) }
    var listenTogetherServerInput by rememberSaveable { mutableStateOf("") }
    var listenTogetherServerTesting by remember { mutableStateOf(false) }
    var listenTogetherServerTestMessage by remember { mutableStateOf<String?>(null) }
    // ------------------------------------

    val neteaseVm: NeteaseAuthViewModel = viewModel()
    val neteaseAuthUiState by neteaseVm.uiState.collectAsStateWithLifecycleCompat()
    var inlineMsg by remember { mutableStateOf<String?>(null) }
    var confirmPhoneMasked by remember { mutableStateOf<String?>(null) }
    var cookieText by remember { mutableStateOf("") }
    val cookieScroll = rememberScrollState()
    var versionTapCount by remember { mutableIntStateOf(0) }
    var biliCookieText by remember { mutableStateOf("") }
    val biliVm: BiliAuthViewModel = viewModel()
    val biliAuthUiState by biliVm.uiState.collectAsStateWithLifecycleCompat()
    var biliReauthHealth by remember { mutableStateOf<SavedCookieAuthHealth?>(null) }
    var biliSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var neteaseReauthHealth by remember { mutableStateOf<SavedCookieAuthHealth?>(null) }
    var neteaseSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var youtubeCookieText by remember { mutableStateOf("") }
    val youtubeVm: YouTubeAuthViewModel = viewModel()
    val youtubeAuthUiState by youtubeVm.uiState.collectAsStateWithLifecycleCompat()
    var youtubeSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    
    // 备份与恢复
    val backupRestoreVm: BackupRestoreViewModel = viewModel()
    val backupRestoreUiState by backupRestoreVm.uiState.collectAsState()

    // 照片选择器
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    val importedUri = BackgroundImageStorage.importFromUri(
                        context = context,
                        sourceUri = uri,
                        previousUriString = backgroundImageUri
                    )
                    if (importedUri != null) {
                        onBackgroundImageChange(importedUri)
                    }
                }
            }
        }
    )

    LaunchedEffect(listenTogetherWorkerBaseUrl) {
        if (listenTogetherServerInput != listenTogetherWorkerBaseUrl) {
            listenTogetherServerInput = listenTogetherWorkerBaseUrl
        }
    }

    val biliWebLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(moe.ouom.neriplayer.activity.BiliWebLoginActivity.RESULT_COOKIE) ?: "{}"
            val map = biliVm.parseJsonToMap(json)
            biliVm.importCookiesFromMap(map)
        } else {
            inlineMsg = context.getString(R.string.settings_cookie_cancelled)
        }
    }

    val youtubeWebLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(YouTubeWebLoginActivity.RESULT_AUTH_JSON) ?: "{}"
            youtubeVm.importAuthFromJson(json)
        } else {
            inlineMsg = context.getString(R.string.settings_cookie_cancelled)
        }
    }

    // 备份与恢复的SAF启动器
    val exportPlaylistLauncher = rememberLauncherForActivityResult(
        contract = CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupRestoreVm.initialize(context)
            backupRestoreVm.exportPlaylists(uri)
        }
    }

    val importPlaylistLauncher = rememberLauncherForActivityResult(
        contract = OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupRestoreVm.initialize(context)
            backupRestoreVm.importPlaylists(uri)
        }
    }

    rememberLauncherForActivityResult(
        contract = OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupRestoreVm.initialize(context)
            backupRestoreVm.analyzeDifferences(uri)
        }
    }

    // 当前所选音质对应的中文标签
    val qualityLabel = remember(preferredQuality) {
        when (preferredQuality) {
            "standard" -> context.getString(R.string.settings_audio_quality_standard)
            "higher" -> context.getString(R.string.settings_audio_quality_higher)
            "exhigh" -> context.getString(R.string.settings_audio_quality_exhigh)
            "lossless" -> context.getString(R.string.settings_audio_quality_lossless)
            "hires" -> context.getString(R.string.quality_hires)
            "jyeffect" -> context.getString(R.string.settings_audio_quality_jyeffect)
            "sky" -> context.getString(R.string.settings_audio_quality_sky)
            "jymaster" -> context.getString(R.string.settings_audio_quality_jymaster)
            else -> preferredQuality
        }
    }

    val biliQualityLabel = remember(biliPreferredQuality) {
        when (biliPreferredQuality) {
            "dolby"   -> context.getString(R.string.settings_audio_quality_dolby)
            "hires"   -> context.getString(R.string.quality_hires)
            "lossless"-> context.getString(R.string.settings_audio_quality_lossless)
            "high"    -> context.getString(R.string.settings_audio_quality_high)
            "medium"  -> context.getString(R.string.settings_audio_quality_medium)
            "low"     -> context.getString(R.string.settings_audio_quality_low)
            else -> biliPreferredQuality
        }
    }

    val youtubeQualityLabel = remember(youtubePreferredQuality) {
        when (youtubePreferredQuality) {
            "low" -> context.getString(R.string.settings_audio_quality_standard)
            "medium" -> context.getString(R.string.settings_audio_quality_medium)
            "high" -> context.getString(R.string.settings_audio_quality_high)
            "very_high" -> context.getString(R.string.quality_very_high)
            else -> youtubePreferredQuality
        }
    }

    !showHomeContinueCard &&
        !showHomeTrendingCard &&
        !showHomeRadarCard &&
        !showHomeRecommendedCard
    val recentUsage by AppContainer.playlistUsageRepo.frequentPlaylistsFlow.collectAsState(initial = emptyList())
    val homeStartAvailable =
        showHomeTrendingCard ||
            showHomeRadarCard ||
            showHomeRecommendedCard ||
            (showHomeContinueCard && recentUsage.isNotEmpty())
    val homeTrendingLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_guess_you_like
    } else {
        R.string.recommend_trending
    }
    val homeRadarLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_daily_discover
    } else {
        R.string.recommend_radar
    }
    val homeRecommendedLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_more_recommendations
    } else {
        R.string.recommend_for_you
    }
    val homeCardsDescriptionRes = if (internationalEnabled) {
        R.string.settings_home_cards_desc_international
    } else {
        R.string.settings_home_cards_desc
    }
    val homeTrendingSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_guess_you_like_desc
    } else {
        null
    }
    val homeRadarSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_daily_discover_desc
    } else {
        null
    }
    val homeRecommendedSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_more_recommendations_desc
    } else {
        null
    }
    val effectiveDefaultStartDestination = remember(defaultStartDestination, homeStartAvailable) {
        if (!homeStartAvailable && defaultStartDestination == "home") {
            "explore"
        } else {
            defaultStartDestination
        }
    }
    val defaultStartDestinationLabel = remember(effectiveDefaultStartDestination, context) {
        when (effectiveDefaultStartDestination) {
            "explore" -> context.getString(R.string.nav_explore)
            "library" -> context.getString(R.string.nav_library)
            "settings" -> context.getString(R.string.nav_settings)
            else -> context.getString(R.string.nav_home)
        }
    }
    val biliStatusText = when (biliAuthUiState.health.state) {
        SavedCookieAuthState.Valid -> {
            val relativeTime = biliAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_bili_status_valid, relativeTime)
        }
        SavedCookieAuthState.Checking -> stringResource(R.string.settings_auth_checking)
        SavedCookieAuthState.Expired -> stringResource(R.string.settings_bili_status_expired)
        SavedCookieAuthState.Stale -> {
            biliAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { stringResource(R.string.settings_bili_status_stale, formatSyncTime(it)) }
                ?: stringResource(R.string.settings_bili_status_stale_no_time)
        }
        SavedCookieAuthState.Missing -> stringResource(R.string.settings_bili_status_missing)
    }
    val neteaseStatusText = when (neteaseAuthUiState.health.state) {
        SavedCookieAuthState.Valid -> {
            val relativeTime = neteaseAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_netease_status_valid, relativeTime)
        }
        SavedCookieAuthState.Checking -> stringResource(R.string.settings_auth_checking)
        SavedCookieAuthState.Expired -> stringResource(R.string.settings_netease_status_expired)
        SavedCookieAuthState.Stale -> {
            neteaseAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { stringResource(R.string.settings_netease_status_stale, formatSyncTime(it)) }
                ?: stringResource(R.string.settings_netease_status_stale_no_time)
        }
        SavedCookieAuthState.Missing -> stringResource(R.string.settings_netease_status_missing)
    }
    val youtubeStatusText = when (youtubeAuthUiState.health.state) {
        YouTubeAuthState.Valid -> {
            val relativeTime = youtubeAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_youtube_status_valid, relativeTime)
        }
        YouTubeAuthState.Expired -> stringResource(R.string.settings_youtube_status_expired)
        YouTubeAuthState.Stale -> {
            youtubeAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { stringResource(R.string.settings_youtube_status_stale, formatSyncTime(it)) }
                ?: stringResource(R.string.settings_youtube_status_stale_no_time)
        }
        YouTubeAuthState.Missing -> stringResource(R.string.settings_youtube_status_missing)
    }
    val lifecycleOwner = context as? LifecycleOwner

    DisposableEffect(lifecycleOwner, biliVm, neteaseVm, youtubeVm) {
        biliVm.refreshAuthHealth(promptIfNeeded = true)
        neteaseVm.refreshAuthHealth(promptIfNeeded = true)
        youtubeVm.refreshAuthHealth()
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    biliVm.refreshAuthHealth(promptIfNeeded = true)
                    neteaseVm.refreshAuthHealth(promptIfNeeded = true)
                    youtubeVm.refreshAuthHealth()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(neteaseVm) {
        neteaseVm.events.collect { e ->
            when (e) {
                is NeteaseAuthEvent.ShowSnack -> {
                    inlineMsg = e.message
                }
                is NeteaseAuthEvent.AskConfirmSend -> {
                    confirmPhoneMasked = e.masked
                    showConfirmDialog = true
                }
                NeteaseAuthEvent.LoginSuccess -> {
                    inlineMsg = null
                    showNeteaseSheet = false
                    showNeteaseReauthDialog = false
                    neteaseReauthHealth = null
                    inlineMsg = context.getString(R.string.settings_netease_login_success)
                    neteaseVm.refreshAuthHealth(promptIfNeeded = true, forcePrompt = true)
                }
                is NeteaseAuthEvent.ShowCookies -> {
                    cookieText = e.cookies.entries.joinToString("\n") { (k, v) -> "$k=${maskCookieValue(v)}" }
                    showCookieDialog = true
                }
                is NeteaseAuthEvent.PromptReauth -> {
                    neteaseReauthHealth = e.health
                    showNeteaseReauthDialog = true
                }
            }
        }
    }

    LaunchedEffect(biliVm) {
        biliVm.events.collect { e ->
            when (e) {
                is BiliAuthEvent.ShowSnack -> inlineMsg = e.message
                is BiliAuthEvent.ShowCookies -> {
                    biliCookieText = e.cookies.entries.joinToString("\n") { (k, v) -> "$k=${maskCookieValue(v)}" }
                    showBiliCookieDialog = true
                }
                BiliAuthEvent.LoginSuccess -> {
                    showBiliSheet = false
                    showBiliReauthDialog = false
                    biliReauthHealth = null
                    inlineMsg = context.getString(R.string.settings_bili_login_success)
                    biliVm.refreshAuthHealth(promptIfNeeded = true, forcePrompt = true)
                }
                is BiliAuthEvent.PromptReauth -> {
                    biliReauthHealth = e.health
                    showBiliReauthDialog = true
                }
            }
        }
    }

    LaunchedEffect(youtubeVm) {
        youtubeVm.events.collect { e ->
            when (e) {
                is YouTubeAuthEvent.ShowSnack -> inlineMsg = e.message
                is YouTubeAuthEvent.ShowCookies -> {
                    youtubeCookieText = e.cookies.entries.joinToString("\n") { (k, v) ->
                        "$k=${maskCookieValue(v)}"
                    }
                    showYouTubeCookieDialog = true
                }
                YouTubeAuthEvent.LoginSuccess -> {
                    showYouTubeSheet = false
                    inlineMsg = context.getString(R.string.settings_youtube_login_success)
                }

            }
        }
    }


    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(stringResource(R.string.settings_title))
                        ThemeModeActionButton(
                            isDarkTheme = isDarkTheme,
                            onToggleRequest = onThemeToggleRequest
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    navigationIconContentColor = Color.Unspecified,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = Color.Unspecified
                )
            )
        }
    ) { innerPadding ->
        val miniPlayerHeight = LocalMiniPlayerHeight.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 8.dp + miniPlayerHeight
            ),
            state = listState
        ) {
            // 动态取色
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Brightness4,
                            contentDescription = stringResource(R.string.settings_dynamic_color),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.settings_dynamic_color_desc)) },
                    trailingContent = {
                        Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            item {
                AnimatedVisibility(visible = !dynamicColor) { // 仅在关闭系统动态取色时显示
                    ThemeSeedListItem(
                        seedColorHex = seedColorHex,
                        onClick = { showColorPickerDialog = true }
                    )
                }
            }


            item {
                // 触感反馈
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.AdsClick,
                            contentDescription = stringResource(R.string.settings_haptic_feedback),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_haptic)) },
                    supportingContent = { Text(stringResource(R.string.settings_haptic_desc)) },
                    trailingContent = {
                        Switch(checked = hapticFeedbackEnabled, onCheckedChange = onHapticFeedbackEnabledChange)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // 语言设置
            item {
                LanguageSettingItem(onBeforeRestart = onBeforeLanguageRestart)
            }

            // 国际化开关
            item {
                var checking by remember { mutableStateOf(false) }

                ListItem(
                    leadingContent = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_i18n),
                            contentDescription = stringResource(R.string.settings_internationalization),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_internationalization)) },
                    supportingContent = {
                        Text(
                            if (checking) stringResource(R.string.settings_internationalization_checking)
                            else stringResource(R.string.settings_internationalization_desc)
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = internationalEnabled,
                            enabled = !checking,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    scope.launch {
                                        AppContainer.settingsRepo.setInternationalizationEnabled(false)
                                    }
                                } else {
                                    checking = true
                                    scope.launch {
                                        try {
                                            // 尝试获取歌单列表检测 YouTube Music 可访问性
                                            AppContainer.youtubeMusicClient.getLibraryPlaylists()
                                            AppContainer.settingsRepo.setInternationalizationEnabled(true)
                                        } catch (_: Exception) {
                                            inlineMsg = context.getString(R.string.settings_internationalization_unavailable)
                                        } finally {
                                            checking = false
                                        }
                                    }
                                }
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // 登录三方平台
            item {
                ExpandableHeader(
                    icon = Icons.Filled.AccountCircle,
                    title = stringResource(R.string.settings_login_platforms),
                    subtitleCollapsed = stringResource(R.string.settings_login_platforms_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = loginExpanded,
                    onToggle = { loginExpanded = !loginExpanded },
                    arrowRotation = arrowRotation
                )
            }

            // 展开区域
            item {
                AnimatedVisibility(
                    visible = loginExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_bilibili),
                                    contentDescription = stringResource(R.string.settings_bilibili),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.platform_bilibili)) },
                            supportingContent = { Text(biliStatusText) },
                            modifier = Modifier.settingsItemClickable {
                                inlineMsg = null
                                biliSheetInitialTab = 0
                                showBiliSheet = true
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )


                        // YouTube
                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_youtube),
                                    contentDescription = stringResource(R.string.common_youtube),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.common_youtube)) },
                            supportingContent = { Text(youtubeStatusText) },
                            modifier = Modifier.settingsItemClickable {
                                inlineMsg = null
                                youtubeSheetInitialTab = 0
                                showYouTubeSheet = true
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        // 网易云音乐
                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_netease_cloud_music),
                                    contentDescription = stringResource(R.string.settings_netease),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.platform_netease)) },
                            supportingContent = { Text(neteaseStatusText) },
                            modifier = Modifier.settingsItemClickable {
                                inlineMsg = null
                                neteaseSheetInitialTab = 0
                                showNeteaseSheet = true
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        // QQ 音乐
                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_qq_music),
                                    contentDescription = stringResource(R.string.settings_qq_music),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_qq_music)) },
                            supportingContent = { Text(stringResource(R.string.common_coming_soon)) },
                            modifier = Modifier.settingsItemClickable { },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            item {
                ExpandableHeader(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_personalization),
                    subtitleCollapsed = stringResource(R.string.settings_personalization_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = personalizationExpanded,
                    onToggle = { personalizationExpanded = !personalizationExpanded },
                    arrowRotation = personalizationArrowRotation
                )
            }

              // 展开区域
              item {
                  AnimatedVisibility(
                      visible = personalizationExpanded,
                      enter = fadeIn() + expandVertically(),
                      exit = fadeOut() + shrinkVertically()
                  ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                showDefaultStartDestinationDialog = true
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Home,
                                    contentDescription = stringResource(R.string.settings_default_start_screen),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_default_start_screen)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.settings_default_start_screen_desc,
                                        defaultStartDestinationLabel
                                    )
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onAutoShowKeyboardChange(!autoShowKeyboard)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Keyboard,
                                    contentDescription = stringResource(R.string.settings_auto_show_keyboard),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_auto_show_keyboard)) },
                            supportingContent = { Text(stringResource(R.string.settings_auto_show_keyboard_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = autoShowKeyboard,
                                    onCheckedChange = onAutoShowKeyboardChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        Text(
                            text = stringResource(R.string.settings_home_cards),
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(homeCardsDescriptionRes),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onShowHomeContinueCardChange(!showHomeContinueCard)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = stringResource(R.string.player_continue),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.player_continue)) },
                            trailingContent = {
                                Switch(
                                    checked = showHomeContinueCard,
                                    onCheckedChange = onShowHomeContinueCardChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onShowHomeTrendingCardChange(!showHomeTrendingCard)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Bolt,
                                    contentDescription = stringResource(homeTrendingLabelRes),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(homeTrendingLabelRes)) },
                            supportingContent = homeTrendingSupportingRes?.let { resId ->
                                {
                                    Text(
                                        text = stringResource(resId),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            trailingContent = {
                                Switch(
                                    checked = showHomeTrendingCard,
                                    onCheckedChange = onShowHomeTrendingCardChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onShowHomeRadarCardChange(!showHomeRadarCard)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = if (internationalEnabled) {
                                        Icons.Outlined.Explore
                                    } else {
                                        Icons.Outlined.Search
                                    },
                                    contentDescription = stringResource(homeRadarLabelRes),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(homeRadarLabelRes)) },
                            supportingContent = homeRadarSupportingRes?.let { resId ->
                                {
                                    Text(
                                        text = stringResource(resId),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            trailingContent = {
                                Switch(
                                    checked = showHomeRadarCard,
                                    onCheckedChange = onShowHomeRadarCardChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onShowHomeRecommendedCardChange(!showHomeRecommendedCard)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.LibraryMusic,
                                    contentDescription = stringResource(homeRecommendedLabelRes),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(homeRecommendedLabelRes)) },
                            supportingContent = homeRecommendedSupportingRes?.let { resId ->
                                {
                                    Text(
                                        text = stringResource(resId),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            trailingContent = {
                                Switch(
                                    checked = showHomeRecommendedCard,
                                    onCheckedChange = onShowHomeRecommendedCardChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        AnimatedVisibility(visible = !homeStartAvailable) {
                            Text(
                                text = stringResource(R.string.settings_home_hidden_notice),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = stringResource(R.string.settings_display),
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_display_desc),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = stringResource(R.string.settings_cover_source_badge),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_cover_source_badge)) },
                            supportingContent = { Text(stringResource(R.string.settings_cover_source_badge_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = showCoverSourceBadge,
                                    onCheckedChange = onShowCoverSourceBadgeChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Subtitles,
                                    contentDescription = stringResource(R.string.settings_show_lyric_translation),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_show_lyric_translation)) },
                            supportingContent = { Text(stringResource(R.string.settings_show_lyric_translation_desc)) },
                            trailingContent = {
                                Switch(checked = showLyricTranslation, onCheckedChange = onShowLyricTranslationChange)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.FormatSize,
                                    contentDescription = stringResource(R.string.settings_lyrics_font_size),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.lyrics_font_size)) },
                            supportingContent = {
                                var pendingLyricFontScale by remember { mutableFloatStateOf(lyricFontScale) }
                                LaunchedEffect(lyricFontScale) {
                                    if ((pendingLyricFontScale - lyricFontScale).absoluteValue > 0.001f) {
                                        pendingLyricFontScale = lyricFontScale
                                    }
                                }

                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = stringResource(R.string.settings_lyrics_font_current, (pendingLyricFontScale * 100).roundToInt()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = pendingLyricFontScale,
                                        onValueChange = { pendingLyricFontScale = it },
                                        onValueChangeFinished = {
                                            onLyricFontScaleChange(pendingLyricFontScale)
                                        },
                                        valueRange = 0.5f..1.6f,
                                        steps = 10
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_lyrics_sample),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        textAlign = TextAlign.Center,
                                        fontSize = (18f * pendingLyricFontScale)
                                            .coerceIn(12f, 28f).sp
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        
                        UiScaleListItem(currentScale = uiDensityScale, onClick = { showDpiDialog = true })

                        // 选择背景图
                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            ),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Wallpaper,
                                    contentDescription = stringResource(R.string.settings_custom_background)
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.background_custom)) },
                            supportingContent = { Text(if (backgroundImageUri != null) stringResource(R.string.settings_background_change) else stringResource(R.string.settings_background_select)) }
                        )

                        // 展开区域
                        AnimatedVisibility(visible = backgroundImageUri != null) {
                                Column {
                                    // 清除背景图按钮
                                TextButton(onClick = {
                                    scope.launch {
                                        BackgroundImageStorage.deleteManagedBackground(
                                            context = context,
                                            uriString = backgroundImageUri
                                        )
                                        onBackgroundImageChange(null)
                                    }
                                }) {
                                    Text(stringResource(R.string.background_clear))
                                }

                                // 模糊度调节
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.background_blur)) },
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent
                                    ),
                                    supportingContent = {
                                        Slider(
                                            value = backgroundImageBlur,
                                            onValueChange = onBackgroundImageBlurChange,
                                            valueRange = 0f..25f // Coil 的模糊范围
                                        )
                                    }
                                )

                                // 透明度调节
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.background_opacity)) },
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent
                                    ),
                                    supportingContent = {
                                        Slider(
                                            value = backgroundImageAlpha,
                                            onValueChange = onBackgroundImageAlphaChange,
                                            valueRange = 0.1f..1.0f
                                        )
                                    }
                                )
                            }
                        }
                      }
                  }
              }

              item {
                  ExpandableHeader(
                      icon = Icons.Outlined.Bolt,
                      title = stringResource(R.string.settings_motion),
                      subtitleCollapsed = stringResource(R.string.settings_motion_expand),
                      subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                      expanded = motionExpanded,
                      onToggle = { motionExpanded = !motionExpanded },
                      arrowRotation = motionArrowRotation
                  )
              }

              item {
                  AnimatedVisibility(
                      visible = motionExpanded,
                      enter = fadeIn() + expandVertically(),
                      exit = fadeOut() + shrinkVertically()
                  ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        val coverBlurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        val advancedBlurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        val dynamicBackgroundApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        LaunchedEffect(coverBlurAvailable, dynamicBackgroundApiAvailable, advancedBlurAvailable) {
                            if (!coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled) {
                                onNowPlayingCoverBlurBackgroundEnabledChange(false)
                            }
                            if (!advancedBlurAvailable && advancedBlurEnabled) {
                                onAdvancedBlurEnabledChange(false)
                            }
                            if (!dynamicBackgroundApiAvailable) {
                                if (nowPlayingDynamicBackgroundEnabled) {
                                    onNowPlayingDynamicBackgroundEnabledChange(false)
                                }
                                if (nowPlayingAudioReactiveEnabled) {
                                    onNowPlayingAudioReactiveEnabledChange(false)
                                }
                            }
                        }
                        val dynamicBackgroundAvailable =
                            dynamicBackgroundApiAvailable && !nowPlayingCoverBlurBackgroundEnabled
                        val dynamicBackgroundAlpha = if (dynamicBackgroundAvailable) 1f else 0.5f
                        val audioReactiveAvailable =
                            dynamicBackgroundApiAvailable &&
                                nowPlayingDynamicBackgroundEnabled &&
                                dynamicBackgroundAvailable
                        val audioReactiveAlpha = if (audioReactiveAvailable) 1f else 0.5f
                        val onCoverBlurToggle: (Boolean) -> Unit = onCoverBlurToggle@{ enabled ->
                            if (!coverBlurAvailable) return@onCoverBlurToggle
                            onNowPlayingCoverBlurBackgroundEnabledChange(enabled)
                            if (enabled) {
                                if (nowPlayingDynamicBackgroundEnabled) {
                                    onNowPlayingDynamicBackgroundEnabledChange(false)
                                }
                                if (nowPlayingAudioReactiveEnabled) {
                                    onNowPlayingAudioReactiveEnabledChange(false)
                                }
                            }
                        }
                        val onDynamicBackgroundToggle: (Boolean) -> Unit = onDynamicBackgroundToggle@{ enabled ->
                            if (!dynamicBackgroundAvailable) return@onDynamicBackgroundToggle
                            onNowPlayingDynamicBackgroundEnabledChange(enabled)
                            if (!enabled && nowPlayingAudioReactiveEnabled) {
                                onNowPlayingAudioReactiveEnabledChange(false)
                            }
                        }

                        ListItem(
                            modifier = Modifier
                                .settingsItemClickable {
                                    if (advancedBlurAvailable) {
                                        onAdvancedBlurEnabledChange(!advancedBlurEnabled)
                                    }
                                }
                                .alpha(if (advancedBlurAvailable) 1f else 0.5f),
                            leadingContent = {
                                  Icon(
                                      imageVector = Icons.Outlined.BlurOn,
                                      contentDescription = stringResource(R.string.settings_advanced_blur),
                                      modifier = Modifier.size(24.dp),
                                      tint = MaterialTheme.colorScheme.onSurface
                                  )
                              },
                              headlineContent = { Text(stringResource(R.string.settings_advanced_blur)) },
                              supportingContent = {
                                  val desc = stringResource(R.string.settings_advanced_blur_desc)
                                  val suffix = if (advancedBlurAvailable) "" else " · " + stringResource(R.string.settings_android12_required)
                                  Text(desc + suffix)
                              },
                              trailingContent = {
                                  Switch(
                                      checked = advancedBlurAvailable && advancedBlurEnabled,
                                      onCheckedChange = { enabled ->
                                          if (advancedBlurAvailable) {
                                              onAdvancedBlurEnabledChange(enabled)
                                          }
                                      },
                                      enabled = advancedBlurAvailable
                                  )
                              },
                              colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                          )

                        ListItem(
                            modifier = Modifier
                                .settingsItemClickable {
                                    if (coverBlurAvailable) {
                                        onCoverBlurToggle(!nowPlayingCoverBlurBackgroundEnabled)
                                    }
                                }
                                .alpha(if (coverBlurAvailable) 1f else 0.5f),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Wallpaper,
                                    contentDescription = stringResource(R.string.settings_nowplaying_cover_blur_background),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_nowplaying_cover_blur_background)) },
                            supportingContent = {
                                val desc = stringResource(R.string.settings_nowplaying_cover_blur_background_desc)
                                val suffix = if (coverBlurAvailable) "" else " · " + stringResource(R.string.settings_android12_required)
                                Text(desc + suffix)
                            },
                            trailingContent = {
                                Switch(
                                    checked = coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled,
                                    onCheckedChange = { onCoverBlurToggle(it) },
                                    enabled = coverBlurAvailable
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        AnimatedVisibility(visible = coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled) {
                            val blurUiMax = 500f
                            val blurUiStep = 5f
                            val blurSteps = (blurUiMax / blurUiStep).toInt().coerceAtLeast(1) - 1

                            Column(Modifier.fillMaxWidth()) {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.settings_nowplaying_cover_blur_amount)) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    supportingContent = {
                                        var pendingBlurAmount by remember {
                                            mutableFloatStateOf(nowPlayingCoverBlurAmount.coerceIn(0f, blurUiMax))
                                        }
                                        LaunchedEffect(nowPlayingCoverBlurAmount) {
                                            val clamped = nowPlayingCoverBlurAmount.coerceIn(0f, blurUiMax)
                                            if ((pendingBlurAmount - clamped).absoluteValue > 0.01f) {
                                                pendingBlurAmount = clamped
                                            }
                                        }
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                text = stringResource(
                                                    R.string.settings_nowplaying_cover_blur_value,
                                                    pendingBlurAmount
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Slider(
                                                value = pendingBlurAmount,
                                                onValueChange = { value ->
                                                    val snapped = (value / blurUiStep).roundToInt() * blurUiStep
                                                    pendingBlurAmount = snapped.coerceIn(0f, blurUiMax)
                                                },
                                                onValueChangeFinished = {
                                                    onNowPlayingCoverBlurAmountChange(
                                                        pendingBlurAmount.coerceIn(0f, blurUiMax)
                                                    )
                                                },
                                                valueRange = 0f..blurUiMax,
                                                steps = blurSteps
                                            )
                                        }
                                    }
                                )

                                Spacer(Modifier.height(4.dp))

                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.settings_nowplaying_cover_blur_darken)) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    supportingContent = {
                                        var pendingDarken by remember { mutableFloatStateOf(nowPlayingCoverBlurDarken) }
                                        LaunchedEffect(nowPlayingCoverBlurDarken) {
                                            if ((pendingDarken - nowPlayingCoverBlurDarken).absoluteValue > 0.01f) {
                                                pendingDarken = nowPlayingCoverBlurDarken
                                            }
                                        }
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                text = stringResource(
                                                    R.string.settings_nowplaying_cover_blur_darken_value,
                                                    pendingDarken
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Slider(
                                                value = pendingDarken,
                                                onValueChange = { pendingDarken = it },
                                                onValueChangeFinished = {
                                                    onNowPlayingCoverBlurDarkenChange(pendingDarken.coerceIn(0f, 0.8f))
                                                },
                                                valueRange = 0f..0.8f,
                                                steps = 15
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        ListItem(
                            modifier = (if (audioReactiveAvailable) {
                                Modifier.settingsItemClickable {
                                    onNowPlayingAudioReactiveEnabledChange(!nowPlayingAudioReactiveEnabled)
                                }
                            } else {
                                Modifier
                            }).alpha(audioReactiveAlpha),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Analytics,
                                    contentDescription = stringResource(R.string.settings_nowplaying_audio_reactive),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_nowplaying_audio_reactive)) },
                            supportingContent = {
                                val desc = stringResource(R.string.settings_nowplaying_audio_reactive_desc)
                                val suffix = if (dynamicBackgroundApiAvailable) "" else " · " + stringResource(R.string.settings_android13_required)
                                Text(desc + suffix)
                            },
                            trailingContent = {
                                Switch(
                                    checked = audioReactiveAvailable && nowPlayingAudioReactiveEnabled,
                                    onCheckedChange = {
                                        if (audioReactiveAvailable) {
                                            onNowPlayingAudioReactiveEnabledChange(it)
                                        }
                                    },
                                    enabled = audioReactiveAvailable
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            modifier = (if (dynamicBackgroundAvailable) {
                                Modifier.settingsItemClickable {
                                    onDynamicBackgroundToggle(!nowPlayingDynamicBackgroundEnabled)
                                }
                            } else {
                                Modifier
                            }).alpha(dynamicBackgroundAlpha),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = stringResource(R.string.settings_nowplaying_dynamic_background),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_nowplaying_dynamic_background)) },
                            supportingContent = {
                                val desc = stringResource(R.string.settings_nowplaying_dynamic_background_desc)
                                val suffix = if (dynamicBackgroundApiAvailable) "" else " · " + stringResource(R.string.settings_android13_required)
                                Text(desc + suffix)
                            },
                            trailingContent = {
                                Switch(
                                    checked = dynamicBackgroundAvailable && nowPlayingDynamicBackgroundEnabled,
                                    onCheckedChange = {
                                        if (dynamicBackgroundAvailable) {
                                            onDynamicBackgroundToggle(it)
                                        }
                                    },
                                    enabled = dynamicBackgroundAvailable
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                          ListItem(
                              leadingContent = {
                                  Icon(
                                      imageVector = Icons.Outlined.Subtitles,
                                      contentDescription = stringResource(R.string.settings_lyrics_blur),
                                      modifier = Modifier.size(24.dp),
                                      tint = MaterialTheme.colorScheme.onSurface
                                  )
                              },
                              headlineContent = { Text(stringResource(R.string.lyrics_blur_effect)) },
                              supportingContent = {
                                  val desc = stringResource(R.string.lyrics_blur_desc)
                                  val suffix =
                                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "" else " · " + stringResource(
                                          R.string.lyrics_blur_low_cost_hint
                                      )
                                  Text(desc + suffix)
                              },
                              trailingContent = {
                                  Switch(checked = lyricBlurEnabled, onCheckedChange = onLyricBlurEnabledChange)
                              },
                              colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                          )

                          AnimatedVisibility(visible = lyricBlurEnabled) {
                              ListItem(
                                  headlineContent = { Text(stringResource(R.string.lyrics_blur_amount)) },
                                  colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                  supportingContent = {
                                      var pendingBlurAmount by remember { mutableFloatStateOf(lyricBlurAmount) }
                                      LaunchedEffect(lyricBlurAmount) {
                                          if ((pendingBlurAmount - lyricBlurAmount).absoluteValue > 0.01f) {
                                              pendingBlurAmount = lyricBlurAmount
                                          }
                                      }

                                      Column(Modifier.fillMaxWidth()) {
                                          Text(
                                              text = stringResource(R.string.lyrics_blur_current, pendingBlurAmount),
                                              style = MaterialTheme.typography.bodySmall,
                                              color = MaterialTheme.colorScheme.onSurfaceVariant
                                          )
                                          Slider(
                                              value = pendingBlurAmount,
                                              onValueChange = { pendingBlurAmount = it },
                                              onValueChangeFinished = {
                                                  onLyricBlurAmountChange(pendingBlurAmount)
                                              },
                                              valueRange = 0f..8f,
                                              steps = 79
                                          )
                                      }
                                  }
                              )
                          }
                      }
                  }
              }

              item {
                  ExpandableHeader(
                      icon = Icons.Outlined.Router,
                      title = stringResource(R.string.settings_network),
                    subtitleCollapsed = stringResource(R.string.settings_network_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = networkExpanded,
                    onToggle = { networkExpanded = !networkExpanded },
                    arrowRotation = networkArrowRotation
                )
            }

            // 展开区域
            item {
                AnimatedVisibility(
                    visible = networkExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.AltRoute,
                                    contentDescription = stringResource(R.string.settings_bypass_proxy),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_bypass_proxy)) },
                            supportingContent = { Text(stringResource(R.string.settings_bypass_proxy_desc)) },
                            trailingContent = {
                                Switch(checked = bypassProxy, onCheckedChange = onBypassProxyChange)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            item {
                ExpandableHeader(
                    icon = Icons.Outlined.Cloud,
                    title = stringResource(R.string.listen_together_title),
                    subtitleCollapsed = stringResource(R.string.settings_listen_together_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = listenTogetherExpanded,
                    onToggle = { listenTogetherExpanded = !listenTogetherExpanded },
                    arrowRotation = listenTogetherArrowRotation
                )
            }

            item {
                AnimatedVisibility(
                    visible = listenTogetherExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    ListenTogetherSettingsSection(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                        isUsingDefaultServer = listenTogetherServerInput.isBlank() ||
                            isDefaultListenTogetherBaseUrl(resolveListenTogetherBaseUrl(listenTogetherServerInput)),
                        isInRoom = !listenTogetherSessionState.roomId.isNullOrBlank(),
                        testing = listenTogetherServerTesting,
                        testMessage = listenTogetherServerTestMessage,
                        onOpenServerDialog = {
                            listenTogetherServerTestMessage = null
                            showListenTogetherServerDialog = true
                        },
                        onResetIdentity = {
                            if (listenTogetherSessionState.roomId.isNullOrBlank()) {
                                showListenTogetherResetUuidDialog = true
                            }
                        }
                    )
                }
            }


            item {
                ExpandableHeader(
                    icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    title = stringResource(R.string.settings_playback),
                    subtitleCollapsed = stringResource(R.string.settings_playback_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = playbackExpanded,
                    onToggle = { playbackExpanded = !playbackExpanded },
                    arrowRotation = playbackArrowRotation
                )
            }

            item {
                AnimatedVisibility(
                    visible = playbackExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onPlaybackFadeInChange(!playbackFadeIn)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.GraphicEq,
                                    contentDescription = stringResource(R.string.settings_playback_fade_in),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_playback_fade_in)) },
                            supportingContent = { Text(stringResource(R.string.settings_playback_fade_in_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = playbackFadeIn,
                                    onCheckedChange = onPlaybackFadeInChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        AnimatedVisibility(visible = playbackFadeIn) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                            ) {
                                val fadeInSeconds = playbackFadeInDurationMs / 1000f
                                var pendingFadeInSeconds by remember { mutableFloatStateOf(fadeInSeconds) }
                                LaunchedEffect(playbackFadeInDurationMs) {
                                    if ((pendingFadeInSeconds - fadeInSeconds).absoluteValue > 0.01f) {
                                        pendingFadeInSeconds = fadeInSeconds
                                    }
                                }
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.settings_playback_fade_in_duration)) },
                                    supportingContent = {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                text = stringResource(
                                                    R.string.settings_playback_fade_duration_value,
                                                    pendingFadeInSeconds
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Slider(
                                                value = pendingFadeInSeconds,
                                                onValueChange = { pendingFadeInSeconds = it },
                                                onValueChangeFinished = {
                                                    onPlaybackFadeInDurationMsChange(
                                                        (pendingFadeInSeconds * 1000f).roundToLong()
                                                    )
                                                },
                                                valueRange = 0f..3f,
                                                steps = 29
                                            )
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )

                                val fadeOutSeconds = playbackFadeOutDurationMs / 1000f
                                var pendingFadeOutSeconds by remember { mutableFloatStateOf(fadeOutSeconds) }
                                LaunchedEffect(playbackFadeOutDurationMs) {
                                    if ((pendingFadeOutSeconds - fadeOutSeconds).absoluteValue > 0.01f) {
                                        pendingFadeOutSeconds = fadeOutSeconds
                                    }
                                }
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.settings_playback_fade_out_duration)) },
                                    supportingContent = {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                text = stringResource(
                                                    R.string.settings_playback_fade_duration_value,
                                                    pendingFadeOutSeconds
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Slider(
                                                value = pendingFadeOutSeconds,
                                                onValueChange = { pendingFadeOutSeconds = it },
                                                onValueChangeFinished = {
                                                    onPlaybackFadeOutDurationMsChange(
                                                        (pendingFadeOutSeconds * 1000f).roundToLong()
                                                    )
                                                },
                                                valueRange = 0f..3f,
                                                steps = 29
                                            )
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onPlaybackCrossfadeNextChange(!playbackCrossfadeNext)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Sync,
                                    contentDescription = stringResource(R.string.settings_playback_crossfade_next),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_playback_crossfade_next)) },
                            supportingContent = { Text(stringResource(R.string.settings_playback_crossfade_next_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = playbackCrossfadeNext,
                                    onCheckedChange = onPlaybackCrossfadeNextChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        AnimatedVisibility(visible = playbackCrossfadeNext) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                            ) {
                                val crossfadeInSeconds = playbackCrossfadeInDurationMs / 1000f
                                var pendingCrossfadeInSeconds by remember { mutableFloatStateOf(crossfadeInSeconds) }
                                LaunchedEffect(playbackCrossfadeInDurationMs) {
                                    if ((pendingCrossfadeInSeconds - crossfadeInSeconds).absoluteValue > 0.01f) {
                                        pendingCrossfadeInSeconds = crossfadeInSeconds
                                    }
                                }
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.settings_playback_crossfade_in_duration)) },
                                    supportingContent = {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                text = stringResource(
                                                    R.string.settings_playback_fade_duration_value,
                                                    pendingCrossfadeInSeconds
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Slider(
                                                value = pendingCrossfadeInSeconds,
                                                onValueChange = { pendingCrossfadeInSeconds = it },
                                                onValueChangeFinished = {
                                                    onPlaybackCrossfadeInDurationMsChange(
                                                        (pendingCrossfadeInSeconds * 1000f).roundToLong()
                                                    )
                                                },
                                                valueRange = 0f..3f,
                                                steps = 29
                                            )
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )

                                val crossfadeOutSeconds = playbackCrossfadeOutDurationMs / 1000f
                                var pendingCrossfadeOutSeconds by remember { mutableFloatStateOf(crossfadeOutSeconds) }
                                LaunchedEffect(playbackCrossfadeOutDurationMs) {
                                    if ((pendingCrossfadeOutSeconds - crossfadeOutSeconds).absoluteValue > 0.01f) {
                                        pendingCrossfadeOutSeconds = crossfadeOutSeconds
                                    }
                                }
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.settings_playback_crossfade_out_duration)) },
                                    supportingContent = {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                text = stringResource(
                                                    R.string.settings_playback_fade_duration_value,
                                                    pendingCrossfadeOutSeconds
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Slider(
                                                value = pendingCrossfadeOutSeconds,
                                                onValueChange = { pendingCrossfadeOutSeconds = it },
                                                onValueChangeFinished = {
                                                    onPlaybackCrossfadeOutDurationMsChange(
                                                        (pendingCrossfadeOutSeconds * 1000f).roundToLong()
                                                    )
                                                },
                                                valueRange = 0f..3f,
                                                steps = 29
                                            )
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onKeepLastPlaybackProgressChange(!keepLastPlaybackProgress)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = stringResource(R.string.settings_keep_last_playback_progress),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_keep_last_playback_progress)) },
                            supportingContent = { Text(stringResource(R.string.settings_keep_last_playback_progress_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = keepLastPlaybackProgress,
                                    onCheckedChange = onKeepLastPlaybackProgressChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onKeepPlaybackModeStateChange(!keepPlaybackModeState)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = stringResource(R.string.settings_keep_playback_mode_state),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_keep_playback_mode_state)) },
                            supportingContent = { Text(stringResource(R.string.settings_keep_playback_mode_state_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = keepPlaybackModeState,
                                    onCheckedChange = onKeepPlaybackModeStateChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onStopOnBluetoothDisconnectChange(!stopOnBluetoothDisconnect)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.BluetoothAudio,
                                    contentDescription = stringResource(R.string.settings_stop_on_bluetooth_disconnect),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_stop_on_bluetooth_disconnect)) },
                            supportingContent = { Text(stringResource(R.string.settings_stop_on_bluetooth_disconnect_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = stopOnBluetoothDisconnect,
                                    onCheckedChange = onStopOnBluetoothDisconnectChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            modifier = Modifier.settingsItemClickable {
                                onAllowMixedPlaybackChange(!allowMixedPlayback)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                                    contentDescription = stringResource(R.string.settings_allow_mixed_playback),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_allow_mixed_playback)) },
                            supportingContent = { Text(stringResource(R.string.settings_allow_mixed_playback_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = allowMixedPlayback,
                                    onCheckedChange = onAllowMixedPlaybackChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }


            item {
                ExpandableHeader(
                    icon = Icons.Filled.Audiotrack,
                    title = stringResource(R.string.settings_audio_quality),
                    subtitleCollapsed = stringResource(R.string.settings_audio_quality_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = audioQualityExpanded,
                    onToggle = { audioQualityExpanded = !audioQualityExpanded },
                    arrowRotation = audioQualityArrowRotation
                )
            }

            // 展开区域
            item {
                AnimatedVisibility(
                    visible = audioQualityExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_netease_cloud_music),
                                    contentDescription = stringResource(R.string.settings_netease_quality),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.quality_netease_default)) },
                            supportingContent = {
                                Text(stringResource(R.string.common_label_value_format, qualityLabel, preferredQuality))
                            },
                            modifier = Modifier.settingsItemClickable { showQualityDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_youtube),
                                    contentDescription = stringResource(R.string.quality_youtube_default),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.quality_youtube_default)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.common_label_value_format,
                                        youtubeQualityLabel,
                                        youtubePreferredQuality
                                    )
                                )
                            },
                            modifier = Modifier.settingsItemClickable { showYouTubeQualityDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_bilibili),
                                    contentDescription = stringResource(R.string.settings_bili_quality),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.quality_bili_default)) },
                            supportingContent = {
                                Text(stringResource(R.string.common_label_value_format, biliQualityLabel, biliPreferredQuality))
                            },
                            modifier = Modifier.settingsItemClickable { showBiliQualityDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            item {
                ExpandableHeader(
                    icon = Icons.Outlined.SdStorage,
                    title = stringResource(R.string.settings_storage_cache),
                    subtitleCollapsed = stringResource(R.string.settings_storage_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = cacheExpanded,
                    onToggle = { cacheExpanded = !cacheExpanded },
                    arrowRotation = cacheArrowRotation
                )
            }

            item {
                AnimatedVisibility(
                    visible = cacheExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        // 缓存大小滑块
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_cache_limit)) },
                            supportingContent = {
                                // 计算当前 MB 值
                                val sizeMb = maxCacheSizeBytes / (1024 * 1024).toFloat()
                                // 本地状态，用于滑块流畅滑动
                                var sliderValue by remember(sizeMb) { mutableFloatStateOf(sizeMb) }

                                // 显示文本格式化：超过 1024MB 显示为 GB
                                val displaySize = if (sliderValue >= 1024) {
                                    context.getString(R.string.settings_cache_size_gb, sliderValue / 1024)
                                } else {
                                    context.getString(R.string.settings_cache_size_mb, sliderValue.toInt())
                                }

                                Column {
                                    Text(
                                        text = if (sliderValue < 10f) stringResource(R.string.settings_no_cache) else displaySize,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = sliderValue,
                                        onValueChange = { sliderValue = it },
                                        onValueChangeFinished = {
                                            // 转换为 Bytes 保存，如果小于 10MB 视为 0
                                            val newBytes = if (sliderValue < 10f) 0L else (sliderValue * 1024 * 1024).toLong()
                                            onMaxCacheSizeBytesChange(newBytes)
                                        },
                                        // 范围扩大到 10GB (10 * 1024 MB)
                                        valueRange = 0f..(10 * 1024f),
                                        steps = 0
                                    )
                                    Text(
                                        stringResource(R.string.settings_cache_notice),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        // 清除缓存按钮
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                            supportingContent = { Text(stringResource(R.string.settings_clear_cache_desc)) },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // 查看详情按钮
                                    OutlinedButton(onClick = {
                                        showStorageDetails = true
                                        // 计算存储占用
                                        val details = mutableMapOf<String, Long>()
                                        try {
                                            // 音频缓存
                                            val mediaCacheDir = File(context.cacheDir, "media_cache")
                                            details[context.getString(R.string.storage_type_audio_cache)] = mediaCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

                                            // 图片缓存
                                            val imageCacheDir = File(context.cacheDir, "image_cache")
                                            details[context.getString(R.string.storage_type_image_cache)] = imageCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

                                            // 下载的音乐
                                            val musicDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)?.let { File(it, "NeriPlayer") }
                                            details[context.getString(R.string.storage_type_downloaded_music)] = musicDir?.walkTopDown()?.filter { it.isFile && !it.name.endsWith(".downloading") }?.sumOf { it.length() } ?: 0L

                                            // 日志文件
                                            val logDir = context.getExternalFilesDir(null)?.let { File(it, "logs") }
                                            details[context.getString(R.string.storage_type_log_files)] = logDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

                                            // 崩溃日志
                                            val crashDir = context.getExternalFilesDir(null)?.let { File(it, "crashes") }
                                            details[context.getString(R.string.storage_type_crash_logs)] = crashDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

                                            // 其他缓存
                                            val otherCache = context.cacheDir.walkTopDown()
                                                .filter { it.isFile && !it.path.contains("media_cache") && !it.path.contains("image_cache") }
                                                .sumOf { it.length() }
                                            details[context.getString(R.string.storage_type_other_cache)] = otherCache

                                            // 应用数据
                                            val dataDir = context.filesDir
                                            val dataSize = dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                                            details[context.getString(R.string.storage_type_app_data)] = dataSize

                                        } catch (_: Exception) {
                                            details[context.getString(R.string.storage_type_error)] = 0L
                                        }
                                        storageDetails = details
                                    }) {
                                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.action_details))
                                    }

                                    // 清除按钮
                                    OutlinedButton(onClick = { showClearCacheDialog = true }) {
                                        Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.action_clear))
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            // 下载管理器
            item {
                ExpandableHeader(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.settings_download_management),
                    subtitleCollapsed = stringResource(R.string.settings_download_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = downloadManagerExpanded,
                    onToggle = { downloadManagerExpanded = !downloadManagerExpanded },
                    arrowRotation = downloadManagerArrowRotation
                )
            }

            // 展开区域
            item {
                AnimatedVisibility(
                    visible = downloadManagerExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        // 下载进度显示
                        val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()

                        batchDownloadProgress?.let { progress ->
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.settings_download_progress),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.download_progress)) },
                                supportingContent = {
                                    Text(stringResource(R.string.settings_download_songs_count, progress.completedSongs, progress.totalSongs))
                                },
                                trailingContent = {
                                    HapticTextButton(
                                        onClick = {
                                            AudioDownloadManager.cancelDownload()
                                        }
                                    ) {
                                        Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                modifier = Modifier.settingsItemClickable {
                                    onNavigateToDownloadManager()
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            // 进度条
                            LinearProgressIndicator(
                                progress = { (progress.percentage / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp)
                            )

                            if (progress.currentSong.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.settings_downloading, progress.currentSong),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }

                        if (batchDownloadProgress == null) {
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.settings_download_manager),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.download_title)) },
                                supportingContent = { Text(stringResource(R.string.download_desc)) },
                                modifier = Modifier.settingsItemClickable {
                                    onNavigateToDownloadManager()
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            }

            // 备份与恢复
            item {
                ExpandableHeader(
                    icon = Icons.Outlined.Backup,
                    title = stringResource(R.string.settings_backup_restore),
                    subtitleCollapsed = stringResource(R.string.settings_backup_expand),
                    subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
                    expanded = backupRestoreExpanded,
                    onToggle = { backupRestoreExpanded = !backupRestoreExpanded },
                    arrowRotation = backupRestoreArrowRotation
                )
            }

            // 展开区域
            item {
                AnimatedVisibility(
                    visible = backupRestoreExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                    ) {
                        // 当前歌单数量
                        val currentPlaylistCount = backupRestoreVm.getCurrentPlaylistCount(context)
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    contentDescription = stringResource(R.string.settings_current_playlist),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.playlist_count)) },
                            supportingContent = { Text(stringResource(R.string.playlist_count_format, currentPlaylistCount)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        // 导出歌单
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Upload,
                                    contentDescription = stringResource(R.string.settings_export_playlist),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.playlist_export)) },
                            supportingContent = { Text(stringResource(R.string.playlist_export_desc)) },
                            modifier = Modifier.settingsItemClickable {
                                if (!backupRestoreUiState.isExporting) {
                                    exportPlaylistLauncher.launch(backupRestoreVm.generateBackupFileName())
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        // 导入歌单
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Download,
                                    contentDescription = stringResource(R.string.settings_import_playlist),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.playlist_import)) },
                            supportingContent = { Text(stringResource(R.string.playlist_import_desc)) },
                            modifier = Modifier.settingsItemClickable {
                                if (!backupRestoreUiState.isImporting) {
                                    importPlaylistLauncher.launch(arrayOf("*/*"))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        // 导出进度
                        backupRestoreUiState.exportProgress?.let { progress ->
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.playlist_export_progress)) },
                                supportingContent = { Text(progress) },
                                trailingContent = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }

                        // 导入进度
                        backupRestoreUiState.importProgress?.let { progress ->
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.playlist_import_progress)) },
                                supportingContent = { Text(progress) },
                                trailingContent = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }

                        // 分析进度
                        backupRestoreUiState.analysisProgress?.let { progress ->
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.sync_analysis_progress)) },
                                supportingContent = { Text(progress) },
                                trailingContent = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }

                        // 导出结果
                        AnimatedVisibility(
                            visible = backupRestoreUiState.lastExportMessage != null,
                            enter = slideInVertically(
                                initialOffsetY = { -it },
                                animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)
                            ) + fadeIn(
                                animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)
                            ),
                            exit = slideOutVertically(
                                targetOffsetY = { -it },
                                animationSpec = tween(durationMillis = 250, easing = EaseInCubic)
                            ) + fadeOut(
                                animationSpec = tween(durationMillis = 250, easing = EaseInCubic)
                            )
                        ) {
                            backupRestoreUiState.lastExportMessage?.let { message ->
                                val isSuccess = backupRestoreUiState.lastExportSuccess == true
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSuccess) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.errorContainer,
                                    tonalElevation = 2.dp
                                ) {
                                    ListItem(
                                        headlineContent = { Text(if (isSuccess) stringResource(R.string.settings_export_success) else stringResource(R.string.settings_export_failed)) },
                                        supportingContent = { Text(message) },
                                        trailingContent = {
                                            HapticTextButton(
                                                onClick = { backupRestoreVm.clearExportStatus() }
                                            ) {
                                                Text(stringResource(R.string.action_close), color = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        colors = ListItemDefaults.colors(
                                            containerColor = Color.Transparent
                                        )
                                    )
                                }
                            }
                        }

                        // 导入结果
                        AnimatedVisibility(
                            visible = backupRestoreUiState.lastImportMessage != null,
                            enter = slideInVertically(
                                initialOffsetY = { -it },
                                animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)
                            ) + fadeIn(
                                animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)
                            ),
                            exit = slideOutVertically(
                                targetOffsetY = { -it },
                                animationSpec = tween(durationMillis = 250, easing = EaseInCubic)
                            ) + fadeOut(
                                animationSpec = tween(durationMillis = 250, easing = EaseInCubic)
                            )
                        ) {
                            backupRestoreUiState.lastImportMessage?.let { message ->
                                val isSuccess = backupRestoreUiState.lastImportSuccess == true
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSuccess) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.errorContainer,
                                    tonalElevation = 2.dp
                                ) {
                                    ListItem(
                                        headlineContent = { Text(if (isSuccess) stringResource(R.string.settings_import_success) else stringResource(R.string.settings_import_failed)) },
                                        supportingContent = { Text(message) },
                                        trailingContent = {
                                            HapticTextButton(
                                                onClick = { backupRestoreVm.clearImportStatus() }
                                            ) {
                                                Text(stringResource(R.string.action_close), color = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        colors = ListItemDefaults.colors(
                                            containerColor = Color.Transparent
                                        )
                                    )
                                }
                            }
                        }

                        // GitHub自动同步
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        val githubVm: GitHubSyncViewModel = viewModel()
                        val githubState by githubVm.uiState.collectAsState()

                        LaunchedEffect(Unit) {
                            githubVm.initialize(context)
                        }

                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.CloudSync,
                                    contentDescription = stringResource(R.string.github_auto_sync),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.github_auto_sync)) },
                            supportingContent = {
                                Text(if (githubState.isConfigured) stringResource(R.string.settings_configured) else stringResource(R.string.settings_not_configured))
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        if (!githubState.isConfigured) {
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Settings,
                                        contentDescription = stringResource(R.string.settings_configure),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.sync_config)) },
                                supportingContent = { Text(stringResource(R.string.sync_config_desc)) },
                                modifier = Modifier.settingsItemClickable {
                                    showGitHubConfigDialog = true
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        } else {
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Sync,
                                        contentDescription = stringResource(R.string.settings_auto_sync),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.sync_auto)) },
                                supportingContent = { Text(stringResource(R.string.sync_auto_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = githubState.autoSyncEnabled,
                                        onCheckedChange = { githubVm.toggleAutoSync(context, it) }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.CloudUpload,
                                        contentDescription = stringResource(R.string.settings_sync_now),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.sync_now)) },
                                supportingContent = {
                                    if (githubState.lastSyncTime > 0) {
                                        Text(stringResource(R.string.sync_last_time, formatSyncTime(githubState.lastSyncTime)))
                                    } else {
                                        Text(stringResource(R.string.sync_not_synced))
                                    }
                                },
                                trailingContent = {
                                    if (githubState.isSyncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        HapticTextButton(onClick = { githubVm.performSync(context) }) {
                                            Text(stringResource(R.string.sync_title))
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            // 播放历史更新频率设置
                            var showPlayHistoryModeDialog by remember { mutableStateOf(false) }
                            val storage = remember { SecureTokenStorage(context) }
                            val currentMode = remember { mutableStateOf(storage.getPlayHistoryUpdateMode()) }

                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Timer,
                                        contentDescription = stringResource(R.string.settings_play_history_update_freq),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.sync_history_frequency)) },
                                supportingContent = {
                                    Text(
                                        when (currentMode.value) {
                                            SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE -> stringResource(R.string.settings_update_immediate)
                                            SecureTokenStorage.PlayHistoryUpdateMode.BATCHED -> stringResource(R.string.settings_sync_batch_update_time)
                                        }
                                    )
                                },
                                modifier = Modifier.settingsItemClickable {
                                    showPlayHistoryModeDialog = true
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            // 播放历史更新频率选择对话框
                            if (showPlayHistoryModeDialog) {
                                AlertDialog(
                                    onDismissRequest = { showPlayHistoryModeDialog = false },
                                    title = { Text(stringResource(R.string.sync_history_frequency)) },
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.sync_frequency_desc), style = MaterialTheme.typography.bodyMedium)
                                            Spacer(modifier = Modifier.height(16.dp))

                                            // 立即更新选项
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        storage.setPlayHistoryUpdateMode(SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE)
                                                        currentMode.value = SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE
                                                        showPlayHistoryModeDialog = false
                                                    }
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = currentMode.value == SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE,
                                                    onClick = {
                                                        storage.setPlayHistoryUpdateMode(SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE)
                                                        currentMode.value = SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE
                                                        showPlayHistoryModeDialog = false
                                                    }
                                                )
                                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                                    Text(stringResource(R.string.sync_after_play), style = MaterialTheme.typography.bodyLarge)
                                                    Text(stringResource(R.string.sync_after_play_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }

                                            // 批量更新选项
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        storage.setPlayHistoryUpdateMode(SecureTokenStorage.PlayHistoryUpdateMode.BATCHED)
                                                        currentMode.value = SecureTokenStorage.PlayHistoryUpdateMode.BATCHED
                                                        showPlayHistoryModeDialog = false
                                                    }
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = currentMode.value == SecureTokenStorage.PlayHistoryUpdateMode.BATCHED,
                                                    onClick = {
                                                        storage.setPlayHistoryUpdateMode(SecureTokenStorage.PlayHistoryUpdateMode.BATCHED)
                                                        currentMode.value = SecureTokenStorage.PlayHistoryUpdateMode.BATCHED
                                                        showPlayHistoryModeDialog = false
                                                    }
                                                )
                                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                                    Text(stringResource(R.string.sync_batch_update), style = MaterialTheme.typography.bodyLarge)
                                                    Text(stringResource(R.string.sync_batch_update_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        HapticTextButton(onClick = { showPlayHistoryModeDialog = false }) {
                                            Text(stringResource(R.string.action_close))
                                        }
                                    }
                                )
                            }

                            // 省流模式开关
                            var dataSaverMode by remember { mutableStateOf(storage.isDataSaverMode()) }
                            var pendingDataSaverMode by remember { mutableStateOf<Boolean?>(null) }

                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.settings_data_saver),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.sync_data_saver)) },
                                supportingContent = { Text(stringResource(R.string.sync_data_saver_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = dataSaverMode,
                                        onCheckedChange = { enabled ->
                                            if (enabled != dataSaverMode) {
                                                pendingDataSaverMode = enabled
                                            }
                                        }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            if (pendingDataSaverMode != null) {
                                AlertDialog(
                                    onDismissRequest = { pendingDataSaverMode = null },
                                    title = {
                                        Text(stringResource(R.string.sync_data_saver_warning_title))
                                    },
                                    text = {
                                        Text(stringResource(R.string.sync_data_saver_warning_message))
                                    },
                                    confirmButton = {
                                        HapticTextButton(
                                            onClick = {
                                                val enabled = pendingDataSaverMode ?: return@HapticTextButton
                                                dataSaverMode = enabled
                                                storage.setDataSaverMode(enabled)
                                                pendingDataSaverMode = null
                                            }
                                        ) {
                                            Text(stringResource(R.string.sync_data_saver_warning_confirm))
                                        }
                                    },
                                    dismissButton = {
                                        HapticTextButton(onClick = { pendingDataSaverMode = null }) {
                                            Text(stringResource(R.string.action_cancel))
                                        }
                                    }
                                )
                            }

                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Error,
                                        contentDescription = stringResource(R.string.github_sync_silent_failure),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.github_sync_silent_failure)) },
                                supportingContent = { Text(stringResource(R.string.github_sync_silent_failure_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = silentGitHubSyncFailure,
                                        onCheckedChange = onSilentGitHubSyncFailureChange
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            HapticTextButton(
                                onClick = { showClearGitHubConfigDialog = true },
                                modifier = Modifier.padding(start = 16.dp)
                            ) {
                                Text(stringResource(R.string.settings_clear_config), color = MaterialTheme.colorScheme.error)
                            }
                        }

                        // GitHub错误消息
                        githubState.errorMessage?.let { error ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { githubVm.clearMessages() }) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_close))
                                    }
                                }
                            }
                        }

                        // GitHub成功消息
                        githubState.successMessage?.let { message ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { githubVm.clearMessages() }) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_close))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 关于
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.settings_about),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.nav_about), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(stringResource(R.string.about_app_footer)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // Build UUID
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Verified,
                            contentDescription = stringResource(R.string.settings_build_uuid),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_build_uuid), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(BuildConfig.BUILD_UUID) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // 版本
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Update,
                            contentDescription = stringResource(R.string.settings_version),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.common_version), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        val suffix = if (devModeEnabled) {
                            " (${stringResource(R.string.settings_version_debug_suffix)})"
                        } else {
                            ""
                        }
                        Text("${BuildConfig.VERSION_NAME}$suffix")
                    },
                    modifier = Modifier.settingsItemClickable {
                        if (!devModeEnabled) {
                            versionTapCount++
                            if (versionTapCount >= 7) {
                                onDevModeChange(true)
                                inlineMsg = context.getString(R.string.debug_mode_opened)
                                versionTapCount = 0
                            }
                        } else {
                            inlineMsg = context.getString(R.string.debug_mode_enabled)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // 编译时间
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = stringResource(R.string.settings_build_time),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.common_build_time), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(convertTimestampToDate(BuildConfig.BUILD_TIMESTAMP)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // GitHub
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = stringResource(R.string.common_github),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.common_github)) },
                    supportingContent = { Text(stringResource(R.string.settings_github_repo_url)) },
                    modifier = Modifier.settingsItemClickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/cwuom/NeriPlayer".toUri()
                        )
                        context.startActivity(intent)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }

    if (showBiliQualityDialog) {
        AlertDialog(
            onDismissRequest = { showBiliQualityDialog = false },
            title = { Text(stringResource(R.string.quality_bili_default)) },
            text = {
                Column {
                    val options = listOf(
                        "dolby" to stringResource(R.string.settings_dolby),
                        "hires" to stringResource(R.string.quality_hires),
                        "lossless" to stringResource(R.string.quality_lossless),
                        "high" to stringResource(R.string.settings_audio_quality_high),
                        "medium" to stringResource(R.string.settings_audio_quality_medium),
                        "low" to stringResource(R.string.settings_audio_quality_low)
                    )
                    options.forEach { (level, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = {
                                if (level == biliPreferredQuality) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.common_selected),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.settingsItemClickable {
                                onBiliQualityChange(level)
                                showBiliQualityDialog = false
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showBiliQualityDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    // 网易云登录窗
    if (showNeteaseSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        // “发送验证码” 确认对话框
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text(stringResource(R.string.login_confirm_send_code)) },
                text = { Text(stringResource(R.string.login_send_code_to, confirmPhoneMasked ?: "")) },
                confirmButton = {
                    HapticTextButton(onClick = {
                        showConfirmDialog = false
                        neteaseVm.sendCaptcha(ctcode = "86")
                    }) { Text(stringResource(R.string.action_send)) }
                },
                dismissButton = {
                    HapticTextButton(onClick = {
                        showConfirmDialog = false
                        inlineMsg = context.getString(R.string.sync_send_cancelled)
                    }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }

        // 0: 浏览器登录 1: 粘贴Cookie 2: 验证码登录
        var selectedTab by remember(neteaseSheetInitialTab) {
            mutableIntStateOf(neteaseSheetInitialTab)
        }
        var rawCookie by remember { mutableStateOf("") }

        // WebView 登录回调
        val webLoginLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val json = result.data?.getStringExtra(NeteaseWebLoginActivity.RESULT_COOKIE) ?: "{}"
                val map = org.json.JSONObject(json).let { obj ->
                    val it = obj.keys()
                    val m = linkedMapOf<String, String>()
                    while (it.hasNext()) {
                        val k = it.next()
                        m[k] = obj.optString(k, "")
                    }
                    m
                }
                // 保存
                neteaseVm.importCookiesFromMap(map)
            } else {
                inlineMsg = context.getString(R.string.settings_cookie_cancelled)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showNeteaseSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 48.dp, top = 12.dp)
            ) {
                Column {
                    Text(text = stringResource(R.string.login_netease), style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))

                    // 内嵌提示条
                    AnimatedVisibility(visible = inlineMsg != null, enter = fadeIn(), exit = fadeOut()) {
                        InlineMessage(
                            text = inlineMsg ?: "",
                            onClose = { inlineMsg = null }
                        )
                    }

                    androidx.compose.material3.PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.login_browser)) })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.login_paste_cookie)) })
                        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(stringResource(R.string.login_verification_code)) })
                    }

                    Spacer(Modifier.height(12.dp))

                    when (selectedTab) {
                        0 -> {
                            Text(
                                stringResource(R.string.settings_netease_login_browser_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            HapticButton(onClick = {
                                inlineMsg = null
                                webLoginLauncher.launch(
                                    Intent(
                                        context,
                                        NeteaseWebLoginActivity::class.java
                                    )
                                )
                            }) {
                                Text(stringResource(R.string.login_start_browser))
                            }
                        }

                        1 -> {
                            OutlinedTextField(
                                value = rawCookie,
                                onValueChange = { rawCookie = it },
                                label = { Text(stringResource(R.string.login_paste_cookie_hint)) },
                                minLines = 6,
                                maxLines = 10,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            HapticButton(onClick = {
                                if (rawCookie.isBlank()) {
                                    inlineMsg = context.getString(R.string.settings_cookie_input_hint)
                                } else {
                                    neteaseVm.importCookiesFromRaw(rawCookie)
                                }
                            }) { Text(stringResource(R.string.login_save_cookie)) }
                        }

                        2 -> {
                            NeteaseLoginContent(
                                vm = neteaseVm
                            )
                        }
                    }
                }
            }
        }
    }

    neteaseReauthHealth?.let { health ->
        if (showNeteaseReauthDialog) {
            val title = when (health.state) {
                SavedCookieAuthState.Missing -> stringResource(R.string.settings_netease_reauth_required_title)
                SavedCookieAuthState.Expired -> stringResource(R.string.settings_netease_reauth_expired_title)
                SavedCookieAuthState.Stale -> stringResource(R.string.settings_netease_reauth_stale_title)
                SavedCookieAuthState.Valid,
                SavedCookieAuthState.Checking -> stringResource(R.string.settings_netease)
            }
            val message = when (health.state) {
                SavedCookieAuthState.Missing -> stringResource(R.string.settings_netease_reauth_required_message)
                SavedCookieAuthState.Expired -> stringResource(R.string.settings_netease_reauth_expired_message)
                SavedCookieAuthState.Stale -> {
                    val savedAtLabel = health.savedAt
                        .takeIf { it > 0L }
                        ?.let { convertTimestampToDate(it) }
                        ?: stringResource(R.string.settings_netease_reauth_unknown_time)
                    stringResource(
                        R.string.settings_netease_reauth_stale_message,
                        (NETEASE_AUTH_STALE_AFTER_MS / (24L * 60L * 60L * 1000L)).toInt(),
                        savedAtLabel
                    )
                }
                SavedCookieAuthState.Valid,
                SavedCookieAuthState.Checking -> ""
            }
            AlertDialog(
                onDismissRequest = {
                    showNeteaseReauthDialog = false
                    neteaseReauthHealth = null
                },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    HapticTextButton(onClick = {
                        showNeteaseReauthDialog = false
                        neteaseReauthHealth = null
                        inlineMsg = null
                        neteaseSheetInitialTab = 0
                        showNeteaseSheet = true
                    }) {
                        Text(stringResource(R.string.settings_netease_reauth_action_login))
                    }
                },
                dismissButton = {
                    Row {
                        HapticTextButton(onClick = {
                            showNeteaseReauthDialog = false
                            neteaseReauthHealth = null
                            inlineMsg = null
                            neteaseSheetInitialTab = 1
                            showNeteaseSheet = true
                        }) {
                            Text(stringResource(R.string.settings_netease_reauth_action_import))
                        }
                        HapticTextButton(onClick = {
                            showNeteaseReauthDialog = false
                            neteaseReauthHealth = null
                        }) {
                            Text(stringResource(R.string.action_later))
                        }
                    }
                }
            )
        }
    }

    if (showBiliSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var selectedTab by remember(biliSheetInitialTab) {
            mutableIntStateOf(biliSheetInitialTab)
        }
        var rawBiliCookie by remember { mutableStateOf("") }

        ModalBottomSheet(
            onDismissRequest = { showBiliSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 48.dp, top = 12.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.platform_bilibili),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AnimatedVisibility(visible = inlineMsg != null, enter = fadeIn(), exit = fadeOut()) {
                        InlineMessage(
                            text = inlineMsg ?: "",
                            onClose = { inlineMsg = null }
                        )
                    }

                    androidx.compose.material3.PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text(stringResource(R.string.login_browser)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text(stringResource(R.string.login_paste_cookie)) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    when (selectedTab) {
                        0 -> {
                            Text(
                                stringResource(R.string.settings_bili_login_browser_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            HapticButton(onClick = {
                                inlineMsg = null
                                biliWebLoginLauncher.launch(
                                    Intent(context, moe.ouom.neriplayer.activity.BiliWebLoginActivity::class.java)
                                )
                            }) {
                                Text(stringResource(R.string.login_start_browser))
                            }
                        }

                        1 -> {
                            OutlinedTextField(
                                value = rawBiliCookie,
                                onValueChange = { rawBiliCookie = it },
                                label = { Text(stringResource(R.string.login_paste_bili_cookie_hint)) },
                                minLines = 6,
                                maxLines = 10,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            HapticButton(onClick = {
                                if (rawBiliCookie.isBlank()) {
                                    inlineMsg = context.getString(R.string.auth_cookie_empty)
                                } else {
                                    biliVm.importCookiesFromRaw(rawBiliCookie)
                                }
                            }) {
                                Text(stringResource(R.string.login_save_cookie))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBiliCookieDialog) {
        AlertDialog(
            onDismissRequest = { showBiliCookieDialog = false },
            title = { Text(stringResource(R.string.settings_bili_login_success)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = biliCookieText.ifBlank { stringResource(R.string.settings_empty_placeholder) },
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showBiliCookieDialog = false }) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }

    if (showYouTubeQualityDialog) {
        AlertDialog(
            onDismissRequest = { showYouTubeQualityDialog = false },
            title = { Text(stringResource(R.string.quality_youtube_default)) },
            text = {
                Column {
                    val options = listOf(
                        "low" to stringResource(R.string.settings_audio_quality_standard),
                        "medium" to stringResource(R.string.settings_audio_quality_medium),
                        "high" to stringResource(R.string.settings_audio_quality_high),
                        "very_high" to stringResource(R.string.quality_very_high)
                    )
                    options.forEach { (level, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = {
                                if (level == youtubePreferredQuality) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.common_selected),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.settingsItemClickable {
                                onYouTubeQualityChange(level)
                                showYouTubeQualityDialog = false
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showYouTubeQualityDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    biliReauthHealth?.let { health ->
        if (showBiliReauthDialog) {
            val title = when (health.state) {
                SavedCookieAuthState.Missing -> stringResource(R.string.settings_bili_reauth_required_title)
                SavedCookieAuthState.Expired -> stringResource(R.string.settings_bili_reauth_expired_title)
                SavedCookieAuthState.Stale -> stringResource(R.string.settings_bili_reauth_stale_title)
                SavedCookieAuthState.Valid,
                SavedCookieAuthState.Checking -> stringResource(R.string.platform_bilibili)
            }
            val message = when (health.state) {
                SavedCookieAuthState.Missing -> stringResource(R.string.settings_bili_reauth_required_message)
                SavedCookieAuthState.Expired -> stringResource(R.string.settings_bili_reauth_expired_message)
                SavedCookieAuthState.Stale -> {
                    val savedAtLabel = health.savedAt
                        .takeIf { it > 0L }
                        ?.let { convertTimestampToDate(it) }
                        ?: stringResource(R.string.settings_bili_reauth_unknown_time)
                    stringResource(
                        R.string.settings_bili_reauth_stale_message,
                        (BILI_AUTH_STALE_AFTER_MS / (24L * 60L * 60L * 1000L)).toInt(),
                        savedAtLabel
                    )
                }
                SavedCookieAuthState.Valid,
                SavedCookieAuthState.Checking -> ""
            }
            AlertDialog(
                onDismissRequest = {
                    showBiliReauthDialog = false
                    biliReauthHealth = null
                },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    HapticTextButton(onClick = {
                        showBiliReauthDialog = false
                        biliReauthHealth = null
                        inlineMsg = null
                        biliSheetInitialTab = 0
                        showBiliSheet = true
                    }) {
                        Text(stringResource(R.string.settings_bili_reauth_action_login))
                    }
                },
                dismissButton = {
                    Row {
                        HapticTextButton(onClick = {
                            showBiliReauthDialog = false
                            biliReauthHealth = null
                            inlineMsg = null
                            biliSheetInitialTab = 1
                            showBiliSheet = true
                        }) {
                            Text(stringResource(R.string.settings_bili_reauth_action_import))
                        }
                        HapticTextButton(onClick = {
                            showBiliReauthDialog = false
                            biliReauthHealth = null
                        }) {
                            Text(stringResource(R.string.action_later))
                        }
                    }
                }
            )
        }
    }

    if (showYouTubeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var selectedTab by remember(youtubeSheetInitialTab) {
            mutableIntStateOf(youtubeSheetInitialTab)
        }
        var rawYouTubeCookie by remember { mutableStateOf("") }

        ModalBottomSheet(
            onDismissRequest = { showYouTubeSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 48.dp, top = 12.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.common_youtube),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AnimatedVisibility(visible = inlineMsg != null, enter = fadeIn(), exit = fadeOut()) {
                        InlineMessage(
                            text = inlineMsg ?: "",
                            onClose = { inlineMsg = null }
                        )
                    }

                    androidx.compose.material3.PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text(stringResource(R.string.login_browser)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text(stringResource(R.string.login_paste_cookie)) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    when (selectedTab) {
                        0 -> {
                            Text(
                                stringResource(R.string.settings_youtube_login_browser_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.settings_youtube_login_browser_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            HapticButton(onClick = {
                                inlineMsg = null
                                youtubeWebLoginLauncher.launch(
                                    Intent(context, YouTubeWebLoginActivity::class.java)
                                )
                            }) {
                                Text(stringResource(R.string.login_start_browser))
                            }
                        }

                        1 -> {
                            OutlinedTextField(
                                value = rawYouTubeCookie,
                                onValueChange = { rawYouTubeCookie = it },
                                label = { Text(stringResource(R.string.login_paste_youtube_cookie_hint)) },
                                minLines = 6,
                                maxLines = 10,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            HapticButton(onClick = {
                                if (rawYouTubeCookie.isBlank()) {
                                    inlineMsg = context.getString(R.string.auth_cookie_empty)
                                } else {
                                    youtubeVm.importCookiesFromRaw(rawYouTubeCookie)
                                }
                            }) {
                                Text(stringResource(R.string.login_save_cookie))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showYouTubeCookieDialog) {
        AlertDialog(
            onDismissRequest = { showYouTubeCookieDialog = false },
            title = { Text(stringResource(R.string.settings_youtube_login_success)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = youtubeCookieText.ifBlank { stringResource(R.string.settings_empty_placeholder) },
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showYouTubeCookieDialog = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }



    // 音质选择对话框
    if (showDefaultStartDestinationDialog) {
        AlertDialog(
            onDismissRequest = { showDefaultStartDestinationDialog = false },
            title = { Text(stringResource(R.string.settings_default_start_screen)) },
            text = {
                Column {
                    val options = listOfNotNull(
                        ("home" to stringResource(R.string.nav_home)).takeUnless { !homeStartAvailable },
                        "explore" to stringResource(R.string.nav_explore),
                        "library" to stringResource(R.string.nav_library),
                        "settings" to stringResource(R.string.nav_settings)
                    )
                    options.forEach { (route, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = {
                                RadioButton(
                                    selected = route == effectiveDefaultStartDestination,
                                    onClick = null
                                )
                            },
                            modifier = Modifier.settingsItemClickable {
                                onDefaultStartDestinationChange(route)
                                showDefaultStartDestinationDialog = false
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showDefaultStartDestinationDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    // 音质选择对话框
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text(stringResource(R.string.quality_default)) },
            text = {
                Column {
                    val qualityOptions = listOf(
                        "standard" to stringResource(R.string.quality_standard),
                        "higher" to stringResource(R.string.quality_high),
                        "exhigh" to stringResource(R.string.quality_very_high),
                        "lossless" to stringResource(R.string.quality_lossless),
                        "hires" to stringResource(R.string.quality_hires),
                        "jyeffect" to stringResource(R.string.quality_hd_surround),
                        "sky" to stringResource(R.string.quality_surround),
                        "jymaster" to stringResource(R.string.quality_hires)
                    )
                    qualityOptions.forEach { (level, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = {
                                if (level == preferredQuality) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.common_selected),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.settingsItemClickable {
                                onQualityChange(level)
                                showQualityDialog = false
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showQualityDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    // Cookies 展示对话框
    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text(stringResource(R.string.login_success)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(cookieScroll)
                ) {
                    Text(
                        text = cookieText.ifBlank { stringResource(R.string.settings_empty_placeholder) },
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showCookieDialog = false }) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }

    // 颜色选择对话框
    if (showColorPickerDialog) {
        ColorPickerDialog(
            currentHex = seedColorHex,
            palette = themeColorPalette,
            onDismiss = { showColorPickerDialog = false },
            onColorSelected = { hex ->
                onSeedColorChange(hex)
                showColorPickerDialog = false
            },
            onAddColor = onAddColorToPalette,
            onRemoveColor = onRemoveColorFromPalette
        )
    }

    if (showDpiDialog) {
        DpiSettingDialog(
            currentScale = uiDensityScale,
            onDismiss = { showDpiDialog = false },
            onApply = { newScale ->
                onUiDensityScaleChange(newScale)
                showDpiDialog = false
            }
        )
    }

    // GitHub配置对话框
    if (showGitHubConfigDialog) {
        val githubVm: GitHubSyncViewModel = viewModel()
        val githubState by githubVm.uiState.collectAsState()
        var githubToken by remember { mutableStateOf("") }
        var githubRepoName by remember { mutableStateOf("neriplayer-backup") }
        var useExistingRepo by remember { mutableStateOf(false) }
        var existingRepoName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showGitHubConfigDialog = false },
            title = { Text(stringResource(R.string.sync_config)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(stringResource(R.string.sync_step1_token), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = githubToken,
                        onValueChange = { githubToken = it },
                        label = { Text(stringResource(R.string.settings_github_token_label)) },
                        placeholder = { Text(stringResource(R.string.settings_github_token_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_github_token_permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                "https://github.com/settings/tokens/new?scopes=repo&description=NeriPlayer%20Backup".toUri()
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(R.string.sync_create_token))
                    }

                    if (githubState.tokenValid) {
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.sync_step2_repo), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = !useExistingRepo,
                                onClick = { useExistingRepo = false }
                            )
                            Text(stringResource(R.string.sync_create_new_repo))
                        }

                        if (!useExistingRepo) {
                            OutlinedTextField(
                                value = githubRepoName,
                                onValueChange = { githubRepoName = it },
                                label = { Text(stringResource(R.string.sync_repo_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = useExistingRepo,
                                onClick = { useExistingRepo = true }
                            )
                            Text(stringResource(R.string.sync_use_existing_repo))
                        }

                        if (useExistingRepo) {
                            OutlinedTextField(
                                value = existingRepoName,
                                onValueChange = { existingRepoName = it },
                                label = { Text(stringResource(R.string.sync_repo_full_name)) },
                                placeholder = { Text(stringResource(R.string.settings_sync_repo_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!githubState.tokenValid) {
                    HapticButton(
                        onClick = { githubVm.validateToken(context, githubToken) },
                        enabled = githubToken.isNotBlank() && !githubState.isValidating
                    ) {
                        if (githubState.isValidating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.sync_verify_token))
                    }
                } else {
                    HapticButton(
                        onClick = {
                            if (useExistingRepo) {
                                githubVm.useExistingRepository(context, existingRepoName)
                            } else {
                                githubVm.createRepository(context, githubRepoName)
                            }
                            showGitHubConfigDialog = false
                        },
                        enabled = !githubState.isCreatingRepo && !githubState.isCheckingRepo
                    ) {
                        if (githubState.isCreatingRepo || githubState.isCheckingRepo) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.action_done))
                    }
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showGitHubConfigDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 清除 GitHub 配置对话框
    if (showClearGitHubConfigDialog) {
        val githubVm: GitHubSyncViewModel = viewModel()

        AlertDialog(
            onDismissRequest = { showClearGitHubConfigDialog = false },
            title = { Text(stringResource(R.string.sync_clear_config)) },
            text = { Text(stringResource(R.string.sync_clear_config_desc)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        githubVm.clearConfiguration(context)
                        showClearGitHubConfigDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_confirm_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showClearGitHubConfigDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showStorageDetails) {
        AlertDialog(
            onDismissRequest = { showStorageDetails = false },
            title = { Text(stringResource(R.string.storage_details_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.storage_details_subtitle), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))

                    storageDetails.forEach { (name, size) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatFileSize(size),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.storage_details_total), style = MaterialTheme.typography.titleSmall)
                        Text(
                            formatFileSize(storageDetails.values.sum()),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HapticTextButton(onClick = {
                        // 打开系统应用详情页面
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = "package:${context.packageName}".toUri()
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // 忽略错误
                        }
                    }) {
                        Text(stringResource(R.string.storage_open_system_settings))
                    }
                    HapticTextButton(onClick = { showStorageDetails = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.settings_confirm_clear_cache)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_clear_cache_warning))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.settings_select_cache_types),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    // 音频缓存选项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { clearAudioCache = !clearAudioCache }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = clearAudioCache,
                            onCheckedChange = { clearAudioCache = it }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                stringResource(R.string.settings_audio_cache),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_audio_cache_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 图片缓存选项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { clearImageCache = !clearImageCache }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = clearImageCache,
                            onCheckedChange = { clearImageCache = it }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                stringResource(R.string.settings_image_cache),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_image_cache_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        onClearCacheClick(clearAudioCache, clearImageCache)
                        showClearCacheDialog = false
                    },
                    enabled = clearAudioCache || clearImageCache
                ) {
                    Text(
                        stringResource(R.string.action_confirm_clear),
                        color = if (clearAudioCache || clearImageCache)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showClearCacheDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showListenTogetherResetUuidDialog) {
        AlertDialog(
            onDismissRequest = { showListenTogetherResetUuidDialog = false },
            title = { Text(stringResource(R.string.listen_together_reset_uuid)) },
            text = { Text(stringResource(R.string.listen_together_reset_uuid_confirm)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        scope.launch {
                            listenTogetherPreferences.resetUserUuid()
                            showListenTogetherResetUuidDialog = false
                            Toast.makeText(
                                context,
                                context.getString(R.string.listen_together_reset_uuid_done),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showListenTogetherResetUuidDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showListenTogetherServerDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!listenTogetherServerTesting) {
                    showListenTogetherServerDialog = false
                    listenTogetherServerInput = listenTogetherWorkerBaseUrl
                    listenTogetherServerTestMessage = null
                }
            },
            title = { Text(stringResource(R.string.settings_listen_together_server_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (listenTogetherServerInput.isBlank() ||
                            isDefaultListenTogetherBaseUrl(resolveListenTogetherBaseUrl(listenTogetherServerInput))
                        ) {
                            stringResource(R.string.settings_listen_together_server_default_desc)
                        } else {
                            stringResource(R.string.settings_listen_together_server_custom_desc)
                        }
                    )
                    OutlinedTextField(
                        value = listenTogetherServerInput,
                        onValueChange = {
                            listenTogetherServerInput = it
                            listenTogetherServerTestMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_listen_together_server_input_label)) },
                        placeholder = { Text(stringResource(R.string.settings_listen_together_server_input_placeholder)) }
                    )
                    if (listenTogetherServerTesting) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.settings_listen_together_server_testing),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        listenTogetherServerTestMessage?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    listenTogetherServerTesting = true
                                    val usingDefaultServer = listenTogetherServerInput.isBlank() ||
                                        isDefaultListenTogetherBaseUrl(resolveListenTogetherBaseUrl(listenTogetherServerInput))
                                    val result = listenTogetherApi.testServerAvailability(
                                        resolveListenTogetherBaseUrl(listenTogetherServerInput)
                                    )
                                    listenTogetherServerTesting = false
                                    listenTogetherServerTestMessage = when {
                                        result.ok && usingDefaultServer ->
                                            context.getString(R.string.settings_listen_together_server_test_success_default)
                                        result.ok ->
                                            context.getString(R.string.settings_listen_together_server_test_success_custom)
                                        result.message == "invalid_response" ->
                                            context.getString(R.string.settings_listen_together_server_test_invalid)
                                        else ->
                                            context.getString(
                                                R.string.settings_listen_together_server_test_failed,
                                                result.message
                                            )
                                    }
                                }
                            },
                            enabled = !listenTogetherServerTesting
                        ) {
                            Text(stringResource(R.string.settings_listen_together_server_test))
                        }
                        TextButton(
                            onClick = {
                                listenTogetherServerInput = ""
                                listenTogetherServerTestMessage = context.getString(
                                    R.string.settings_listen_together_server_reset_done
                                )
                            },
                            enabled = !listenTogetherServerTesting
                        ) {
                            Text(stringResource(R.string.action_reset))
                        }
                    }
                }
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        scope.launch {
                            val normalizedInput = listenTogetherServerInput.trim()
                            listenTogetherPreferences.setWorkerBaseUrl(normalizedInput)
                            listenTogetherServerInput = normalizedInput
                            showListenTogetherServerDialog = false
                            listenTogetherServerTestMessage = null
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_listen_together_server_saved),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !listenTogetherServerTesting
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                HapticTextButton(
                    onClick = {
                        showListenTogetherServerDialog = false
                        listenTogetherServerInput = listenTogetherWorkerBaseUrl
                        listenTogetherServerTestMessage = null
                    },
                    enabled = !listenTogetherServerTesting
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun ListenTogetherSettingsSection(
    modifier: Modifier = Modifier,
    isUsingDefaultServer: Boolean,
    isInRoom: Boolean,
    testing: Boolean,
    testMessage: String?,
    onOpenServerDialog: () -> Unit,
    onResetIdentity: () -> Unit
) {
    val identityItemModifier = if (isInRoom) {
        Modifier.alpha(0.5f)
    } else {
        Modifier.settingsItemClickable(onClick = onResetIdentity)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ListItem(
            modifier = Modifier.settingsItemClickable(onClick = onOpenServerDialog),
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = stringResource(R.string.settings_listen_together_server_title),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_listen_together_server_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isUsingDefaultServer) {
                            stringResource(R.string.settings_listen_together_server_default_desc)
                        } else {
                            stringResource(R.string.settings_listen_together_server_custom_desc)
                        }
                    )
                    testMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            trailingContent = {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            modifier = identityItemModifier,
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = stringResource(R.string.listen_together_reset_uuid),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.listen_together_reset_uuid)) },
            supportingContent = {
                Text(
                    if (isInRoom) {
                        stringResource(R.string.listen_together_reset_uuid_disabled)
                    } else {
                        stringResource(R.string.settings_listen_together_reset_identity_desc)
                    }
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ColorPickerDialog(
    currentHex: String,
    palette: List<String>,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit,
    onAddColor: (String) -> Unit,
    onRemoveColor: (String) -> Unit
) {
    // 用于 HSV 取色器输出
    var pickedHex by remember(currentHex) { mutableStateOf(currentHex.uppercase(Locale.ROOT)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_select_color)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 色列表
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        palette.forEach { hex ->
                            val isPreset = ThemeDefaults.PRESET_SET.contains(hex.uppercase(Locale.ROOT))
                            ColorPickerItem(
                                hex = hex,
                                isSelected = currentHex.equals(hex, ignoreCase = true),
                                onClick = {
                                    pickedHex = hex.uppercase(Locale.ROOT)
                                    onColorSelected(hex) // 允许直接使用预设色
                                },
                                onRemove = if (!isPreset) { { onRemoveColor(hex) } } else null
                            )
                        }
                    }
                }

                // 滑动取色
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_custom_color), style = MaterialTheme.typography.titleSmall)
                    HsvPicker(
                        initialHex = currentHex,
                        onColorChanged = { pickedHex = it.uppercase(Locale.ROOT) }
                    )
                }

                // 操作按钮
                val existsInPalette = palette.any { it.equals(pickedHex, ignoreCase = true) }
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val useVerticalButtons = maxWidth < 360.dp
                    if (useVerticalButtons) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onAddColor(pickedHex) },
                                enabled = !existsInPalette && !ThemeDefaults.PRESET_SET.contains(pickedHex),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_add_to_palette),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                            Button(
                                onClick = { onColorSelected(pickedHex) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_apply_color),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { onAddColor(pickedHex) },
                                enabled = !existsInPalette && !ThemeDefaults.PRESET_SET.contains(pickedHex),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_add_to_palette),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                            Button(
                                onClick = { onColorSelected(pickedHex) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_apply_color),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // 仅当存在可删除的自定义色时给出提示
                val deletableCount = palette.count { !ThemeDefaults.PRESET_SET.contains(it.uppercase(Locale.ROOT)) }
                if (deletableCount > 0) {
                    Text(
                        text = stringResource(R.string.settings_color_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorPickerItem(
    hex: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val color = Color(("#$hex").toColorInt())
    val clickableModifier = if (onRemove != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { onRemove() }
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            // 让对勾在深/浅色上都有对比度
            val contentColor = if (ColorUtils.calculateLuminance(color.toArgb()) > 0.5) {
                Color.Black
            } else {
                Color.White
            }
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.common_selected),
                tint = contentColor
            )
        }

        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.settings_delete_color),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun NeteaseLoginContent(
    vm: NeteaseAuthViewModel
) {
    val state by vm.uiState.collectAsStateWithLifecycleCompat()

    Column {
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.phone,
            onValueChange = vm::onPhoneChange,
            label = { Text(stringResource(R.string.settings_phone_number_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.captcha,
            onValueChange = vm::onCaptchaChange,
            label = { Text(stringResource(R.string.login_sms_code)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 发送验证码
        HapticButton(
            enabled = !state.sending && state.countdownSec <= 0,
            onClick = { vm.askConfirmSendCaptcha() }
        ) {
            if (state.sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.login_sending))
            } else {
                Text(if (state.countdownSec > 0) stringResource(R.string.settings_resend_code_countdown, state.countdownSec) else stringResource(R.string.login_send_code))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 登录
        HapticButton(
            enabled = state.captcha.isNotEmpty() && !state.loggingIn,
            onClick = { vm.loginByCaptcha(countryCode = "86") }
        ) {
            if (state.loggingIn) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.login_logging_in))
            } else {
                Text(stringResource(R.string.login_title))
            }
        }
    }
}

/** 内嵌提示条 */
@Composable
private fun InlineMessage(
    text: String,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            HapticIconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}


/** DPI 设置对话框 */
@SuppressLint("DefaultLocale")
@Composable
private fun DpiSettingDialog(
    currentScale: Float,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentScale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ui_scale)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format("%.2fx", sliderValue),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0.6f..1.2f,
                    steps = 11, // (1.2f - 0.6f) / 0.05f - 1 = 11
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_restart_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = { onApply(sliderValue) }) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            Row {
                HapticTextButton(onClick = {
                    sliderValue = 1.0f // 仅重置滑块状态，不应用
                }) {
                    Text(stringResource(R.string.action_reset))
                }
                HapticTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

/** 兼容性：不用依赖 collectAsState / lifecycle-compose，手动收集 StateFlow */
@Composable
private fun <T> StateFlow<T>.collectAsStateWithLifecycleCompat(): State<T> {
    val flow = this
    val state = remember { mutableStateOf(flow.value) }
    LaunchedEffect(flow) {
        flow.collect { v -> state.value = v }
    }
    return state
}

/**
 * 格式化同步时间
 */
@Composable
private fun formatSyncTime(timestamp: Long): String {
    LocalContext.current
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3600_000 -> stringResource(R.string.time_minutes_ago, diff / 60_000)
        diff < 86400_000 -> stringResource(R.string.time_hours_ago, diff / 3600_000)
        else -> stringResource(R.string.time_days_ago, diff / 86400_000)
    }
}
