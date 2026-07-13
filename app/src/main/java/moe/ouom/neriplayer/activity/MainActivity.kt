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


import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.NeriPlayerApplication
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.service.canUseDirectPlaybackServiceStart
import moe.ouom.neriplayer.core.startup.StartupStageFlow
import moe.ouom.neriplayer.core.startup.StartupStage
import moe.ouom.neriplayer.core.startup.crash.StartupCrashReportManager
import moe.ouom.neriplayer.core.startup.download.StartupDownloadRecoveryCoordinator
import moe.ouom.neriplayer.core.startup.logging.StartupLogInitializer
import moe.ouom.neriplayer.core.startup.permission.StartupNotificationPermission
import moe.ouom.neriplayer.core.startup.safemode.SafeModeRecoveryCoordinator
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.core.startup.sync.StartupSyncScheduler
import moe.ouom.neriplayer.core.startup.sync.StartupSyncWarningCoordinator
import moe.ouom.neriplayer.core.startup.sync.StartupSyncWarningRepository
import moe.ouom.neriplayer.core.startup.theme.StartupNightModeSyncPlanner
import moe.ouom.neriplayer.core.startup.theme.StartupResourceNightMode
import moe.ouom.neriplayer.core.startup.theme.StartupThemeResolver
import moe.ouom.neriplayer.core.startup.theme.StartupThemeSnapshotProvider
import moe.ouom.neriplayer.listentogether.invite.ListenTogetherInvite
import moe.ouom.neriplayer.listentogether.validation.normalizeListenTogetherRoomId
import moe.ouom.neriplayer.listentogether.invite.parseListenTogetherInvite
import moe.ouom.neriplayer.listentogether.invite.resolveListenTogetherInviteJoinBaseUrl
import moe.ouom.neriplayer.ui.MobileDataDownloadInterruptionDialog
import moe.ouom.neriplayer.ui.NeriApp
import moe.ouom.neriplayer.ui.onboarding.StartupOnboardingScreen
import moe.ouom.neriplayer.ui.screen.safemode.SafeModeScreen
import moe.ouom.neriplayer.ui.theme.rememberActualSystemDarkTheme
import moe.ouom.neriplayer.util.crash.CrashReportStore
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.platform.NightModeHelper
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone

private data class PendingAudioServiceStart(
    val requestToken: Long,
    val source: String,
    val forceForeground: Boolean
)

@Composable
private fun GitHubSyncWarningDialog(
    onConfirm: () -> Unit,
    onDismissReminder: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    AlertDialog(
        onDismissRequest = onConfirm,
        title = { Text(stringResource(R.string.github_sync_warning_title)) },
        text = { Text(stringResource(R.string.github_sync_warning_message)) },
        confirmButton = {
            HapticTextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(
                onClick = onDismissReminder,
                enabled = countdown == 0
            ) {
                Text(
                    if (countdown > 0) {
                        stringResource(R.string.github_sync_no_remind_countdown, countdown)
                    } else {
                        stringResource(R.string.github_sync_no_remind)
                    }
                )
            }
        }
    )
}

class MainActivity : ComponentActivity() {
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val startupCrashReportManager by lazy { StartupCrashReportManager(applicationContext) }
    private var externalAudioImportJob: Job? = null
    private var externalAudioMetadataHydrationJob: Job? = null
    private var externalAudioRequestToken = 0L
    private var pendingExternalAudioServiceStart: PendingAudioServiceStart? = null
    private val pendingListenTogetherInvite = MutableStateFlow<ListenTogetherInvite?>(null)
    private val listenTogetherInviteFlow = pendingListenTogetherInvite.asStateFlow()
    private var lastObservedClipboardInviteSignature: String? = null
    private var clipboardInviteInspectJob: Job? = null
    private var hasWindowFocusForClipboardInspection = false
    private val listenTogetherStatusMessage = MutableStateFlow<String?>(null)
    private val listenTogetherStatusFlow = listenTogetherStatusMessage.asStateFlow()
    private val startupSyncWarningCoordinator by lazy {
        StartupSyncWarningCoordinator(
            StartupSyncWarningRepository(applicationContext)
        )
    }
    private var safeModeActive = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        safeModeActive = SafeModeManager.shouldEnterSafeMode(this)
        val startupThemeSnapshot = StartupThemeSnapshotProvider.read(
            context = this,
            safeModeActive = safeModeActive
        )
        NightModeHelper.applyNightMode(
            followSystemDark = startupThemeSnapshot.followSystemDark,
            forceDark = startupThemeSnapshot.forceDark
        )
        installSplashScreen()
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowBackground(
            StartupThemeResolver.resolveSnapshotUseDark(
                snapshot = startupThemeSnapshot,
                systemDark = StartupResourceNightMode.isDark(resources.configuration.uiMode)
            )
        )

