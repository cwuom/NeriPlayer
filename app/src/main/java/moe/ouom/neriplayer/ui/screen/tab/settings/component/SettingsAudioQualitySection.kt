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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.generated.AutoSettingInfo
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

private const val NETEASE_LOSSLESS_QUALITY = "lossless"
private const val NETEASE_HIRES_QUALITY = "hires"
private const val NETEASE_HD_SURROUND_QUALITY = "jyeffect"
private const val NETEASE_SURROUND_QUALITY = "sky"
private const val NETEASE_MASTER_QUALITY = "jymaster"
private const val BILI_DOLBY_QUALITY = "dolby"

private val NETEASE_MEMBER_QUALITIES = setOf(
    NETEASE_LOSSLESS_QUALITY,
    NETEASE_HIRES_QUALITY,
    NETEASE_HD_SURROUND_QUALITY,
    NETEASE_SURROUND_QUALITY,
    NETEASE_MASTER_QUALITY
)

private enum class AudioQualityNotice {
    NeteaseMemberQuality,
    BiliDolby
}

@Composable
internal fun SettingsAudioQualitySection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
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
    var audioQualityNotice by remember { mutableStateOf<AudioQualityNotice?>(null) }

    if (showHeader) {
        ExpandableHeader(
            icon = Icons.Filled.Audiotrack,
            title = stringResource(R.string.settings_audio_quality),
            subtitleCollapsed = stringResource(R.string.settings_audio_quality_expand),
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
            AudioQualityListItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.AUDIO_QUALITY),
                valueLabel = qualityLabel,
                preferredQuality = preferredQuality,
                iconRes = R.drawable.ic_netease_cloud_music,
                onClick = { onShowQualityDialogChange(true) }
            )

            AudioQualityListItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.YOUTUBE_AUDIO_QUALITY),
                valueLabel = youtubeQualityLabel,
                preferredQuality = youtubePreferredQuality,
                iconRes = R.drawable.ic_youtube,
                onClick = { onShowYouTubeQualityDialogChange(true) }
            )

            AudioQualityListItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BILI_AUDIO_QUALITY),
                valueLabel = biliQualityLabel,
                preferredQuality = biliPreferredQuality,
                iconRes = R.drawable.ic_bilibili,
                onClick = { onShowBiliQualityDialogChange(true) }
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
                NETEASE_LOSSLESS_QUALITY to stringResource(R.string.quality_lossless),
                NETEASE_HIRES_QUALITY to stringResource(R.string.quality_hires),
                NETEASE_HD_SURROUND_QUALITY to stringResource(R.string.quality_hd_surround),
                NETEASE_SURROUND_QUALITY to stringResource(R.string.quality_surround),
                NETEASE_MASTER_QUALITY to stringResource(R.string.settings_audio_quality_jymaster)
            ),
            onDismiss = { onShowQualityDialogChange(false) },
            onSelect = { level ->
                onQualityChange(level)
                onShowQualityDialogChange(false)
                if (level in NETEASE_MEMBER_QUALITIES && preferredQuality != level) {
                    audioQualityNotice = AudioQualityNotice.NeteaseMemberQuality
                }
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
                BILI_DOLBY_QUALITY to stringResource(R.string.settings_dolby),
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
                if (level == BILI_DOLBY_QUALITY && biliPreferredQuality != level) {
                    audioQualityNotice = AudioQualityNotice.BiliDolby
                }
            }
        )
    }

    audioQualityNotice?.let { notice ->
        AudioQualityNoticeDialog(
            notice = notice,
            onDismiss = { audioQualityNotice = null }
        )
    }
}

@Composable
private fun AudioQualityListItem(
    setting: AutoSettingInfo,
    valueLabel: String,
    preferredQuality: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    AutoSettingsListItem(
        setting = setting,
        leadingContent = {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = stringResource(setting.titleRes),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(stringResource(R.string.common_label_value_format, valueLabel, preferredQuality))
        },
        onClick = onClick
    )
}

@Composable
private fun QualityOptionsDialog(
    title: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (level, label) ->
                    MiuixSettingsChoiceRow(
                        title = label,
                        selected = level == selectedValue,
                        onClick = { onSelect(level) }
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun AudioQualityNoticeDialog(
    notice: AudioQualityNotice,
    onDismiss: () -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_hint)) },
        text = {
            Text(
                stringResource(
                    when (notice) {
                        AudioQualityNotice.NeteaseMemberQuality ->
                            R.string.settings_audio_quality_netease_member_quality_notice
                        AudioQualityNotice.BiliDolby ->
                            R.string.settings_audio_quality_bili_dolby_notice
                    }
                )
            )
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}
