package moe.ouom.neriplayer.ui.viewmodel.tab

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/HomeLayoutState
 * Created: 2026/11/20
 */

import moe.ouom.neriplayer.data.model.settings.home.HomeMajorSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRecommendedSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeSongCardColumns
import moe.ouom.neriplayer.data.model.settings.home.HomeSongSectionDisplayMode
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds

/**
 * 首页模块布局：各大模块的顺序，以及每个大模块内部小模块的顺序与隐藏集合。
 */
data class HomeLayoutState(
    val majorOrder: List<String> = HomeMajorSectionIds.DEFAULT_ORDER,
    val trendingOrder: List<String> = HomeTrendingSubSectionIds.DEFAULT_ORDER,
    val trendingHidden: Set<String> = emptySet(),
    val radarOrder: List<String> = HomeRadarSubSectionIds.DEFAULT_ORDER,
    val radarHidden: Set<String> = emptySet(),
    val recommendedOrder: List<String> = HomeRecommendedSubSectionIds.DEFAULT_ORDER,
    val recommendedHidden: Set<String> = emptySet(),
    /** 歌曲类小模块的展示模式（默认列表 / 榜单卡片）。 */
    val songSectionModes: Map<String, HomeSongSectionDisplayMode> = emptyMap(),
    /** 榜单卡片并排数量（1 / 2 / 3）。 */
    val songCardColumns: Int = HomeSongCardColumns.DEFAULT
)
