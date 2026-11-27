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

import moe.ouom.neriplayer.platform.youtube.api.auth.YouTubeCookieSupport
import moe.ouom.neriplayer.platform.youtube.auth.SharedPreferencesYouTubeCookieRotationStateStore
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeCookieRotationOutcome
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeCookieRotator
import moe.ouom.neriplayer.platform.youtube.auth.hasYouTubeRotationPrerequisites
import moe.ouom.neriplayer.platform.youtube.auth.mergeYouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.auth.web.applyYouTubeWebCookies
import moe.ouom.neriplayer.platform.youtube.auth.youtubeAuthRotationMutex
import android.content.Context
import android.webkit.CookieManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.network.DataHttpClients
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.network.weblogin.ForegroundWebLoginGuard
import java.util.concurrent.TimeUnit

private const val MAX_ROTATION_RETRIES = 2

internal fun shouldRetryPeriodicRotation(outcome: YouTubeCookieRotationOutcome, attempt: Int): Boolean =
    attempt < MAX_ROTATION_RETRIES && when (outcome) {
        YouTubeCookieRotationOutcome.NetworkError -> true
        is YouTubeCookieRotationOutcome.Rejected -> outcome.code == 408 || outcome.code == 429 || outcome.code in 500..599
        YouTubeCookieRotationOutcome.Throttled -> attempt > 0
        else -> false
    }

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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .addTag(PERIODIC_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                // 保留原排期，同时让升级前的任务采用当前退避策略
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelPeriodicRotation(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val host = YouTubeRotationHosts.current
            if (host == null || !host.isInitialized()) {
                NPLogger.d(TAG, "skip rotation: app container is not initialized")
                return Result.success()
            }
            if (!host.isYouTubeEnabled()) {
                NPLogger.d(TAG, "skip rotation: YouTube is disabled")
                return Result.success()
            }
            val authStore = host.authStore
            val outcome = youtubeAuthRotationMutex.withLock {
                if (ForegroundWebLoginGuard.isActive) return retryIfAvailable()
                val snapshot = authStore.captureAuthSnapshot()
                val auth = snapshot.auth
                if (!hasYouTubeRotationPrerequisites(auth.cookies)) return Result.success()
                val result = YouTubeCookieRotator(
                    httpClientProvider = { DataHttpClients.shared },
                    stateStore = SharedPreferencesYouTubeCookieRotationStateStore(
                        applicationContext
                    )
                ).rotateDetailed(
                    cookies = auth.cookies,
                    userAgent = auth.userAgent
                )
                if (ForegroundWebLoginGuard.isActive) return retryIfAvailable()
                if (result is YouTubeCookieRotationOutcome.Rotated) {
                    val updated = authStore.updateAuthIfCurrent(snapshot) { current ->
                        mergeYouTubeAuthBundle(
                            base = current,
                            observedCookies = result.cookies,
                            savedAt = System.currentTimeMillis()
                        )
                    } ?: return Result.success()
                    authStore.runIfCurrent(updated) {
                        if (!ForegroundWebLoginGuard.isActive) syncRotatedCookiesToWebView(result.cookies)
                    }
                }
                result
            }
            if (shouldRetryPeriodicRotation(outcome, runAttemptCount)) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "rotation worker failed: ${error.message}")
            retryIfAvailable()
        }
    }

    private fun retryIfAvailable(): Result =
        if (runAttemptCount < MAX_ROTATION_RETRIES) Result.retry() else Result.success()

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
