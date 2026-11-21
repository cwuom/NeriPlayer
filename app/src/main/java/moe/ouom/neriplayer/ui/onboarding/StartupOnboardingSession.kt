package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.i18n.asString
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.LoginSuccessDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountPlatform
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsBiliAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsNeteaseAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsYouTubeAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsGitHubDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsWebDavDialogs
import moe.ouom.neriplayer.ui.sync.upgrade.StartupSyncUpgradePrompt
import moe.ouom.neriplayer.ui.sync.upgrade.SyncProtocolUpgradeViewModel
import moe.ouom.neriplayer.ui.sync.upgrade.rememberSyncProtocolUpgradeViewModel
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel

/**
 * 账号登录和同步只在用户同意使用说明后才创建, 同意之前的外观步骤不会触碰任何账户或网络状态
 */
@Stable
internal class StartupOnboardingSession(
    val neteaseVm: NeteaseAuthViewModel,
    val biliVm: BiliAuthViewModel,
    val youTubeVm: YouTubeAuthViewModel,
    val githubVm: GitHubSyncViewModel,
    val webDavVm: WebDavSyncViewModel,
    val syncUpgradeVm: SyncProtocolUpgradeViewModel
) {
    var inlineMessage by mutableStateOf<UiText?>(null)
    var loginSuccessTitle by mutableStateOf<Int?>(null)
    var showNeteaseSheet by mutableStateOf(false)
    var showNeteaseConfirm by mutableStateOf(false)
    var showNeteaseSavedCookieDialog by mutableStateOf(false)
    var neteaseMaskedPhone by mutableStateOf<String?>(null)
    var neteaseSheetTab by mutableIntStateOf(0)
    var showBiliSheet by mutableStateOf(false)
    var showBiliSavedCookieDialog by mutableStateOf(false)
    var biliSheetTab by mutableIntStateOf(0)
    var showYouTubeSheet by mutableStateOf(false)
    var showYouTubeSavedCookieDialog by mutableStateOf(false)
    var youTubeSheetTab by mutableIntStateOf(0)
    var showGitHubConfigDialog by mutableStateOf(false)
    var showClearGitHubConfigDialog by mutableStateOf(false)
    var showWebDavConfigDialog by mutableStateOf(false)
    var showClearWebDavConfigDialog by mutableStateOf(false)

    val anySyncDialogVisible: Boolean
        get() = showGitHubConfigDialog || showClearGitHubConfigDialog ||
            showWebDavConfigDialog || showClearWebDavConfigDialog

    fun openLogin(platform: SettingsAccountPlatform) {
        inlineMessage = null
        when (platform) {
            SettingsAccountPlatform.Bilibili -> {
                biliSheetTab = 0
                showBiliSheet = true
            }
            SettingsAccountPlatform.Netease -> {
                neteaseSheetTab = 0
                showNeteaseSheet = true
            }
            SettingsAccountPlatform.YouTube -> {
                youTubeSheetTab = 0
                showYouTubeSheet = true
            }
            SettingsAccountPlatform.QqMusic -> Unit
        }
    }

    fun openSavedCookies(platform: SettingsAccountPlatform) {
        inlineMessage = null
        when (platform) {
            SettingsAccountPlatform.Bilibili -> showBiliSavedCookieDialog = true
            SettingsAccountPlatform.Netease -> showNeteaseSavedCookieDialog = true
            SettingsAccountPlatform.YouTube -> showYouTubeSavedCookieDialog = true
            SettingsAccountPlatform.QqMusic -> Unit
        }
    }
}