        if (safeModeActive) {
            setContent {
                val systemDark = rememberActualSystemDarkTheme()
                val useDark = remember(systemDark) {
                    StartupThemeResolver.resolveSnapshotUseDark(
                        snapshot = startupThemeSnapshot,
                        systemDark = systemDark
                    )
                }
                NeriTheme(useDark = useDark, useDynamic = false) {
                    SideEffect {
                        val controller = WindowInsetsControllerCompat(window, window.decorView)
                        controller.isAppearanceLightStatusBars = !useDark
                        controller.isAppearanceLightNavigationBars = !useDark
                    }
                    SafeModeScreen(
                        onRestoreNormal = ::restoreFromSafeMode
                    )
                }
            }
            return
        }

        setContent {
            val devModeEnabled by settingsRepository.devModeEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
            val alwaysRecordLogsEnabled by settingsRepository.alwaysRecordLogsEnabledFlow.collectAsStateWithLifecycle(
                initialValue = false
            )
            LaunchedEffect(devModeEnabled, alwaysRecordLogsEnabled) {
                StartupLogInitializer.sync(
                    context = this@MainActivity,
                    devModeEnabled = devModeEnabled,
                    alwaysRecordLogsEnabled = alwaysRecordLogsEnabled
                )
            }

            val dynamicColor by settingsRepository.dynamicColorFlow.collectAsStateWithLifecycle(
                initialValue = startupThemeSnapshot.dynamicColor
            )
            val forceDark by settingsRepository.forceDarkFlow.collectAsStateWithLifecycle(
                initialValue = startupThemeSnapshot.forceDark
            )
            val followSystemDark by settingsRepository.followSystemDarkFlow.collectAsStateWithLifecycle(
                initialValue = startupThemeSnapshot.followSystemDark
            )
            val startupStageFlow = remember(settingsRepository) {
                StartupStageFlow.from(
                    disclaimerAccepted = settingsRepository.disclaimerAcceptedFlow,
                    startupOnboardingCompleted = settingsRepository.startupOnboardingCompletedFlow
                )
            }

            val systemDark = rememberActualSystemDarkTheme()
            val currentResourceDark = StartupResourceNightMode.isDark(resources.configuration.uiMode)
            val nightModeSyncPlan = remember(forceDark, followSystemDark, systemDark, currentResourceDark) {
                StartupNightModeSyncPlanner.plan(
                    forceDark = forceDark,
                    followSystemDark = followSystemDark,
                    systemDark = systemDark,
                    currentResourceDark = currentResourceDark
                )
            }
            val useDark = nightModeSyncPlan.useDark
            LaunchedEffect(followSystemDark, forceDark, nightModeSyncPlan) {
                if (nightModeSyncPlan.shouldApplyNightMode) {
                    NightModeHelper.applyNightMode(
                        followSystemDark = followSystemDark,
                        forceDark = forceDark
                    )
                }
            }

            if (StartupNotificationPermission.shouldRequest()) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {  }
                LaunchedEffect(Unit) {
                    launcher.launch(StartupNotificationPermission.permission)
                }
            }

