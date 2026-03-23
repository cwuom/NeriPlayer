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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.math.roundToLong
import moe.ouom.neriplayer.R

@Composable
internal fun SettingsPlaybackSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
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
    onAllowMixedPlaybackChange: (Boolean) -> Unit
) {
    ExpandableHeader(
        icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
        title = stringResource(R.string.settings_playback),
        subtitleCollapsed = stringResource(R.string.settings_playback_expand),
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
            PlaybackSwitchItem(
                title = stringResource(R.string.settings_playback_fade_in),
                description = stringResource(R.string.settings_playback_fade_in_desc),
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

            AnimatedVisibility(visible = playbackFadeIn) {
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
                title = stringResource(R.string.settings_playback_crossfade_next),
                description = stringResource(R.string.settings_playback_crossfade_next_desc),
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

            AnimatedVisibility(visible = playbackCrossfadeNext) {
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
                title = stringResource(R.string.settings_keep_last_playback_progress),
                description = stringResource(R.string.settings_keep_last_playback_progress_desc),
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
                title = stringResource(R.string.settings_keep_playback_mode_state),
                description = stringResource(R.string.settings_keep_playback_mode_state_desc),
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
                title = stringResource(R.string.settings_stop_on_bluetooth_disconnect),
                description = stringResource(R.string.settings_stop_on_bluetooth_disconnect_desc),
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

            PlaybackSwitchItem(
                title = stringResource(R.string.settings_allow_mixed_playback),
                description = stringResource(R.string.settings_allow_mixed_playback_desc),
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
        }
    }
}

@Composable
private fun PlaybackSwitchItem(
    title: String,
    description: String,
    checked: Boolean,
    icon: @Composable () -> Unit,
    onToggle: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onToggle),
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
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
                Slider(
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
