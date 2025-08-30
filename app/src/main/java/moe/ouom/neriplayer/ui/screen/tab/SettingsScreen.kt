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
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Router
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.NeteaseWebLoginActivity
import moe.ouom.neriplayer.data.ThemeDefaults
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.util.HapticButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.NightModeHelper
import moe.ouom.neriplayer.util.convertTimestampToDate

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
                contentDescription = if (expanded) "收起" else "展开",
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
                contentDescription = "主题色",
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text("主题色") },
        supportingContent = { Text("选择一个你喜欢的颜色作为主色调") },
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
                contentDescription = "UI 缩放",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text("UI 缩放 (DPI)") },
        supportingContent = { Text("当前: ${"%.2f".format(currentScale)}x (默认 1.00x)") },
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
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
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
    onNavigateToDownloadManager: () -> Unit = {},
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

    // 下载管理菜单的状态
    var downloadManagerExpanded by remember { mutableStateOf(false) }
    val downloadManagerArrowRotation by animateFloatAsState(targetValue = if (downloadManagerExpanded) 180f else 0f, label = "download_manager_arrow")

    // 备份与恢复菜单的状态
    var backupRestoreExpanded by remember { mutableStateOf(false) }
    val backupRestoreArrowRotation by animateFloatAsState(targetValue = if (backupRestoreExpanded) 180f else 0f, label = "backup_restore_arrow")


    // 各种对话框和弹窗的显示状态 //
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNeteaseSheet by remember { mutableStateOf(false) }
    var showBiliQualityDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var showBiliCookieDialog by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
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
            inlineMsg = "已取消读取 B 站 Cookie"
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
            "standard" -> "标准"
            "higher" -> "较高"
            "exhigh" -> "极高"
            "lossless" -> "无损"
            "hires" -> "Hi-Res"
            "jyeffect" -> "高清环绕声"
            "sky" -> "沉浸环绕声"
            "jymaster" -> "超清母带"
            else -> preferredQuality
        }
    }

    val biliQualityLabel = remember(biliPreferredQuality) {
        when (biliPreferredQuality) {
            "dolby"   -> "杜比全景声"
            "hires"   -> "Hi-Res"
            "lossless"-> "无损"
            "high"    -> "高（约192kbps）"
            "medium"  -> "中（约128kbps）"
            "low"     -> "低（约64kbps）"
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
                    inlineMsg = "B 站登录成功"
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
                title = { Text("设置") },
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
                            contentDescription = "动态取色",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("动态取色") },
                    supportingContent = { Text("跟随系统主题色，Android 12+ 可用") },
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
                            contentDescription = "强制深色",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("强制深色") },
                    supportingContent = { Text("不跟随系统时可手动指定") },
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

            // 登录三方平台
            item {
                ExpandableHeader(
                    icon = Icons.Filled.AccountCircle,
                    title = "登录三方平台",
                    subtitleCollapsed = "展开以选择登录平台",
                    subtitleExpanded = "收起",
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
                                    contentDescription = "哔哩哔哩",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text("哔哩哔哩") },
                            supportingContent = { Text("浏览器登录") },
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
                            supportingContent = { Text("暂未实现") },
                            modifier = Modifier.clickable { },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        // 网易云音乐
                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_netease_cloud_music),
                                    contentDescription = "网易云音乐",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text("网易云音乐") },
                            supportingContent = { Text("浏览器/验证码/Cookie 登录") },
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
                                    contentDescription = "QQ 音乐",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text("QQ 音乐") },
                            supportingContent = { Text("咕咕咕，先观望一会") },
                            modifier = Modifier.clickable { },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            item {
                ExpandableHeader(
                    icon = Icons.Outlined.Tune,
                    title = "个性化",
                    subtitleCollapsed = "展开以调整视觉效果",
                    subtitleExpanded = "收起",
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
                                    contentDescription = "歌词模糊",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text("歌词模糊效果") },
                            supportingContent = { Text("为非当前行歌词添加景深模糊") },
                            trailingContent = {
                                Switch(checked = lyricBlurEnabled, onCheckedChange = onLyricBlurEnabledChange)
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
                                    contentDescription = "自定义背景图"
                                )
                            },
                            headlineContent = { Text("自定义背景图") },
                            supportingContent = { Text(if (backgroundImageUri != null) "点击更换" else "选择一张图片") }
                        )

                        // 展开区域
                        AnimatedVisibility(visible = backgroundImageUri != null) {
                            Column {
                                // 清除背景图按钮
                                TextButton(onClick = { onBackgroundImageChange(null) }) {
                                    Text("清除背景图")
                                }

                                // 模糊度调节
                                ListItem(
                                    headlineContent = { Text("背景模糊度") },
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
                                    headlineContent = { Text("背景透明度") },
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
                    title = "网络设置",
                    subtitleCollapsed = "展开以配置网络选项",
                    subtitleExpanded = "收起",
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
                                    contentDescription = "绕过系统代理",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text("绕过系统代理") },
                            supportingContent = { Text("应用内网络请求不走系统代理") },
                            trailingContent = {
                                Switch(checked = bypassProxy, onCheckedChange = onBypassProxyChange)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }


            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Audiotrack,
                            contentDescription = "网易云默认音质",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("网易云默认音质", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("$qualityLabel - $preferredQuality") },
                    modifier = Modifier.clickable { showQualityDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bilibili),
                            contentDescription = "B 站默认音质",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("B 站默认音质", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("$biliQualityLabel - $biliPreferredQuality") },
                    modifier = Modifier.clickable { showBiliQualityDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // 下载管理器
            item {
                ExpandableHeader(
                    icon = Icons.Outlined.Download,
                    title = "下载管理",
                    subtitleCollapsed = "展开以管理下载任务",
                    subtitleExpanded = "收起",
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
                                        contentDescription = "下载进度",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                headlineContent = { Text("下载进度") },
                                supportingContent = {
                                    Text("${progress.completedSongs}/${progress.totalSongs} 首歌曲")
                                },
                                trailingContent = {
                                    HapticTextButton(
                                        onClick = {
                                            AudioDownloadManager.cancelDownload()
                                        }
                                    ) {
                                        Text("取消", color = MaterialTheme.colorScheme.error)
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
                                    "正在下载: ${progress.currentSong}",
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
                                        contentDescription = "下载管理",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                headlineContent = { Text("下载管理") },
                                supportingContent = { Text("管理下载任务和本地文件") },
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
                    title = "备份与恢复",
                    subtitleCollapsed = "展开以管理歌单备份",
                    subtitleExpanded = "收起",
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
                                    contentDescription = "当前歌单",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            headlineContent = { Text("当前歌单数量") },
                            supportingContent = { Text("$currentPlaylistCount 个歌单") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        // 导出歌单
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Upload,
                                    contentDescription = "导出歌单",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text("导出歌单") },
                            supportingContent = { Text("将歌单导出为备份文件") },
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
                                    contentDescription = "导入歌单",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text("导入歌单") },
                            supportingContent = { Text("从备份文件恢复歌单") },
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
                                headlineContent = { Text("导出进度") },
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
                                headlineContent = { Text("导入进度") },
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
                                headlineContent = { Text("分析进度") },
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
                                        headlineContent = { Text(if (isSuccess) "导出成功" else "导出失败") },
                                        supportingContent = { Text(message) },
                                        trailingContent = {
                                            HapticTextButton(
                                                onClick = { backupRestoreVm.clearExportStatus() }
                                            ) {
                                                Text("关闭", color = MaterialTheme.colorScheme.primary)
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
                                        headlineContent = { Text(if (isSuccess) "导入成功" else "导入失败") },
                                        supportingContent = { Text(message) },
                                        trailingContent = {
                                            HapticTextButton(
                                                onClick = { backupRestoreVm.clearImportStatus() }
                                            ) {
                                                Text("关闭", color = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        colors = ListItemDefaults.colors(
                                            containerColor = Color.Transparent
                                        )
                                    )
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
                            contentDescription = "关于",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("关于", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("NeriPlayer • GPLv3 • 由你可爱地驱动") },
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
                            contentDescription = "版本",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("版本", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        val hint = if (!devModeEnabled) "" else "（DEBUG MODE）"
                        Text("${BuildConfig.VERSION_NAME} $hint")
                    },
                    modifier = Modifier.clickable {
                        if (!devModeEnabled) {
                            versionTapCount++
                            if (versionTapCount >= 7) {
                                onDevModeChange(true)
                                inlineMsg = "已开启调试模式"
                                versionTapCount = 0
                            }
                        } else {
                            inlineMsg = "调试模式已开启"
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
                            contentDescription = "编译时间",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("编译时间", style = MaterialTheme.typography.titleMedium) },
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
            title = { Text("选择 B 站默认音质") },
            text = {
                Column {
                    val options = listOf(
                        "dolby" to "杜比全景声",
                        "hires" to "Hi-Res",
                        "lossless" to "无损",
                        "high" to "高（约192kbps）",
                        "medium" to "中（约128kbps）",
                        "low" to "低（约64kbps）"
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
                    Text("关闭")
                }
            }
        )
    }

    // 网易云登录窗
    if (showNeteaseSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val conf = LocalConfiguration.current
        val sheetHeight = (conf.screenHeightDp * 0.75f).dp

        // “发送验证码” 确认对话框
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text("确认发送验证码？") },
                text = { Text("将发送验证码到 >${confirmPhoneMasked ?: ""}<") },
                confirmButton = {
                    HapticTextButton(onClick = {
                        showConfirmDialog = false
                        neteaseVm.sendCaptcha(ctcode = "86")
                    }) { Text("发送") }
                },
                dismissButton = {
                    HapticTextButton(onClick = {
                        showConfirmDialog = false
                        inlineMsg = "已取消发送"
                    }) { Text("取消") }
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
                inlineMsg = "已取消读取 Cookie"
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showNeteaseSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier
                    .height(sheetHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Text(text = "网易云音乐登录", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))

                    // 内嵌提示条
                    AnimatedVisibility(visible = inlineMsg != null, enter = fadeIn(), exit = fadeOut()) {
                        InlineMessage(
                            text = inlineMsg ?: "",
                            onClose = { inlineMsg = null }
                        )
                    }

                    // 使用 PrimaryTabRow（Material3 推荐），避免旧 TabRow 的弃用告警
                    androidx.compose.material3.PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("浏览器登录") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("粘贴 Cookie") })
                        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("验证码登录") })
                    }

                    Spacer(Modifier.height(12.dp))

                    when (selectedTab) {
                        0 -> {
                            Text(
                                "将打开内置浏览器（桌面UA）访问 music.163.com，登录成功后点击右上角 “完成”。",
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
                                Text("开始浏览器登录")
                            }
                        }

                        1 -> {
                            OutlinedTextField(
                                value = rawCookie,
                                onValueChange = { rawCookie = it },
                                label = { Text("粘贴 Cookie（例如：MUSIC_U=...; __csrf=...）") },
                                minLines = 6,
                                maxLines = 10,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            HapticButton(onClick = {
                                if (rawCookie.isBlank()) {
                                    inlineMsg = "请输入 Cookie"
                                } else {
                                    neteaseVm.importCookiesFromRaw(rawCookie)
                                }
                            }) { Text("保存 Cookie 并登录") }
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
            title = { Text("B 站登录成功") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = biliCookieText.ifBlank { "(空)" },
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showBiliCookieDialog = false }) { Text("好的") }
            }
        )
    }

    // 音质选择对话框
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("选择默认音质") },
            text = {
                Column {
                    val qualityOptions = listOf(
                        "standard" to "标准",
                        "higher" to "较高",
                        "exhigh" to "极高",
                        "lossless" to "无损",
                        "hires" to "Hi-Res",
                        "jyeffect" to "高清环绕声",
                        "sky" to "沉浸环绕声",
                        "jymaster" to "超清母带"
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
                    Text("关闭")
                }
            }
        )
    }

    // Cookies 展示对话框
    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("登录成功") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(cookieScroll)
                ) {
                    Text(
                        text = cookieText.ifBlank { "(空)" },
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showCookieDialog = false }) { Text("好的") }
            }
        )
    }

    // 颜色选择对话框
    if (showColorPickerDialog) {
        ColorPickerDialog(
            currentHex = seedColorHex,
            onDismiss = { showColorPickerDialog = false },
            onColorSelected = { hex ->
                onSeedColorChange(hex)
                showColorPickerDialog = false
            }
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(
    currentHex: String,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择主题色") },
        text = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ThemeDefaults.PRESET_COLORS.forEach { hex ->
                    ColorPickerItem(
                        hex = hex,
                        isSelected = currentHex.equals(hex, ignoreCase = true),
                        onClick = { onColorSelected(hex) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun ColorPickerItem(hex: String, isSelected: Boolean, onClick: () -> Unit) {
    val color = Color(("#$hex").toColorInt())
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            // 计算一个与背景色对比度高的颜色来显示对勾
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
    }
}

@Composable
private fun NeteaseLoginContent(
    message: String?,
    onDismissMessage: () -> Unit,
    vm: NeteaseAuthViewModel
) {
    val state by vm.uiState.collectAsStateWithLifecycleCompat()

    Column(modifier = Modifier.fillMaxSize()) {
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
            label = { Text("+86 手机号") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.captcha,
            onValueChange = vm::onCaptchaChange,
            label = { Text("短信验证码") },
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
                Text("发送中...")
            } else {
                Text(if (state.countdownSec > 0) "重新获取（${state.countdownSec}s）" else "发送验证码")
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
                Text("登录中...")
            } else {
                Text("登录")
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
                Icon(imageVector = Icons.Filled.Close, contentDescription = "关闭")
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
        title = { Text("修改 UI 缩放比例") },
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
                    "修改后可能需要重启应用以获得最佳效果",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = { onApply(sliderValue) }) {
                Text("应用")
            }
        },
        dismissButton = {
            Row {
                HapticTextButton(onClick = {
                    sliderValue = 1.0f // 仅重置滑块状态，不应用
                }) {
                    Text("重置")
                }
                HapticTextButton(onClick = onDismiss) {
                    Text("取消")
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
