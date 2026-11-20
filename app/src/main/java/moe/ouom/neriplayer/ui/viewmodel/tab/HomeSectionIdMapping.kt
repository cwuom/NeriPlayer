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
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/HomeSectionIdMapping
 * Created: 2026/11/20
 */

import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRecommendedSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.applyHomeSectionOrder

/**
 * 首页小模块稳定 id 与网易云来源枚举的映射。
 */
internal object HomeSectionIdMapping {

    /** 热力与榜单大模块下的歌曲来源 id。 */
    val trendingSongIds: Map<String, NeteaseHomeSongSource> = mapOf(
        HomeTrendingSubSectionIds.TOP_SOARING to NeteaseHomeSongSource.TOP_SOARING,
        HomeTrendingSubSectionIds.PERSONALIZED_NEW_SONGS to NeteaseHomeSongSource.PERSONALIZED_NEW_SONGS,
        HomeTrendingSubSectionIds.TOP_HOT to NeteaseHomeSongSource.TOP_HOT,
        HomeTrendingSubSectionIds.TOP_NEW to NeteaseHomeSongSource.TOP_NEW
    )

    /** 私人雷达大模块下的歌曲来源 id。 */
    val radarSongIds: Map<String, NeteaseHomeSongSource> = mapOf(
        HomeRadarSubSectionIds.PERSONAL_RADAR to NeteaseHomeSongSource.PERSONAL_RADAR,
        HomeRadarSubSectionIds.DAILY_RECOMMEND to NeteaseHomeSongSource.DAILY_RECOMMEND,
        HomeRadarSubSectionIds.PRIVATE_FM to NeteaseHomeSongSource.PRIVATE_FM
    )

    /** 推荐歌单大模块下的歌单来源 id。 */
    val recommendedPlaylistIds: Map<String, NeteaseHomePlaylistSource> = mapOf(
        HomeRecommendedSubSectionIds.PERSONALIZED to NeteaseHomePlaylistSource.PERSONALIZED,
        HomeRecommendedSubSectionIds.DAILY_RESOURCE to NeteaseHomePlaylistSource.DAILY_RESOURCE,
        HomeRecommendedSubSectionIds.HIGH_QUALITY to NeteaseHomePlaylistSource.HIGH_QUALITY,
        HomeRecommendedSubSectionIds.HOT_PLAYLISTS to NeteaseHomePlaylistSource.HOT_PLAYLISTS,
        HomeRecommendedSubSectionIds.ACG_PLAYLISTS to NeteaseHomePlaylistSource.ACG_PLAYLISTS
    )

    fun trendingSourceOf(id: String): NeteaseHomeSongSource? = trendingSongIds[id]

    fun radarSourceOf(id: String): NeteaseHomeSongSource? = radarSongIds[id]

    fun recommendedSourceOf(id: String): NeteaseHomePlaylistSource? = recommendedPlaylistIds[id]

    fun trendingIdOf(source: NeteaseHomeSongSource): String? =
        trendingSongIds.entries.firstOrNull { it.value == source }?.key

    fun radarIdOf(source: NeteaseHomeSongSource): String? =
        radarSongIds.entries.firstOrNull { it.value == source }?.key

    fun recommendedIdOf(source: NeteaseHomePlaylistSource): String? =
        recommendedPlaylistIds.entries.firstOrNull { it.value == source }?.key

    /**
     * 背后有固定网易云歌单 id 的榜单歌单。
     */
    val chartPlaylistIds: Set<Long> = setOf(
        NETEASE_TOPLIST_SOARING_ID,
        NETEASE_PRIVATE_RADAR_PLAYLIST_ID,
        NETEASE_TOPLIST_HOT_ID,
        NETEASE_TOPLIST_NEW_ID
    )

    /** 返回该歌曲来源背后的固定歌单 id，没有则返回 null。 */
    fun playlistIdOf(source: NeteaseHomeSongSource): Long? = when (source) {
        NeteaseHomeSongSource.TOP_SOARING -> NETEASE_TOPLIST_SOARING_ID
        NeteaseHomeSongSource.PERSONAL_RADAR -> NETEASE_PRIVATE_RADAR_PLAYLIST_ID
        NeteaseHomeSongSource.TOP_HOT -> NETEASE_TOPLIST_HOT_ID
        NeteaseHomeSongSource.TOP_NEW -> NETEASE_TOPLIST_NEW_ID
        else -> null
    }
}

/**
 * 按用户顺序返回可见的小模块 id。
 */
internal fun resolveVisibleHomeSubSectionIds(
    defaults: List<String>,
    order: List<String>,
    hidden: Set<String>
): List<String> = applyHomeSectionOrder(defaults = defaults, order = order, hidden = hidden)

/**
 * 按用户顺序重排网易云歌曲小模块。
 */
internal fun orderHomeSongSections(
    sections: List<HomeNeteaseSongSectionState>,
    defaults: List<String>,
    idOf: (NeteaseHomeSongSource) -> String?,
    order: List<String>,
    hidden: Set<String>
): List<HomeNeteaseSongSectionState> {
    if (sections.isEmpty()) return sections
    val sectionById = sections.mapNotNull { section ->
        idOf(section.source)?.let { it to section }
    }.toMap()
    val visibleIds = resolveVisibleHomeSubSectionIds(
        defaults = defaults,
        order = order,
        hidden = hidden
    )
    val ordered = visibleIds.mapNotNull { sectionById[it] }
    val knownIds = defaults.toSet()
    val rest = sections.filter { section ->
        val id = idOf(section.source)
        id != null && id !in knownIds
    }
    return ordered + rest
}

/**
 * 按用户顺序重排网易云歌单小模块。
 */
internal fun orderHomePlaylistSections(
    sections: List<HomeNeteasePlaylistSectionState>,
    defaults: List<String>,
    order: List<String>,
    hidden: Set<String>
): List<HomeNeteasePlaylistSectionState> {
    if (sections.isEmpty()) return sections
    val sectionById = sections.mapNotNull { section ->
        HomeSectionIdMapping.recommendedIdOf(section.source)?.let { it to section }
    }.toMap()
    val visibleIds = resolveVisibleHomeSubSectionIds(
        defaults = defaults,
        order = order,
        hidden = hidden
    )
    val ordered = visibleIds.mapNotNull { sectionById[it] }
    val knownIds = defaults.toSet()
    val rest = sections.filter { section ->
        val id = HomeSectionIdMapping.recommendedIdOf(section.source)
        id != null && id !in knownIds
    }
    return ordered + rest
}
