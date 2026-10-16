package moe.ouom.neriplayer.ui.screen.nowplaying.actions

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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaSectionsLegacy
 * Created: 2025/10/17
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaAward
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCredits
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaMusicInfo
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia

/** 基本信息 + 音乐属性 + 创作团队 + 获奖收录（任务书模块 3、4 的子集）。 */
@Composable
internal fun SongEncyclopediaLegacySections(data: SongEncyclopedia) {
    val basic = data.basicInfo
    if (basic.name.isNotBlank() || basic.artists.isNotEmpty() || !basic.album.isNullOrBlank()) {
        SongEncyclopediaSubsectionTitle(CoreCommonR.string.encyclopedia_section_basic)
        basic.name.takeIf { it.isNotBlank() }?.let { name ->
            SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_song, name)
        }
        SongEncyclopediaRowIfAny(
            labelRes = CoreCommonR.string.encyclopedia_label_artists,
            values = basic.artists
        )
        basic.album?.takeIf { it.isNotBlank() }?.let { album ->
            SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_album, album)
        }
    }

    data.musicInfo?.let { SongEncyclopediaMusicSection(it) }
    data.credits?.let { SongEncyclopediaCreditsSection(it) }

    if (data.awards.isNotEmpty() || data.appearances.isNotEmpty()) {
        SongEncyclopediaSubsectionTitle(CoreCommonR.string.encyclopedia_section_awards)
        data.awards.forEach { SongEncyclopediaAwardRow(it) }
        data.appearances.forEach { appearance ->
            SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_appearance, appearance)
        }
    }
}

@Composable
private fun SongEncyclopediaMusicSection(musicInfo: EncyclopediaMusicInfo) {
    val hasContent = musicInfo.genres.isNotEmpty() ||
        musicInfo.tags.isNotEmpty() ||
        !musicInfo.language.isNullOrBlank() ||
        (musicInfo.bpm ?: 0) > 0
    if (!hasContent) return
    SongEncyclopediaSubsectionTitle(CoreCommonR.string.encyclopedia_section_music)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_genres, musicInfo.genres)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_tags, musicInfo.tags)
    musicInfo.language?.takeIf { it.isNotBlank() }?.let { language ->
        SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_language, language)
    }
    musicInfo.bpm?.takeIf { it > 0 }?.let { bpm ->
        SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_bpm, bpm.toString())
    }
}

@Composable
private fun SongEncyclopediaCreditsSection(credits: EncyclopediaCredits) {
    val hasContent = credits.lyricists.isNotEmpty() ||
        credits.composers.isNotEmpty() ||
        credits.arrangers.isNotEmpty() ||
        credits.producers.isNotEmpty() ||
        credits.otherRoles.isNotEmpty()
    if (!hasContent) return
    SongEncyclopediaSubsectionTitle(CoreCommonR.string.encyclopedia_section_credits)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_lyricist, credits.lyricists)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_composer, credits.composers)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_arranger, credits.arrangers)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_producer, credits.producers)
    credits.otherRoles.forEach { role ->
        SongEncyclopediaNamedRowIfAny(role.role, role.names)
    }
}

@Composable
private fun SongEncyclopediaAwardRow(award: EncyclopediaAward) {
    val detail = award.detail?.takeIf { it.isNotBlank() }
    ListItem(
        headlineContent = { Text(award.title) },
        supportingContent = {
            if (detail != null) Text(detail)
        }
    )
}

@Composable
internal fun SongEncyclopediaRow(labelRes: Int, value: String) {
    SongEncyclopediaRow(stringResource(labelRes), value)
}

@Composable
internal fun SongEncyclopediaRowIfAny(labelRes: Int, values: List<String>) {
    SongEncyclopediaNamedRowIfAny(stringResource(labelRes), values)
}

@Composable
internal fun SongEncyclopediaNamedRowIfAny(label: String, values: List<String>) {
    val text = values.filter { it.isNotBlank() }.joinToString("、")
    if (text.isEmpty()) return
    SongEncyclopediaRow(label, text)
}

@Composable
internal fun SongEncyclopediaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
