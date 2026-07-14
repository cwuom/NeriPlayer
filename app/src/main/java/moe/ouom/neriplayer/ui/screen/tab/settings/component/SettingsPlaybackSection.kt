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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsPlaybackSection
 * Updated: 2026/3/23
 */

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SurroundSound
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.MAX_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.core.player.model.MIN_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.core.player.model.normalizePlaybackVolumeBalance
import moe.ouom.neriplayer.data.settings.generated.AutoSettingInfo
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch

@Composable
internal fun SettingsPlaybackSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
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
    playbackVolumeNormalizationEnabled: Boolean,
    onPlaybackVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    playbackVolumeBalance: Float,
    onPlaybackVolumeBalanceChange: (Float) -> Unit,
    keepLastPlaybackProgress: Boolean,
    onKeepLastPlaybackProgressChange: (Boolean) -> Unit,
    keepPlaybackModeState: Boolean,
    onKeepPlaybackModeStateChange: (Boolean) -> Unit,
    stopOnBluetoothDisconnect: Boolean,
    onStopOnBluetoothDisconnectChange: (Boolean) -> Unit,
    usbExclusivePlayback: Boolean,
    onUsbExclusiveSettingsClick: () -> Unit,
    allowMixedPlayback: Boolean,
    onAllowMixedPlaybackChange: (Boolean) -> Unit,
    preemptAudioFocus: Boolean,
    onPreemptAudioFocusChange: (Boolean) -> Unit
) {
    if (showHeader) {
        ExpandableHeader(
            icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
            title = stringResource(R.string.settings_playback),
            subtitleCollapsed = stringResource(R.string.settings_playback_expand),
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
            PlaybackSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.PLAYBACK_FADE_IN),
                checked = playbackFadeIn,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = stringResource(R.string.settings_playback_fade_in),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onPlaybackFadeInChange(!playbackFadeIn) },
                onCheckedChange = onPlaybackFadeInChange
            )

            LazyAnimatedVisibility(visible = playbackFadeIn) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                ) {
                    DurationSliderListItem(
                        title = stringResource(R.string.settings_playback_fade_in_duration),
                        durationMs = playbackFadeInDurationMs,
                        onDurationChange = onPlaybackFadeInDurationMsChange
                    )
                    DurationSliderListItem(
                        title = stringResource(R.string.settings_playback_fade_out_duration),
                        durationMs = playbackFadeOutDurationMs,
                        onDurationChange = onPlaybackFadeOutDurationMsChange
                    )
                }
            }

            PlaybackSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.PLAYBACK_CROSSFADE_NEXT),
                checked = playbackCrossfadeNext,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = stringResource(R.string.settings_playback_crossfade_next),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onPlaybackCrossfadeNextChange(!playbackCrossfadeNext) },
                onCheckedChange = onPlaybackCrossfadeNextChange
            )

            LazyAnimatedVisibility(visible = playbackCrossfadeNext) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                ) {
                    DurationSliderListItem(
                        title = stringResource(R.string.settings_playback_crossfade_in_duration),
                        durationMs = playbackCrossfadeInDurationMs,
                        onDurationChange = onPlaybackCrossfadeInDurationMsChange
                    )
                    DurationSliderListItem(
                        title = stringResource(R.string.settings_playback_crossfade_out_duration),
                        durationMs = playbackCrossfadeOutDurationMs,
                        onDurationChange = onPlaybackCrossfadeOutDurationMsChange
                    )
                }
            }

            PlaybackSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(
                    AutoSettingsKeys.PLAYBACK_VOLUME_NORMALIZATION_ENABLED
                ),
                checked = playbackVolumeNormalizationEnabled,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.BarChart,
                        contentDescription = stringResource(
                            R.string.settings_playback_volume_normalization
                        ),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = {
                    onPlaybackVolumeNormalizationEnabledChange(
                        !playbackVolumeNormalizationEnabled
                    )
                },
                onCheckedChange = onPlaybackVolumeNormalizationEnabledChange
            )

            VolumeBalanceSliderListItem(
                balance = playbackVolumeBalance,
                onBalanceChange = onPlaybackVolumeBalanceChange
            )

            PlaybackSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.KEEP_LAST_PLAYBACK_PROGRESS),
                checked = keepLastPlaybackProgress,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = stringResource(R.string.settings_keep_last_playback_progress),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onKeepLastPlaybackProgressChange(!keepLastPlaybackProgress) },
                onCheckedChange = onKeepLastPlaybackProgressChange
            )

            PlaybackSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.KEEP_PLAYBACK_MODE_STATE),
                checked = keepPlaybackModeState,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = stringResource(R.string.settings_keep_playback_mode_state),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onKeepPlaybackModeStateChange(!keepPlaybackModeState) },
                onCheckedChange = onKeepPlaybackModeStateChange
            )

            PlaybackSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.STOP_ON_BLUETOOTH_DISCONNECT),
                checked = stopOnBluetoothDisconnect,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.BluetoothAudio,
                        contentDescription = stringResource(R.string.settings_stop_on_bluetooth_disconnect),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onStopOnBluetoothDisconnectChange(!stopOnBluetoothDisconnect) },
                onCheckedChange = onStopOnBluetoothDisconnectChange
            )

            UsbExclusiveSettingsEntry(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.USB_EXCLUSIVE_PLAYBACK),
                enabled = usbExclusivePlayback,
                onClick = onUsbExclusiveSettingsClick
            )

            PlaybackSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.ALLOW_MIXED_PLAYBACK),
                checked = allowMixedPlayback,
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = stringResource(R.string.settings_allow_mixed_playback),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onAllowMixedPlaybackChange(!allowMixedPlayback) },
                onCheckedChange = onAllowMixedPlaybackChange
            )

            PlaybackSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.PREEMPT_AUDIO_FOCUS),
                checked = preemptAudioFocus,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Headphones,
                        contentDescription = stringResource(R.string.settings_preempt_audio_focus),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onToggle = { onPreemptAudioFocusChange(!preemptAudioFocus) },
                onCheckedChange = onPreemptAudioFocusChange
            )
        }
    }
}

