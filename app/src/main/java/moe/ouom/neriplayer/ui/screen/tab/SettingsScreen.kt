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

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.settings.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.LyricFontScales
import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchItems
import moe.ouom.neriplayer.data.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.ui.component.settings.LanguageSettingItem
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.PlaybackServiceIdleShutdownSetting
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsAudioQualitySection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsLyricsSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsMotionSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsPlaybackSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsTrafficManagementSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeSeedListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.component.UsbExclusiveSettingsSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.YouTubePlaybackSourceSetting
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsGitHubDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsPreferenceDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsWebDavDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHeader
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHomeScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsResponsiveDetailScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.miuixSettingsSectionCardItem
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.feedback.AppFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@NonRestartableComposable
@Suppress("AssignedValueIsNeverRead")
fun SettingsScreen(
    listState: LazyListState,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    themeMode: ThemeMode,
    onThemeToggleRequest: (Offset, Float) -> Unit,
    onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit,
    preferredQuality: String,
    onQualityChange: (String) -> Unit,
    youtubePreferredQuality: String,
    onYouTubeQualityChange: (String) -> Unit,
    biliPreferredQuality: String,
    onBiliQualityChange: (String) -> Unit,
    mobileDataFollowDefaultAudioQuality: Boolean,
    onMobileDataFollowDefaultAudioQualityChange: (Boolean) -> Unit,
    mobileDataNeteaseAudioQuality: String,
    onMobileDataNeteaseAudioQualityChange: (String) -> Unit,
    mobileDataYouTubeAudioQuality: String,
    onMobileDataYouTubeAudioQualityChange: (String) -> Unit,
    mobileDataBiliAudioQuality: String,
    onMobileDataBiliAudioQualityChange: (String) -> Unit,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    seedColorHex: String,
    onSeedColorChange: (String) -> Unit,
    themeColorPalette: List<String>,
    onAddColorToPalette: (String) -> Unit,
    onRemoveColorFromPalette: (String) -> Unit,
    themePaletteStyle: String,
    onThemePaletteStyleChange: (String) -> Unit,
    themeColorSpec: String,
    onThemeColorSpecChange: (String) -> Unit,
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
    lyricBlurAmount: Float,
    onLyricBlurAmountChange: (Float) -> Unit,
    cloudMusicLyricDefaultOffsetMs: Long,
    onCloudMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    qqMusicLyricDefaultOffsetMs: Long,
    onQqMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    kugouLyricDefaultOffsetMs: Long,
    onKugouLyricDefaultOffsetMsChange: (Long) -> Unit,
    lrclibLyricDefaultOffsetMs: Long,
    onLrclibLyricDefaultOffsetMsChange: (Long) -> Unit,
    amllTtmlLyricDefaultOffsetMs: Long,
    onAmllTtmlLyricDefaultOffsetMsChange: (Long) -> Unit,
    onResetAllLyricDefaultOffsets: () -> Unit,
    floatingLyricsPreferences: FloatingLyricsPreferences,
    onFloatingLyricsPreferencesChange: (FloatingLyricsPreferences) -> Unit,
    advancedBlurEnabled: Boolean,
    onAdvancedBlurEnabledChange: (Boolean) -> Unit,
    enhancedAdvancedBlurEnabled: Boolean,
    onEnhancedAdvancedBlurEnabledChange: (Boolean) -> Unit,
    enhancedAdvancedBlurRadiusDp: Float,
    onEnhancedAdvancedBlurRadiusDpChange: (Float) -> Unit,
    advancedBlurQuality: AdvancedBlurQuality,
    onAdvancedBlurQualityChange: (AdvancedBlurQuality) -> Unit,
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
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    uiDensityScale: Float,
    onUiDensityScaleChange: (Float) -> Unit,
    bypassProxy: Boolean,
    onBypassProxyChange: (Boolean) -> Unit,
    backgroundImageUri: String?,
    onBackgroundImageChange: (Uri?) -> Unit,
    downloadDirectoryUri: String?,
    downloadFileNameTemplate: String?,
    onDownloadDirectoryUriChange: (String?, String?) -> Unit,
    onDownloadFileNameTemplateChange: (String?) -> Unit,
    backgroundImageBlur: Float,
    onBackgroundImageBlurChange: (Float) -> Unit,
    onBackgroundImageBlurChangeFinished: (Float) -> Unit,
    backgroundImageAlpha: Float,
    onBackgroundImageAlphaChange: (Float) -> Unit,
    onBackgroundImageAlphaChangeFinished: (Float) -> Unit,
    defaultStartDestination: String,
    onDefaultStartDestinationChange: (String) -> Unit,
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
    sleepTimerFinishCurrentOnExpiry: Boolean,
    onSleepTimerFinishCurrentOnExpiryChange: (Boolean) -> Unit,
    playbackFadeInDurationMs: Long,
    onPlaybackFadeInDurationMsChange: (Long) -> Unit,
    playbackFadeOutDurationMs: Long,
    onPlaybackFadeOutDurationMsChange: (Long) -> Unit,
    playbackCrossfadeInDurationMs: Long,
    onPlaybackCrossfadeInDurationMsChange: (Long) -> Unit,
    playbackCrossfadeOutDurationMs: Long,
    onPlaybackCrossfadeOutDurationMsChange: (Long) -> Unit,
    playbackVolumeNormalizationEnabled: Boolean,
    onPlaybackVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    playbackHighResolutionOutputEnabled: Boolean,
    onPlaybackHighResolutionOutputEnabledChange: (Boolean) -> Unit,
    playbackVolumeBalance: Float,
    onPlaybackVolumeBalanceChange: (Float) -> Unit,
    keepLastPlaybackProgress: Boolean,
    onKeepLastPlaybackProgressChange: (Boolean) -> Unit,
    rememberLongFormPlaybackProgress: Boolean,
    onRememberLongFormPlaybackProgressChange: (Boolean) -> Unit,
    keepPlaybackModeState: Boolean,
    onKeepPlaybackModeStateChange: (Boolean) -> Unit,
    neteaseAutoSourceSwitch: Boolean,
    onNeteaseAutoSourceSwitchChange: (Boolean) -> Unit,
    neteaseLocalSourceFallback: Boolean,
    onNeteaseLocalSourceFallbackChange: (Boolean) -> Unit,
    stopOnBluetoothDisconnect: Boolean,
    onStopOnBluetoothDisconnectChange: (Boolean) -> Unit,
    usbExclusivePlayback: Boolean,
    onUsbExclusivePlaybackChange: (Boolean) -> Unit,
    allowMixedPlayback: Boolean,
    onAllowMixedPlaybackChange: (Boolean) -> Unit,
    preemptAudioFocus: Boolean,
    onPreemptAudioFocusChange: (Boolean) -> Unit,
    onNavigateToDownloadManager: () -> Unit,
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onClearCacheClick: (StorageCacheClearOptions) -> Unit,
    onBeforeLanguageRestart: () -> Unit,
    onLanguageChanged: (LanguageManager.Language) -> Unit,
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val scope = rememberCoroutineScope()
    val autoSettingsRepository = remember(context) { AutoSettingsRepository(context) }
    val listenTogetherPreferences = AppContainer.listenTogetherPreferences
    val listenTogetherApi = AppContainer.listenTogetherApi
    val listenTogetherSessionManager = AppContainer.listenTogetherSessionManager
    val listenTogetherSettings = rememberSettingsListenTogetherController(
        preferences = listenTogetherPreferences,
        api = listenTogetherApi,
        sessionManager = listenTogetherSessionManager,
        onMessage = { AppFeedback.showToast(context = context, message = it) }
    )
    val backgroundImageDraft = rememberSettingsBackgroundImageDraft(
        imageUri = backgroundImageUri,
        committedBlur = backgroundImageBlur,
        committedAlpha = backgroundImageAlpha
    )

    val internationalEnabled by AppContainer.settingsRepo.internationalizationEnabledFlow
        .collectAsState(initial = false)
    val usbExclusivePreferences by AppContainer.settingsRepo.usbExclusivePreferencesFlow
        .collectAsState(initial = UsbExclusivePreferences())

    EnforceNowPlayingBackgroundExclusion(
        NowPlayingBackgroundExclusionPort(
            coverBlurEnabled = nowPlayingCoverBlurBackgroundEnabled,
            dynamicEnabled = nowPlayingDynamicBackgroundEnabled,
            reactiveEnabled = nowPlayingAudioReactiveEnabled,
            onDynamicChange = onNowPlayingDynamicBackgroundEnabledChange,
            onReactiveChange = onNowPlayingAudioReactiveEnabledChange
        )
    )

    val storageSelection = rememberSettingsStorageSelectionState()

    // 各种对话框和弹窗的显示状态 //
    var showQualityDialog by remember { mutableStateOf(false) }
    var showYouTubeQualityDialog by remember { mutableStateOf(false) }
    var showBiliQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataNeteaseQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataYouTubeQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataBiliQualityDialog by remember { mutableStateOf(false) }
    var showDefaultStartDestinationDialog by remember { mutableStateOf(false) }

    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
    var showGitHubConfigDialog by remember { mutableStateOf(false) }
    var showClearGitHubConfigDialog by remember { mutableStateOf(false) }
    var showWebDavConfigDialog by remember { mutableStateOf(false) }
    var showClearWebDavConfigDialog by remember { mutableStateOf(false) }
    // ------------------------------------

    var inlineMsg by remember { mutableStateOf<String?>(null) }
    val accountAuth = rememberSettingsAccountAuthController { inlineMsg = it }
    
    val backupTransfer = rememberSettingsBackupTransferController(onBeforeLanguageRestart)

    fun showSettingsMessage(message: String) {
        AppFeedback.show(context = context, message = message)
    }

    val downloadDirectorySettings = rememberDownloadDirectorySettingsController(
        downloadDirectoryUri = downloadDirectoryUri,
        onDownloadDirectoryUriChange = onDownloadDirectoryUriChange,
        onInlineMessageChange = { inlineMsg = it },
        onShowMessage = ::showSettingsMessage
    )

    val pickBackgroundImage = rememberSettingsBackgroundImagePicker(
        BackgroundImagePickerPort(backgroundImageUri, onBackgroundImageChange)
    )

    val qualityPresentation = rememberSettingsQualityPresentation(
        context = context,
        neteaseValue = preferredQuality,
        youtubeValue = youtubePreferredQuality,
        biliValue = biliPreferredQuality,
        mobileNeteaseValue = mobileDataNeteaseAudioQuality,
        mobileYouTubeValue = mobileDataYouTubeAudioQuality,
        mobileBiliValue = mobileDataBiliAudioQuality
    )

    val homeStartPresentation = rememberSettingsHomeStartPresentation(
        resources = composeResources,
        configuredDestination = defaultStartDestination,
        internationalEnabled = internationalEnabled,
        showTrending = showHomeTrendingCard,
        showRadar = showHomeRadarCard,
        showRecommended = showHomeRecommendedCard,
        showContinue = showHomeContinueCard,
        hasRecentUsage = homeHasRecentUsage
    )
    val homeStartAvailable = homeStartPresentation.available
    val homeCardCopy = homeStartPresentation.copy
    val effectiveDefaultStartDestination = homeStartPresentation.destination
    val defaultStartDestinationLabel = homeStartPresentation.destinationLabel
    val navigation = rememberSettingsNavigationState(
        context = context,
        listState = listState,
        dynamicColor = dynamicColor,
        mobileDataFollowDefaultAudioQuality = mobileDataFollowDefaultAudioQuality,
        backgroundImageUri = backgroundImageUri
    )
    val isSettingsSplitLayout = navigation.splitLayout
    val activeSettingsPage = navigation.activePage
    val storageDetailsController = rememberSettingsStorageDetailsController(context)
    LaunchedEffect(activeSettingsPage) {
        storageDetailsController.requestIfDetailsPage(activeSettingsPage)
    }
    ObserveSettingsBackupPlaylistCount(activeSettingsPage, context, backupTransfer)
    val homeTopAppBarState = navigation.homeTopAppBarState
    val detailTopAppBarStates = navigation.detailTopAppBarStates
    val detailListStates = navigation.detailListStates
    val settingsHighlightTargetId by navigation.highlightTargetState
    val settingsHighlightPulse by navigation.highlightPulseState
    val onSettingsHighlightFinished: () -> Unit = navigation::clearHighlight

    fun navigateBackFromActiveSettingsPage() = navigation.navigateBack()

    val showSplitDetailBackButton = navigation.showSplitDetailBackButton
    val isolateAdvancedGlassTransitions = LocalAdvancedGlassController.current.isEnabled
    val settingsHomeTitle: @Composable () -> Unit = {
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
    }
    val settingsHomeContent: LazyListScope.() -> Unit = {
        settingsHomePageItems(navigation)
    }

    val settingsPageContent: @Composable (SettingsPage?) -> Unit = { requestedPage ->
        SettingsPageScaffold(
            page = requestedPage,
            home = {
            MiuixSettingsHomeScaffold(
                listState = listState,
                topAppBarState = homeTopAppBarState,
                title = settingsHomeTitle,
                content = settingsHomeContent
            )
            },
            detail = { selectedPage ->
            MiuixSettingsResponsiveDetailScaffold(
                title = stringResource(selectedPage.titleRes),
                onBack = ::navigateBackFromActiveSettingsPage,
                listState = detailListStates.getValue(selectedPage),
                topAppBarState = detailTopAppBarStates.getValue(selectedPage),
                splitLayout = isSettingsSplitLayout,
                showSplitDetailBackButton = showSplitDetailBackButton,
                selectedPage = selectedPage,
                homeListState = listState,
                homeTopAppBarState = homeTopAppBarState,
                homeTitle = settingsHomeTitle,
                homeContent = settingsHomeContent
            ) {
                item(key = "${selectedPage.name}:header") {
                    MiuixSettingsHeader(
                        icon = selectedPage.icon,
                        title = stringResource(selectedPage.titleRes),
                        description = stringResource(selectedPage.descriptionRes),
                        modifier = Modifier
                            .animateItem()
                            .settingsHighlightTarget(
                                targetId = "page:${selectedPage.name}",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                    )
                }

                settingsStorageProcessingItem(selectedPage, downloadDirectorySettings)

                val pageItems = mapOf<SettingsPage, LazyListScope.() -> Unit>(
                SettingsPage.General to {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        AutoSettingsSwitchItems(
                            repository = autoSettingsRepository,
                            scope = scope,
                            sectionScope = AutoSettingsScopes.general,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                        PlaybackServiceIdleShutdownSetting(
                            repository = autoSettingsRepository,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                        LanguageSettingItem(
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:language",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            ),
                            onLanguageChanged = onLanguageChanged
                        )
                        ListItem(
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:internationalization",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            ),
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
                                    stringResource(R.string.settings_internationalization_desc)
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = internationalEnabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            AppContainer.settingsRepo.setInternationalizationEnabled(enabled)
                                        }
                                    }
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_ui_scale),
                            description = stringResource(R.string.settings_ui_scale_global_desc)
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.UI_DENSITY_SCALE),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.ZoomInMap,
                                    contentDescription = stringResource(R.string.settings_ui_scale),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.settings_ui_scale_current,
                                        "%.2f".format(uiDensityScale)
                                    )
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { showDpiDialog = true }
                        )
                    }
                },

                SettingsPage.Theme to {
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:mode") {
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_theme_mode),
                            description = stringResource(R.string.settings_theme_mode_desc)
                        )
                        ThemeModeSelectorListItem(
                            isDarkTheme = isDarkTheme,
                            themeMode = themeMode,
                            onThemeModeRequest = onThemeModeRequest,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_mode",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                        ThemeAutoModeListItem(
                            themeMode = themeMode,
                            isDarkTheme = isDarkTheme,
                            onThemeModeRequest = onThemeModeRequest
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:dynamic_color") {
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_theme_color_section),
                            description = stringResource(R.string.settings_theme_color_section_desc)
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DYNAMIC_COLOR),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Colorize,
                                    contentDescription = stringResource(R.string.settings_dynamic_color),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { onDynamicColorChange(!dynamicColor) }
                        )
                        LazyAnimatedVisibility(visible = !dynamicColor) {
                            ThemeSeedListItem(
                                seedColorHex = seedColorHex,
                                onClick = { showColorPickerDialog = true },
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_theme_palette_hint),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:palette_style") {
                        ThemePaletteStyleSelector(
                            selectedStyle = themePaletteStyle,
                            onStyleChange = onThemePaletteStyleChange,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_palette_style",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:color_spec") {
                        ThemeColorSpecSelector(
                            selectedSpec = themeColorSpec,
                            onSpecChange = onThemeColorSpecChange,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_color_spec",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                    }
                },

                SettingsPage.Accounts to {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsLoginExpandedContent(accountAuth)
                    }
                },

                SettingsPage.Personalization to {
                    item(key = "${selectedPage.name}:card:0") {
                        SettingsPersonalizationStartCard(
                            autoSettingsRepository = autoSettingsRepository,
                            scope = scope,
                            defaultStartDestinationLabel = defaultStartDestinationLabel,
                            onOpenDefaultStartDestination = {
                                showDefaultStartDestinationDialog = true
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                    item(key = "${selectedPage.name}:card:1") {
                        SettingsPersonalizationHomeCard(
                            internationalEnabled = internationalEnabled,
                            homeTrendingLabelRes = homeCardCopy.trendingLabelRes,
                            homeRadarLabelRes = homeCardCopy.radarLabelRes,
                            homeRecommendedLabelRes = homeCardCopy.recommendedLabelRes,
                            homeTrendingSupportingRes = homeCardCopy.trendingSupportingRes,
                            homeRadarSupportingRes = homeCardCopy.radarSupportingRes,
                            homeRecommendedSupportingRes = homeCardCopy.recommendedSupportingRes,
                            homeStartAvailable = homeStartAvailable,
                            showHomeContinueCard = showHomeContinueCard,
                            onShowHomeContinueCardChange = onShowHomeContinueCardChange,
                            showHomeTrendingCard = showHomeTrendingCard,
                            onShowHomeTrendingCardChange = onShowHomeTrendingCardChange,
                            showHomeRadarCard = showHomeRadarCard,
                            onShowHomeRadarCardChange = onShowHomeRadarCardChange,
                            showHomeRecommendedCard = showHomeRecommendedCard,
                            onShowHomeRecommendedCardChange = onShowHomeRecommendedCardChange,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                    item(key = "${selectedPage.name}:card:2") {
                        SettingsPersonalizationPlaybackInfoCard(
                            autoSettingsRepository = autoSettingsRepository,
                            scope = scope,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                    item(key = "${selectedPage.name}:card:3") {
                        SettingsPersonalizationControlsCard(
                            autoSettingsRepository = autoSettingsRepository,
                            settingsRepository = AppContainer.settingsRepo,
                            scope = scope,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                    item(key = "${selectedPage.name}:card:4") {
                        SettingsPersonalizationBackgroundCard(
                            backgroundImageUri = backgroundImageUri,
                            onPickBackgroundImage = {
                                pickBackgroundImage()
                            },
                            onClearBackgroundImage = {
                                scope.launch {
                                    BackgroundImageStorage.deleteManagedBackground(
                                        context = context,
                                        uriString = backgroundImageUri
                                    )
                                    onBackgroundImageChange(null)
                                }
                            },
                            pendingBackgroundImageBlur = backgroundImageDraft.blur,
                            onPendingBackgroundImageBlurChange = {
                                backgroundImageDraft.blur = it
                            },
                            onBackgroundImageBlurCommit = {
                                onBackgroundImageBlurChange(backgroundImageDraft.blur)
                                onBackgroundImageBlurChangeFinished(backgroundImageDraft.blur)
                            },
                            pendingBackgroundImageAlpha = backgroundImageDraft.alpha,
                            onPendingBackgroundImageAlphaChange = {
                                backgroundImageDraft.alpha = it
                                onBackgroundImageAlphaChange(it)
                            },
                            onBackgroundImageAlphaCommit = {
                                onBackgroundImageAlphaChangeFinished(backgroundImageDraft.alpha)
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                },

                SettingsPage.Motion to {
                    for (cardIndex in 0..3) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsMotionSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                advancedBlurEnabled = advancedBlurEnabled,
                                onAdvancedBlurEnabledChange = onAdvancedBlurEnabledChange,
                                enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
                                onEnhancedAdvancedBlurEnabledChange =
                                    onEnhancedAdvancedBlurEnabledChange,
                                enhancedAdvancedBlurRadiusDp = enhancedAdvancedBlurRadiusDp,
                                onEnhancedAdvancedBlurRadiusDpChange =
                                    onEnhancedAdvancedBlurRadiusDpChange,
                                advancedBlurQuality = advancedBlurQuality,
                                onAdvancedBlurQualityChange = onAdvancedBlurQualityChange,
                                nowPlayingAudioReactiveEnabled = nowPlayingAudioReactiveEnabled,
                                onNowPlayingAudioReactiveEnabledChange =
                                    onNowPlayingAudioReactiveEnabledChange,
                                nowPlayingDynamicBackgroundEnabled =
                                    nowPlayingDynamicBackgroundEnabled,
                                onNowPlayingDynamicBackgroundEnabledChange =
                                    onNowPlayingDynamicBackgroundEnabledChange,
                                nowPlayingCoverBlurBackgroundEnabled =
                                    nowPlayingCoverBlurBackgroundEnabled,
                                onNowPlayingCoverBlurBackgroundEnabledChange =
                                    onNowPlayingCoverBlurBackgroundEnabledChange,
                                nowPlayingCoverBlurAmount = nowPlayingCoverBlurAmount,
                                onNowPlayingCoverBlurAmountChange = onNowPlayingCoverBlurAmountChange,
                                nowPlayingCoverBlurDarken = nowPlayingCoverBlurDarken,
                                onNowPlayingCoverBlurDarkenChange = onNowPlayingCoverBlurDarkenChange,
                                lyricBlurEnabled = lyricBlurEnabled,
                                onLyricBlurEnabledChange = onLyricBlurEnabledChange,
                                lyricBlurAmount = lyricBlurAmount,
                                onLyricBlurAmountChange = onLyricBlurAmountChange,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                },

                SettingsPage.Lyrics to {
                    for (cardIndex in 0..4) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsLyricsSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                settingsRepository = AppContainer.settingsRepo,
                                scope = scope,
                                floatingLyricsPreferences = floatingLyricsPreferences,
                                onFloatingLyricsPreferencesChange = onFloatingLyricsPreferencesChange,
                                lyricsAppearanceContent = {
                                    SettingsLyricsAppearanceContent(
                                        autoSettingsRepository = autoSettingsRepository,
                                        scope = scope,
                                        lyricFontScales = lyricFontScales,
                                        onLyricFontScaleChange = onLyricFontScaleChange,
                                        highlightTargetId = settingsHighlightTargetId,
                                        highlightPulse = settingsHighlightPulse,
                                        onHighlightFinished = onSettingsHighlightFinished
                                    )
                                },
                                cloudMusicLyricDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
                                onCloudMusicLyricDefaultOffsetMsChange =
                                    onCloudMusicLyricDefaultOffsetMsChange,
                                qqMusicLyricDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
                                onQqMusicLyricDefaultOffsetMsChange =
                                    onQqMusicLyricDefaultOffsetMsChange,
                                kugouLyricDefaultOffsetMs = kugouLyricDefaultOffsetMs,
                                onKugouLyricDefaultOffsetMsChange =
                                    onKugouLyricDefaultOffsetMsChange,
                                lrclibLyricDefaultOffsetMs = lrclibLyricDefaultOffsetMs,
                                onLrclibLyricDefaultOffsetMsChange =
                                    onLrclibLyricDefaultOffsetMsChange,
                                amllTtmlLyricDefaultOffsetMs = amllTtmlLyricDefaultOffsetMs,
                                onAmllTtmlLyricDefaultOffsetMsChange =
                                    onAmllTtmlLyricDefaultOffsetMsChange,
                                onResetAllLyricDefaultOffsets = onResetAllLyricDefaultOffsets,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                },

                SettingsPage.Network to {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BYPASS_PROXY),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.AltRoute,
                                    contentDescription = stringResource(R.string.settings_bypass_proxy),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(checked = bypassProxy, onCheckedChange = onBypassProxyChange)
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { onBypassProxyChange(!bypassProxy) }
                        )
                    }
                },

                SettingsPage.Playback to {
                    for (cardIndex in 0..3) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsPlaybackSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                playbackFadeIn = playbackFadeIn,
                                onPlaybackFadeInChange = onPlaybackFadeInChange,
                                playbackCrossfadeNext = playbackCrossfadeNext,
                                onPlaybackCrossfadeNextChange = onPlaybackCrossfadeNextChange,
                                sleepTimerFinishCurrentOnExpiry = sleepTimerFinishCurrentOnExpiry,
                                onSleepTimerFinishCurrentOnExpiryChange =
                                    onSleepTimerFinishCurrentOnExpiryChange,
                                playbackFadeInDurationMs = playbackFadeInDurationMs,
                                onPlaybackFadeInDurationMsChange = onPlaybackFadeInDurationMsChange,
                                playbackFadeOutDurationMs = playbackFadeOutDurationMs,
                                onPlaybackFadeOutDurationMsChange = onPlaybackFadeOutDurationMsChange,
                                playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
                                onPlaybackCrossfadeInDurationMsChange =
                                    onPlaybackCrossfadeInDurationMsChange,
                                playbackCrossfadeOutDurationMs = playbackCrossfadeOutDurationMs,
                                onPlaybackCrossfadeOutDurationMsChange =
                                    onPlaybackCrossfadeOutDurationMsChange,
                                playbackVolumeNormalizationEnabled = playbackVolumeNormalizationEnabled,
                                onPlaybackVolumeNormalizationEnabledChange =
                                    onPlaybackVolumeNormalizationEnabledChange,
                                playbackHighResolutionOutputEnabled =
                                    playbackHighResolutionOutputEnabled,
                                onPlaybackHighResolutionOutputEnabledChange =
                                    onPlaybackHighResolutionOutputEnabledChange,
                                playbackVolumeBalance = playbackVolumeBalance,
                                onPlaybackVolumeBalanceChange = onPlaybackVolumeBalanceChange,
                                keepLastPlaybackProgress = keepLastPlaybackProgress,
                                onKeepLastPlaybackProgressChange = onKeepLastPlaybackProgressChange,
                                rememberLongFormPlaybackProgress = rememberLongFormPlaybackProgress,
                                onRememberLongFormPlaybackProgressChange =
                                    onRememberLongFormPlaybackProgressChange,
                                keepPlaybackModeState = keepPlaybackModeState,
                                onKeepPlaybackModeStateChange = onKeepPlaybackModeStateChange,
                                stopOnBluetoothDisconnect = stopOnBluetoothDisconnect,
                                onStopOnBluetoothDisconnectChange = onStopOnBluetoothDisconnectChange,
                                usbExclusivePlayback = usbExclusivePlayback,
                                onUsbExclusiveSettingsClick = {
                                    navigation.activePage = SettingsPage.UsbExclusive
                                },
                                allowMixedPlayback = allowMixedPlayback,
                                onAllowMixedPlaybackChange = onAllowMixedPlaybackChange,
                                preemptAudioFocus = preemptAudioFocus,
                                onPreemptAudioFocusChange = onPreemptAudioFocusChange,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                },

                SettingsPage.UsbExclusive to {
                    item(key = "${selectedPage.name}:content") {
                        UsbExclusiveSettingsSection(
                            usbExclusivePlayback = usbExclusivePlayback,
                            onUsbExclusivePlaybackChange = onUsbExclusivePlaybackChange,
                            preferences = usbExclusivePreferences,
                            onDeviceKeyChange = { deviceKey ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveDeviceKey(deviceKey)
                                }
                            },
                            onSampleRateModeChange = { mode ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveSampleRateMode(mode)
                                }
                            },
                            onBitDepthModeChange = { mode ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBitDepthMode(mode)
                                }
                            },
                            onBitPerfectChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBitPerfect(enabled)
                                }
                            },
                            onBufferProfileChange = { profile ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBufferProfile(profile)
                                }
                            },
                            onUnsupportedFormatPolicyChange = { policy ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveUnsupportedFormatPolicy(policy)
                                }
                            },
                            onSampleRateCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveSampleRateCompatibility(enabled)
                                }
                            },
                            onBitDepthCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveBitDepthCompatibility(enabled)
                                }
                            },
                            onChannelCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveChannelCompatibility(enabled)
                                }
                            },
                            onForegroundBufferMsChange = { bufferMs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveForegroundBufferMs(bufferMs)
                                }
                            },
                            onBackgroundBufferMsChange = { bufferMs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveBackgroundBufferMs(bufferMs)
                                }
                            },
                            onVolumeRiskThresholdDbfsChange = { thresholdDbfs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveVolumeRiskThresholdDbfs(thresholdDbfs)
                                }
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                },

                SettingsPage.PlaybackSource to {
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:content") {
                        YouTubePlaybackSourceSetting(
                            repository = AppContainer.settingsRepo,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(
                                AutoSettingsKeys.NETEASE_LOCAL_SOURCE_FALLBACK
                            ),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.LibraryMusic,
                                    contentDescription = stringResource(
                                        R.string.settings_netease_local_source_fallback
                                    ),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = neteaseLocalSourceFallback,
                                    onCheckedChange = onNeteaseLocalSourceFallbackChange
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = {
                                onNeteaseLocalSourceFallbackChange(!neteaseLocalSourceFallback)
                            }
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(
                                AutoSettingsKeys.NETEASE_AUTO_SOURCE_SWITCH
                            ),
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bilibili),
                                    contentDescription = stringResource(
                                        R.string.settings_netease_auto_source_switch
                                    ),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = neteaseAutoSourceSwitch,
                                    onCheckedChange = onNeteaseAutoSourceSwitchChange
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = {
                                onNeteaseAutoSourceSwitchChange(!neteaseAutoSourceSwitch)
                            }
                        )
                    }
                },

                SettingsPage.AudioQuality to {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsAudioQualitySection(
                            expanded = true,
                            arrowRotation = 0f,
                            onExpandedChange = {},
                            showHeader = false,
                            qualityLabel = qualityPresentation.neteaseLabel,
                            preferredQuality = preferredQuality,
                            onQualityChange = onQualityChange,
                            youtubeQualityLabel = qualityPresentation.youtubeLabel,
                            youtubePreferredQuality = youtubePreferredQuality,
                            onYouTubeQualityChange = onYouTubeQualityChange,
                            biliQualityLabel = qualityPresentation.biliLabel,
                            biliPreferredQuality = biliPreferredQuality,
                            onBiliQualityChange = onBiliQualityChange,
                            mobileDataFollowDefaultAudioQuality = mobileDataFollowDefaultAudioQuality,
                            onMobileDataFollowDefaultAudioQualityChange =
                                onMobileDataFollowDefaultAudioQualityChange,
                            mobileDataNeteaseQualityLabel = qualityPresentation.mobileNeteaseLabel,
                            mobileDataNeteaseAudioQuality = qualityPresentation.mobileNeteaseValue,
                            onMobileDataNeteaseAudioQualityChange =
                                onMobileDataNeteaseAudioQualityChange,
                            mobileDataYouTubeQualityLabel = qualityPresentation.mobileYouTubeLabel,
                            mobileDataYouTubeAudioQuality = qualityPresentation.mobileYouTubeValue,
                            onMobileDataYouTubeAudioQualityChange =
                                onMobileDataYouTubeAudioQualityChange,
                            mobileDataBiliQualityLabel = qualityPresentation.mobileBiliLabel,
                            mobileDataBiliAudioQuality = qualityPresentation.mobileBiliValue,
                            onMobileDataBiliAudioQualityChange = onMobileDataBiliAudioQualityChange,
                            showQualityDialog = showQualityDialog,
                            onShowQualityDialogChange = { showQualityDialog = it },
                            showYouTubeQualityDialog = showYouTubeQualityDialog,
                            onShowYouTubeQualityDialogChange = { showYouTubeQualityDialog = it },
                            showBiliQualityDialog = showBiliQualityDialog,
                            onShowBiliQualityDialogChange = { showBiliQualityDialog = it },
                            showMobileDataNeteaseQualityDialog = showMobileDataNeteaseQualityDialog,
                            onShowMobileDataNeteaseQualityDialogChange = {
                                showMobileDataNeteaseQualityDialog = it
                            },
                            showMobileDataYouTubeQualityDialog = showMobileDataYouTubeQualityDialog,
                            onShowMobileDataYouTubeQualityDialogChange = {
                                showMobileDataYouTubeQualityDialog = it
                            },
                            showMobileDataBiliQualityDialog = showMobileDataBiliQualityDialog,
                            onShowMobileDataBiliQualityDialogChange = {
                                showMobileDataBiliQualityDialog = it
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                },

                SettingsPage.Storage to {
                    settingsStoragePageItems(
                        directory = downloadDirectorySettings,
                        downloadDirectoryUri = downloadDirectoryUri,
                        downloadFileNameTemplate = downloadFileNameTemplate,
                        onDownloadFileNameTemplateChange = onDownloadFileNameTemplateChange,
                        maxCacheSizeBytes = maxCacheSizeBytes,
                        onMaxCacheSizeBytesChange = onMaxCacheSizeBytesChange,
                        onOpenStorageDetails = {
                            navigation.activePage = SettingsPage.StorageCacheDetails
                            storageDetailsController.requestRefresh()
                        },
                        storageDetails = storageDetailsController.details,
                        selection = storageSelection,
                        onClearCacheClick = onClearCacheClick,
                        highlightTargetId = settingsHighlightTargetId,
                        highlightPulse = settingsHighlightPulse,
                        onHighlightFinished = onSettingsHighlightFinished
                    )
                },

                SettingsPage.StorageCacheDetails to {
                    settingsStorageCacheDetailsItem(
                        storageDetails = storageDetailsController.details,
                        isScanning = storageDetailsController.isScanning,
                        onRefresh = storageDetailsController::requestRefresh,
                        onClearCache = {
                            navigation.activePage = SettingsPage.Storage
                            storageSelection.showClearCacheDialog = true
                        },
                        onOpenSystemSettings = {
                            openStorageSystemSettings(context) {
                                showSettingsMessage(
                                    composeResources.getString(
                                        R.string.storage_open_system_settings_failed
                                    )
                                )
                            }
                        }
                    )
                },

                SettingsPage.Downloads to {
                    settingsDownloadsPageItems(
                        onNavigateToDownloadManager = onNavigateToDownloadManager,
                        highlightTargetId = settingsHighlightTargetId,
                        highlightPulse = settingsHighlightPulse,
                        onHighlightFinished = onSettingsHighlightFinished
                    )
                },

                SettingsPage.TrafficManagement to {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsTrafficManagementSection()
                    }
                },

                SettingsPage.Backup to {
                    settingsBackupPageItems(
                        controller = backupTransfer,
                        repository = autoSettingsRepository,
                        scope = scope,
                        remoteDialogs = SettingsBackupRemoteDialogPort(
                            showGitHubConfigDialog = showGitHubConfigDialog,
                            showWebDavConfigDialog = showWebDavConfigDialog,
                            onOpenGitHubConfig = { showGitHubConfigDialog = true },
                            onOpenClearGitHubConfig = { showClearGitHubConfigDialog = true },
                            onOpenWebDavConfig = { showWebDavConfigDialog = true },
                            onOpenClearWebDavConfig = { showClearWebDavConfigDialog = true }
                        ),
                        highlightTargetId = settingsHighlightTargetId,
                        highlightPulse = settingsHighlightPulse,
                        onHighlightFinished = onSettingsHighlightFinished
                    )
                },

                SettingsPage.ListenTogether to {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsListenTogetherSection(
                            controller = listenTogetherSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Transparent)
                        )
                    }
                },

                SettingsPage.About to {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsAboutPageContent(
                            devModeEnabled = devModeEnabled,
                            onDevModeChange = onDevModeChange,
                            onInlineMessageChange = { inlineMsg = it },
                            onShowMessage = ::showSettingsMessage
                        )
                    }
                }
                )
                pageItems.getValue(selectedPage).invoke(this)
            }
            }
        )
    }

    SettingsPageHost(
        activePage = activeSettingsPage,
        splitLayout = isSettingsSplitLayout,
        isolateAdvancedGlassTransitions = isolateAdvancedGlassTransitions,
        content = settingsPageContent
    )

    SettingsAccountAuthDialogs(accountAuth, inlineMsg) { inlineMsg = it }
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

    SettingsListenTogetherDialogs(listenTogetherSettings)

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

    DownloadDirectoryDialogs(controller = downloadDirectorySettings)

}
