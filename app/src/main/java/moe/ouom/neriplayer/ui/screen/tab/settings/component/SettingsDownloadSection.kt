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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsDownloadSection
 * Updated: 2026/3/23
 */

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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.util.HapticTextButton

@Composable
internal fun SettingsDownloadSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    onNavigateToDownloadManager: () -> Unit
) {
    ExpandableHeader(
        icon = Icons.Outlined.Download,
        title = stringResource(R.string.settings_download_management),
        subtitleCollapsed = stringResource(R.string.settings_download_expand),
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
        SettingsDownloadExpandedContent(
            onNavigateToDownloadManager = onNavigateToDownloadManager
        )
    }
}

@Composable
private fun SettingsDownloadExpandedContent(
    onNavigateToDownloadManager: () -> Unit
) {
    val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
    ) {
        batchDownloadProgress?.let { progress ->
            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.settings_download_progress),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text(stringResource(R.string.download_progress)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.settings_download_songs_count,
                            progress.completedSongs,
                            progress.totalSongs
                        )
                    )
                },
                trailingContent = {
                    HapticTextButton(onClick = { AudioDownloadManager.cancelDownload() }) {
                        Text(
                            stringResource(R.string.action_cancel),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                modifier = Modifier.settingsItemClickable(onClick = onNavigateToDownloadManager),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            LinearProgressIndicator(
                progress = { (progress.percentage / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp)
            )

            if (progress.currentSong.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_downloading, progress.currentSong),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        if (batchDownloadProgress == null) {
            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.settings_download_manager),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                headlineContent = { Text(stringResource(R.string.download_title)) },
                supportingContent = { Text(stringResource(R.string.download_desc)) },
                modifier = Modifier.settingsItemClickable(onClick = onNavigateToDownloadManager),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}
