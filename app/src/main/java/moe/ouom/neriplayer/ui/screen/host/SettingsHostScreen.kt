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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
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
    forceDark: Boolean,
    onForceDarkChange: (Boolean) -> Unit,
    preferredQuality: String,
    onQualityChange: (String) -> Unit,
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
    showLyricTranslation: Boolean,
    onShowLyricTranslationChange: (Boolean) -> Unit,
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onClearCacheClick: (clearAudio: Boolean, clearImage: Boolean) -> Unit,
) {
    var screenState by remember { mutableStateOf<SettingsScreenState>(SettingsScreenState.Settings) }

    // 保存设置页面的滚动状态，使用正确的Saver
    val settingsListSaver: Saver<LazyListState, *> = LazyListState.Saver
    val settingsListState = rememberSaveable(saver = settingsListSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }

    BackHandler(enabled = screenState != SettingsScreenState.Settings) {
        screenState = when (screenState) {
            SettingsScreenState.DownloadProgress -> SettingsScreenState.DownloadManager
            SettingsScreenState.DownloadManager -> SettingsScreenState.Settings
            SettingsScreenState.Settings -> SettingsScreenState.Settings
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
                    forceDark = forceDark,
                    onForceDarkChange = onForceDarkChange,
                    preferredQuality = preferredQuality,
                    onQualityChange = onQualityChange,
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
                    lyricFontScale = lyricFontScale,
                    onLyricFontScaleChange = onLyricFontScaleChange,
                    uiDensityScale = uiDensityScale,
                    onUiDensityScaleChange = onUiDensityScaleChange,
                    bypassProxy = bypassProxy,
                    onBypassProxyChange = onBypassProxyChange,
                    backgroundImageUri = backgroundImageUri,
                    onBackgroundImageChange = onBackgroundImageChange,
                    backgroundImageBlur = backgroundImageBlur,
                    onBackgroundImageBlurChange = onBackgroundImageBlurChange,
                    backgroundImageAlpha = backgroundImageAlpha,
                    onBackgroundImageAlphaChange = onBackgroundImageAlphaChange,
                    hapticFeedbackEnabled = hapticFeedbackEnabled,
                    onHapticFeedbackEnabledChange = onHapticFeedbackEnabledChange,
                    showLyricTranslation = showLyricTranslation,
                    onShowLyricTranslationChange = onShowLyricTranslationChange,
                    onNavigateToDownloadManager = { screenState = SettingsScreenState.DownloadManager },
                    maxCacheSizeBytes = maxCacheSizeBytes,
                    onMaxCacheSizeBytesChange = onMaxCacheSizeBytesChange,
                    onClearCacheClick = onClearCacheClick
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
