package moe.ouom.neriplayer.data.sync.webdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.util.NPLogger
import java.util.concurrent.TimeUnit

class WebDavSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WebDavSyncWorker"
        private const val WORK_NAME = "webdav_sync_work"
        private const val PERIODIC_WORK_NAME = "webdav_sync_periodic"
        private const val NOTIFICATION_CHANNEL_ID = "webdav_sync_channel"
        private const val NOTIFICATION_ID = 1002
        private const val DEFAULT_DELAY_MS = 5_000L

        fun scheduleDelayedSync(
            context: Context,
            triggerByUserAction: Boolean = false,
            markMutation: Boolean = false
        ) {
            if (markMutation) {
                SecureTokenStorage(context).markSyncMutation()
            }
            if (!triggerByUserAction) {
                val storage = WebDavStorage(context)
                if (!storage.isConfigured() || !storage.isAutoSyncEnabled()) {
                    return
                }
            }
            val syncRequest = OneTimeWorkRequestBuilder<WebDavSyncWorker>()
                .setInitialDelay(DEFAULT_DELAY_MS, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .setInputData(workDataOf("trigger_by_user_action" to triggerByUserAction))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, syncRequest)
        }

        fun schedulePeriodicSync(context: Context) {
            val syncRequest = PeriodicWorkRequestBuilder<WebDavSyncWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            ).addTag(PERIODIC_WORK_NAME).build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
        }

        fun cancelAllSync(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME)
            WorkManager.getInstance(context).cancelAllWorkByTag(PERIODIC_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val forceSync = inputData.getBoolean("force_sync", false)
        val triggerByUserAction = inputData.getBoolean("trigger_by_user_action", false)
        try {
            NPLogger.d(TAG, "Starting WebDAV sync...")

            val storage = WebDavStorage(applicationContext)
            if (!forceSync && !triggerByUserAction && !storage.isAutoSyncEnabled()) {
                NPLogger.d(TAG, "WebDAV auto sync is disabled")
                return@withContext Result.success()
            }
            if (!storage.isConfigured()) {
                NPLogger.d(TAG, "WebDAV not configured")
                return@withContext Result.success()
            }
            if (!hasValidatedNetwork()) {
                NPLogger.d(TAG, "No validated network available, retry later")
                return@withContext Result.retry()
            }

            val syncResult = WebDavSyncManager.getInstance(applicationContext).performSync()
            if (syncResult.isSuccess) {
                NPLogger.d(TAG, "WebDAV sync completed: ${syncResult.getOrNull()?.message}")
                Result.success()
            } else {
                val error = syncResult.exceptionOrNull()
                if (error is WebDavSyncInProgressException) {
                    return@withContext Result.success()
                }
                NPLogger.e(TAG, "WebDAV sync failed", error)
                if (forceSync || triggerByUserAction || error is WebDavAuthException) {
                    showErrorNotification(error)
                }
                if (error is WebDavAuthException) Result.failure() else Result.retry()
            }
        } catch (e: Exception) {
            NPLogger.e(TAG, "WebDAV sync worker error", e)
            if (forceSync || triggerByUserAction) {
                showErrorNotification(e)
            }
            Result.retry()
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val connectivityManager =
            applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showErrorNotification(error: Throwable?) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.webdav_sync_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = applicationContext.getString(R.string.webdav_sync_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)

        val errorMessage = when (error) {
            is WebDavAuthException -> applicationContext.getString(R.string.webdav_auth_failed)
            else -> error?.message ?: applicationContext.getString(R.string.webdav_sync_failed_message)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.webdav_sync_failed_title))
            .setContentText(errorMessage)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
