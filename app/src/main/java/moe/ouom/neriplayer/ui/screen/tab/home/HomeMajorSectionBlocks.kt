package moe.ouom.neriplayer.ui.screen.tab.home

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
 * File: moe.ouom.neriplayer.ui.screen.tab.home/HomeMajorSectionBlocks
 * Created: 2026/11/20
 */

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRecommendedSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionIdMapping
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomePlaylistSource
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.resolveVisibleHomeSubSectionIds

/**
 * 首页大模块渲染所需的共享上下文。
 */
internal class HomeMajorSectionEnv(
    val ui: HomeUiState,
    val showContinue: Boolean,
    val showTrending: Boolean,
    val showRadar: Boolean,
    val showRecommended: Boolean,
    val showBiliMusic: Boolean,
    val showOnlineFeeds: Boolean,
    val usageEntries: List<UsageEntry> = emptyList(),
    val usageLoaded: Boolean = true,
    val localPlaylistUsageLookup: Map<Long, LocalPlaylist> = emptyMap(),
    val downloadedPlaybackCoverCandidates: List<SongItem> = emptyList(),
    val continuePagerPage: Int = 0,
    val onContinuePagerPageChanged: (Int) -> Unit = {},
    val onOpenRecent: (UsageEntry) -> Unit = {},
    val onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    val onItemClick: (PlaylistSummary) -> Unit = {},
    val onYouTubeMusicPlaylistClick: (YouTubeMusicPlaylist) -> Unit = {},
    val favoriteSongLookup: FavoriteSongLookup = FavoriteSongLookup(emptyList()),
    val favoriteKeys: Set<String> = emptySet(),
    val onFavoriteToggle: (SongItem, Boolean) -> Unit = { _, _ -> },
    val onShowSnackbar: (String) -> Unit = {},
    val offlineMode: Boolean = false,
    val radarPlaylistListState: LazyListState,
    val loadingText: String,
    val guessYouLikeTitle: String = "",
    val dailyDiscoverTitle: String = "",
    val moreRecommendationsTitle: String = "",
    val ytmSections: ClassifiedYouTubeMusicShelves = ClassifiedYouTubeMusicShelves(
        guessYouLike = null,
        dailyDiscover = null,
        remaining = emptyList()
    ),
    val registerKey: (String) -> String,
    val onBiliMusicRankMore: () -> Unit = {},
    val onBiliMusicHotMore: () -> Unit = {},
    val onBiliMusicRankPlay: (List<BiliVideoItem>, Int) -> Unit = { _, _ -> },
    val onBiliMusicHotPlay: (List<BiliVideoItem>, Int) -> Unit = { _, _ -> },
    val onBiliMusicTypeSelected: (moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType) -> Unit = {},
    val onRetryBiliMusicRank: () -> Unit = {},
    val onRetryBiliMusicHot: () -> Unit = {},
    /** 点击网易云歌曲小模块标题，进入该小模块的详情页。 */
    val onSongSectionMore: (NeteaseHomeSongSource) -> Unit = {},
    /** 点击网易云歌单小模块标题，进入该分类的更多歌单页。 */
    val onPlaylistSectionMore: (NeteaseHomePlaylistSource) -> Unit = {}
)

/**
 * 渲染「继续播放」大模块。
 */
internal fun LazyGridScope.addHomeContinueBlock(env: HomeMajorSectionEnv) {
    if (!env.showContinue) return
    item(
        key = env.registerKey(HomeScrollKeyContinueHeader),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        SectionHeader(
            icon = Icons.Outlined.History,
            title = stringResource(CoreCommonR.string.player_continue)
        )
    }
    item(
        key = env.registerKey(HomeScrollKeyContinueContent),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        if (env.usageLoaded) {
            ContinueSection(
                items = env.usageEntries.take(HomeContinueEntryLimit),
                localPlaylistLookup = env.localPlaylistUsageLookup,
                localFilesCoverCandidates = env.downloadedPlaybackCoverCandidates,
                onClick = env.onOpenRecent,
                savedPage = env.continuePagerPage,
                onPageChanged = env.onContinuePagerPageChanged,
                offlineMode = env.offlineMode
            )
        } else {
            SectionLoadingState(env.loadingText)
        }
    }
}

/**
 * 渲染 Bilibili 音乐大模块（版权音乐榜与音乐分区热门）。
 */
internal fun LazyGridScope.addHomeBiliMusicBlock(env: HomeMajorSectionEnv) {
    if (!env.showBiliMusic) return
    addBiliMusicRankSection(
        registerKey = env.registerKey,
        state = env.ui.biliMusicRank,
        onTypeSelected = env.onBiliMusicTypeSelected,
        onMoreClick = env.onBiliMusicRankMore,
        onPlay = env.onBiliMusicRankPlay,
        onRetry = env.onRetryBiliMusicRank,
        offlineMode = env.offlineMode
    )
    addBiliMusicHotSection(
        registerKey = env.registerKey,
        state = env.ui.biliMusicHot,
        onMoreClick = env.onBiliMusicHotMore,
        onPlay = env.onBiliMusicHotPlay,
        onRetry = env.onRetryBiliMusicHot,
        offlineMode = env.offlineMode
    )
}

/**
 * 渲染「热力与榜单」大模块的网易云部分。
 */
