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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.NeteaseWebLoginActivity
import moe.ouom.neriplayer.data.ThemeDefaults
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.data.github.SecureTokenStorage
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.util.HapticButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.NightModeHelper
import moe.ouom.neriplayer.util.convertTimestampToDate
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.SdStorage
import moe.ouom.neriplayer.ui.component.HsvPicker
import moe.ouom.neriplayer.ui.component.LanguageSettingItem
import android.graphics.Color as AndroidColor


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
        modifier = Modifier.clickable { onToggle() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** 主题色预览行（当关闭系统动态取色时显示） */
@Composable
private fun ThemeSeedListItem(seedColorHex: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
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
        modifier = Modifier.clickable(onClick = onClick),
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    listState: androidx.compose.foundation.lazy.LazyListState,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    forceDark: Boolean,
    onForceDarkChange: (Boolean) -> Unit,
    preferredQuality: String,
    onQualityChange: (String) -> Unit,
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
    showLyricTranslation: Boolean,
    onShowLyricTranslationChange: (Boolean) -> Unit,
    onNavigateToDownloadManager: () -> Unit = {},
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onClearCacheClick: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    // 登录菜单的状态
    var loginExpanded by remember { mutableStateOf(false) }
    // 仅用于示意展开箭头的旋转，后续可复用至 ExpandableHeader 的 arrowRotation 入参
    val arrowRotation by animateFloatAsState(targetValue = if (loginExpanded) 180f else 0f, label = "arrow")

    // 个性化菜单的状态
    var personalizationExpanded by remember { mutableStateOf(false) }
    val personalizationArrowRotation by animateFloatAsState(targetValue = if (personalizationExpanded) 180f else 0f, label = "personalization_arrow")

    // 网络配置菜单的状态
    var networkExpanded by remember { mutableStateOf(false) }
    val networkArrowRotation by animateFloatAsState(targetValue = if (networkExpanded) 180f else 0f, label = "network_arrow")

    // 音质设置菜单的状态
    var audioQualityExpanded by remember { mutableStateOf(false) }
    val audioQualityArrowRotation by animateFloatAsState(targetValue = if (audioQualityExpanded) 180f else 0f, label = "audio_quality_arrow")

    // 下载管理菜单的状态
    var downloadManagerExpanded by remember { mutableStateOf(false) }
    val downloadManagerArrowRotation by animateFloatAsState(targetValue = if (downloadManagerExpanded) 180f else 0f, label = "download_manager_arrow")

    // 备份与恢复菜单的状态
    var backupRestoreExpanded by remember { mutableStateOf(false) }
    val backupRestoreArrowRotation by animateFloatAsState(targetValue = if (backupRestoreExpanded) 180f else 0f, label = "backup_restore_arrow")

    // 缓存设置的状态
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var cacheExpanded by remember { mutableStateOf(false) }
    val cacheArrowRotation by animateFloatAsState(targetValue = if (cacheExpanded) 180f else 0f, label = "backup_restore_arrow")


    // 各种对话框和弹窗的显示状态 //
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNeteaseSheet by remember { mutableStateOf(false) }
    var showBiliQualityDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var showBiliCookieDialog by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
    var showGitHubConfigDialog by remember { mutableStateOf(false) }
    var showClearGitHubConfigDialog by remember { mutableStateOf(false) }
    // ------------------------------------

    val neteaseVm: NeteaseAuthViewModel = viewModel()
    var inlineMsg by remember { mutableStateOf<String?>(null) }
    var confirmPhoneMasked by remember { mutableStateOf<String?>(null) }
    var cookieText by remember { mutableStateOf("") }
    val cookieScroll = rememberScrollState()
    var versionTapCount by remember { mutableIntStateOf(0) }
    var biliCookieText by remember { mutableStateOf("") }
    val biliVm: BiliAuthViewModel = viewModel()
    
    // 备份与恢复
    val backupRestoreVm: BackupRestoreViewModel = viewModel()
    val backupRestoreUiState by backupRestoreVm.uiState.collectAsState()

    // 照片选择器
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                // 获取永久访问权限
                val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flag)
                onBackgroundImageChange(uri)
            }
        }
    )

    val biliWebLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(moe.ouom.neriplayer.activity.BiliWebLoginActivity.RESULT_COOKIE) ?: "{}"
            val map = biliVm.parseJsonToMap(json)
            biliVm.importCookiesFromMap(map)
        } else {
            inlineMsg = context.getString(R.string.settings_bili_cookie_cancelled)
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
            "hires" -> "Hi-Res"
            "jyeffect" -> context.getString(R.string.settings_audio_quality_jyeffect)
            "sky" -> context.getString(R.string.settings_audio_quality_sky)
            "jymaster" -> context.getString(R.string.settings_audio_quality_jymaster)
            else -> preferredQuality
        }
    }

    val biliQualityLabel = remember(biliPreferredQuality) {
        when (biliPreferredQuality) {
            "dolby"   -> context.getString(R.string.settings_audio_quality_dolby)
            "hires"   -> "Hi-Res"
            "lossless"-> context.getString(R.string.settings_audio_quality_lossless)
            "high"    -> context.getString(R.string.settings_audio_quality_high)
            "medium"  -> context.getString(R.string.settings_audio_quality_medium)
            "low"     -> context.getString(R.string.settings_audio_quality_low)
            else -> biliPreferredQuality
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
                }
                is NeteaseAuthEvent.ShowCookies -> {
                    cookieText = e.cookies.entries.joinToString("\n") { (k, v) -> "$k=$v" }
                    showCookieDialog = true
                }
            }
        }
    }

    LaunchedEffect(biliVm) {
        biliVm.events.collect { e ->
            when (e) {
                is BiliAuthEvent.ShowSnack -> inlineMsg = e.message
                is BiliAuthEvent.ShowCookies -> {
                    biliCookieText = e.cookies.entries.joinToString("\n") { (k, v) -> "$k=$v" }
                    showBiliCookieDialog = true
                }
                BiliAuthEvent.LoginSuccess -> {
                    inlineMsg = context.getString(R.string.settings_bili_login_success)
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
                title = { Text(stringResource(R.string.settings_title)) },
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


            // 强制深色
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.DarkMode,
                            contentDescription = stringResource(R.string.settings_force_dark),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_force_dark)) },
                    supportingContent = { Text(stringResource(R.string.settings_force_dark_desc)) },
                    trailingContent = {
                        Switch(
                            checked = forceDark,
                            onCheckedChange = { checked ->
                                onForceDarkChange(checked)
                                NightModeHelper.applyNightMode(
                                    followSystemDark = false,
                                    forceDark = checked
                                )
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
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
                LanguageSettingItem()
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
                        // 哔哩哔哩
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
                            supportingContent = { Text(stringResource(R.string.login_browser)) },
                            modifier = Modifier.clickable {
                                inlineMsg = null
                                biliWebLoginLauncher.launch(
                                    Intent(context, moe.ouom.neriplayer.activity.BiliWebLoginActivity::class.java)
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )


                        // YouTube
                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_youtube),
                                    contentDescription = "YouTube",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text("YouTube") },
                            supportingContent = { Text(stringResource(R.string.common_not_implemented)) },
                            modifier = Modifier.clickable { },
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
                            supportingContent = { Text(stringResource(R.string.login_methods)) },
                            modifier = Modifier.clickable {
                                inlineMsg = null
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
                            modifier = Modifier.clickable { },
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
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.BlurOn,
                                    contentDescription = stringResource(R.string.settings_lyrics_blur),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.lyrics_blur_effect)) },
                            supportingContent = { Text(stringResource(R.string.lyrics_blur_desc)) },
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
                                        if ((pendingBlurAmount - lyricBlurAmount).absoluteValue > 0.1f) {
                                            pendingBlurAmount = lyricBlurAmount
                                        }
                                    }

                                    Column(Modifier.fillMaxWidth()) {
                                        Text(
                                            text = stringResource(R.string.lyrics_blur_current, pendingBlurAmount.roundToInt()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Slider(
                                            value = pendingBlurAmount,
                                            onValueChange = { pendingBlurAmount = it },
                                            onValueChangeFinished = {
                                                onLyricBlurAmountChange(pendingBlurAmount)
                                            },
                                            valueRange = 0f..25f,
                                            steps = 24
                                        )
                                    }
                                }
                            )
                        }

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
                            modifier = Modifier.clickable {
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
                                TextButton(onClick = { onBackgroundImageChange(null) }) {
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
                            supportingContent = { Text("$qualityLabel - $preferredQuality") },
                            modifier = Modifier.clickable { showQualityDialog = true },
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
                            supportingContent = { Text("$biliQualityLabel - $biliPreferredQuality") },
                            modifier = Modifier.clickable { showBiliQualityDialog = true },
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
                                    String.format(Locale.US, "%.1f GB", sliderValue / 1024)
                                } else {
                                    "${sliderValue.toInt()} MB"
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
                                OutlinedButton(onClick = { showClearCacheDialog = true }) {
                                    Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_clear))
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
                                modifier = Modifier.clickable {
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
                                    Icons.Outlined.PlaylistPlay,
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
                            modifier = Modifier.clickable {
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
                            modifier = Modifier.clickable {
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
                                modifier = Modifier.clickable {
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
                                modifier = Modifier.clickable {
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
                                        onCheckedChange = {
                                            dataSaverMode = it
                                            storage.setDataSaverMode(it)
                                        }
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
                            contentDescription = "Build UUID",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("Build UUID", style = MaterialTheme.typography.titleMedium) },
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
                        val hint = if (!devModeEnabled) "" else "（DEBUG MODE）"
                        Text("${BuildConfig.VERSION_NAME} $hint")
                    },
                    modifier = Modifier.clickable {
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
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("GitHub") },
                    supportingContent = { Text("github.com/cwuom/NeriPlayer") },
                    modifier = Modifier.clickable {
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
                        "hires" to "Hi-Res",
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
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
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
        var selectedTab by remember { mutableIntStateOf(0) }
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
                inlineMsg = context.getString(R.string.settings_bili_cookie_cancelled)
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
                                message = null,
                                onDismissMessage = { },
                                vm = neteaseVm
                            )
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
                        "hires" to "Hi-Res",
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
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
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
                        label = { Text("GitHub Token") },
                        placeholder = { Text("ghp_xxxxxxxxxxxx") },
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
                            androidx.compose.material3.RadioButton(
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
                            androidx.compose.material3.RadioButton(
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
                                placeholder = { Text("username/repo-name") },
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

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.settings_confirm_clear_cache)) },
            text = { Text(stringResource(R.string.settings_clear_cache_warning)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        onClearCacheClick()
                        showClearCacheDialog = false
                    }
                ) { Text(stringResource(R.string.action_confirm_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                HapticTextButton(onClick = { showClearCacheDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 色列表
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
                        Text(stringResource(R.string.settings_add_to_palette))
                    }
                    Button(
                        onClick = { onColorSelected(pickedHex) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_apply_color))
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
                contentDescription = "Selected",
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
    message: String?,
    onDismissMessage: () -> Unit,
    vm: NeteaseAuthViewModel
) {
    val state by vm.uiState.collectAsStateWithLifecycleCompat()

    Column {
        Spacer(modifier = Modifier.height(12.dp))

        // 内嵌提示条
        AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
            InlineMessage(
                text = message ?: "",
                onClose = onDismissMessage
            )
        }

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
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3600_000 -> stringResource(R.string.time_minutes_ago, diff / 60_000)
        diff < 86400_000 -> stringResource(R.string.time_hours_ago, diff / 3600_000)
        else -> stringResource(R.string.time_days_ago, diff / 86400_000)
    }
}
