package moe.ouom.neriplayer.ui.viewmodel.youtube

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
 * File: moe.ouom.neriplayer.ui.viewmodel.youtube/YouTubeMusicUiGateway
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist

data class YouTubeMusicPlaylistDetail(
    val playlistId: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String,
    val trackCount: Int,
    val tracks: List<YouTubeMusicTrack>
)

data class YouTubeMusicTrack(
    val videoId: String,
    val name: String,
    val artist: String,
    val albumName: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
)

interface YouTubeMusicLibraryGateway {
    suspend fun getLibraryPlaylists(): List<YouTubeMusicPlaylist>

    suspend fun getPlaylistDetail(browseId: String): YouTubeMusicPlaylistDetail
}

/**
 * UI 层只依赖一个最小网关
 * 底层实现由主线程在 data/core 层接入后注入，避免当前分支越权修改底层代码
 */
object YouTubeMusicUiDependencies {
    @Volatile
    var libraryGateway: YouTubeMusicLibraryGateway? = null
}
