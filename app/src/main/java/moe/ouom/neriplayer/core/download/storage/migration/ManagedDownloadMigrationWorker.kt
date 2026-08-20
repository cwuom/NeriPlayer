package moe.ouom.neriplayer.core.download.storage.migration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.settings.SettingsRepository

class ManagedDownloadMigrationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(createForegroundInfo())
            val fromDirectoryUri = inputData.getString(KEY_FROM_DIRECTORY_URI)
            val toDirectoryUri = inputData.getString(KEY_TO_DIRECTORY_URI)
            val targetLabel = inputData.getString(KEY_TARGET_LABEL)
            val releasePreviousPermission = inputData.getBoolean(KEY_RELEASE_PREVIOUS_PERMISSION, false)
            val minimumSourceEntryCount = inputData
                .getInt(KEY_MINIMUM_SOURCE_ENTRY_COUNT, 0)
                .coerceAtLeast(0)
            val migrationResult = ManagedDownloadStorage.migrateManagedDownloads(
                context = applicationContext,
                fromDirectoryUri = fromDirectoryUri,
                toDirectoryUri = toDirectoryUri,
                minimumSourceEntryCount = minimumSourceEntryCount,
                onTargetVerified = {
                    SettingsRepository(applicationContext).setDownloadDirectory(
                        uri = toDirectoryUri,
                        label = targetLabel
                    )
                    ManagedDownloadStorage.updateConfiguredTreeUri(toDirectoryUri)
                    ManagedDownloadStorage.updateCustomDirectoryLabel(targetLabel)
                }
            )
            if (!migrationResult.canSwitchDirectory) {
                return@withContext Result.failure(
                    workDataOf(
                        KEY_SKIPPED_FILES to migrationResult.skippedFiles,
                        KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles
                    )
                )
            }
            GlobalDownloadManager.scanLocalFiles(applicationContext, forceRefresh = true)
            if (releasePreviousPermission && migrationResult.canReleasePreviousPermission) {
                ManagedDownloadStorage.releasePersistedDirectoryPermission(
                    applicationContext,
                    fromDirectoryUri
                )
            }
            Result.success(
                workDataOf(
                    KEY_MOVED_FILES to migrationResult.movedFiles,
                    KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: ManagedDownloadMigrationException) {
            if (error.retryable && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (error.message ?: "")))
            }
        } catch (error: IOException) {
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (error.message ?: "")))
        } catch (error: Exception) {
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (error.message ?: "")))
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    applicationContext.getString(R.string.settings_download_directory_migrating),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(applicationContext.getString(R.string.settings_download_directory_migrating))
            .setContentText(applicationContext.getString(R.string.settings_download_directory_migrating_desc))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "managed_download_migration"
        const val KEY_FROM_DIRECTORY_URI = "from_directory_uri"
        const val KEY_TO_DIRECTORY_URI = "to_directory_uri"
        const val KEY_TARGET_LABEL = "target_label"
        const val KEY_RELEASE_PREVIOUS_PERMISSION = "release_previous_permission"
        const val KEY_MINIMUM_SOURCE_ENTRY_COUNT = "minimum_source_entry_count"
        const val KEY_MOVED_FILES = "moved_files"
        const val KEY_SKIPPED_FILES = "skipped_files"
        const val KEY_CLEANUP_FAILED_FILES = "cleanup_failed_files"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val NOTIFICATION_CHANNEL_ID = "managed_download_migration"
        private const val NOTIFICATION_ID = 1004
        private const val MAX_RETRY_ATTEMPTS = 2

        suspend fun enqueueOrGetActiveWorkId(
            context: Context,
            fromDirectoryUri: String?,
            toDirectoryUri: String?,
            targetLabel: String,
            releasePreviousPermission: Boolean,
            minimumSourceEntryCount: Int = GlobalDownloadManager.downloadedSongs.value.size
        ): String = withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                .firstOrNull { info -> !info.state.isFinished }
                ?.id
                ?.toString()
                ?.let { return@withContext it }
            val request = OneTimeWorkRequestBuilder<ManagedDownloadMigrationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_FROM_DIRECTORY_URI to fromDirectoryUri,
                        KEY_TO_DIRECTORY_URI to toDirectoryUri,
                        KEY_TARGET_LABEL to targetLabel,
                        KEY_RELEASE_PREVIOUS_PERMISSION to releasePreviousPermission,
                        KEY_MINIMUM_SOURCE_ENTRY_COUNT to minimumSourceEntryCount.coerceAtLeast(0)
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    10L,
                    TimeUnit.SECONDS
                )
                .setInitialDelay(0L, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            ).result.get()
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                .firstOrNull { info -> !info.state.isFinished }
                ?.id
                ?.toString()
                ?: request.id.toString()
        }
    }
}
