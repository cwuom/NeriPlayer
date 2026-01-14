package moe.ouom.neriplayer.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
 * See the GNU General Public License for more details
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.navigation/Destinations
 * Created: 2025/8/8
 */

sealed class Destinations(val route: String, val labelResId: Int) {
    // TAB
    data object Home : Destinations("home", moe.ouom.neriplayer.R.string.nav_home)
    data object Explore : Destinations("explore", moe.ouom.neriplayer.R.string.nav_explore)
    data object Library : Destinations("library", moe.ouom.neriplayer.R.string.nav_library)
    data object Settings : Destinations("settings", moe.ouom.neriplayer.R.string.nav_settings)
    object Recent { const val route = "recent" }

    // DEBUG
    data object Debug : Destinations("debug", moe.ouom.neriplayer.R.string.debug_title)
    data object DebugBili : Destinations("debug/bili", moe.ouom.neriplayer.R.string.debug_bili_api)
    data object DebugNetease : Destinations("debug/netease", moe.ouom.neriplayer.R.string.debug_netease_api)
    data object DebugSearch : Destinations("debug/search", moe.ouom.neriplayer.R.string.debug_search_api)
    data object DebugLogsList : Destinations("debug_logs_list", moe.ouom.neriplayer.R.string.log_list)
    data object DebugCrashLogsList : Destinations("debug_crash_logs_list", moe.ouom.neriplayer.R.string.log_list)

    // 网易云歌单详情路由
    data object PlaylistDetail : Destinations("playlist_detail/{playlistJson}", moe.ouom.neriplayer.R.string.playlist_detail) {
        fun createRoute(playlistJson: String) = "playlist_detail/$playlistJson"
    }

    // 网易云专辑详情路由
    data object NeteaseAlbumDetail : Destinations("netease_album_detail/{playlistJson}", moe.ouom.neriplayer.R.string.common_album_detail) {
        fun createRoute(playlistJson: String) = "netease_album_detail/$playlistJson"
    }

    // B 站收藏夹详情路由
    data object BiliPlaylistDetail : Destinations("bili_playlist_detail/{playlistJson}", moe.ouom.neriplayer.R.string.playlist_detail) {
        fun createRoute(playlistJson: String) = "bili_playlist_detail/$playlistJson"
    }

    // 本地歌单详情路由
    data object LocalPlaylistDetail : Destinations("local_playlist_detail/{playlistId}", moe.ouom.neriplayer.R.string.playlist_local_detail) {
        fun createRoute(playlistId: Long) = "local_playlist_detail/$playlistId"
    }

    // 下载管理器路由
    data object DownloadManager : Destinations("download_manager", moe.ouom.neriplayer.R.string.download_manager)

    // 下载进度路由
    data object DownloadProgress : Destinations("download_progress", moe.ouom.neriplayer.R.string.download_progress)

    data object DebugLogViewer : Destinations("debug_log_viewer/{filePath}", moe.ouom.neriplayer.R.string.log_view) {
        fun createRoute(filePath: String): String {
            val encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8.name())
            return "debug_log_viewer/$encodedPath"
        }
    }
}