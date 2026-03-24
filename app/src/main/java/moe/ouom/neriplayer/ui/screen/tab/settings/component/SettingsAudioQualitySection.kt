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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsAudioQualitySection
 * Updated: 2026/3/23
 */

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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.HapticTextButton

@Composable
internal fun SettingsAudioQualitySection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    qualityLabel: String,
    preferredQuality: String,
    onQualityChange: (String) -> Unit,
    youtubeQualityLabel: String,
    youtubePreferredQuality: String,
    onYouTubeQualityChange: (String) -> Unit,
    biliQualityLabel: String,
    biliPreferredQuality: String,
    onBiliQualityChange: (String) -> Unit,
    showQualityDialog: Boolean,
    onShowQualityDialogChange: (Boolean) -> Unit,
    showYouTubeQualityDialog: Boolean,
    onShowYouTubeQualityDialogChange: (Boolean) -> Unit,
    showBiliQualityDialog: Boolean,
    onShowBiliQualityDialogChange: (Boolean) -> Unit
) {
    ExpandableHeader(
        icon = Icons.Filled.Audiotrack,
        title = stringResource(R.string.settings_audio_quality),
        subtitleCollapsed = stringResource(R.string.settings_audio_quality_expand),
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
            ListItem(
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_netease_cloud_music),
                        contentDescription = stringResource(R.string.settings_netease_quality),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                headlineContent = { Text(stringResource(R.string.quality_netease_default)) },
                supportingContent = {
                    Text(stringResource(R.string.common_label_value_format, qualityLabel, preferredQuality))
                },
                modifier = Modifier.settingsItemClickable {
                    onShowQualityDialogChange(true)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_youtube),
                        contentDescription = stringResource(R.string.quality_youtube_default),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                headlineContent = { Text(stringResource(R.string.quality_youtube_default)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.common_label_value_format,
                            youtubeQualityLabel,
                            youtubePreferredQuality
                        )
                    )
                },
                modifier = Modifier.settingsItemClickable {
                    onShowYouTubeQualityDialogChange(true)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_bilibili),
                        contentDescription = stringResource(R.string.settings_bili_quality),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                headlineContent = { Text(stringResource(R.string.quality_bili_default)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.common_label_value_format,
                            biliQualityLabel,
                            biliPreferredQuality
                        )
                    )
                },
                modifier = Modifier.settingsItemClickable {
                    onShowBiliQualityDialogChange(true)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }

    if (showQualityDialog) {
        QualityOptionsDialog(
            title = stringResource(R.string.quality_default),
            selectedValue = preferredQuality,
            options = listOf(
                "standard" to stringResource(R.string.quality_standard),
                "higher" to stringResource(R.string.quality_high),
                "exhigh" to stringResource(R.string.quality_very_high),
                "lossless" to stringResource(R.string.quality_lossless),
                "hires" to stringResource(R.string.quality_hires),
                "jyeffect" to stringResource(R.string.quality_hd_surround),
                "sky" to stringResource(R.string.quality_surround),
                "jymaster" to stringResource(R.string.quality_hires)
            ),
            onDismiss = { onShowQualityDialogChange(false) },
            onSelect = { level ->
                onQualityChange(level)
                onShowQualityDialogChange(false)
            }
        )
    }

    if (showYouTubeQualityDialog) {
        QualityOptionsDialog(
            title = stringResource(R.string.quality_youtube_default),
            selectedValue = youtubePreferredQuality,
            options = listOf(
                "low" to stringResource(R.string.settings_audio_quality_standard),
                "medium" to stringResource(R.string.settings_audio_quality_medium),
                "high" to stringResource(R.string.settings_audio_quality_high),
                "very_high" to stringResource(R.string.quality_very_high)
            ),
            onDismiss = { onShowYouTubeQualityDialogChange(false) },
            onSelect = { level ->
                onYouTubeQualityChange(level)
                onShowYouTubeQualityDialogChange(false)
            }
        )
    }

    if (showBiliQualityDialog) {
        QualityOptionsDialog(
            title = stringResource(R.string.quality_bili_default),
            selectedValue = biliPreferredQuality,
            options = listOf(
                "dolby" to stringResource(R.string.settings_dolby),
                "hires" to stringResource(R.string.quality_hires),
                "lossless" to stringResource(R.string.quality_lossless),
                "high" to stringResource(R.string.settings_audio_quality_high),
                "medium" to stringResource(R.string.settings_audio_quality_medium),
                "low" to stringResource(R.string.settings_audio_quality_low)
            ),
            onDismiss = { onShowBiliQualityDialogChange(false) },
            onSelect = { level ->
                onBiliQualityChange(level)
                onShowBiliQualityDialogChange(false)
            }
        )
    }
}

@Composable
private fun QualityOptionsDialog(
    title: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (level, label) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        trailingContent = {
                            if (level == selectedValue) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.common_selected),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier.settingsItemClickable {
                            onSelect(level)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
