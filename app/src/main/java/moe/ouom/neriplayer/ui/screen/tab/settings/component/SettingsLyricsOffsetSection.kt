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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsLyricsSection
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
import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.LYRIC_DEFAULT_OFFSET_STEP_MS
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchItems
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import kotlin.math.roundToLong

private val LYRIC_OFFSET_SLIDER_STEPS =
    ((MAX_LYRIC_DEFAULT_OFFSET_MS - MIN_LYRIC_DEFAULT_OFFSET_MS) / LYRIC_DEFAULT_OFFSET_STEP_MS)
        .toInt() - 1
private val LYRIC_OFFSET_STEP_MS_FLOAT = LYRIC_DEFAULT_OFFSET_STEP_MS.toFloat()

@Composable
internal fun SettingsLyricsSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
    autoSettingsRepository: AutoSettingsRepository,
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
    floatingLyricsPreferences: FloatingLyricsPreferences,
    onFloatingLyricsPreferencesChange: (FloatingLyricsPreferences) -> Unit,
    lyricsAppearanceContent: @Composable () -> Unit,
    cloudMusicLyricDefaultOffsetMs: Long,
    onCloudMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    qqMusicLyricDefaultOffsetMs: Long,
    onQqMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    cardIndex: Int? = null,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    fun shouldShowCard(index: Int): Boolean = cardIndex == null || cardIndex == index

    if (showHeader) {
        ExpandableHeader(
            icon = Icons.Outlined.Subtitles,
            title = stringResource(R.string.settings_lyrics_offset),
            subtitleCollapsed = stringResource(R.string.settings_lyrics_offset_expand),
            subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
            expanded = expanded,
            onToggle = { onExpandedChange(!expanded) },
            arrowRotation = arrowRotation
        )
    }

    LazyAnimatedVisibility(visible = expanded || !showHeader) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (showHeader) 16.dp else 0.dp,
                    end = if (showHeader) 8.dp else 0.dp,
                    bottom = if (showHeader) 8.dp else 0.dp
                )
        ) {
            if (shouldShowCard(0)) LyricsDetailCard(
                showCard = !showHeader,
                highlightPulse = highlightPulse,
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_lyrics_floating_section),
                    description = stringResource(R.string.settings_lyrics_floating_section_desc)
                )
                SettingsFloatingLyricsSection(
                    preferences = floatingLyricsPreferences,
                    onPreferencesChange = onFloatingLyricsPreferencesChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
            }
            if (cardIndex == null) LyricsDetailGap(showHeader)
            if (shouldShowCard(1)) LyricsDetailCard(
                showCard = !showHeader,
                highlightPulse = highlightPulse,
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_lyrics_source_section),
                    description = stringResource(R.string.settings_lyrics_source_section_desc)
                )
                AutoSettingsSwitchItems(
                    repository = autoSettingsRepository,
                    scope = scope,
                    sectionScope = AutoSettingsScopes.lyrics,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                DynamicIslandLyricsSetting(
                    repository = settingsRepository,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
            }
            if (cardIndex == null) LyricsDetailGap(showHeader)
            if (shouldShowCard(2)) LyricsDetailCard(
                showCard = !showHeader,
                highlightPulse = highlightPulse,
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_lyrics_offset_section),
                    description = stringResource(R.string.settings_lyrics_offset_section_desc)
                )
                LyricsOffsetSliderListItem(
                    targetId = "setting:cloud_music_lyric_default_offset_ms",
                    title = stringResource(R.string.settings_lyrics_offset_cloud_music),
                    description = stringResource(R.string.settings_lyrics_offset_cloud_music_desc),
                    offsetMs = cloudMusicLyricDefaultOffsetMs,
                    onOffsetChange = onCloudMusicLyricDefaultOffsetMsChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                Spacer(Modifier.height(4.dp))
                LyricsOffsetSliderListItem(
                    targetId = "setting:qq_music_lyric_default_offset_ms",
                    title = stringResource(R.string.settings_lyrics_offset_qq_music),
                    description = stringResource(R.string.settings_lyrics_offset_qq_music_desc),
                    offsetMs = qqMusicLyricDefaultOffsetMs,
                    onOffsetChange = onQqMusicLyricDefaultOffsetMsChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
            }
            if (cardIndex == null) LyricsDetailGap(showHeader)
            if (shouldShowCard(3)) LyricsDetailCard(
                showCard = !showHeader,
                highlightPulse = highlightPulse,
            ) {
                lyricsAppearanceContent()
            }
        }
    }
}

@Composable
private fun LyricsDetailCard(
    showCard: Boolean,
    highlightPulse: Int = 0,
    content: @Composable () -> Unit
) {
    if (showCard) {
        MiuixSettingsSectionCard(
            highlighted = false,
            highlightPulse = highlightPulse,
            onHighlightFinished = null,
            content = content
        )
    } else {
        content()
    }
}

@Composable
private fun LyricsDetailGap(showHeader: Boolean) {
    if (!showHeader) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LyricsOffsetSliderListItem(
    targetId: String,
    title: String,
    description: String,
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    var pendingOffset by remember { mutableLongStateOf(offsetMs) }

    LaunchedEffect(offsetMs) {
        if (pendingOffset != offsetMs) {
            pendingOffset = offsetMs
        }
    }

    ListItem(
        modifier = Modifier.settingsHighlightTarget(
            targetId = targetId,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        ),
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
                MiuixSettingsSlider(
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
