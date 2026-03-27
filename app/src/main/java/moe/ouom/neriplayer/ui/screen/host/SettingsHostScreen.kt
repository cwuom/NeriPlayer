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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.ui.screen.DownloadManagerScreen
import moe.ouom.neriplayer.ui.screen.DownloadProgressScreen
import moe.ouom.neriplayer.ui.screen.tab.SettingsScreen

private sealed class SettingsScreenState {
    data object Settings : SettingsScreenState()
    data object DownloadManager : SettingsScreenState()
    data object DownloadProgress : SettingsScreenState()
}

@Composable
fun SettingsHostScreen(
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
    seedColorHex: String,
    onSeedColorChange: (String) -> Unit,
    themeColorPalette: List<String>,
    onAddColorToPalette: (String) -> Unit,
    onRemoveColorFromPalette: (String) -> Unit,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
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
    downloadDirectoryUri: String?,
    onDownloadDirectoryUriChange: (String?) -> Unit,
    backgroundImageBlur: Float,
    onBackgroundImageBlurChange: (Float) -> Unit,
    onBackgroundImageBlurChangeFinished: (Float) -> Unit,
    backgroundImageAlpha: Float,
    onBackgroundImageAlphaChange: (Float) -> Unit,
    onBackgroundImageAlphaChangeFinished: (Float) -> Unit,
    hapticFeedbackEnabled: Boolean,
    onHapticFeedbackEnabledChange: (Boolean) -> Unit,
    showCoverSourceBadge: Boolean,
    onShowCoverSourceBadgeChange: (Boolean) -> Unit,
    nowPlayingToolbarDockEnabled: Boolean,
    onNowPlayingToolbarDockEnabledChange: (Boolean) -> Unit,
    showNowPlayingProgressQualitySwitch: Boolean,
    onShowNowPlayingProgressQualitySwitchChange: (Boolean) -> Unit,
    showNowPlayingProgressAudioCodec: Boolean,
    onShowNowPlayingProgressAudioCodecChange: (Boolean) -> Unit,
    showNowPlayingProgressAudioSpec: Boolean,
    onShowNowPlayingProgressAudioSpecChange: (Boolean) -> Unit,
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
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onClearCacheClick: (clearAudio: Boolean, clearImage: Boolean) -> Unit,
    onBeforeLanguageRestart: () -> Unit = {},
) {
    var screenState by remember { mutableStateOf<SettingsScreenState>(SettingsScreenState.Settings) }

    // 保存设置页面的滚动状态，使用正确的Saver
    val settingsListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val settingsListState = rememberSaveable(saver = settingsListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }

    PredictiveBackHandler(enabled = screenState != SettingsScreenState.Settings) { progress ->
        try {
            progress.collect { }
            screenState = when (screenState) {
                SettingsScreenState.DownloadProgress -> SettingsScreenState.DownloadManager
                SettingsScreenState.DownloadManager -> SettingsScreenState.Settings
                SettingsScreenState.Settings -> SettingsScreenState.Settings
            }
        } catch (_: CancellationException) {
        }
    }

    Surface(color = Color.Transparent) {
        AnimatedContent(
            targetState = screenState,
            label = "settings_screen_switch",
            transitionSpec = {
                when {
                    initialState == SettingsScreenState.Settings && targetState != SettingsScreenState.Settings -> {
                        (slideInVertically(animationSpec = tween(220)) { it } + fadeIn()) togetherWith
                                (fadeOut(animationSpec = tween(160)))
                    }
                    initialState != SettingsScreenState.Settings && targetState == SettingsScreenState.Settings -> {
                        (slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()) togetherWith
                                (slideOutVertically(animationSpec = tween(240)) { it } + fadeOut())
                    }
                    else -> {
                        (slideInVertically(animationSpec = tween(220)) { it } + fadeIn()) togetherWith
                                (slideOutVertically(animationSpec = tween(220)) { -it } + fadeOut())
                    }
                }.using(SizeTransform(clip = false))
            }
        ) { state ->
            when (state) {
                SettingsScreenState.Settings -> {
                SettingsScreen(
                    listState = settingsListState,
                    dynamicColor = dynamicColor,
                    onDynamicColorChange = onDynamicColorChange,
                    isDarkTheme = isDarkTheme,
                    onThemeToggleRequest = onThemeToggleRequest,
                    preferredQuality = preferredQuality,
                    onQualityChange = onQualityChange,
                    youtubePreferredQuality = youtubePreferredQuality,
                    onYouTubeQualityChange = onYouTubeQualityChange,
                    biliPreferredQuality = biliPreferredQuality,
                    onBiliQualityChange = onBiliQualityChange,
                    seedColorHex = seedColorHex,
                    onSeedColorChange = onSeedColorChange,
                    themeColorPalette = themeColorPalette,
                    onAddColorToPalette = onAddColorToPalette,
                    onRemoveColorFromPalette = onRemoveColorFromPalette,
                    devModeEnabled = devModeEnabled,
                    onDevModeChange = onDevModeChange,
                    lyricBlurEnabled = lyricBlurEnabled,
                    onLyricBlurEnabledChange = onLyricBlurEnabledChange,
                    lyricBlurAmount = lyricBlurAmount,
                    onLyricBlurAmountChange = onLyricBlurAmountChange,
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
                    lyricFontScale = lyricFontScale,
                    onLyricFontScaleChange = onLyricFontScaleChange,
                    uiDensityScale = uiDensityScale,
                    onUiDensityScaleChange = onUiDensityScaleChange,
                    bypassProxy = bypassProxy,
                    onBypassProxyChange = onBypassProxyChange,
                    backgroundImageUri = backgroundImageUri,
                    onBackgroundImageChange = onBackgroundImageChange,
                    downloadDirectoryUri = downloadDirectoryUri,
                    onDownloadDirectoryUriChange = onDownloadDirectoryUriChange,
                    backgroundImageBlur = backgroundImageBlur,
                    onBackgroundImageBlurChange = onBackgroundImageBlurChange,
                    onBackgroundImageBlurChangeFinished = onBackgroundImageBlurChangeFinished,
                    backgroundImageAlpha = backgroundImageAlpha,
                    onBackgroundImageAlphaChange = onBackgroundImageAlphaChange,
                    onBackgroundImageAlphaChangeFinished = onBackgroundImageAlphaChangeFinished,
                    hapticFeedbackEnabled = hapticFeedbackEnabled,
                    onHapticFeedbackEnabledChange = onHapticFeedbackEnabledChange,
                    showCoverSourceBadge = showCoverSourceBadge,
                    onShowCoverSourceBadgeChange = onShowCoverSourceBadgeChange,
                    nowPlayingToolbarDockEnabled = nowPlayingToolbarDockEnabled,
                    onNowPlayingToolbarDockEnabledChange = onNowPlayingToolbarDockEnabledChange,
                    showNowPlayingProgressQualitySwitch = showNowPlayingProgressQualitySwitch,
                    onShowNowPlayingProgressQualitySwitchChange = onShowNowPlayingProgressQualitySwitchChange,
                    showNowPlayingProgressAudioCodec = showNowPlayingProgressAudioCodec,
                    onShowNowPlayingProgressAudioCodecChange = onShowNowPlayingProgressAudioCodecChange,
                    showNowPlayingProgressAudioSpec = showNowPlayingProgressAudioSpec,
                    onShowNowPlayingProgressAudioSpecChange = onShowNowPlayingProgressAudioSpecChange,
                    silentGitHubSyncFailure = silentGitHubSyncFailure,
                     onSilentGitHubSyncFailureChange = onSilentGitHubSyncFailureChange,
                     showLyricTranslation = showLyricTranslation,
                     onShowLyricTranslationChange = onShowLyricTranslationChange,
                     defaultStartDestination = defaultStartDestination,
                     onDefaultStartDestinationChange = onDefaultStartDestinationChange,
                     autoShowKeyboard = autoShowKeyboard,
                     onAutoShowKeyboardChange = onAutoShowKeyboardChange,
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
                    onAllowMixedPlaybackChange = onAllowMixedPlaybackChange,
                     onNavigateToDownloadManager = { screenState = SettingsScreenState.DownloadManager },
                     maxCacheSizeBytes = maxCacheSizeBytes,
                     onMaxCacheSizeBytesChange = onMaxCacheSizeBytesChange,
                     onClearCacheClick = onClearCacheClick,
                    onBeforeLanguageRestart = onBeforeLanguageRestart
                )
                }
                SettingsScreenState.DownloadManager -> {
                    DownloadManagerScreen(
                        onBack = { screenState = SettingsScreenState.Settings },
                        onOpenDownloadProgress = { screenState = SettingsScreenState.DownloadProgress }
                    )
                }
                SettingsScreenState.DownloadProgress -> {
                    DownloadProgressScreen(
                        onBack = { screenState = SettingsScreenState.DownloadManager }
                    )
                }
            }
        }
    }
}
