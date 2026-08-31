package moe.ouom.neriplayer.core.download.execution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** translates WorkManager events to the shared download host */
class ForegroundDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rawOperationId = inputData.getString(OPERATION_ID_KEY)
        val operationId = rawOperationId?.let(::normalizeDownloadOperationId)
        if (rawOperationId != null && operationId == null) {
            return@withContext Result.failure()
        }
        val executionId = operationId ?: PUMP_OPERATION_ID
        var enteredHostExecution = false
        try {
            setForeground(createForegroundInfo(applicationContext, executionId))
            enteredHostExecution = true
            if (operationId == null) {
                val pumpResult = DownloadExecutionHosts.pump(applicationContext)
                val workerResult = pumpResult.toWorkerResult()
                markPumpFinished(
                    context = applicationContext,
                    workWillRetry = pumpResult == DownloadExecutionPumpResult.Retry
                )
                return@withContext workerResult
            }
            DownloadExecutionHosts.default.execute(applicationContext, operationId)
        } catch (cancellation: CancellationException) {
            operationId?.let { normalizedId ->
                DownloadExecutionHosts.default.stop(
                    context = applicationContext,
                    operationId = normalizedId,
                    preventReschedule = false
                )
            }
            if (operationId == null) {
                markPumpFinished(
                    context = applicationContext,
                    workWillRetry = true
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            if (!enteredHostExecution && operationId != null) {
                DownloadExecutionHosts.releaseHandoffAdmissionIfIdle(
                    context = applicationContext,
                    operationId = operationId
                )
            }
            NPLogger.w(
                "NERI-DownloadWorker",
                "下载 Worker 执行异常: operationId=$executionId, error=${error.message}",
                error
            )
            if (operationId == null) {
                markPumpFinished(
                    context = applicationContext,
                    workWillRetry = true
                )
            }
            return@withContext Result.retry()
        }.toWorkerResult()
    }

    companion object {
        internal const val OPERATION_ID_KEY = "operation_id"
        internal const val PUMP_WORK_NAME = "download_execution_pump"
        internal const val PUMP_OPERATION_ID = "download-pump"
        private const val PUMP_WORK_TAG = "download_execution_pump"
        private const val PUMP_RETRY_BACKOFF_MS = 10_000L
        private val PUMP_SUCCESSOR_WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE
        private const val WORK_NAME_PREFIX = "download_execution_"
        private const val WORK_TAG_PREFIX = "download_execution_operation_"
        private const val ALL_DOWNLOAD_WORK_TAG = "download_execution_all"
        private const val CHANNEL_ID = "download_execution"
        internal val fallbackExistingWorkPolicy = ExistingWorkPolicy.KEEP
        internal val pumpExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
        private val pumpScheduleRequested = AtomicBoolean(false)
        private val pumpSuccessorRequested = AtomicBoolean(false)

        fun schedule(
            context: Context,
            operationId: String
        ): Boolean {
            normalizeDownloadOperationId(operationId) ?: return false
            return schedulePump(context)
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
                        fallbackExistingWorkPolicy,
                        buildFallbackRequest(normalizedId)
                    )
                true
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-DownloadWorker",
                    "UIDT fallback 调度失败，保留 Room operation 等待恢复: " +
                        "operationId=$normalizedId, error=${error.message}",
                    error
                )
            }.getOrDefault(false)
        }

        /** 所有新下载共用一个持久泵，operation 载荷始终保存在 Room */
        internal fun schedulePump(
            context: Context,
            initialDelayMs: Long = 0L
        ): Boolean {
            if (!pumpScheduleRequested.compareAndSet(false, true)) {
                pumpSuccessorRequested.set(true)
                return true
            }
            val delayMs = initialDelayMs.coerceAtLeast(0L)
            return runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        PUMP_WORK_NAME,
                        pumpExistingWorkPolicy,
                        buildPumpRequest(delayMs)
                    )
                true
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-DownloadWorker",
                    "全局下载泵调度失败，保留 Room operation 等待恢复: ${error.message}",
                    error
                )
                pumpScheduleRequested.set(false)
                pumpSuccessorRequested.set(false)
            }.getOrDefault(false)
        }

        private fun markPumpFinished(
            context: Context,
            workWillRetry: Boolean
        ) {
            pumpScheduleRequested.set(false)
            val successorRequested = pumpSuccessorRequested.getAndSet(false)
            if (successorRequested && !workWillRetry) {
                schedulePumpSuccessor(context)
            }
        }

        private fun schedulePumpSuccessor(context: Context): Boolean {
            if (!pumpScheduleRequested.compareAndSet(false, true)) {
                return true
            }
            return runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        PUMP_WORK_NAME,
                        PUMP_SUCCESSOR_WORK_POLICY,
                        buildPumpRequest()
                    )
                true
            }.onFailure { error ->
                pumpScheduleRequested.set(false)
                NPLogger.w(
                    "NERI-DownloadWorker",
                    "下载泵 successor 调度失败，保留 Room operation: ${error.message}",
                    error
                )
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

        fun cancelAll(
            context: Context,
            operationIds: Collection<String>
        ) {
            val normalizedIds = operationIds.mapNotNull(::normalizeDownloadOperationId).toSet()
            if (normalizedIds.isEmpty()) return
            runCatching {
                val workManager = WorkManager.getInstance(context.applicationContext)
                normalizedIds.forEach { operationId ->
                    workManager.cancelUniqueWork(uniqueWorkName(operationId))
                    workManager.cancelUniqueWork(fallbackWorkName(operationId))
                }
            }
        }

        fun cancelAllOwned(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .cancelAllWorkByTag(ALL_DOWNLOAD_WORK_TAG)
            }
            pumpScheduleRequested.set(false)
            pumpSuccessorRequested.set(false)
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
            // 旧 fallback 只属于当前 operation，结束后唤醒共享泵处理其他任务
            schedulePump(context)
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
                .addTag(ALL_DOWNLOAD_WORK_TAG)
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
                .addTag(ALL_DOWNLOAD_WORK_TAG)
                .build()
        }

        internal fun buildPumpRequest(initialDelayMs: Long = 0L): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<ForegroundDownloadWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    PUMP_RETRY_BACKOFF_MS,
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(PUMP_WORK_TAG)
                .addTag(ALL_DOWNLOAD_WORK_TAG)
            if (initialDelayMs > 0L) {
                builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            }
            return builder.build()
        }

        internal fun uniqueWorkName(operationId: String): String {
            return WORK_NAME_PREFIX + operationId
        }

        internal fun fallbackWorkName(operationId: String): String {
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
    val notificationId = DownloadExecutionNotificationIds.foreground(operationId)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    } else {
        ForegroundInfo(notificationId, notification)
    }
}

