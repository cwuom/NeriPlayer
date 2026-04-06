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
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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

@Composable
internal fun SettingsMotionSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    advancedLyricsEnabled: Boolean,
    onAdvancedLyricsEnabledChange: (Boolean) -> Unit,
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
    ExpandableHeader(
        icon = Icons.Outlined.Bolt,
        title = stringResource(R.string.settings_motion),
        subtitleCollapsed = stringResource(R.string.settings_motion_expand),
        subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
        expanded = expanded,
        onToggle = { onExpandedChange(!expanded) },
        arrowRotation = arrowRotation
    )

    LazyAnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
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

            MotionSwitchItem(
                title = stringResource(R.string.settings_advanced_lyrics),
                description = stringResource(R.string.settings_advanced_lyrics_desc),
                disabledSuffix = null,
                checked = advancedLyricsEnabled,
                enabled = true,
                alpha = 1f,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = stringResource(R.string.settings_advanced_lyrics),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onAdvancedLyricsEnabledChange(!advancedLyricsEnabled) },
                onCheckedChange = onAdvancedLyricsEnabledChange
            )

            MotionSwitchItem(
                title = stringResource(R.string.settings_advanced_blur),
                description = stringResource(R.string.settings_advanced_blur_desc),
                disabledSuffix = stringResource(R.string.settings_android12_required),
                checked = advancedBlurAvailable && advancedBlurEnabled,
                enabled = advancedBlurAvailable,
                alpha = if (advancedBlurAvailable) 1f else 0.5f,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.BlurOn,
                        contentDescription = stringResource(R.string.settings_advanced_blur),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
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
                title = stringResource(R.string.settings_nowplaying_cover_blur_background),
                description = stringResource(R.string.settings_nowplaying_cover_blur_background_desc),
                disabledSuffix = stringResource(R.string.settings_android12_required),
                checked = coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled,
                enabled = coverBlurAvailable,
                alpha = if (coverBlurAvailable) 1f else 0.5f,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Wallpaper,
                        contentDescription = stringResource(R.string.settings_nowplaying_cover_blur_background),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = {
                    if (coverBlurAvailable) {
                        safeCoverBlurToggle(!nowPlayingCoverBlurBackgroundEnabled)
                    }
                },
                onCheckedChange = safeCoverBlurToggle
            )

            AnimatedVisibility(visible = coverBlurAvailable && nowPlayingCoverBlurBackgroundEnabled) {
                Column(Modifier.fillMaxWidth()) {
                    SnappedFloatSliderListItem(
                        title = stringResource(R.string.settings_nowplaying_cover_blur_amount),
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
                        title = stringResource(R.string.settings_nowplaying_cover_blur_darken),
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
                title = stringResource(R.string.settings_nowplaying_audio_reactive),
                description = stringResource(R.string.settings_nowplaying_audio_reactive_desc),
                disabledSuffix = stringResource(R.string.settings_android13_required),
                checked = audioReactiveAvailable && nowPlayingAudioReactiveEnabled,
                enabled = audioReactiveAvailable,
                alpha = if (audioReactiveAvailable) 1f else 0.5f,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Analytics,
                        contentDescription = stringResource(R.string.settings_nowplaying_audio_reactive),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
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
                title = stringResource(R.string.settings_nowplaying_dynamic_background),
                description = stringResource(R.string.settings_nowplaying_dynamic_background_desc),
                disabledSuffix = stringResource(R.string.settings_android13_required),
                checked = dynamicBackgroundAvailable && nowPlayingDynamicBackgroundEnabled,
                enabled = dynamicBackgroundAvailable,
                alpha = if (dynamicBackgroundAvailable) 1f else 0.5f,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = stringResource(R.string.settings_nowplaying_dynamic_background),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
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
                title = stringResource(R.string.lyrics_blur_effect),
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    stringResource(R.string.lyrics_blur_desc)
                } else {
                    stringResource(R.string.lyrics_blur_desc) +
                        " · " +
                        stringResource(R.string.lyrics_blur_low_cost_hint)
                },
                disabledSuffix = null,
                checked = lyricBlurEnabled,
                enabled = true,
                alpha = 1f,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Subtitles,
                        contentDescription = stringResource(R.string.settings_lyrics_blur),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onLyricBlurEnabledChange(!lyricBlurEnabled) },
                onCheckedChange = onLyricBlurEnabledChange
            )

            AnimatedVisibility(visible = lyricBlurEnabled) {
                SnappedFloatSliderListItem(
                    title = stringResource(R.string.lyrics_blur_amount),
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
    title: String,
    description: String,
    disabledSuffix: String?,
    checked: Boolean,
    enabled: Boolean,
    alpha: Float,
    icon: @Composable () -> Unit,
    onToggle: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier
            .settingsItemClickable(onClick = onToggle)
            .alpha(alpha),
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = {
            val suffix = disabledSuffix?.takeIf { !enabled }?.let { " · $it" }.orEmpty()
            Text(description + suffix)
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SnappedFloatSliderListItem(
    title: String,
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

    ListItem(
        headlineContent = { Text(title) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = valueText(pendingValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
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
