package moe.ouom.neriplayer.data.auth.youtube

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
 * File: moe.ouom.neriplayer.data.auth.youtube/YouTubeAuthRotationWorker
 * Created: 2026/7/27
 */

import android.content.Context
import android.webkit.CookieManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.concurrent.TimeUnit

/**
 * 后台定期轮换 YouTube 会话凭据
 *
 * 长时间不打开 App 时没有任何前台流程会去刷新, *PSIDTS 一直不动就会被判成休眠会话;
 * 这里只做纯 HTTP 轮换, 不碰 WebView, 后台进程起 WebView 既慢又容易被系统杀
 */
class YouTubeAuthRotationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "YouTubeAuthRotation"
        private const val PERIODIC_WORK_NAME = "youtube_auth_rotation_periodic"
        private const val REPEAT_INTERVAL_HOURS = 6L
        private const val FLEX_INTERVAL_HOURS = 1L

        fun schedulePeriodicRotation(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<YouTubeAuthRotationWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
                FLEX_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                // 已经排上的就别重排, 否则每次启动都把周期重置回 6 小时后
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelPeriodicRotation(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            if (!AppContainer.isInitialized()) {
                NPLogger.d(TAG, "skip rotation: app container is not initialized")
                return Result.success()
            }
            if (!AppContainer.settingsRepo.youtubeEnabledFlow.first()) {
                NPLogger.d(TAG, "skip rotation: YouTube is disabled")
                return Result.success()
            }
            val auth = AppContainer.youtubeAuthRepo.getAuthOnce().normalized()
            if (!hasYouTubeRotationPrerequisites(auth.cookies)) {
                NPLogger.d(TAG, "skip rotation: no usable session cookies")
                return Result.success()
            }

            when (val outcome = youtubeAuthRotationMutex.withLock {
                val result = YouTubeCookieRotator(
                    stateStore = SharedPreferencesYouTubeCookieRotationStateStore(
                        applicationContext
                    )
                ).rotateDetailed(
                    cookies = auth.cookies,
                    userAgent = auth.userAgent,
                    force = true
                )
                if (result is YouTubeCookieRotationOutcome.Rotated) {
                    AppContainer.youtubeAuthRepo.mergeRotatedCookies(result.cookies)
                    syncRotatedCookiesToWebView(result.cookies)
                }
                result
            }) {
                is YouTubeCookieRotationOutcome.Rotated -> {
                    NPLogger.i(
                        TAG,
                        "rotation persisted keys=${outcome.cookies.keys.joinToString()}"
                    )
                    Result.success()
                }

                YouTubeCookieRotationOutcome.NetworkError -> {
                    NPLogger.w(TAG, "rotation failed because the network request failed")
                    Result.retry()
                }

                is YouTubeCookieRotationOutcome.Rejected,
                YouTubeCookieRotationOutcome.Unchanged,
                YouTubeCookieRotationOutcome.Throttled,
                YouTubeCookieRotationOutcome.Skipped -> {
                    NPLogger.d(TAG, "rotation completed without a new cookie")
                    Result.success()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "rotation worker failed: ${error.message}")
            Result.retry()
        }
    }

    /**
     * 存档更新了也要同步回浏览器
     *
     * 不同步的话下次 WebView 刷新会拿旧的 *PSIDTS 覆盖掉刚轮换好的这份
     */
    private fun syncRotatedCookiesToWebView(rotatedCookies: Map<String, String>) {
        runCatching {
            applyYouTubeWebCookies(
                cookieManager = CookieManager.getInstance(),
                cookies = rotatedCookies,
                urls = YouTubeCookieSupport.webCookieReadUrls,
                skipExisting = false
            )
        }.onFailure { error ->
            NPLogger.d(TAG, "webview cookie sync unavailable in worker: ${error.message}")
        }
    }
}
