package moe.ouom.neriplayer.ui.onboarding

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.PixelCopy
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import moe.ouom.neriplayer.data.settings.scaledLyricFontSize
import moe.ouom.neriplayer.ui.BlurTransformation
import moe.ouom.neriplayer.ui.component.ThemeRevealOverlay
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsBiliAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsNeteaseAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsYouTubeAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.component.InlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.maskCookieValue
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsGitHubDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncUiState
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import moe.ouom.neriplayer.util.HapticButton
import moe.ouom.neriplayer.util.HapticOutlinedButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.getDisplayName
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.drawToBitmap
import kotlin.coroutines.resume
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap

private enum class StartupStep {
    Language,
    Platforms,
    GitHubSync,
    Personalize
}

@Composable
fun StartupOnboardingScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repo = AppContainer.settingsRepo

    val steps = remember { StartupStep.entries }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedLanguageCode by rememberSaveable {
        mutableStateOf(LanguageManager.getCurrentLanguage(context).code)
    }
    val selectedLanguage = remember(selectedLanguageCode) {
        LanguageManager.Language.entries.firstOrNull { it.code == selectedLanguageCode }
            ?: LanguageManager.Language.SYSTEM
    }

    val uiDensityScale by repo.uiDensityScaleFlow.collectAsState(initial = 1.0f)
    var pendingUiScale by rememberSaveable { mutableFloatStateOf(uiDensityScale) }
    LaunchedEffect(uiDensityScale) {
        if ((pendingUiScale - uiDensityScale).absoluteValue > 0.001f) {
            pendingUiScale = uiDensityScale
        }
    }
    val lyricFontScale by repo.lyricFontScaleFlow.collectAsState(initial = 1.0f)
    var pendingLyricFontScale by rememberSaveable { mutableFloatStateOf(lyricFontScale) }
    LaunchedEffect(lyricFontScale) {
        if ((pendingLyricFontScale - lyricFontScale).absoluteValue > 0.001f) {
            pendingLyricFontScale = lyricFontScale
        }
    }

    val backgroundImageUri by repo.backgroundImageUriFlow.collectAsState(initial = null)
    val backgroundImageBlur by repo.backgroundImageBlurFlow.collectAsState(initial = 0f)
    val backgroundImageAlpha by repo.backgroundImageAlphaFlow.collectAsState(initial = 0.3f)
    val followSystemDark by repo.followSystemDarkFlow.collectAsState(initial = true)
    val forceDark by repo.forceDarkFlow.collectAsState(initial = false)
    val systemDark = isSystemInDarkTheme()
    val isDarkTheme = when {
        forceDark -> true
        followSystemDark -> systemDark
        else -> false
    }

    var inlineMessage by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }
    val rootView = LocalView.current
    var themeRevealSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var themeRevealOriginWindow by remember { mutableStateOf<Offset?>(null) }
    var themeRevealStartRadiusPx by remember { mutableFloatStateOf(0f) }
    var themeRevealFallbackColor by remember { mutableStateOf<ComposeColor?>(null) }
    var themeRevealCaptureInFlight by remember { mutableStateOf(false) }

    var showNeteaseSheet by remember { mutableStateOf(false) }
    var showNeteaseConfirm by remember { mutableStateOf(false) }
    var showNeteaseCookieDialog by remember { mutableStateOf(false) }
    var neteaseMaskedPhone by remember { mutableStateOf<String?>(null) }
    var neteaseCookieText by remember { mutableStateOf("") }
    var neteaseSheetTab by rememberSaveable { mutableIntStateOf(0) }

    var showBiliSheet by remember { mutableStateOf(false) }
    var showBiliCookieDialog by remember { mutableStateOf(false) }
    var biliCookieText by remember { mutableStateOf("") }
    var biliSheetTab by rememberSaveable { mutableIntStateOf(0) }

    var showYouTubeSheet by remember { mutableStateOf(false) }
    var showYouTubeCookieDialog by remember { mutableStateOf(false) }
    var youTubeCookieText by remember { mutableStateOf("") }
    var youTubeSheetTab by rememberSaveable { mutableIntStateOf(0) }
    var showGitHubConfigDialog by remember { mutableStateOf(false) }
    var showClearGitHubConfigDialog by remember { mutableStateOf(false) }

    val neteaseVm: NeteaseAuthViewModel = viewModel()
    val neteaseState by neteaseVm.uiState.collectAsState()
    val biliVm: BiliAuthViewModel = viewModel()
    val biliState by biliVm.uiState.collectAsState()
    val youTubeVm: YouTubeAuthViewModel = viewModel()
    val youTubeState by youTubeVm.uiState.collectAsState()
    val githubVm: GitHubSyncViewModel = viewModel()
    val githubState by githubVm.uiState.collectAsState()

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
            }
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

    LaunchedEffect(neteaseVm) {
        neteaseVm.events.collect { event ->
            when (event) {
                is NeteaseAuthEvent.ShowSnack -> inlineMessage = event.message
                is NeteaseAuthEvent.AskConfirmSend -> {
                    neteaseMaskedPhone = event.masked
                    showNeteaseConfirm = true
                }
                is NeteaseAuthEvent.ShowCookies -> {
                    neteaseCookieText = event.cookies.entries.joinToString("\n") { (k, v) ->
                        "$k=${maskCookieValue(v)}"
                    }
                    showNeteaseCookieDialog = true
                }
                NeteaseAuthEvent.LoginSuccess -> {
                    inlineMessage = context.getString(R.string.settings_netease_login_success)
                    showNeteaseSheet = false
                    neteaseVm.refreshAuthHealth()
                }
            }
        }
    }

    LaunchedEffect(biliVm) {
        biliVm.events.collect { event ->
            when (event) {
                is BiliAuthEvent.ShowSnack -> inlineMessage = event.message
                is BiliAuthEvent.ShowCookies -> {
                    biliCookieText = event.cookies.entries.joinToString("\n") { (k, v) ->
                        "$k=${maskCookieValue(v)}"
                    }
                    showBiliCookieDialog = true
                }
                BiliAuthEvent.LoginSuccess -> {
                    inlineMessage = context.getString(R.string.settings_bili_login_success)
                    showBiliSheet = false
                    biliVm.refreshAuthHealth()
                }
            }
        }
    }

    LaunchedEffect(youTubeVm) {
        youTubeVm.events.collect { event ->
            when (event) {
                is YouTubeAuthEvent.ShowSnack -> inlineMessage = event.message
                is YouTubeAuthEvent.ShowCookies -> {
                    youTubeCookieText = event.cookies.entries.joinToString("\n") { (k, v) ->
                        "$k=${maskCookieValue(v)}"
                    }
                    showYouTubeCookieDialog = true
                }
                YouTubeAuthEvent.LoginSuccess -> {
                    inlineMessage = context.getString(R.string.settings_youtube_login_success)
                    showYouTubeSheet = false
                    youTubeVm.refreshAuthHealth()
                }
            }
        }
    }

    val baseDensity = LocalDensity.current
    val previewDensity = remember(baseDensity, pendingUiScale) {
        Density(baseDensity.density * pendingUiScale, baseDensity.fontScale)
    }

    fun selectLanguage(language: LanguageManager.Language) {
        selectedLanguageCode = language.code
    }

    fun finishOnboarding() {
        if (finishing) return
        finishing = true
        scope.launch {
            runCatching {
                repo.setUiDensityScale(pendingUiScale)
                repo.setLyricFontScale(pendingLyricFontScale)
                repo.setStartupOnboardingCompleted(true)
            }.onFailure {
                finishing = false
            }
        }
    }

    fun goNextStep() {
        if (stepIndex == steps.lastIndex) {
            finishOnboarding()
            return
        }

        val currentStep = steps[stepIndex]
        if (currentStep == StartupStep.Language) {
            val currentLanguage = LanguageManager.getCurrentLanguage(context)
            if (currentLanguage != selectedLanguage) {
                stepIndex = StartupStep.Platforms.ordinal
                LanguageManager.setLanguage(context, selectedLanguage)
                activity?.let(LanguageManager::restartActivity)
                return
            }
        }

        stepIndex = (stepIndex + 1).coerceAtMost(steps.lastIndex)
    }

    fun clearThemeRevealState() {
        themeRevealSnapshot = null
        themeRevealOriginWindow = null
        themeRevealStartRadiusPx = 0f
        themeRevealFallbackColor = null
        themeRevealCaptureInFlight = false
    }

    fun requestThemeToggle(originInWindow: Offset, startRadiusPx: Float) {
        if (themeRevealCaptureInFlight || themeRevealOriginWindow != null) {
            return
        }

        val nextDark = !isDarkTheme
        themeRevealCaptureInFlight = true
        scope.launch {
            val captureView = activity?.window?.decorView?.rootView ?: rootView.rootView
            awaitStartupStableDraw(captureView)
            val snapshot = captureStartupThemeRevealSnapshot(
                activity = activity,
                fallbackView = captureView
            )
            themeRevealSnapshot = snapshot
            themeRevealFallbackColor = if (isDarkTheme) {
                ComposeColor(0xFF121212)
            } else {
                ComposeColor.White
            }
            themeRevealOriginWindow = originInWindow
            themeRevealStartRadiusPx = startRadiusPx.coerceAtLeast(1f)
            themeRevealCaptureInFlight = false
            repo.setFollowSystemDark(false)
            repo.setForceDark(nextDark)
        }
    }

    CompositionLocalProvider(LocalDensity provides previewDensity) {
        val colorScheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            colorScheme.surfaceVariant.copy(alpha = 0.86f),
                            colorScheme.surface,
                            colorScheme.primaryContainer.copy(alpha = 0.66f)
                        ),
                        start = Offset.Zero,
                        end = Offset(1200f, 2400f)
                    )
                )
        ) {
            Blob(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 40.dp),
                color = colorScheme.primary.copy(alpha = 0.14f),
                size = 210.dp
            )
            Blob(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 64.dp),
                color = colorScheme.tertiary.copy(alpha = 0.14f),
                size = 260.dp
            )

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
                shape = RoundedCornerShape(34.dp),
                color = colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 10.dp,
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_badge),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.headlineMedium,
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
                        progress = { (stepIndex + 1f) / steps.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.onboarding_step_counter, stepIndex + 1, steps.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))

                    AnimatedContent(
                        targetState = stepIndex,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            val forward = targetState > initialState
                            val enter = slideInHorizontally(
                                animationSpec = tween(380, easing = FastOutSlowInEasing),
                                initialOffsetX = { if (forward) it / 4 else -it / 4 }
                            ) + fadeIn(animationSpec = tween(280))
                            val exit = slideOutHorizontally(
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                targetOffsetX = { if (forward) -it / 5 else it / 5 }
                            ) + fadeOut(animationSpec = tween(220))
                            enter togetherWith exit using SizeTransform(clip = false)
                        },
                        label = "startup_step"
                    ) { currentStep ->
                        StepContainer {
                            when (steps[currentStep]) {
                                StartupStep.Language -> LanguageContent(
                                    selectedLanguage = selectedLanguage,
                                    onSelectLanguage = ::selectLanguage
                                )
                                StartupStep.Platforms -> PlatformContent(
                                    inlineMessage = inlineMessage,
                                    onInlineMessageChange = { inlineMessage = it },
                                    biliState = biliState.health.state,
                                    neteaseState = neteaseState.health.state,
                                    youTubeState = youTubeState.health.state,
                                    onOpenBili = {
                                        inlineMessage = null
                                        biliSheetTab = 0
                                        showBiliSheet = true
                                    },
                                    onLogoutBili = {
                                        AppContainer.biliCookieRepo.clear()
                                        biliVm.refreshAuthHealth()
                                        inlineMessage = null
                                    },
                                    onOpenNetease = {
                                        inlineMessage = null
                                        neteaseSheetTab = 0
                                        showNeteaseSheet = true
                                    },
                                    onLogoutNetease = {
                                        AppContainer.neteaseClient.logout()
                                        AppContainer.neteaseCookieRepo.clear()
                                        neteaseVm.refreshAuthHealth()
                                        inlineMessage = null
                                    },
                                    onOpenYouTube = {
                                        inlineMessage = null
                                        youTubeSheetTab = 0
                                        showYouTubeSheet = true
                                    },
                                    onLogoutYouTube = {
                                        AppContainer.youtubeAuthRepo.clear()
                                        youTubeVm.refreshAuthHealth()
                                        inlineMessage = null
                                    }
                                )
                                StartupStep.GitHubSync -> GitHubSyncContent(
                                    gitHubState = githubState,
                                    onDismissGitHubMessage = githubVm::clearMessages,
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
                                    }
                                )
                                StartupStep.Personalize -> PersonalizeContent(
                                    pendingUiScale = pendingUiScale,
                                    onUiScaleChange = { pendingUiScale = it },
                                    onUiScaleCommit = { scope.launch { repo.setUiDensityScale(pendingUiScale) } },
                                    pendingLyricFontScale = pendingLyricFontScale,
                                    onLyricFontScaleChange = { pendingLyricFontScale = it },
                                    onLyricFontScaleCommit = { scope.launch { repo.setLyricFontScale(pendingLyricFontScale) } },
                                    backgroundImageUri = backgroundImageUri,
                                    backgroundImageBlur = backgroundImageBlur,
                                    backgroundImageAlpha = backgroundImageAlpha,
                                    onSelectBackground = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    onClearBackground = {
                                        scope.launch {
                                            BackgroundImageStorage.deleteManagedBackground(context, backgroundImageUri)
                                            repo.setBackgroundImageUri(null)
                                        }
                                    },
                                    onBackgroundBlurCommit = { blur ->
                                        scope.launch { repo.setBackgroundImageBlur(blur) }
                                    },
                                    onBackgroundAlphaCommit = { alpha ->
                                        scope.launch { repo.setBackgroundImageAlpha(alpha) }
                                    },
                                    previewLyricFontScale = pendingLyricFontScale,
                                    isDarkTheme = isDarkTheme,
                                    onThemeToggleRequest = ::requestThemeToggle
                                )
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
                                onClick = { stepIndex = (stepIndex - 1).coerceAtLeast(0) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.action_back))
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }

                        HapticButton(
                            onClick = {
                                goNextStep()
                            },
                            enabled = !finishing,
                            modifier = Modifier.weight(1.4f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                text = if (stepIndex == steps.lastIndex) {
                                    stringResource(R.string.onboarding_action_enter_app)
                                } else {
                                    stringResource(R.string.onboarding_action_next)
                                }
                            )
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
                showCookieDialog = showNeteaseCookieDialog,
                cookieText = neteaseCookieText,
                onDismissCookieDialog = { showNeteaseCookieDialog = false },
                onBrowserLogin = null
            )
            SettingsBiliAuthDialogs(
                showSheet = showBiliSheet,
                initialTab = biliSheetTab,
                onDismissSheet = { showBiliSheet = false },
                inlineMsg = inlineMessage,
                onInlineMsgChange = { inlineMessage = it },
                vm = biliVm,
                showCookieDialog = showBiliCookieDialog,
                cookieText = biliCookieText,
                onDismissCookieDialog = { showBiliCookieDialog = false },
                onBrowserLogin = null
            )
            SettingsYouTubeAuthDialogs(
                showSheet = showYouTubeSheet,
                initialTab = youTubeSheetTab,
                onDismissSheet = { showYouTubeSheet = false },
                inlineMsg = inlineMessage,
                onInlineMsgChange = { inlineMessage = it },
                vm = youTubeVm,
                showCookieDialog = showYouTubeCookieDialog,
                cookieText = youTubeCookieText,
                onDismissCookieDialog = { showYouTubeCookieDialog = false }
            )
            SettingsGitHubDialogs(
                showGitHubConfigDialog = showGitHubConfigDialog,
                onShowGitHubConfigDialogChange = { showGitHubConfigDialog = it },
                showClearGitHubConfigDialog = showClearGitHubConfigDialog,
                onShowClearGitHubConfigDialogChange = { showClearGitHubConfigDialog = it }
            )
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
                    onFinished = ::clearThemeRevealState
                )
            }
        }
    }
}

