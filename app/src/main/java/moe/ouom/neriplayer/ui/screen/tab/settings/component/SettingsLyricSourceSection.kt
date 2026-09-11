package moe.ouom.neriplayer.ui.screen.tab.settings.component

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsLyricSourceSection
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.LyricSourcePreference
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

/**
 * 歌词源设置页内容。
 *
 * 自包含: 只依赖 [SettingsRepository], 由调用方直接传 `AppContainer.settingsRepo`,
 * 这样设置页宿主不需要为此新增参数。
 */
@Composable
internal fun SettingsLyricSourceSection(
    repository: SettingsRepository,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val preferWordTimedLyrics by repository.preferWordTimedLyricsFlow.collectAsState(
        initial = true
    )
    val defaultLyricSource by repository.defaultLyricSourceFlow.collectAsState(
        initial = LyricSourcePreference.Automatic
    )
    var showSourceDialog by remember { mutableStateOf(false) }

    AutoSettingSpecListItem(
        setting = AutoSettingsSchema.lyricSource.preferWordTimedLyrics,
        trailingContent = {
            MiuixSettingsSwitch(
                checked = preferWordTimedLyrics,
                onCheckedChange = { enabled ->
                    scope.launch { repository.setPreferWordTimedLyrics(enabled) }
                }
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = {
            scope.launch { repository.setPreferWordTimedLyrics(!preferWordTimedLyrics) }
        }
    )

    AutoSettingSpecListItem(
        setting = AutoSettingsSchema.lyricSource.defaultLyricSource,
        supportingContent = {
            Text(
                stringResource(
                    R.string.settings_lyric_source_current,
                    stringResource(lyricSourcePreferenceLabel(defaultLyricSource))
                )
            )
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
        onClick = { showSourceDialog = true }
    )

    if (!showSourceDialog) return

    MiuixSettingsDialog(
        onDismissRequest = { showSourceDialog = false },
        title = { Text(stringResource(R.string.settings_default_lyric_source)) },
        text = {
            Column {
                LyricSourcePreference.entries.forEach { option ->
                    MiuixSettingsChoiceRow(
                        title = stringResource(lyricSourcePreferenceLabel(option)),
                        subtitle = stringResource(lyricSourcePreferenceDescription(option)),
                        selected = option == defaultLyricSource,
                        onClick = {
                            scope.launch { repository.setDefaultLyricSource(option) }
                            showSourceDialog = false
                        }
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = { showSourceDialog = false },
                text = { Text(stringResource(R.string.action_close)) }
            )
        }
    )
}

internal fun lyricSourcePreferenceLabel(source: LyricSourcePreference): Int = when (source) {
    LyricSourcePreference.Automatic -> R.string.settings_lyric_source_automatic
    LyricSourcePreference.CloudMusic -> R.string.settings_lyric_source_cloud_music
    LyricSourcePreference.Kugou -> R.string.settings_lyric_source_kugou
    LyricSourcePreference.QqMusic -> R.string.settings_lyric_source_qq_music
    LyricSourcePreference.LrcLib -> R.string.settings_lyric_source_lrclib
    LyricSourcePreference.AmllTtml -> R.string.settings_lyric_source_amll_ttml
}

private fun lyricSourcePreferenceDescription(source: LyricSourcePreference): Int = when (source) {
    LyricSourcePreference.Automatic -> R.string.settings_lyric_source_automatic_desc
    LyricSourcePreference.CloudMusic -> R.string.settings_lyric_source_cloud_music_desc
    LyricSourcePreference.Kugou -> R.string.settings_lyric_source_kugou_desc
    LyricSourcePreference.QqMusic -> R.string.settings_lyric_source_qq_music_desc
    LyricSourcePreference.LrcLib -> R.string.settings_lyric_source_lrclib_desc
    LyricSourcePreference.AmllTtml -> R.string.settings_lyric_source_amll_ttml_desc
}
