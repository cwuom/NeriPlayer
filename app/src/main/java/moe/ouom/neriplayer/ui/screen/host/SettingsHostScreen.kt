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

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.data.storage.StorageCacheClearOptions
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
    requestedState: SettingsScreenState
): Boolean = !isRunning && currentState == targetState && targetState != requestedState

@Composable
fun SettingsHostScreen(
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
    seedColorHex: String,
    onSeedColorChange: (String) -> Unit,
    themeColorPalette: List<String>,
    onAddColorToPalette: (String) -> Unit,
    onRemoveColorFromPalette: (String) -> Unit,
    themePaletteStyle: String,
    onThemePaletteStyleChange: (String) -> Unit,
    themeColorSpec: String,
    onThemeColorSpecChange: (String) -> Unit,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
    lyricBlurAmount: Float,
    onLyricBlurAmountChange: (Float) -> Unit,
    cloudMusicLyricDefaultOffsetMs: Long,
    onCloudMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    qqMusicLyricDefaultOffsetMs: Long,
    onQqMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    floatingLyricsPreferences: FloatingLyricsPreferences,
    onFloatingLyricsPreferencesChange: (FloatingLyricsPreferences) -> Unit,
    advancedBlurEnabled: Boolean,
    onAdvancedBlurEnabledChange: (Boolean) -> Unit,
    enhancedAdvancedBlurEnabled: Boolean,
    onEnhancedAdvancedBlurEnabledChange: (Boolean) -> Unit,
    enhancedAdvancedBlurRadiusDp: Float,
    onEnhancedAdvancedBlurRadiusDpChange: (Float) -> Unit,
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
    keepPlaybackModeState: Boolean,
    onKeepPlaybackModeStateChange: (Boolean) -> Unit,
    neteaseAutoSourceSwitch: Boolean,
    onNeteaseAutoSourceSwitchChange: (Boolean) -> Unit,
    stopOnBluetoothDisconnect: Boolean,
    onStopOnBluetoothDisconnectChange: (Boolean) -> Unit,
    usbExclusivePlayback: Boolean,
    onUsbExclusivePlaybackChange: (Boolean) -> Unit,
    allowMixedPlayback: Boolean,
    onAllowMixedPlaybackChange: (Boolean) -> Unit,
    preemptAudioFocus: Boolean,
    onPreemptAudioFocusChange: (Boolean) -> Unit,
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onClearCacheClick: (StorageCacheClearOptions) -> Unit,
    onBeforeLanguageRestart: () -> Unit = {},
    coherentFeedbackEnabled: Boolean = false,
    renderScene: @Composable (
        revealTopFraction: Float,
        contentTranslationYFraction: Float,
        contentScale: Float,
        content: @Composable () -> Unit
    ) -> Unit = { _, _, _, content ->
        content()
    },
) {
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
        if (
            shouldAdvanceSettingsScreenTransition(
                targetState = screenState,
                currentState = navigationTransition.currentState,
                isRunning = navigationTransition.isRunning,
                requestedState = requestedScreenState
            )
        ) {
            screenState = screenState.nextTowards(requestedScreenState)
        }
    }

    LaunchedEffect(
        navigationTransition.currentState,
        navigationTransition.isRunning,
        requestedScreenState,
        screenState
    ) {
        if (
            shouldAdvanceSettingsScreenTransition(
                targetState = screenState,
                currentState = navigationTransition.currentState,
                isRunning = navigationTransition.isRunning,
                requestedState = requestedScreenState
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
                    coherentFeedbackEnabled = coherentFeedbackEnabled
                ).using(SizeTransform(clip = true))
            }
        ) { state ->
            val sceneMotion = navigationTransition.animateAdvancedGlassSceneMotion(
                sceneState = state,
                coherentFeedbackEnabled = coherentFeedbackEnabled,
                navigationDepth = { item -> item.navigationDepth },
                label = "settings_host_scene"
            )
            renderScene(
                sceneMotion.revealTopFraction,
                sceneMotion.contentTranslationYFraction,
                sceneMotion.contentScale
            ) {
                    saveableStateHolder.SaveableStateProvider(state.saveableKey()) {
                        when (state) {
                            SettingsScreenState.Settings -> {
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
                            lyricFontScale = lyricFontScale,
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
                            keepPlaybackModeState = keepPlaybackModeState,
                            onKeepPlaybackModeStateChange = onKeepPlaybackModeStateChange,
                            neteaseAutoSourceSwitch = neteaseAutoSourceSwitch,
                            onNeteaseAutoSourceSwitchChange = onNeteaseAutoSourceSwitchChange,
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
                            onBeforeLanguageRestart = onBeforeLanguageRestart
                                )
                            }

                            SettingsScreenState.DownloadManager -> {
                                DownloadManagerScreen(
                                    onBack = { requestScreen(SettingsScreenState.Settings) },
                                    onOpenDownloadProgress = {
                                        requestScreen(SettingsScreenState.DownloadProgress)
                                    },
                                    listState = downloadManagerListState
                                )
                            }

                            SettingsScreenState.DownloadProgress -> {
                                DownloadProgressScreen(
                                    onBack = {
                                        requestScreen(SettingsScreenState.DownloadManager)
                                    },
                                    listState = downloadProgressListState
                                )
                            }
                        }
                    }
            }
        }
    }
}
