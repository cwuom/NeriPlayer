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
 * File: moe.ouom.neriplayer.ui.screen.tab.home/HomeYouTubeMusicBlocks
 * Created: 2026/11/20
 */

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Star
import moe.ouom.neriplayer.ui.i18n.asString
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId

/**
 * 国际模式下的「热力与榜单」大模块：YouTube Music 的「猜你喜欢」。
 */
internal fun LazyGridScope.addHomeYouTubeMusicTrendingBlock(env: HomeMajorSectionEnv) {
    if (!env.showTrending || !env.showOnlineFeeds) return
    val shelf = env.ytmSections.guessYouLike ?: return
    addYouTubeMusicSongShelfSection(
        sectionKey = HomeScrollKeyYtGuess,
        registerKey = env.registerKey,
        shelf = shelf,
        icon = Icons.Outlined.Bolt,
        title = env.guessYouLikeTitle,
        onSongClick = env.onSongClick,
        favoriteSongLookup = env.favoriteSongLookup,
        downloadPresenceVersion = env.downloadPresenceVersion,
        onFavoriteToggle = env.onFavoriteToggle,
        onShowSnackbar = env.onShowSnackbar,
        offlineMode = env.offlineMode
    )
}

/**
 * 国际模式下的「私人雷达」大模块：YouTube Music 的「每日发现」。
 */
internal fun LazyGridScope.addHomeYouTubeMusicRadarBlock(env: HomeMajorSectionEnv) {
    if (!env.showRadar || !env.showOnlineFeeds) return
    val shelf = env.ytmSections.dailyDiscover ?: return
    addYouTubeMusicSongShelfSection(
        sectionKey = HomeScrollKeyYtDaily,
        registerKey = env.registerKey,
        shelf = shelf,
        icon = Icons.Outlined.Explore,
        title = env.dailyDiscoverTitle,
        onSongClick = env.onSongClick,
        favoriteSongLookup = env.favoriteSongLookup,
        downloadPresenceVersion = env.downloadPresenceVersion,
        onFavoriteToggle = env.onFavoriteToggle,
        onShowSnackbar = env.onShowSnackbar,
        offlineMode = env.offlineMode
    )
}

/**
 * 国际模式下的「推荐歌单」大模块：YouTube Music 推荐歌单与其它货架。
 */
