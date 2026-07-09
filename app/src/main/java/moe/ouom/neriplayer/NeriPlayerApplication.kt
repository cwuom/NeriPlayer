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
import android.webkit.WebView
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.FloatingLyricsOverlayManager
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicLibraryGateway
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicTrack
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicUiDependencies
import moe.ouom.neriplayer.util.AnrWatchdog
import moe.ouom.neriplayer.util.ExceptionHandler
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.NativeCrashHandler
import moe.ouom.neriplayer.util.SafeModeManager

class NeriPlayerApplication : Application() {
    @Volatile
    private var normalComponentsInitialized = false

    override fun onCreate() {
        super.onCreate()
        val runningInMainProcess = isMainProcess()
        configureWebViewDataDirectoryIfNeeded(runningInMainProcess)

        // 初始化语言设置
        LanguageManager.init(this)
        if (runningInMainProcess) {
            AnrWatchdog.capturePreviousAnrIfNeeded(this)
        }
        val enterSafeMode = runningInMainProcess && SafeModeManager.shouldEnterSafeMode(this)
        ExceptionHandler.init(
            this,
            installNativeCrashHandler = runningInMainProcess && !enterSafeMode
        )

        if (enterSafeMode) {
            return
        }
        if (!runningInMainProcess) {
            return
        }
        initializeNormalComponents()
    }

    private fun isMainProcess(): Boolean {
        val currentProcessName = getProcessName()
        val mainProcessName = applicationInfo.processName.ifBlank { packageName }
        return currentProcessName == mainProcessName
    }

    private fun configureWebViewDataDirectoryIfNeeded(runningInMainProcess: Boolean) {
        if (runningInMainProcess) {
            return
        }
        val processName = getProcessName()
        val suffix = processName
            .substringAfter(':', missingDelimiterValue = processName)
            .ifBlank { "webview" }
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        WebView.setDataDirectorySuffix(suffix)
    }

    internal fun initializeNormalComponents() {
        if (normalComponentsInitialized) return
        synchronized(this) {
            if (normalComponentsInitialized) {
                return@synchronized
            }

            NativeCrashHandler.init(this)
            AppContainer.initialize(this)
            // 提前注册前后台回调，避免等播放器初始化后才开始统计 Activity 状态
            FloatingLyricsOverlayManager.initialize(this)
            ManagedDownloadStorage.initialize(this)

            // 初始化 YouTube Music UI 依赖的库网关
            YouTubeMusicUiDependencies.libraryGateway = object : YouTubeMusicLibraryGateway {
                override suspend fun getLibraryPlaylists(): List<YouTubeMusicPlaylist> {
                    return AppContainer.youtubeMusicClient.getLibraryPlaylists(
                        resolveMissingTrackCounts = false
                    ).map { playlist ->
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
                        fullyLoaded = detail.fullyLoaded,
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

                override suspend fun getPlaylistDetailPreview(browseId: String): YouTubeMusicPlaylistDetail {
                    val detail = AppContainer.youtubeMusicClient.getPlaylistDetailPreview(browseId)
                    return YouTubeMusicPlaylistDetail(
                        playlistId = detail.playlistId,
                        title = detail.title,
                        subtitle = detail.subtitle,
                        coverUrl = detail.coverUrl,
                        trackCount = detail.trackCount ?: detail.tracks.size,
                        fullyLoaded = detail.fullyLoaded,
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

            // 初始化 LyriconManager，如果用户启用了 Lyricon 功能
            if (readPlaybackPreferenceSnapshotSync(this).lyriconEnabled) {
                LyriconManager.initialize(this)
            }

            // 设置一个全局 Coil ImageLoader，它使用共享的 OkHttpClient 支持代理绕过
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
                        .maxSizePercent(0.12)
                        .build()
                }
                .build()
            Coil.setImageLoader(imageLoader)
            normalComponentsInitialized = true
        }
    }
}
