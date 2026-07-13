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
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.lyrics.FloatingLyricsOverlayManager
import moe.ouom.neriplayer.core.startup.app.AppImageLoaderInitializer
import moe.ouom.neriplayer.core.startup.app.AppProcessClassifier
import moe.ouom.neriplayer.core.startup.app.AppStartupPlanner
import moe.ouom.neriplayer.core.startup.app.WebViewDataDirectorySuffix
import moe.ouom.neriplayer.core.startup.app.YouTubeMusicUiGatewayInitializer
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.util.crash.AnrWatchdog
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.util.crash.NativeCrashHandler
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager

class NeriPlayerApplication : Application() {
    @Volatile
    private var normalComponentsInitialized = false

    override fun onCreate() {
        super.onCreate()
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

            // 提前注册前后台回调，避免等播放器初始化后才开始统计 Activity 状态
            FloatingLyricsOverlayManager.initialize(this)
            ManagedDownloadStorage.initialize(this)

            YouTubeMusicUiGatewayInitializer.initialize()

            // 初始化全局下载管理器
            GlobalDownloadManager.initialize(this)

            // 初始化 LyriconManager，如果用户启用了 Lyricon 功能
            if (readPlaybackPreferenceSnapshotSync(this).lyriconEnabled) {
                LyriconManager.initialize(this)
            }

            AppImageLoaderInitializer.initialize(this)
            normalComponentsInitialized = true
        }
    }
}
