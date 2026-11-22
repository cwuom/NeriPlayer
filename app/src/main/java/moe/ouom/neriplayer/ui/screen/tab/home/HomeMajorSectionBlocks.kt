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
import moe.ouom.neriplayer.data.model.settings.home.HomeSongSectionDisplayMode
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.homeSongSectionDisplayModeOf
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
 * 判断某个歌曲小模块当前是否使用「榜单卡片」形态。
 */
internal fun HomeMajorSectionEnv.isChartCardMode(id: String): Boolean =
    homeSongSectionDisplayModeOf(ui.layout.songSectionModes, id) == HomeSongSectionDisplayMode.CARD

/**
 * 一个大模块内所有「榜单卡片」形态的小模块。
 *
 * 榜单卡片最终渲染成一条整体卡片带，因此需要先知道哪些小模块是卡片形态，
 * 才能把这条卡片带放到「第一个卡片形态小模块」所处的位置，从而保证用户配置的
 * 小模块顺序被真正遵守。
 */
internal data class HomeSongChartCardGroup(
    /** 处于卡片形态的小模块 id。 */
    val cardIds: Set<String>,
    /** 按配置顺序排列的卡片数据。 */
    val cards: List<HomeSongChartCardData>,
    /** 卡片 id 到对应网易云来源的映射。 */
    val sources: Map<String, NeteaseHomeSongSource>
) {
    /** 卡片 id 到卡片数据的映射。 */
    val cardsById: Map<String, HomeSongChartCardData> = cards.associateBy { it.id }
}

/**
 * 收集一个大模块内的榜单卡片数据。
 *
 * 卡片只依赖小模块标题，不依赖歌曲列表是否已加载，因此这里不检查小模块的数据状态：
 * 否则数据尚未返回时卡片会整块消失，用户看不到自己配置的形态。
 *
 * 但登录要求仍然要遵守：[availableSources] 是当前登录状态下真正会展示的来源集合，
 * 需要登录的来源（每日推荐、私人 FM）在未登录时既不出现在列表里，也不该出现在卡片带里，
 * 否则点进去只会得到一个接口报错。
 */
internal fun collectHomeSongChartCardGroup(
    visibleIds: List<String>,
    availableSources: Set<NeteaseHomeSongSource>,
    isChartCardMode: (String) -> Boolean,
    sourceOf: (String) -> NeteaseHomeSongSource?,
    titleResOf: (NeteaseHomeSongSource) -> Int
): HomeSongChartCardGroup {
    val cardIds = LinkedHashSet<String>()
    val cards = mutableListOf<HomeSongChartCardData>()
    val sources = mutableMapOf<String, NeteaseHomeSongSource>()
    visibleIds.forEach { id ->
        if (!isChartCardMode(id)) return@forEach
        val source = sourceOf(id) ?: return@forEach
        if (source !in availableSources) return@forEach
        cardIds += id
        cards += HomeSongChartCardData(id = id, titleRes = titleResOf(source))
        sources[id] = source
    }
    return HomeSongChartCardGroup(
        cardIds = cardIds,
        cards = cards,
        sources = sources
    )
}

/**
 * 把可见小模块按「连续卡片区间」切分。
 *
 * 卡片带只能合并相邻的卡片形态小模块：若两个卡片小模块之间隔了列表形态的小模块，
 * 把它们并到同一条卡片带会让其中一个卡片越过中间的小模块，破坏用户配置的顺序。
 * 返回的每一段都是一段连续的卡片 id，且段内顺序与 [visibleIds] 一致。
 */
internal fun splitHomeSongChartCardRuns(
    visibleIds: List<String>,
    cardIds: Set<String>
): List<List<String>> {
    val runs = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    visibleIds.forEach { id ->
        if (cardIds.contains(id)) {
            current += id
            return@forEach
        }
        if (current.isNotEmpty()) {
            runs += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) runs += current
    return runs
}

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
    val cardGroup = collectHomeSongChartCardGroup(
        visibleIds = visibleIds,
        availableSources = env.ui.trendingSongSections.map { it.source }.toSet(),
        isChartCardMode = { env.isChartCardMode(it) },
        sourceOf = { HomeSectionIdMapping.trendingSourceOf(it) },
        titleResOf = { it.titleRes }
    )
    val cardRuns = splitHomeSongChartCardRuns(visibleIds, cardGroup.cardIds)
    val runIndexById: Map<String, Int> = cardRuns.flatMapIndexed { index, run ->
        run.map { id -> id to index }
    }.toMap()
    visibleIds.forEach { id ->
        val runIndex = runIndexById[id]
        if (runIndex != null) {
            val run = cardRuns[runIndex]
            if (id == run.first()) {
                addHomeSongChartCardBlock(
                    env = env,
                    group = "trending",
                    runIndex = runIndex,
                    cards = run.mapNotNull { cardGroup.cardsById[it] },
                    onCardClick = { card ->
                        cardGroup.sources[card.id]?.let { env.onSongSectionMore(it) }
                    }
                )
            }
            return@forEach
        }
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
    val cardGroup = collectHomeSongChartCardGroup(
        visibleIds = visibleIds,
        availableSources = env.ui.radarSongSections.map { it.source }.toSet(),
        isChartCardMode = { env.isChartCardMode(it) },
        sourceOf = { HomeSectionIdMapping.radarSourceOf(it) },
        titleResOf = { it.titleRes }
    )
    val cardRuns = splitHomeSongChartCardRuns(visibleIds, cardGroup.cardIds)
    val runIndexById: Map<String, Int> = cardRuns.flatMapIndexed { index, run ->
        run.map { id -> id to index }
    }.toMap()
    visibleIds.forEach { id ->
        if (id == HomeRadarSubSectionIds.RADAR_PLAYLISTS) {
            addHomeRadarPlaylistBlock(env)
            return@forEach
        }
        val runIndex = runIndexById[id]
        if (runIndex != null) {
            val run = cardRuns[runIndex]
            if (id == run.first()) {
                addHomeSongChartCardBlock(
                    env = env,
                    group = "radar",
                    runIndex = runIndex,
                    cards = run.mapNotNull { cardGroup.cardsById[it] },
                    onCardClick = { card ->
                        cardGroup.sources[card.id]?.let { env.onSongSectionMore(it) }
                    }
                )
            }
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

/**
 * 渲染一条榜单卡片带。
 *
 * [runIndex] 是该卡片带在大模块内连续区间中的序号，用于生成互不冲突的滚动定位 key。
 */
internal fun LazyGridScope.addHomeSongChartCardBlock(
    env: HomeMajorSectionEnv,
    group: String,
    runIndex: Int,
    cards: List<HomeSongChartCardData>,
    onCardClick: (HomeSongChartCardData) -> Unit
) {
    if (cards.isEmpty()) return
    item(
        key = env.registerKey(homeSongChartCardsScrollKey(group, runIndex)),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        HomeSongChartCardStrip(
            cards = cards,
            columns = env.ui.layout.songCardColumns,
            onCardClick = onCardClick
        )
    }
}