@Composable
private fun StepContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        content = content
    )
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
            shape = RoundedCornerShape(18.dp),
            color = colors.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.onPrimaryContainer)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
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
    HintCard(
        title = stringResource(R.string.onboarding_language_hint_title),
        body = stringResource(R.string.onboarding_language_hint_body)
    )
}

@Composable
private fun PlatformContent(
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit,
    biliState: SavedCookieAuthState,
    neteaseState: SavedCookieAuthState,
    youTubeState: YouTubeAuthState,
    onOpenBili: () -> Unit,
    onLogoutBili: () -> Unit,
    onOpenNetease: () -> Unit,
    onLogoutNetease: () -> Unit,
    onOpenYouTube: () -> Unit,
    onLogoutYouTube: () -> Unit
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
        onClick = if (biliState == SavedCookieAuthState.Valid) onLogoutBili else onOpenBili
    )
    Spacer(Modifier.height(12.dp))
    PlatformCard(
        icon = painterResource(R.drawable.ic_netease_cloud_music),
        title = stringResource(R.string.platform_netease),
        status = statusTextForSavedCookie(neteaseState),
        connected = neteaseState == SavedCookieAuthState.Valid,
        onClick = if (neteaseState == SavedCookieAuthState.Valid) onLogoutNetease else onOpenNetease
    )
    Spacer(Modifier.height(12.dp))
    PlatformCard(
        icon = painterResource(R.drawable.ic_youtube),
        title = stringResource(R.string.common_youtube),
        status = statusTextForYouTube(youTubeState),
        connected = youTubeState == YouTubeAuthState.Valid,
        onClick = if (youTubeState == YouTubeAuthState.Valid) onLogoutYouTube else onOpenYouTube
    )
    Spacer(Modifier.height(18.dp))
    HintCard(body = stringResource(R.string.onboarding_platforms_hint))
}

