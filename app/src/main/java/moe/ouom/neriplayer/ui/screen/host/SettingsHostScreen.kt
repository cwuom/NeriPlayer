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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import moe.ouom.neriplayer.ui.screen.DownloadManagerScreen
import moe.ouom.neriplayer.ui.screen.tab.SettingsScreen

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
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
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
) {
    var showDownloadManager by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showDownloadManager) { showDownloadManager = false }

    Surface(color = Color.Transparent) {
        AnimatedContent(
            targetState = showDownloadManager,
            label = "settings_download_manager_switch",
            transitionSpec = {
                if (initialState == false && targetState == true) {
                    (slideInVertically(animationSpec = tween(220)) { it } + fadeIn()) togetherWith
                            (fadeOut(animationSpec = tween(160)))
                } else {
                    (slideInVertically(animationSpec = tween(200)) { full -> -full / 6 } + fadeIn()) togetherWith
                            (slideOutVertically(animationSpec = tween(240)) { it } + fadeOut())
                }.using(SizeTransform(clip = false))
            }
        ) { current ->
            if (!current) {
                SettingsScreen(
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
                    devModeEnabled = devModeEnabled,
                    onDevModeChange = onDevModeChange,
                    lyricBlurEnabled = lyricBlurEnabled,
                    onLyricBlurEnabledChange = onLyricBlurEnabledChange,
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
                    onNavigateToDownloadManager = { showDownloadManager = true }
                )
            } else {
                DownloadManagerScreen(
                    onBack = { showDownloadManager = false }
                )
            }
        }
    }
}
