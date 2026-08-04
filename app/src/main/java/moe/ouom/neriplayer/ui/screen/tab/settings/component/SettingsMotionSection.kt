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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import moe.ouom.neriplayer.data.settings.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.ENHANCED_ADVANCED_BLUR_RADIUS_STEP_DP
import moe.ouom.neriplayer.data.settings.EnhancedAdvancedBlurPreference
import moe.ouom.neriplayer.data.settings.MAX_ENHANCED_ADVANCED_BLUR_RADIUS_DP
import moe.ouom.neriplayer.data.settings.MIN_ENHANCED_ADVANCED_BLUR_RADIUS_DP
import moe.ouom.neriplayer.data.settings.canBeSelectedWhen
import moe.ouom.neriplayer.data.settings.generated.AutoSettingInfo
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchItems
import moe.ouom.neriplayer.ui.effect.glass.ADVANCED_GLASS_MIN_SDK
import moe.ouom.neriplayer.ui.effect.glass.shouldShowAdvancedBlurSettings
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro

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
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
    lyricBlurAmount: Float,
    onLyricBlurAmountChange: (Float) -> Unit,
    cardIndex: Int? = null,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    fun shouldShowCard(index: Int): Boolean = cardIndex == null || cardIndex == index

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
            val advancedBlurAvailable = Build.VERSION.SDK_INT >= ADVANCED_GLASS_MIN_SDK
            val dynamicBackgroundApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

            if (cardIndex == null || cardIndex == 0) {
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
            }

            val dynamicBackgroundAvailable =
                dynamicBackgroundApiAvailable && !nowPlayingCoverBlurBackgroundEnabled
            val audioReactiveAvailable =
                dynamicBackgroundApiAvailable &&
                    nowPlayingDynamicBackgroundEnabled &&
                    dynamicBackgroundAvailable
            val coverBlurConflictSuffix = stringResource(R.string.settings_nowplaying_disable_cover_blur_required)
            val dynamicBackgroundDisabledSuffix = when {
                !dynamicBackgroundApiAvailable -> stringResource(R.string.settings_android13_required)
                nowPlayingCoverBlurBackgroundEnabled -> coverBlurConflictSuffix
                else -> null
            }
            val audioReactiveDisabledSuffix = when {
                !dynamicBackgroundApiAvailable -> stringResource(R.string.settings_android13_required)
                nowPlayingCoverBlurBackgroundEnabled -> coverBlurConflictSuffix
                !nowPlayingDynamicBackgroundEnabled -> stringResource(
                    R.string.settings_nowplaying_dynamic_background_required
                )
                else -> null
            }

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
            if (shouldShowCard(0)) MotionDetailCard(
                showCard = !showHeader,
                highlighted = false,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_motion_general_section),
                    description = stringResource(R.string.settings_motion_general_section_desc)
                )
                AutoSettingsSwitchItems(
                    repository = autoSettingsRepository,
                    scope = scope,
                    sectionScope = AutoSettingsScopes.motion,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
            }
            if (cardIndex == null) MotionDetailGap(showHeader)

            if (shouldShowCard(1)) MotionDetailCard(
                showCard = !showHeader,
                highlighted = false,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_motion_glass_section),
                    description = stringResource(R.string.settings_motion_glass_section_desc)
                )
                MotionSwitchItem(
                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.ADVANCED_BLUR_ENABLED),
                    disabledSuffix = stringResource(R.string.settings_android13_required),
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
                    },
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )

                LazyAnimatedVisibility(
                    visible = shouldShowAdvancedBlurSettings(
                        sdkInt = Build.VERSION.SDK_INT,
                        advancedBlurEnabled = advancedBlurEnabled
                    )
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        BlurAmountSettingItem(
                            amountDp = enhancedAdvancedBlurRadiusDp,
                            onAmountDpChange = onEnhancedAdvancedBlurRadiusDpChange
                        )
                        AdvancedBlurQualitySettingItem(
                            quality = advancedBlurQuality,
                            enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
                            onQualityChange = onAdvancedBlurQualityChange,
                            highlightTargetId = highlightTargetId,
                            highlightPulse = highlightPulse,
                            onHighlightFinished = onHighlightFinished
                        )
                    }
                }

                EnhancedAdvancedBlurSettingItem(
                    sdkInt = Build.VERSION.SDK_INT,
                    advancedBlurEnabled = advancedBlurEnabled,
                    enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
                    onEnhancedAdvancedBlurEnabledChange = onEnhancedAdvancedBlurEnabledChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
            }
            if (cardIndex == null) MotionDetailGap(showHeader)

            if (shouldShowCard(2)) MotionDetailCard(
                showCard = !showHeader,
                highlighted = false,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_motion_nowplaying_section),
                    description = stringResource(R.string.settings_motion_nowplaying_section_desc)
                )
                MotionSwitchItem(
                    setting = AutoSettingsMetadata.requireSetting(
                        AutoSettingsKeys.NOWPLAYING_COVER_BLUR_BACKGROUND_ENABLED
                    ),
                    disabledSuffix = stringResource(R.string.settings_android12_required),
                    checked = coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled,
                    enabled = coverBlurAvailable,
                    alpha = if (coverBlurAvailable) 1f else 0.5f,
                    onToggle = {
                        if (coverBlurAvailable) {
                            safeCoverBlurToggle(!nowPlayingCoverBlurBackgroundEnabled)
                        }
                    },
                    onCheckedChange = safeCoverBlurToggle,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
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
                    disabledSuffix = audioReactiveDisabledSuffix,
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
                    },
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )

                MotionSwitchItem(
                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_DYNAMIC_BACKGROUND_ENABLED),
                    disabledSuffix = dynamicBackgroundDisabledSuffix,
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
                    },
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
            }
            if (cardIndex == null) MotionDetailGap(showHeader)

            if (shouldShowCard(3)) MotionDetailCard(
                showCard = !showHeader,
                highlighted = false,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_motion_lyrics_section),
                    description = stringResource(R.string.settings_motion_lyrics_section_desc)
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
                    onCheckedChange = onLyricBlurEnabledChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
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
}

