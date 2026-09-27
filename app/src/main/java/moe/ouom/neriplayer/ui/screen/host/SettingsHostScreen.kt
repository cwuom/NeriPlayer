package moe.ouom.neriplayer.ui.screen.host

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
 * File: moe.ouom.neriplayer.ui.screen.host/SettingsHostScreen
 * Created: 2025/1/17
 */

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.ui.AppSettingsHostBindings
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.screen.DownloadManagerScreen
import moe.ouom.neriplayer.ui.screen.DownloadProgressScreen
import moe.ouom.neriplayer.ui.screen.tab.SettingsScreen

internal enum class SettingsScreenState {
    Settings,
    DownloadManager,
    DownloadProgress
}

internal fun <T> selectSettingsHostPage(
    state: SettingsScreenState,
    settings: T,
    downloadManager: T,
    downloadProgress: T
): T = when (state) {
    SettingsScreenState.Settings -> settings
    SettingsScreenState.DownloadManager -> downloadManager
    SettingsScreenState.DownloadProgress -> downloadProgress
}

private fun SettingsScreenState.saveableKey(): String = "settings_host:${name}"

private val SettingsScreenState.navigationDepth: Int
    get() = when (this) {
        SettingsScreenState.Settings -> 0
        SettingsScreenState.DownloadManager -> 1
        SettingsScreenState.DownloadProgress -> 2
    }

internal fun SettingsScreenState.nextTowards(
    requestedState: SettingsScreenState
): SettingsScreenState = when {
    navigationDepth < requestedState.navigationDepth -> when (this) {
        SettingsScreenState.Settings -> SettingsScreenState.DownloadManager
        SettingsScreenState.DownloadManager -> SettingsScreenState.DownloadProgress
        SettingsScreenState.DownloadProgress -> SettingsScreenState.DownloadProgress
    }
    navigationDepth > requestedState.navigationDepth -> when (this) {
        SettingsScreenState.Settings -> SettingsScreenState.Settings
        SettingsScreenState.DownloadManager -> SettingsScreenState.Settings
        SettingsScreenState.DownloadProgress -> SettingsScreenState.DownloadManager
    }
    else -> this
}

internal fun shouldAdvanceSettingsScreenTransition(
    targetState: SettingsScreenState,
    currentState: SettingsScreenState,
    isRunning: Boolean,
    requestedState: SettingsScreenState,
    renderedScreenStates: Set<SettingsScreenState>
): Boolean = !isRunning &&
    currentState == targetState &&
    targetState != requestedState &&
    renderedScreenStates == setOf(targetState)

