package moe.ouom.neriplayer.ui.screen.tab.settings.component

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsMotionSection
 * Updated: 2026/3/23
 */

import android.os.Build
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.generated.AutoSettingInfo
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchItems
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch

@Composable
internal fun SettingsMotionSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
    autoSettingsRepository: AutoSettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
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
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
    lyricBlurAmount: Float,
    onLyricBlurAmountChange: (Float) -> Unit
) {
    if (showHeader) {
        ExpandableHeader(
            icon = Icons.Outlined.Bolt,
            title = stringResource(R.string.settings_motion),
            subtitleCollapsed = stringResource(R.string.settings_motion_expand),
            subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
            expanded = expanded,
            onToggle = { onExpandedChange(!expanded) },
            arrowRotation = arrowRotation
        )
    }

    LazyAnimatedVisibility(
        visible = expanded || !showHeader,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(
                    start = if (showHeader) 16.dp else 0.dp,
                    end = if (showHeader) 8.dp else 0.dp,
                    bottom = if (showHeader) 8.dp else 0.dp
                )
        ) {
            val coverBlurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val advancedBlurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val dynamicBackgroundApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

            LaunchedEffect(
                coverBlurAvailable,
                dynamicBackgroundApiAvailable,
                advancedBlurAvailable
            ) {
                if (!coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled) {
                    onNowPlayingCoverBlurBackgroundEnabledChange(false)
                }
                if (!advancedBlurAvailable && advancedBlurEnabled) {
                    onAdvancedBlurEnabledChange(false)
                }
                if (!dynamicBackgroundApiAvailable) {
                    if (nowPlayingDynamicBackgroundEnabled) {
                        onNowPlayingDynamicBackgroundEnabledChange(false)
                    }
                    if (nowPlayingAudioReactiveEnabled) {
                        onNowPlayingAudioReactiveEnabledChange(false)
                    }
                }
            }

            val dynamicBackgroundAvailable =
                dynamicBackgroundApiAvailable && !nowPlayingCoverBlurBackgroundEnabled
            val audioReactiveAvailable =
                dynamicBackgroundApiAvailable &&
                    nowPlayingDynamicBackgroundEnabled &&
                    dynamicBackgroundAvailable

            val safeCoverBlurToggle: (Boolean) -> Unit = { enabled ->
                if (coverBlurAvailable) {
                    onNowPlayingCoverBlurBackgroundEnabledChange(enabled)
                    if (enabled) {
                        if (nowPlayingDynamicBackgroundEnabled) {
                            onNowPlayingDynamicBackgroundEnabledChange(false)
                        }
                        if (nowPlayingAudioReactiveEnabled) {
                            onNowPlayingAudioReactiveEnabledChange(false)
                        }
                    }
                }
            }
            val onDynamicBackgroundToggle: (Boolean) -> Unit = { enabled ->
                if (dynamicBackgroundAvailable) {
                    onNowPlayingDynamicBackgroundEnabledChange(enabled)
                    if (!enabled && nowPlayingAudioReactiveEnabled) {
                        onNowPlayingAudioReactiveEnabledChange(false)
                    }
                }
            }

            AutoSettingsSwitchItems(
                repository = autoSettingsRepository,
                scope = scope,
                sectionScope = AutoSettingsScopes.motion
            )

            MotionSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.ADVANCED_BLUR_ENABLED),
                disabledSuffix = stringResource(R.string.settings_android12_required),
                checked = advancedBlurAvailable && advancedBlurEnabled,
                enabled = advancedBlurAvailable,
                alpha = if (advancedBlurAvailable) 1f else 0.5f,
                onToggle = {
                    if (advancedBlurAvailable) {
                        onAdvancedBlurEnabledChange(!advancedBlurEnabled)
                    }
                },
                onCheckedChange = {
                    if (advancedBlurAvailable) {
                        onAdvancedBlurEnabledChange(it)
                    }
                }
            )

            MotionSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_COVER_BLUR_BACKGROUND_ENABLED),
                disabledSuffix = stringResource(R.string.settings_android12_required),
                checked = coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled,
                enabled = coverBlurAvailable,
                alpha = if (coverBlurAvailable) 1f else 0.5f,
                onToggle = {
                    if (coverBlurAvailable) {
                        safeCoverBlurToggle(!nowPlayingCoverBlurBackgroundEnabled)
                    }
                },
                onCheckedChange = safeCoverBlurToggle
            )

            LazyAnimatedVisibility(visible = coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled) {
                Column(Modifier.fillMaxWidth()) {
                    SnappedFloatSliderListItem(
                        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_COVER_BLUR_AMOUNT),
                        value = nowPlayingCoverBlurAmount.coerceIn(0f, 500f),
                        valueText = { current ->
                            stringResource(R.string.settings_nowplaying_cover_blur_value, current)
                        },
                        valueRange = 0f..500f,
                        steps = (500f / 5f).toInt().coerceAtLeast(1) - 1,
                        snapStep = 5f,
                        onValueCommitted = { onNowPlayingCoverBlurAmountChange(it.coerceIn(0f, 500f)) }
                    )

                    Spacer(Modifier.height(4.dp))

                    SnappedFloatSliderListItem(
                        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_COVER_BLUR_DARKEN),
                        value = nowPlayingCoverBlurDarken.coerceIn(0f, 0.8f),
                        valueText = { current ->
                            stringResource(R.string.settings_nowplaying_cover_blur_darken_value, current)
                        },
                        valueRange = 0f..0.8f,
                        steps = 15,
                        onValueCommitted = { onNowPlayingCoverBlurDarkenChange(it.coerceIn(0f, 0.8f)) }
                    )
                }
            }

            MotionSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_AUDIO_REACTIVE_ENABLED),
                disabledSuffix = stringResource(R.string.settings_android13_required),
                checked = audioReactiveAvailable && nowPlayingAudioReactiveEnabled,
                enabled = audioReactiveAvailable,
                alpha = if (audioReactiveAvailable) 1f else 0.5f,
                onToggle = {
                    if (audioReactiveAvailable) {
                        onNowPlayingAudioReactiveEnabledChange(!nowPlayingAudioReactiveEnabled)
                    }
                },
                onCheckedChange = {
                    if (audioReactiveAvailable) {
                        onNowPlayingAudioReactiveEnabledChange(it)
                    }
                }
            )

            MotionSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_DYNAMIC_BACKGROUND_ENABLED),
                disabledSuffix = stringResource(R.string.settings_android13_required),
                checked = dynamicBackgroundAvailable && nowPlayingDynamicBackgroundEnabled,
                enabled = dynamicBackgroundAvailable,
                alpha = if (dynamicBackgroundAvailable) 1f else 0.5f,
                onToggle = {
                    if (dynamicBackgroundAvailable) {
                        onDynamicBackgroundToggle(!nowPlayingDynamicBackgroundEnabled)
                    }
                },
                onCheckedChange = {
                    if (dynamicBackgroundAvailable) {
                        onDynamicBackgroundToggle(it)
                    }
                }
            )

            MotionSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.LYRIC_BLUR_ENABLED),
                descriptionOverride = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    null
                } else {
                    stringResource(R.string.lyrics_blur_desc) +
                        " · " +
                        stringResource(R.string.lyrics_blur_low_cost_hint)
                },
                disabledSuffix = null,
                checked = lyricBlurEnabled,
                enabled = true,
                alpha = 1f,
                onToggle = { onLyricBlurEnabledChange(!lyricBlurEnabled) },
                onCheckedChange = onLyricBlurEnabledChange
            )

            LazyAnimatedVisibility(visible = lyricBlurEnabled) {
                SnappedFloatSliderListItem(
                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.LYRIC_BLUR_AMOUNT),
                    value = lyricBlurAmount,
                    valueText = { current ->
                        stringResource(R.string.lyrics_blur_current, current)
                    },
                    valueRange = 0f..8f,
                    steps = 79,
                    onValueCommitted = onLyricBlurAmountChange
                )
            }
        }
    }
}

