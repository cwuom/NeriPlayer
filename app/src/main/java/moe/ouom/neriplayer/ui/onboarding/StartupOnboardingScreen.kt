package moe.ouom.neriplayer.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.PixelCopy
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import moe.ouom.neriplayer.data.settings.DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP
import moe.ouom.neriplayer.data.settings.AdvancedBlurQualityPreference
import moe.ouom.neriplayer.data.settings.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.LyricFontScales
import moe.ouom.neriplayer.data.settings.isCurrentBuildDimensity
import moe.ouom.neriplayer.ui.component.common.ThemeRevealOverlay
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassNavigationHandoff
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneLayer
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassNavigationOwner
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.isAdvancedGlassBackendSupported
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.LoginSuccessDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsBiliAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsNeteaseAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsYouTubeAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.component.InlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsGitHubDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsWebDavDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncUiState
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncUiState
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.util.platform.getDisplayName
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.drawToBitmap
import kotlin.coroutines.resume
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import androidx.core.graphics.createBitmap
import androidx.core.content.ContextCompat
import moe.ouom.neriplayer.core.startup.permission.StartupMediaPermission
import moe.ouom.neriplayer.core.startup.permission.StartupNotificationPermission
import moe.ouom.neriplayer.data.settings.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.ui.CustomBackground

private enum class StartupStep {
    Language,
    Platforms,
    PlaybackSources,
    Permissions,
    PlaybackControls,
    Lyrics,
    Personalize,
    BackupRestore,
    LearningGuide
}

private const val STARTUP_THEME_REVEAL_WATCHDOG_DELAY_MILLIS = 900L
private const val STARTUP_THEME_REVEAL_CAPTURE_TIMEOUT_MILLIS = 500L

internal fun calculateStartupOnboardingProgress(stepIndex: Int, stepCount: Int): Float {
    if (stepCount <= 0) return 0f
    return ((stepIndex + 1).toFloat() / stepCount).coerceIn(0f, 1f)
}

internal fun shouldAdvanceStartupOnboarding(
    permissionRequestActive: Boolean,
    permissionNavigationBlocked: Boolean
): Boolean = !permissionRequestActive && !permissionNavigationBlocked

internal const val STARTUP_NOTIFICATION_PERMISSION_WARNING_ATTEMPTS = 2

internal fun shouldShowStartupNotificationPermissionWarning(
    permissionSupported: Boolean,
    permissionGranted: Boolean,
    attempts: Int
): Boolean = permissionSupported && !permissionGranted &&
    attempts < STARTUP_NOTIFICATION_PERMISSION_WARNING_ATTEMPTS

internal fun shouldWarnStartupNoPlatformConnected(
    biliState: SavedCookieAuthState,
    neteaseState: SavedCookieAuthState,
    youTubeState: YouTubeAuthState
): Boolean = biliState == SavedCookieAuthState.Missing &&
    neteaseState == SavedCookieAuthState.Missing &&
    youTubeState == YouTubeAuthState.Missing

internal fun hasFinishedStartupNotificationPermissionWarning(
    attempts: Int
): Boolean = attempts >= STARTUP_NOTIFICATION_PERMISSION_WARNING_ATTEMPTS

internal fun shouldPromptStartupEnhancedAdvancedBlur(
    advancedBlurAvailable: Boolean,
    enhancedAdvancedBlurEnabled: Boolean,
    backgroundImageImported: Boolean
): Boolean = advancedBlurAvailable && backgroundImageImported && !enhancedAdvancedBlurEnabled

private tailrec fun Context.findStartupActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findStartupActivity()
    else -> null
}