@Composable
private fun GitHubSyncContent(
    gitHubState: GitHubSyncUiState,
    onDismissGitHubMessage: () -> Unit,
    onOpenGitHubConfig: () -> Unit,
    onOpenClearGitHubConfig: () -> Unit,
    onToggleGitHubAutoSync: (Boolean) -> Unit,
    onGitHubSyncNow: () -> Unit
) {
    StepHeader(
        icon = Icons.Outlined.CloudSync,
        title = stringResource(R.string.common_github),
        description = stringResource(R.string.onboarding_github_desc)
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
    GitHubSyncCard(
        state = gitHubState,
        onOpenConfig = onOpenGitHubConfig,
        onOpenClearConfig = onOpenClearGitHubConfig,
        onToggleAutoSync = onToggleGitHubAutoSync,
        onSyncNow = onGitHubSyncNow
    )
    Spacer(Modifier.height(18.dp))
    HintCard(body = stringResource(R.string.onboarding_github_hint))
}

@Composable
private fun PersonalizeContent(
    pendingUiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    onUiScaleCommit: () -> Unit,
    pendingLyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    onLyricFontScaleCommit: () -> Unit,
    backgroundImageUri: String?,
    backgroundImageBlur: Float,
    backgroundImageAlpha: Float,
    onSelectBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onBackgroundBlurCommit: (Float) -> Unit,
    onBackgroundAlphaCommit: (Float) -> Unit,
    previewLyricFontScale: Float,
    isDarkTheme: Boolean,
    onThemeToggleRequest: (Offset, Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var pendingBackgroundImageBlur by rememberSaveable(backgroundImageUri) {
        mutableFloatStateOf(backgroundImageBlur)
    }
    var pendingBackgroundImageAlpha by rememberSaveable(backgroundImageUri) {
        mutableFloatStateOf(backgroundImageAlpha)
    }

    LaunchedEffect(backgroundImageBlur, backgroundImageUri) {
        if ((pendingBackgroundImageBlur - backgroundImageBlur).absoluteValue > 0.001f) {
            pendingBackgroundImageBlur = backgroundImageBlur
        }
    }
    LaunchedEffect(backgroundImageAlpha, backgroundImageUri) {
        if ((pendingBackgroundImageAlpha - backgroundImageAlpha).absoluteValue > 0.001f) {
            pendingBackgroundImageAlpha = backgroundImageAlpha
        }
    }

    StepHeader(
        icon = Icons.Outlined.Palette,
        title = stringResource(R.string.onboarding_personalize_title),
        description = stringResource(R.string.onboarding_personalize_desc)
    )
    Spacer(Modifier.height(18.dp))
    PreviewCard(
        backgroundImageUri = backgroundImageUri,
        backgroundImageBlur = pendingBackgroundImageBlur,
        backgroundImageAlpha = pendingBackgroundImageAlpha,
        lyricFontScale = previewLyricFontScale
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
        title = stringResource(R.string.lyrics_font_size),
        body = stringResource(R.string.settings_lyrics_font_current, (pendingLyricFontScale * 100).roundToInt())
    ) {
        Slider(
            value = pendingLyricFontScale,
            onValueChange = onLyricFontScaleChange,
            onValueChangeFinished = onLyricFontScaleCommit,
            valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
            steps = 10
        )
        Text(
            text = stringResource(R.string.settings_lyrics_sample),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = scaledLyricFontSize(18f, pendingLyricFontScale).sp
            ),
            color = colors.onSurface
        )
    }
    Spacer(Modifier.height(14.dp))
    HintCard(
        title = stringResource(R.string.background_custom),
        body = stringResource(R.string.onboarding_background_hint)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HapticOutlinedButton(onClick = onSelectBackground, modifier = Modifier.weight(1f)) {
                Text(
                    if (backgroundImageUri == null) {
                        stringResource(R.string.onboarding_background_select)
                    } else {
                        stringResource(R.string.onboarding_background_change)
                    }
                )
            }
            if (backgroundImageUri != null) {
                HapticOutlinedButton(onClick = onClearBackground, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_background_clear))
                }
            }
        }
        if (backgroundImageUri != null) {
            Text(stringResource(R.string.background_blur), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = pendingBackgroundImageBlur,
                onValueChange = { pendingBackgroundImageBlur = it },
                onValueChangeFinished = { onBackgroundBlurCommit(pendingBackgroundImageBlur) },
                valueRange = 0f..25f
            )
            Text(stringResource(R.string.background_opacity), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = pendingBackgroundImageAlpha,
                onValueChange = { pendingBackgroundImageAlpha = it },
                onValueChangeFinished = { onBackgroundAlphaCommit(pendingBackgroundImageAlpha) },
                valueRange = 0.1f..1.0f
            )
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceVariant.copy(alpha = 0.56f),
        tonalElevation = if (selected) 6.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) colors.onSecondaryContainer else colors.onSurface)
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun PlatformCard(icon: Painter, title: String, status: String, connected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = if (connected) colors.secondaryContainer.copy(alpha = 0.72f) else colors.surfaceVariant.copy(alpha = 0.5f)
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
                shape = RoundedCornerShape(18.dp),
                color = colors.surface.copy(alpha = 0.72f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painter = icon, contentDescription = title, tint = colors.onSurface, modifier = Modifier.size(28.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Spacer(Modifier.height(6.dp))
                StatusPill(status, connected)
            }
            OnboardingActionButton(
                text = if (connected) {
                    stringResource(R.string.onboarding_platform_action_logout)
                } else {
                    stringResource(R.string.onboarding_platform_action_connect)
                },
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
        shape = RoundedCornerShape(18.dp),
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
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun PreviewCard(
    backgroundImageUri: String?,
    backgroundImageBlur: Float,
    backgroundImageAlpha: Float,
    lyricFontScale: Float
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val legacyBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0f else backgroundImageBlur
    val previewImageRequest = remember(context, backgroundImageUri, legacyBlur) {
        backgroundImageUri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri.toUri())
                .crossfade(false)
                .transformations(
                    if (legacyBlur > 0f) {
                        listOf(BlurTransformation(context, radius = legacyBlur))
                    } else {
                        emptyList()
                    }
                )
                .build()
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (previewImageRequest != null) {
                AsyncImage(
                    model = previewImageRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .let { base ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && backgroundImageBlur > 0f) {
                                base.blur(backgroundImageBlur.dp)
                            } else {
                                base
                            }
                        },
                    contentScale = ContentScale.Crop
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.surface.copy(alpha = (1f - backgroundImageAlpha).coerceIn(0.18f, 0.72f)))
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    colors.primaryContainer.copy(alpha = 0.9f),
                                    colors.tertiaryContainer.copy(alpha = 0.7f),
                                    colors.surfaceVariant.copy(alpha = 0.82f)
                                )
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Blob(color = colors.primary, size = 10.dp)
                    Blob(color = colors.secondary, size = 10.dp)
                    Blob(color = colors.tertiary, size = 10.dp)
                }
                Surface(shape = RoundedCornerShape(24.dp), color = colors.surface.copy(alpha = 0.82f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_preview_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onSurface
                        )
                        Text(
                            text = stringResource(R.string.onboarding_preview_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_lyrics_sample),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = scaledLyricFontSize(24f, lyricFontScale).sp
                            ),
                            color = colors.onSurface
                        )
                    }
                }
            }
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

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = if (state.isConfigured) {
            colors.secondaryContainer.copy(alpha = 0.72f)
        } else {
            colors.surfaceVariant.copy(alpha = 0.5f)
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
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surface.copy(alpha = 0.72f)
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
                        text = stringResource(R.string.github_auto_sync),
                        style = MaterialTheme.typography.titleMedium,
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
                    shape = RoundedCornerShape(22.dp),
                    color = colors.surface.copy(alpha = 0.72f)
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
                        HapticOutlinedButton(onClick = onSyncNow, shape = RoundedCornerShape(18.dp)) {
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
private fun Blob(modifier: Modifier = Modifier, color: Color, size: Dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
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
                    continuation.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
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
