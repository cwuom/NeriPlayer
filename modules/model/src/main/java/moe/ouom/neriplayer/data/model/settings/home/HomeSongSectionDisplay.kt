package moe.ouom.neriplayer.data.model.settings.home

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
 * along with this software.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.model.settings.home.HomeSongSectionDisplay
 * Created: 2026/11/20
 */

/**
 * 歌曲类小模块的展示模式。
 */
enum class HomeSongSectionDisplayMode {
    DEFAULT,
    CARD;

    companion object {
        fun fromName(raw: String?): HomeSongSectionDisplayMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** 歌曲类小模块的稳定标识集合（推荐歌单类小模块不在此列）。 */
object HomeSongSectionIds {
    /** 热力与榜单下的歌曲小模块。 */
    val trendingSongIds: List<String> = listOf(
        HomeTrendingSubSectionIds.TOP_SOARING,
        HomeTrendingSubSectionIds.PERSONALIZED_NEW_SONGS,
        HomeTrendingSubSectionIds.TOP_HOT,
        HomeTrendingSubSectionIds.TOP_NEW
    )

    /** 私人雷达下的歌曲小模块（雷达歌单是歌单集合，不算歌曲模块）。 */
    val radarSongIds: List<String> = listOf(
        HomeRadarSubSectionIds.PERSONAL_RADAR,
        HomeRadarSubSectionIds.DAILY_RECOMMEND,
        HomeRadarSubSectionIds.PRIVATE_FM
    )

    /** 全部歌曲类小模块 id。 */
    val ALL: List<String> = trendingSongIds + radarSongIds
}

/** 榜单卡片并排数量可选档位。 */
object HomeSongCardColumns {
    const val MIN = 1
    const val MAX = 3
    const val DEFAULT = 2

    /** 把任意输入规整到合法档位。 */
    fun normalize(value: Int): Int = value.coerceIn(MIN, MAX)
}

/**
 * 解析持久化的展示模式串。
 */
fun parseHomeSongSectionModes(
    raw: String?,
    defaults: List<String> = HomeSongSectionIds.ALL
): Map<String, HomeSongSectionDisplayMode> {
    if (raw.isNullOrBlank()) return emptyMap()
    val allowed = defaults.toSet()
    return raw.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val id = entry.substring(0, separator).trim()
            if (id !in allowed) return@mapNotNull null
            id to HomeSongSectionDisplayMode.fromName(entry.substring(separator + 1))
        }
        .toMap()
}

/** 序列化展示模式，只保存与默认值不同的项，保持设置串精简。 */
fun encodeHomeSongSectionModes(
    modes: Map<String, HomeSongSectionDisplayMode>,
    defaults: List<String> = HomeSongSectionIds.ALL
): String {
    val allowed = defaults.toSet()
    return modes.entries
        .filter { it.key in allowed && it.value != HomeSongSectionDisplayMode.DEFAULT }
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value.name}" }
}

/** 取某个小模块的展示模式，未配置时回落默认模式。 */
fun homeSongSectionDisplayModeOf(
    modes: Map<String, HomeSongSectionDisplayMode>,
    id: String
): HomeSongSectionDisplayMode = modes[id] ?: HomeSongSectionDisplayMode.DEFAULT
