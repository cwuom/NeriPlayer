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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaSongDetailSection
 * Created: 2025/10/17
 */

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia

private const val SONG_DETAIL_COLLAPSED_COUNT = 4

/**
 * 模块三 歌曲详情：曲风 → 专辑 → 语种 → 发行日期 → BPM → 乐器 → 制作。
 *
 * 所有字段只在数据源真实给出时展示；缺失一律隐藏或显示「暂无信息」，不做任何推断。
 */
@Composable
internal fun SongEncyclopediaSongDetailSection(data: SongEncyclopedia) {
    val rows = buildSongDetailRows(data)
    if (rows.isEmpty()) return
    SongEncyclopediaSubsectionTitle(CoreCommonR.string.encyclopedia_section_song_detail)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = if (expanded) rows else rows.take(SONG_DETAIL_COLLAPSED_COUNT)
    visible.forEach { (labelRes, value) -> SongEncyclopediaRow(labelRes, value) }
    if (rows.size > SONG_DETAIL_COLLAPSED_COUNT) {
        SongEncyclopediaExpandAction(expanded = expanded, onToggle = { expanded = !expanded })
    }
}

private fun buildSongDetailRows(data: SongEncyclopedia): List<Pair<Int, String>> {
    val rows = mutableListOf<Pair<Int, String>>()
    val musicInfo = data.musicInfo
    if (musicInfo != null) {
        musicInfo.genres.takeIf { it.isNotEmpty() }?.let { genres ->
            rows += CoreCommonR.string.encyclopedia_label_genres to genres.joinToString("、")
        }
        musicInfo.language?.takeIf { it.isNotBlank() }?.let { language ->
            rows += CoreCommonR.string.encyclopedia_label_language to language
        }
        musicInfo.bpm?.takeIf { it > 0 }?.let { bpm ->
            rows += CoreCommonR.string.encyclopedia_label_bpm to bpm.toString()
        }
    }
    data.basicInfo.album?.takeIf { it.isNotBlank() }?.let { album ->
        rows += CoreCommonR.string.encyclopedia_label_album to album
    }
    data.publishTimeMs?.let { ms ->
        rows += CoreCommonR.string.encyclopedia_label_publish_date to formatEncyclopediaDate(ms)
    }
    data.albumCompany?.takeIf { it.isNotBlank() }?.let { company ->
        rows += CoreCommonR.string.encyclopedia_label_company to company
    }
    val credits = data.credits
    if (credits != null) {
        appendCreditRow(rows, CoreCommonR.string.encyclopedia_label_lyricist, credits.lyricists)
        appendCreditRow(rows, CoreCommonR.string.encyclopedia_label_composer, credits.composers)
        appendCreditRow(rows, CoreCommonR.string.encyclopedia_label_arranger, credits.arrangers)
        appendCreditRow(rows, CoreCommonR.string.encyclopedia_label_producer, credits.producers)
    }
    return rows
}

private fun appendCreditRow(rows: MutableList<Pair<Int, String>>, labelRes: Int, names: List<String>) {
    val text = names.filter { it.isNotBlank() }.joinToString("、")
    if (text.isNotEmpty()) rows += labelRes to text
}
