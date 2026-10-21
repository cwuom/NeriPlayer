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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/EncyclopediaSongFactory
 * Created: 2025/10/17
 */

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaRelatedPlaylist
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaSimilarSong

/**
 * 把百科中的相似歌曲转成可播放的 SongItem。
 * 网易云歌曲的 ID 用 [song.songId]（Long），channelId 固定 "netease"。
 */
internal fun EncyclopediaSimilarSong.toSimilarSongPlaceholder(): SongItem = SongItem(
    id = songId,
    name = name,
    artist = artist,
    album = albumName.orEmpty(),
    albumId = 0L,
    durationMs = durationMs ?: 0L,
    coverUrl = coverUrl,
    channelId = "netease",
    audioId = songId.toString()
)

/**
 * 相关歌单转成 SongItem 时先入队；歌单详情页会自行加载曲目列表。
 */
internal fun EncyclopediaRelatedPlaylist.toPlaylistPlaceholderSong(): SongItem = SongItem(
    id = playlistId,
    name = name,
    artist = "",
    album = "",
    albumId = 0L,
    durationMs = 0L,
    coverUrl = coverUrl,
    channelId = "netease",
    audioId = playlistId.toString(),
    playlistContextId = playlistId.toString()
)