@Composable
fun StartupOnboardingScreen(
    onLanguageChanged: (LanguageManager.Language) -> Unit = {}
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val activity = LocalView.current.context.findStartupActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repo = AppContainer.settingsRepo

    val steps = remember { StartupStep.entries }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val stepTransitionState = rememberStartupOnboardingLayerTransitionState(
        initialStepIndex = stepIndex
    )
    var selectedLanguageCode by rememberSaveable {
        mutableStateOf(LanguageManager.getCurrentLanguage(context).code)
    }
    val selectedLanguage = remember(selectedLanguageCode) {
        LanguageManager.Language.entries.firstOrNull { it.code == selectedLanguageCode }
            ?: LanguageManager.Language.SYSTEM
    }

    val uiDensityScale by repo.uiDensityScaleFlow.collectAsStateWithLifecycle(initialValue = 1.0f)
    var pendingUiScale by rememberSaveable { mutableFloatStateOf(uiDensityScale) }
    LaunchedEffect(uiDensityScale) {
        if ((pendingUiScale - uiDensityScale).absoluteValue > 0.001f) {
            pendingUiScale = uiDensityScale
        }
    }
    val backgroundImageUri by repo.backgroundImageUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val backgroundImageBlur by repo.backgroundImageBlurFlow.collectAsStateWithLifecycle(initialValue = 0f)
    val backgroundImageAlpha by repo.backgroundImageAlphaFlow.collectAsStateWithLifecycle(initialValue = 0.3f)
    val advancedBlurEnabled by repo.advancedBlurEnabledFlow.collectAsStateWithLifecycle(
        initialValue = true
    )
    val enhancedAdvancedBlurEnabled by repo.enhancedAdvancedBlurEnabledFlow.collectAsStateWithLifecycle(
        initialValue = false
    )
    val enhancedAdvancedBlurRadiusDp by repo.enhancedAdvancedBlurRadiusDpFlow
        .collectAsStateWithLifecycle(initialValue = DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP)
    val initialAdvancedBlurQuality = remember {
        AdvancedBlurQualityPreference.defaultForDevice(isCurrentBuildDimensity())
    }
    val advancedBlurQuality by repo.advancedBlurQualityFlow.collectAsStateWithLifecycle(
        initialValue = initialAdvancedBlurQuality
    )
    var pendingBackgroundImageBlur by remember { mutableStateOf<Float?>(null) }
    var pendingBackgroundImageAlpha by remember { mutableStateOf<Float?>(null) }
    val effectiveBackgroundImageBlur = pendingBackgroundImageBlur ?: backgroundImageBlur
    val effectiveBackgroundImageAlpha = pendingBackgroundImageAlpha ?: backgroundImageAlpha
    val playbackControlLayoutPreferences by repo.playbackControlLayoutPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = PlaybackControlLayoutPreferences())
    val lyricFontScales by repo.lyricFontScalesFlow.collectAsStateWithLifecycle(
        initialValue = LyricFontScales(
            coverLyric = 1.0f,
            coverTranslation = 1.0f,
            lyricsPageLyric = 1.0f,
            lyricsPageTranslation = 1.0f
        )
    )
    val neteaseAutoSourceSwitch by repo.neteaseAutoSourceSwitchFlow.collectAsStateWithLifecycle(
        initialValue = false
    )
    val neteaseLocalSourceFallback by repo.neteaseLocalSourceFallbackFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val followSystemDark by repo.followSystemDarkFlow.collectAsStateWithLifecycle(initialValue = true)
    val forceDark by repo.forceDarkFlow.collectAsStateWithLifecycle(initialValue = false)
    val systemDark = isSystemInDarkTheme()
    var pendingFollowSystemDark by remember { mutableStateOf<Boolean?>(null) }
    var pendingForceDark by remember { mutableStateOf<Boolean?>(null) }
    val effectiveThemeMode = resolveStartupOnboardingThemeMode(
        storedFollowSystemDark = followSystemDark,
        storedForceDark = forceDark,
        pendingFollowSystemDark = pendingFollowSystemDark,
        pendingForceDark = pendingForceDark
    )
    val isDarkTheme = effectiveThemeMode.resolveUseDark(systemDark)
    val latestIsDarkTheme = rememberUpdatedState(isDarkTheme)
    val latestEnhancedAdvancedBlurEnabled = rememberUpdatedState(enhancedAdvancedBlurEnabled)

    LaunchedEffect(backgroundImageBlur, pendingBackgroundImageBlur) {
        if (
            pendingBackgroundImageBlur != null &&
            ((pendingBackgroundImageBlur ?: backgroundImageBlur) - backgroundImageBlur)
                .absoluteValue < 0.001f
        ) {
            pendingBackgroundImageBlur = null
        }
    }
    LaunchedEffect(backgroundImageAlpha, pendingBackgroundImageAlpha) {
        if (
            pendingBackgroundImageAlpha != null &&
            ((pendingBackgroundImageAlpha ?: backgroundImageAlpha) - backgroundImageAlpha)
                .absoluteValue < 0.001f
        ) {
            pendingBackgroundImageAlpha = null
        }
    }

    val sdkInt = Build.VERSION.SDK_INT
    val enhancedAdvancedBlurAvailable = isAdvancedGlassBackendSupported(sdkInt)
    val advancedGlassController = remember(
        sdkInt,
        advancedBlurEnabled,
        enhancedAdvancedBlurEnabled,
        enhancedAdvancedBlurRadiusDp,
        advancedBlurQuality
    ) {
        AdvancedGlassController(
            sdkInt = sdkInt,
            advancedBlurEnabled = advancedBlurEnabled,
            enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
            backendReady = enhancedAdvancedBlurAvailable,
            enhancedAdvancedBlurRadiusDp = enhancedAdvancedBlurRadiusDp,
            advancedBlurQuality = advancedBlurQuality
        )
    }
    val backgroundGlassBackdrop = rememberAdvancedGlassBackdrop()
    val contentGlassBackdrop = rememberAdvancedGlassBackdrop()
    val notificationPermission = StartupNotificationPermission.permission
    val localMediaPermission = remember(sdkInt) {
        StartupMediaPermission.permissionFor(sdkInt)
    }
    var notificationPermissionGranted by remember(notificationPermission) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, notificationPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var localMediaPermissionGranted by remember(localMediaPermission) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, localMediaPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestActive by remember { mutableStateOf(false) }
    var permissionNavigationBlocked by remember { mutableStateOf(false) }
    var notificationPermissionWarningVisible by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionWarningAttempts by rememberSaveable { mutableIntStateOf(0) }
    var noPlatformWarningVisible by rememberSaveable { mutableStateOf(false) }
    var enhancedAdvancedBlurPromptVisible by rememberSaveable { mutableStateOf(false) }

    var inlineMessage by remember { mutableStateOf<String?>(null) }
    var loginSuccessTitle by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }
    val rootView = LocalView.current
    var themeRevealSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var themeRevealOriginWindow by remember { mutableStateOf<Offset?>(null) }
    var themeRevealStartRadiusPx by remember { mutableFloatStateOf(0f) }
    var themeRevealFallbackColor by remember { mutableStateOf<ComposeColor?>(null) }
    var themeRevealCaptureInFlight by remember { mutableStateOf(false) }
    var themeRevealCaptureJob by remember { mutableStateOf<Job?>(null) }
    var themeRevealCaptureToken by remember { mutableIntStateOf(0) }

    var showNeteaseSheet by remember { mutableStateOf(false) }
    var showNeteaseConfirm by remember { mutableStateOf(false) }
    var showNeteaseSavedCookieDialog by remember { mutableStateOf(false) }
    var neteaseMaskedPhone by remember { mutableStateOf<String?>(null) }
    var neteaseSheetTab by rememberSaveable { mutableIntStateOf(0) }

    var showBiliSheet by remember { mutableStateOf(false) }
    var showBiliSavedCookieDialog by remember { mutableStateOf(false) }
    var biliSheetTab by rememberSaveable { mutableIntStateOf(0) }

    var showYouTubeSheet by remember { mutableStateOf(false) }
    var showYouTubeSavedCookieDialog by remember { mutableStateOf(false) }
    var youTubeSheetTab by rememberSaveable { mutableIntStateOf(0) }
    var showGitHubConfigDialog by remember { mutableStateOf(false) }
    var showClearGitHubConfigDialog by remember { mutableStateOf(false) }
    var showWebDavConfigDialog by remember { mutableStateOf(false) }
    var showClearWebDavConfigDialog by remember { mutableStateOf(false) }

    LaunchedEffect(followSystemDark, pendingFollowSystemDark) {
        if (pendingFollowSystemDark != null && pendingFollowSystemDark == followSystemDark) {
            pendingFollowSystemDark = null
        }
    }
    LaunchedEffect(forceDark, pendingForceDark) {
        if (pendingForceDark != null && pendingForceDark == forceDark) {
            pendingForceDark = null
        }
    }
    val neteaseVm: NeteaseAuthViewModel = viewModel()
    val neteaseState by neteaseVm.uiState.collectAsStateWithLifecycle()
    val biliVm: BiliAuthViewModel = viewModel()
    val biliState by biliVm.uiState.collectAsStateWithLifecycle()
    val youTubeVm: YouTubeAuthViewModel = viewModel()
    val youTubeState by youTubeVm.uiState.collectAsStateWithLifecycle()
    val githubVm: GitHubSyncViewModel = viewModel()
    val githubState by githubVm.uiState.collectAsStateWithLifecycle()
    val webDavVm: WebDavSyncViewModel = viewModel()
    val webDavState by webDavVm.uiState.collectAsStateWithLifecycle()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = BackgroundImageStorage.importFromUri(
                context = context,
                sourceUri = uri,
                previousUriString = backgroundImageUri
            )
            if (imported != null) {
                repo.setBackgroundImageUri(imported.toString())
                if (
                    shouldPromptStartupEnhancedAdvancedBlur(
                        advancedBlurAvailable = enhancedAdvancedBlurAvailable,
                        enhancedAdvancedBlurEnabled = latestEnhancedAdvancedBlurEnabled.value,
                        backgroundImageImported = true
                    )
                ) {
                    enhancedAdvancedBlurPromptVisible = true
                }
            }
        }
    }
    fun showNotificationPermissionWarning() {
        if (
            !shouldShowStartupNotificationPermissionWarning(
                permissionSupported = StartupNotificationPermission.isSupported(sdkInt),
                permissionGranted = notificationPermissionGranted,
                attempts = notificationPermissionWarningAttempts
            )
        ) {
            return
        }
        notificationPermissionWarningAttempts += 1
        notificationPermissionWarningVisible = true
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        permissionRequestActive = false
        if (granted) {
            notificationPermissionWarningVisible = false
            notificationPermissionWarningAttempts = 0
        } else {
            showNotificationPermissionWarning()
        }
        scope.launch {
            delay(500L)
            permissionNavigationBlocked = false
        }
    }
    val localMediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        localMediaPermissionGranted = granted
        permissionRequestActive = false
        scope.launch {
            delay(500L)
            permissionNavigationBlocked = false
        }
    }

    DisposableEffect(lifecycleOwner, neteaseVm, biliVm, youTubeVm) {
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
    LaunchedEffect(githubVm, context) {
        githubVm.initialize(context)
    }
    LaunchedEffect(webDavVm, context) {
        webDavVm.initialize(context)
    }

    LaunchedEffect(neteaseVm) {
        neteaseVm.events.collect { event ->
            when (event) {
                is NeteaseAuthEvent.ShowSnack -> inlineMessage = event.message
                is NeteaseAuthEvent.AskConfirmSend -> {
                    neteaseMaskedPhone = event.masked
                    showNeteaseConfirm = true
                }
                NeteaseAuthEvent.LoginSuccess -> {
                    showNeteaseSavedCookieDialog = false
                    inlineMessage = null
                    showNeteaseSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_netease_login_success
                    )
                    neteaseVm.refreshAuthHealth()
                }
            }
        }
    }

    LaunchedEffect(biliVm) {
        biliVm.events.collect { event ->
            when (event) {
                is BiliAuthEvent.ShowSnack -> inlineMessage = event.message
                BiliAuthEvent.LoginSuccess -> {
                    showBiliSavedCookieDialog = false
                    inlineMessage = null
                    showBiliSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_bili_login_success
                    )
                    biliVm.refreshAuthHealth()
                }
            }
        }
    }

    LaunchedEffect(youTubeVm) {
        youTubeVm.events.collect { event ->
            when (event) {
                is YouTubeAuthEvent.ShowSnack -> inlineMessage = event.message
                YouTubeAuthEvent.LoginSuccess -> {
                    showYouTubeSavedCookieDialog = false
                    inlineMessage = null
                    showYouTubeSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_youtube_login_success
                    )
                    youTubeVm.refreshAuthHealth()
                }
            }
        }
    }

    val baseDensity = LocalDensity.current
    val previewDensity = remember(
        baseDensity.fontScale,
        composeResources.displayMetrics.density,
        pendingUiScale
    ) {
        Density(
            composeResources.displayMetrics.density * pendingUiScale,
            baseDensity.fontScale
        )
    }

    fun selectLanguage(language: LanguageManager.Language) {
        if (selectedLanguage == language) return
        selectedLanguageCode = language.code
        LanguageManager.setLanguage(context, language)
        onLanguageChanged(language)
    }

    fun finishOnboarding() {
        if (finishing) return
        finishing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repo.setUiDensityScale(pendingUiScale)
                    repo.setStartupOnboardingCompleted(true)
                }
            }
            if (result.isFailure) {
                finishing = false
            }
        }
    }

    fun transitionToStep(targetIndex: Int) {
        val nextIndex = targetIndex.coerceIn(0, steps.lastIndex)
        if (nextIndex == stepIndex) return
        stepIndex = nextIndex
    }

    fun requestNotificationPermission() {
        if (!StartupNotificationPermission.isSupported(sdkInt)) {
            notificationPermissionGranted = true
            permissionRequestActive = false
            permissionNavigationBlocked = false
            return
        }
        permissionRequestActive = true
        permissionNavigationBlocked = true
        notificationPermissionWarningVisible = false
        notificationPermissionLauncher.launch(notificationPermission)
    }

    fun goNextStep() {
        if (
            !shouldAdvanceStartupOnboarding(
                permissionRequestActive = permissionRequestActive,
                permissionNavigationBlocked = permissionNavigationBlocked
            )
        ) {
            return
        }
        if (
            steps[stepIndex] == StartupStep.Permissions &&
            shouldShowStartupNotificationPermissionWarning(
                permissionSupported = StartupNotificationPermission.isSupported(sdkInt),
                permissionGranted = notificationPermissionGranted,
                attempts = notificationPermissionWarningAttempts
            )
        ) {
            showNotificationPermissionWarning()
            return
        }
        if (
            steps[stepIndex] == StartupStep.Platforms &&
            shouldWarnStartupNoPlatformConnected(
                biliState = biliState.health.state,
                neteaseState = neteaseState.health.state,
                youTubeState = youTubeState.health.state
            )
        ) {
            noPlatformWarningVisible = true
            return
        }
        if (stepIndex == steps.lastIndex) {
            finishOnboarding()
            return
        }

        transitionToStep(stepIndex + 1)
    }

    fun clearThemeRevealVisualState() {
        themeRevealSnapshot = null
        themeRevealOriginWindow = null
        themeRevealStartRadiusPx = 0f
        themeRevealFallbackColor = null
    }

    fun clearThemeRevealState() {
        themeRevealCaptureToken += 1
        themeRevealCaptureJob?.cancel()
        themeRevealCaptureJob = null
        themeRevealCaptureInFlight = false
        pendingFollowSystemDark = null
        pendingForceDark = null
        clearThemeRevealVisualState()
    }

    fun finishThemeReveal(captureToken: Int) {
        if (themeRevealCaptureToken == captureToken) {
            clearThemeRevealVisualState()
        }
    }

    fun requestThemeToggle(originInWindow: Offset, startRadiusPx: Float) {
        if (
            shouldBlockStartupOnboardingThemeToggle(
                captureInFlight = themeRevealCaptureInFlight,
                revealActive = themeRevealOriginWindow != null &&
                    themeRevealFallbackColor != null
            )
        ) {
            return
        }

        val isDarkBeforeToggle = latestIsDarkTheme.value
        val nextDark = !isDarkBeforeToggle
        val captureToken = themeRevealCaptureToken + 1
        themeRevealCaptureToken = captureToken
        themeRevealCaptureJob?.cancel()
        themeRevealCaptureJob = null
        themeRevealCaptureInFlight = true
        val captureJob = scope.launch {
            var themeWriteStarted = false
            var themeWriteCompleted = false
            val captureView = activity?.window?.decorView?.rootView ?: rootView.rootView
            try {
                awaitStartupStableDraw(captureView)
                val snapshot = withTimeoutOrNull(STARTUP_THEME_REVEAL_CAPTURE_TIMEOUT_MILLIS) {
                    runCatching {
                        captureStartupThemeRevealSnapshot(
                            activity = activity,
                            fallbackView = captureView
                        )
                    }.getOrNull()
                }
                val lifecycleActive = lifecycleOwner.lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                val activityValid = activity == null ||
                    (!activity.isFinishing && !activity.isDestroyed)
                if (themeRevealCaptureToken != captureToken || !lifecycleActive || !activityValid) {
                    return@launch
                }

                clearThemeRevealVisualState()
                themeRevealSnapshot = snapshot
                themeRevealFallbackColor = if (isDarkBeforeToggle) {
                    ComposeColor(0xFF121212)
                } else {
                    ComposeColor.White
                }
                themeRevealOriginWindow = originInWindow
                themeRevealStartRadiusPx = startRadiusPx.coerceAtLeast(1f)
                themeRevealCaptureInFlight = false
                pendingFollowSystemDark = false
                pendingForceDark = nextDark
                themeWriteStarted = true
                repo.setThemeMode(
                    followSystemDark = false,
                    forceDark = nextDark
                )
                themeWriteCompleted = true
            } finally {
                if (themeRevealCaptureToken == captureToken) {
                    if (themeWriteStarted && !themeWriteCompleted) {
                        pendingFollowSystemDark = null
                        pendingForceDark = null
                        clearThemeRevealVisualState()
                    }
                    themeRevealCaptureJob = null
                    themeRevealCaptureInFlight = false
                }
            }
        }
        themeRevealCaptureJob = captureJob
    }

    val themeRevealActive = themeRevealOriginWindow != null && themeRevealFallbackColor != null
    val activeThemeRevealToken = themeRevealCaptureToken
    LaunchedEffect(themeRevealActive, activeThemeRevealToken) {
        if (!themeRevealActive) {
            return@LaunchedEffect
        }
        delay(STARTUP_THEME_REVEAL_WATCHDOG_DELAY_MILLIS)
        finishThemeReveal(activeThemeRevealToken)
    }
    DisposableEffect(lifecycleOwner) {
        onDispose { clearThemeRevealState() }
    }

    @Composable
    fun RenderOnboardingStepContent(currentStep: Int) {
        StepContainer(stepIndex = currentStep) {
            when (steps[currentStep]) {
                StartupStep.Language -> LanguageContent(
                    selectedLanguage = selectedLanguage,
                    onSelectLanguage = ::selectLanguage
                )
                StartupStep.Platforms -> PlatformContent(
                    inlineMessage = inlineMessage,
                    onInlineMessageChange = { inlineMessage = it },
                    biliState = biliState.health.state,
                    hasSavedBiliCookies = biliState.hasSavedCookies,
                    neteaseState = neteaseState.health.state,
                    hasSavedNeteaseCookies = neteaseState.hasSavedCookies,
                    youTubeState = youTubeState.health.state,
                    hasSavedYouTubeAuth = youTubeState.hasSavedAuth,
                    onOpenBili = {
                        inlineMessage = null
                        biliSheetTab = 0
                        showBiliSheet = true
                    },
                    onManageBili = {
                        inlineMessage = null
                        showBiliSavedCookieDialog = true
                    },
                    onOpenNetease = {
                        inlineMessage = null
                        neteaseSheetTab = 0
                        showNeteaseSheet = true
                    },
                    onManageNetease = {
                        inlineMessage = null
                        showNeteaseSavedCookieDialog = true
                    },
                    onOpenYouTube = {
                        inlineMessage = null
                        youTubeSheetTab = 0
                        showYouTubeSheet = true
                    },
                    onManageYouTube = {
                        inlineMessage = null
                        showYouTubeSavedCookieDialog = true
                    }
                )
                StartupStep.PlaybackSources -> StartupPlaybackSourceContent(
                    autoSourceSwitchEnabled = neteaseAutoSourceSwitch,
                    localSourceFallbackEnabled = neteaseLocalSourceFallback,
                    onSetFallbackEnabled = { enabled ->
                        scope.launch {
                            repo.setNeteasePlaybackSourceFallback(enabled)
                        }
                    }
                )
                StartupStep.Permissions -> StartupPermissionContent(
                    notificationPermissionSupported =
                        StartupNotificationPermission.isSupported(sdkInt),
                    notificationPermissionGranted = notificationPermissionGranted,
                    localMediaPermissionGranted = localMediaPermissionGranted,
                    onRequestNotificationPermission = {
                        requestNotificationPermission()
                    },
                    onRequestLocalMediaPermission = {
                        permissionRequestActive = true
                        permissionNavigationBlocked = true
                        localMediaPermissionLauncher.launch(localMediaPermission)
                    }
                )
                StartupStep.PlaybackControls -> StartupPlaybackControlsContent(
                    preferences = playbackControlLayoutPreferences,
                    coverLyricFontScale = lyricFontScales.coverLyric,
                    onCoverLyricFontScaleChange = { scale ->
                        scope.launch {
                            repo.setLyricFontScale(
                                LyricFontScaleTarget.COVER_LYRIC,
                                scale
                            )
                        }
                    },
                    onPreferencesChange = { preferences ->
                        scope.launch {
                            repo.setPlaybackControlLayoutPreferences(preferences)
                        }
                    }
                )
                StartupStep.Lyrics -> StartupLyricsContent(
                    preferences = playbackControlLayoutPreferences,
                    lyricFontScale = lyricFontScales.lyricsPageLyric,
                    onLyricFontScaleChange = { scale ->
                        scope.launch {
                            repo.setLyricFontScale(
                                LyricFontScaleTarget.LYRICS_PAGE_LYRIC,
                                scale
                            )
                        }
                    },
                    onPreferencesChange = { preferences ->
                        scope.launch {
                            repo.setPlaybackControlLayoutPreferences(preferences)
                        }
                    }
                )
                StartupStep.BackupRestore -> BackupRestoreContent(
                    gitHubState = githubState,
                    webDavState = webDavState,
                    onDismissGitHubMessage = githubVm::clearMessages,
                    onDismissWebDavMessage = webDavVm::clearMessages,
                    onOpenGitHubConfig = {
                        githubVm.clearMessages()
                        showGitHubConfigDialog = true
                    },
                    onOpenClearGitHubConfig = {
                        githubVm.clearMessages()
                        showClearGitHubConfigDialog = true
                    },
                    onToggleGitHubAutoSync = { enabled ->
                        githubVm.toggleAutoSync(context, enabled)
                    },
                    onGitHubSyncNow = {
                        githubVm.performSync(context)
                    },
                    onOpenWebDavConfig = {
                        webDavVm.clearMessages()
                        showWebDavConfigDialog = true
                    },
                    onOpenClearWebDavConfig = {
                        webDavVm.clearMessages()
                        showClearWebDavConfigDialog = true
                    },
                    onToggleWebDavAutoSync = { enabled ->
                        webDavVm.toggleAutoSync(context, enabled)
                    },
                    onWebDavSyncNow = {
                        webDavVm.performSync(context)
                    }
                )
                StartupStep.Personalize -> PersonalizeContent(
                    pendingUiScale = pendingUiScale,
                    onUiScaleChange = { pendingUiScale = it },
                    onUiScaleCommit = { scope.launch { repo.setUiDensityScale(pendingUiScale) } },
                    backgroundImageUri = backgroundImageUri,
                    backgroundImageBlur = effectiveBackgroundImageBlur,
                    backgroundImageAlpha = effectiveBackgroundImageAlpha,
                    enhancedAdvancedBlurAvailable = enhancedAdvancedBlurAvailable,
                    enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
                    onSelectBackground = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClearBackground = {
                        pendingBackgroundImageBlur = null
                        pendingBackgroundImageAlpha = null
                        scope.launch {
                            BackgroundImageStorage.deleteManagedBackground(context, backgroundImageUri)
                            repo.setBackgroundImageUri(null)
                        }
                    },
                    onBackgroundBlurChange = { blur ->
                        pendingBackgroundImageBlur = blur
                    },
                    onBackgroundBlurCommit = { blur ->
                        scope.launch { repo.setBackgroundImageBlur(blur) }
                    },
                    onBackgroundAlphaChange = { alpha ->
                        pendingBackgroundImageAlpha = alpha
                    },
                    onBackgroundAlphaCommit = { alpha ->
                        scope.launch { repo.setBackgroundImageAlpha(alpha) }
                    },
                    onEnhancedAdvancedBlurChange = { enabled ->
                        scope.launch {
                            repo.setEnhancedAdvancedBlurEnabled(enabled)
                        }
                    },
                    isDarkTheme = isDarkTheme,
                    onThemeToggleRequest = ::requestThemeToggle
                )
                StartupStep.LearningGuide -> StartupLearningGuideContent()
            }
        }
    }

    LaunchedEffect(stepIndex) {
        stepTransitionState.request(stepIndex)
    }

    CompositionLocalProvider(LocalDensity provides previewDensity) {
        val colorScheme = MaterialTheme.colorScheme
        val visibleStepScenes = stepTransitionState.visibleScenes
        val activeGlassStepIndex = stepTransitionState.activeGlassStepIndex
        val activeNavigationOwners = setOf(steps[activeGlassStepIndex])
        val prewarmedNavigationOwners = visibleStepScenes.mapTo(linkedSetOf()) { scene ->
            steps[scene.stepIndex]
        }
        val animatedProgress by animateFloatAsState(
            targetValue = calculateStartupOnboardingProgress(stepIndex, steps.size),
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            label = "startup_onboarding_progress"
        )
        AdvancedGlassHost(
            controller = advancedGlassController,
            backgroundBackdrop = backgroundGlassBackdrop,
            contentBackdrop = contentGlassBackdrop,
            activeNavigationOwners = activeNavigationOwners,
            prewarmedNavigationOwners = prewarmedNavigationOwners,
            disableStretchOverscroll = backgroundImageUri != null
        ) {
            AdvancedGlassNavigationHandoff(enabled = visibleStepScenes.size > 1) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .captureAdvancedGlassBackdrop(backgroundGlassBackdrop)
                            .background(colorScheme.background)
                    ) {
                        CustomBackground(
                            imageUri = backgroundImageUri,
                            blur = effectiveBackgroundImageBlur,
                            alpha = effectiveBackgroundImageAlpha
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .captureAdvancedGlassBackdrop(contentGlassBackdrop)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 680.dp)
                                .align(Alignment.Center)
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp, vertical = 20.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_badge),
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 6.dp
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSecondaryContainer
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.onboarding_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.onboarding_subtitle),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(18.dp))
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp))
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(
                                    R.string.onboarding_step_counter,
                                    stepIndex + 1,
                                    steps.size
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(18.dp))

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                StartupOnboardingLayerHost(
                                    transitionState = stepTransitionState,
                                    modifier = Modifier.fillMaxSize(),
                                ) { scene ->
                                    key(scene.stepIndex) {
                                        CompositionLocalProvider(
                                            LocalAdvancedGlassNavigationOwner provides
                                                steps[scene.stepIndex]
                                        ) {
                                            AdvancedGlassSceneLayer(
                                                controller = advancedGlassController,
                                                modifier = Modifier.fillMaxSize(),
                                                disableStretchOverscroll =
                                                    backgroundImageUri != null,
                                                fixedBackground = true,
                                                background = {
                                                    Box(Modifier.fillMaxSize())
                                                },
                                                content = {
                                                    Box(Modifier.fillMaxSize()) {
                                                        RenderOnboardingStepContent(
                                                            scene.stepIndex
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (stepIndex > 0) {
                                    HapticTextButton(
                                        onClick = {
                                            transitionToStep(stepIndex - 1)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(stringResource(R.string.action_back))
                                    }
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }

                                HapticButton(
                                    onClick = ::goNextStep,
                                    enabled = !finishing,
                                    modifier = Modifier.weight(1.4f),
                                    shape = OnboardingControlShape
                                ) {
                                    Text(
                                        text = if (stepIndex == steps.lastIndex) {
                                            stringResource(R.string.onboarding_learning_enter_app)
                                        } else {
                                            stringResource(R.string.onboarding_action_next)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingsNeteaseAuthDialogs(
                showSheet = showNeteaseSheet,
                initialTab = neteaseSheetTab,
                onDismissSheet = { showNeteaseSheet = false },
                inlineMsg = inlineMessage,
                onInlineMsgChange = { inlineMessage = it },
                showConfirmDialog = showNeteaseConfirm,
                confirmPhoneMasked = neteaseMaskedPhone,
                onDismissConfirmDialog = { showNeteaseConfirm = false },
                vm = neteaseVm,
                showSavedCookieDialog = showNeteaseSavedCookieDialog,
                onDismissSavedCookieDialog = { showNeteaseSavedCookieDialog = false },
                onOpenSheetAtTab = { tab ->
                    inlineMessage = null
                    neteaseSheetTab = tab
                    showNeteaseSheet = true
                },
                onLogout = {
                    showNeteaseSavedCookieDialog = false
                    neteaseVm.clearCookies()
                },
                onBrowserLogin = null
            )
            SettingsBiliAuthDialogs(
                showSheet = showBiliSheet,
                initialTab = biliSheetTab,
                onDismissSheet = { showBiliSheet = false },
                inlineMsg = inlineMessage,
                onInlineMsgChange = { inlineMessage = it },
                vm = biliVm,
                showSavedCookieDialog = showBiliSavedCookieDialog,
                onDismissSavedCookieDialog = { showBiliSavedCookieDialog = false },
                onOpenSheetAtTab = { tab ->
                    inlineMessage = null
                    biliSheetTab = tab
                    showBiliSheet = true
                },
                onLogout = {
                    showBiliSavedCookieDialog = false
                    biliVm.clearCookies()
                },
                onBrowserLogin = null
            )
            SettingsYouTubeAuthDialogs(
                showSheet = showYouTubeSheet,
                initialTab = youTubeSheetTab,
                onDismissSheet = { showYouTubeSheet = false },
                inlineMsg = inlineMessage,
                onInlineMsgChange = { inlineMessage = it },
                vm = youTubeVm,
                showSavedCookieDialog = showYouTubeSavedCookieDialog,
                onDismissSavedCookieDialog = { showYouTubeSavedCookieDialog = false },
                onOpenSheetAtTab = { tab ->
                    inlineMessage = null
                    youTubeSheetTab = tab
                    showYouTubeSheet = true
                },
                onLogout = {
                    showYouTubeSavedCookieDialog = false
                    youTubeVm.clearAuth()
                }
            )
            SettingsGitHubDialogs(
                showGitHubConfigDialog = showGitHubConfigDialog,
                onShowGitHubConfigDialogChange = { showGitHubConfigDialog = it },
                showClearGitHubConfigDialog = showClearGitHubConfigDialog,
                onShowClearGitHubConfigDialogChange = { showClearGitHubConfigDialog = it }
            )
            SettingsWebDavDialogs(
                showWebDavConfigDialog = showWebDavConfigDialog,
                onShowWebDavConfigDialogChange = { showWebDavConfigDialog = it },
                showClearWebDavConfigDialog = showClearWebDavConfigDialog,
                onShowClearWebDavConfigDialogChange = { showClearWebDavConfigDialog = it }
            )
            if (notificationPermissionWarningVisible) {
                StartupNotificationPermissionWarningDialog(
                    attempt = notificationPermissionWarningAttempts,
                    onRequestPermission = ::requestNotificationPermission,
                    onDismiss = {
                        notificationPermissionWarningVisible = false
                        if (
                            !hasFinishedStartupNotificationPermissionWarning(
                                notificationPermissionWarningAttempts
                            )
                        ) {
                            scope.launch {
                                delay(180L)
                                if (!notificationPermissionGranted) {
                                    showNotificationPermissionWarning()
                                }
                            }
                        }
                    }
                )
            }
            if (noPlatformWarningVisible) {
                StartupNoPlatformWarningDialog(
                    onContinue = {
                        noPlatformWarningVisible = false
                        transitionToStep(stepIndex + 1)
                    },
                    onDismiss = { noPlatformWarningVisible = false }
                )
            }
            if (enhancedAdvancedBlurPromptVisible) {
                StartupEnhancedAdvancedBlurPromptDialog(
                    onEnable = {
                        enhancedAdvancedBlurPromptVisible = false
                        scope.launch { repo.setEnhancedAdvancedBlurEnabled(true) }
                    },
                    onDismiss = { enhancedAdvancedBlurPromptVisible = false }
                )
            }
            loginSuccessTitle?.let { title ->
                LoginSuccessDialog(
                    title = title,
                    onDismiss = { loginSuccessTitle = null }
                )
            }
            val revealOrigin = themeRevealOriginWindow
            val revealFallbackColor = themeRevealFallbackColor
            if (revealOrigin != null && revealFallbackColor != null) {
                ThemeRevealOverlay(
                    snapshot = themeRevealSnapshot,
                    fallbackColor = revealFallbackColor,
                    originInWindow = revealOrigin,
                    modifier = Modifier.fillMaxSize(),
                    startRadiusPx = themeRevealStartRadiusPx,
                    legacySnapshotDim = true,
                    durationMillis = 720,
                    onFinished = { finishThemeReveal(activeThemeRevealToken) }
                )
            }
        }
    }
}

@Composable
private fun StepContainer(
    stepIndex: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    key(stepIndex) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            content = content
        )
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, description: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(54.dp),
            shape = OnboardingControlShape,
            color = colors.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.onPrimaryContainer)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageContent(
    selectedLanguage: LanguageManager.Language,
    onSelectLanguage: (LanguageManager.Language) -> Unit
) {
    StepHeader(
        icon = Icons.Outlined.Language,
        title = stringResource(R.string.onboarding_language_title),
        description = stringResource(R.string.onboarding_language_desc)
    )
    Spacer(Modifier.height(18.dp))
    LanguageManager.Language.entries.forEach { language ->
        OptionCard(
            title = language.getDisplayName(LocalContext.current),
            selected = selectedLanguage == language,
            onClick = { onSelectLanguage(language) }
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PlatformContent(
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit,
    biliState: SavedCookieAuthState,
    hasSavedBiliCookies: Boolean,
    neteaseState: SavedCookieAuthState,
    hasSavedNeteaseCookies: Boolean,
    youTubeState: YouTubeAuthState,
    hasSavedYouTubeAuth: Boolean,
    onOpenBili: () -> Unit,
    onManageBili: () -> Unit,
    onOpenNetease: () -> Unit,
    onManageNetease: () -> Unit,
    onOpenYouTube: () -> Unit,
    onManageYouTube: () -> Unit
) {
    StepHeader(
        icon = Icons.Outlined.Tune,
        title = stringResource(R.string.onboarding_platforms_title),
        description = stringResource(R.string.onboarding_platforms_desc)
    )
    Spacer(Modifier.height(18.dp))
    inlineMessage?.let {
        InlineMessage(text = it, onClose = { onInlineMessageChange(null) })
        Spacer(Modifier.height(14.dp))
    }
    PlatformCard(
        icon = painterResource(R.drawable.ic_bilibili),
        title = stringResource(R.string.platform_bilibili),
        status = statusTextForSavedCookie(biliState),
        connected = biliState == SavedCookieAuthState.Valid,
        actionText = if (hasSavedBiliCookies) {
            stringResource(R.string.onboarding_platform_action_manage)
        } else if (biliState == SavedCookieAuthState.Valid) {
            stringResource(R.string.onboarding_platform_action_logout)
        } else {
            stringResource(R.string.onboarding_platform_action_connect)
        },
        onClick = if (hasSavedBiliCookies) onManageBili else onOpenBili
    )
    Spacer(Modifier.height(12.dp))
    PlatformCard(
        icon = painterResource(R.drawable.ic_netease_cloud_music),
        title = stringResource(R.string.platform_netease),
        status = statusTextForSavedCookie(neteaseState),
        connected = neteaseState == SavedCookieAuthState.Valid,
        actionText = if (hasSavedNeteaseCookies) {
            stringResource(R.string.onboarding_platform_action_manage)
        } else if (neteaseState == SavedCookieAuthState.Valid) {
            stringResource(R.string.onboarding_platform_action_logout)
        } else {
            stringResource(R.string.onboarding_platform_action_connect)
        },
        onClick = if (hasSavedNeteaseCookies) onManageNetease else onOpenNetease
    )
    Spacer(Modifier.height(12.dp))
    PlatformCard(
        icon = painterResource(R.drawable.ic_youtube),
        title = stringResource(R.string.common_youtube),
        status = statusTextForYouTube(youTubeState),
        connected = youTubeState == YouTubeAuthState.Valid,
        actionText = if (hasSavedYouTubeAuth) {
            stringResource(R.string.onboarding_platform_action_manage)
        } else if (youTubeState == YouTubeAuthState.Valid) {
            stringResource(R.string.onboarding_platform_action_logout)
        } else {
            stringResource(R.string.onboarding_platform_action_connect)
        },
        onClick = if (hasSavedYouTubeAuth) onManageYouTube else onOpenYouTube
    )
    Spacer(Modifier.height(18.dp))
    HintCard(body = stringResource(R.string.onboarding_platforms_hint))
}

@Composable
private fun BackupRestoreContent(
    gitHubState: GitHubSyncUiState,
    webDavState: WebDavSyncUiState,
    onDismissGitHubMessage: () -> Unit,
    onDismissWebDavMessage: () -> Unit,
    onOpenGitHubConfig: () -> Unit,
    onOpenClearGitHubConfig: () -> Unit,
    onToggleGitHubAutoSync: (Boolean) -> Unit,
    onGitHubSyncNow: () -> Unit,
    onOpenWebDavConfig: () -> Unit,
    onOpenClearWebDavConfig: () -> Unit,
    onToggleWebDavAutoSync: (Boolean) -> Unit,
    onWebDavSyncNow: () -> Unit
) {
    StepHeader(
        icon = Icons.Outlined.CloudSync,
        title = stringResource(R.string.onboarding_backup_restore_title),
        description = stringResource(R.string.onboarding_backup_restore_desc)
    )
    Spacer(Modifier.height(18.dp))
    gitHubState.errorMessage?.let {
        InlineMessage(text = it, onClose = onDismissGitHubMessage)
        Spacer(Modifier.height(14.dp))
    }
    gitHubState.successMessage?.let {
        InlineMessage(text = it, onClose = onDismissGitHubMessage)
        Spacer(Modifier.height(14.dp))
    }
    webDavState.errorMessage?.let {
        InlineMessage(text = it, onClose = onDismissWebDavMessage)
        Spacer(Modifier.height(14.dp))
    }
    webDavState.successMessage?.let {
        InlineMessage(text = it, onClose = onDismissWebDavMessage)
        Spacer(Modifier.height(14.dp))
    }
    GitHubSyncCard(
        state = gitHubState,
        onOpenConfig = onOpenGitHubConfig,
        onOpenClearConfig = onOpenClearGitHubConfig,
        onToggleAutoSync = onToggleGitHubAutoSync,
        onSyncNow = onGitHubSyncNow
    )
    Spacer(Modifier.height(14.dp))
    WebDavSyncCard(
        state = webDavState,
        onOpenConfig = onOpenWebDavConfig,
        onOpenClearConfig = onOpenClearWebDavConfig,
        onToggleAutoSync = onToggleWebDavAutoSync,
        onSyncNow = onWebDavSyncNow
    )
    Spacer(Modifier.height(18.dp))
    HintCard(body = stringResource(R.string.onboarding_backup_restore_hint))
}

@Composable
private fun PersonalizeContent(
    pendingUiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    onUiScaleCommit: () -> Unit,
    backgroundImageUri: String?,
    backgroundImageBlur: Float,
    backgroundImageAlpha: Float,
    enhancedAdvancedBlurAvailable: Boolean,
    enhancedAdvancedBlurEnabled: Boolean,
    onSelectBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onBackgroundBlurChange: (Float) -> Unit,
    onBackgroundBlurCommit: (Float) -> Unit,
    onBackgroundAlphaChange: (Float) -> Unit,
    onBackgroundAlphaCommit: (Float) -> Unit,
    onEnhancedAdvancedBlurChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    onThemeToggleRequest: (Offset, Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme

    StepHeader(
        icon = Icons.Outlined.Palette,
        title = stringResource(R.string.onboarding_personalize_title),
        description = stringResource(R.string.onboarding_personalize_desc)
    )
    Spacer(Modifier.height(18.dp))
    HintCard(
        title = stringResource(R.string.settings_ui_scale),
        body = stringResource(R.string.onboarding_ui_scale_hint, (pendingUiScale * 100).roundToInt())
    ) {
        Slider(
            value = pendingUiScale,
            onValueChange = onUiScaleChange,
            onValueChangeFinished = onUiScaleCommit,
            valueRange = 0.6f..1.2f,
            steps = 11
        )
    }
    Spacer(Modifier.height(14.dp))
    HintCard(
        title = stringResource(R.string.onboarding_theme_mode_title),
        body = if (isDarkTheme) {
            stringResource(R.string.settings_theme_toggle_light)
        } else {
            stringResource(R.string.settings_theme_toggle_dark)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            ThemeModeActionButton(
                isDarkTheme = isDarkTheme,
                onToggleRequest = onThemeToggleRequest
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    HintCard(
        title = stringResource(R.string.background_custom),
        body = stringResource(R.string.onboarding_background_hint)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HapticOutlinedButton(
                onClick = onSelectBackground,
                modifier = Modifier.weight(1f),
                shape = OnboardingControlShape,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text(
                    if (backgroundImageUri == null) {
                        stringResource(R.string.onboarding_background_select)
                    } else {
                        stringResource(R.string.onboarding_background_change)
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (backgroundImageUri != null) {
                HapticOutlinedButton(
                    onClick = onClearBackground,
                    modifier = Modifier.weight(1f),
                    shape = OnboardingControlShape,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_background_clear),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (backgroundImageUri != null) {
            Text(stringResource(R.string.background_blur), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = backgroundImageBlur,
                onValueChange = onBackgroundBlurChange,
                onValueChangeFinished = { onBackgroundBlurCommit(backgroundImageBlur) },
                valueRange = 0f..25f
            )
            Text(stringResource(R.string.background_opacity), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = backgroundImageAlpha,
                onValueChange = onBackgroundAlphaChange,
                onValueChangeFinished = { onBackgroundAlphaCommit(backgroundImageAlpha) },
                valueRange = 0.1f..1.0f
            )
        }
    }
    if (enhancedAdvancedBlurAvailable) {
        Spacer(Modifier.height(14.dp))
        HintCard(
            title = stringResource(R.string.settings_enhanced_advanced_blur),
            body = if (backgroundImageUri == null) {
                stringResource(R.string.onboarding_enhanced_blur_no_background_desc)
            } else {
                stringResource(R.string.onboarding_enhanced_blur_background_desc)
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.onboarding_enhanced_blur_switch_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Switch(
                    checked = enhancedAdvancedBlurEnabled,
                    onCheckedChange = onEnhancedAdvancedBlurChange
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        text = stringResource(R.string.onboarding_complete_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant
    )
}

@Composable
private fun OptionCard(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OnboardingCardShape)
            .clickable(onClick = onClick),
        shape = OnboardingCardShape,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface
            )
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun PlatformCard(
    icon: Painter,
    title: String,
    status: String,
    connected: Boolean,
    actionText: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OnboardingCardShape)
            .clickable(onClick = onClick),
        shape = OnboardingCardShape,
        color = if (connected) colors.secondaryContainer else colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = OnboardingControlShape,
                color = colors.surface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painter = icon, contentDescription = title, tint = colors.onSurface, modifier = Modifier.size(28.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(6.dp))
                StatusPill(status, connected)
            }
            OnboardingActionButton(
                text = actionText,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun OnboardingActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    HapticOutlinedButton(
        onClick = onClick,
        modifier = modifier
            .widthIn(max = 104.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
        shape = OnboardingControlShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusPill(label: String, connected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val textStyle = when {
        label.length >= 14 -> MaterialTheme.typography.labelSmall
        label.length >= 10 -> MaterialTheme.typography.labelMedium
        else -> MaterialTheme.typography.labelLarge
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (connected) colors.primary.copy(alpha = 0.14f) else colors.outlineVariant.copy(alpha = 0.6f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = textStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = if (connected) colors.primary else colors.onSurfaceVariant
        )
    }
}

@Composable
private fun HintCard(
    title: String? = null,
    body: String,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun GitHubSyncCard(
    state: GitHubSyncUiState,
    onOpenConfig: () -> Unit,
    onOpenClearConfig: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val repoFullName = listOf(state.repoOwner, state.repoName)
        .filter { it.isNotBlank() }
        .takeIf { it.size == 2 }
        ?.joinToString("/")
    val primarySupportingText = if (state.isConfigured) {
        repoFullName?.let { stringResource(R.string.onboarding_github_repo_configured, it) }
            ?: stringResource(R.string.settings_configured)
    } else {
        null
    }
    val secondarySupportingText = if (state.isConfigured) {
        if (state.lastSyncTime > 0) {
            stringResource(R.string.sync_last_time, formatSyncTime(state.lastSyncTime))
        } else {
            stringResource(R.string.sync_not_synced)
        }
    } else {
        null
    }

    OnboardingGlassSurface(
        shape = OnboardingCardShape,
        color = if (state.isConfigured) {
            colors.secondaryContainer
        } else {
            colors.surfaceContainerHigh
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = OnboardingControlShape,
                    color = colors.surface
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = stringResource(R.string.common_github),
                            tint = colors.onSurface,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.onboarding_backup_restore_github_title
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    StatusPill(
                        label = if (state.isConfigured) {
                            stringResource(R.string.settings_configured)
                        } else {
                            stringResource(R.string.settings_not_configured)
                        },
                        connected = state.isConfigured
                    )
                    primarySupportingText?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                    secondarySupportingText?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                OnboardingActionButton(
                    text = if (state.isConfigured) {
                        stringResource(R.string.onboarding_platform_action_manage)
                    } else {
                        stringResource(R.string.settings_configure)
                    },
                    onClick = onOpenConfig
                )
            }

            if (state.isConfigured) {
                Surface(
                    shape = OnboardingControlShape,
                    color = colors.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sync_auto),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.sync_auto_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.autoSyncEnabled,
                            onCheckedChange = onToggleAutoSync
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        HapticOutlinedButton(onClick = onSyncNow, shape = OnboardingControlShape) {
                            Text(stringResource(R.string.settings_sync_now))
                        }
                    }
                    HapticTextButton(onClick = onOpenClearConfig) {
                        Text(
                            text = stringResource(R.string.settings_clear_config),
                            color = colors.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebDavSyncCard(
    state: WebDavSyncUiState,
    onOpenConfig: () -> Unit,
    onOpenClearConfig: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val endpoint = state.serverUrl.takeIf { it.isNotBlank() }?.let { serverUrl ->
        state.basePath.takeIf { it.isNotBlank() }?.let { basePath ->
            "$serverUrl/$basePath"
        } ?: serverUrl
    }

    OnboardingGlassSurface(
        shape = OnboardingCardShape,
        color = if (state.isConfigured) {
            colors.secondaryContainer
        } else {
            colors.surfaceContainerHigh
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = OnboardingControlShape,
                    color = colors.surface
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.CloudSync,
                            contentDescription = stringResource(
                                R.string.onboarding_backup_restore_webdav_title
                            ),
                            tint = colors.onSurface,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.onboarding_backup_restore_webdav_title
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    StatusPill(
                        label = if (state.isConfigured) {
                            stringResource(R.string.settings_configured)
                        } else {
                            stringResource(R.string.settings_not_configured)
                        },
                        connected = state.isConfigured
                    )
                    endpoint?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.onboarding_backup_restore_webdav_endpoint,
                                it
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (state.isConfigured) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (state.lastSyncTime > 0) {
                                stringResource(
                                    R.string.sync_last_time,
                                    formatSyncTime(state.lastSyncTime)
                                )
                            } else {
                                stringResource(R.string.sync_not_synced)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                OnboardingActionButton(
                    text = if (state.isConfigured) {
                        stringResource(R.string.onboarding_platform_action_manage)
                    } else {
                        stringResource(R.string.settings_configure)
                    },
                    onClick = onOpenConfig
                )
            }

            if (state.isConfigured) {
                Surface(
                    shape = OnboardingControlShape,
                    color = colors.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sync_auto),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.webdav_auto_sync_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.autoSyncEnabled,
                            onCheckedChange = onToggleAutoSync
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        HapticOutlinedButton(onClick = onSyncNow, shape = OnboardingControlShape) {
                            Text(stringResource(R.string.settings_sync_now))
                        }
                    }
                    HapticTextButton(onClick = onOpenClearConfig) {
                        Text(
                            text = stringResource(R.string.settings_clear_config),
                            color = colors.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupNoPlatformWarningDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.onboarding_platforms_no_login_title))
        },
        text = {
            Text(stringResource(R.string.onboarding_platforms_no_login_desc))
        },
        confirmButton = {
            HapticTextButton(onClick = onContinue) {
                Text(stringResource(R.string.onboarding_platforms_no_login_continue))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.onboarding_platforms_no_login_connect))
            }
        }
    )
}

@Composable
private fun StartupEnhancedAdvancedBlurPromptDialog(
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.onboarding_enhanced_blur_prompt_title))
        },
        text = {
            Text(stringResource(R.string.onboarding_enhanced_blur_prompt_desc))
        },
        confirmButton = {
            HapticTextButton(onClick = onEnable) {
                Text(stringResource(R.string.onboarding_enhanced_blur_prompt_enable))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.onboarding_enhanced_blur_prompt_not_now))
            }
        }
    )
}

@Composable
private fun StartupNotificationPermissionWarningDialog(
    attempt: Int,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val isFinalWarning = attempt >= STARTUP_NOTIFICATION_PERMISSION_WARNING_ATTEMPTS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isFinalWarning) {
                        R.string.onboarding_notification_permission_final_warning_title
                    } else {
                        R.string.onboarding_notification_permission_warning_title
                    }
                )
            )
        },
        text = {
            Text(
                stringResource(
                    if (isFinalWarning) {
                        R.string.onboarding_notification_permission_final_warning_desc
                    } else {
                        R.string.onboarding_notification_permission_warning_desc
                    }
                )
            )
        },
        confirmButton = {
            HapticTextButton(onClick = onRequestPermission) {
                Text(
                    stringResource(
                        if (isFinalWarning) {
                            R.string.onboarding_notification_permission_final_request
                        } else {
                            R.string.onboarding_notification_permission_request_again
                        }
                    )
                )
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (isFinalWarning) {
                            R.string.onboarding_notification_permission_final_skip
                        } else {
                            R.string.onboarding_notification_permission_skip
                        }
                    )
                )
            }
        }
    )
}

@Composable
private fun statusTextForSavedCookie(state: SavedCookieAuthState): String {
    return when (state) {
        SavedCookieAuthState.Valid -> stringResource(R.string.onboarding_platform_status_connected)
        SavedCookieAuthState.Checking -> stringResource(R.string.onboarding_platform_status_not_connected)
        SavedCookieAuthState.Missing -> stringResource(R.string.onboarding_platform_status_not_connected)
    }
}

@Composable
private fun statusTextForYouTube(state: YouTubeAuthState): String {
    return when (state) {
        YouTubeAuthState.Valid -> stringResource(R.string.onboarding_platform_status_connected)
        YouTubeAuthState.Missing -> stringResource(R.string.onboarding_platform_status_not_connected)
    }
}

private suspend fun captureStartupThemeRevealSnapshot(
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

            val bitmap = createBitmap(decorView.width, decorView.height)

            PixelCopy.request(
                currentActivity.window,
                bitmap,
                { result ->
                    if (continuation.isActive) {
                        if (result == PixelCopy.SUCCESS) {
                            continuation.resume(bitmap)
                        } else {
                            bitmap.recycle()
                            continuation.resume(null)
                        }
                    } else {
                        bitmap.recycle()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    return windowBitmap?.asImageBitmap() ?: captureStartupThemeRevealFallbackSnapshot(fallbackView)
}

private suspend fun captureStartupThemeRevealFallbackSnapshot(view: View): ImageBitmap? {
    return withContext(Dispatchers.Main.immediate) {
        runCatching {
            if (view.width > 0 && view.height > 0) {
                view.drawToBitmap().asImageBitmap()
            } else {
                null
            }
        }.getOrNull()
    }
}

private suspend fun awaitStartupNextDraw(view: View) {
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

private suspend fun awaitStartupStableDraw(view: View) {
    repeat(2) {
        awaitStartupNextDraw(view)
    }
}
