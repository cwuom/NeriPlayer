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
 * File: moe.ouom.neriplayer.ui.screens/SettingsScreen
 * Created: 2025/8/8
 */

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.NeteaseWebLoginActivity
import moe.ouom.neriplayer.ui.viewmodel.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.NeteaseAuthViewModel
import moe.ouom.neriplayer.util.NightModeHelper
import moe.ouom.neriplayer.util.convertTimestampToDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    followSystemDark: Boolean,
    forceDark: Boolean,
    onForceDarkChange: (Boolean) -> Unit,
    preferredQuality: String,
    onQualityChange: (String) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    var loginExpanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (loginExpanded) 180f else 0f, label = "arrow")

    // 音质设置对话框显隐状态
    var showQualityDialog by remember { mutableStateOf(false) }

    // 网易云登录弹窗显隐
    var showNeteaseSheet by remember { mutableStateOf(false) }
    val neteaseVm: NeteaseAuthViewModel = viewModel()

    // 弹窗内提示信息
    var inlineMsg by remember { mutableStateOf<String?>(null) }

    // 网易云 “发送验证码” 确认弹窗
    var confirmPhoneMasked by remember { mutableStateOf<String?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Cookies 展示对话框
    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieText by remember { mutableStateOf("") }

    val cookieScroll = rememberScrollState()

    // Bilibili 登录
    var showBiliCookieDialog by remember { mutableStateOf(false) }
    var biliCookieText by remember { mutableStateOf("") }
    val biliVm: moe.ouom.neriplayer.ui.viewmodel.BiliAuthViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()

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
                is moe.ouom.neriplayer.ui.viewmodel.BiliAuthEvent.ShowSnack -> inlineMsg = e.message
                is moe.ouom.neriplayer.ui.viewmodel.BiliAuthEvent.ShowCookies -> {
                    biliCookieText = e.cookies.entries.joinToString("\n") { (k, v) -> "$k=$v" }
                    showBiliCookieDialog = true
                }
                moe.ouom.neriplayer.ui.viewmodel.BiliAuthEvent.LoginSuccess -> {
                    inlineMsg = "B 站登录成功"
                }
            }
        }
    }


    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface,)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            LargeTopAppBar(
                title = { Text("设置") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
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
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = "登录三方平台",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("登录三方平台") },
                    supportingContent = { Text(if (loginExpanded) "收起" else "展开以选择登录平台") },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (loginExpanded) "收起" else "展开",
                            modifier = Modifier.rotate(arrowRotation),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable { loginExpanded = !loginExpanded },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
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
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
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
                    TextButton(onClick = {
                        showConfirmDialog = false
                        neteaseVm.sendCaptcha(ctcode = "86")
                    }) { Text("发送") }
                },
                dismissButton = {
                    TextButton(onClick = {
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

                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("浏览器登录") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("粘贴 Cookie") })
                        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("验证码登录") })
                    }

                    Spacer(Modifier.height(12.dp))

                    when (selectedTab) {
                        0 -> {
                            Text(
                                "将打开内置浏览器（桌面UA）访问 music.163.com，登录成功后点击右上角“读取 Cookie 并返回”。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
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
                            Button(onClick = {
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
                TextButton(onClick = { showBiliCookieDialog = false }) { Text("好的") }
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
                TextButton(onClick = { showQualityDialog = false }) {
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
                TextButton(onClick = { showCookieDialog = false }) { Text("好的") }
            }
        )
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
        Button(
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
        Button(
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
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "关闭")
            }
        }
    }
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
