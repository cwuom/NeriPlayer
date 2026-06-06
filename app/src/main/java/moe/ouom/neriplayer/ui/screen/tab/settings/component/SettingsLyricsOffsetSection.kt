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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsLyricsOffsetSection
 * Updated: 2026/4/13
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.LYRIC_DEFAULT_OFFSET_STEP_MS
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_DEFAULT_OFFSET_MS
import kotlin.math.roundToLong

private val LYRIC_OFFSET_SLIDER_STEPS =
    ((MAX_LYRIC_DEFAULT_OFFSET_MS - MIN_LYRIC_DEFAULT_OFFSET_MS) / LYRIC_DEFAULT_OFFSET_STEP_MS)
        .toInt() - 1
private val LYRIC_OFFSET_STEP_MS_FLOAT = LYRIC_DEFAULT_OFFSET_STEP_MS.toFloat()

@Composable
internal fun SettingsLyricsOffsetSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    cloudMusicLyricDefaultOffsetMs: Long,
    onCloudMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    qqMusicLyricDefaultOffsetMs: Long,
    onQqMusicLyricDefaultOffsetMsChange: (Long) -> Unit
) {
    ExpandableHeader(
        icon = Icons.Outlined.Subtitles,
        title = stringResource(R.string.settings_lyrics_offset),
        subtitleCollapsed = stringResource(R.string.settings_lyrics_offset_expand),
        subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
        expanded = expanded,
        onToggle = { onExpandedChange(!expanded) },
        arrowRotation = arrowRotation
    )

    LazyAnimatedVisibility(visible = expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
        ) {
            LyricsOffsetSliderListItem(
                title = stringResource(R.string.settings_lyrics_offset_cloud_music),
                description = stringResource(R.string.settings_lyrics_offset_cloud_music_desc),
                offsetMs = cloudMusicLyricDefaultOffsetMs,
                onOffsetChange = onCloudMusicLyricDefaultOffsetMsChange
            )
            Spacer(Modifier.height(4.dp))
            LyricsOffsetSliderListItem(
                title = stringResource(R.string.settings_lyrics_offset_qq_music),
                description = stringResource(R.string.settings_lyrics_offset_qq_music_desc),
                offsetMs = qqMusicLyricDefaultOffsetMs,
                onOffsetChange = onQqMusicLyricDefaultOffsetMsChange
            )
        }
    }
}

@Composable
private fun LyricsOffsetSliderListItem(
    title: String,
    description: String,
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit
) {
    var pendingOffset by remember { mutableLongStateOf(offsetMs) }

    LaunchedEffect(offsetMs) {
        if (pendingOffset != offsetMs) {
            pendingOffset = offsetMs
        }
    }

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.settings_lyrics_offset_value,
                        formatLyricOffsetValue(pendingOffset)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = pendingOffset.toFloat(),
                    onValueChange = { candidate ->
                        pendingOffset = ((candidate / LYRIC_OFFSET_STEP_MS_FLOAT).roundToLong()
                            * LYRIC_DEFAULT_OFFSET_STEP_MS)
                            .coerceIn(MIN_LYRIC_DEFAULT_OFFSET_MS, MAX_LYRIC_DEFAULT_OFFSET_MS)
                    },
                    onValueChangeFinished = { onOffsetChange(pendingOffset) },
                    valueRange = MIN_LYRIC_DEFAULT_OFFSET_MS.toFloat()..MAX_LYRIC_DEFAULT_OFFSET_MS.toFloat(),
                    steps = LYRIC_OFFSET_SLIDER_STEPS
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun formatLyricOffsetValue(offsetMs: Long): String {
    val sign = if (offsetMs > 0) "+" else ""
    return "$sign$offsetMs"
}