internal fun LazyGridScope.addHomeTrendingBlock(env: HomeMajorSectionEnv) {
    if (!env.showTrending || !env.showOnlineFeeds) return
    val visibleIds = resolveVisibleHomeSubSectionIds(
        defaults = HomeTrendingSubSectionIds.DEFAULT_ORDER,
        order = env.ui.layout.trendingOrder,
        hidden = env.ui.layout.trendingHidden
    )
    visibleIds.forEach { id ->
        val source = HomeSectionIdMapping.trendingSourceOf(id) ?: return@forEach
        val sectionState = env.ui.trendingSongSections.firstOrNull { it.source == source }
            ?: return@forEach
        addNeteaseSongSection(
            sectionKey = homeNeteaseSongSectionKey(group = "trending", source = source),
            registerKey = env.registerKey,
            sectionState = sectionState,
            icon = neteaseSongSectionIcon(source),
            loadingText = env.loadingText,
            onSongClick = env.onSongClick,
            favoriteSongLookup = env.favoriteSongLookup,
            onFavoriteToggle = env.onFavoriteToggle,
            onShowSnackbar = env.onShowSnackbar,
            offlineMode = env.offlineMode,
            onHeaderClick = { env.onSongSectionMore(source) }
        )
    }
}

/**
 * 渲染「私人雷达」大模块的网易云部分。
 */
internal fun LazyGridScope.addHomeRadarBlock(env: HomeMajorSectionEnv) {
    if (!env.showRadar || !env.showOnlineFeeds) return
    val visibleIds = resolveVisibleHomeSubSectionIds(
        defaults = HomeRadarSubSectionIds.DEFAULT_ORDER,
        order = env.ui.layout.radarOrder,
        hidden = env.ui.layout.radarHidden
    )
    visibleIds.forEach { id ->
        if (id == HomeRadarSubSectionIds.RADAR_PLAYLISTS) {
            addHomeRadarPlaylistBlock(env)
            return@forEach
        }
        val source = HomeSectionIdMapping.radarSourceOf(id) ?: return@forEach
        val sectionState = env.ui.radarSongSections.firstOrNull { it.source == source }
            ?: return@forEach
        addNeteaseSongSection(
            sectionKey = homeNeteaseSongSectionKey(group = "radar", source = source),
            registerKey = env.registerKey,
            sectionState = sectionState,
            icon = neteaseSongSectionIcon(source),
            loadingText = env.loadingText,
            onSongClick = env.onSongClick,
            favoriteSongLookup = env.favoriteSongLookup,
            onFavoriteToggle = env.onFavoriteToggle,
            onShowSnackbar = env.onShowSnackbar,
            offlineMode = env.offlineMode,
            onHeaderClick = { env.onSongSectionMore(source) }
        )
    }
}

internal fun LazyGridScope.addHomeRadarPlaylistBlock(env: HomeMajorSectionEnv) {
    val radarPlaylistState: HomeSectionState<PlaylistSummary> = env.ui.radarPlaylists
    item(
        key = env.registerKey(HomeScrollKeyNeteaseRadarPlaylistsHeader),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        SectionHeader(
            icon = Icons.Outlined.Explore,
            title = stringResource(CoreCommonR.string.home_netease_radar_playlists)
        )
    }
    sectionContent(
        section = radarPlaylistState,
        loadingText = env.loadingText,
        errorDetail = radarPlaylistState.error,
        keyPrefix = HomeScrollKeyNeteaseRadarPlaylists,
        registerKey = env.registerKey
    ) {
        item(
            key = env.registerKey(HomeScrollKeyNeteaseRadarPlaylistsContent),
            span = { GridItemSpan(maxLineSpan) }
        ) {
            RadarPlaylistStrip(
                playlists = radarPlaylistState.items,
                favoriteKeys = env.favoriteKeys,
                listState = env.radarPlaylistListState,
                onClick = env.onItemClick,
                onShowSnackbar = env.onShowSnackbar,
                offlineMode = env.offlineMode
            )
        }
    }
}

/**
 * 渲染「推荐歌单」大模块的网易云部分。
 */
internal fun LazyGridScope.addHomeRecommendedBlock(env: HomeMajorSectionEnv) {
    if (!env.showRecommended || !env.showOnlineFeeds) return
    val visibleIds = resolveVisibleHomeSubSectionIds(
        defaults = HomeRecommendedSubSectionIds.DEFAULT_ORDER,
        order = env.ui.layout.recommendedOrder,
        hidden = env.ui.layout.recommendedHidden
    )
    visibleIds.forEach { id ->
        val source = HomeSectionIdMapping.recommendedSourceOf(id) ?: return@forEach
        val sectionState = env.ui.playlistSections.firstOrNull { it.source == source }
            ?: return@forEach
        addNeteasePlaylistSection(
            sectionKey = homeNeteasePlaylistSectionKey(source),
            registerKey = env.registerKey,
            sectionState = sectionState,
            loadingText = env.loadingText,
            favoriteKeys = env.favoriteKeys,
            onItemClick = env.onItemClick,
            onShowSnackbar = env.onShowSnackbar,
            offlineMode = env.offlineMode,
            onHeaderClick = { env.onPlaylistSectionMore(source) }
        )
    }
}
