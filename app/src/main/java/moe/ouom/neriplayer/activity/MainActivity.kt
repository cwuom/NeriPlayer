package moe.ouom.neriplayer.activity

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
 * File: moe.ouom.neriplayer.activity/MainActivity
 * Created: 2025/8/8
 */


import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerEvent
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.ui.NeriApp
import moe.ouom.neriplayer.util.HapticButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.ExceptionHandler
import moe.ouom.neriplayer.util.LanguageManager
import android.content.Context

private enum class AppStage { Loading, Disclaimer, Main }

class MainActivity : ComponentActivity() {
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            // 初始化日志：在 Application 期也会被调用，重复调用是幂等的
            val repo = SettingsRepository(this)
            val devModeEnabled by repo.devModeEnabledFlow.collectAsState(initial = false)
            NPLogger.init(context = this, enableFileLogging = devModeEnabled)

            val dynamicColor by settingsRepository.dynamicColorFlow.collectAsState(initial = true)
            val forceDark by settingsRepository.forceDarkFlow.collectAsState(initial = false)
            val followSystemDark by settingsRepository.followSystemDarkFlow.collectAsState(initial = true)
            val disclaimerAcceptedNullable by settingsRepository.disclaimerAcceptedFlow.collectAsState(initial = null)

            val systemDark = isSystemInDarkTheme()
            val useDark = remember(forceDark, followSystemDark, systemDark) {
                when {
                    forceDark -> true
                    followSystemDark -> systemDark
                    else -> false
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {  }
                LaunchedEffect(Unit) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // GitHub自动同步 - 应用启动时拉取最新数据(异步,不阻塞UI)
            LaunchedEffect(Unit) {
                launch {
                    delay(1000) // 延迟1秒,避免阻塞启动
                    val storage = moe.ouom.neriplayer.data.github.SecureTokenStorage(this@MainActivity)
                    if (storage.isConfigured()) {
                        moe.ouom.neriplayer.data.github.GitHubSyncWorker.syncNow(this@MainActivity)
                    }
                }
            }

            NeriTheme(useDark = useDark, useDynamic = dynamicColor) {
                SideEffect {
                    applyWindowBackground(useDark)
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !useDark
                    controller.isAppearanceLightNavigationBars = !useDark
                }

                // 入场动画状态
                var playedEntrance by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) { playedEntrance = true }

                val stage = when (disclaimerAcceptedNullable) {
                    null -> AppStage.Loading
                    true -> AppStage.Main
                    false -> AppStage.Disclaimer
                }

                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        val enter = slideInVertically(
                            animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
                            initialOffsetY = { fullHeight ->
                                if (playedEntrance) fullHeight / 8 else 0
                            }
                        ) + fadeIn(animationSpec = tween(350, delayMillis = if (playedEntrance) 50 else 0))

                        val exit = slideOutVertically(
                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                            targetOffsetY = { -it / 12 }
                        ) + fadeOut(animationSpec = tween(250))

                        enter togetherWith exit using SizeTransform(clip = false)
                    },
                    label = "AppStageTransition"
                ) { current ->
                    when (current) {
                        AppStage.Loading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .navigationBarsPadding()
                            )
                        }
                        AppStage.Disclaimer -> {
                            val scope = rememberCoroutineScope()
                            DisclaimerScreen(
                                onAgree = { scope.launch { settingsRepository.setDisclaimerAccepted(true) } }
                            )
                        }
                        AppStage.Main -> {
                            // 弹窗状态管理和事件监听
                            var showDialog by remember { mutableStateOf(false) }
                            var dialogMessage by remember { mutableStateOf("") }
                            var showErrorDialog by remember { mutableStateOf(false) }
                            var errorTitle by remember { mutableStateOf("") }
                            var errorMessage by remember { mutableStateOf("") }
                            val lifecycleOwner = LocalLifecycleOwner.current

                            // 初始化异常处理器
                            LaunchedEffect(Unit) {
                                ExceptionHandler.init(this@MainActivity) { title, message ->
                                    errorTitle = title
                                    errorMessage = message
                                    showErrorDialog = true
                                }
                            }

                            // GitHub同步配置检查（每次回到前台时检查，并定期轮询）
                            var hasShownTokenWarning by remember { mutableStateOf(false) }
                            var showTokenWarningDialog by remember { mutableStateOf(false) }
                            LaunchedEffect(lifecycleOwner.lifecycle) {
                                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                    while (true) {
                                        val storage = moe.ouom.neriplayer.data.github.SecureTokenStorage(this@MainActivity)
                                        // 如果有仓库信息但token缺失，或者曾经同步过但现在未配置，显示警告
                                        val hasRepoInfo = !storage.getRepoOwner().isNullOrEmpty() || !storage.getRepoName().isNullOrEmpty()
                                        val hasSyncHistory = storage.getLastSyncTime() > 0
                                        val isConfigured = storage.isConfigured()
                                        val isDismissed = storage.isTokenWarningDismissed()

                                        if ((hasRepoInfo || hasSyncHistory) && !isConfigured && !hasShownTokenWarning && !isDismissed) {
                                            // 曾经配置过但现在token缺失，显示警告
                                            NPLogger.d("MainActivity", "显示 GitHub 配置警告")
                                            showTokenWarningDialog = true
                                            hasShownTokenWarning = true
                                        } else if (isConfigured) {
                                            // 如果重新配置了，重置警告标志和忽略标志
                                            hasShownTokenWarning = false
                                            storage.setTokenWarningDismissed(false)
                                        }

                                        // 每3秒检查一次
                                        delay(3000)
                                    }
                                }
                            }