            NeriTheme(useDark = useDark, useDynamic = dynamicColor) {
                        val startupScope = rememberCoroutineScope()
                        val clipboardManager = remember {
                            getSystemService(ClipboardManager::class.java)
                        }
                        var pendingStartupCrashReport by remember {
                            mutableStateOf<CrashReportStore.PendingCrashReport?>(null)
                        }
                        val exportCrashReportLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.CreateDocument("text/plain")
                        ) { uri: Uri? ->
                            val report = pendingStartupCrashReport ?: return@rememberLauncherForActivityResult
                            uri ?: return@rememberLauncherForActivityResult
                            startupScope.launch(Dispatchers.IO) {
                                runCatching {
                                    startupCrashReportManager.exportReport(
                                        reportFile = report.file,
                                        destination = uri
                                    )
                                }.onSuccess {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.log_exported),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }.onFailure { error ->
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.log_export_failed, error.message),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                        LaunchedEffect(Unit) {
                            pendingStartupCrashReport = startupCrashReportManager.readPendingReport()
                        }
                        LaunchedEffect(Unit) {
                            handleIncomingIntent(intent)
                            inspectClipboardForListenTogetherInvite()
                        }
                        SideEffect {
                            val controller = WindowInsetsControllerCompat(window, window.decorView)
                            controller.isAppearanceLightStatusBars = !useDark
                            controller.isAppearanceLightNavigationBars = !useDark
                        }

                // 入场动画状态
                var playedEntrance by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) { playedEntrance = true }

