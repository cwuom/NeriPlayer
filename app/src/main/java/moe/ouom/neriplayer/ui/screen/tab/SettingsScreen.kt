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

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.LanguageSettingItem
import moe.ouom.neriplayer.ui.screen.tab.settings.about.settingsAboutSection
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsBiliAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsNeteaseAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsYouTubeAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ExpandableHeader
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsAudioQualitySection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsBackupRestoreSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsDownloadSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsMotionSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsPlaybackSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsStorageCacheSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeSeedListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.component.UiScaleListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.component.maskCookieValue
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsGitHubDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsPreferenceDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import kotlin.math.absoluteValue
import kotlin.math.roundToInt


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
    homeHasRecentUsage: Boolean,
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    // ------------------------------------

    val neteaseVm: NeteaseAuthViewModel = viewModel()
    var inlineMsg by remember { mutableStateOf<String?>(null) }
    var confirmPhoneMasked by remember { mutableStateOf<String?>(null) }
    var cookieText by remember { mutableStateOf("") }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var biliCookieText by remember { mutableStateOf("") }
    val biliVm: BiliAuthViewModel = viewModel()
    var biliReauthHealth by remember { mutableStateOf<SavedCookieAuthHealth?>(null) }
    var biliSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var neteaseReauthHealth by remember { mutableStateOf<SavedCookieAuthHealth?>(null) }
    var neteaseSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var youtubeCookieText by remember { mutableStateOf("") }
    val youtubeVm: YouTubeAuthViewModel = viewModel()
    var youtubeSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    
    // 备份与恢复
    val backupRestoreVm: BackupRestoreViewModel = viewModel()
    val backupRestoreUiState by backupRestoreVm.uiState.collectAsState()
    val localPlaylistRepo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val localPlaylists by localPlaylistRepo.playlists.collectAsState(initial = emptyList())

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
    val homeStartAvailable =
        showHomeTrendingCard ||
            showHomeRadarCard ||
            showHomeRecommendedCard ||
            (showHomeContinueCard && homeHasRecentUsage)
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
            .background(Color.Transparent),
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        topBar = {
            TopAppBar(
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
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
                LazyAnimatedVisibility(
                    visible = loginExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    SettingsLoginExpandedContent(
                        biliVm = biliVm,
                        youtubeVm = youtubeVm,
                        neteaseVm = neteaseVm,
                        onOpenBiliSheet = {
                            inlineMsg = null
                            biliSheetInitialTab = 0
                            showBiliSheet = true
                        },
                        onOpenYouTubeSheet = {
                            inlineMsg = null
                            youtubeSheetInitialTab = 0
                            showYouTubeSheet = true
                        },
                        onOpenNeteaseSheet = {
                            inlineMsg = null
                            neteaseSheetInitialTab = 0
                            showNeteaseSheet = true
                        }
                    )
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
                  LazyAnimatedVisibility(
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
                  SettingsMotionSection(
                      expanded = motionExpanded,
                      arrowRotation = motionArrowRotation,
                      onExpandedChange = { motionExpanded = it },
                      advancedBlurEnabled = advancedBlurEnabled,
                      onAdvancedBlurEnabledChange = onAdvancedBlurEnabledChange,
                      nowPlayingAudioReactiveEnabled = nowPlayingAudioReactiveEnabled,
                      onNowPlayingAudioReactiveEnabledChange = onNowPlayingAudioReactiveEnabledChange,
                      nowPlayingDynamicBackgroundEnabled = nowPlayingDynamicBackgroundEnabled,
                      onNowPlayingDynamicBackgroundEnabledChange = onNowPlayingDynamicBackgroundEnabledChange,
                      nowPlayingCoverBlurBackgroundEnabled = nowPlayingCoverBlurBackgroundEnabled,
                      onNowPlayingCoverBlurBackgroundEnabledChange = onNowPlayingCoverBlurBackgroundEnabledChange,
                      nowPlayingCoverBlurAmount = nowPlayingCoverBlurAmount,
                      onNowPlayingCoverBlurAmountChange = onNowPlayingCoverBlurAmountChange,
                      nowPlayingCoverBlurDarken = nowPlayingCoverBlurDarken,
                      onNowPlayingCoverBlurDarkenChange = onNowPlayingCoverBlurDarkenChange,
                      lyricBlurEnabled = lyricBlurEnabled,
                      onLyricBlurEnabledChange = onLyricBlurEnabledChange,
                      lyricBlurAmount = lyricBlurAmount,
                      onLyricBlurAmountChange = onLyricBlurAmountChange
                  )
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
                LazyAnimatedVisibility(
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
                SettingsPlaybackSection(
                    expanded = playbackExpanded,
                    arrowRotation = playbackArrowRotation,
                    onExpandedChange = { playbackExpanded = it },
                    playbackFadeIn = playbackFadeIn,
                    onPlaybackFadeInChange = onPlaybackFadeInChange,
                    playbackCrossfadeNext = playbackCrossfadeNext,
                    onPlaybackCrossfadeNextChange = onPlaybackCrossfadeNextChange,
                    playbackFadeInDurationMs = playbackFadeInDurationMs,
                    onPlaybackFadeInDurationMsChange = onPlaybackFadeInDurationMsChange,
                    playbackFadeOutDurationMs = playbackFadeOutDurationMs,
                    onPlaybackFadeOutDurationMsChange = onPlaybackFadeOutDurationMsChange,
                    playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
                    onPlaybackCrossfadeInDurationMsChange = onPlaybackCrossfadeInDurationMsChange,
                    playbackCrossfadeOutDurationMs = playbackCrossfadeOutDurationMs,
                    onPlaybackCrossfadeOutDurationMsChange = onPlaybackCrossfadeOutDurationMsChange,
                    keepLastPlaybackProgress = keepLastPlaybackProgress,
                    onKeepLastPlaybackProgressChange = onKeepLastPlaybackProgressChange,
                    keepPlaybackModeState = keepPlaybackModeState,
                    onKeepPlaybackModeStateChange = onKeepPlaybackModeStateChange,
                    stopOnBluetoothDisconnect = stopOnBluetoothDisconnect,
                    onStopOnBluetoothDisconnectChange = onStopOnBluetoothDisconnectChange,
                    allowMixedPlayback = allowMixedPlayback,
                    onAllowMixedPlaybackChange = onAllowMixedPlaybackChange
                )
            }


            item {
                SettingsAudioQualitySection(
                    expanded = audioQualityExpanded,
                    arrowRotation = audioQualityArrowRotation,
                    onExpandedChange = { audioQualityExpanded = it },
                    qualityLabel = qualityLabel,
                    preferredQuality = preferredQuality,
                    onQualityChange = onQualityChange,
                    youtubeQualityLabel = youtubeQualityLabel,
                    youtubePreferredQuality = youtubePreferredQuality,
                    onYouTubeQualityChange = onYouTubeQualityChange,
                    biliQualityLabel = biliQualityLabel,
                    biliPreferredQuality = biliPreferredQuality,
                    onBiliQualityChange = onBiliQualityChange,
                    showQualityDialog = showQualityDialog,
                    onShowQualityDialogChange = { showQualityDialog = it },
                    showYouTubeQualityDialog = showYouTubeQualityDialog,
                    onShowYouTubeQualityDialogChange = { showYouTubeQualityDialog = it },
                    showBiliQualityDialog = showBiliQualityDialog,
                    onShowBiliQualityDialogChange = { showBiliQualityDialog = it }
                )
            }

            item {
                SettingsStorageCacheSection(
                    expanded = cacheExpanded,
                    arrowRotation = cacheArrowRotation,
                    onExpandedChange = { cacheExpanded = it },
                    maxCacheSizeBytes = maxCacheSizeBytes,
                    onMaxCacheSizeBytesChange = onMaxCacheSizeBytesChange,
                    showStorageDetails = showStorageDetails,
                    onShowStorageDetailsChange = { showStorageDetails = it },
                    storageDetails = storageDetails,
                    onStorageDetailsChange = { storageDetails = it },
                    showClearCacheDialog = showClearCacheDialog,
                    onShowClearCacheDialogChange = { showClearCacheDialog = it },
                    clearAudioCache = clearAudioCache,
                    onClearAudioCacheChange = { clearAudioCache = it },
                    clearImageCache = clearImageCache,
                    onClearImageCacheChange = { clearImageCache = it },
                    onClearCacheClick = onClearCacheClick
                )
            }

            item {
                SettingsDownloadSection(
                    expanded = downloadManagerExpanded,
                    arrowRotation = downloadManagerArrowRotation,
                    onExpandedChange = { downloadManagerExpanded = it },
                    onNavigateToDownloadManager = onNavigateToDownloadManager
                )
            }

            item {
                SettingsBackupRestoreSection(
                    expanded = backupRestoreExpanded,
                    arrowRotation = backupRestoreArrowRotation,
                    onExpandedChange = { backupRestoreExpanded = it },
                    currentPlaylistCount = localPlaylists.size,
                    backupRestoreUiState = backupRestoreUiState,
                    onExportClick = {
                        if (!backupRestoreUiState.isExporting) {
                            exportPlaylistLauncher.launch(backupRestoreVm.generateBackupFileName())
                        }
                    },
                    onImportClick = {
                        if (!backupRestoreUiState.isImporting) {
                            importPlaylistLauncher.launch(arrayOf("*/*"))
                        }
                    },
                    onClearExportStatus = backupRestoreVm::clearExportStatus,
                    onClearImportStatus = backupRestoreVm::clearImportStatus,
                    silentGitHubSyncFailure = silentGitHubSyncFailure,
                    onSilentGitHubSyncFailureChange = onSilentGitHubSyncFailureChange,
                    onOpenGitHubConfig = { showGitHubConfigDialog = true },
                    onOpenClearGitHubConfig = { showClearGitHubConfigDialog = true }
                )
            }
            settingsAboutSection(
                devModeEnabled = devModeEnabled,
                onVersionClick = {
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
                onOpenGitHubRepo = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/cwuom/NeriPlayer".toUri()
                    )
                    context.startActivity(intent)
                }
            )
        }
    }

    SettingsNeteaseAuthDialogs(
        showSheet = showNeteaseSheet,
        initialTab = neteaseSheetInitialTab,
        onDismissSheet = { showNeteaseSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        showConfirmDialog = showConfirmDialog,
        confirmPhoneMasked = confirmPhoneMasked,
        onDismissConfirmDialog = { showConfirmDialog = false },
        vm = neteaseVm,
        showCookieDialog = showCookieDialog,
        cookieText = cookieText,
        onDismissCookieDialog = { showCookieDialog = false },
        showReauthDialog = showNeteaseReauthDialog,
        reauthHealth = neteaseReauthHealth,
        onDismissReauthDialog = {
            showNeteaseReauthDialog = false
            neteaseReauthHealth = null
        },
        onOpenSheetAtTab = { tab ->
            neteaseSheetInitialTab = tab
            showNeteaseSheet = true
        }
    )

    SettingsBiliAuthDialogs(
        showSheet = showBiliSheet,
        initialTab = biliSheetInitialTab,
        onDismissSheet = { showBiliSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        vm = biliVm,
        showCookieDialog = showBiliCookieDialog,
        cookieText = biliCookieText,
        onDismissCookieDialog = { showBiliCookieDialog = false },
        showReauthDialog = showBiliReauthDialog,
        reauthHealth = biliReauthHealth,
        onDismissReauthDialog = {
            showBiliReauthDialog = false
            biliReauthHealth = null
        },
        onOpenSheetAtTab = { tab ->
            biliSheetInitialTab = tab
            showBiliSheet = true
        }
    )

    SettingsYouTubeAuthDialogs(
        showSheet = showYouTubeSheet,
        initialTab = youtubeSheetInitialTab,
        onDismissSheet = { showYouTubeSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        vm = youtubeVm,
        showCookieDialog = showYouTubeCookieDialog,
        cookieText = youtubeCookieText,
        onDismissCookieDialog = { showYouTubeCookieDialog = false }
    )
    SettingsPreferenceDialogs(
        showDefaultStartDestinationDialog = showDefaultStartDestinationDialog,
        onShowDefaultStartDestinationDialogChange = { showDefaultStartDestinationDialog = it },
        homeStartAvailable = homeStartAvailable,
        effectiveDefaultStartDestination = effectiveDefaultStartDestination,
        onDefaultStartDestinationChange = onDefaultStartDestinationChange,
        showColorPickerDialog = showColorPickerDialog,
        onShowColorPickerDialogChange = { showColorPickerDialog = it },
        seedColorHex = seedColorHex,
        themeColorPalette = themeColorPalette,
        onSeedColorChange = onSeedColorChange,
        onAddColorToPalette = onAddColorToPalette,
        onRemoveColorFromPalette = onRemoveColorFromPalette,
        showDpiDialog = showDpiDialog,
        onShowDpiDialogChange = { showDpiDialog = it },
        uiDensityScale = uiDensityScale,
        onUiDensityScaleChange = onUiDensityScaleChange
    )

    SettingsGitHubDialogs(
        showGitHubConfigDialog = showGitHubConfigDialog,
        onShowGitHubConfigDialogChange = { showGitHubConfigDialog = it },
        showClearGitHubConfigDialog = showClearGitHubConfigDialog,
        onShowClearGitHubConfigDialogChange = { showClearGitHubConfigDialog = it }
    )

}

@Composable
private fun SettingsLoginExpandedContent(
    biliVm: BiliAuthViewModel,
    youtubeVm: YouTubeAuthViewModel,
    neteaseVm: NeteaseAuthViewModel,
    onOpenBiliSheet: () -> Unit,
    onOpenYouTubeSheet: () -> Unit,
    onOpenNeteaseSheet: () -> Unit,
) {
    val biliAuthUiState by biliVm.uiState.collectAsStateWithLifecycleCompat()
    val youtubeAuthUiState by youtubeVm.uiState.collectAsStateWithLifecycleCompat()
    val neteaseAuthUiState by neteaseVm.uiState.collectAsStateWithLifecycleCompat()

    LaunchedEffect(biliVm, youtubeVm, neteaseVm) {
        biliVm.refreshAuthHealth(promptIfNeeded = true)
        neteaseVm.refreshAuthHealth(promptIfNeeded = true)
        youtubeVm.refreshAuthHealth()
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
            modifier = Modifier.settingsItemClickable(onClick = onOpenBiliSheet),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

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
            modifier = Modifier.settingsItemClickable(onClick = onOpenYouTubeSheet),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

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
            modifier = Modifier.settingsItemClickable(onClick = onOpenNeteaseSheet),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

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