@Composable
internal fun rememberStartupOnboardingSession(): StartupOnboardingSession {
    val neteaseVm: NeteaseAuthViewModel = viewModel()
    val biliVm: BiliAuthViewModel = viewModel()
    val youTubeVm: YouTubeAuthViewModel = viewModel()
    val githubVm: GitHubSyncViewModel = viewModel()
    val webDavVm: WebDavSyncViewModel = viewModel()
    val syncUpgradeVm = rememberSyncProtocolUpgradeViewModel()
    val session = remember(neteaseVm, biliVm, youTubeVm, githubVm, webDavVm, syncUpgradeVm) {
        StartupOnboardingSession(neteaseVm, biliVm, youTubeVm, githubVm, webDavVm, syncUpgradeVm)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        fun refresh() {
            neteaseVm.refreshAuthHealth()
            biliVm.refreshAuthHealth()
            youTubeVm.refreshAuthHealth()
        }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val applicationContext = LocalContext.current.applicationContext
    LaunchedEffect(githubVm, applicationContext) { githubVm.initialize(applicationContext) }
    LaunchedEffect(webDavVm, applicationContext) { webDavVm.initialize(applicationContext) }
    LaunchedEffect(session) {
        neteaseVm.events.collect { event ->
            when (event) {
                is NeteaseAuthEvent.ShowSnack -> session.inlineMessage = event.message
                is NeteaseAuthEvent.AskConfirmSend -> {
                    session.neteaseMaskedPhone = event.masked
                    session.showNeteaseConfirm = true
                }
                NeteaseAuthEvent.LoginSuccess -> {
                    session.showNeteaseSavedCookieDialog = false
                    session.inlineMessage = null
                    session.showNeteaseSheet = false
                    session.loginSuccessTitle = CoreCommonR.string.settings_netease_login_success
                    neteaseVm.refreshAuthHealth()
                }
            }
        }
    }
    LaunchedEffect(session) {
        biliVm.events.collect { event ->
            when (event) {
                is BiliAuthEvent.ShowSnack -> session.inlineMessage = event.message
                BiliAuthEvent.LoginSuccess -> {
                    session.showBiliSavedCookieDialog = false
                    session.inlineMessage = null
                    session.showBiliSheet = false
                    session.loginSuccessTitle = CoreCommonR.string.settings_bili_login_success
                    biliVm.refreshAuthHealth()
                }
            }
        }
    }
    LaunchedEffect(session) {
        youTubeVm.events.collect { event ->
            when (event) {
                is YouTubeAuthEvent.ShowSnack -> session.inlineMessage = event.message
                YouTubeAuthEvent.LoginSuccess -> {
                    session.showYouTubeSavedCookieDialog = false
                    session.inlineMessage = null
                    session.showYouTubeSheet = false
                    session.loginSuccessTitle = CoreCommonR.string.settings_youtube_login_success
                    youTubeVm.refreshAuthHealth()
                }
            }
        }
    }
    return session
}

@Composable
internal fun rememberStartupNoPlatformConnected(session: StartupOnboardingSession): Boolean {
    val neteaseState by session.neteaseVm.uiState.collectAsStateWithLifecycle()
    val biliState by session.biliVm.uiState.collectAsStateWithLifecycle()
    val youTubeState by session.youTubeVm.uiState.collectAsStateWithLifecycle()
    return shouldWarnStartupNoPlatformConnected(
        biliState = biliState.health.state,
        neteaseState = neteaseState.health.state,
        youTubeState = youTubeState.health.state
    )
}

@Composable
internal fun StartupPlatformsStepBody(session: StartupOnboardingSession, isActive: Boolean) {
    val neteaseState by session.neteaseVm.uiState.collectAsStateWithLifecycle()
    val biliState by session.biliVm.uiState.collectAsStateWithLifecycle()
    val youTubeState by session.youTubeVm.uiState.collectAsStateWithLifecycle()
    StartupPlatformAccounts(
        inlineMessage = session.inlineMessage?.asString(),
        onInlineMessageChange = { session.inlineMessage = null },
        biliState = biliState,
        neteaseState = neteaseState,
        youTubeState = youTubeState,
        isActive = isActive,
        onLogin = session::openLogin,
        onManageSaved = session::openSavedCookies
    )
}

@Composable
internal fun StartupBackupRestoreStepBody(session: StartupOnboardingSession) {
    val context = LocalContext.current
    val githubState by session.githubVm.uiState.collectAsStateWithLifecycle()
    val webDavState by session.webDavVm.uiState.collectAsStateWithLifecycle()
    val syncUpgradeState by session.syncUpgradeVm.uiState.collectAsStateWithLifecycle()
    val githubTarget = if (githubState.isConfigured) {
        SyncProtocolUpgradeRepository.githubTargetHash(githubState.repoOwner, githubState.repoName)
    } else null
    val webDavTarget = if (webDavState.isConfigured) {
        SyncProtocolUpgradeRepository.webDavTargetHash(
            webDavState.serverUrl, webDavState.basePath, webDavState.username
        )
    } else null
    LaunchedEffect(githubTarget, webDavTarget) {
        session.syncUpgradeVm.refreshTargets()
    }
    StartupBackupRestoreSyncGate(
        state = syncUpgradeState,
        onRetry = session.syncUpgradeVm::refreshTargets
    ) {
        BackupRestoreContent(
            gitHubState = githubState,
            webDavState = webDavState,
            onDismissGitHubMessage = session.githubVm::clearMessages,
            onDismissWebDavMessage = session.webDavVm::clearMessages,
            onOpenGitHubConfig = {
                session.githubVm.clearMessages()
                session.showGitHubConfigDialog = true
            },
            onOpenClearGitHubConfig = {
                session.githubVm.clearMessages()
                session.showClearGitHubConfigDialog = true
            },
            onToggleGitHubAutoSync = { enabled -> session.githubVm.toggleAutoSync(context, enabled) },
            onGitHubSyncNow = {
                githubTarget?.let { target ->
                    requestStartupBackupSync(target, session.syncUpgradeVm) { targetId, finished, upgradeRequired ->
                        session.githubVm.performSyncForTarget(context, targetId, finished, upgradeRequired)
                    }
                }
            },
            onOpenWebDavConfig = {
                session.webDavVm.clearMessages()
                session.showWebDavConfigDialog = true
            },
            onOpenClearWebDavConfig = {
                session.webDavVm.clearMessages()
                session.showClearWebDavConfigDialog = true
            },
            onToggleWebDavAutoSync = { enabled -> session.webDavVm.toggleAutoSync(context, enabled) },
            onWebDavSyncNow = {
                webDavTarget?.let { target ->
                    requestStartupBackupSync(target, session.syncUpgradeVm) { targetId, finished, upgradeRequired ->
                        session.webDavVm.performSyncForTarget(context, targetId, finished, upgradeRequired)
                    }
                }
            }
        )
    }
}

@Composable
internal fun StartupSessionDialogs(
    session: StartupOnboardingSession,
    backupStepActive: Boolean,
    finishing: Boolean
) {
    val githubState by session.githubVm.uiState.collectAsStateWithLifecycle()
    val webDavState by session.webDavVm.uiState.collectAsStateWithLifecycle()
    val syncUpgradeState by session.syncUpgradeVm.uiState.collectAsStateWithLifecycle()
    SettingsNeteaseAuthDialogs(
        showSheet = session.showNeteaseSheet,
        initialTab = session.neteaseSheetTab,
        onDismissSheet = { session.showNeteaseSheet = false },
        inlineMsg = session.inlineMessage,
        onInlineMsgChange = { session.inlineMessage = it },
        showConfirmDialog = session.showNeteaseConfirm,
        confirmPhoneMasked = session.neteaseMaskedPhone,
        onDismissConfirmDialog = { session.showNeteaseConfirm = false },
        vm = session.neteaseVm,
        showSavedCookieDialog = session.showNeteaseSavedCookieDialog,
        onDismissSavedCookieDialog = { session.showNeteaseSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            session.inlineMessage = null
            session.neteaseSheetTab = tab
            session.showNeteaseSheet = true
        },
        onLogout = {
            session.showNeteaseSavedCookieDialog = false
            session.neteaseVm.clearCookies()
        },
        onBrowserLogin = null
    )
    SettingsBiliAuthDialogs(
        showSheet = session.showBiliSheet,
        initialTab = session.biliSheetTab,
        onDismissSheet = { session.showBiliSheet = false },
        inlineMsg = session.inlineMessage,
        onInlineMsgChange = { session.inlineMessage = it },
        vm = session.biliVm,
        showSavedCookieDialog = session.showBiliSavedCookieDialog,
        onDismissSavedCookieDialog = { session.showBiliSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            session.inlineMessage = null
            session.biliSheetTab = tab
            session.showBiliSheet = true
        },
        onLogout = {
            session.showBiliSavedCookieDialog = false
            session.biliVm.clearCookies()
        },
        onBrowserLogin = null
    )
    SettingsYouTubeAuthDialogs(
        showSheet = session.showYouTubeSheet,
        initialTab = session.youTubeSheetTab,
        onDismissSheet = { session.showYouTubeSheet = false },
        inlineMsg = session.inlineMessage,
        onInlineMsgChange = { session.inlineMessage = it },
        vm = session.youTubeVm,
        showSavedCookieDialog = session.showYouTubeSavedCookieDialog,
        onDismissSavedCookieDialog = { session.showYouTubeSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            session.inlineMessage = null
            session.youTubeSheetTab = tab
            session.showYouTubeSheet = true
        },
        onLogout = {
            session.showYouTubeSavedCookieDialog = false
            session.youTubeVm.clearAuth()
        }
    )
    if (syncUpgradeState.startupRegistrationComplete) {
        SettingsGitHubDialogs(
            showGitHubConfigDialog = session.showGitHubConfigDialog,
            onShowGitHubConfigDialogChange = { session.showGitHubConfigDialog = it },
            showClearGitHubConfigDialog = session.showClearGitHubConfigDialog,
            onShowClearGitHubConfigDialogChange = { session.showClearGitHubConfigDialog = it }
        )
        SettingsWebDavDialogs(
            showWebDavConfigDialog = session.showWebDavConfigDialog,
            onShowWebDavConfigDialogChange = { session.showWebDavConfigDialog = it },
            showClearWebDavConfigDialog = session.showClearWebDavConfigDialog,
            onShowClearWebDavConfigDialogChange = { session.showClearWebDavConfigDialog = it }
        )
    }
    StartupSyncUpgradePrompt(
        canShowDialog = syncUpgradeState.startupRegistrationComplete && backupStepActive && !finishing &&
            !githubState.isSyncing && !webDavState.isSyncing && !session.anySyncDialogVisible,
        viewModel = session.syncUpgradeVm
    )
    session.loginSuccessTitle?.let { title ->
        LoginSuccessDialog(
            title = stringResource(title),
            onDismiss = { session.loginSuccessTitle = null }
        )
    }
}