                            LaunchedEffect(lifecycleOwner.lifecycle) {
                                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    PlayerManager.playerEventFlow.collect { event ->
                                        when (event) {
                                            is PlayerEvent.ShowLoginPrompt -> {
                                                dialogMessage = event.message
                                                showDialog = true
                                            }

                                            is PlayerEvent.ShowError -> {
                                                dialogMessage = event.message
                                                showDialog = true
                                            }
                                        }
                                    }
                                }
                            }

                            if (showDialog) {
                                AlertDialog(
                                    onDismissRequest = { showDialog = false },
                                    title = { Text(stringResource(R.string.dialog_hint)) },
                                    text = { Text(dialogMessage) },
                                    confirmButton = {
                                        HapticTextButton(onClick = { showDialog = false }) {
                                            Text(stringResource(R.string.action_confirm))
                                        }
                                    }
                                )
                            }

                            // 异常错误弹窗
                            if (showErrorDialog) {
                                AlertDialog(
                                    onDismissRequest = { showErrorDialog = false },
                                    title = { Text(errorTitle) },
                                    text = { Text(errorMessage) },
                                    confirmButton = {
                                        HapticTextButton(onClick = { showErrorDialog = false }) {
                                            Text(stringResource(R.string.action_confirm))
                                        }
                                    }
                                )
                            }

                            // Token过期警告弹窗（带"不再提醒"按钮）
                            if (showTokenWarningDialog) {
                                var countdown by remember { mutableIntStateOf(3) }
                                LaunchedEffect(Unit) {
                                    while (countdown > 0) {
                                        delay(1000)
                                        countdown--
                                    }
                                }

                                AlertDialog(
                                    onDismissRequest = { showTokenWarningDialog = false },
                                    title = { Text(stringResource(R.string.github_sync_warning_title)) },
                                    text = { Text(stringResource(R.string.github_sync_warning_message)) },
                                    confirmButton = {
                                        HapticTextButton(onClick = { showTokenWarningDialog = false }) {
                                            Text(stringResource(R.string.action_confirm))
                                        }
                                    },
                                    dismissButton = {
                                        HapticTextButton(
                                            onClick = {
                                                val storage = moe.ouom.neriplayer.data.github.SecureTokenStorage(this@MainActivity)
                                                storage.setTokenWarningDismissed(true)
                                                showTokenWarningDialog = false
                                            },
                                            enabled = countdown == 0
                                        ) {
                                            Text(if (countdown > 0) stringResource(R.string.github_sync_no_remind_countdown, countdown) else stringResource(R.string.github_sync_no_remind))
                                        }
                                    }
                                )
                            }

                            NeriApp(
                                onIsDarkChanged = { isDark ->
                                    // 仅调整窗口底色 & 系统栏外观
                                    applyWindowBackground(isDark)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyWindowBackground(isDark: Boolean) {
        val bgColor = if (isDark) "#121212".toColorInt() else Color.WHITE
        window.setBackgroundDrawable(bgColor.toDrawable())
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ExceptionHandler.cleanup()
    }
}

/* --------------------- 统一主题 --------------------- */

@Composable
fun NeriTheme(
    useDark: Boolean,
    useDynamic: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            if (useDark) darkColorScheme() else lightColorScheme()
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

/* --------------------- 免责声明与隐私说明 --------------------- */

@Composable
fun DisclaimerScreen(onAgree: () -> Unit) {
    var countdown by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) { while (countdown > 0) { delay(1000); countdown-- } }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.disclaimer_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    SectionTitle(stringResource(R.string.disclaimer_section1_title))
                    BodyText(stringResource(R.string.disclaimer_section1_body))

                    SectionTitle(stringResource(R.string.disclaimer_section2_title))
                    Bullets(
                        listOf(
                            stringResource(R.string.disclaimer_section2_bullet1),
                            stringResource(R.string.disclaimer_section2_bullet2),
                            stringResource(R.string.disclaimer_section2_bullet3),
                            stringResource(R.string.disclaimer_section2_bullet4)
                        )
                    )

                    SectionTitle(stringResource(R.string.disclaimer_section3_title))
                    Bullets(
                        listOf(
                            stringResource(R.string.disclaimer_section3_bullet1),
                            stringResource(R.string.disclaimer_section3_bullet2),
                            stringResource(R.string.disclaimer_section3_bullet3)
                        )
                    )

                    SectionTitle(stringResource(R.string.disclaimer_section4_title))
                    Bullets(
                        listOf(
                            stringResource(R.string.disclaimer_section4_bullet1),
                            stringResource(R.string.disclaimer_section4_bullet2),
                            stringResource(R.string.disclaimer_section4_bullet3)
                        )
                    )

                    SectionTitle(stringResource(R.string.disclaimer_section5_title))
                    Bullets(
                        listOf(
                            stringResource(R.string.disclaimer_section5_bullet1),
                            stringResource(R.string.disclaimer_section5_bullet2),
                            stringResource(R.string.disclaimer_section5_bullet3),
                            stringResource(R.string.disclaimer_section5_bullet4),
                            stringResource(R.string.disclaimer_section5_bullet5),
                            stringResource(R.string.disclaimer_section5_bullet6),
                            stringResource(R.string.disclaimer_section5_bullet7),
                            stringResource(R.string.disclaimer_section5_bullet8),
                            stringResource(R.string.disclaimer_section5_bullet9)
                        )
                    )

                    SectionTitle(stringResource(R.string.disclaimer_section6_title))
                    Bullets(
                        listOf(
                            stringResource(R.string.disclaimer_section6_bullet1),
                            stringResource(R.string.disclaimer_section6_bullet2),
                            stringResource(R.string.disclaimer_section6_bullet3)
                        )
                    )

                    SectionTitle(stringResource(R.string.disclaimer_section7_title))
                    BodyText(stringResource(R.string.disclaimer_section7_body))

                    SectionTitle(stringResource(R.string.disclaimer_section8_title))
                    BodyText(stringResource(R.string.disclaimer_section8_body))

                    SectionTitle(stringResource(R.string.disclaimer_section9_title))
                    EmphasisText(stringResource(R.string.disclaimer_section9_body))
                }

                Spacer(Modifier.height(16.dp))

                HapticButton(
                    onClick = { onAgree() },
                    enabled = countdown == 0,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (countdown == 0) stringResource(R.string.disclaimer_agree_countdown) else stringResource(R.string.disclaimer_read_countdown, countdown),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 6.dp)
    )
}
@Composable private fun BodyText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Justify)
}
@Composable private fun EmphasisText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Justify
    )
}
@Composable private fun Bullets(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text("• ", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Justify,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}