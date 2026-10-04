package moe.ouom.neriplayer.ui.screen.tab.settings

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings/SettingsScreen
 * Created: 2025/8/8
 */

import moe.ouom.neriplayer.data.ltw.validation.format
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.ui.settings.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.ui.settings.AutoSettingsSwitchItems
import moe.ouom.neriplayer.ui.settings.route.AppSettingsHostBindings
import moe.ouom.neriplayer.ui.component.settings.LanguageSettingItem
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.playback.PlaybackServiceIdleShutdownSetting
import moe.ouom.neriplayer.ui.screen.tab.settings.component.playback.SettingsAudioQualitySection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics.SettingsLyricsSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.appearance.SettingsMotionSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.playback.SettingsPlaybackSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.traffic.SettingsTrafficManagementSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeSeedListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.component.usb.UsbExclusiveSettingsSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.playback.YouTubePlaybackSourceSetting
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
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.about.SettingsAboutPageContent
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.BackgroundImagePickerPort
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.EnforceNowPlayingBackgroundExclusion
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.NowPlayingBackgroundExclusionPort
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsLyricsAppearanceContent
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsPersonalizationBackgroundCard
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsPersonalizationControlsCard
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsPersonalizationHomeCard
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsPersonalizationPlaybackInfoCard
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsPersonalizationStartCard
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.ThemeAutoModeListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.ThemeColorSpecSelector
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.ThemeModeSelectorListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.ThemePaletteStyleSelector
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.rememberSettingsBackgroundImageDraft
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.rememberSettingsBackgroundImagePicker
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsLoginExpandedContent
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.rememberSettingsAccountAuthController
import moe.ouom.neriplayer.ui.screen.tab.settings.backup.ObserveSettingsBackupPlaylistCount
import moe.ouom.neriplayer.ui.screen.tab.settings.backup.SettingsBackupRemoteDialogPort
import moe.ouom.neriplayer.ui.screen.tab.settings.backup.rememberSettingsBackupTransferController
import moe.ouom.neriplayer.ui.screen.tab.settings.backup.settingsBackupPageItems
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectoryDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.rememberDownloadDirectorySettingsController
import moe.ouom.neriplayer.ui.screen.tab.settings.home.rememberSettingsHomeStartPresentation
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.SettingsListenTogetherDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.SettingsListenTogetherSection
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.rememberSettingsListenTogetherController
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.SettingsPageHost
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.SettingsPageScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.rememberSettingsNavigationState
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.settingsHomePageItems
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.rememberSettingsQualityPresentation
import moe.ouom.neriplayer.ui.screen.tab.settings.storage.openStorageSystemSettings
import moe.ouom.neriplayer.ui.screen.tab.settings.storage.rememberSettingsStorageDetailsController
import moe.ouom.neriplayer.ui.screen.tab.settings.storage.rememberSettingsStorageSelectionState
import moe.ouom.neriplayer.ui.screen.tab.settings.storage.settingsDownloadsPageItems
import moe.ouom.neriplayer.ui.screen.tab.settings.storage.settingsStorageCacheDetailsItem
import moe.ouom.neriplayer.ui.screen.tab.settings.storage.settingsStoragePageItems
import moe.ouom.neriplayer.ui.screen.tab.settings.storage.settingsStorageProcessingItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@NonRestartableComposable
internal fun SettingsScreen(
    listState: LazyListState,
    bindings: AppSettingsHostBindings,
    isActive: Boolean = true,
    onNavigateToDownloadManager: () -> Unit
) {
    val appearanceState = bindings.state.appearance
    val lyricsState = bindings.state.lyrics
    val playbackState = bindings.state.playback
    val otherState = bindings.state.other
    val appearanceActions = bindings.appearanceActions
    val lyricsActions = bindings.lyricsActions
    val qualityActions = bindings.qualityActions
    val playbackActions = bindings.playbackActions
    val usbActions = bindings.usbActions
    val homeActions = bindings.homeActions
    val storageActions = bindings.storageActions
    val environment = bindings.environment
    val repository = bindings.repository
    val listenTogether = bindings.listenTogether

    val context = LocalContext.current
    val composeResources = LocalResources.current
    val scope = rememberCoroutineScope()
    val autoSettingsRepository = remember(context) { AutoSettingsRepository(context) }
    val listenTogetherSettings = rememberSettingsListenTogetherController(
        preferences = listenTogether.preferences,
        api = listenTogether.api,
        sessionManager = listenTogether.sessionManager,
        onMessage = { AppFeedback.showToast(context = context, message = it) }
    )
    val backgroundImageDraft = rememberSettingsBackgroundImageDraft(
        imageUri = appearanceState.visualBackground.backgroundImageUri,
        committedBlur = appearanceState.visualBackground.backgroundImageBlur,
        committedAlpha = environment.backgroundImageAlpha
    )

    EnforceNowPlayingBackgroundExclusion(
        NowPlayingBackgroundExclusionPort(
            coverBlurEnabled = appearanceState.nowPlayingVisual.nowPlayingCoverBlurBackgroundEnabled,
            dynamicEnabled = appearanceState.nowPlayingVisual.nowPlayingDynamicBackgroundEnabled,
            reactiveEnabled = appearanceState.nowPlayingVisual.nowPlayingAudioReactiveEnabled,
            onDynamicChange = appearanceActions.onNowPlayingDynamicBackgroundEnabledChange,
            onReactiveChange = appearanceActions.onNowPlayingAudioReactiveEnabledChange
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

    val backupTransfer =
        rememberSettingsBackupTransferController(environment.onBeforeLanguageRestart)

    fun showSettingsMessage(message: String) {
        AppFeedback.show(context = context, message = message)
    }

    val downloadDirectorySettings = rememberDownloadDirectorySettingsController(
        downloadDirectoryUri = otherState.storage.downloadDirectoryUri,
        onDownloadDirectoryUriChange = storageActions.onDownloadDirectoryUriChange,
        onInlineMessageChange = { inlineMsg = it },
        onShowMessage = ::showSettingsMessage
    )

    val pickBackgroundImage = rememberSettingsBackgroundImagePicker(
        BackgroundImagePickerPort(
            appearanceState.visualBackground.backgroundImageUri,
            appearanceActions.onBackgroundImageChange
        )
    )

    val qualityPresentation = rememberSettingsQualityPresentation(
        context = context,
        neteaseValue = playbackState.defaultAudioQuality.preferredQuality,
        youtubeValue = playbackState.defaultAudioQuality.youtubePreferredQuality,
        biliValue = playbackState.defaultAudioQuality.biliPreferredQuality,
        mobileNeteaseValue = playbackState.mobileAudioQuality.mobileDataNeteaseAudioQuality,
        mobileYouTubeValue = playbackState.mobileAudioQuality.mobileDataYouTubeAudioQuality,
        mobileBiliValue = playbackState.mobileAudioQuality.mobileDataBiliAudioQuality
    )

    val homeStartPresentation = rememberSettingsHomeStartPresentation(
        resources = composeResources,
        configuredDestination = environment.defaultStartDestination,
        internationalEnabled = otherState.internationalizationEnabled,
        showTrending = otherState.homeCards.showHomeTrendingCard,
        showRadar = otherState.homeCards.showHomeRadarCard,
        showRecommended = otherState.homeCards.showHomeRecommendedCard,
        showContinue = otherState.homeCards.showHomeContinueCard,
        hasRecentUsage = environment.homeHasRecentUsage
    )
    val homeStartAvailable = homeStartPresentation.available
    val homeCardCopy = homeStartPresentation.copy
    val effectiveDefaultStartDestination = homeStartPresentation.destination
    val defaultStartDestinationLabel = homeStartPresentation.destinationLabel
    val navigation = rememberSettingsNavigationState(
        context = context,
        listState = listState,
        dynamicColor = appearanceState.theme.dynamicColorEnabled,
        mobileDataFollowDefaultAudioQuality = playbackState.defaultAudioQuality.mobileDataFollowDefaultAudioQuality,
        backgroundImageUri = appearanceState.visualBackground.backgroundImageUri
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
            Text(stringResource(CoreCommonR.string.settings_title))
            ThemeModeActionButton(
                isDarkTheme = environment.isDarkTheme,
                onToggleRequest = environment.onThemeToggleRequest
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
            detail = { requestedDetailPage ->
                val settingsDetailContent: LazyListScope.(SettingsPage) -> Unit = { selectedPage ->
                    if (selectedPage != SettingsPage.Accounts) {
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
                                    onLanguageChanged = environment.onLanguageChanged
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
                                            painter = painterResource(id = CoreCommonR.drawable.ic_i18n),
                                            contentDescription = stringResource(CoreCommonR.string.settings_internationalization),
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    headlineContent = { Text(stringResource(CoreCommonR.string.settings_internationalization)) },
                                    supportingContent = {
                                        Text(
                                            stringResource(CoreCommonR.string.settings_internationalization_desc)
                                        )
                                    },
                                    trailingContent = {
                                        MiuixSettingsSwitch(
                                            checked = otherState.internationalizationEnabled,
                                            onCheckedChange = appearanceActions.onInternationalizationEnabledChange
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                                MiuixSettingsSectionIntro(
                                    title = stringResource(CoreCommonR.string.settings_ui_scale),
                                    description = stringResource(CoreCommonR.string.settings_ui_scale_global_desc)
                                )
                                AutoSettingsListItem(
                                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.UI_DENSITY_SCALE),
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Outlined.ZoomInMap,
                                            contentDescription = stringResource(CoreCommonR.string.settings_ui_scale),
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                CoreCommonR.string.settings_ui_scale_current,
                                                "%.2f".format(appearanceState.visualBackground.uiDensityScale)
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
                                    title = stringResource(CoreCommonR.string.settings_theme_mode),
                                    description = stringResource(CoreCommonR.string.settings_theme_mode_desc)
                                )
                                ThemeModeSelectorListItem(
                                    isDarkTheme = environment.isDarkTheme,
                                    themeMode = environment.themeMode,
                                    onThemeModeRequest = environment.onThemeModeRequest,
                                    modifier = Modifier.settingsHighlightTarget(
                                        targetId = "manual:theme_mode",
                                        highlightTargetId = settingsHighlightTargetId,
                                        highlightPulse = settingsHighlightPulse,
                                        onHighlightFinished = onSettingsHighlightFinished
                                    )
                                )
                                ThemeAutoModeListItem(
                                    themeMode = environment.themeMode,
                                    isDarkTheme = environment.isDarkTheme,
                                    onThemeModeRequest = environment.onThemeModeRequest
                                )
                            }
                            miuixSettingsSectionCardItem(key = "${selectedPage.name}:dynamic_color") {
                                MiuixSettingsSectionIntro(
                                    title = stringResource(CoreCommonR.string.settings_theme_color_section),
                                    description = stringResource(CoreCommonR.string.settings_theme_color_section_desc)
                                )
                                AutoSettingsListItem(
                                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DYNAMIC_COLOR),
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Outlined.Colorize,
                                            contentDescription = stringResource(CoreCommonR.string.settings_dynamic_color),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    trailingContent = {
                                        MiuixSettingsSwitch(
                                            checked = appearanceState.theme.dynamicColorEnabled,
                                            onCheckedChange = appearanceActions.onDynamicColorChange
                                        )
                                    },
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished,
                                    onClick = { appearanceActions.onDynamicColorChange(!appearanceState.theme.dynamicColorEnabled) }
                                )
                                LazyAnimatedVisibility(visible = !appearanceState.theme.dynamicColorEnabled) {
                                    ThemeSeedListItem(
                                        seedColorHex = appearanceState.theme.themeSeedColor,
                                        onClick = { showColorPickerDialog = true },
                                        highlightTargetId = settingsHighlightTargetId,
                                        highlightPulse = settingsHighlightPulse,
                                        onHighlightFinished = onSettingsHighlightFinished
                                    )
                                }
                                Text(
                                    text = stringResource(CoreCommonR.string.settings_theme_palette_hint),
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        bottom = 8.dp
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            miuixSettingsSectionCardItem(key = "${selectedPage.name}:palette_style") {
                                ThemePaletteStyleSelector(
                                    selectedStyle = appearanceState.theme.themePaletteStyleValue,
                                    onStyleChange = appearanceActions.onThemePaletteStyleChange,
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
                                    selectedSpec = appearanceState.visualBlur.themeColorSpecValue,
                                    onSpecChange = appearanceActions.onThemeColorSpecChange,
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
                            item(key = "${selectedPage.name}:content") {
                                SettingsLoginExpandedContent(
                                    accountAuth,
                                    isActive = isActive && environment.settingsVisible &&
                                        activeSettingsPage == SettingsPage.Accounts,
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished
                                )
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
                                    internationalEnabled = otherState.internationalizationEnabled,
                                    homeTrendingLabelRes = homeCardCopy.trendingLabelRes,
                                    homeRadarLabelRes = homeCardCopy.radarLabelRes,
                                    homeRecommendedLabelRes = homeCardCopy.recommendedLabelRes,
                                    homeTrendingSupportingRes = homeCardCopy.trendingSupportingRes,
                                    homeRadarSupportingRes = homeCardCopy.radarSupportingRes,
                                    homeRecommendedSupportingRes = homeCardCopy.recommendedSupportingRes,
                                    homeStartAvailable = homeStartAvailable,
                                    showHomeContinueCard = otherState.homeCards.showHomeContinueCard,
                                    onShowHomeContinueCardChange = homeActions.onShowHomeContinueCardChange,
                                    showHomeTrendingCard = otherState.homeCards.showHomeTrendingCard,
                                    onShowHomeTrendingCardChange = homeActions.onShowHomeTrendingCardChange,
                                    showHomeRadarCard = otherState.homeCards.showHomeRadarCard,
                                    onShowHomeRadarCardChange = homeActions.onShowHomeRadarCardChange,
                                    showHomeRecommendedCard = otherState.homeCards.showHomeRecommendedCard,
                                    onShowHomeRecommendedCardChange = homeActions.onShowHomeRecommendedCardChange,
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
                                    settingsRepository = repository,
                                    scope = scope,
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished
                                )
                            }
                            item(key = "${selectedPage.name}:card:4") {
                                SettingsPersonalizationBackgroundCard(
                                    backgroundImageUri = appearanceState.visualBackground.backgroundImageUri,
                                    onPickBackgroundImage = {
                                        pickBackgroundImage()
                                    },
                                    onClearBackgroundImage = {
                                        scope.launch {
                                            BackgroundImageStorage.deleteManagedBackground(
                                                context = context,
                                                uriString = appearanceState.visualBackground.backgroundImageUri
                                            )
                                            appearanceActions.onBackgroundImageChange(null)
                                        }
                                    },
                                    pendingBackgroundImageBlur = backgroundImageDraft.blur,
                                    onPendingBackgroundImageBlurChange = {
                                        backgroundImageDraft.blur = it
                                    },
                                    onBackgroundImageBlurCommit = {
                                        appearanceActions.onBackgroundImageBlurChange(
                                            backgroundImageDraft.blur
                                        )
                                        appearanceActions.onBackgroundImageBlurChangeFinished(
                                            backgroundImageDraft.blur
                                        )
                                    },
                                    pendingBackgroundImageAlpha = backgroundImageDraft.alpha,
                                    onPendingBackgroundImageAlphaChange = {
                                        backgroundImageDraft.alpha = it
                                        appearanceActions.onBackgroundImageAlphaChange(it)
                                    },
                                    onBackgroundImageAlphaCommit = {
                                        appearanceActions.onBackgroundImageAlphaChangeFinished(
                                            backgroundImageDraft.alpha
                                        )
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
                                        advancedBlurEnabled = appearanceState.visualBlur.advancedBlurEnabled,
                                        onAdvancedBlurEnabledChange = appearanceActions.onAdvancedBlurEnabledChange,
                                        enhancedAdvancedBlurEnabled = appearanceState.visualBlur.enhancedAdvancedBlurEnabled,
                                        onEnhancedAdvancedBlurEnabledChange =
                                            appearanceActions.onEnhancedAdvancedBlurEnabledChange,
                                        enhancedAdvancedBlurRadiusDp = appearanceState.visualBlur.enhancedAdvancedBlurRadiusDp,
                                        onEnhancedAdvancedBlurRadiusDpChange =
                                            appearanceActions.onEnhancedAdvancedBlurRadiusDpChange,
                                        advancedBlurQuality = appearanceState.visualBlur.advancedBlurQuality,
                                        onAdvancedBlurQualityChange = appearanceActions.onAdvancedBlurQualityChange,
                                        nowPlayingAudioReactiveEnabled = appearanceState.nowPlayingVisual.nowPlayingAudioReactiveEnabled,
                                        onNowPlayingAudioReactiveEnabledChange =
                                            appearanceActions.onNowPlayingAudioReactiveEnabledChange,
                                        nowPlayingDynamicBackgroundEnabled =
                                            appearanceState.nowPlayingVisual.nowPlayingDynamicBackgroundEnabled,
                                        onNowPlayingDynamicBackgroundEnabledChange =
                                            appearanceActions.onNowPlayingDynamicBackgroundEnabledChange,
                                        nowPlayingCoverBlurBackgroundEnabled =
                                            appearanceState.nowPlayingVisual.nowPlayingCoverBlurBackgroundEnabled,
                                        onNowPlayingCoverBlurBackgroundEnabledChange =
                                            appearanceActions.onNowPlayingCoverBlurBackgroundEnabledChange,
                                        nowPlayingCoverBlurAmount = appearanceState.nowPlayingVisual.nowPlayingCoverBlurAmount,
                                        onNowPlayingCoverBlurAmountChange = appearanceActions.onNowPlayingCoverBlurAmountChange,
                                        nowPlayingCoverBlurDarken = appearanceState.nowPlayingVisual.nowPlayingCoverBlurDarken,
                                        onNowPlayingCoverBlurDarkenChange = appearanceActions.onNowPlayingCoverBlurDarkenChange,
                                        lyricBlurEnabled = lyricsState.lyricPresentation.lyricBlurEnabled,
                                        onLyricBlurEnabledChange = lyricsActions.onLyricBlurEnabledChange,
                                        lyricBlurAmount = lyricsState.lyricPresentation.lyricBlurAmount,
                                        onLyricBlurAmountChange = lyricsActions.onLyricBlurAmountChange,
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
                                        settingsRepository = repository,
                                        scope = scope,
                                        floatingLyricsPreferences = lyricsState.lyricPresentation.floatingLyricsPreferences,
                                        onFloatingLyricsPreferencesChange = lyricsActions.onFloatingLyricsPreferencesChange,
                                        lyricsAppearanceContent = {
                                            SettingsLyricsAppearanceContent(
                                                autoSettingsRepository = autoSettingsRepository,
                                                scope = scope,
                                                lyricFontScales = lyricsState.lyricPresentation.lyricFontScales,
                                                onLyricFontScaleChange = lyricsActions.onLyricFontScaleChange,
                                                highlightTargetId = settingsHighlightTargetId,
                                                highlightPulse = settingsHighlightPulse,
                                                onHighlightFinished = onSettingsHighlightFinished
                                            )
                                        },
                                        cloudMusicLyricDefaultOffsetMs = lyricsState.lyricOffsets.cloudMusicLyricDefaultOffsetMs,
                                        onCloudMusicLyricDefaultOffsetMsChange =
                                            lyricsActions.onCloudMusicLyricDefaultOffsetMsChange,
                                        qqMusicLyricDefaultOffsetMs = lyricsState.lyricOffsets.qqMusicLyricDefaultOffsetMs,
                                        onQqMusicLyricDefaultOffsetMsChange =
                                            lyricsActions.onQqMusicLyricDefaultOffsetMsChange,
                                        kugouLyricDefaultOffsetMs = lyricsState.lyricOffsets.kugouLyricDefaultOffsetMs,
                                        onKugouLyricDefaultOffsetMsChange =
                                            lyricsActions.onKugouLyricDefaultOffsetMsChange,
                                        lrclibLyricDefaultOffsetMs = lyricsState.lyricOffsets.lrclibLyricDefaultOffsetMs,
                                        onLrclibLyricDefaultOffsetMsChange =
                                            lyricsActions.onLrclibLyricDefaultOffsetMsChange,
                                        amllTtmlLyricDefaultOffsetMs = lyricsState.lyricOffsets.amllTtmlLyricDefaultOffsetMs,
                                        onAmllTtmlLyricDefaultOffsetMsChange =
                                            lyricsActions.onAmllTtmlLyricDefaultOffsetMsChange,
                                        onResetAllLyricDefaultOffsets = lyricsActions.onResetAllLyricDefaultOffsets,
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
                                            contentDescription = stringResource(CoreCommonR.string.settings_bypass_proxy),
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    trailingContent = {
                                        MiuixSettingsSwitch(
                                            checked = otherState.storage.bypassProxy,
                                            onCheckedChange = storageActions.onBypassProxyChange
                                        )
                                    },
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished,
                                    onClick = { storageActions.onBypassProxyChange(!otherState.storage.bypassProxy) }
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
                                        playbackFadeIn = playbackState.playbackFade.playbackFadeIn,
                                        onPlaybackFadeInChange = playbackActions.onPlaybackFadeInChange,
                                        playbackCrossfadeNext = playbackState.playbackFade.playbackCrossfadeNext,
                                        onPlaybackCrossfadeNextChange = playbackActions.onPlaybackCrossfadeNextChange,
                                        sleepTimerFinishCurrentOnExpiry = playbackState.playbackContinuity.sleepTimerFinishCurrentOnExpiry,
                                        onSleepTimerFinishCurrentOnExpiryChange =
                                            playbackActions.onSleepTimerFinishCurrentOnExpiryChange,
                                        playbackFadeInDurationMs = playbackState.playbackFade.playbackFadeInDurationMs,
                                        onPlaybackFadeInDurationMsChange = playbackActions.onPlaybackFadeInDurationMsChange,
                                        playbackFadeOutDurationMs = playbackState.playbackFade.playbackFadeOutDurationMs,
                                        onPlaybackFadeOutDurationMsChange = playbackActions.onPlaybackFadeOutDurationMsChange,
                                        playbackCrossfadeInDurationMs = playbackState.playbackFade.playbackCrossfadeInDurationMs,
                                        onPlaybackCrossfadeInDurationMsChange =
                                            playbackActions.onPlaybackCrossfadeInDurationMsChange,
                                        playbackCrossfadeOutDurationMs = playbackState.playbackOutput.playbackCrossfadeOutDurationMs,
                                        onPlaybackCrossfadeOutDurationMsChange =
                                            playbackActions.onPlaybackCrossfadeOutDurationMsChange,
                                        playbackVolumeNormalizationEnabled = playbackState.playbackOutput.playbackVolumeNormalizationEnabled,
                                        onPlaybackVolumeNormalizationEnabledChange =
                                            playbackActions.onPlaybackVolumeNormalizationEnabledChange,
                                        playbackHighResolutionOutputEnabled =
                                            playbackState.playbackOutput.playbackHighResolutionOutputEnabled,
                                        onPlaybackHighResolutionOutputEnabledChange =
                                            playbackActions.onPlaybackHighResolutionOutputEnabledChange,
                                        playbackVolumeBalance = playbackState.playbackOutput.playbackVolumeBalance,
                                        onPlaybackVolumeBalanceChange = playbackActions.onPlaybackVolumeBalanceChange,
                                        keepLastPlaybackProgress = playbackState.playbackContinuity.keepLastPlaybackProgress,
                                        onKeepLastPlaybackProgressChange = playbackActions.onKeepLastPlaybackProgressChange,
                                        rememberLongFormPlaybackProgress = playbackState.playbackContinuity.rememberLongFormPlaybackProgress,
                                        onRememberLongFormPlaybackProgressChange =
                                            playbackActions.onRememberLongFormPlaybackProgressChange,
                                        keepPlaybackModeState = playbackState.playbackContinuity.keepPlaybackModeState,
                                        onKeepPlaybackModeStateChange = playbackActions.onKeepPlaybackModeStateChange,
                                        stopOnBluetoothDisconnect = playbackState.playbackContinuity.stopOnBluetoothDisconnect,
                                        onStopOnBluetoothDisconnectChange = playbackActions.onStopOnBluetoothDisconnectChange,
                                        usbExclusivePlayback = playbackState.playbackOutput.usbExclusivePlayback,
                                        onUsbExclusiveSettingsClick = {
                                            navigation.activePage = SettingsPage.UsbExclusive
                                        },
                                        allowMixedPlayback = playbackState.playbackSources.allowMixedPlayback,
                                        onAllowMixedPlaybackChange = playbackActions.onAllowMixedPlaybackChange,
                                        preemptAudioFocus = playbackState.playbackSources.preemptAudioFocus,
                                        onPreemptAudioFocusChange = playbackActions.onPreemptAudioFocusChange,
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
                                    usbExclusivePlayback = playbackState.playbackOutput.usbExclusivePlayback,
                                    onUsbExclusivePlaybackChange = playbackActions.onUsbExclusivePlaybackChange,
                                    preferences = playbackState.usbExclusivePreferences,
                                    onDeviceKeyChange = usbActions.onDeviceKeyChange,
                                    onSampleRateModeChange = usbActions.onSampleRateModeChange,
                                    onBitDepthModeChange = usbActions.onBitDepthModeChange,
                                    onBitPerfectChange = usbActions.onBitPerfectChange,
                                    onBufferProfileChange = usbActions.onBufferProfileChange,
                                    onUnsupportedFormatPolicyChange = usbActions.onUnsupportedFormatPolicyChange,
                                    onSampleRateCompatibilityChange = usbActions.onSampleRateCompatibilityChange,
                                    onBitDepthCompatibilityChange = usbActions.onBitDepthCompatibilityChange,
                                    onChannelCompatibilityChange = usbActions.onChannelCompatibilityChange,
                                    onForegroundBufferMsChange = usbActions.onForegroundBufferMsChange,
                                    onBackgroundBufferMsChange = usbActions.onBackgroundBufferMsChange,
                                    onVolumeRiskThresholdDbfsChange = usbActions.onVolumeRiskThresholdDbfsChange,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        },

                        SettingsPage.PlaybackSource to {
                            miuixSettingsSectionCardItem(key = "${selectedPage.name}:content") {
                                YouTubePlaybackSourceSetting(
                                    repository = repository,
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
                                                CoreCommonR.string.settings_netease_local_source_fallback
                                            ),
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    trailingContent = {
                                        MiuixSettingsSwitch(
                                            checked = playbackState.playbackSources.neteaseLocalSourceFallback,
                                            onCheckedChange = playbackActions.onNeteaseLocalSourceFallbackChange
                                        )
                                    },
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished,
                                    onClick = {
                                        playbackActions.onNeteaseLocalSourceFallbackChange(!playbackState.playbackSources.neteaseLocalSourceFallback)
                                    }
                                )
                                AutoSettingsListItem(
                                    setting = AutoSettingsMetadata.requireSetting(
                                        AutoSettingsKeys.NETEASE_AUTO_SOURCE_SWITCH
                                    ),
                                    leadingContent = {
                                        Icon(
                                            painter = painterResource(CoreCommonR.drawable.ic_bilibili),
                                            contentDescription = stringResource(
                                                CoreCommonR.string.settings_netease_auto_source_switch
                                            ),
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    trailingContent = {
                                        MiuixSettingsSwitch(
                                            checked = playbackState.playbackSources.neteaseAutoSourceSwitch,
                                            onCheckedChange = playbackActions.onNeteaseAutoSourceSwitchChange
                                        )
                                    },
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished,
                                    onClick = {
                                        playbackActions.onNeteaseAutoSourceSwitchChange(!playbackState.playbackSources.neteaseAutoSourceSwitch)
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
                                    preferredQuality = playbackState.defaultAudioQuality.preferredQuality,
                                    onQualityChange = qualityActions.onQualityChange,
                                    youtubeQualityLabel = qualityPresentation.youtubeLabel,
                                    youtubePreferredQuality = playbackState.defaultAudioQuality.youtubePreferredQuality,
                                    onYouTubeQualityChange = qualityActions.onYouTubeQualityChange,
                                    biliQualityLabel = qualityPresentation.biliLabel,
                                    biliPreferredQuality = playbackState.defaultAudioQuality.biliPreferredQuality,
                                    onBiliQualityChange = qualityActions.onBiliQualityChange,
                                    mobileDataFollowDefaultAudioQuality = playbackState.defaultAudioQuality.mobileDataFollowDefaultAudioQuality,
                                    onMobileDataFollowDefaultAudioQualityChange =
                                        qualityActions.onMobileDataFollowDefaultAudioQualityChange,
                                    mobileDataNeteaseQualityLabel = qualityPresentation.mobileNeteaseLabel,
                                    mobileDataNeteaseAudioQuality = qualityPresentation.mobileNeteaseValue,
                                    onMobileDataNeteaseAudioQualityChange =
                                        qualityActions.onMobileDataNeteaseAudioQualityChange,
                                    mobileDataYouTubeQualityLabel = qualityPresentation.mobileYouTubeLabel,
                                    mobileDataYouTubeAudioQuality = qualityPresentation.mobileYouTubeValue,
                                    onMobileDataYouTubeAudioQualityChange =
                                        qualityActions.onMobileDataYouTubeAudioQualityChange,
                                    mobileDataBiliQualityLabel = qualityPresentation.mobileBiliLabel,
                                    mobileDataBiliAudioQuality = qualityPresentation.mobileBiliValue,
                                    onMobileDataBiliAudioQualityChange = qualityActions.onMobileDataBiliAudioQualityChange,
                                    showQualityDialog = showQualityDialog,
                                    onShowQualityDialogChange = { showQualityDialog = it },
                                    showYouTubeQualityDialog = showYouTubeQualityDialog,
                                    onShowYouTubeQualityDialogChange = {
                                        showYouTubeQualityDialog = it
                                    },
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
                                downloadDirectoryUri = otherState.storage.downloadDirectoryUri,
                                downloadFileNameTemplate = otherState.storage.downloadFileNameTemplate,
                                onDownloadFileNameTemplateChange = storageActions.onDownloadFileNameTemplateChange,
                                maxCacheSizeBytes = otherState.storage.maxCacheSizeBytes,
                                onMaxCacheSizeBytesChange = storageActions.onMaxCacheSizeBytesChange,
                                onOpenStorageDetails = {
                                    navigation.activePage = SettingsPage.StorageCacheDetails
                                    storageDetailsController.requestRefresh()
                                },
                                storageDetails = storageDetailsController.details,
                                selection = storageSelection,
                                onClearCacheClick = storageActions.onClearCacheClick,
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
                                                CoreCommonR.string.storage_open_system_settings_failed
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
                                    onOpenClearGitHubConfig = {
                                        showClearGitHubConfigDialog = true
                                    },
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
                                    devModeEnabled = appearanceState.theme.devModeEnabled,
                                    onDevModeChange = appearanceActions.onDevModeChange,
                                    onInlineMessageChange = { inlineMsg = it },
                                    onShowMessage = ::showSettingsMessage
                                )
                            }
                        }
                    )
                    pageItems.getValue(selectedPage).invoke(this)
                }
                MiuixSettingsResponsiveDetailScaffold(
                    title = stringResource(requestedDetailPage.titleRes),
                    onBack = ::navigateBackFromActiveSettingsPage,
                    listState = detailListStates.getValue(requestedDetailPage),
                    topAppBarState = detailTopAppBarStates.getValue(requestedDetailPage),
                    splitLayout = isSettingsSplitLayout,
                    showSplitDetailBackButton = showSplitDetailBackButton,
                    selectedPage = requestedDetailPage,
                    homeListState = listState,
                    homeTopAppBarState = homeTopAppBarState,
                    homeTitle = settingsHomeTitle,
                    homeContent = settingsHomeContent,
                    detailContent = settingsDetailContent,
                    detailListStates = detailListStates,
                    detailTopAppBarStates = detailTopAppBarStates
                ) {
                    settingsDetailContent(requestedDetailPage)
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
        onDefaultStartDestinationChange = homeActions.onDefaultStartDestinationChange,
        showColorPickerDialog = showColorPickerDialog,
        onShowColorPickerDialogChange = { showColorPickerDialog = it },
        seedColorHex = appearanceState.theme.themeSeedColor,
        themeColorPalette = appearanceState.theme.themeColorPalette,
        onSeedColorChange = appearanceActions.onSeedColorChange,
        onAddColorToPalette = appearanceActions.onAddColorToPalette,
        onRemoveColorFromPalette = appearanceActions.onRemoveColorFromPalette,
        showDpiDialog = showDpiDialog,
        onShowDpiDialogChange = { showDpiDialog = it },
        uiDensityScale = appearanceState.visualBackground.uiDensityScale,
        onUiDensityScaleChange = appearanceActions.onUiDensityScaleChange
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
