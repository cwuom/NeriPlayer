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
import kotlinx.coroutines.flow.collect
import moe.ouom.neriplayer.activity.UsbDeviceAttachHandling
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lyrics.FloatingLyricsOverlayManager
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.core.startup.app.AppImageLoaderInitializer
import moe.ouom.neriplayer.core.startup.app.AppProcessClassifier
import moe.ouom.neriplayer.core.startup.app.AppStartupPlanner
import moe.ouom.neriplayer.core.startup.app.WebViewDataDirectorySuffix
import moe.ouom.neriplayer.core.startup.app.YouTubeMusicUiGatewayInitializer
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRotationWorker
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.util.crash.AnrWatchdog
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.util.crash.NativeCrashHandler
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
import moe.ouom.neriplayer.ui.feedback.AppFeedback

class NeriPlayerApplication : Application() {
    @Volatile
    private var normalComponentsInitialized = false

    override fun onCreate() {
        super.onCreate()
        AppFeedback.initialize(this)
        // 冷启动首个播放点击可能早于 Compose 的 SideEffect, 先把 Application 绑给播放器
        PlayerManager.bindApplication(this)
        val runningInMainProcess = AppProcessClassifier.isMainProcess(
            currentProcessName = getProcessName(),
            configuredMainProcessName = applicationInfo.processName,
            packageName = packageName
        )
        configureWebViewDataDirectoryIfNeeded(runningInMainProcess)

        // 初始化语言设置
        LanguageManager.init(this)
        val startupPlan = AppStartupPlanner.plan(
            runningInMainProcess = runningInMainProcess,
            safeModeRequested = runningInMainProcess && SafeModeManager.shouldEnterSafeMode(this)
        )
        if (startupPlan.shouldCapturePreviousAnr) {
            AnrWatchdog.capturePreviousAnrIfNeeded(this)
        }
        ExceptionHandler.init(
            this,
            installNativeCrashHandler = startupPlan.shouldInstallNativeCrashHandler
        )

        if (!startupPlan.shouldInitializeNormalComponents) {
            return
        }
        initializeNormalComponents()
    }

    private fun configureWebViewDataDirectoryIfNeeded(runningInMainProcess: Boolean) {
        if (runningInMainProcess) {
            return
        }
        WebView.setDataDirectorySuffix(
            WebViewDataDirectorySuffix.forProcess(getProcessName())
        )
    }

    internal fun initializeNormalComponents() {
        if (normalComponentsInitialized) return
        synchronized(this) {
            if (normalComponentsInitialized) {
                return@synchronized
            }

            NativeCrashHandler.init(this)
            AppContainer.initialize(this)

            // 后台预热收藏仓库: 首次构造会同步 loadFromDisk, 放到 IO 线程避免首个 UI 触达在主线程读盘
            AppContainer.launchBackgroundIo {
                FavoritePlaylistRepository.getInstance(this@NeriPlayerApplication)
            }
            AppContainer.launchBackgroundIo {
                AppContainer.playHistoryRepo
            }
            // 这些统计仓库首次创建会读取完整快照，预热后详情页不会在主线程首次读盘
            AppContainer.launchBackgroundIo {
                AppContainer.playlistUsageRepo
                AppContainer.localPlaylistPlaybackStatsRepo
                AppContainer.playbackStatsRepo
                AppContainer.trafficStatsRepo
            }
            AppContainer.launchBackgroundIo {
                AppContainer.neteasePlaylistCacheRepo.importLegacyCaches()
                AppContainer.biliFavoriteFolderCacheRepo.importLegacyCaches()
                AppContainer.biliArchiveCacheRepo.importLegacyCaches()
                AppContainer.youtubeMusicPlaylistCacheRepo.importLegacyCaches()
            }
            AppContainer.launchBackgroundIo {
                AppContainer.settingsRepo.usbDeviceAttachHandlingEnabledFlow.collect { enabled ->
                    UsbDeviceAttachHandling.applyComponentState(
                        this@NeriPlayerApplication,
                        enabled
                    )
                }
            }

            // 提前注册前后台回调, 避免等播放器初始化后才开始统计 Activity 状态
            FloatingLyricsOverlayManager.initialize(this)
            ManagedDownloadStorage.initialize(this)

            YouTubeMusicUiGatewayInitializer.initialize()

            // 长期不开 App 时没有任何前台流程会去续期, 靠这个周期任务把会话保活
            YouTubeAuthRotationWorker.schedulePeriodicRotation(this)

            // 初始化全局下载管理器
            GlobalDownloadManager.initialize(this)
            LegacyJsonCleanupScheduler.schedule(this, "app-init")

            // 初始化 LyriconManager, 如果用户启用了 Lyricon 功能
            if (readPlaybackPreferenceSnapshotSync(this).lyriconEnabled) {
                LyriconManager.initialize(this)
            }

            AppImageLoaderInitializer.initialize(this)
            normalComponentsInitialized = true
        }
    }
}
