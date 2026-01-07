package moe.ouom.neriplayer.data.github

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
 * File: moe.ouom.neriplayer.data.github/GitHubSyncWorker
 * Created: 2025/1/7
 */

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.NPLogger
import java.util.concurrent.TimeUnit

/**
 * GitHub 同步 Worker
 * 使用WorkManager实现后台自动同步
 */
class GitHubSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GitHubSyncWorker"
        private const val WORK_NAME = "github_sync_work"
        private const val PERIODIC_WORK_NAME = "github_sync_periodic"
        private const val NOTIFICATION_CHANNEL_ID = "github_sync_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * 调度延迟同步(5秒后执行)
         * @param triggerByUserAction 是否由用户操作触发（如果是，则忽略自动同步开关）
         */
        fun scheduleDelayedSync(context: Context, triggerByUserAction: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<GitHubSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .setInputData(workDataOf("trigger_by_user_action" to triggerByUserAction))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP, // 如果已有任务在执行，保持现有任务
                    syncRequest
                )
        }

        /**
         * 调度定期同步(每小时)
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<GitHubSyncWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES // 灵活间隔
            )
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
        }

        /**
         * 取消所有同步任务
         */
        fun cancelAllSync(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME)
            WorkManager.getInstance(context).cancelAllWorkByTag(PERIODIC_WORK_NAME)
        }

        /**
         * 立即执行同步（无论是否开启自动同步）
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<GitHubSyncWorker>()
                .setConstraints(constraints)
                .addTag("sync_now")
                .setInputData(workDataOf("force_sync" to true))
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            NPLogger.d(TAG, "Starting GitHub sync...")

            val storage = SecureTokenStorage(applicationContext)
            val forceSync = inputData.getBoolean("force_sync", false)
            val triggerByUserAction = inputData.getBoolean("trigger_by_user_action", false)

            // 如果是强制同步或用户操作触发，跳过自动同步检查
            if (!forceSync && !triggerByUserAction) {
                // 检查是否启用自动同步
                if (!storage.isAutoSyncEnabled()) {
                    NPLogger.d(TAG, "Auto sync is disabled")
                    return@withContext Result.success()
                }
            }

            // 检查是否已配置
            if (!storage.isConfigured()) {
                NPLogger.d(TAG, "GitHub not configured")
                return@withContext Result.success()
            }

            // 执行同步
            val syncManager = GitHubSyncManager.getInstance(applicationContext)
            val syncResult = syncManager.performSync()

            if (syncResult.isSuccess) {
                val result = syncResult.getOrNull()
                NPLogger.d(TAG, "Sync completed: ${result?.message}")
                Result.success()
            } else {
                val error = syncResult.exceptionOrNull()
                NPLogger.e(TAG, "Sync failed", error)

                // 显示错误通知
                showErrorNotification(error)

                // Token过期时不重试，其他错误重试
                if (error is TokenExpiredException) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }

        } catch (e: Exception) {
            NPLogger.e(TAG, "Sync worker error", e)
            showErrorNotification(e)
            Result.retry()
        }
    }

    /**
     * 显示同步错误通知
     */
    private fun showErrorNotification(error: Throwable?) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 创建通知渠道（Android 8.0+）
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "GitHub同步",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "GitHub同步状态通知"
        }
        notificationManager.createNotificationChannel(channel)

        val errorMessage = when (error) {
            is TokenExpiredException -> "Token 已过期，请重新配置"
            else -> error?.message ?: "同步失败"
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("GitHub 同步失败")
            .setContentText(errorMessage)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