internal object DownloadExecutionNotificationIds {
    internal const val FOREGROUND_MIN = 0x4000_0000
    internal const val FOREGROUND_MAX = 0x4fff_ffff
    internal const val UIDT_MIN = 0x5000_0000
    internal const val UIDT_MAX = 0x5fff_ffff
    private const val PARTITION_MASK = 0x0fff_ffff

    fun foreground(operationId: String): Int {
        return inPartition(operationId, FOREGROUND_MIN)
    }

    fun uidt(operationId: String): Int {
        return inPartition(operationId, UIDT_MIN)
    }

    private fun inPartition(operationId: String, partitionStart: Int): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(operationId.toByteArray(Charsets.UTF_8))
        var value = 0
        repeat(4) { index ->
            value = (value shl 8) or (digest[index].toInt() and 0xff)
        }
        return partitionStart + (value and PARTITION_MASK)
    }
}

internal fun DownloadExecutionResult.toWorkerResult(): ListenableWorker.Result {
    return when (this) {
        DownloadExecutionResult.Accepted,
        DownloadExecutionResult.AlreadyHandled,
        DownloadExecutionResult.Cancelled,
        DownloadExecutionResult.UserStopped,
        DownloadExecutionResult.UserActionRequired,
        DownloadExecutionResult.NetworkPolicyWaiting,
        DownloadExecutionResult.MissingOperation -> ListenableWorker.Result.success()
        DownloadExecutionResult.Retry -> ListenableWorker.Result.retry()
        is DownloadExecutionResult.Failed -> ListenableWorker.Result.retry()
    }
}

internal fun DownloadExecutionPumpResult.toWorkerResult(): ListenableWorker.Result {
    return when (this) {
        DownloadExecutionPumpResult.Completed -> ListenableWorker.Result.success()
        DownloadExecutionPumpResult.Retry -> ListenableWorker.Result.retry()
    }
}
