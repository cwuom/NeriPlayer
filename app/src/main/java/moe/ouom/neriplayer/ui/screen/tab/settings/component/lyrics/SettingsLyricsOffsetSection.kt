package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics

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

import moe.ouom.neriplayer.ui.screen.tab.settings.component.ExpandableHeader
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.floating.SettingsFloatingLyricsSection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_KUGOU_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_LRCLIB_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_BILI_CC_SUBTITLE_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.component.XiaomiSuperIslandLyricsSetting
import moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics.coloros.ColorOsLockscreenLyricsSetting
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.ui.settings.AutoSettingsSwitchItems
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro

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
    kugouLyricDefaultOffsetMs: Long,
    onKugouLyricDefaultOffsetMsChange: (Long) -> Unit,
    lrclibLyricDefaultOffsetMs: Long,
    onLrclibLyricDefaultOffsetMsChange: (Long) -> Unit,
    amllTtmlLyricDefaultOffsetMs: Long,
    onAmllTtmlLyricDefaultOffsetMsChange: (Long) -> Unit,
    biliCcSubtitleLyricDefaultOffsetMs: Long,
    onBiliCcSubtitleLyricDefaultOffsetMsChange: (Long) -> Unit,
    onResetAllLyricDefaultOffsets: () -> Unit,
    cardIndex: Int? = null,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    fun shouldShowCard(index: Int): Boolean = cardIndex == null || cardIndex == index

    if (showHeader) {
        ExpandableHeader(
            icon = Icons.Outlined.Subtitles,
            title = stringResource(CoreCommonR.string.settings_lyrics_offset),
            subtitleCollapsed = stringResource(CoreCommonR.string.settings_lyrics_offset_expand),
            subtitleExpanded = stringResource(CoreCommonR.string.settings_login_platforms_collapse),
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
                    title = stringResource(CoreCommonR.string.settings_lyrics_floating_section),
                    description = stringResource(CoreCommonR.string.settings_lyrics_floating_section_desc)
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
                    title = stringResource(CoreCommonR.string.settings_lyric_source),
                    description = stringResource(CoreCommonR.string.settings_lyric_source_desc)
                )
                SettingsLyricSourceSection(
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
                    title = stringResource(CoreCommonR.string.settings_lyrics_source_section),
                    description = stringResource(CoreCommonR.string.settings_lyrics_source_section_desc)
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
                XiaomiSuperIslandLyricsSetting(
                    autoSettingsRepository = autoSettingsRepository,
                    settingsRepository = settingsRepository,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                ColorOsLockscreenLyricsSetting(
                    repository = autoSettingsRepository,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished,
                )
                BluetoothMetadataModeSetting(
                    repository = settingsRepository,
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiuixSettingsSectionIntro(
                        title = stringResource(CoreCommonR.string.settings_lyrics_offset_section),
                        description = stringResource(CoreCommonR.string.settings_lyrics_offset_section_desc),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onResetAllLyricDefaultOffsets) {
                        Text(stringResource(CoreCommonR.string.settings_lyrics_offset_reset_all))
                    }
                }
                LyricsOffsetSliderListItem(
                    targetId = "setting:cloud_music_lyric_default_offset_ms",
                    title = stringResource(CoreCommonR.string.settings_lyrics_offset_cloud_music),
                    description = stringResource(CoreCommonR.string.settings_lyrics_offset_cloud_music_desc),
                    offsetMs = cloudMusicLyricDefaultOffsetMs,
                    defaultOffsetMs = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS,
                    onOffsetChange = onCloudMusicLyricDefaultOffsetMsChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                Spacer(Modifier.height(4.dp))
                LyricsOffsetSliderListItem(
                    targetId = "setting:qq_music_lyric_default_offset_ms",
                    title = stringResource(CoreCommonR.string.settings_lyrics_offset_qq_music),
                    description = stringResource(CoreCommonR.string.settings_lyrics_offset_qq_music_desc),
                    offsetMs = qqMusicLyricDefaultOffsetMs,
                    defaultOffsetMs = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS,
                    onOffsetChange = onQqMusicLyricDefaultOffsetMsChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                Spacer(Modifier.height(4.dp))
                LyricsOffsetSliderListItem(
                    targetId = "setting:kugou_lyric_default_offset_ms",
                    title = stringResource(CoreCommonR.string.settings_lyrics_offset_kugou),
                    description = stringResource(CoreCommonR.string.settings_lyrics_offset_kugou_desc),
                    offsetMs = kugouLyricDefaultOffsetMs,
                    defaultOffsetMs = DEFAULT_KUGOU_LYRIC_OFFSET_MS,
                    onOffsetChange = onKugouLyricDefaultOffsetMsChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                Spacer(Modifier.height(4.dp))
                LyricsOffsetSliderListItem(
                    targetId = "setting:lrclib_lyric_default_offset_ms",
                    title = stringResource(CoreCommonR.string.settings_lyrics_offset_lrclib),
                    description = stringResource(CoreCommonR.string.settings_lyrics_offset_lrclib_desc),
                    offsetMs = lrclibLyricDefaultOffsetMs,
                    defaultOffsetMs = DEFAULT_LRCLIB_LYRIC_OFFSET_MS,
                    onOffsetChange = onLrclibLyricDefaultOffsetMsChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                Spacer(Modifier.height(4.dp))
                LyricsOffsetSliderListItem(
                    targetId = "setting:amll_ttml_lyric_default_offset_ms",
                    title = stringResource(CoreCommonR.string.settings_lyrics_offset_amll_ttml),
                    description = stringResource(CoreCommonR.string.settings_lyrics_offset_amll_ttml_desc),
                    offsetMs = amllTtmlLyricDefaultOffsetMs,
                    defaultOffsetMs = DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS,
                    onOffsetChange = onAmllTtmlLyricDefaultOffsetMsChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                Spacer(Modifier.height(4.dp))
                LyricsOffsetSliderListItem(
                    targetId = "setting:bili_cc_subtitle_lyric_default_offset_ms",
                    title = stringResource(CoreCommonR.string.settings_lyrics_offset_bili_cc_subtitle),
                    description = stringResource(CoreCommonR.string.settings_lyrics_offset_bili_cc_subtitle_desc),
                    offsetMs = biliCcSubtitleLyricDefaultOffsetMs,
                    defaultOffsetMs = DEFAULT_BILI_CC_SUBTITLE_LYRIC_OFFSET_MS,
                    onOffsetChange = onBiliCcSubtitleLyricDefaultOffsetMsChange,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
                Spacer(Modifier.height(12.dp))
            }
            if (cardIndex == null) LyricsDetailGap(showHeader)
            if (shouldShowCard(4)) LyricsDetailCard(
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