@Composable
private fun MotionSwitchItem(
    setting: AutoSettingInfo,
    descriptionOverride: String? = null,
    disabledSuffix: String?,
    checked: Boolean,
    enabled: Boolean,
    alpha: Float,
    onToggle: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    AutoSettingsListItem(
        setting = setting,
        modifier = Modifier
            .alpha(alpha),
        enabled = enabled,
        supportingContent = {
            val suffix = disabledSuffix?.takeIf { !enabled }?.let { " · $it" }.orEmpty()
            val description = descriptionOverride ?: stringResource(setting.descriptionRes)
            Text(description + suffix)
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        },
        onClick = onToggle
    )
}

@Composable
private fun SnappedFloatSliderListItem(
    setting: AutoSettingInfo,
    value: Float,
    valueText: @Composable (Float) -> String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    snapStep: Float? = null,
    onValueCommitted: (Float) -> Unit
) {
    var pendingValue by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value) {
        if ((pendingValue - value).absoluteValue > 0.01f) {
            pendingValue = value
        }
    }

    AutoSettingsListItem(
        setting = setting,
        showDefaultIcon = false,
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = valueText(pendingValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MiuixSettingsSlider(
                    value = pendingValue,
                    onValueChange = { changed ->
                        pendingValue = snapStep
                            ?.let { step ->
                                ((changed / step).roundToInt() * step)
                                    .coerceIn(valueRange.start, valueRange.endInclusive)
                            }
                            ?: changed
                    },
                    onValueChangeFinished = { onValueCommitted(pendingValue) },
                    valueRange = valueRange,
                    steps = steps
                )
            }
        }
    )
}