@Composable
private fun MotionDetailCard(
    showCard: Boolean,
    highlighted: Boolean = false,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (showCard) {
        MiuixSettingsSectionCard(
            highlighted = highlighted,
            highlightPulse = highlightPulse,
            onHighlightFinished = if (highlighted) onHighlightFinished else null,
            content = content
        )
    } else {
        content()
    }
}

@Composable
private fun MotionDetailGap(showHeader: Boolean) {
    if (!showHeader) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun EnhancedAdvancedBlurSettingItem(
    sdkInt: Int,
    advancedBlurEnabled: Boolean,
    enhancedAdvancedBlurEnabled: Boolean,
    onEnhancedAdvancedBlurEnabledChange: (Boolean) -> Unit,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    var showBackgroundImageHint by remember { mutableStateOf(false) }
    val updateEnabled: (Boolean) -> Unit = { enabled ->
        onEnhancedAdvancedBlurEnabledChange(enabled)
        if (enabled && !enhancedAdvancedBlurEnabled) {
            showBackgroundImageHint = true
        }
    }

    LazyAnimatedVisibility(
        visible = shouldShowAdvancedBlurSettings(
            sdkInt = sdkInt,
            advancedBlurEnabled = advancedBlurEnabled
        )
    ) {
        Column(Modifier.fillMaxWidth()) {
            MotionSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(
                    AutoSettingsKeys.ENHANCED_ADVANCED_BLUR_ENABLED
                ),
                disabledSuffix = null,
                checked = enhancedAdvancedBlurEnabled,
                enabled = true,
                alpha = 1f,
                onToggle = {
                    updateEnabled(!enhancedAdvancedBlurEnabled)
                },
                onCheckedChange = updateEnabled,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
        }
    }

    if (showBackgroundImageHint) {
        MiuixSettingsDialog(
            onDismissRequest = { showBackgroundImageHint = false },
            title = { Text(stringResource(R.string.settings_enhanced_advanced_blur)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_enhanced_advanced_blur_background_hint
                    )
                )
            },
            confirmButton = {
                MiuixSettingsTextButton(onClick = { showBackgroundImageHint = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
}

@Composable
private fun BlurAmountSettingItem(
    amountDp: Float,
    onAmountDpChange: (Float) -> Unit
) {
    SnappedFloatSliderListItem(
        setting = AutoSettingsMetadata.requireSetting(
            AutoSettingsKeys.ENHANCED_ADVANCED_BLUR_RADIUS_DP
        ),
        value = EnhancedAdvancedBlurPreference.normalize(amountDp),
        valueText = { current ->
            stringResource(
                R.string.settings_enhanced_advanced_blur_radius_value,
                current.roundToInt()
            )
        },
        valueRange = MIN_ENHANCED_ADVANCED_BLUR_RADIUS_DP..
            MAX_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
        steps = (
            (MAX_ENHANCED_ADVANCED_BLUR_RADIUS_DP -
                MIN_ENHANCED_ADVANCED_BLUR_RADIUS_DP) /
                ENHANCED_ADVANCED_BLUR_RADIUS_STEP_DP
            ).roundToInt() - 1,
        snapStep = ENHANCED_ADVANCED_BLUR_RADIUS_STEP_DP,
        onValueChanged = onAmountDpChange,
        onValueCommitted = onAmountDpChange
    )
}

@Composable
internal fun AdvancedBlurQualitySettingItem(
    quality: AdvancedBlurQuality,
    enhancedAdvancedBlurEnabled: Boolean,
    onQualityChange: (AdvancedBlurQuality) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    var showDialog by remember { mutableStateOf(false) }
    val setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.ADVANCED_BLUR_QUALITY)

    AutoSettingsListItem(
        setting = setting,
        supportingContent = {
            Text(advancedBlurQualityLabel(quality))
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_advanced_blur_quality)) },
            text = {
                Column {
                    AdvancedBlurQuality.entries.forEach { option ->
                        val enabled = option.canBeSelectedWhen(enhancedAdvancedBlurEnabled)
                        MiuixSettingsChoiceRow(
                            title = advancedBlurQualityLabel(option),
                            subtitle = advancedBlurQualityDescription(option),
                            selected = option == quality,
                            enabled = enabled,
                            onClick = {
                                onQualityChange(option)
                                showDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

@Composable
private fun advancedBlurQualityLabel(quality: AdvancedBlurQuality): String = stringResource(
    when (quality) {
        AdvancedBlurQuality.UltraLow -> R.string.settings_advanced_blur_quality_ultra_low
        AdvancedBlurQuality.Low -> R.string.settings_advanced_blur_quality_low
        AdvancedBlurQuality.Default -> R.string.settings_advanced_blur_quality_default
        AdvancedBlurQuality.High -> R.string.settings_advanced_blur_quality_high
    }
)

@Composable
private fun advancedBlurQualityDescription(quality: AdvancedBlurQuality): String = stringResource(
    when (quality) {
        AdvancedBlurQuality.UltraLow -> R.string.settings_advanced_blur_quality_ultra_low_desc
        AdvancedBlurQuality.Low -> R.string.settings_advanced_blur_quality_low_desc
        AdvancedBlurQuality.Default -> R.string.settings_advanced_blur_quality_default_desc
        AdvancedBlurQuality.High -> R.string.settings_advanced_blur_quality_high_desc
    }
)

@Composable
private fun MotionSwitchItem(
    setting: AutoSettingInfo,
    descriptionOverride: String? = null,
    disabledSuffix: String?,
    checked: Boolean,
    enabled: Boolean,
    alpha: Float,
    onToggle: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
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
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
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
    onValueChanged: ((Float) -> Unit)? = null,
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
                        val nextValue = snapStep
                            ?.let { step ->
                                ((changed / step).roundToInt() * step)
                                    .coerceIn(valueRange.start, valueRange.endInclusive)
                            }
                            ?: changed
                        if ((nextValue - pendingValue).absoluteValue > 0.01f) {
                            pendingValue = nextValue
                            onValueChanged?.invoke(nextValue)
                        }
                    },
                    onValueChangeFinished = { onValueCommitted(pendingValue) },
                    valueRange = valueRange,
                    steps = steps
                )
            }
        }
    )
}
