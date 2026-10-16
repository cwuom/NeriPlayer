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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.home/HomeScrollKeys
 * Created: 2026/10/16
 */

import java.util.Locale
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomePlaylistSource
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId

/**
 * 首页滚动锚点 key。LazyGrid item 的 key 需要在重组之间保持稳定，
 * 这些常量集中放在这里，便于与 HomeScreen.kt 中的使用点保持一致。
 *
 * 使用 internal 而非 private：这些声明与 HomeScreen.kt 中的使用点分属不同文件，
 * 而 Kotlin 没有包私有可见性。
 */

internal const val HomeScrollKeyContinueHeader = "home:continue:header"

internal const val HomeScrollKeyContinueContent = "home:continue:content"

internal const val HomeScrollKeyYtGuess = "home:ytmusic:guess"

internal const val HomeScrollKeyYtDaily = "home:ytmusic:daily"

internal const val HomeScrollKeyYtMoreHeader = "home:ytmusic:more:header"

internal const val HomeScrollKeyYtMoreLoading = "home:ytmusic:more:loading"

internal const val HomeScrollKeyYtMoreError = "home:ytmusic:more:error"

internal const val HomeScrollKeyYtShelvesLoading = "home:ytmusic:shelves:loading"

internal const val HomeScrollKeyYtShelvesError = "home:ytmusic:shelves:error"

internal const val HomeScrollKeyYtEmptyFeedLoading = "home:ytmusic:empty-feed:loading"

internal const val HomeScrollKeyYtEmptyFeedError = "home:ytmusic:empty-feed:error"

internal const val HomeScrollKeyNeteaseRadarPlaylists = "home:netease:radar-playlists"

internal const val HomeScrollKeyNeteaseRadarPlaylistsHeader = "$HomeScrollKeyNeteaseRadarPlaylists:header"

internal const val HomeScrollKeyNeteaseRadarPlaylistsContent = "$HomeScrollKeyNeteaseRadarPlaylists:content"

/**
 * 首页滚动锚点 key 生成规则。所有辅助函数都从原始 id / 标题派生 key，
 * 保证相同输入总是得到相同的滚动锚点。
 */

internal fun homeNeteaseSongSectionKey(
    group: String,
    source: NeteaseHomeSongSource
): String {
    return "home:netease:$group:${source.name.lowercase(Locale.ROOT)}"
}

internal fun homeNeteasePlaylistSectionKey(
    source: NeteaseHomePlaylistSource
): String {
    return "home:netease:playlist:${source.name.lowercase(Locale.ROOT)}"
}

internal fun homeNeteasePlaylistScrollKey(
    sectionKey: String,
    id: Long
): String {
    return "$sectionKey:playlist:$id"
}

internal fun homeNeteaseRadarPlaylistScrollKey(id: Long): String {
    return "$HomeScrollKeyNeteaseRadarPlaylists:playlist:$id"
}

internal fun homeYtMusicPlaylistScrollKey(playlist: YouTubeMusicPlaylist): String {
    return "home:ytmusic:playlist:${playlist.favoriteId()}"
}

internal fun homeYtMusicShelfScrollKey(
    shelfIndex: Int,
    title: String
): String {
    return "home:ytmusic:shelf:$shelfIndex:${title.hashCode()}"
}

internal fun homeYtMusicHomeItemScrollKey(
    shelfKey: String,
    itemIndex: Int,
    item: YouTubeMusicHomeItem
): String {
    val stableId = item.browseId.ifBlank { item.videoId }.ifBlank { item.title }
    return "$shelfKey:item:$itemIndex:${stableId.hashCode()}"
}