                val stage by startupStageFlow.collectAsStateWithLifecycle(initialValue = StartupStage.Loading)
                val pendingMobileDataDownloadInterruptionRequest by
                    GlobalDownloadManager.mobileDataDownloadInterruptionRequest.collectAsStateWithLifecycle()
                val rootLifecycleOwner = LocalLifecycleOwner.current
                var hasShownTokenWarning by rememberSaveable { mutableStateOf(false) }
                var showTokenWarningDialog by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(stage, rootLifecycleOwner.lifecycle) {
                    if (stage != StartupStage.Main) {
                        return@LaunchedEffect
                    }
                    StartupDownloadRecoveryCoordinator(
                        context = this@MainActivity,
                        awaitResumed = {
                            while (!rootLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                delay(100L)
                            }
                        }
                    ).requestWhenMainReady()
                }
                LaunchedEffect(stage, rootLifecycleOwner.lifecycle) {
                    if (stage != StartupStage.Main) {
                        return@LaunchedEffect
                    }
                    rootLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        val warningResult = startupSyncWarningCoordinator.check(hasShownTokenWarning)
                        hasShownTokenWarning = warningResult.hasShownWarning

                        if (warningResult.showWarning) {
                            NPLogger.d("MainActivity", "显示 GitHub 配置警告")
                            showTokenWarningDialog = true
                        }
                    }
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
                        StartupStage.Loading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .navigationBarsPadding()
                            )
                        }
                        StartupStage.Disclaimer -> {
                            val scope = rememberCoroutineScope()
                            DisclaimerScreen(
                                onAgree = { scope.launch { settingsRepository.setDisclaimerAccepted(true) } }
                            )
                        }
                        StartupStage.Onboarding -> {
                            StartupOnboardingScreen()
                        }
                        StartupStage.Main -> {
                            // 弹窗状态管理和事件监听
                            var showDialog by remember { mutableStateOf(false) }
                            var dialogMessage by remember { mutableStateOf("") }
                            var showErrorDialog by remember { mutableStateOf(false) }
                            var errorTitle by remember { mutableStateOf("") }
                            var errorMessage by remember { mutableStateOf("") }
                            val lifecycleOwner = LocalLifecycleOwner.current
                            val scope = rememberCoroutineScope()
                            var joiningInvite by remember { mutableStateOf(false) }
                            val pendingInvite by listenTogetherInviteFlow.collectAsStateWithLifecycle()
                            val listenTogetherStatus by listenTogetherStatusFlow.collectAsStateWithLifecycle()
                            val listenTogetherSessionState by AppContainer.listenTogetherSessionManager.sessionState
                                .collectAsStateWithLifecycle()
                            val listenTogetherRoomState by AppContainer.listenTogetherSessionManager.roomState
                                .collectAsStateWithLifecycle()
                            val isListenTogetherRoomActive = !listenTogetherSessionState.roomId.isNullOrBlank()
                            var hadActiveListenTogetherRoom by rememberSaveable { mutableStateOf(false) }
                            var lastShownListenTogetherNotice by rememberSaveable { mutableStateOf<String?>(null) }
                            val effectiveListenTogetherStatus = when {
                                joiningInvite -> getString(R.string.listen_together_status_joining)
                                !listenTogetherStatus.isNullOrBlank() -> listenTogetherStatus
                                isListenTogetherRoomActive &&
                                    listenTogetherSessionState.connectionState == moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState.CONNECTING ->
                                    getString(R.string.listen_together_status_syncing)
                                isListenTogetherRoomActive -> getString(R.string.listen_together_status_active)
                                else -> null
                            }
                            val showLeaveListenTogetherAction = isListenTogetherRoomActive &&
                                shouldOfferListenTogetherLeaveAction(dialogMessage)

                            // 初始化异常处理器事件监听
                            LaunchedEffect(Unit) {
                                ExceptionHandler.errorEvents.collect { event ->
                                    errorTitle = event.title
                                    errorMessage = event.message
                                    showErrorDialog = true
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

                            LaunchedEffect(
                                listenTogetherSessionState.roomId,
                                listenTogetherSessionState.connectionState
                            ) {
                                updateListenTogetherStatus(
                                    when {
                                        listenTogetherSessionState.roomId.isNullOrBlank() -> null
                                        listenTogetherSessionState.connectionState == moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState.CONNECTING ->
                                            getString(R.string.listen_together_status_syncing)
                                        else -> getString(R.string.listen_together_status_active)
                                    }
                                )
                            }

                            LaunchedEffect(isListenTogetherRoomActive) {
                                when {
                                    isListenTogetherRoomActive -> hadActiveListenTogetherRoom = true
                                    hadActiveListenTogetherRoom -> {
                                        clearListenTogetherInviteCache()
                                        hadActiveListenTogetherRoom = false
                                    }
                                }
                            }

                            LaunchedEffect(effectiveListenTogetherStatus) {
                                effectiveListenTogetherStatus?.let(::showListenTogetherStatusToast)
                            }

                            LaunchedEffect(
                                listenTogetherSessionState.roomNotice,
                                listenTogetherRoomState?.version
                            ) {
                                val notice = listenTogetherSessionState.roomNotice ?: return@LaunchedEffect
                                val displayNotice = notice.toListenTogetherDisplayMessage()
                                if (displayNotice.isBlank()) {
                                    return@LaunchedEffect
                                }
                                val noticeKey = "${listenTogetherRoomState?.version ?: -1L}:$notice"
                                if (lastShownListenTogetherNotice == noticeKey) {
                                    return@LaunchedEffect
                                }
                                lastShownListenTogetherNotice = noticeKey
                                showListenTogetherStatusToast(
                                    message = displayNotice,
                                    atBottom = true
                                )
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
                                    },
                                    dismissButton = if (showLeaveListenTogetherAction) {
                                        {
                                            HapticTextButton(
                                                onClick = {
                                                    AppContainer.listenTogetherSessionManager.leaveRoom()
                                                    showDialog = false
                                                }
                                            ) {
                                                Text(stringResource(R.string.listen_together_leave_room))
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }

                            // 异常错误弹窗
                            pendingInvite?.let { invite ->
                                val inviterNickname = invite.inviterNickname
                                AlertDialog(
                                    onDismissRequest = {
                                        if (!joiningInvite) {
                                            clearPendingListenTogetherInvite()
                                        }
                                    },
                                    title = { Text(stringResource(R.string.listen_together_join_invite_title)) },
                                    text = {
                                        Text(
                                            if (!inviterNickname.isNullOrBlank()) {
                                                stringResource(
                                                    R.string.listen_together_join_invite_message_with_inviter,
                                                    inviterNickname,
                                                    invite.roomId
                                                )
                                            } else {
                                                stringResource(
                                                    R.string.listen_together_join_invite_message,
                                                    invite.roomId
                                                )
                                            }
                                        )
                                    },
                                    confirmButton = {
                                        HapticTextButton(
                                            onClick = {
                                                scope.launch {
                                                    joiningInvite = true
                                                    try {
                                                        val preferences = AppContainer.listenTogetherPreferences
                                                        val sessionManager = AppContainer.listenTogetherSessionManager
                                                        updateListenTogetherStatus(getString(R.string.listen_together_status_joining))
                                                        val savedBaseUrlInput = preferences.workerBaseUrlInputFlow.first()
                                                        val savedBaseUrl = preferences.workerBaseUrlFlow.first()
                                                        val baseUrl = resolveListenTogetherInviteJoinBaseUrl(
                                                            invite = invite,
                                                            savedBaseUrlInput = savedBaseUrlInput,
                                                            savedBaseUrl = savedBaseUrl
                                                        )
                                                        val userUuid = preferences.getOrCreateUserUuid()
                                                        val nickname = preferences.getOrCreateNickname()
                                                        preferences.setWorkerBaseUrl(baseUrl)
                                                        invite.baseUrl?.let {
                                                            preferences.setWorkerBaseUrlInput(baseUrl)
                                                        }
                                                        updateListenTogetherStatus(getString(R.string.listen_together_status_syncing))
                                                        sessionManager.joinRoom(
                                                            baseUrl = baseUrl,
                                                            roomId = invite.roomId,
                                                            userUuid = userUuid,
                                                            nickname = nickname
                                                        )
                                                        sessionManager.connectWebSocket()
                                                        clearPendingListenTogetherInvite()
                                                    } catch (error: Throwable) {
                                                        updateListenTogetherStatus(null)
                                                        dialogMessage = (
                                                            error.message ?: error.javaClass.simpleName
                                                            ).toListenTogetherDisplayMessage()
                                                        showDialog = true
                                                    } finally {
                                                        joiningInvite = false
                                                    }
                                                }
                                            },
                                            enabled = !joiningInvite
                                        ) {
                                            Text(
                                                if (joiningInvite) {
                                                    stringResource(R.string.listen_together_joining_room)
                                                } else {
                                                    stringResource(R.string.listen_together_join_room)
                                                }
                                            )
                                        }
                                    },
                                    dismissButton = {
                                        HapticTextButton(
                                            onClick = { clearPendingListenTogetherInvite() },
                                            enabled = !joiningInvite
                                        ) {
                                            Text(stringResource(R.string.action_cancel))
                                        }
                                    }
                                )
                            }

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

                            NeriApp(
                                initialThemeSnapshot = startupThemeSnapshot,
                                onIsDarkChanged = { isDark ->
                                    // 仅调整窗口底色 & 系统栏外观
                                    applyWindowBackground(isDark)
                                }
                            )
                        }
                    }
                }

                pendingMobileDataDownloadInterruptionRequest?.let { request ->
                    MobileDataDownloadInterruptionDialog(
                        request = request,
                        onContinue = {
                            GlobalDownloadManager.continueDownloadsOnMobileData(this@MainActivity, request)
                        },
                        onWaitWifi = {
                            GlobalDownloadManager.waitDownloadsForWifi(request)
                        },
                        onCancelAll = {
                            GlobalDownloadManager.cancelAllDownloadsForMobileData(request)
                        }
                    )
                }

                if (showTokenWarningDialog) {
                    GitHubSyncWarningDialog(
                        onConfirm = {
                            showTokenWarningDialog = false
                        },
                        onDismissReminder = {
                            showTokenWarningDialog = false
                            startupScope.launch {
                                startupSyncWarningCoordinator.dismissReminder()
                            }
                        }
                    )
                }

                pendingStartupCrashReport?.let { report ->
                    StartupCrashReportDialog(
                        report = report,
                        onCopy = {
                            startupScope.launch(Dispatchers.IO) {
                                val fullContent = startupCrashReportManager.readFullReport(report.file)
                                    ?: report.previewContent
                                withContext(Dispatchers.Main) {
                                    if (fullContent.isNotBlank()) {
                                        clipboardManager?.setPrimaryClip(
                                            ClipData.newPlainText("crash_report", fullContent)
                                        )
                                        Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.log_copied),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.log_cannot_read),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },
                        onExport = {
                            exportCrashReportLauncher.launch(report.file.name)
                        },
                        onClose = {
                            startupCrashReportManager.clearPendingReport()
                            pendingStartupCrashReport = null
                        }
                    )
                }
            }
        }

        scheduleStartupSyncIfNeeded()
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

    private fun restoreFromSafeMode() {
        runCatching {
            SafeModeRecoveryCoordinator(
                initializeNormalComponents = {
                    (application as? NeriPlayerApplication)?.initializeNormalComponents()
                },
                restoreNormalStartup = {
                    SafeModeManager.restoreNormalStartup(this)
                }
            ).restore()
            val restartIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(restartIntent)
            finish()
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.safe_mode_restore_failed, error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (safeModeActive) {
            setIntent(intent)
            return
        }
        setIntent(intent)
        handleIncomingIntent(intent)
        scheduleClipboardInviteInspection(immediate = true)
    }

    override fun onResume() {
        super.onResume()
        if (safeModeActive) {
            return
        }
        startPendingExternalAudioServiceIfNeeded()
        if (hasWindowFocusForClipboardInspection) {
            scheduleClipboardInviteInspection()
        }
    }

    override fun onPause() {
        clipboardInviteInspectJob?.cancel()
        super.onPause()
    }

    override fun onStop() {
        if (!safeModeActive) {
            PlayerManager.flushPlaybackStatsAsync("activity_stop")
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (safeModeActive) {
            return
        }
        hasWindowFocusForClipboardInspection = hasFocus
        if (hasFocus) {
            startPendingExternalAudioServiceIfNeeded()
            scheduleClipboardInviteInspection()
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (handleListenTogetherInviteIntent(intent)) return
        handleExternalAudioIntent(intent)
    }

    private fun handleListenTogetherInviteIntent(intent: Intent?): Boolean {
        val invite = parseListenTogetherInvite(intent?.data) ?: return false
        presentListenTogetherInvite(invite)
        setIntent(Intent(this, MainActivity::class.java))
        return true
    }

    private fun inspectClipboardForListenTogetherInvite() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val clipText = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        val invite = parseListenTogetherInvite(clipText)
        if (invite == null) {
            lastObservedClipboardInviteSignature = null
            return
        }
        if (lastObservedClipboardInviteSignature == invite.signature) return
        lastObservedClipboardInviteSignature = invite.signature
        presentListenTogetherInvite(invite)
    }

    private fun scheduleClipboardInviteInspection(immediate: Boolean = false) {
        clipboardInviteInspectJob?.cancel()
        clipboardInviteInspectJob = lifecycleScope.launch {
            if (!immediate) {
                delay(180)
            }
            inspectClipboardForListenTogetherInvite()
        }
    }

    private fun clearPendingListenTogetherInvite() {
        pendingListenTogetherInvite.value = null
    }

    private fun clearListenTogetherInviteCache() {
        lastObservedClipboardInviteSignature = null
        clearPendingListenTogetherInvite()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun startPendingExternalAudioServiceIfNeeded() {
        val pendingStart = pendingExternalAudioServiceStart ?: return
        val isDestroyed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed
        if (
            !canUseDirectPlaybackServiceStart(
                isFinishing = isFinishing,
                isDestroyed = isDestroyed,
                lifecycleState = lifecycle.currentState,
                hasWindowFocus = hasWindowFocus()
            )
        ) {
            return
        }
        if (pendingStart.requestToken != externalAudioRequestToken) {
            pendingExternalAudioServiceStart = null
            return
        }
        val started = AudioPlayerService.startSyncService(
            this,
            pendingStart.source,
            forceForeground = pendingStart.forceForeground
        )
        if (started) {
            NPLogger.d(
                "MainActivity",
                "Retried audio service start after activity resumed: source=${pendingStart.source}"
            )
            pendingExternalAudioServiceStart = null
        }
    }

    private fun scheduleExternalAudioMetadataHydration(
        requestToken: Long,
        quickSong: moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
    ) {
        externalAudioMetadataHydrationJob?.cancel()
        externalAudioMetadataHydrationJob = lifecycleScope.launch {
            delay(1200L)
            if (requestToken != externalAudioRequestToken) {
                return@launch
            }
            val detailedSong = withContext(Dispatchers.IO) {
                runCatching {
                    LocalMediaSupport.inspect(this@MainActivity, quickSong)
                        ?.let(LocalMediaSupport::toSongItem)
                }.getOrElse {
                    NPLogger.w(
                        "MainActivity",
                        "External audio metadata hydration skipped: ${it.message}"
                    )
                    null
                }
            } ?: return@launch
            if (requestToken != externalAudioRequestToken) {
                return@launch
            }
            PlayerManager.hydrateSongMetadata(
                originalSong = quickSong,
                updatedSong = LocalAudioImportManager.mergeImportedSongMetadata(
                    quickSong = quickSong,
                    detailedSong = detailedSong
                )
            )
        }
    }

    private fun updateListenTogetherStatus(message: String?) {
        listenTogetherStatusMessage.value = message
    }

    private fun showListenTogetherStatusToast(
        message: String,
        atBottom: Boolean = false
    ) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).apply {
            if (atBottom) {
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 220)
            } else {
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 180)
            }
        }.show()
    }

    private fun scheduleStartupSyncIfNeeded() {
        lifecycleScope.launch {
            StartupSyncScheduler(
                context = this@MainActivity,
                ioDispatcher = Dispatchers.IO,
                isStarted = {
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                }
            ).scheduleIfNeeded()
        }
    }

    private fun shouldOfferListenTogetherLeaveAction(message: String): Boolean {
        return message == getString(R.string.listen_together_error_controller_offline) ||
            message == getString(R.string.listen_together_error_unauthorized) ||
            message == getString(R.string.listen_together_error_room_not_found) ||
            message == getString(R.string.listen_together_notice_room_closed) ||
            message == getString(R.string.listen_together_error_reconnecting) ||
            message == getString(R.string.listen_together_error_rejoining)
    }

    private fun String.toListenTogetherDisplayMessage(): String {
        val normalized = trim()
        val lowered = normalized.lowercase()
        return when {
            startsWith("controller_offline:") -> {
                val minutes = substringAfter(':')
                    .toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?.coerceAtMost(Int.MAX_VALUE.toLong())
                    ?.toInt()
                    ?: 10
                resources.getQuantityString(
                    R.plurals.listen_together_notice_controller_offline,
                    minutes,
                    minutes
                )
            }
            startsWith("member_joined:") ->
                getString(R.string.listen_together_notice_member_joined, substringAfter(':'))
            startsWith("member_left:") ->
                getString(R.string.listen_together_notice_member_left, substringAfter(':'))
            normalized == "controller_reconnected" ->
                getString(R.string.listen_together_notice_controller_reconnected)
            normalized == "controller_timeout" ||
                normalized == "room_closed" ||
                "room closed" in lowered ->
                getString(R.string.listen_together_notice_room_closed)
            "unauthorized" in lowered ||
                "http=401" in lowered ||
                "(401)" in lowered ->
                getString(R.string.listen_together_error_unauthorized)
            "room not initialized" in lowered ||
                "not found in do" in lowered ->
                getString(R.string.listen_together_error_room_not_found)
            "controller offline" in lowered ->
                getString(R.string.listen_together_error_controller_offline)
            "member control disabled" in lowered ->
                getString(R.string.listen_together_error_member_control_disabled)
            normalized == getString(R.string.listen_together_error_reconnecting) ||
                ("listen together" in lowered && "reconnect" in lowered) ->
                getString(R.string.listen_together_error_reconnecting)
            normalized == getString(R.string.listen_together_error_rejoining) ||
                ("rejoin" in lowered && "room" in lowered) ->
                getString(R.string.listen_together_error_rejoining)
            else -> normalized
        }
    }

    private fun presentListenTogetherInvite(invite: ListenTogetherInvite) {
        val currentRoomId = AppContainer.listenTogetherSessionManager.sessionState.value.roomId
            ?.let(::normalizeListenTogetherRoomId)
        if (currentRoomId != null && currentRoomId == invite.roomId) {
            return
        }
        if (pendingListenTogetherInvite.value?.signature == invite.signature) return
        pendingListenTogetherInvite.value = invite
    }

    private fun handleExternalAudioIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val uriList: List<Uri> = when (action) {
            Intent.ACTION_VIEW -> intent.data?.let(::listOf) ?: emptyList()
            Intent.ACTION_SEND -> getSharedSingleUri(intent)?.let(::listOf) ?: emptyList()
            Intent.ACTION_SEND_MULTIPLE -> getSharedMultipleUris(intent)
            else -> emptyList()
        }
        if (uriList.isEmpty()) return

        externalAudioImportJob?.cancel()
        externalAudioMetadataHydrationJob?.cancel()
        val requestToken = ++externalAudioRequestToken
        pendingExternalAudioServiceStart = null
        setIntent(Intent(this, MainActivity::class.java))

        externalAudioImportJob = lifecycleScope.launch {
            try {
                val result = LocalAudioImportManager.importExternalSongs(this@MainActivity, uriList)
                if (requestToken != externalAudioRequestToken) {
                    return@launch
                }
                if (result.songs.isNotEmpty()) {
                    PlayerManager.initialize(application)
                    PlayerManager.playPlaylist(result.songs, startIndex = 0)
                    result.songs.firstOrNull()?.let { firstSong ->
                        scheduleExternalAudioMetadataHydration(requestToken, firstSong)
                    }
                    // 让播放状态和 mini player 先稳定一帧，再拉起前台服务
                    delay(16L)
                    if (requestToken != externalAudioRequestToken) {
                        return@launch
                    }
                    NPLogger.d("MainActivity", "Starting audio service after external audio import")
                    val serviceStarted = AudioPlayerService.startSyncService(
                        this@MainActivity,
                        "external_audio_import",
                        forceForeground = true
                    )
                    if (!serviceStarted) {
                        pendingExternalAudioServiceStart = PendingAudioServiceStart(
                            requestToken = requestToken,
                            source = "external_audio_import",
                            forceForeground = true
                        )
                        NPLogger.w(
                            "MainActivity",
                            "Deferred audio service start until activity is resumed"
                        )
                    }
                }
            } catch (_: CancellationException) {
                // 只保留最新一次外部唤起请求
            }
        }
    }

    override fun onDestroy() {
        if (!safeModeActive) {
            PlayerManager.flushPlaybackStatsAsync("activity_destroy")
        }
        clipboardInviteInspectJob?.cancel()
        externalAudioImportJob?.cancel()
        externalAudioMetadataHydrationJob?.cancel()
        super.onDestroy()
        ExceptionHandler.cleanup()
    }

    @Suppress("DEPRECATION")
    private fun getSharedSingleUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun getSharedMultipleUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
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

