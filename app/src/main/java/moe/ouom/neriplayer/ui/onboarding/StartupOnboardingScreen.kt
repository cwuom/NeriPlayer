package moe.ouom.neriplayer.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.startup.permission.StartupMediaPermission
import moe.ouom.neriplayer.core.startup.permission.StartupNotificationPermission
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.appearance.AdvancedBlurQualityPreference
import moe.ouom.neriplayer.data.settings.appearance.DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.data.settings.appearance.ThemeDefaults
import moe.ouom.neriplayer.data.settings.appearance.UiDensityScalePreference
import moe.ouom.neriplayer.data.settings.appearance.isCurrentBuildDimensity
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.component.overlay.ApplyOverlayWindowNavigationBarPolicy
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassNavigationHandoff
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneLayer
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassNavigationOwner
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.isAdvancedGlassBackendSupported
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberVisualEffectsDeviceProfile
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.background.CustomBackground

private tailrec fun Context.findStartupActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findStartupActivity()
    else -> null
}

private const val STARTUP_FINISH_CELEBRATION_LEAD_MILLIS = 520L

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StartupOnboardingScreen(
    disclaimerAccepted: Boolean = true,
    onAcceptDisclaimer: () -> Unit = {},
    initialUiStyle: UiStyle = UiStyle.Material,
    onLanguageChanged: (LanguageManager.Language) -> Unit = {}
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val activity = LocalView.current.context.findStartupActivity()
    val scope = rememberCoroutineScope()
    val repo = AppContainer.settingsRepo
    val autoSettings = remember(context) { AutoSettingsRepository(context.applicationContext) }
    val steps = StartupStep.entries
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentStep = steps[stepIndex]
    val latestDisclaimerAccepted by rememberUpdatedState(disclaimerAccepted)

    var selectedLanguageCode by rememberSaveable {
        mutableStateOf(LanguageManager.getCurrentLanguage(context).code)
    }
    val selectedLanguage = remember(selectedLanguageCode) {
        LanguageManager.Language.entries.firstOrNull { it.code == selectedLanguageCode }
            ?: LanguageManager.Language.SYSTEM
    }

    // 主题输入: 引导页本身就用真实的 NeriTheme 渲染, 风格和深浅色切换即时可见
    val followSystemDark by repo.followSystemDarkFlow.collectAsStateWithLifecycle(initialValue = true)
    val forceDark by repo.forceDarkFlow.collectAsStateWithLifecycle(initialValue = false)
    val dynamicColor by repo.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = true)
    val seedColor by repo.themeSeedColorFlow.collectAsStateWithLifecycle(initialValue = ThemeDefaults.DEFAULT_SEED_COLOR_HEX)
    val paletteStyleValue by repo.themePaletteStyleFlow.collectAsStateWithLifecycle(
        initialValue = ThemeDefaults.DEFAULT_PALETTE_STYLE
    )
    val colorSpecValue by repo.themeColorSpecFlow.collectAsStateWithLifecycle(
        initialValue = ThemeDefaults.DEFAULT_COLOR_SPEC
    )
    val storedUiStyle by repo.uiStyleFlow.collectAsStateWithLifecycle(initialValue = initialUiStyle)
    var pendingUiStyle by remember { mutableStateOf<UiStyle?>(null) }
    val uiStyle = pendingUiStyle ?: storedUiStyle
    LaunchedEffect(storedUiStyle, pendingUiStyle) {
        if (pendingUiStyle == storedUiStyle) pendingUiStyle = null
    }
    val systemDark = isSystemInDarkTheme()
    var pendingFollowSystemDark by remember { mutableStateOf<Boolean?>(null) }
    var pendingForceDark by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(followSystemDark, pendingFollowSystemDark) {
        if (pendingFollowSystemDark != null && pendingFollowSystemDark == followSystemDark) {
            pendingFollowSystemDark = null
        }
    }
    LaunchedEffect(forceDark, pendingForceDark) {
        if (pendingForceDark != null && pendingForceDark == forceDark) pendingForceDark = null
    }
    val isDarkTheme = resolveStartupOnboardingThemeMode(
        storedFollowSystemDark = followSystemDark,
        storedForceDark = forceDark,
        pendingFollowSystemDark = pendingFollowSystemDark,
        pendingForceDark = pendingForceDark
    ).resolveUseDark(systemDark)
    val latestIsDarkTheme by rememberUpdatedState(isDarkTheme)
    val themeInputs = remember(isDarkTheme, dynamicColor, seedColor, paletteStyleValue, colorSpecValue) {
        StartupThemeInputs(
            isDark = isDarkTheme,
            dynamicColor = dynamicColor,
            seedColorHex = seedColor,
            paletteStyle = PaletteStyle.valueOf(ThemeDefaults.normalizePaletteStyle(paletteStyleValue)),
            colorSpec = ColorSpec.SpecVersion.valueOf(ThemeDefaults.normalizeColorSpec(colorSpecValue))
        )
    }

    // 动效: 设备只读一次, 推荐在本机计算
    val deviceSpec = remember(context) { readStartupDeviceSpec(context) }
    val recommendation = remember(deviceSpec) { recommendStartupMotion(deviceSpec) }
    val sdkInt = Build.VERSION.SDK_INT
    val advancedBlurEnabled by repo.advancedBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val enhancedAdvancedBlurEnabled by repo.enhancedAdvancedBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val enhancedAdvancedBlurRadiusDp by repo.enhancedAdvancedBlurRadiusDpFlow
        .collectAsStateWithLifecycle(initialValue = DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP)
    val initialAdvancedBlurQuality = remember { AdvancedBlurQualityPreference.defaultForDevice(isCurrentBuildDimensity()) }
    val advancedBlurQuality by repo.advancedBlurQualityFlow.collectAsStateWithLifecycle(initialValue = initialAdvancedBlurQuality)
    val liquidGlassEnabled by repo.liquidGlassEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val enhancedVisualEffectsEnabled by repo.enhancedVisualEffectsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val dynamicBackgroundEnabled by repo.nowPlayingDynamicBackgroundEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val audioReactiveEnabled by repo.nowPlayingAudioReactiveEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val lyricBlurEnabled by repo.lyricBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val preferHighRefreshRate by repo.preferHighRefreshRateFlow.collectAsStateWithLifecycle(initialValue = true)
    val storedMotionConfig = StartupMotionConfig(
        advancedBlur = advancedBlurEnabled,
        enhancedAdvancedBlur = enhancedAdvancedBlurEnabled,
        advancedBlurQuality = advancedBlurQuality,
        liquidGlass = liquidGlassEnabled,
        enhancedVisualEffects = enhancedVisualEffectsEnabled,
        nowPlayingDynamicBackground = dynamicBackgroundEnabled,
        nowPlayingAudioReactive = audioReactiveEnabled,
        lyricBlur = lyricBlurEnabled,
        preferHighRefreshRate = preferHighRefreshRate
    )
    var motionTouched by rememberSaveable { mutableStateOf(false) }
    var motionScanPlayed by rememberSaveable { mutableStateOf(false) }
    var pendingMotionConfig by remember { mutableStateOf<StartupMotionConfig?>(null) }
    LaunchedEffect(storedMotionConfig, pendingMotionConfig) {
        if (pendingMotionConfig == storedMotionConfig) pendingMotionConfig = null
    }
    // 用户动手之前展示推荐方案, 离开这一步时才落盘; 动过之后以实际设置为准
    val displayedMotionConfig = when {
        !motionTouched -> startupMotionConfigFor(recommendation.preset, deviceSpec)
        else -> pendingMotionConfig ?: storedMotionConfig
    }
    val selectedMotionPreset = if (motionTouched) {
        matchStartupMotionPreset(displayedMotionConfig, deviceSpec)
    } else {
        recommendation.preset
    }
    fun applyMotionConfig(config: StartupMotionConfig) {
        motionTouched = true
        pendingMotionConfig = config
        scope.launch {
            runCatching {
                repo.setAdvancedBlurEnabled(config.advancedBlur)
                repo.setEnhancedAdvancedBlurEnabled(config.enhancedAdvancedBlur)
                repo.setAdvancedBlurQuality(config.advancedBlurQuality)
                repo.setLiquidGlassEnabled(config.liquidGlass)
                autoSettings.setEnhancedVisualEffectsEnabled(config.enhancedVisualEffects)
                repo.setNowPlayingDynamicBackgroundEnabled(config.nowPlayingDynamicBackground)
                repo.setNowPlayingAudioReactiveEnabled(config.nowPlayingAudioReactive)
                repo.setLyricBlurEnabled(config.lyricBlur)
                autoSettings.setPreferHighRefreshRate(config.preferHighRefreshRate)
            }.onFailure { pendingMotionConfig = null }
        }
    }

    // UI 缩放与背景
    val uiDensityScale by repo.uiDensityScaleFlow.collectAsStateWithLifecycle(initialValue = 1.0f)
    val configuration = LocalConfiguration.current
    val uiScaleRange = UiDensityScalePreference.forDevice(configuration.smallestScreenWidthDp, configuration.uiMode)
    var pendingUiScale by rememberSaveable { mutableFloatStateOf(uiScaleRange.normalize(uiDensityScale)) }
    val appliedPendingUiScale = uiScaleRange.normalize(pendingUiScale)
    LaunchedEffect(uiDensityScale, uiScaleRange.maximumScale) {
        val normalizedScale = uiScaleRange.normalize(uiDensityScale)
        if ((pendingUiScale - normalizedScale).absoluteValue > 0.001f) pendingUiScale = normalizedScale
    }
    val backgroundImageUri by repo.backgroundImageUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val backgroundImageBlur by repo.backgroundImageBlurFlow.collectAsStateWithLifecycle(initialValue = 0f)
    val backgroundImageAlpha by repo.backgroundImageAlphaFlow.collectAsStateWithLifecycle(initialValue = 0.3f)
    var pendingBackgroundImageBlur by remember { mutableStateOf<Float?>(null) }
    var pendingBackgroundImageAlpha by remember { mutableStateOf<Float?>(null) }
    val effectiveBackgroundImageBlur = pendingBackgroundImageBlur ?: backgroundImageBlur
    val effectiveBackgroundImageAlpha = pendingBackgroundImageAlpha ?: backgroundImageAlpha
    LaunchedEffect(backgroundImageBlur, pendingBackgroundImageBlur) {
        val pending = pendingBackgroundImageBlur ?: return@LaunchedEffect
        if ((pending - backgroundImageBlur).absoluteValue < 0.001f) pendingBackgroundImageBlur = null
    }
    LaunchedEffect(backgroundImageAlpha, pendingBackgroundImageAlpha) {
        val pending = pendingBackgroundImageAlpha ?: return@LaunchedEffect
        if ((pending - backgroundImageAlpha).absoluteValue < 0.001f) pendingBackgroundImageAlpha = null
    }
    val latestEnhancedAdvancedBlurEnabled by rememberUpdatedState(enhancedAdvancedBlurEnabled)
    val enhancedAdvancedBlurAvailable = isAdvancedGlassBackendSupported(sdkInt)
    var enhancedAdvancedBlurPromptVisible by rememberSaveable { mutableStateOf(false) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = BackgroundImageStorage.importFromUri(
                context = context,
                sourceUri = uri,
                previousUriString = backgroundImageUri
            ) ?: return@launch
            repo.setBackgroundImageUri(imported.toString())
            if (
                shouldPromptStartupEnhancedAdvancedBlur(
                    advancedBlurAvailable = enhancedAdvancedBlurAvailable,
                    enhancedAdvancedBlurEnabled = latestEnhancedAdvancedBlurEnabled,
                    backgroundImageImported = true
                )
            ) {
                enhancedAdvancedBlurPromptVisible = true
            }
        }
    }

    // 播放偏好
    val playbackControlLayoutPreferences by repo.playbackControlLayoutPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = repo.defaultPlaybackControlLayoutPreferences)
    val lyricFontScales by repo.lyricFontScalesFlow.collectAsStateWithLifecycle(initialValue = repo.defaultLyricFontScales)
    val neteaseAutoSourceSwitch by repo.neteaseAutoSourceSwitchFlow.collectAsStateWithLifecycle(initialValue = false)
    val neteaseLocalSourceFallback by repo.neteaseLocalSourceFallbackFlow.collectAsStateWithLifecycle(initialValue = false)

    // 权限
    val notificationPermission = StartupNotificationPermission.permission
    val localMediaPermission = remember(sdkInt) { StartupMediaPermission.permissionFor(sdkInt) }
    var notificationPermissionGranted by remember(notificationPermission) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var localMediaPermissionGranted by remember(localMediaPermission) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, localMediaPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestActive by remember { mutableStateOf(false) }
    var permissionNavigationBlocked by remember { mutableStateOf(false) }
    var notificationPermissionWarningVisible by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionWarningAttempts by rememberSaveable { mutableIntStateOf(0) }
    var noPlatformWarningVisible by rememberSaveable { mutableStateOf(false) }
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
            delay(500L.milliseconds)
            permissionNavigationBlocked = false
        }
    }
    val localMediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        localMediaPermissionGranted = granted
        permissionRequestActive = false
        scope.launch {
            delay(500L.milliseconds)
            permissionNavigationBlocked = false
        }
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

    // 隐私说明: 只在同意前倒计时, 同意动作交给宿主写入
    var privacyCountdown by rememberSaveable { mutableIntStateOf(STARTUP_PRIVACY_COUNTDOWN_SECONDS) }
    var policyOpen by rememberSaveable { mutableStateOf(false) }
    var awaitingConsent by remember { mutableStateOf(false) }
    LaunchedEffect(currentStep, privacyCountdown, disclaimerAccepted) {
        if (currentStep != StartupStep.Privacy || disclaimerAccepted || privacyCountdown <= 0) {
            return@LaunchedEffect
        }
        delay(1_000L.milliseconds)
        privacyCountdown -= 1
    }
    val session = if (disclaimerAccepted) rememberStartupOnboardingSession() else null
    val noPlatformConnected = session?.let { rememberStartupNoPlatformConnected(it) } ?: false

    var finishing by remember { mutableStateOf(false) }
    var celebrationOrigin by remember { mutableStateOf<Offset?>(null) }
    val reveal = rememberStartupRevealController(activity)

    val stepTransition = updateTransition(targetState = stepIndex, label = "startup_step")

    fun transitionToStep(targetIndex: Int) {
        val nextIndex = targetIndex.coerceIn(0, steps.lastIndex)
        if (finishing || nextIndex == stepIndex) return
        if (!canEnterStartupStep(steps[nextIndex], latestDisclaimerAccepted)) return
        policyOpen = false
        stepIndex = nextIndex
    }
    LaunchedEffect(disclaimerAccepted) {
        if (disclaimerAccepted && awaitingConsent) {
            awaitingConsent = false
            transitionToStep(StartupStep.Privacy.ordinal + 1)
        }
    }

    fun selectLanguage(language: LanguageManager.Language) {
        if (selectedLanguage == language) return
        selectedLanguageCode = language.code
        LanguageManager.setLanguage(context, language)
        onLanguageChanged(language)
    }

    fun requestStyleChange(style: UiStyle, origin: Offset, startRadius: Float) {
        if (style == uiStyle) return
        val previous = storedUiStyle
        reveal.request(
            origin = origin,
            startRadius = startRadius,
            coverColor = if (latestIsDarkTheme) Color(0xFF121212) else Color.White,
            beforeApply = { pendingUiStyle = style },
            apply = { repo.setUiStyle(style) },
            onFailure = { pendingUiStyle = previous }
        )
    }

    fun requestThemeToggle(origin: Offset, startRadius: Float) {
        val nextDark = !latestIsDarkTheme
        reveal.request(
            origin = origin,
            startRadius = startRadius,
            coverColor = if (latestIsDarkTheme) Color(0xFF121212) else Color.White,
            beforeApply = {
                pendingFollowSystemDark = false
                pendingForceDark = nextDark
            },
            apply = { repo.setThemeMode(followSystemDark = false, forceDark = nextDark) },
            onFailure = {
                pendingFollowSystemDark = null
                pendingForceDark = null
            }
        )
    }

    fun answerPreviewThemePrompt() {
        val chosen = uiStyle
        scope.launch {
            runCatching { PreviewThemePromptRepository(context).answer(enable = chosen == UiStyle.Clean) }
        }
    }

    fun finishOnboarding(origin: Offset?) {
        if (finishing) return
        finishing = true
        celebrationOrigin = origin
        answerPreviewThemePrompt()
        scope.launch {
            delay(STARTUP_FINISH_CELEBRATION_LEAD_MILLIS.milliseconds)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repo.setUiDensityScale(pendingUiScale)
                    repo.setStartupOnboardingCompleted(true)
                }
            }
            if (result.isFailure) {
                finishing = false
                celebrationOrigin = null
            }
        }
    }

    fun goNextStep(origin: Offset? = null) {
        if (!canNavigateStartupOnboardingStep(finishing, stepTransition.isRunning)) return
        if (!shouldAdvanceStartupOnboarding(permissionRequestActive, permissionNavigationBlocked)) return
        when (currentStep) {
            StartupStep.Style -> answerPreviewThemePrompt()
            StartupStep.Motion -> if (!motionTouched) applyMotionConfig(displayedMotionConfig)
            StartupStep.Privacy -> if (!canLeaveStartupStepForward(currentStep, disclaimerAccepted)) {
                if (privacyCountdown > 0 || awaitingConsent) return
                awaitingConsent = true
                policyOpen = false
                onAcceptDisclaimer()
                return
            }
            StartupStep.Permissions -> if (
                shouldShowStartupNotificationPermissionWarning(
                    permissionSupported = StartupNotificationPermission.isSupported(sdkInt),
                    permissionGranted = notificationPermissionGranted,
                    attempts = notificationPermissionWarningAttempts
                )
            ) {
                showNotificationPermissionWarning()
                return
            }
            StartupStep.Platforms -> if (noPlatformConnected) {
                noPlatformWarningVisible = true
                return
            }
            StartupStep.LearningGuide -> {
                finishOnboarding(origin)
                return
            }
            else -> Unit
        }
        transitionToStep(stepIndex + 1)
    }

    fun goBackStep() {
        if (!canNavigateStartupOnboardingBack(finishing, stepIndex)) return
        transitionToStep(stepIndex - 1)
    }
    BackHandler(enabled = stepIndex > 0 && !finishing && !policyOpen, onBack = ::goBackStep)

    // 玻璃
    val visualEffectsDeviceProfile = rememberVisualEffectsDeviceProfile()
    val liquidGlassRequested = uiStyle == UiStyle.Clean && liquidGlassEnabled &&
        visualEffectsDeviceProfile.liquidGlassAllowed(advancedBlurQuality)
    val advancedGlassController = remember(
        sdkInt, advancedBlurEnabled, enhancedAdvancedBlurEnabled, enhancedAdvancedBlurRadiusDp,
        advancedBlurQuality, liquidGlassRequested
    ) {
        AdvancedGlassController(
            sdkInt = sdkInt,
            advancedBlurEnabled = advancedBlurEnabled,
            enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
            backendReady = enhancedAdvancedBlurAvailable,
            enhancedAdvancedBlurRadiusDp = enhancedAdvancedBlurRadiusDp,
            advancedBlurQuality = advancedBlurQuality,
            liquidGlassRequested = liquidGlassRequested
        )
    }
    val backgroundGlassBackdrop = rememberAdvancedGlassBackdrop()
    val contentGlassBackdrop = rememberAdvancedGlassBackdrop()
    val enhancedEffectsActive = uiStyle == UiStyle.Clean && enhancedVisualEffectsEnabled &&
        visualEffectsDeviceProfile.enhancedVisualEffectsAllowed

    val baseDensity = LocalDensity.current
    val previewDensity = remember(baseDensity.fontScale, composeResources.displayMetrics.density, appliedPendingUiScale) {
        Density(composeResources.displayMetrics.density * appliedPendingUiScale, baseDensity.fontScale)
    }

    val choiceSummaries = listOf(
        StartupChoiceSummary(StartupChoice.Language, Icons.Rounded.Translate, startupLanguageShortLabel(selectedLanguage)),
        StartupChoiceSummary(StartupChoice.Style, Icons.Rounded.Palette, startupStyleLabel(uiStyle)),
        StartupChoiceSummary(
            StartupChoice.Motion,
            startupMotionPresetIcon(selectedMotionPreset),
            startupMotionPresetLabel(selectedMotionPreset)
        )
    )

    @Composable
    fun StepBody(step: StartupStep, sceneActive: Boolean) {
        when (step) {
            StartupStep.Language -> StartupLanguageStepBody(
                selectedLanguage = selectedLanguage,
                onSelectLanguage = ::selectLanguage
            )
            StartupStep.Style -> StartupStyleStepBody(
                selectedStyle = uiStyle,
                themeInputs = themeInputs,
                onSelectStyle = ::requestStyleChange
            )
            StartupStep.Motion -> StartupMotionStepBody(
                spec = deviceSpec,
                recommendation = recommendation,
                selectedPreset = selectedMotionPreset,
                config = displayedMotionConfig,
                uiStyle = uiStyle,
                scanPlayed = motionScanPlayed,
                onScanPlayed = { motionScanPlayed = true },
                onSelectPreset = { preset -> applyMotionConfig(startupMotionConfigFor(preset, deviceSpec)) },
                onConfigChange = ::applyMotionConfig
            )
            StartupStep.Privacy -> StartupPrivacyStepBody(
                accepted = disclaimerAccepted,
                policyOpen = policyOpen,
                onOpenPolicy = { policyOpen = true }
            )
            StartupStep.Platforms -> session?.let { StartupPlatformsStepBody(it, isActive = sceneActive) }
            StartupStep.PlaybackSources -> StartupPlaybackSourceContent(
                autoSourceSwitchEnabled = neteaseAutoSourceSwitch,
                localSourceFallbackEnabled = neteaseLocalSourceFallback,
                onSetFallbackEnabled = { enabled -> scope.launch { repo.setNeteasePlaybackSourceFallback(enabled) } }
            )
            StartupStep.Permissions -> StartupPermissionContent(
                notificationPermissionSupported = StartupNotificationPermission.isSupported(sdkInt),
                notificationPermissionGranted = notificationPermissionGranted,
                localMediaPermissionGranted = localMediaPermissionGranted,
                onRequestNotificationPermission = ::requestNotificationPermission,
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
                    scope.launch { repo.setLyricFontScale(LyricFontScaleTarget.COVER_LYRIC, scale) }
                },
                onPreferencesChange = { preferences ->
                    scope.launch { repo.setPlaybackControlLayoutPreferences(preferences) }
                }
            )
            StartupStep.Lyrics -> StartupLyricsContent(
                preferences = playbackControlLayoutPreferences,
                lyricFontScale = lyricFontScales.lyricsPageLyric,
                onLyricFontScaleChange = { scale ->
                    scope.launch { repo.setLyricFontScale(LyricFontScaleTarget.LYRICS_PAGE_LYRIC, scale) }
                },
                onPreferencesChange = { preferences ->
                    scope.launch { repo.setPlaybackControlLayoutPreferences(preferences) }
                }
            )
            StartupStep.Personalize -> StartupPersonalizeStepBody(
                pendingUiScale = appliedPendingUiScale,
                onUiScaleChange = { pendingUiScale = it },
                onUiScaleCommit = { scope.launch { repo.setUiDensityScale(pendingUiScale) } },
                backgroundImageUri = backgroundImageUri,
                backgroundImageBlur = effectiveBackgroundImageBlur,
                backgroundImageAlpha = effectiveBackgroundImageAlpha,
                onSelectBackground = {
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onClearBackground = {
                    pendingBackgroundImageBlur = null
                    pendingBackgroundImageAlpha = null
                    scope.launch {
                        BackgroundImageStorage.deleteManagedBackground(context, backgroundImageUri)
                        repo.setBackgroundImageUri(null)
                    }
                },
                onBackgroundBlurChange = { pendingBackgroundImageBlur = it },
                onBackgroundBlurCommit = { blur -> scope.launch { repo.setBackgroundImageBlur(blur) } },
                onBackgroundAlphaChange = { pendingBackgroundImageAlpha = it },
                onBackgroundAlphaCommit = { alpha -> scope.launch { repo.setBackgroundImageAlpha(alpha) } },
                isDarkTheme = isDarkTheme,
                onThemeToggleRequest = ::requestThemeToggle
            )
            StartupStep.BackupRestore -> session?.let { StartupBackupRestoreStepBody(it) }
            StartupStep.LearningGuide -> StartupFinishStepBody(
                summaries = choiceSummaries,
                onChoiceClick = { choice -> transitionToStep(choice.step.ordinal) }
            )
        }
    }

    @Composable
    fun StepHero(step: StartupStep, layout: StartupHeroLayout, visibilityScope: AnimatedVisibilityScope) {
        StartupStepHero(
            spec = startupHeroSpec(step),
            layout = layout,
            visibilityScope = visibilityScope,
            accessory = {
                if (step == StartupStep.Language) {
                    StartupGreetingTicker(greetings = startupGreetings())
                    Spacer(Modifier.height(10.dp))
                }
            }
        )
    }

    StartupStyleTheme(
        style = uiStyle,
        inputs = themeInputs,
        animateColors = !reveal.revealActive,
        liquidGlass = advancedGlassController.isLiquidGlassEnabled,
        enhancedEffects = enhancedEffectsActive
    ) {
        val clean = NeriDesign.isClean
        SideEffect { NeriMotionStyle.clean = clean }
        CompositionLocalProvider(
            LocalDensity provides previewDensity,
            LocalHasCustomBackground provides (backgroundImageUri != null)
        ) {
            val colors = MaterialTheme.colorScheme
            val activeOwners = setOf<Any>(steps[stepTransition.targetState])
            val prewarmedOwners = linkedSetOf<Any>(steps[stepTransition.currentState], steps[stepTransition.targetState])
            AdvancedGlassHost(
                controller = advancedGlassController,
                backgroundBackdrop = backgroundGlassBackdrop,
                contentBackdrop = contentGlassBackdrop,
                activeNavigationOwners = activeOwners,
                prewarmedNavigationOwners = prewarmedOwners,
                disableStretchOverscroll = backgroundImageUri != null
            ) {
                SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalStartupSharedTransition provides this) {
                        AdvancedGlassNavigationHandoff(enabled = stepTransition.isRunning) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .captureAdvancedGlassBackdrop(backgroundGlassBackdrop)
                                        .background(colors.background)
                                ) {
                                    StartupAmbientBackdrop(
                                        stepIndex = stepIndex,
                                        hasCustomBackground = backgroundImageUri != null
                                    )
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
                                    StartupOnboardingLayout(
                                        header = {
                                            val twoPane = LocalStartupOnboardingTwoPane.current
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                StartupProgressHeader(stepIndex = stepIndex, stepCount = steps.size)
                                                Column(modifier = Modifier.animateContentSize(spring(0.9f, 420f))) {
                                                    if (currentStep != StartupStep.Language) {
                                                        Spacer(Modifier.height(10.dp))
                                                    }
                                                    StartupChoiceRail(
                                                        currentStep = currentStep,
                                                        summaries = choiceSummaries,
                                                        onChoiceClick = { choice -> transitionToStep(choice.step.ordinal) }
                                                    )
                                                }
                                                if (twoPane) {
                                                    Spacer(Modifier.height(28.dp))
                                                    stepTransition.AnimatedContent(
                                                        transitionSpec = {
                                                            (fadeIn(tween(260, delayMillis = 80)) +
                                                                slideInVertically(spring(0.86f, 320f)) { it / 6 })
                                                                .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 10 })
                                                                .using(SizeTransform(clip = false))
                                                        }
                                                    ) { index ->
                                                        StepHero(steps[index], StartupHeroLayout.Pane, this)
                                                    }
                                                }
                                            }
                                        },
                                        actions = {
                                            StartupActionBar(
                                                showBack = stepIndex > 0,
                                                backEnabled = canNavigateStartupOnboardingBack(finishing, stepIndex),
                                                onBack = ::goBackStep,
                                                primaryLabel = when (currentStep) {
                                                    StartupStep.Privacy -> startupPrivacyAgreeLabel(disclaimerAccepted, privacyCountdown)
                                                    StartupStep.LearningGuide -> stringResource(AppR.string.onboarding_learning_enter_app)
                                                    else -> stringResource(AppR.string.onboarding_action_next)
                                                },
                                                primaryEnabled = !finishing && !awaitingConsent &&
                                                    (currentStep != StartupStep.Privacy || disclaimerAccepted || privacyCountdown <= 0),
                                                onPrimary = { goNextStep() },
                                                primaryProgress = if (currentStep == StartupStep.Privacy && !disclaimerAccepted) {
                                                    resolveStartupPrivacyCountdownProgress(privacyCountdown)
                                                } else {
                                                    null
                                                }
                                            )
                                        }
                                    ) {
                                        val twoPane = LocalStartupOnboardingTwoPane.current
                                        stepTransition.AnimatedContent(
                                            modifier = Modifier.fillMaxSize(),
                                            transitionSpec = {
                                                startupStepContentTransform(forward = targetState > initialState, clean = clean)
                                            }
                                        ) { index ->
                                            val step = steps[index]
                                            CompositionLocalProvider(
                                                LocalStartupStepVisibility provides this,
                                                LocalAdvancedGlassNavigationOwner provides step
                                            ) {
                                                AdvancedGlassSceneLayer(
                                                    controller = advancedGlassController,
                                                    modifier = Modifier.fillMaxSize(),
                                                    disableStretchOverscroll = backgroundImageUri != null,
                                                    fixedBackground = true,
                                                    background = { Box(Modifier.fillMaxSize()) }
                                                ) {
                                                    val stepScroll = rememberScrollState()
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .startupEdgeFade(
                                                                topStrength = { stepScroll.value / 120f },
                                                                bottomStrength = { if (stepScroll.canScrollForward) 1f else 0f }
                                                            )
                                                            .verticalScroll(stepScroll)
                                                    ) {
                                                        if (!twoPane) {
                                                            StepHero(
                                                                step = step,
                                                                layout = if (step == StartupStep.Language) {
                                                                    StartupHeroLayout.Welcome
                                                                } else {
                                                                    StartupHeroLayout.Compact
                                                                },
                                                                visibilityScope = this@AnimatedContent
                                                            )
                                                            Spacer(Modifier.height(24.dp))
                                                        }
                                                        Column(
                                                            modifier = Modifier.animateEnterExit(
                                                                enter = fadeIn(tween(320, delayMillis = 120)) +
                                                                    slideInVertically(spring(0.85f, 260f)) { it / 10 },
                                                                exit = fadeOut(tween(120))
                                                            )
                                                        ) {
                                                            StepBody(
                                                                step = step,
                                                                sceneActive = index == stepIndex && !stepTransition.isRunning && !finishing
                                                            )
                                                        }
                                                        Spacer(Modifier.height(16.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                StartupPrivacyPolicyOverlay(
                                    visible = policyOpen && currentStep == StartupStep.Privacy,
                                    onClose = { policyOpen = false },
                                    agreeLabel = startupPrivacyAgreeLabel(disclaimerAccepted, privacyCountdown),
                                    agreeEnabled = !awaitingConsent && (disclaimerAccepted || privacyCountdown <= 0),
                                    agreeProgress = if (!disclaimerAccepted) {
                                        resolveStartupPrivacyCountdownProgress(privacyCountdown)
                                    } else {
                                        null
                                    },
                                    onAgree = {
                                        if (disclaimerAccepted) {
                                            policyOpen = false
                                        } else {
                                            goNextStep()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                session?.let { activeSession ->
                    StartupSessionDialogs(
                        session = activeSession,
                        backupStepActive = currentStep == StartupStep.BackupRestore,
                        finishing = finishing
                    )
                }
                if (notificationPermissionWarningVisible) {
                    StartupNotificationPermissionWarningDialog(
                        attempt = notificationPermissionWarningAttempts,
                        onRequestPermission = ::requestNotificationPermission,
                        onDismiss = {
                            notificationPermissionWarningVisible = false
                            if (!hasFinishedStartupNotificationPermissionWarning(notificationPermissionWarningAttempts)) {
                                scope.launch {
                                    delay(180L.milliseconds)
                                    if (!notificationPermissionGranted) showNotificationPermissionWarning()
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
                StartupCelebrationBurst(active = finishing, origin = celebrationOrigin)
                StartupRevealOverlay(reveal)
            }
        }
    }
}

@Composable
private fun startupHeroSpec(step: StartupStep): StartupHeroSpec = when (step) {
    StartupStep.Language -> StartupHeroSpec(
        Icons.Rounded.GraphicEq, 9,
        stringResource(AppR.string.onboarding_title), stringResource(AppR.string.onboarding_subtitle)
    )
    StartupStep.Style -> StartupHeroSpec(
        Icons.Rounded.Palette, 6,
        stringResource(AppR.string.onboarding_style_title), stringResource(AppR.string.onboarding_style_desc)
    )
    StartupStep.Motion -> StartupHeroSpec(
        Icons.Rounded.Animation, 8,
        stringResource(AppR.string.onboarding_motion_title), stringResource(AppR.string.onboarding_motion_desc)
    )
    StartupStep.Privacy -> StartupHeroSpec(
        Icons.Rounded.Shield, 5,
        stringResource(AppR.string.disclaimer_title), stringResource(AppR.string.disclaimer_intro)
    )
    StartupStep.Platforms -> StartupHeroSpec(
        Icons.Rounded.Hub, 7,
        stringResource(AppR.string.onboarding_platforms_title), stringResource(AppR.string.onboarding_platforms_desc)
    )
    StartupStep.PlaybackSources -> StartupHeroSpec(
        Icons.Rounded.AltRoute, 6,
        stringResource(AppR.string.onboarding_playback_sources_title),
        stringResource(AppR.string.onboarding_playback_sources_desc)
    )
    StartupStep.Permissions -> StartupHeroSpec(
        Icons.Rounded.NotificationsActive, 4,
        stringResource(AppR.string.onboarding_permissions_title), stringResource(AppR.string.onboarding_permissions_desc)
    )
    StartupStep.PlaybackControls -> StartupHeroSpec(
        Icons.Rounded.PlayCircle, 10,
        stringResource(AppR.string.onboarding_controls_title), stringResource(AppR.string.onboarding_controls_desc)
    )
    StartupStep.Lyrics -> StartupHeroSpec(
        Icons.Rounded.Subtitles, 8,
        stringResource(AppR.string.onboarding_lyrics_title), stringResource(AppR.string.onboarding_lyrics_desc)
    )
    StartupStep.Personalize -> StartupHeroSpec(
        Icons.Rounded.Brush, 12,
        stringResource(AppR.string.onboarding_personalize_title), stringResource(AppR.string.onboarding_personalize_desc)
    )
    StartupStep.BackupRestore -> StartupHeroSpec(
        Icons.Rounded.CloudSync, 6,
        stringResource(AppR.string.onboarding_backup_restore_title),
        stringResource(AppR.string.onboarding_backup_restore_desc)
    )
    StartupStep.LearningGuide -> StartupHeroSpec(
        Icons.Rounded.Celebration, 12,
        stringResource(AppR.string.onboarding_finish_title), stringResource(AppR.string.onboarding_finish_desc)
    )
}

@Composable
private fun startupGreetings(): List<String> {
    val raw = stringResource(AppR.string.onboarding_welcome_greetings)
    return remember(raw) { raw.split('|').map(String::trim).filter(String::isNotEmpty) }
}

@Composable
private fun startupLanguageShortLabel(language: LanguageManager.Language): String = stringResource(
    when (language) {
        LanguageManager.Language.CHINESE -> AppR.string.onboarding_language_short_zh
        LanguageManager.Language.ENGLISH -> AppR.string.onboarding_language_short_en
        LanguageManager.Language.SYSTEM -> AppR.string.onboarding_language_short_system
    }
)

@Composable
private fun StartupNoPlatformWarningDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppR.string.onboarding_platforms_no_login_title)) },
        text = { Text(stringResource(AppR.string.onboarding_platforms_no_login_desc)) },
        confirmButton = {
            ApplyOverlayWindowNavigationBarPolicy()
            HapticTextButton(onClick = onContinue) {
                Text(stringResource(AppR.string.onboarding_platforms_no_login_continue))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(AppR.string.onboarding_platforms_no_login_connect))
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
        title = { Text(stringResource(AppR.string.onboarding_enhanced_blur_prompt_title)) },
        text = { Text(stringResource(AppR.string.onboarding_enhanced_blur_prompt_desc)) },
        confirmButton = {
            ApplyOverlayWindowNavigationBarPolicy()
            HapticTextButton(onClick = onEnable) {
                Text(stringResource(AppR.string.onboarding_enhanced_blur_prompt_enable))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(AppR.string.onboarding_enhanced_blur_prompt_not_now))
            }
        }
    )
}

/** 通知权限的二次确认只能通过按钮做决定, 点外部不会关闭, 避免误触直接跳过 */
internal val StartupNotificationWarningDialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false
)

@Composable
internal fun StartupNotificationPermissionWarningDialog(
    attempt: Int,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val isFinalWarning = attempt >= STARTUP_NOTIFICATION_PERMISSION_WARNING_ATTEMPTS
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = StartupNotificationWarningDialogProperties,
        title = {
            Text(
                stringResource(
                    if (isFinalWarning) {
                        AppR.string.onboarding_notification_permission_final_warning_title
                    } else {
                        AppR.string.onboarding_notification_permission_warning_title
                    }
                )
            )
        },
        text = {
            Text(
                stringResource(
                    if (isFinalWarning) {
                        AppR.string.onboarding_notification_permission_final_warning_desc
                    } else {
                        AppR.string.onboarding_notification_permission_warning_desc
                    }
                )
            )
        },
        confirmButton = {
            ApplyOverlayWindowNavigationBarPolicy()
            HapticTextButton(onClick = onRequestPermission) {
                Text(
                    stringResource(
                        if (isFinalWarning) {
                            AppR.string.onboarding_notification_permission_final_request
                        } else {
                            AppR.string.onboarding_notification_permission_request_again
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
                            AppR.string.onboarding_notification_permission_final_skip
                        } else {
                            AppR.string.onboarding_notification_permission_skip
                        }
                    )
                )
            }
        }
    )
}
