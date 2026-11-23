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
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.model.settings.home/HomeSectionOrder
 * Created: 2026/11/20
 */

/** 首页大模块稳定标识，与展示语言无关，持久化只存这些 key。 */
object HomeMajorSectionIds {
    const val CONTINUE = "continue"
    const val TRENDING = "trending"
    const val RADAR = "radar"
    const val RECOMMENDED = "recommended"
    const val BILIBILI = "bilibili"

    val DEFAULT_ORDER: List<String> = listOf(
        CONTINUE,
        RADAR,
        TRENDING,
        RECOMMENDED,
        BILIBILI
    )
}

object InternationalHomeSectionIds {
    const val SPOTLIGHT = "spotlight"
    const val CONTINUE = "continue"
    const val FEED = "feed"

    val DEFAULT_ORDER = listOf(SPOTLIGHT, CONTINUE, FEED)
}

data class InternationalHomeLayout(
    val order: List<String> = InternationalHomeSectionIds.DEFAULT_ORDER,
    val hidden: Set<String> = emptySet()
) {
    val visibleSections: List<String>
        get() = applyHomeSectionOrder(InternationalHomeSectionIds.DEFAULT_ORDER, order, hidden)

    fun isHomeAvailable(hasRecentUsage: Boolean): Boolean =
        visibleSections.any { it != InternationalHomeSectionIds.CONTINUE || hasRecentUsage }
}

/** 热力与榜单（大模块 trending）内部小模块稳定标识。 */
object HomeTrendingSubSectionIds {
    const val TOP_SOARING = "top_soaring"
    const val PERSONALIZED_NEW_SONGS = "personalized_new_songs"
    const val TOP_HOT = "top_hot"
    const val TOP_NEW = "top_new"

    val DEFAULT_ORDER: List<String> = listOf(
        TOP_SOARING,
        PERSONALIZED_NEW_SONGS,
        TOP_HOT,
        TOP_NEW
    )
}

/** 私人雷达（大模块 radar）内部小模块稳定标识，含雷达歌单横滑条。 */
object HomeRadarSubSectionIds {
    const val PERSONAL_RADAR = "personal_radar"
    const val RADAR_PLAYLISTS = "radar_playlists"
    const val DAILY_RECOMMEND = "daily_recommend"
    const val PRIVATE_FM = "private_fm"

    val DEFAULT_ORDER: List<String> = listOf(
        PERSONAL_RADAR,
        RADAR_PLAYLISTS,
        DAILY_RECOMMEND,
        PRIVATE_FM
    )
}

/** 推荐歌单（大模块 recommended）内部小模块稳定标识。 */
object HomeRecommendedSubSectionIds {
    const val PERSONALIZED = "personalized"
    const val DAILY_RESOURCE = "daily_resource"
    const val HIGH_QUALITY = "high_quality"
    const val HOT_PLAYLISTS = "hot_playlists"
    const val ACG_PLAYLISTS = "acg_playlists"

    val DEFAULT_ORDER: List<String> = listOf(
        PERSONALIZED,
        DAILY_RESOURCE,
        HIGH_QUALITY,
        HOT_PLAYLISTS,
        ACG_PLAYLISTS
    )
}

/**
 * 解析持久化的顺序串。
 */
fun parseHomeSectionOrder(raw: String?, defaults: List<String>): List<String> {
    if (raw.isNullOrBlank()) return defaults.toList()
    val requested = raw.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .filter { defaults.contains(it) }
    if (requested.isEmpty()) return defaults.toList()
    return requested + defaults.filterNot { requested.contains(it) }
}

/** 解析隐藏集合串，只保留 [defaults] 中出现的合法 key。 */
fun parseHomeSectionHidden(raw: String?, defaults: List<String>): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
        .intersect(defaults.toSet())
}

/** 序列化顺序与隐藏集合，空值存空串以保持 DataStore 简洁。 */
fun encodeHomeSectionOrder(order: List<String>): String = order.joinToString(",")

fun encodeHomeSectionHidden(hidden: Set<String>): String = hidden.joinToString(",")

/** 按用户顺序过滤并排序，隐藏项直接剔除。 */
fun applyHomeSectionOrder(
    defaults: List<String>,
    order: List<String>,
    hidden: Set<String>
): List<String> {
    val normalizedOrder = (order.filter { defaults.contains(it) } +
        defaults.filterNot { order.contains(it) }).distinct()
    return normalizedOrder.filterNot { hidden.contains(it) }
}

/** 对调两个位置，用于设置页上移/下移。 */
fun moveHomeSectionItem(order: List<String>, fromIndex: Int, toIndex: Int): List<String> {
    if (fromIndex !in order.indices || toIndex !in order.indices) return order
    if (fromIndex == toIndex) return order
    val mutable = order.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable.toList()
}

/**
 * 把 [key] 移动到 [targetKey] 所在的位置，用于拖拽排序。
 */
fun moveHomeSectionItemByKey(
    order: List<String>,
    key: String,
    targetKey: String
): List<String> {
    val fromIndex = order.indexOf(key)
    if (fromIndex < 0) return order
    if (key == targetKey) return order
    val targetIndex = order.indexOf(targetKey)
    if (targetIndex < 0) return order
    val mutable = order.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(targetIndex, item)
    return mutable.toList()
}

fun mergeVisibleHomeSectionOrder(order: List<String>, visibleOrder: List<String>): List<String> {
    val visible = visibleOrder.toSet()
    val replacements = visibleOrder.iterator()
    return order.map { if (it in visible) replacements.next() else it }
}
