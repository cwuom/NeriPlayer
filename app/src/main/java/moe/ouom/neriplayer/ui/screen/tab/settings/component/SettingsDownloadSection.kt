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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.download.MAX_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.download.normalizeDownloadParallelism
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.settings.normalizeDownloadBiliAudioQuality
import moe.ouom.neriplayer.data.settings.normalizeDownloadNeteaseAudioQuality
import moe.ouom.neriplayer.data.settings.normalizeDownloadYouTubeAudioQuality
import moe.ouom.neriplayer.data.settings.readDownloadFollowPlaybackAudioQualityStartupValue
import moe.ouom.neriplayer.data.settings.setDownloadFollowPlaybackAudioQuality
import moe.ouom.neriplayer.data.settings.updateDownloadFollowPlaybackAudioQualityStartupValue
import moe.ouom.neriplayer.ksp.annotations.AutoSettingSpec
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard

@Composable
internal fun SettingsDownloadSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
    onNavigateToDownloadManager: () -> Unit,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
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
            onNavigateToDownloadManager = onNavigateToDownloadManager,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
    }
}

@Composable
private fun SettingsDownloadExpandedContent(
    indentContent: Boolean,
    onNavigateToDownloadManager: () -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val batchDownloadProgress by GlobalDownloadManager.batchDownloadProgressFlow.collectAsState()
    val taskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
    val visibleProgress = batchDownloadProgress?.takeIf { taskSummary.hasPendingTasks }

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
            setting = AutoSettingsSchema.download.downloadMetadataPostProcessingEnabled,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

        Spacer(modifier = Modifier.height(4.dp))

        AutoSettingSpecSwitchItem(
            setting = AutoSettingsSchema.download.standardizedLyricEmbeddingEnabled,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

        Spacer(modifier = Modifier.height(4.dp))

        DownloadParallelismSettingItem(
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

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

private enum class DownloadAudioQualityPlatform {
    NETEASE,
    YOUTUBE,
    BILI
}

internal const val DOWNLOAD_QUALITY_FOLLOW_PLAYBACK_CARD_TEST_TAG =
    "download-quality-follow-playback-card"
internal const val DOWNLOAD_QUALITY_PLATFORM_OPTIONS_TEST_TAG =
    "download-quality-platform-options"

@Composable
internal fun SettingsDownloadQualityFollowPlaybackCard(
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val followPlaybackSetting = AutoSettingsSchema.download.downloadFollowPlaybackAudioQuality
    val initialFollowPlaybackQuality = remember(context) {
        readDownloadFollowPlaybackAudioQualityStartupValue(context)
    }
    val followPlaybackQualityFlow = remember(context, followPlaybackSetting) {
        context.autoSettingFlow(followPlaybackSetting)
    }
    val followPlaybackQuality by followPlaybackQualityFlow.collectAsState(
        initial = initialFollowPlaybackQuality
    )

    LaunchedEffect(followPlaybackQualityFlow, context) {
        followPlaybackQualityFlow.collect { followsPlaybackQuality ->
            updateDownloadFollowPlaybackAudioQualityStartupValue(
                context = context,
                followsPlaybackQuality = followsPlaybackQuality
            )
        }
    }

    MiuixSettingsSectionCard(
        modifier = modifier.testTag(DOWNLOAD_QUALITY_FOLLOW_PLAYBACK_CARD_TEST_TAG)
    ) {
        AutoSettingSpecSwitchItem(
            setting = followPlaybackSetting,
            checked = followPlaybackQuality,
            onCheckedChange = { followsPlaybackQuality ->
                scope.launch {
                    setDownloadFollowPlaybackAudioQuality(
                        context = context,
                        followsPlaybackQuality = followsPlaybackQuality
                    )
                }
            },
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

        DownloadAudioQualitySettings(
            followsPlaybackQuality = followPlaybackQuality,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
    }
}

@Composable
private fun DownloadAudioQualitySettings(
    followsPlaybackQuality: Boolean,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val repository = rememberAutoSettingSpecRepository()
    val scope = rememberCoroutineScope()
    val neteaseSetting = AutoSettingsSchema.download.downloadNeteaseAudioQuality
    val youtubeSetting = AutoSettingsSchema.download.downloadYouTubeAudioQuality
    val biliSetting = AutoSettingsSchema.download.downloadBiliAudioQuality
    val neteaseQuality by remember(repository, neteaseSetting) {
        repository.flow(neteaseSetting)
    }.collectAsState(initial = neteaseSetting.defaultValue)
    val youtubeQuality by remember(repository, youtubeSetting) {
        repository.flow(youtubeSetting)
    }.collectAsState(initial = youtubeSetting.defaultValue)
    val biliQuality by remember(repository, biliSetting) {
        repository.flow(biliSetting)
    }.collectAsState(initial = biliSetting.defaultValue)
    var dialogPlatform by remember { mutableStateOf<DownloadAudioQualityPlatform?>(null) }

    if (!followsPlaybackQuality) {
        Column(modifier = Modifier.testTag(DOWNLOAD_QUALITY_PLATFORM_OPTIONS_TEST_TAG)) {
            Text(
                text = stringResource(R.string.settings_download_quality_group),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            DownloadAudioQualityListItem(
                setting = neteaseSetting,
                value = normalizeDownloadNeteaseAudioQuality(neteaseQuality),
                valueLabel = stringResource(
                    when (normalizeDownloadNeteaseAudioQuality(neteaseQuality)) {
                        "standard" -> R.string.quality_standard
                        "higher" -> R.string.quality_high
                        "exhigh" -> R.string.quality_very_high
                        "lossless" -> R.string.quality_lossless
                        "hires" -> R.string.quality_hires
                        "jyeffect" -> R.string.quality_hd_surround
                        "sky" -> R.string.quality_surround
                        else -> R.string.settings_audio_quality_jymaster
                    }
                ),
                onClick = { dialogPlatform = DownloadAudioQualityPlatform.NETEASE },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )

            DownloadAudioQualityListItem(
                setting = youtubeSetting,
                value = normalizeDownloadYouTubeAudioQuality(youtubeQuality),
                valueLabel = stringResource(
                    when (normalizeDownloadYouTubeAudioQuality(youtubeQuality)) {
                        "low" -> R.string.settings_audio_quality_low
                        "medium" -> R.string.settings_audio_quality_medium
                        "high" -> R.string.settings_audio_quality_high
                        else -> R.string.quality_very_high
                    }
                ),
                onClick = { dialogPlatform = DownloadAudioQualityPlatform.YOUTUBE },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )

            DownloadAudioQualityListItem(
                setting = biliSetting,
                value = normalizeDownloadBiliAudioQuality(biliQuality),
                valueLabel = stringResource(
                    when (normalizeDownloadBiliAudioQuality(biliQuality)) {
                        "dolby" -> R.string.settings_dolby
                        "hires" -> R.string.quality_hires
                        "lossless" -> R.string.quality_lossless
                        "high" -> R.string.settings_audio_quality_high
                        "medium" -> R.string.settings_audio_quality_medium
                        else -> R.string.settings_audio_quality_low
                    }
                ),
                onClick = { dialogPlatform = DownloadAudioQualityPlatform.BILI },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
        }
    }

    dialogPlatform?.let { platform ->
        when (platform) {
            DownloadAudioQualityPlatform.NETEASE -> {
                QualityOptionsDialog(
                    title = stringResource(R.string.settings_download_netease_audio_quality),
                    selectedValue = normalizeDownloadNeteaseAudioQuality(neteaseQuality),
                    options = listOf(
                        "standard" to stringResource(R.string.quality_standard),
                        "higher" to stringResource(R.string.quality_high),
                        "exhigh" to stringResource(R.string.quality_very_high),
                        "lossless" to stringResource(R.string.quality_lossless),
                        "hires" to stringResource(R.string.quality_hires),
                        "jyeffect" to stringResource(R.string.quality_hd_surround),
                        "sky" to stringResource(R.string.quality_surround),
                        "jymaster" to stringResource(R.string.settings_audio_quality_jymaster)
                    ),
                    onDismiss = { dialogPlatform = null },
                    onSelect = { value ->
                        dialogPlatform = null
                        scope.launch { repository.set(neteaseSetting, value) }
                    }
                )
            }

            DownloadAudioQualityPlatform.YOUTUBE -> {
                QualityOptionsDialog(
                    title = stringResource(R.string.settings_download_youtube_audio_quality),
                    selectedValue = normalizeDownloadYouTubeAudioQuality(youtubeQuality),
                    options = listOf(
                        "low" to stringResource(R.string.settings_audio_quality_low),
                        "medium" to stringResource(R.string.settings_audio_quality_medium),
                        "high" to stringResource(R.string.settings_audio_quality_high),
                        "very_high" to stringResource(R.string.quality_very_high)
                    ),
                    onDismiss = { dialogPlatform = null },
                    onSelect = { value ->
                        dialogPlatform = null
                        scope.launch { repository.set(youtubeSetting, value) }
                    }
                )
            }

            DownloadAudioQualityPlatform.BILI -> {
                QualityOptionsDialog(
                    title = stringResource(R.string.settings_download_bili_audio_quality),
                    selectedValue = normalizeDownloadBiliAudioQuality(biliQuality),
                    options = listOf(
                        "dolby" to stringResource(R.string.settings_dolby),
                        "hires" to stringResource(R.string.quality_hires),
                        "lossless" to stringResource(R.string.quality_lossless),
                        "high" to stringResource(R.string.settings_audio_quality_high),
                        "medium" to stringResource(R.string.settings_audio_quality_medium),
                        "low" to stringResource(R.string.settings_audio_quality_low)
                    ),
                    onDismiss = { dialogPlatform = null },
                    onSelect = { value ->
                        dialogPlatform = null
                        scope.launch { repository.set(biliSetting, value) }
                    }
                )
            }
        }
    }
}

@Composable
private fun DownloadAudioQualityListItem(
    setting: AutoSettingSpec<String>,
    value: String,
    valueLabel: String,
    onClick: () -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    AutoSettingSpecListItem(
        setting = setting,
        supportingContent = {
            Text(stringResource(R.string.common_label_value_format, valueLabel, value))
        },
        onClick = onClick,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun DownloadParallelismSettingItem(
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
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
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
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
                            repository.setDownloadParallelism(nextValue)
                        }
                    },
                    valueRange = 1f..MAX_DOWNLOAD_PARALLELISM.toFloat(),
                    steps = MAX_DOWNLOAD_PARALLELISM - 2
                )
            }
        }
    )
}