@Composable
internal fun SettingsHostScreen(
    bindings: AppSettingsHostBindings,
    renderScene: @Composable (Float, Float, Float, Int, @Composable () -> Unit) -> Unit
) {
    val settingsState = bindings.state
    val devModeEnabled = settingsState.appearance.theme.devModeEnabled
    val themeColorPalette = settingsState.appearance.theme.themeColorPalette
    val lyricBlurEnabled = settingsState.lyrics.lyricPresentation.lyricBlurEnabled
    val lyricBlurAmount = settingsState.lyrics.lyricPresentation.lyricBlurAmount
    val cloudMusicLyricDefaultOffsetMs = settingsState.lyrics.lyricOffsets.cloudMusicLyricDefaultOffsetMs
    val qqMusicLyricDefaultOffsetMs = settingsState.lyrics.lyricOffsets.qqMusicLyricDefaultOffsetMs
    val kugouLyricDefaultOffsetMs = settingsState.lyrics.lyricOffsets.kugouLyricDefaultOffsetMs
    val lrclibLyricDefaultOffsetMs = settingsState.lyrics.lyricOffsets.lrclibLyricDefaultOffsetMs
    val amllTtmlLyricDefaultOffsetMs = settingsState.lyrics.lyricOffsets.amllTtmlLyricDefaultOffsetMs
    val floatingLyricsPreferences = settingsState.lyrics.lyricPresentation.floatingLyricsPreferences
    val advancedBlurEnabled = settingsState.appearance.visualBlur.advancedBlurEnabled
    val enhancedAdvancedBlurEnabled = settingsState.appearance.visualBlur.enhancedAdvancedBlurEnabled
    val enhancedAdvancedBlurRadiusDp = settingsState.appearance.visualBlur.enhancedAdvancedBlurRadiusDp
    val advancedBlurQuality = settingsState.appearance.visualBlur.advancedBlurQuality
    val nowPlayingAudioReactiveEnabled = settingsState.appearance.nowPlayingVisual.nowPlayingAudioReactiveEnabled
    val nowPlayingDynamicBackgroundEnabled = settingsState.appearance.nowPlayingVisual.nowPlayingDynamicBackgroundEnabled
    val nowPlayingCoverBlurBackgroundEnabled = settingsState.appearance.nowPlayingVisual.nowPlayingCoverBlurBackgroundEnabled
    val nowPlayingCoverBlurAmount = settingsState.appearance.nowPlayingVisual.nowPlayingCoverBlurAmount
    val nowPlayingCoverBlurDarken = settingsState.appearance.nowPlayingVisual.nowPlayingCoverBlurDarken
    val lyricFontScales = settingsState.lyrics.lyricPresentation.lyricFontScales
    val uiDensityScale = settingsState.appearance.visualBackground.uiDensityScale
    val bypassProxy = settingsState.other.storage.bypassProxy
    val backgroundImageUri = settingsState.appearance.visualBackground.backgroundImageUri
    val downloadDirectoryUri = settingsState.other.storage.downloadDirectoryUri
    val downloadFileNameTemplate = settingsState.other.storage.downloadFileNameTemplate
    val backgroundImageBlur = settingsState.appearance.visualBackground.backgroundImageBlur
    val showHomeContinueCard = settingsState.other.homeCards.showHomeContinueCard
    val showHomeTrendingCard = settingsState.other.homeCards.showHomeTrendingCard
    val showHomeRadarCard = settingsState.other.homeCards.showHomeRadarCard
    val showHomeRecommendedCard = settingsState.other.homeCards.showHomeRecommendedCard
    val playbackFadeIn = settingsState.playback.playbackFade.playbackFadeIn
    val playbackCrossfadeNext = settingsState.playback.playbackFade.playbackCrossfadeNext
    val sleepTimerFinishCurrentOnExpiry = settingsState.playback.playbackContinuity.sleepTimerFinishCurrentOnExpiry
    val playbackFadeInDurationMs = settingsState.playback.playbackFade.playbackFadeInDurationMs
    val playbackFadeOutDurationMs = settingsState.playback.playbackFade.playbackFadeOutDurationMs
    val playbackCrossfadeInDurationMs = settingsState.playback.playbackFade.playbackCrossfadeInDurationMs
    val playbackCrossfadeOutDurationMs = settingsState.playback.playbackOutput.playbackCrossfadeOutDurationMs
    val playbackVolumeNormalizationEnabled = settingsState.playback.playbackOutput.playbackVolumeNormalizationEnabled
    val playbackHighResolutionOutputEnabled = settingsState.playback.playbackOutput.playbackHighResolutionOutputEnabled
    val playbackVolumeBalance = settingsState.playback.playbackOutput.playbackVolumeBalance
    val keepLastPlaybackProgress = settingsState.playback.playbackContinuity.keepLastPlaybackProgress
    val rememberLongFormPlaybackProgress = settingsState.playback.playbackContinuity.rememberLongFormPlaybackProgress
    val keepPlaybackModeState = settingsState.playback.playbackContinuity.keepPlaybackModeState
    val neteaseAutoSourceSwitch = settingsState.playback.playbackSources.neteaseAutoSourceSwitch
    val neteaseLocalSourceFallback = settingsState.playback.playbackSources.neteaseLocalSourceFallback
    val stopOnBluetoothDisconnect = settingsState.playback.playbackContinuity.stopOnBluetoothDisconnect
    val usbExclusivePlayback = settingsState.playback.playbackOutput.usbExclusivePlayback
    val allowMixedPlayback = settingsState.playback.playbackSources.allowMixedPlayback
    val preemptAudioFocus = settingsState.playback.playbackSources.preemptAudioFocus
    val maxCacheSizeBytes = settingsState.other.storage.maxCacheSizeBytes
    val preferredQuality = settingsState.playback.defaultAudioQuality.preferredQuality
    val youtubePreferredQuality = settingsState.playback.defaultAudioQuality.youtubePreferredQuality
    val biliPreferredQuality = settingsState.playback.defaultAudioQuality.biliPreferredQuality
    val mobileDataFollowDefaultAudioQuality = settingsState.playback.defaultAudioQuality.mobileDataFollowDefaultAudioQuality
    val mobileDataNeteaseAudioQuality = settingsState.playback.mobileAudioQuality.mobileDataNeteaseAudioQuality
    val mobileDataYouTubeAudioQuality = settingsState.playback.mobileAudioQuality.mobileDataYouTubeAudioQuality
    val mobileDataBiliAudioQuality = settingsState.playback.mobileAudioQuality.mobileDataBiliAudioQuality
    val dynamicColor = settingsState.appearance.theme.dynamicColorEnabled
    val seedColorHex = settingsState.appearance.theme.themeSeedColor
    val themePaletteStyle = settingsState.appearance.theme.themePaletteStyleValue
    val themeColorSpec = settingsState.appearance.visualBlur.themeColorSpecValue
    val onDynamicColorChange = bindings.appearanceActions.onDynamicColorChange
    val onQualityChange = bindings.qualityActions.onQualityChange
    val onYouTubeQualityChange = bindings.qualityActions.onYouTubeQualityChange
    val onBiliQualityChange = bindings.qualityActions.onBiliQualityChange
    val onMobileDataFollowDefaultAudioQualityChange = bindings.qualityActions.onMobileDataFollowDefaultAudioQualityChange
    val onMobileDataNeteaseAudioQualityChange = bindings.qualityActions.onMobileDataNeteaseAudioQualityChange
    val onMobileDataYouTubeAudioQualityChange = bindings.qualityActions.onMobileDataYouTubeAudioQualityChange
    val onMobileDataBiliAudioQualityChange = bindings.qualityActions.onMobileDataBiliAudioQualityChange
    val onSeedColorChange = bindings.appearanceActions.onSeedColorChange
    val onAddColorToPalette = bindings.appearanceActions.onAddColorToPalette
    val onRemoveColorFromPalette = bindings.appearanceActions.onRemoveColorFromPalette
    val onThemePaletteStyleChange = bindings.appearanceActions.onThemePaletteStyleChange
    val onThemeColorSpecChange = bindings.appearanceActions.onThemeColorSpecChange
    val onDevModeChange = bindings.appearanceActions.onDevModeChange
    val onLyricBlurEnabledChange = bindings.lyricsActions.onLyricBlurEnabledChange
    val onLyricBlurAmountChange = bindings.lyricsActions.onLyricBlurAmountChange
    val onCloudMusicLyricDefaultOffsetMsChange = bindings.lyricsActions.onCloudMusicLyricDefaultOffsetMsChange
    val onQqMusicLyricDefaultOffsetMsChange = bindings.lyricsActions.onQqMusicLyricDefaultOffsetMsChange
    val onKugouLyricDefaultOffsetMsChange = bindings.lyricsActions.onKugouLyricDefaultOffsetMsChange
    val onLrclibLyricDefaultOffsetMsChange = bindings.lyricsActions.onLrclibLyricDefaultOffsetMsChange
    val onAmllTtmlLyricDefaultOffsetMsChange = bindings.lyricsActions.onAmllTtmlLyricDefaultOffsetMsChange
    val onResetAllLyricDefaultOffsets = bindings.lyricsActions.onResetAllLyricDefaultOffsets
    val onFloatingLyricsPreferencesChange = bindings.lyricsActions.onFloatingLyricsPreferencesChange
    val onAdvancedBlurEnabledChange = bindings.appearanceActions.onAdvancedBlurEnabledChange
    val onEnhancedAdvancedBlurEnabledChange = bindings.appearanceActions.onEnhancedAdvancedBlurEnabledChange
    val onEnhancedAdvancedBlurRadiusDpChange = bindings.appearanceActions.onEnhancedAdvancedBlurRadiusDpChange
    val onAdvancedBlurQualityChange = bindings.appearanceActions.onAdvancedBlurQualityChange
    val onNowPlayingAudioReactiveEnabledChange = bindings.appearanceActions.onNowPlayingAudioReactiveEnabledChange
    val onNowPlayingDynamicBackgroundEnabledChange = bindings.appearanceActions.onNowPlayingDynamicBackgroundEnabledChange
    val onNowPlayingCoverBlurBackgroundEnabledChange = bindings.appearanceActions.onNowPlayingCoverBlurBackgroundEnabledChange
    val onNowPlayingCoverBlurAmountChange = bindings.appearanceActions.onNowPlayingCoverBlurAmountChange
    val onNowPlayingCoverBlurDarkenChange = bindings.appearanceActions.onNowPlayingCoverBlurDarkenChange
    val onLyricFontScaleChange = bindings.lyricsActions.onLyricFontScaleChange
    val onUiDensityScaleChange = bindings.appearanceActions.onUiDensityScaleChange
    val onBypassProxyChange = bindings.storageActions.onBypassProxyChange
    val onBackgroundImageChange = bindings.appearanceActions.onBackgroundImageChange
    val onDownloadDirectoryUriChange = bindings.storageActions.onDownloadDirectoryUriChange
    val onDownloadFileNameTemplateChange = bindings.storageActions.onDownloadFileNameTemplateChange
    val onBackgroundImageBlurChange = bindings.appearanceActions.onBackgroundImageBlurChange
    val onBackgroundImageBlurChangeFinished = bindings.appearanceActions.onBackgroundImageBlurChangeFinished
    val onBackgroundImageAlphaChange = bindings.appearanceActions.onBackgroundImageAlphaChange
    val onBackgroundImageAlphaChangeFinished = bindings.appearanceActions.onBackgroundImageAlphaChangeFinished
    val onDefaultStartDestinationChange = bindings.homeActions.onDefaultStartDestinationChange
    val onShowHomeContinueCardChange = bindings.homeActions.onShowHomeContinueCardChange
    val onShowHomeTrendingCardChange = bindings.homeActions.onShowHomeTrendingCardChange
    val onShowHomeRadarCardChange = bindings.homeActions.onShowHomeRadarCardChange
    val onShowHomeRecommendedCardChange = bindings.homeActions.onShowHomeRecommendedCardChange
    val onPlaybackFadeInChange = bindings.playbackActions.onPlaybackFadeInChange
    val onPlaybackCrossfadeNextChange = bindings.playbackActions.onPlaybackCrossfadeNextChange
    val onSleepTimerFinishCurrentOnExpiryChange = bindings.playbackActions.onSleepTimerFinishCurrentOnExpiryChange
    val onPlaybackFadeInDurationMsChange = bindings.playbackActions.onPlaybackFadeInDurationMsChange
    val onPlaybackFadeOutDurationMsChange = bindings.playbackActions.onPlaybackFadeOutDurationMsChange
    val onPlaybackCrossfadeInDurationMsChange = bindings.playbackActions.onPlaybackCrossfadeInDurationMsChange
    val onPlaybackCrossfadeOutDurationMsChange = bindings.playbackActions.onPlaybackCrossfadeOutDurationMsChange
    val onPlaybackVolumeNormalizationEnabledChange = bindings.playbackActions.onPlaybackVolumeNormalizationEnabledChange
    val onPlaybackHighResolutionOutputEnabledChange = bindings.playbackActions.onPlaybackHighResolutionOutputEnabledChange
    val onPlaybackVolumeBalanceChange = bindings.playbackActions.onPlaybackVolumeBalanceChange
    val onKeepLastPlaybackProgressChange = bindings.playbackActions.onKeepLastPlaybackProgressChange
    val onRememberLongFormPlaybackProgressChange = bindings.playbackActions.onRememberLongFormPlaybackProgressChange
    val onKeepPlaybackModeStateChange = bindings.playbackActions.onKeepPlaybackModeStateChange
    val onNeteaseAutoSourceSwitchChange = bindings.playbackActions.onNeteaseAutoSourceSwitchChange
    val onNeteaseLocalSourceFallbackChange = bindings.playbackActions.onNeteaseLocalSourceFallbackChange
    val onStopOnBluetoothDisconnectChange = bindings.playbackActions.onStopOnBluetoothDisconnectChange
    val onUsbExclusivePlaybackChange = bindings.playbackActions.onUsbExclusivePlaybackChange
    val onAllowMixedPlaybackChange = bindings.playbackActions.onAllowMixedPlaybackChange
    val onPreemptAudioFocusChange = bindings.playbackActions.onPreemptAudioFocusChange
    val onMaxCacheSizeBytesChange = bindings.storageActions.onMaxCacheSizeBytesChange
    val onClearCacheClick = bindings.storageActions.onClearCacheClick
    val backgroundImageAlpha = bindings.environment.backgroundImageAlpha
    val coherentFeedbackEnabled = bindings.environment.coherentFeedbackEnabled
    val defaultStartDestination = bindings.environment.defaultStartDestination
    val homeHasRecentUsage = bindings.environment.homeHasRecentUsage
    val isDarkTheme = bindings.environment.isDarkTheme
    val onBeforeLanguageRestart = bindings.environment.onBeforeLanguageRestart
    val onLanguageChanged = bindings.environment.onLanguageChanged
    val onThemeModeRequest = bindings.environment.onThemeModeRequest
    val onThemeToggleRequest = bindings.environment.onThemeToggleRequest
    val themeMode = bindings.environment.themeMode

    var screenState by rememberSaveable { mutableStateOf(SettingsScreenState.Settings) }
    var requestedScreenState by rememberSaveable { mutableStateOf(SettingsScreenState.Settings) }
    val saveableStateHolder = rememberSaveableStateHolder()

    // 保存设置页面的滚动状态，使用正确的Saver
    val listStateSaver: Saver<LazyListState, *> = LazyListState.Saver
    val settingsListState = rememberSaveable(saver = listStateSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val downloadManagerListState = rememberSaveable(saver = listStateSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val downloadProgressListState = rememberSaveable(saver = listStateSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    var pendingSettingsListRestoreIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingSettingsListRestoreOffset by rememberSaveable { mutableIntStateOf(0) }
    val navigationTransition = updateTransition(
        targetState = screenState,
        label = "settings_screen_switch"
    )
    val renderedScreenStates = remember { mutableStateListOf<SettingsScreenState>() }
    val settledRenderedScreenStates = renderedScreenStates.toSet()

    fun captureSettingsListPosition() {
        val position = settingsListState.captureHostScrollPosition()
        pendingSettingsListRestoreIndex = position.index
        pendingSettingsListRestoreOffset = position.offset
    }

    fun requestScreen(target: SettingsScreenState) {
        if (
            requestedScreenState == SettingsScreenState.Settings &&
            target != SettingsScreenState.Settings
        ) {
            captureSettingsListPosition()
        }
        requestedScreenState = target
    }

    LaunchedEffect(
        navigationTransition.currentState,
        navigationTransition.isRunning,
        requestedScreenState,
        screenState,
        settledRenderedScreenStates
    ) {
        if (
            shouldAdvanceSettingsScreenTransition(
                targetState = screenState,
                currentState = navigationTransition.currentState,
                isRunning = navigationTransition.isRunning,
                requestedState = requestedScreenState,
                renderedScreenStates = settledRenderedScreenStates
            )
        ) {
            screenState = screenState.nextTowards(requestedScreenState)
        }
    }

    LaunchedEffect(
        screenState,
        navigationTransition.isRunning,
        pendingSettingsListRestoreIndex
    ) {
        val restoreIndex = pendingSettingsListRestoreIndex ?: return@LaunchedEffect
        if (
            screenState != SettingsScreenState.Settings ||
            navigationTransition.isRunning
        ) {
            return@LaunchedEffect
        }
        settingsListState.restoreHostScrollPosition(
            HostScrollPosition(
                index = restoreIndex,
                offset = pendingSettingsListRestoreOffset
            )
        )
        pendingSettingsListRestoreIndex = null
        pendingSettingsListRestoreOffset = 0
    }

    PredictiveBackHandler(enabled = requestedScreenState != SettingsScreenState.Settings) { progress ->
        try {
            progress.collect { }
            requestScreen(
                when (requestedScreenState) {
                SettingsScreenState.DownloadProgress -> SettingsScreenState.DownloadManager
                SettingsScreenState.DownloadManager -> SettingsScreenState.Settings
                SettingsScreenState.Settings -> SettingsScreenState.Settings
                }
            )
        } catch (_: CancellationException) {
        }
    }

    Surface(color = Color.Transparent) {
        navigationTransition.AnimatedContent(
            transitionSpec = {
                advancedGlassHostNavigationTransition(
                    forward = targetState.navigationDepth > initialState.navigationDepth,
                    coherentFeedbackEnabled = coherentFeedbackEnabled,
                    targetContentZIndex = targetState.navigationDepth.toFloat()
                ).using(SizeTransform(clip = true))
            }
        ) { state ->
            DisposableEffect(state) {
                renderedScreenStates += state
                onDispose {
                    renderedScreenStates.remove(state)
                }
            }
            val sceneMotion = navigationTransition.animateAdvancedGlassSceneMotion(
                sceneState = state,
                coherentFeedbackEnabled = coherentFeedbackEnabled,
                navigationDepth = { item -> item.navigationDepth },
                label = "settings_host_scene"
            )
            renderScene(
                sceneMotion.revealTopFraction,
                sceneMotion.contentTranslationYFraction,
                sceneMotion.contentScale,
                state.navigationDepth
            ) {
                    saveableStateHolder.SaveableStateProvider(state.saveableKey()) {
                        val settingsPage: @Composable () -> Unit = {
                            SettingsScreen(
                            listState = settingsListState,
                            dynamicColor = dynamicColor,
                            onDynamicColorChange = onDynamicColorChange,
                            isDarkTheme = isDarkTheme,
                            themeMode = themeMode,
                            onThemeToggleRequest = onThemeToggleRequest,
                            onThemeModeRequest = onThemeModeRequest,
                            preferredQuality = preferredQuality,
                            onQualityChange = onQualityChange,
                            youtubePreferredQuality = youtubePreferredQuality,
                            onYouTubeQualityChange = onYouTubeQualityChange,
                            biliPreferredQuality = biliPreferredQuality,
                            onBiliQualityChange = onBiliQualityChange,
                            mobileDataFollowDefaultAudioQuality =
                                mobileDataFollowDefaultAudioQuality,
                            onMobileDataFollowDefaultAudioQualityChange =
                                onMobileDataFollowDefaultAudioQualityChange,
                            mobileDataNeteaseAudioQuality = mobileDataNeteaseAudioQuality,
                            onMobileDataNeteaseAudioQualityChange =
                                onMobileDataNeteaseAudioQualityChange,
                            mobileDataYouTubeAudioQuality = mobileDataYouTubeAudioQuality,
                            onMobileDataYouTubeAudioQualityChange =
                                onMobileDataYouTubeAudioQualityChange,
                            mobileDataBiliAudioQuality = mobileDataBiliAudioQuality,
                            onMobileDataBiliAudioQualityChange =
                                onMobileDataBiliAudioQualityChange,
                            seedColorHex = seedColorHex,
                            onSeedColorChange = onSeedColorChange,
                            themeColorPalette = themeColorPalette,
                            onAddColorToPalette = onAddColorToPalette,
                            onRemoveColorFromPalette = onRemoveColorFromPalette,
                            themePaletteStyle = themePaletteStyle,
                            onThemePaletteStyleChange = onThemePaletteStyleChange,
                            themeColorSpec = themeColorSpec,
                            onThemeColorSpecChange = onThemeColorSpecChange,
                            devModeEnabled = devModeEnabled,
                            onDevModeChange = onDevModeChange,
                            lyricBlurEnabled = lyricBlurEnabled,
                            onLyricBlurEnabledChange = onLyricBlurEnabledChange,
                            lyricBlurAmount = lyricBlurAmount,
                            onLyricBlurAmountChange = onLyricBlurAmountChange,
                            cloudMusicLyricDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
                            onCloudMusicLyricDefaultOffsetMsChange = onCloudMusicLyricDefaultOffsetMsChange,
                            qqMusicLyricDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
                            onQqMusicLyricDefaultOffsetMsChange = onQqMusicLyricDefaultOffsetMsChange,
                            kugouLyricDefaultOffsetMs = kugouLyricDefaultOffsetMs,
                            onKugouLyricDefaultOffsetMsChange = onKugouLyricDefaultOffsetMsChange,
                            lrclibLyricDefaultOffsetMs = lrclibLyricDefaultOffsetMs,
                            onLrclibLyricDefaultOffsetMsChange = onLrclibLyricDefaultOffsetMsChange,
                            amllTtmlLyricDefaultOffsetMs = amllTtmlLyricDefaultOffsetMs,
                            onAmllTtmlLyricDefaultOffsetMsChange = onAmllTtmlLyricDefaultOffsetMsChange,
                            onResetAllLyricDefaultOffsets = onResetAllLyricDefaultOffsets,
                            floatingLyricsPreferences = floatingLyricsPreferences,
                            onFloatingLyricsPreferencesChange = onFloatingLyricsPreferencesChange,
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
                            onNowPlayingAudioReactiveEnabledChange = onNowPlayingAudioReactiveEnabledChange,
                            nowPlayingDynamicBackgroundEnabled = nowPlayingDynamicBackgroundEnabled,
                            onNowPlayingDynamicBackgroundEnabledChange = onNowPlayingDynamicBackgroundEnabledChange,
                            nowPlayingCoverBlurBackgroundEnabled = nowPlayingCoverBlurBackgroundEnabled,
                            onNowPlayingCoverBlurBackgroundEnabledChange = onNowPlayingCoverBlurBackgroundEnabledChange,
                            nowPlayingCoverBlurAmount = nowPlayingCoverBlurAmount,
                            onNowPlayingCoverBlurAmountChange = onNowPlayingCoverBlurAmountChange,
                            nowPlayingCoverBlurDarken = nowPlayingCoverBlurDarken,
                            onNowPlayingCoverBlurDarkenChange = onNowPlayingCoverBlurDarkenChange,
                            lyricFontScales = lyricFontScales,
                            onLyricFontScaleChange = onLyricFontScaleChange,
                            uiDensityScale = uiDensityScale,
                            onUiDensityScaleChange = onUiDensityScaleChange,
                            bypassProxy = bypassProxy,
                            onBypassProxyChange = onBypassProxyChange,
                            backgroundImageUri = backgroundImageUri,
                            onBackgroundImageChange = onBackgroundImageChange,
                            downloadDirectoryUri = downloadDirectoryUri,
                            downloadFileNameTemplate = downloadFileNameTemplate,
                            onDownloadDirectoryUriChange = onDownloadDirectoryUriChange,
                            onDownloadFileNameTemplateChange = onDownloadFileNameTemplateChange,
                            backgroundImageBlur = backgroundImageBlur,
                            onBackgroundImageBlurChange = onBackgroundImageBlurChange,
                            onBackgroundImageBlurChangeFinished = onBackgroundImageBlurChangeFinished,
                            backgroundImageAlpha = backgroundImageAlpha,
                            onBackgroundImageAlphaChange = onBackgroundImageAlphaChange,
                            onBackgroundImageAlphaChangeFinished = onBackgroundImageAlphaChangeFinished,
                            defaultStartDestination = defaultStartDestination,
                            onDefaultStartDestinationChange = onDefaultStartDestinationChange,
                            showHomeContinueCard = showHomeContinueCard,
                            onShowHomeContinueCardChange = onShowHomeContinueCardChange,
                            showHomeTrendingCard = showHomeTrendingCard,
                            onShowHomeTrendingCardChange = onShowHomeTrendingCardChange,
                            showHomeRadarCard = showHomeRadarCard,
                            onShowHomeRadarCardChange = onShowHomeRadarCardChange,
                            showHomeRecommendedCard = showHomeRecommendedCard,
                            onShowHomeRecommendedCardChange = onShowHomeRecommendedCardChange,
                            homeHasRecentUsage = homeHasRecentUsage,
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
                            onPlaybackCrossfadeInDurationMsChange = onPlaybackCrossfadeInDurationMsChange,
                            playbackCrossfadeOutDurationMs = playbackCrossfadeOutDurationMs,
                            onPlaybackCrossfadeOutDurationMsChange = onPlaybackCrossfadeOutDurationMsChange,
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
                            neteaseAutoSourceSwitch = neteaseAutoSourceSwitch,
                            onNeteaseAutoSourceSwitchChange = onNeteaseAutoSourceSwitchChange,
                            neteaseLocalSourceFallback = neteaseLocalSourceFallback,
                            onNeteaseLocalSourceFallbackChange = onNeteaseLocalSourceFallbackChange,
                            stopOnBluetoothDisconnect = stopOnBluetoothDisconnect,
                            onStopOnBluetoothDisconnectChange = onStopOnBluetoothDisconnectChange,
                            usbExclusivePlayback = usbExclusivePlayback,
                            onUsbExclusivePlaybackChange = onUsbExclusivePlaybackChange,
                            allowMixedPlayback = allowMixedPlayback,
                            onAllowMixedPlaybackChange = onAllowMixedPlaybackChange,
                            preemptAudioFocus = preemptAudioFocus,
                            onPreemptAudioFocusChange = onPreemptAudioFocusChange,
                            onNavigateToDownloadManager = {
                                requestScreen(SettingsScreenState.DownloadManager)
                            },
                            maxCacheSizeBytes = maxCacheSizeBytes,
                            onMaxCacheSizeBytesChange = onMaxCacheSizeBytesChange,
                            onClearCacheClick = onClearCacheClick,
                            onBeforeLanguageRestart = onBeforeLanguageRestart,
                            onLanguageChanged = onLanguageChanged
                            )
                        }
                        val downloadManagerPage: @Composable () -> Unit = {
                            DownloadManagerScreen(
                                onBack = { requestScreen(SettingsScreenState.Settings) },
                                onOpenDownloadProgress = {
                                    requestScreen(SettingsScreenState.DownloadProgress)
                                },
                                listState = downloadManagerListState
                            )
                        }
                        val downloadProgressPage: @Composable () -> Unit = {
                            DownloadProgressScreen(
                                onBack = { requestScreen(SettingsScreenState.DownloadManager) },
                                listState = downloadProgressListState
                            )
                        }
                        selectSettingsHostPage(
                            state, settingsPage, downloadManagerPage, downloadProgressPage
                        ).invoke()
                    }
            }
        }
    }
}