internal fun LazyGridScope.addHomeYouTubeMusicRecommendedBlock(env: HomeMajorSectionEnv) {
    if (!env.showRecommended || !env.showOnlineFeeds) return

    item(
        key = env.registerKey(HomeScrollKeyYtMoreHeader),
        span = { GridItemSpan(maxLineSpan) },
        contentType = HomeContentTypeHeader
    ) {
        SectionHeader(
            icon = Icons.Outlined.Star,
            title = env.moreRecommendationsTitle
        )
    }

    when {
        env.ui.ytMusicPlaylists.items.isNotEmpty() -> {
            env.ui.ytMusicPlaylists.items.forEach { playlist ->
                item(
                    key = env.registerKey(homeYtMusicPlaylistScrollKey(playlist)),
                    contentType = HomeContentTypeYtPlaylistCard
                ) {
                    YtMusicPlaylistCard(
                        playlist = playlist,
                        isFavorite = env.favoriteKeys.contains("youtubeMusic:${playlist.favoriteId()}"),
                        onClick = { env.onYouTubeMusicPlaylistClick(playlist) },
                        onShowSnackbar = env.onShowSnackbar,
                        offlineMode = env.offlineMode
                    )
                }
            }
        }
        env.ui.ytMusicPlaylists.loading -> {
            item(
                key = env.registerKey(HomeScrollKeyYtMoreLoading),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeLoading
            ) {
                SectionLoadingState(env.loadingText)
            }
        }
        env.ui.ytMusicPlaylists.error != null -> {
            item(
                key = env.registerKey(HomeScrollKeyYtMoreError),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeError
            ) {
                SectionErrorState(detail = env.ui.ytMusicPlaylists.error.asString())
            }
        }
    }

    when {
        env.ytmSections.remaining.any { shelf ->
            shelf.shouldRenderAsSongShelf() || shelf.hasRenderablePlaylistItems()
        } -> {
            env.ytmSections.remaining.forEachIndexed { shelfIndex, shelf ->
                val shelfKey = homeYtMusicShelfScrollKey(
                    shelfIndex = shelfIndex,
                    title = shelf.title
                )
                if (shelf.shouldRenderAsSongShelf()) {
                    addYouTubeMusicSongShelfSection(
                        sectionKey = shelfKey,
                        registerKey = env.registerKey,
                        shelf = shelf,
                        icon = Icons.Outlined.Explore,
                        title = shelf.title,
                        onSongClick = env.onSongClick,
                        favoriteSongLookup = env.favoriteSongLookup,
                        downloadPresenceVersion = env.downloadPresenceVersion,
                        onFavoriteToggle = env.onFavoriteToggle,
                        onShowSnackbar = env.onShowSnackbar,
                        offlineMode = env.offlineMode
                    )
                } else {
                    val playlistItems = shelf.items.filter { it.isPlaylistItem() }
                    if (playlistItems.isEmpty()) {
                        return@forEachIndexed
                    }
                    item(
                        key = env.registerKey("$shelfKey:header"),
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = HomeContentTypeHeader
                    ) {
                        SectionHeader(
                            icon = Icons.Outlined.Explore,
                            title = shelf.title
                        )
                    }
                    playlistItems.forEachIndexed { itemIndex, homeItem ->
                        item(
                            key = env.registerKey(
                                homeYtMusicHomeItemScrollKey(
                                    shelfKey = shelfKey,
                                    itemIndex = itemIndex,
                                    item = homeItem
                                )
                            ),
                            contentType = HomeContentTypeYtHomeItemCard
                        ) {
                            YtMusicHomeItemCard(
                                item = homeItem,
                                isFavorite = homeItem.toPlaylist()
                                    ?.favoriteId()
                                    ?.let { env.favoriteKeys.contains("youtubeMusic:$it") } == true,
                                onClick = {
                                    val playlist = homeItem.toPlaylist()
                                    if (playlist != null) {
                                        env.onYouTubeMusicPlaylistClick(playlist)
                                    } else if (homeItem.videoId.isNotBlank()) {
                                        val songs = listOfNotNull(
                                            homeItem.toPlayableSongItem(shelf.title)
                                        )
                                        if (songs.isNotEmpty()) {
                                            env.onSongClick(songs, 0)
                                        }
                                    }
                                },
                                onShowSnackbar = env.onShowSnackbar,
                                offlineMode = env.offlineMode
                            )
                        }
                    }
                }
            }
        }
        env.ui.ytMusicHomeShelves.loading -> {
            item(
                key = env.registerKey(HomeScrollKeyYtShelvesLoading),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeLoading
            ) {
                SectionLoadingState(env.loadingText)
            }
        }
        env.ui.ytMusicHomeShelves.error != null -> {
            item(
                key = env.registerKey(HomeScrollKeyYtShelvesError),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeError
            ) {
                SectionErrorState(detail = env.ui.ytMusicHomeShelves.error.asString())
            }
        }
    }
}

/**
 * 国际模式下没有任何可见内容时的占位（加载中或错误）。
 */
internal fun LazyGridScope.addHomeYouTubeMusicEmptyFeedBlock(env: HomeMajorSectionEnv) {
    if (!env.showOnlineFeeds) return
    if (env.ytmSections.hasVisibleFeed()) return
    if (!env.showTrending && !env.showRadar && !env.showRecommended) return
    when {
        env.ui.ytMusicHomeShelves.loading -> {
            item(
                key = env.registerKey(HomeScrollKeyYtEmptyFeedLoading),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeLoading
            ) {
                SectionLoadingState(env.loadingText)
            }
        }
        env.ui.ytMusicHomeShelves.error != null -> {
            item(
                key = env.registerKey(HomeScrollKeyYtEmptyFeedError),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeError
            ) {
                SectionErrorState(detail = env.ui.ytMusicHomeShelves.error.asString().orEmpty())
            }
        }
    }
}
