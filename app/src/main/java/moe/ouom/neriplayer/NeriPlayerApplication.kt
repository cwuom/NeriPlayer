package moe.ouom.neriplayer

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
 * File: moe.ouom.neriplayer/NeriPlayerApplication
 * Created: 2025/8/19
 */

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicLibraryGateway
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicTrack
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicUiDependencies
import moe.ouom.neriplayer.util.ExceptionHandler
import moe.ouom.neriplayer.util.LanguageManager

class NeriPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化语言设置
        LanguageManager.init(this)
        ExceptionHandler.init(this)

        AppContainer.initialize(this)
        ManagedDownloadStorage.initialize(this)
        YouTubeMusicUiDependencies.libraryGateway = object : YouTubeMusicLibraryGateway {
            override suspend fun getLibraryPlaylists(): List<YouTubeMusicPlaylist> {
                return AppContainer.youtubeMusicClient.getLibraryPlaylists().map { playlist ->
                    YouTubeMusicPlaylist(
                        browseId = playlist.browseId,
                        playlistId = playlist.playlistId,
                        title = playlist.title,
                        subtitle = playlist.subtitle,
                        coverUrl = playlist.coverUrl,
                        trackCount = playlist.trackCount ?: 0
                    )
                }
            }

            override suspend fun getPlaylistDetail(browseId: String): YouTubeMusicPlaylistDetail {
                val detail = AppContainer.youtubeMusicClient.getPlaylistDetail(browseId)
                return YouTubeMusicPlaylistDetail(
                    playlistId = detail.playlistId,
                    title = detail.title,
                    subtitle = detail.subtitle,
                    coverUrl = detail.coverUrl,
                    trackCount = detail.trackCount ?: detail.tracks.size,
                    tracks = detail.tracks.map { track ->
                        YouTubeMusicTrack(
                            videoId = track.videoId,
                            name = track.title,
                            artist = track.artist,
                            albumName = track.album,
                            durationMs = track.durationMs,
                            coverUrl = track.coverUrl
                        )
                    }
                )
            }
        }

        // 初始化全局下载管理器
        GlobalDownloadManager.initialize(this)

        // set a global Coil ImageLoader that uses the shared OkHttpClient honoring proxy bypass
        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient { AppContainer.sharedOkHttpClient }
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .build()
        Coil.setImageLoader(imageLoader)
    }
}
