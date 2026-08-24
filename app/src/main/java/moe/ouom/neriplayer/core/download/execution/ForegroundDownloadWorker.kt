package moe.ouom.neriplayer.core.download.execution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.concurrent.TimeUnit

/** translates WorkManager events to the shared download host */
class ForegroundDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationId = inputData.getString(OPERATION_ID_KEY)
            ?.let(::normalizeDownloadOperationId)
            ?: return@withContext Result.failure()
        try {
            setForeground(createForegroundInfo(applicationContext, operationId))
            DownloadExecutionHosts.default.execute(applicationContext, operationId)
        } catch (cancellation: CancellationException) {
            DownloadExecutionHosts.default.stop(
                context = applicationContext,
                operationId = operationId,
                preventReschedule = false
            )
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                "NERI-DownloadWorker",
                "下载 Worker 执行异常: operationId=$operationId, error=${error.message}",
                error
            )
            return@withContext Result.retry()
        }.toWorkerResult()
    }

    companion object {
        internal const val OPERATION_ID_KEY = "operation_id"
        private const val WORK_NAME_PREFIX = "download_execution_"
        private const val WORK_TAG_PREFIX = "download_execution_operation_"
        private const val CHANNEL_ID = "download_execution"
        private const val NOTIFICATION_ID = 0x4e50_0001

        fun schedule(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            return runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        uniqueWorkName(normalizedId),
                        ExistingWorkPolicy.REPLACE,
                        buildRequest(normalizedId)
                    )
                true
            }.getOrDefault(false)
        }

        /** gives a UIDT job a short head start while keeping a durable fallback */
        fun scheduleFallback(
            context: Context,
            operationId: String
        ): Boolean {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
            return runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        fallbackWorkName(normalizedId),
                        ExistingWorkPolicy.REPLACE,
                        buildFallbackRequest(normalizedId)
                    )
                true
            }.getOrDefault(false)
        }

        fun cancel(
            context: Context,
            operationId: String
        ) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            runCatching {
                val workManager = WorkManager.getInstance(context.applicationContext)
                workManager.cancelUniqueWork(uniqueWorkName(normalizedId))
                workManager.cancelUniqueWork(fallbackWorkName(normalizedId))
            }
        }

        internal fun cancelFallback(
            context: Context,
            operationId: String
        ) {
            val normalizedId = normalizeDownloadOperationId(operationId) ?: return
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(fallbackWorkName(normalizedId))
            }
        }

        internal fun buildRequest(operationId: String): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<ForegroundDownloadWorker>()
                .setInputData(workDataOf(OPERATION_ID_KEY to operationId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_TAG_PREFIX + operationId)
                .build()
        }

        internal fun buildFallbackRequest(operationId: String): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<ForegroundDownloadWorker>()
                .setInitialDelay(UIDT_START_GRACE_MS, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(OPERATION_ID_KEY to operationId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_TAG_PREFIX + "fallback_" + operationId)
                .build()
        }

        internal fun uniqueWorkName(operationId: String): String {
            return WORK_NAME_PREFIX + operationId
        }

        private fun fallbackWorkName(operationId: String): String {
            return WORK_NAME_PREFIX + "fallback_" + operationId
        }

        private const val UIDT_START_GRACE_MS = 3_000L
    }
}

private fun createForegroundInfo(
    context: Context,
    operationId: String
): ForegroundInfo {
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(
        NotificationChannel(
            "download_execution",
            context.getString(R.string.download_execution_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
    )
    val notification = NotificationCompat.Builder(context, "download_execution")
        .setSmallIcon(R.drawable.ic_notification_small)
        .setContentTitle(context.getString(R.string.download_execution_notification_title))
        .setContentText(
            context.getString(
                R.string.download_execution_notification_content,
                operationId
            )
        )
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    } else {
        ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
    }
}

private const val FOREGROUND_NOTIFICATION_ID = 0x4e50_0001

internal fun DownloadExecutionResult.toWorkerResult(): ListenableWorker.Result {
    return when (this) {
        DownloadExecutionResult.Accepted,
        DownloadExecutionResult.AlreadyHandled,
        DownloadExecutionResult.Cancelled,
        DownloadExecutionResult.UserStopped -> ListenableWorker.Result.success()
        DownloadExecutionResult.MissingOperation -> ListenableWorker.Result.retry()
        DownloadExecutionResult.Retry -> ListenableWorker.Result.retry()
        is DownloadExecutionResult.Failed -> ListenableWorker.Result.retry()
    }
}
