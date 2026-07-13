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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.MAX_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.download.normalizeDownloadParallelism
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun SettingsDownloadSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
    onNavigateToDownloadManager: () -> Unit
) {
    if (showHeader) {
        ExpandableHeader(
            icon = Icons.Outlined.Download,
            title = stringResource(R.string.settings_download_management),
            subtitleCollapsed = stringResource(R.string.settings_download_expand),
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
        SettingsDownloadExpandedContent(
            indentContent = showHeader,
            onNavigateToDownloadManager = onNavigateToDownloadManager
        )
    }
}

@Composable
private fun SettingsDownloadExpandedContent(
    indentContent: Boolean,
    onNavigateToDownloadManager: () -> Unit
) {
    val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
    val taskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
    val visibleProgress = batchDownloadProgress?.takeIf { progress ->
        taskSummary.hasPendingTasks &&
            (taskSummary.pendingTaskCount <= 1 || progress.totalSongs >= taskSummary.pendingTaskCount)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(
                start = if (indentContent) 16.dp else 0.dp,
                end = if (indentContent) 8.dp else 0.dp,
                bottom = if (indentContent) 8.dp else 0.dp
            )
    ) {
        AutoSettingSpecSwitchItem(
            setting = AutoSettingsSchema.download.downloadMetadataPostProcessingEnabled
        )

        Spacer(modifier = Modifier.height(4.dp))

        AutoSettingSpecSwitchItem(
            setting = AutoSettingsSchema.download.standardizedLyricEmbeddingEnabled
        )

        Spacer(modifier = Modifier.height(4.dp))

        DownloadParallelismSettingItem()

        Spacer(modifier = Modifier.height(4.dp))

        if (visibleProgress != null || taskSummary.hasPendingTasks) {
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
                    if (visibleProgress != null) {
                        Text(
                            stringResource(
                                R.string.settings_download_songs_count,
                                visibleProgress.completedSongs,
                                visibleProgress.totalSongs
                            )
                        )
                    } else {
                        Text(
                            pluralStringResource(
                                R.plurals.download_tasks_count,
                                taskSummary.pendingTaskCount,
                                taskSummary.pendingTaskCount
                            )
                        )
                    }
                },
                trailingContent = {
                    if (taskSummary.hasPendingTasks) {
                        MiuixSettingsTextButton(
                            onClick = { GlobalDownloadManager.cancelAllDownloadTasks() },
                            enabled = taskSummary.hasPendingTasks
                        ) {
                            Text(
                                stringResource(R.string.action_cancel),
                                color = if (taskSummary.hasPendingTasks) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.settingsItemClickable(onClick = onNavigateToDownloadManager),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            if (visibleProgress != null) {
                LinearProgressIndicator(
                    progress = { (visibleProgress.percentage / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp)
                )
            }

        } else {
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

@Composable
private fun DownloadParallelismSettingItem() {
    val setting = AutoSettingsSchema.download.downloadParallelism
    val repository = rememberAutoSettingSpecRepository()
    val scope = rememberCoroutineScope()
    val flow = remember(repository, setting) { repository.flow(setting) }
    val savedValue by flow.collectAsState(initial = setting.defaultValue)
    val normalizedValue = normalizeDownloadParallelism(savedValue)
    var sliderValue by remember { mutableFloatStateOf(normalizedValue.toFloat()) }

    LaunchedEffect(normalizedValue) {
        if (sliderValue.roundToInt() != normalizedValue) {
            sliderValue = normalizedValue.toFloat()
        }
    }

    AutoSettingSpecListItem(
        setting = setting,
        supportingContent = {
            val displayValue = normalizeDownloadParallelism(sliderValue.roundToInt())
            Column {
                Text(
                    text = stringResource(
                        R.string.settings_download_parallelism_current,
                        displayValue,
                        MAX_DOWNLOAD_PARALLELISM
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MiuixSettingsSlider(
                    value = sliderValue,
                    onValueChange = { value ->
                        sliderValue = normalizeDownloadParallelism(value.roundToInt()).toFloat()
                    },
                    onValueChangeFinished = {
                        val nextValue = normalizeDownloadParallelism(sliderValue.roundToInt())
                        sliderValue = nextValue.toFloat()
                        scope.launch {
                            repository.set(setting, nextValue)
                        }
                    },
                    valueRange = 1f..MAX_DOWNLOAD_PARALLELISM.toFloat(),
                    steps = MAX_DOWNLOAD_PARALLELISM - 2
                )
            }
        }
    )
}