@Composable
private fun StartupCrashReportDialog(
    report: CrashReportStore.PendingCrashReport,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                text = when (report.origin) {
                    CrashReportStore.CrashOrigin.Jvm ->
                        stringResource(R.string.startup_crash_report_title_jvm)
                    CrashReportStore.CrashOrigin.Native ->
                        stringResource(R.string.startup_crash_report_title_native)
                    CrashReportStore.CrashOrigin.Anr ->
                        stringResource(R.string.startup_crash_report_title_anr)
                    CrashReportStore.CrashOrigin.Unknown ->
                        stringResource(R.string.startup_crash_report_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.startup_crash_report_desc,
                        report.file.name
                    )
                )
                if (report.previewTruncated) {
                    Text(
                        text = stringResource(R.string.startup_crash_report_truncated),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = if (report.previewContent.isBlank()) {
                        stringResource(R.string.log_cannot_read)
                    } else {
                        report.previewContent
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HapticTextButton(onClick = onCopy) {
                    Text(stringResource(R.string.debug_copy_all))
                }
                HapticTextButton(onClick = onExport) {
                    Text(stringResource(R.string.log_export))
                }
                HapticTextButton(onClick = onClose) {
                    Text(stringResource(R.string.action_close))
                }
            }
        },
        dismissButton = null
    )
}

/* --------------------- 免责声明与隐私说明 --------------------- */

@Composable
fun DisclaimerScreen(
    onAgree: () -> Unit,
    initialCountdownSeconds: Int = 5
) {
    var countdown by remember(initialCountdownSeconds) {
        mutableIntStateOf(initialCountdownSeconds.coerceAtLeast(0))
    }
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
                    shape = RoundedCornerShape(12.dp),
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
    Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Start)
}
@Composable private fun EmphasisText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Start
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
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