@Composable
private fun UsbExclusiveSettingsEntry(
    setting: AutoSettingInfo,
    enabled: Boolean,
    onClick: () -> Unit
) {
    AutoSettingsListItem(
        setting = setting,
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Usb,
                contentDescription = stringResource(R.string.settings_usb_exclusive_playback),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(
                        if (enabled) {
                            R.string.settings_usb_exclusive_state_enabled
                        } else {
                            R.string.settings_usb_exclusive_state_disabled
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
        },
        onClick = onClick
    )
}

@Composable
private fun VolumeBalanceSliderListItem(
    balance: Float,
    onBalanceChange: (Float) -> Unit
) {
    val normalizedBalance = normalizePlaybackVolumeBalance(balance)
    var pendingBalance by remember { mutableFloatStateOf(normalizedBalance) }

    LaunchedEffect(normalizedBalance) {
        if ((pendingBalance - normalizedBalance).absoluteValue > 0.01f) {
            pendingBalance = normalizedBalance
        }
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_playback_volume_balance)) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = volumeBalanceLabel(pendingBalance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MiuixSettingsSlider(
                    value = pendingBalance,
                    onValueChange = { pendingBalance = it },
                    onValueChangeFinished = {
                        onBalanceChange(normalizePlaybackVolumeBalance(pendingBalance))
                    },
                    valueRange = MIN_PLAYBACK_VOLUME_BALANCE..MAX_PLAYBACK_VOLUME_BALANCE,
                    steps = 39
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.SurroundSound,
                contentDescription = stringResource(R.string.settings_playback_volume_balance),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun volumeBalanceLabel(balance: Float): String {
    val normalizedBalance = normalizePlaybackVolumeBalance(balance)
    val percent = (normalizedBalance.absoluteValue * 100f).roundToInt()
    return when {
        percent == 0 -> stringResource(R.string.settings_playback_volume_balance_center)
        normalizedBalance < 0f -> stringResource(
            R.string.settings_playback_volume_balance_left,
            percent
        )
        else -> stringResource(R.string.settings_playback_volume_balance_right, percent)
    }
}

@Composable
private fun PlaybackSwitchItem(
    setting: AutoSettingInfo,
    checked: Boolean,
    icon: @Composable () -> Unit,
    onToggle: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    AutoSettingsListItem(
        setting = setting,
        leadingContent = icon,
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        onClick = onToggle
    )
}

@Composable
private fun DurationSliderListItem(
    title: String,
    durationMs: Long,
    onDurationChange: (Long) -> Unit
) {
    val durationSeconds = durationMs / 1000f
    var pendingDurationSeconds by remember { mutableFloatStateOf(durationSeconds) }

    LaunchedEffect(durationMs) {
        if ((pendingDurationSeconds - durationSeconds).absoluteValue > 0.01f) {
            pendingDurationSeconds = durationSeconds
        }
    }

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.settings_playback_fade_duration_value,
                        pendingDurationSeconds
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MiuixSettingsSlider(
                    value = pendingDurationSeconds,
                    onValueChange = { pendingDurationSeconds = it },
                    onValueChangeFinished = {
                        onDurationChange((pendingDurationSeconds * 1000f).roundToLong())
                    },
                    valueRange = 0f..3f,
                    steps = 29
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
